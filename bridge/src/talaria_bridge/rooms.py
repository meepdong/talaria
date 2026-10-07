"""Group chats: Hermes's rooms (spec/README.md §18.2), through the doorway to `hermes serve` (hermes_serve.py).

A room is two to six bots (and the owner's own assistant, Hermes's default profile) in one conversation. Hermes
runs it on its backend: a message from the owner starts rounds of member turns, members pass when they have nothing
to add, and the room keeps going with no device connected. A room is an event log (`groups.log`); Hermes doesn't
push it to clients, so the bridge watches: every couple of seconds for a room that is busy or open on a device,
every half minute for the rest (`groups.list`), and tells devices what changed (`rooms.update`, `rooms.changed`).
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import re
import secrets
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field

from .hermes_serve import HermesBackend, ServeError, ServeUnavailable
from .protocol import messages as m

log = logging.getLogger("talaria.rooms")

FAST_S = 2.0  # how often a busy or watched room is looked at
SLOW_S = 30.0  # how often the list of rooms is
WATCH_S = 600  # a room opened on a device is watched this long
KEEP = 500  # messages kept per room
PAGE = 60  # messages rooms.open returns
ASSISTANT = "assistant"  # rooms.create's name for the owner's own assistant (the default profile)
MAX_TEXT = 32000
NOTE_KINDS = ("turn.failed", "room.stop_requested", "room.renamed", "member.unavailable")


class RoomError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass
class _Room:
    raw: dict  # Hermes's room row
    cursor: int = 0  # the last seq read
    messages: list[dict] = field(default_factory=list)  # oldest first, at most KEEP
    loaded: bool = False  # the whole log has been read once
    working: bool = False
    approvals: list[dict] = field(default_factory=list)  # as devices see them, with the action kept privately
    actions: dict[str, dict] = field(default_factory=dict)  # approval_id -> Hermes's pending action
    settled: list[dict] = field(default_factory=list)  # recent turn.settled payloads, for guessing who asks
    watched_until: float = 0.0


def _slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")[:40] or "member"


class Rooms:
    def __init__(self, backend: HermesBackend, bots, *, assistant_name: str = "Hermes", fast_s: float = FAST_S,
                 slow_s: float = SLOW_S, now: Callable[[], float] = time.time):
        self.backend = backend
        self.bots = bots  # bots.Bots: the roster, for members' names and rooms.create
        self.assistant_name = assistant_name
        self.fast_s = fast_s
        self.slow_s = slow_s
        self.now = now
        self.broadcast: Callable[[dict], Awaitable[None]] | None = None  # set by the chat service
        self._rooms: dict[str, _Room] = {}
        self._listed: list[dict] | None = None  # what devices last got in rooms.changed
        self._lock = asyncio.Lock()

    async def _rpc(self, method: str, params: dict, **kw):
        try:
            return await self.backend.rpc(method, params, **kw)
        except ServeUnavailable as exc:
            raise RoomError(m.AGENT_UNAVAILABLE, f"Hermes's backend isn't connected: {exc}") from None
        except ServeError as exc:
            low = exc.message.lower()
            code = m.NOT_FOUND if "not found" in low or "no longer pending" in low or "unknown room" in low else m.INVALID_PARAMS
            raise RoomError(code, exc.message[:500]) from None

    async def _notify(self, method: str, params: dict) -> None:
        if self.broadcast is not None:
            with contextlib.suppress(Exception):
                await self.broadcast(m.notification(method, params))

    # what devices see

    def _member_name(self, room: dict, member_id: str | None) -> str:
        for mem in room.get("members") or []:
            if mem.get("member_id") == member_id:
                return self._name_of(mem)
        return member_id or "A member"

    def _name_of(self, mem: dict) -> str:
        profile = mem.get("profile")
        if profile == "default":
            return self.assistant_name
        if self.bots is not None and profile:
            known = next((b for b in self.bots.roster() if b["profile"] == profile), None)
            if known is not None:
                return known["name"]
        return str(mem.get("display_name") or profile or mem.get("member_id") or "A member")[:100]

    def _summary(self, rid: str, r: _Room) -> dict:
        raw = r.raw
        members = []
        for mem in raw.get("members") or []:
            item = {"member_id": str(mem.get("member_id"))[:120], "name": self._name_of(mem),
                    "handle": str(mem.get("handle") or mem.get("member_id"))[:80]}
            if mem.get("profile") and mem.get("profile") != "default":
                item["bot_id"] = f"bot:{mem['profile']}"
            members.append(item)
        out = {"id": rid, "name": str(raw.get("name") or "Group chat")[:200], "members": members,
               "updated_at": int(raw.get("updated_at") or 0), "working": r.working, "needs_you": self._needs_you(r)}
        last = next((x for x in reversed(r.messages) if x["kind"] != "note"), None)
        if last is not None:
            out["preview"] = {"speaker": last["speaker"], "text": last["text"][:300]}
        return out

    @staticmethod
    def _needs_you(r: _Room) -> bool:
        if r.approvals:
            return True
        for msg in reversed(r.messages):
            if msg["kind"] == "user":
                return False
            if msg["kind"] == "member" and re.search(r"(?<![\w@])@user\b", msg["text"]):
                return True
        return False

    def _message(self, room: dict, e: dict) -> dict | None:
        kind, p = e.get("kind"), e.get("payload") if isinstance(e.get("payload"), dict) else {}
        out: dict = {"seq": int(e.get("seq") or 0), "at": int(e.get("created_at") or 0)}
        if kind == "message.user":
            out.update(kind="user", speaker="You", text=str(p.get("text") or ""))
        elif kind == "message.member":
            out.update(kind="member", speaker=self._member_name(room, p.get("member_id")), text=str(p.get("text") or ""),
                       member_id=str(p.get("member_id"))[:120])
        elif kind == "turn.failed":
            first = str(p.get("error") or "an error").strip().splitlines()[0][:300]
            out.update(kind="note", speaker="Room", text=f"{self._member_name(room, p.get('member_id'))} couldn't answer: {first}")
        elif kind == "room.stop_requested":
            out.update(kind="note", speaker="Room", text="Stopped. Mention a member (or @all) to go on.")
        elif kind == "room.renamed":
            name = p.get("name") or p.get("new_name")
            out.update(kind="note", speaker="Room", text=f"Renamed to {name}" if isinstance(name, str) else "The room was renamed")
        elif kind == "member.unavailable":
            out.update(kind="note", speaker="Room", text=f"{self._member_name(room, p.get('member_id'))} isn't available")
        else:
            return None
        if isinstance(p.get("thread_id"), str) and p["thread_id"]:
            out["thread_id"] = p["thread_id"][:120]
        out["text"] = out["text"][:40000]
        return out

    # reading Hermes

    async def _read(self, rid: str, r: _Room) -> list[dict]:
        """New events since the cursor (all of them the first time): the new messages, oldest first."""
        new: list[dict] = []
        for _ in range(40):  # at most 40 pages per look
            page = await self._rpc("groups.log", {"room_id": rid, "since_seq": r.cursor, "limit": 200})
            for e in page.get("events") or []:
                if e.get("kind") == "turn.settled" and isinstance(e.get("payload"), dict):
                    r.settled = (r.settled + [e["payload"]])[-12:]
                msg = self._message(r.raw, e)
                if msg is not None:
                    new.append(msg)
            r.cursor = int(page.get("cursor") or r.cursor)
            if not page.get("has_more"):
                break
        r.messages = (r.messages + new)[-KEEP:]
        r.loaded = True
        return new

    async def _status(self, rid: str, r: _Room) -> bool:
        """The room's driver state; True when it changed."""
        state = await self._rpc("groups.state", {"room_id": rid})
        if isinstance(state.get("room"), dict):
            r.raw = state["room"]
        status = state.get("driver_status") or {}
        working = bool(status.get("working"))
        actions, approvals = {}, []
        for a in status.get("pending_actions") or []:
            if not isinstance(a, dict) or a.get("kind") != "approval" or not a.get("request_id"):
                continue
            ask = a.get("approval") if isinstance(a.get("approval"), dict) else {}
            aid = str(a["request_id"])[:200]
            actions[aid] = a
            item = {"approval_id": aid, "member": self._member_name(r.raw, self._asking(r)), "choices": ["once", "deny"]}
            for key in ("command", "description"):
                if isinstance(ask.get(key), str) and ask[key]:
                    item[key] = ask[key][:2000]
            approvals.append(item)
        changed = working != r.working or approvals != r.approvals
        r.working, r.approvals, r.actions = working, approvals, actions
        return changed

    def _asking(self, r: _Room) -> str | None:
        """Whose turn it probably is: turns go round the roster in order, so the one after the last that settled."""
        members = [mem.get("member_id") for mem in r.raw.get("members") or []]
        if not members:
            return None
        if not r.settled:
            return members[0]
        last = r.settled[-1].get("member_index")
        return members[(int(last) + 1) % len(members)] if isinstance(last, int) else members[0]

    async def _look(self, rid: str, r: _Room) -> None:
        new = await self._read(rid, r)
        changed = await self._status(rid, r)
        if new or changed:
            await self._notify("rooms.update", {"room_id": rid, "messages": new, "working": r.working,
                                                "approvals": r.approvals, "needs_you": self._needs_you(r)})

    async def refresh(self) -> None:
        listed = await self._rpc("groups.list", {})
        rooms = [x for x in listed.get("rooms") or [] if isinstance(x, dict) and x.get("room_id") and not x.get("disbanded_at")]
        seen = set()
        for raw in rooms:
            rid = str(raw["room_id"])
            seen.add(rid)
            r = self._rooms.get(rid)
            if r is None:
                r = self._rooms[rid] = _Room(raw)
                await self._read(rid, r)
                await self._status(rid, r)
            else:
                r.raw = {**r.raw, **raw}
                if int(raw.get("latest_seq") or 0) > r.cursor:
                    await self._look(rid, r)
        for rid in [x for x in self._rooms if x not in seen]:
            del self._rooms[rid]
        summary = self.list()["rooms"]
        if summary != self._listed:
            self._listed = summary
            await self._notify("rooms.changed", {"rooms": summary})

    async def run(self) -> None:
        last_list = 0.0
        while True:
            try:
                if self.backend.connected:
                    async with self._lock:
                        if self.now() - last_list >= self.slow_s:
                            await self.refresh()
                            last_list = self.now()
                        for rid, r in list(self._rooms.items()):
                            if r.working or r.approvals or r.watched_until > self.now():
                                await self._look(rid, r)
            except asyncio.CancelledError:
                raise
            except RoomError as exc:
                log.info("rooms: %s", exc.message)
            except Exception:  # noqa: BLE001 (keep watching whatever one round hit)
                log.exception("rooms watch failed")
            await asyncio.sleep(self.fast_s)

    # what devices call

    def list(self) -> dict:
        rooms = sorted((self._summary(rid, r) for rid, r in self._rooms.items()), key=lambda x: -x["updated_at"])
        return {"rooms": rooms, "available": self.backend.connected}

    def _room(self, p: dict) -> tuple[str, _Room]:
        rid = p.get("room_id")
        if not isinstance(rid, str) or rid not in self._rooms:
            raise RoomError(m.NOT_FOUND, "Unknown group chat")
        return rid, self._rooms[rid]

    async def handle(self, method: str, p: dict) -> dict:
        if method == "rooms.list":
            return self.list()
        if method == "rooms.create":
            return await self.create(p)
        async with self._lock:
            rid, r = self._room(p)
            if method == "rooms.open":
                return await self.open(rid, r, p)
            if method == "rooms.send":
                return await self.send(rid, r, p)
            if method == "rooms.stop":
                out = await self._rpc("groups.stop", {"room_id": rid, "cancel_id": "stop-" + secrets.token_hex(6)})
                await self._look(rid, r)
                return {"stopped": int(out.get("cancelled") or 0)}
            if method == "rooms.approve":
                return await self.approve(rid, r, p)
        raise RoomError(m.METHOD_NOT_FOUND, f"Method not found: {method}")

    async def open(self, rid: str, r: _Room, p: dict) -> dict:
        r.watched_until = self.now() + WATCH_S
        await self._read(rid, r)
        await self._status(rid, r)
        before = p.get("before")
        pool = [x for x in r.messages if not isinstance(before, int) or x["seq"] < before]
        page = pool[-PAGE:]
        return {"room": self._summary(rid, r), "messages": page, "has_more": len(pool) > len(page),
                "approvals": r.approvals}

    async def send(self, rid: str, r: _Room, p: dict) -> dict:
        text = p.get("text")
        if not (isinstance(text, str) and text.strip() and len(text) <= MAX_TEXT):
            raise RoomError(m.INVALID_PARAMS, f"text must be 1 to {MAX_TEXT} characters")
        thread = p.get("thread_id")
        if thread is not None and not (isinstance(thread, str) and re.fullmatch(r"[A-Za-z0-9_.:-]{1,120}", thread)):
            raise RoomError(m.INVALID_PARAMS, "thread_id must be an id from a message")
        out = await self._rpc("groups.send", {"room_id": rid, "event_id": "ev-" + secrets.token_hex(10),
                                              "payload": {"text": text.strip(), "thread_id": thread or "th-" + secrets.token_hex(6)}})
        r.watched_until = max(r.watched_until, self.now() + WATCH_S)
        r.working = True
        msg = self._message(r.raw, out["event"]) if isinstance(out.get("event"), dict) else None
        if msg is None:
            raise RoomError(m.INTERNAL_ERROR, "Hermes didn't take the message")
        await self._look(rid, r)
        return {"message": msg}

    async def approve(self, rid: str, r: _Room, p: dict) -> dict:
        aid, choice = p.get("approval_id"), p.get("choice")
        if choice not in ("once", "deny"):
            raise RoomError(m.INVALID_PARAMS, "choice must be once or deny")
        action = r.actions.get(aid) if isinstance(aid, str) else None
        if action is None:
            raise RoomError(m.NOT_FOUND, "That approval is no longer waiting")
        guess = self._asking(r)
        members = [mem.get("member_id") for mem in r.raw.get("members") or []]
        for member in [guess] + [x for x in members if x != guess]:  # Hermes doesn't say who asked: the right one takes it
            try:
                await self._rpc("groups.approve", {"room_id": rid, "member_id": member, "task_id": action.get("task_id"),
                                                   "execution_generation": int(action.get("execution_generation") or 0),
                                                   "choice": choice, "request_id": aid})
            except RoomError as exc:
                if exc.code == m.NOT_FOUND:
                    continue
                raise
            await self._look(rid, r)
            return {}
        raise RoomError(m.NOT_FOUND, "That approval is no longer waiting")

    async def create(self, p: dict) -> dict:
        name, wanted = p.get("name"), p.get("members")
        if not (isinstance(name, str) and name.strip() and len(name) <= 120):
            raise RoomError(m.INVALID_PARAMS, "name must be 1 to 120 characters")
        if not (isinstance(wanted, list) and 2 <= len(wanted) <= 6 and len(set(map(str, wanted))) == len(wanted)):
            raise RoomError(m.INVALID_PARAMS, "members must be 2 to 6 different bots (or the assistant)")
        roster = {b["id"]: b for b in (self.bots.roster() if self.bots is not None else [])}
        members, handles = [], set()
        for w in wanted:
            if w == ASSISTANT:
                profile, title, handle = "default", self.assistant_name, "hermes"  # @hermes always means the primary bot
            elif isinstance(w, str) and w in roster:
                profile, title = roster[w]["profile"], roster[w]["name"]
                handle = _slug(title)
            else:
                raise RoomError(m.NOT_FOUND, f"Unknown bot {w!r}")
            while handle in handles or handle in ("all", "everyone", "user"):
                handle = f"{handle}-{profile}"[:60]
            handles.add(handle)
            members.append({"member_id": profile, "profile": profile, "handle": handle, "display_name": title[:80]})
        rid = "r-" + secrets.token_hex(8)
        out = await self._rpc("groups.create", {"room_id": rid, "name": name.strip(), "members": members})
        async with self._lock:
            r = self._rooms[rid] = _Room(out.get("room") or {"room_id": rid, "name": name.strip(), "members": members})
            r.loaded, r.watched_until = True, self.now() + WATCH_S
            summary = self._summary(rid, r)
            self._listed = self.list()["rooms"]
        await self._notify("rooms.changed", {"rooms": self._listed})
        return {"room": summary}


ROOM_METHODS = frozenset({"rooms.list", "rooms.open", "rooms.send", "rooms.stop", "rooms.approve", "rooms.create"})
