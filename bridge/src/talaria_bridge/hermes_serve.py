"""The bridge as a client of `hermes serve`, Hermes's other backend (HANDOFF item 11, step 0: the trial).

`hermes serve` is what Hermes Desktop talks to: JSON-RPC over a WebSocket (`/api/ws`), plus a few REST addresses
(the Kanban board). Bots (Hermes profiles), group chats, jobs, skills and settings all live there. The bridge
connects to it on this machine only, with a token both sides keep in their own file, and (from step 1) passes an
allowlisted set of calls through to the phone. The phone never connects to Hermes itself.

Wire format (Hermes `tui_gateway/ws.py`): newline-delimited JSON-RPC 2.0 both ways; `gateway.ready` arrives as an
event right after connecting. Besides answers and events, the server sends requests of its own (approval,
clarify, sudo, secret, ...): this trial answers every one "not handled" (-32601), so the agent fails fast instead
of waiting, and never advertises that it answers them.
"""

from __future__ import annotations

import asyncio
import contextlib
import itertools
import json
import logging
from collections.abc import Callable
from pathlib import Path
from urllib.parse import quote

import httpx
import websockets

log = logging.getLogger("talaria.hermes_serve")

DEFAULT_URL = "ws://127.0.0.1:9119/api/ws"
CALL_TIMEOUT_S = 30


class ServeError(Exception):
    """`hermes serve` answered a call with a JSON-RPC error."""

    def __init__(self, code: int, message: str):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


class ServeUnavailable(Exception):
    """`hermes serve` could not be reached, refused the token, or went away."""


class HermesServe:
    """One connection to `hermes serve`. Use `async with HermesServe(url, token) as hs: await hs.call(...)`."""

    def __init__(self, url: str, token: str, *, on_event: Callable[[dict], None] | None = None):
        self.url = url
        self.token = token
        self.on_event = on_event
        self.ready: dict | None = None  # gateway.ready's payload
        self._ws = None
        self._reader: asyncio.Task | None = None
        self._pending: dict[str, asyncio.Future] = {}
        self._ids = itertools.count(1)
        self._ready = asyncio.Event()

    async def __aenter__(self) -> HermesServe:
        try:
            self._ws = await websockets.connect(f"{self.url}?token={quote(self.token)}", max_size=None,
                                                open_timeout=10)
        except (OSError, websockets.exceptions.WebSocketException, TimeoutError) as exc:
            raise ServeUnavailable(f"{type(exc).__name__}: {exc}") from exc
        self._reader = asyncio.create_task(self._read())
        with contextlib.suppress(TimeoutError):
            await asyncio.wait_for(self._ready.wait(), 10)
        return self

    async def __aexit__(self, *exc) -> None:
        await self.close()

    async def close(self) -> None:
        if self._reader is not None:
            self._reader.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await self._reader
        if self._ws is not None:
            await self._ws.close()
        for fut in self._pending.values():
            if not fut.done():
                fut.set_exception(ServeUnavailable("connection closed"))
        self._pending.clear()

    async def call(self, method: str, params: dict | None = None, *, timeout: float = CALL_TIMEOUT_S):
        """One JSON-RPC call; its `result`, or ServeError with Hermes's code and words."""
        if self._ws is None:
            raise ServeUnavailable("not connected")
        rid = f"talaria-{next(self._ids)}"
        fut = asyncio.get_running_loop().create_future()
        self._pending[rid] = fut
        try:
            await self._ws.send(json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params or {}}))
            msg = await asyncio.wait_for(fut, timeout)
        except websockets.exceptions.ConnectionClosed as exc:
            raise ServeUnavailable(f"connection closed: {exc}") from exc
        except TimeoutError as exc:
            raise ServeUnavailable(f"no answer to {method} within {timeout:g} s") from exc
        finally:
            self._pending.pop(rid, None)
        if "error" in msg:
            err = msg["error"] if isinstance(msg["error"], dict) else {}
            raise ServeError(int(err.get("code") or 0), str(err.get("message") or "error"))
        return msg.get("result")

    async def _read(self) -> None:
        try:
            async for raw in self._ws:
                for line in (raw if isinstance(raw, str) else raw.decode()).splitlines():
                    if line.strip():
                        await self._frame(line)
        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            for fut in self._pending.values():
                if not fut.done():
                    fut.set_exception(ServeUnavailable("connection closed"))

    async def _frame(self, line: str) -> None:
        try:
            msg = json.loads(line)
        except ValueError:
            log.debug("skipping a frame that isn't JSON")
            return
        if not isinstance(msg, dict):
            return
        rid = msg.get("id")
        if rid is not None and ("result" in msg or "error" in msg):
            fut = self._pending.get(rid)
            if fut is not None and not fut.done():
                fut.set_result(msg)
        elif rid is not None and "method" in msg:  # the server asking us something: not handled in the trial
            log.info("declining hermes serve's request %s", msg.get("method"))
            await self._ws.send(json.dumps({"jsonrpc": "2.0", "id": rid,
                                            "error": {"code": -32601, "message": "Talaria doesn't answer this yet"}}))
        elif msg.get("method") == "event" and isinstance(msg.get("params"), dict):
            event = msg["params"]
            if event.get("type") == "gateway.ready":
                self.ready = event.get("payload") if isinstance(event.get("payload"), dict) else {}
                self._ready.set()
            if self.on_event is not None:
                self.on_event(event)

    async def rest(self, path: str) -> object:
        """GET one of its REST addresses (e.g. the Kanban board), with the same token."""
        base = self.url.replace("ws://", "http://", 1).replace("wss://", "https://", 1).rsplit("/api/ws", 1)[0]
        async with httpx.AsyncClient(timeout=CALL_TIMEOUT_S) as http:
            try:
                resp = await http.get(base + path, headers={"X-Hermes-Session-Token": self.token})
            except httpx.HTTPError as exc:
                raise ServeUnavailable(f"{type(exc).__name__}: {exc}") from exc
        if resp.status_code >= 400:
            raise ServeError(resp.status_code, resp.text[:200])
        return resp.json()


