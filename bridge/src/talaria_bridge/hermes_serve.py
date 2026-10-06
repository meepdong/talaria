"""The bridge as a client of `hermes serve`, Hermes's other backend: the doorway (spec/README.md §18).

`hermes serve` is what Hermes Desktop talks to: JSON-RPC over a WebSocket (`/api/ws`), plus a few REST addresses
(the Kanban board). Bots (Hermes profiles), group chats, jobs, skills and settings all live there. The bridge
connects to it on this machine only, with a token both sides keep in their own file, and passes an allowlisted set
of calls through to devices (`hermes.call`). Devices never reach Hermes themselves.

Wire format (Hermes `tui_gateway/ws.py`): newline-delimited JSON-RPC 2.0 both ways; `gateway.ready` arrives as an
event right after connecting. Besides answers and events, the server sends requests of its own: the bridge passes
`approval` and `clarify` on to devices and declines every other one (sudo, secret, vault, ...) at once.
"""

from __future__ import annotations

import asyncio
import contextlib
import inspect
import itertools
import json
import logging
import secrets
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote, urlsplit

import httpx
import websockets

from .protocol import messages as m

log = logging.getLogger("talaria.hermes_serve")

DEFAULT_URL = "ws://127.0.0.1:9119/api/ws"
CALL_TIMEOUT_S = 30
LOCAL_HOSTS = ("127.0.0.1", "localhost", "::1")


def is_local_url(url: str) -> bool:
    """The doorway only ever talks to a `hermes serve` on this machine (ws://, a loopback host, a port)."""
    try:
        parts = urlsplit(url)
        return parts.scheme == "ws" and parts.hostname in LOCAL_HOSTS and parts.port is not None
    except ValueError:
        return False


class ServeError(Exception):
    """`hermes serve` answered a call with a JSON-RPC error."""

    def __init__(self, code: int, message: str):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


class ServeUnavailable(Exception):
    """`hermes serve` could not be reached, refused the token, or went away."""


OnRequest = Callable[[str, str, dict], Awaitable[bool]]  # (server's request id, method, params) -> handled?


class HermesServe:
    """One connection to `hermes serve`. Use `async with HermesServe(url, token) as hs: await hs.call(...)`.

    `on_event(event)` gets every event (`{type, session_id?, payload?}`); `on_request(id, method, params)` gets the
    server's own requests and returns True when it will answer later (`reply`/`refuse`); otherwise, or without it,
    the request is declined at once (-32601), so the agent doesn't wait."""

    def __init__(self, url: str, token: str, *, on_event: Callable[[dict], object] | None = None,
                 on_request: OnRequest | None = None):
        self.url = url
        self.token = token
        self.on_event = on_event
        self.on_request = on_request
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
            await asyncio.wait([self._reader])  # unlike awaiting it, never swallows our own cancellation
        if self._ws is not None:
            await self._ws.close()
        self._fail_pending()

    async def closed(self) -> None:
        """Returns when the connection has ended (and raises CancelledError when the caller is cancelled)."""
        if self._reader is not None:
            await asyncio.wait([self._reader])

    def _fail_pending(self) -> None:
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

    async def reply(self, rid: str, result: dict) -> None:
        await self._send({"jsonrpc": "2.0", "id": rid, "result": result})

    async def refuse(self, rid: str, code: int = -32601, message: str = "Talaria doesn't answer this") -> None:
        await self._send({"jsonrpc": "2.0", "id": rid, "error": {"code": code, "message": message}})

    async def _send(self, msg: dict) -> None:
        if self._ws is None:
            raise ServeUnavailable("not connected")
        try:
            await self._ws.send(json.dumps(msg))
        except websockets.exceptions.ConnectionClosed as exc:
            raise ServeUnavailable(f"connection closed: {exc}") from exc

    async def _read(self) -> None:
        try:
            async for raw in self._ws:
                for line in (raw if isinstance(raw, str) else raw.decode()).splitlines():
                    if line.strip():
                        try:
                            await self._frame(line)
                        except Exception:  # noqa: BLE001 (one bad frame or handler mustn't end the connection)
                            log.exception("hermes serve frame failed")
        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            self._fail_pending()

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
        elif rid is not None and isinstance(msg.get("method"), str):  # the server asking us something
            params = msg.get("params") if isinstance(msg.get("params"), dict) else {}
            handled = self.on_request is not None and await self.on_request(str(rid), msg["method"], params)
            if not handled:
                log.info("declining hermes serve's request %s", msg["method"])
                await self.refuse(str(rid))
        elif msg.get("method") == "event" and isinstance(msg.get("params"), dict):
            event = msg["params"]
            if event.get("type") == "gateway.ready":
                self.ready = event.get("payload") if isinstance(event.get("payload"), dict) else {}
                self._ready.set()
            if self.on_event is not None:
                done = self.on_event(event)
                if inspect.isawaitable(done):
                    await done

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


