"""Hermes's bots in Talaria (spec/README.md §18 "Bots").

A bot is a Hermes profile (Bot Mode in Hermes Desktop): its own role, model, memory and skills. Each has one
permanent chat, the session titled exactly "Bot Chat" in its profile, which Hermes Desktop opens too. Talaria
shows each bot's permanent chat as an ordinary conversation (agent_id `bot:<profile>`): BotChatClient speaks to
it through the doorway to `hermes serve` (hermes_serve.py) and looks to the chat service like the Hermes API
client (hermes.py), so chat, history, stop, notes, approvals and Talk work for bots as they do for Hermes.

What Talaria never does to a bot's chat: rename it (the title is what makes it the bot's chat), delete it, or
change its model. Deleting the conversation in Talaria only takes it off the list; opening the bot again brings
its history back from Hermes.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import re
from collections.abc import AsyncIterator, Awaitable, Callable

from .hermes import HermesError, HermesUnavailable
from .hermes_serve import HermesBackend, ServeError, ServeUnavailable, filter_models

log = logging.getLogger("talaria.bots")

PREFIX = "bot:"
BOT_CHAT = "Bot Chat"  # Hermes's name for a bot's permanent chat
PROFILE_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,59}$")
ROSTER_EVERY_S = 60
TURN_IDLE_S = 900  # a turn that says nothing for this long has failed (tools stream progress while they run)
MAX_AVATAR = 512 * 1024


def bot_id(profile: str) -> str:
    return PREFIX + profile


def profile_of(agent_id: str | None) -> str | None:
    if isinstance(agent_id, str) and agent_id.startswith(PREFIX) and PROFILE_RE.match(agent_id[len(PREFIX):]):
        return agent_id[len(PREFIX):]
    return None


def _stored_id(snap: dict) -> str | None:
    """A session's stored id: session.create says stored_session_id; resuming an existing one says resumed and
    session_key instead (Hermes 0.21.5)."""
    for key in ("stored_session_id", "resumed", "session_key"):
        if isinstance(snap.get(key), str) and snap[key]:
            return snap[key]
    return None


def _hermes_error(exc: ServeError) -> HermesError:
    status = 404 if "not found" in exc.message.lower() else 409 if exc.code in (4009, 4007) else 400
    return HermesError(status, str(exc.code), exc.message)


class BotChatClient:
    """One bot's permanent chat, through hermes serve, shaped like hermes.HermesClient for the chat service."""

    def __init__(self, backend: HermesBackend, profile: str, *, idle_s: float = TURN_IDLE_S):
        self.backend = backend
        self.profile = profile
        self.idle_s = idle_s
        self._live: dict[str, tuple[int, str]] = {}  # stored session id -> (connection generation, live id)

    async def _rpc(self, method: str, params: dict, **kw):
        try:
            return await self.backend.rpc(method, {**params, "profile": self.profile}, **kw)
        except ServeError as exc:
            raise _hermes_error(exc) from None
        except ServeUnavailable as exc:
            raise HermesUnavailable(str(exc)) from None

    async def open_chat(self) -> str:
        """The stored id of this bot's permanent chat, made the first time (as Hermes Desktop does)."""
        try:
            snap = await self._rpc("session.resume", {"session_id": BOT_CHAT, "omit_messages": True})
        except HermesError as exc:
            if exc.status != 404:
                raise
            snap = await self._rpc("session.create", {"title": BOT_CHAT})
        stored, live = _stored_id(snap), snap.get("session_id")
        if not (isinstance(stored, str) and stored and isinstance(live, str) and live):
            raise HermesError(502, "bad_response", "Hermes didn't say which session is the bot's chat")
        self._live[stored] = (self.backend.generation, live)
        return stored

    async def live(self, stored: str) -> str:
        """The session's id in this connection to hermes serve (resumed again after a reconnect)."""
        known = self._live.get(stored)
        if known is not None and known[0] == self.backend.generation:
            return known[1]
        snap = await self._rpc("session.resume", {"session_id": stored, "omit_messages": True})
        live = snap.get("session_id")
        if not (isinstance(live, str) and live):
            raise HermesError(502, "bad_response", "Hermes didn't resume the bot's chat")
        self._live[stored] = (self.backend.generation, live)
        return live

    # what Talaria leaves alone

    async def create_session(self, session_id: str, title: str | None) -> dict:
        raise HermesError(400, "not_supported", "A bot has one chat; Talaria doesn't start others")

    async def rename_session(self, session_id: str, title: str) -> None:
        return None  # renamed in Talaria only: "Bot Chat" is what makes it the bot's chat

    async def pin_session(self, session_id: str, pinned: bool) -> None:
        return None

    async def delete_session(self, session_id: str) -> None:
        return None  # never: the bot's chat is also Hermes Desktop's

    async def set_session_model(self, session_id: str, provider: str, model: str) -> None:
        raise HermesError(400, "not_supported", "A bot's model is set in Hermes (its profile)")

    async def close(self) -> None:
        return None

    # what Talaria reads and does

    async def model_options(self) -> dict:
        data = await self._rpc("model.options", {})
        allowed = await self.backend.allowed_models() if self.backend.allowed_models is not None else None
        return filter_models(data, allowed) if allowed else data

    async def session(self, session_id: str) -> dict:
        try:
            usage = await self._rpc("session.usage", {"session_id": await self.live(session_id)})
        except (HermesError, HermesUnavailable):
            return {}
        if not isinstance(usage, dict):
            return {}
        info = {"input_tokens": usage.get("input"), "output_tokens": usage.get("output"),
                "cache_read_tokens": usage.get("cache_read"), "api_call_count": usage.get("calls")}
        if isinstance(usage.get("cost_usd"), (int, float)):
            info["estimated_cost_usd"] = usage["cost_usd"]
        return {k: v for k, v in info.items() if v is not None}

    async def messages(self, session_id: str, *, limit: int, offset: int) -> list[dict]:
        """One page, newest first, in the Hermes API's row shape (chat.history_messages reads it)."""
        data = await self._rpc("session.history", {"session_id": await self.live(session_id)}, timeout=60)
        rows = data.get("messages") if isinstance(data, dict) else None
        out = []
        for i, r in enumerate(rows if isinstance(rows, list) else []):
            if not isinstance(r, dict) or r.get("role") not in ("user", "assistant"):
                continue
            text = r.get("text") if isinstance(r.get("text"), str) else r.get("content")
            out.append({"id": r.get("row_id") if isinstance(r.get("row_id"), int) else i + 1, "role": r["role"],
                        "content": text if text is not None else "", "timestamp": r.get("timestamp") or 0,
                        **({"display_kind": r["display_kind"]} if r.get("display_kind") else {})})
        out.reverse()
        return out[offset:offset + limit]

    async def stop_run(self, run_id: str) -> None:
        await self._rpc("session.interrupt", {"session_id": run_id})

    async def steer_run(self, run_id: str, text: str) -> bool:
        try:
            await self._rpc("session.steer", {"session_id": run_id, "text": text})
        except HermesError:
            return False
        return True

    async def approve_run(self, run_id: str, choice: str, request_id: str | None = None) -> bool:
        from .hermes_serve import BackendError

        if not request_id:
            return False
        try:
            await self.backend.respond(request_id, {"choice": choice})
        except BackendError:
            return False
        return True

    async def chat_stream(self, session_id: str, text: str | list, rewind: int | None = None) -> AsyncIterator[tuple[str, dict]]:
        """One turn, as the Hermes API's stream events (hermes.py) so the chat service runs it unchanged. [rewind]: first
        cut the chat back to just before that message (its row id), as Hermes Desktop's edit and regenerate do (§18.10)."""
        live = await self.live(session_id)
        queue = self.backend.listen(live)
        try:
            message = text if isinstance(text, str) else "\n".join(
                p.get("text", "") for p in text if isinstance(p, dict) and p.get("type") == "text")
            cut = {"truncate_before_row_id": rewind, "confirm_truncate": True, "confirm_empty_truncate": True} \
                if rewind is not None else {}
            await self._rpc("prompt.submit", {"session_id": live, "text": message, **cut})
            yield "run.started", {"run_id": live}
            while True:
                try:
                    event = await asyncio.wait_for(queue.get(), self.idle_s)
                except TimeoutError:
                    yield "error", {"message": f"The bot said nothing for {int(self.idle_s // 60)} minutes"}
                    return
                kind, payload = event.get("type"), event.get("payload")
                payload = payload if isinstance(payload, dict) else {}
                if kind == "_disconnected":
                    raise HermesUnavailable("Hermes's backend went away during the reply")
                if kind == "_approval":
                    ask = dict(payload, request_id=event["request_id"])
                    choices = ask.get("choices")
                    ask["choices"] = [c.get("id") if isinstance(c, dict) else c for c in choices] \
                        if isinstance(choices, list) and choices else ["once", "session", "always", "deny"]
                    yield "approval.request", ask
                elif kind == "message.delta" and isinstance(payload.get("text"), str):
                    yield "assistant.delta", {"delta": payload["text"]}
                elif kind == "tool.start":
                    preview = payload.get("context") or payload.get("preview")
                    yield "tool.started", {"tool_name": payload.get("name") or "tool",
                                           **({"preview": preview} if isinstance(preview, str) else {})}
                elif kind == "tool.complete":
                    yield "tool.completed", {"tool_name": payload.get("name") or "tool"}
                elif kind == "message.complete":
                    final = payload.get("text") if isinstance(payload.get("text"), str) else None
                    if final is not None:
                        yield "assistant.completed", {"content": final}
                    usage = payload.get("usage") if isinstance(payload.get("usage"), dict) else {}
                    status, error = payload.get("status"), payload.get("error") or payload.get("failure_reason")
                    done = {"usage": {"input_tokens": int(usage.get("input") or 0), "output_tokens": int(usage.get("output") or 0),
                                      "total_tokens": int(usage.get("total") or 0)}}
                    if isinstance(usage.get("model"), str) and usage["model"]:
                        done["runtime"] = {"model": usage["model"]}
                    if error or status not in (None, "complete"):
                        name = "run.cancelled" if status in ("interrupted", "cancelled") else "run.failed"
                        yield name, {**done, "error": str(error or status)}
                    else:
                        yield "run.completed", done
                    yield "done", {}
                    return
                elif kind == "error":
                    yield "error", {"message": str(payload.get("message") or "The bot failed")}
                    yield "done", {}
                    return
        finally:
            self.backend.unlisten(live, queue)