async def probe(url: str, token: str) -> list[tuple[str, bool, str]]:
    """What the trial reports: (what, worked, plain-words summary) for each thing Talaria would show."""
    out: list[tuple[str, bool, str]] = []

    async def step(what: str, method: str, params: dict | None, summary: Callable[[object], str]) -> None:
        try:
            out.append((what, True, summary(await hs.call(method, params))))
        except ServeError as exc:
            out.append((what, False, f"{method} refused: {exc.message[:160]}"))

    async with HermesServe(url, token) as hs:
        out.append(("connected", hs.ready is not None,
                    "gateway.ready received" if hs.ready is not None else "no gateway.ready within 10 s"))
        await step("capabilities", "gateway.capabilities", {}, lambda r: json.dumps(r)[:200])
        await step("server questions it can send", "client.capabilities", {"server_requests": False},
                   lambda r: ", ".join(r.get("server_requests") or []) if isinstance(r, dict) else str(r))
        await step("bots", "profiles.list", {"include_sessions": False}, lambda r: ", ".join(
            f"{p.get('name')} ({p.get('model')}, {p.get('skill_count')} skills)" for p in (r or {}).get("profiles", [])) or "none")
        await step("group chats", "groups.list", {}, lambda r: ", ".join(
            str(g.get("name") or g.get("title") or g.get("id")) for g in (r or {}).get("rooms", [])) or "none yet")
        await step("chats", "session.list", {"limit": 5}, lambda r: f"{len((r or {}).get('sessions', []))} newest: " + "; ".join(
            f"{s.get('title') or '(untitled)'} [{s.get('source')}]" for s in (r or {}).get("sessions", [])))
        await step("jobs", "cron.manage", {"action": "list", "include_disabled": True}, lambda r: ", ".join(
            str(j.get("name")) for j in (r or {}).get("jobs", [])) or "none")
        await step("skills", "skills.manage", {"action": "list"}, lambda r: f"{sum(len(v) for v in ((r or {}).get('skills') or {}).values())} in "
                   f"{len((r or {}).get('skills') or {})} groups")
        await step("helper agents", "delegation.status", {}, lambda r: f"{len((r or {}).get('active', []))} running, "
                   f"up to {(r or {}).get('max_concurrent_children')} at once")
        try:
            board = await hs.rest("/api/plugins/kanban/board")
            cols = board.get("columns") if isinstance(board, dict) else None
            out.append(("Kanban board", True, f"{len(cols) if isinstance(cols, list) else '?'} columns, "
                        f"{sum(len(c.get('tasks') or []) for c in cols if isinstance(c, dict)) if isinstance(cols, list) else '?'} tasks"))
        except (ServeError, ServeUnavailable, ValueError) as exc:
            out.append(("Kanban board", False, str(exc)[:200]))
    return out


def read_token(path: Path) -> str:
    token = path.read_text(encoding="utf-8").strip()
    if not token:
        raise ValueError(f"{path} is empty")
    return token