# the doorway (§18)

Guard = Callable[[dict], str | None]  # params -> why they're refused, or None


def _action(*allowed: str) -> Guard:
    return lambda p: None if p.get("action", "list") in allowed else f"only action {' or '.join(allowed)} is allowed"


def _no_rewind(p: dict) -> str | None:
    bad = sorted(k for k in p if k.startswith(("truncate_", "confirm_")))
    return f"rewinding a chat isn't allowed ({', '.join(bad)})" if bad else None


# What a device may call, with limits on the params where a method can do more than reading or talking. Never here:
# cli.exec, shell.exec, slash.exec, command.dispatch, config.*, reload.*, vault.*, connectors.*, billing.*,
# subscription.*, free_tier.*, groups.peer.*, bot_relay.*, image.attach (reads a path), process.stop, browser.*,
# profiles.create/configure/set_asset, skills install, cron changes. Settings come later with signed approvals (§16).
ALLOWED: dict[str, Guard | None] = {
    # reading
    "ping": None, "gateway.capabilities": None,
    "profiles.list": None, "profiles.describe": None, "profiles.get_asset": None,
    "groups.capabilities": None, "groups.list": None, "groups.state": None, "groups.log": None,
    "session.list": None, "session.most_recent": None, "session.history": None, "session.status": None,
    "session.usage": None, "session.events.since": None,
    "delegation.status": None, "model.options": None,
    "cron.manage": _action("list"), "skills.manage": _action("list", "inspect"),
    # talking
    "session.create": None, "session.resume": None, "session.activate": None, "session.close": None,
    "session.title": None, "session.interrupt": None, "session.steer": None, "prompt.submit": _no_rewind,
    "clarify.lock": None, "subagent.interrupt": None,
    "groups.create": None, "groups.rename": None, "groups.send": None, "groups.stop": None, "groups.retry": None,
    "groups.approve": None,
}
QUESTIONS = ("approval", "clarify")  # what Hermes may ask that devices answer; everything else is declined
APPROVAL_CHOICES = ("once", "session", "always", "deny")
FRAME_ROOM = m.MAX_FRAME - 4096  # what fits in one TNP frame with its envelope
BACKEND_ERROR = m.BACKEND_ERROR
PROBE_KEY = "talaria_probe"


