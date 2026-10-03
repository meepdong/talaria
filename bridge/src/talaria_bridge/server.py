"""TNP WebSocket endpoint: pairing, the signed handshake and heartbeats (PROTOCOL §3)."""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import secrets
import ssl
import time
from collections.abc import AsyncIterator
from dataclasses import dataclass, field

from websockets.asyncio.server import Server, ServerConnection, serve
from websockets.exceptions import ConnectionClosed
from websockets.http11 import Request

from . import __version__
from .agents import AgentMonitor
from .chat import CHAT_METHODS, ChatService, RpcError
from .protocol import keys
from .protocol import messages as m
from .protocol.encoding import EncodingError, b64u_encode, now
from .protocol.pairing import normalize_short_code
from .protocol.sas import derive_sas
from .registry import ClaimError, Registry

log = logging.getLogger("talaria.server")

MAX_NAME_LEN = 64
MAX_PLATFORM_LEN = 32


@dataclass
class ServerSettings:
    host: str = "127.0.0.1"
    port: int = 8765
    ssl: ssl.SSLContext | None = None
    first_message_timeout_s: float = 30
    approval_timeout_s: float = 120
    ping_interval_s: float = 30
    idle_timeout_s: float = 90
    revocation_check_s: float = 1.0
    decision_poll_s: float = 0.25


@dataclass
class _Session:
    session_id: str
    device_id: str
    ready: bool = False
    latency_ms: int | None = None  # from the bridge's own heartbeat pings
    pings: dict[str, float] = field(default_factory=dict)


class _Reject(Exception):
    def __init__(self, reason: str):
        self.reason = reason