class Bots:
    """The roster of bots (Hermes profiles other than the default one, which is Hermes itself in Talaria)."""

    def __init__(self, backend: HermesBackend, *, every_s: float = ROSTER_EVERY_S):
        self.backend = backend
        self.every_s = every_s
        self.broadcast: Callable[[dict], Awaitable[None]] | None = None  # set by the chat service
        self._roster: list[dict] = []
        self._clients: dict[str, BotChatClient] = {}
        backend.on_change.append(self.refresh)

    def roster(self) -> list[dict]:
        return [dict(b) for b in self._roster]

    def known(self, agent_id: str) -> bool:
        return any(b["id"] == agent_id for b in self._roster)

    def client(self, agent_id: str) -> BotChatClient | None:
        profile = profile_of(agent_id)
        if profile is None:
            return None
        if agent_id not in self._clients:
            self._clients[agent_id] = BotChatClient(self.backend, profile)
        return self._clients[agent_id]

    def name(self, agent_id: str) -> str:
        return next((b["name"] for b in self._roster if b["id"] == agent_id), profile_of(agent_id) or agent_id)

    async def refresh(self) -> None:
        try:
            data = await self.backend.rpc("profiles.list", {"include_sessions": False})
        except (ServeError, ServeUnavailable) as exc:
            log.info("bots: no roster (%s)", exc)
            return
        bots = []
        for p in (data or {}).get("profiles") or []:
            name = p.get("name") if isinstance(p, dict) else None
            if not isinstance(name, str) or not PROFILE_RE.match(name) or p.get("is_default") or name == "default":
                continue
            # Hermes Desktop keeps a bot's title in front of its description ("ChainMail — Use for: …") and leaves
            # display_name empty; profiles.list doesn't carry the title itself
            desc = p.get("description") if isinstance(p.get("description"), str) else ""
            title, sep, rest = desc.partition(" — ")
            title = title.strip() if sep and 0 < len(title.strip()) <= 60 else ""
            bot = {"id": bot_id(name), "name": str(p.get("display_name") or title or name)[:100], "profile": name,
                   "has_avatar": bool(p.get("has_avatar"))}
            if (rest.strip() if title else desc.strip()):
                bot["description"] = (rest.strip() if title else desc.strip())[:500]
            for key in ("role", "model"):
                if isinstance(p.get(key), str) and p[key]:
                    bot[key] = p[key][:500]
            bots.append(bot)
        bots.sort(key=lambda b: b["name"].lower())
        if bots != self._roster:
            self._roster = bots
            if self.broadcast is not None:
                from .protocol import messages as m
                with contextlib.suppress(Exception):
                    await self.broadcast(m.notification("bots.changed", {"bots": self.roster()}))

    async def run(self) -> None:
        while True:
            if self.backend.connected:
                await self.refresh()
            await asyncio.sleep(self.every_s)

    async def avatar(self, agent_id: str) -> dict:
        profile = profile_of(agent_id)
        if profile is None:
            raise ValueError("unknown bot")
        data = await self.backend.rpc("profiles.get_asset", {"name": profile, "asset": "avatar"})
        if not isinstance(data, dict) or not data.get("found") or not isinstance(data.get("data"), str):
            return {"found": False}
        url = data["data"]
        mime, _, b64 = url.partition(";base64,") if url.startswith("data:") else (data.get("mime") or "", "", url)
        mime = mime.removeprefix("data:") or str(data.get("mime") or "image/png")
        if len(b64) > MAX_AVATAR * 4 // 3:
            return {"found": False}
        return {"found": True, "mime": mime[:100], "data": b64}