class BackendError(Exception):
    """A hermes.* call refused: a TNP error code, words, and maybe data."""

    def __init__(self, code: int, message: str, data: dict | None = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.data = data


@dataclass
class _Question:
    request_id: str  # ours, given to devices
    serve_id: str  # the server's own request id
    method: str
    params: dict
    hs: HermesServe

    def public(self) -> dict:
        return {"request_id": self.request_id, "method": self.method, "params": self.params}


Notify = Callable[[dict], Awaitable[None]]
AllowedModels = Callable[[], Awaitable[set[str] | None]]


class HermesBackend:
    """The bridge's one lasting connection to `hermes serve`, and the hermes.* methods devices use (§18)."""

    def __init__(self, url: str, token: str, *, allowed_models: AllowedModels | None = None,
                 connect: Callable[..., HermesServe] = HermesServe, backoff: tuple[float, float] = (1, 60)):
        self.url = url
        self.token = token
        self.allowed_models = allowed_models
        self.connect = connect
        self.backoff = backoff
        self.broadcast: Notify | None = None  # set by the server
        self.methods: set[str] = set()  # allowlisted and present in this Hermes
        self.missing: set[str] = set()  # allowlisted but this Hermes doesn't have it
        self.version: str | None = None
        self._hs: HermesServe | None = None
        self._questions: dict[str, _Question] = {}

    @property
    def connected(self) -> bool:
        return self._hs is not None

    def capabilities(self) -> dict:
        caps = {"connected": self.connected, "methods": sorted(self.methods) if self.connected else [],
                "server_requests": list(QUESTIONS), "open_requests": [q.public() for q in self._questions.values()]}
        if self.version:
            caps["version"] = self.version[:100]
        return caps

    async def _notify(self, method: str, params: dict) -> None:
        if self.broadcast is not None:
            with contextlib.suppress(Exception):
                await self.broadcast(m.notification(method, params))

    # the connection

    async def run(self) -> None:
        """Stay connected: reconnect with a growing pause while `hermes serve` is away."""
        pause = self.backoff[0]
        while True:
            try:
                async with self.connect(self.url, self.token, on_event=self._event, on_request=self._question) as hs:
                    await self._open(hs)
                    pause = self.backoff[0]
                    await hs.closed()
                log.warning("hermes serve went away; reconnecting")
            except asyncio.CancelledError:
                await self._close()
                raise
            except ServeUnavailable as exc:
                log.log(logging.INFO if pause > self.backoff[0] else logging.WARNING, "hermes serve unavailable: %s", exc)
            except Exception:  # noqa: BLE001 (keep the doorway trying whatever one round hit)
                log.exception("hermes serve connection failed")
            await self._close()
            await asyncio.sleep(pause)
            pause = min(pause * 2, self.backoff[1])

    async def _open(self, hs: HermesServe) -> None:
        await hs.call("client.capabilities", {"server_requests": True})
        ready = hs.ready or {}
        version = ready.get("version") or ready.get("hermes_version")
        self.version = version if isinstance(version, str) else None
        self.methods, self.missing = await probe_methods(hs, ALLOWED)
        if self.missing:
            log.warning("hermes serve lacks %s: hidden on devices", ", ".join(sorted(self.missing)))
        self._hs = hs
        await self._notify("hermes.changed", self.capabilities())

    async def _close(self) -> None:
        was = self._hs is not None
        self._hs = None
        closed, self._questions = list(self._questions), {}
        for request_id in closed:
            await self._notify("hermes.request.done", {"request_id": request_id, "reason": "disconnected"})
        if was:
            await self._notify("hermes.changed", self.capabilities())

    async def _event(self, event: dict) -> None:
        kind = event.get("type")
        if not isinstance(kind, str) or not kind or kind == "gateway.ready":
            return
        payload = event.get("payload")
        if kind == "request.cancel":  # Hermes withdrew one of its questions
            serve_id = payload.get("id") if isinstance(payload, dict) else None
            for q in [q for q in self._questions.values() if q.serve_id == serve_id]:
                del self._questions[q.request_id]
                await self._notify("hermes.request.done", {"request_id": q.request_id, "reason": "withdrawn"})
            return
        out: dict = {"type": kind[:100]}
        if isinstance(event.get("session_id"), str) and event["session_id"]:
            out["session_id"] = event["session_id"][:200]
        if payload is not None:
            out["payload"] = payload
        if len(json.dumps(out, default=str)) > FRAME_ROOM:
            log.info("dropping hermes serve event %s: too big for one frame", kind)
            return
        await self._notify("hermes.event", out)

    async def _question(self, serve_id: str, method: str, params: dict) -> bool:
        if method not in QUESTIONS or self._hs is None:
            return False  # declined at once by HermesServe
        q = _Question("hq-" + secrets.token_hex(8), serve_id, method, params, self._hs)
        self._questions[q.request_id] = q
        await self._notify("hermes.request", q.public())
        return True

    # what devices call

    async def handle(self, method: str, p: dict) -> dict:
        if method == "hermes.capabilities":
            return self.capabilities()
        if method == "hermes.call":
            return {"result": await self.call(p.get("method"), p.get("params", {}))}
        if method == "hermes.respond":
            await self.respond(p.get("request_id"), p.get("result"))
            return {}
        raise BackendError(m.METHOD_NOT_FOUND, f"Method not found: {method}")

    async def call(self, method, params) -> object:
        if not isinstance(method, str) or method not in ALLOWED:
            raise BackendError(m.METHOD_NOT_FOUND, f"{method} isn't something devices may call")
        if not isinstance(params, dict):
            raise BackendError(m.INVALID_PARAMS, "params must be an object")
        hs = self._hs
        if hs is None:
            raise BackendError(m.AGENT_UNAVAILABLE, "Hermes's backend isn't connected")
        if method not in self.methods:
            raise BackendError(m.METHOD_NOT_FOUND, f"this Hermes doesn't have {method}")
        if inner := sorted(str(k) for k in params if str(k).startswith("_")):
            raise BackendError(m.INVALID_PARAMS, f"params starting with _ aren't allowed ({', '.join(inner)})")
        guard = ALLOWED[method]
        if guard is not None and (why := guard(params)):
            raise BackendError(m.INVALID_PARAMS, why)
        allowed = await self.allowed_models() if self.allowed_models is not None else None
        if allowed and isinstance(params.get("model"), str) and params["model"] not in allowed:
            raise BackendError(m.INVALID_PARAMS, f"{params['model']} isn't one of the models this bridge may use")
        try:
            result = await hs.call(method, params)
        except ServeError as exc:
            if exc.code == -32601:  # gone since we connected: hide it
                self.methods.discard(method)
                self.missing.add(method)
                await self._notify("hermes.changed", self.capabilities())
            raise BackendError(BACKEND_ERROR, f"Hermes refused {method}: {exc.message[:500]}",
                               {"code": exc.code, "message": exc.message[:2000]}) from None
        except ServeUnavailable as exc:
            raise BackendError(m.AGENT_UNAVAILABLE, f"Hermes's backend went away: {exc}") from None
        if method == "model.options" and allowed:
            result = filter_models(result, allowed)
        if len(json.dumps({"result": result}, default=str)) > FRAME_ROOM:
            raise BackendError(m.CONFLICT, f"the answer to {method} is too big for one message; ask for less")
        return result

    async def respond(self, request_id, result) -> None:
        q = self._questions.get(request_id) if isinstance(request_id, str) else None
        if q is None:
            raise BackendError(m.NOT_FOUND, "That question is closed or unknown")
        if not isinstance(result, dict):
            raise BackendError(m.INVALID_PARAMS, "result must be an object")
        if q.method == "approval" and (set(result) != {"choice"} or result["choice"] not in APPROVAL_CHOICES):
            raise BackendError(m.INVALID_PARAMS, f"an approval's result is {{choice}}: {', '.join(APPROVAL_CHOICES)}")
        if q.method == "clarify" and (set(result) - {"answers"} or not isinstance(result.get("answers", {}), dict)):
            raise BackendError(m.INVALID_PARAMS, "a clarify's result is {answers} (an object), or {} to cancel")
        del self._questions[request_id]
        try:
            await q.hs.reply(q.serve_id, result)
        except ServeUnavailable as exc:
            raise BackendError(m.AGENT_UNAVAILABLE, f"Hermes's backend went away: {exc}") from None
        await self._notify("hermes.request.done", {"request_id": request_id, "reason": "answered"})


async def probe_methods(hs: HermesServe, names) -> tuple[set[str], set[str]]:
    """Which of these methods this Hermes has, without running any: Hermes checks a method's params before it runs
    it, so an unknown parameter is refused (4000) by a method it has, and the method is unknown (-32601) otherwise."""
    present, missing = set(), set()
    for name in names:
        try:
            await hs.call(name, {PROBE_KEY: True})
            log.warning("hermes serve ran %s for a probe (it accepted an unknown parameter)", name)
            present.add(name)
        except ServeError as exc:
            (missing if exc.code == -32601 else present).add(name)
    return present, missing


def filter_models(result: object, allowed: set[str]) -> object:
    """model.options with only the models the bridge's OpenRouter key may use (its guardrail), as chat does (§11)."""
    if not isinstance(result, dict) or not isinstance(result.get("providers"), list):
        return result
    providers = []
    for row in result["providers"]:
        if not isinstance(row, dict) or row.get("authenticated") is False:
            continue
        row = dict(row)
        if row.get("slug") == "openrouter":
            row["models"] = [x for x in row.get("models") or [] if x in allowed]
        if row.get("models"):
            providers.append(row)
    return dict(result, providers=providers)


# a look around (`talaria hermes-serve probe`)

async def probe(url: str, token: str) -> list[tuple[str, bool, str]]:
    """(what, worked, plain-words summary) for each thing Talaria shows from Hermes's backend."""
    out: list[tuple[str, bool, str]] = []

    async def step(what: str, method: str, params: dict | None, summary: Callable[[object], str]) -> None:
        try:
            out.append((what, True, summary(await hs.call(method, params))))
        except ServeError as exc:
            out.append((what, False, f"{method} refused: {exc.message[:160]}"))

    async with HermesServe(url, token) as hs:
        out.append(("connected", hs.ready is not None,
                    "gateway.ready received" if hs.ready is not None else "no gateway.ready within 10 s"))
        present, missing = await probe_methods(hs, ALLOWED)
        out.append(("doorway methods", not missing, f"{len(present)} of {len(ALLOWED)} present"
                    + (f"; missing: {', '.join(sorted(missing))}" if missing else "")))
        await step("capabilities", "gateway.capabilities", {}, lambda r: json.dumps(r)[:200])
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