class BridgeServer:
    def __init__(self, registry: Registry, key: keys.PrivateKey, settings: ServerSettings | None = None,
                 agents: AgentMonitor | None = None, chat: ChatService | None = None):
        self.registry = registry
        self.key = key
        self.settings = settings or ServerSettings()
        self.agents = agents or AgentMonitor([])
        self.chat = chat
        if chat is not None:
            chat.broadcast = self.broadcast
        self._tasks: set[asyncio.Task] = set()
        self.bridge_pk = keys.public_key_b64u(key)
        self.bridge_id = keys.key_id(key)
        self._seen_nonces: dict[str, float] = {}
        self._sessions: dict[ServerConnection, _Session] = {}
        self._started = time.monotonic()

    @contextlib.asynccontextmanager
    async def serve(self) -> AsyncIterator[Server]:
        """`async with bridge.serve():` runs the endpoint at ws(s)://host:port/tnp, plus the
        agent health checks."""
        async with serve(
            self.handler,
            self.settings.host,
            self.settings.port,
            ssl=self.settings.ssl,
            subprotocols=[m.SUBPROTOCOL],
            max_size=m.MAX_FRAME,
            ping_interval=None,  # TNP has its own heartbeat (§3.4)
            process_request=self._check_path,
        ) as server:
            self._started = time.monotonic()
            monitor = asyncio.ensure_future(self.agents.run(self.push_status)) if self.agents.agents else None
            background = asyncio.ensure_future(self.chat.run_background()) if self.chat is not None else None
            try:
                yield server
            finally:
                for task in (monitor, background):
                    if task is not None:
                        task.cancel()
                        await asyncio.gather(task, return_exceptions=True)
                if self.chat is not None:
                    await self.chat.close()

    # status (§10.1)

    def status_report(self, session: _Session) -> dict:
        return {
            "bridge": {"version": __version__,
                       "uptime_s": int(time.monotonic() - self._started),
                       "latency_ms": session.latency_ms},
            "agents": self.agents.report(),
            "device": {"session_id": session.session_id, "last_acked_seq": 0},
        }

    async def push_status(self) -> None:
        """Send a `status` notification to every session that is past `ready`."""
        for ws, session in list(self._sessions.items()):
            if session.ready:
                with contextlib.suppress(ConnectionClosed):
                    await self._send(ws, m.notification("status", self.status_report(session)))

    async def broadcast(self, msg: dict) -> None:
        """Send one notification to every session that is past `ready` (chat, §9)."""
        for ws, session in list(self._sessions.items()):
            if session.ready:
                with contextlib.suppress(ConnectionClosed):
                    await self._send(ws, msg)

    async def _chat_request(self, ws: ServerConnection, method: str, msg_id, params: dict) -> None:
        try:
            result, turn = await self.chat.handle(method, params)
        except RpcError as exc:
            if msg_id is not None:
                with contextlib.suppress(ConnectionClosed):
                    await self._send(ws, m.error(msg_id, exc.code, exc.message))
            return
        except Exception:
            log.exception("%s failed", method)
            if msg_id is not None:
                with contextlib.suppress(ConnectionClosed):
                    await self._send(ws, m.error(msg_id, -32603, "Internal error"))
            return
        if msg_id is not None:
            with contextlib.suppress(ConnectionClosed):
                await self._send(ws, m.result(msg_id, result))
        if turn is not None:
            self.chat.start(turn)  # after the result, so it arrives before the turn's first delta

    @staticmethod
    def _check_path(connection: ServerConnection, request: Request):
        if request.path != "/tnp":
            return connection.respond(404, "Not found\n")
        return None

    async def handler(self, ws: ServerConnection) -> None:
        if ws.subprotocol != m.SUBPROTOCOL:
            await ws.close(m.CLOSE_PROTOCOL, "subprotocol tnp.v0 required")
            return
        try:
            nonce_b = b64u_encode(secrets.token_bytes(16))
            ts = now()
            sig_b = keys.sign(self.key, m.hello_signed_data(self.bridge_id, nonce_b, ts))
            await self._send(ws, m.notification(
                "hello", {"bridge_id": self.bridge_id, "bridge_pk": self.bridge_pk,
                          "nonce_b": nonce_b, "ts": ts, "sig_b": sig_b}))
            try:
                raw = await asyncio.wait_for(ws.recv(), self.settings.first_message_timeout_s)
            except asyncio.TimeoutError:
                await ws.close(m.CLOSE_TIMEOUT, "no pair.request or auth")
                return
            try:
                msg = m.decode(raw)
            except m.ProtocolError as exc:
                await ws.close(m.CLOSE_PROTOCOL, str(exc)[:100])
                return
            method = msg.get("method")
            if method == "pair.request":
                await self._pair(ws, msg, nonce_b)
            elif method == "auth" and "id" in msg:
                await self._auth(ws, msg, nonce_b)
            else:
                if "id" in msg:
                    await self._send(ws, m.error(msg["id"], m.NOT_AUTHENTICATED, "Session not authenticated"))
                await ws.close(m.CLOSE_PROTOCOL, "expected pair.request or auth")
        except ConnectionClosed:
            pass

    async def _send(self, ws: ServerConnection, msg: dict) -> None:
        await ws.send(m.encode(msg))

    # pairing (§3.2)

    async def _pair(self, ws: ServerConnection, msg: dict, nonce_b: str) -> None:
        try:
            outcome = await self._pair_inner(ws, msg.get("params") or {}, nonce_b)
        except _Reject as rej:
            log.info("pairing rejected: %s", rej.reason)
            await self._send(ws, m.notification("pair.rejected", {"reason": rej.reason}))
            await ws.close(1000, "pairing rejected")
            return
        await self._send(ws, m.notification("pair.accepted", outcome))
        await ws.close(1000, "paired")

    async def _pair_inner(self, ws: ServerConnection, p: dict, nonce_b: str) -> dict:
        device_pk, name, platform = p.get("device_pk"), p.get("name"), p.get("platform")
        ts, sig_d = p.get("ts"), p.get("sig_d")
        token, code = p.get("pair_token"), p.get("short_code")
        if not (isinstance(device_pk, str) and isinstance(sig_d, str)
                and isinstance(name, str) and 0 < len(name) <= MAX_NAME_LEN
                and isinstance(platform, str) and 0 < len(platform) <= MAX_PLATFORM_LEN
                and isinstance(ts, int) and not isinstance(ts, bool)
                and (token is None) != (code is None)
                and isinstance(token if token is not None else code, str)):
            raise _Reject("invalid_request")
        if abs(now() - ts) > m.TS_WINDOW_S:
            raise _Reject("invalid_request")
        try:
            secret = token if token is not None else normalize_short_code(code)
            if code is not None and code != secret:
                raise _Reject("invalid_request")  # clients send the normalized form
            device_key = keys.load_public_key(device_pk)
        except EncodingError:
            raise _Reject("invalid_request") from None
        signed = m.pair_signed_data(self.bridge_id, nonce_b, device_pk, secret, name, platform, ts)
        if not keys.verify(device_key, signed, sig_d):
            raise _Reject("bad_signature")
        device_id = keys.key_id(device_key)
        if self.registry.is_revoked(device_id):
            raise _Reject("revoked")
        try:
            pairing = (self.registry.claim_pairing(now=now(), token=token) if token is not None
                       else self.registry.claim_pairing(now=now(), short_code=secret))
        except ClaimError as exc:
            raise _Reject(exc.reason) from None

        sas = derive_sas(self.bridge_pk, device_pk, secret)
        label = pairing.name or name
        request_id = self.registry.add_pair_request(
            token=pairing.token, device_id=device_id, device_pk=device_pk, device_name=label,
            platform=platform, sas_digits=sas.digits, sas_emoji=sas.emoji, now=now())
        log.info("pair request %s from %r waiting for terminal approval", request_id, label)

        state = await self._await_decision(ws, request_id)
        if state != "approved":
            raise _Reject("sas_rejected" if state == "rejected" else "timeout")
        self.registry.add_device(device_id=device_id, public_key=device_pk, name=label,
                                 platform=platform, now=now())
        log.info("paired %s (%s)", label, device_id)
        return {"device_id": device_id, "name": label}

    async def _await_decision(self, ws: ServerConnection, request_id: int) -> str:
        closed = asyncio.ensure_future(ws.wait_closed())
        deadline = time.monotonic() + self.settings.approval_timeout_s
        try:
            while time.monotonic() < deadline:
                req = self.registry.get_pair_request(request_id)
                if req is not None and req.state != "waiting":
                    return req.state
                await asyncio.wait({closed}, timeout=self.settings.decision_poll_s)
                if closed.done():
                    self.registry.decide_pair_request(request_id, "expired", now())
                    raise ConnectionClosed(None, None)
            if self.registry.decide_pair_request(request_id, "expired", now()):
                return "expired"
            return self.registry.get_pair_request(request_id).state
        finally:
            closed.cancel()

    # handshake (§3.3)

    def _nonce_seen(self, nonce: str) -> bool:
        t = time.monotonic()
        for n, expiry in list(self._seen_nonces.items()):
            if expiry < t:
                del self._seen_nonces[n]
        if nonce in self._seen_nonces:
            return True
        self._seen_nonces[nonce] = t + 2 * m.TS_WINDOW_S
        return False

    async def _auth(self, ws: ServerConnection, msg: dict, nonce_b: str) -> None:
        p = msg.get("params") or {}
        device_id, nonce_d, ts, sig_d = p.get("device_id"), p.get("nonce_d"), p.get("ts"), p.get("sig_d")
        if not (isinstance(device_id, str) and isinstance(nonce_d, str) and isinstance(sig_d, str)
                and isinstance(ts, int) and not isinstance(ts, bool)):
            await ws.close(m.CLOSE_BAD_SIGNATURE, "malformed auth")
            return
        if abs(now() - ts) > m.TS_WINDOW_S:
            await ws.close(m.CLOSE_BAD_SIGNATURE, "timestamp outside window")
            return
        device = self.registry.get_device(device_id)
        if device is None:
            await ws.close(m.CLOSE_BAD_SIGNATURE, "unknown device")
            return
        signed = m.auth_signed_data(self.bridge_id, device_id, nonce_b, nonce_d, ts)
        if not keys.verify(keys.load_public_key(device.public_key), signed, sig_d):
            await ws.close(m.CLOSE_BAD_SIGNATURE, "bad signature")
            return
        if device.revoked:
            await ws.close(m.CLOSE_REVOKED, "device revoked")
            return
        if self._nonce_seen(nonce_d):
            await ws.close(m.CLOSE_BAD_SIGNATURE, "replayed nonce")
            return

        session_id = "s-" + secrets.token_hex(6)
        self.registry.touch_device(device_id, now())
        await self._send(ws, m.result(msg["id"], {
            "session_id": session_id, "last_acked_seq": 0, "server_time": now()}))
        log.info("session %s opened for %s (%s)", session_id, device.name, device_id)
        self._sessions[ws] = _Session(session_id, device_id)
        try:
            await self._session(ws, device_id)
        finally:
            del self._sessions[ws]
        log.info("session %s closed (%s)", session_id, ws.close_code)

    async def _session(self, ws: ServerConnection, device_id: str) -> None:
        last_rx = time.monotonic()

        async def reader() -> None:
            nonlocal last_rx
            async for raw in ws:
                last_rx = time.monotonic()
                try:
                    msg = m.decode(raw)
                except m.ProtocolError as exc:
                    await self._send(ws, m.error(None, m.INVALID_REQUEST, str(exc)))
                    continue
                await self._dispatch(ws, device_id, msg)

        async def heartbeat() -> None:
            sent = 0
            last_ping = time.monotonic()
            while True:
                await asyncio.sleep(min(self.settings.ping_interval_s, self.settings.revocation_check_s))
                t = time.monotonic()
                if self.registry.is_revoked(device_id):
                    await ws.close(m.CLOSE_REVOKED, "device revoked")
                    return
                if t - last_rx > self.settings.idle_timeout_s:
                    await ws.close(m.CLOSE_TIMEOUT, "no traffic")
                    return
                if (t - last_rx >= self.settings.ping_interval_s
                        and t - last_ping >= self.settings.ping_interval_s):
                    sent += 1
                    last_ping = t
                    self._sessions[ws].pings[f"p-{sent}"] = t
                    await self._send(ws, m.request(f"p-{sent}", "ping"))

        tasks = [asyncio.ensure_future(reader()), asyncio.ensure_future(heartbeat())]
        try:
            await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        finally:
            for t in tasks:
                t.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            self.registry.touch_device(device_id, now())

    async def _dispatch(self, ws: ServerConnection, device_id: str, msg: dict) -> None:
        method, msg_id = msg.get("method"), msg.get("id")
        session = self._sessions[ws]
        if method is None:
            # a result or error for one of our pings
            sent_at = session.pings.pop(msg_id, None) if isinstance(msg_id, str) else None
            if sent_at is not None and "result" in msg:
                session.latency_ms = round((time.monotonic() - sent_at) * 1000)
            return
        if method == "ping":
            if msg_id is not None:
                await self._send(ws, m.result(msg_id, {"ts": now()}))
        elif method == "capabilities.announce":
            self.registry.set_capabilities(device_id, json.dumps(msg.get("params") or {}))
            await self._send(ws, m.notification("ready"))
            session.ready = True
        elif method == "status.get":
            if msg_id is not None:
                await self._send(ws, m.result(msg_id, self.status_report(session)))
        elif method in CHAT_METHODS and not session.ready:
            if msg_id is not None:
                await self._send(ws, m.error(msg_id, m.INVALID_REQUEST, "Send capabilities.announce first"))
        elif method in CHAT_METHODS and self.chat is not None:
            # Chat calls may wait on the agent, so they run beside the reader, not in it.
            params = msg.get("params")
            task = asyncio.ensure_future(self._chat_request(ws, method, msg_id, params if isinstance(params, dict) else {}))
            self._tasks.add(task)
            task.add_done_callback(self._tasks.discard)
        elif method in CHAT_METHODS and msg_id is not None:
            await self._send(ws, m.error(msg_id, m.AGENT_UNAVAILABLE, "No chat agent is configured on the bridge"))
        elif msg_id is not None:
            await self._send(ws, m.error(msg_id, m.METHOD_NOT_FOUND, f"Method not found: {method}"))
