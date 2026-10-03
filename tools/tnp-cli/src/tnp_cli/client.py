"""A minimal TNP device: pairs with a bridge and opens authenticated sessions."""

from __future__ import annotations

import asyncio
import hashlib
import json
import secrets
import ssl
from collections.abc import Callable
from dataclasses import asdict, dataclass
from pathlib import Path
from urllib.parse import urlparse

from talaria_bridge.protocol import keys
from talaria_bridge.protocol import messages as m
from talaria_bridge.protocol.encoding import EncodingError, b64u_encode, now
from talaria_bridge.protocol.pairing import PairingPayload
from talaria_bridge.protocol.sas import Sas, derive_sas
from websockets.asyncio.client import ClientConnection, connect
from websockets.exceptions import ConnectionClosed

PLATFORM = "cli"


class TnpError(Exception):
    pass


@dataclass
class BridgeInfo:
    url: str
    bridge_id: str
    bridge_pk: str
    device_id: str
    name: str
    tls_spki_sha256: str | None = None


class DeviceState:
    """Files in the tnp-cli home folder: the device key and the paired bridge."""

    def __init__(self, home: Path):
        self.home = home

    @property
    def key_path(self) -> Path:
        return self.home / "device_key.pem"

    @property
    def bridge_path(self) -> Path:
        return self.home / "bridge.json"

    def key(self) -> keys.PrivateKey:
        return keys.load_or_create_private_key(self.key_path)

    def save_bridge(self, info: BridgeInfo) -> None:
        self.home.mkdir(parents=True, exist_ok=True)
        self.bridge_path.write_text(json.dumps(asdict(info), indent=2), encoding="utf-8")

    def load_bridge(self) -> BridgeInfo:
        if not self.bridge_path.exists():
            raise TnpError("Not paired yet. Run: tnp-cli pair <link>")
        return BridgeInfo(**json.loads(self.bridge_path.read_text(encoding="utf-8")))


def _ssl_context(url: str, pin: str | None) -> ssl.SSLContext | None:
    parsed = urlparse(url)
    if parsed.scheme == "ws":
        if parsed.hostname not in ("127.0.0.1", "localhost", "::1"):
            raise TnpError("Plain ws:// is only allowed to this computer (bridge --dev mode).")
        return None
    if parsed.scheme != "wss":
        raise TnpError(f"Bridge address must start with wss:// (got {url!r}).")
    if pin:
        # Self-signed bridge: no CA, but only the certificate key from the pairing payload.
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        return ctx
    return ssl.create_default_context()


def _check_pin(ws: ClientConnection, pin: str | None) -> None:
    if not pin:
        return
    from cryptography import x509
    from cryptography.hazmat.primitives import serialization

    der = ws.transport.get_extra_info("ssl_object").getpeercert(binary_form=True)
    spki = x509.load_der_x509_certificate(der).public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    if b64u_encode(hashlib.sha256(spki).digest()) != pin:
        raise TnpError("The bridge's TLS certificate does not match the pinned one.")


async def _open(url: str, pin: str | None) -> ClientConnection:
    try:
        ws = await connect(url, subprotocols=[m.SUBPROTOCOL], ssl=_ssl_context(url, pin),
                           max_size=m.MAX_FRAME, ping_interval=None, open_timeout=10)
    except (OSError, asyncio.TimeoutError) as exc:
        raise TnpError(f"Can't reach the bridge at {url}: {exc}. "
                       "Is it running, and is your private network (Tailscale/WireGuard) on?") from None
    try:
        _check_pin(ws, pin)
    except TnpError:
        await ws.close()
        raise
    return ws


async def _recv(ws: ClientConnection, timeout: float) -> dict:
    try:
        raw = await asyncio.wait_for(ws.recv(), timeout)
    except ConnectionClosed:
        raise TnpError(_closed_reason(ws)) from None
    except asyncio.TimeoutError:
        raise TnpError("The bridge stopped answering.") from None
    return m.decode(raw)


def _closed_reason(ws: ClientConnection) -> str:
    code, reason = ws.close_code, ws.close_reason
    if code == m.CLOSE_REVOKED:
        return "This device was revoked on the bridge. Pair it again with a new key."
    if code == m.CLOSE_BAD_SIGNATURE:
        return f"The bridge refused this device's credentials ({reason})."
    return f"Connection closed ({code} {reason})."


async def _verify_hello(ws: ClientConnection, bridge_id: str | None, bridge_pk: str | None) -> dict:
    """Check the bridge proves the expected identity. With no pinned key (short code
    pairing) the key is accepted here and protected by the SAS comparison."""
    hello = await _recv(ws, 15)
    p = hello.get("params") or {}
    if hello.get("method") != "hello" or not isinstance(p.get("ts"), int):
        raise TnpError("The bridge did not start with a valid hello.")
    try:
        pk = keys.load_public_key(p.get("bridge_pk", ""))
    except EncodingError:
        raise TnpError("The bridge sent an invalid key.") from None
    if keys.key_id(pk) != p.get("bridge_id"):
        raise TnpError("The bridge's id does not match its key.")
    if (bridge_pk and p["bridge_pk"] != bridge_pk) or (bridge_id and p["bridge_id"] != bridge_id):
        raise TnpError("This is not the bridge you paired with (key mismatch). Not connecting.")
    signed = m.hello_signed_data(p["bridge_id"], p.get("nonce_b", ""), p["ts"])
    if not keys.verify(pk, signed, p.get("sig_b", "")):
        raise TnpError("The bridge's hello signature is invalid.")
    if abs(now() - p["ts"]) > m.TS_WINDOW_S:
        raise TnpError("The bridge's clock is more than 2 minutes off from this computer's.")
    return p


async def pair(state: DeviceState, *, name: str, payload: PairingPayload | None = None,
               url: str | None = None, short_code: str | None = None,
               show_sas: Callable[[Sas], None] = lambda sas: None,
               timeout_s: float = 150) -> BridgeInfo:
    """Pair by link payload, or by bridge url + normalized short code."""
    if payload is not None:
        if payload.exp <= now():
            raise TnpError("This pairing link has expired. Run talaria pair again.")
        url, secret, pin = payload.url, payload.pair_token, payload.tls_spki_sha256
        expect_id, expect_pk = payload.bridge_id, payload.bridge_pk
    else:
        assert url and short_code
        secret, pin, expect_id, expect_pk = short_code, None, None, None

    key = state.key()
    device_pk = keys.public_key_b64u(key)
    ws = await _open(url, pin)
    try:
        hello = await _verify_hello(ws, expect_id, expect_pk)
        sas = derive_sas(hello["bridge_pk"], device_pk, secret)
        show_sas(sas)
        ts = now()
        signed = m.pair_signed_data(hello["bridge_id"], hello["nonce_b"], device_pk, secret,
                                    name, PLATFORM, ts)
        params = {"device_pk": device_pk, "name": name, "platform": PLATFORM, "ts": ts,
                  "sig_d": keys.sign(key, signed)}
        params["pair_token" if payload is not None else "short_code"] = secret
        await ws.send(m.encode(m.notification("pair.request", params)))
        reply = await _recv(ws, timeout_s)
    finally:
        await ws.close()

    if reply.get("method") == "pair.rejected":
        raise TnpError(f"Pairing rejected: {(reply.get('params') or {}).get('reason')}")
    if reply.get("method") != "pair.accepted":
        raise TnpError("Unexpected reply from the bridge.")
    accepted = reply["params"]
    info = BridgeInfo(url=url, bridge_id=hello["bridge_id"], bridge_pk=hello["bridge_pk"],
                      device_id=accepted["device_id"], name=accepted["name"], tls_spki_sha256=pin)
    if info.device_id != keys.key_id(key):
        raise TnpError("The bridge registered a different device id.")
    state.save_bridge(info)
    return info


class Session:
    def __init__(self, ws: ClientConnection, session_id: str):
        self.ws = ws
        self.session_id = session_id
        self._next = 0

    async def ping(self, timeout: float = 10) -> dict:
        self._next += 1
        msg_id = f"d-{self._next}"
        await self.ws.send(m.encode(m.request(msg_id, "ping")))
        while True:
            msg = await _recv(self.ws, timeout)
            if msg.get("id") == msg_id and "result" in msg:
                return msg["result"]
            await self.handle(msg)

    async def handle(self, msg: dict) -> None:
        if msg.get("method") == "ping" and "id" in msg:
            await self.ws.send(m.encode(m.result(msg["id"], {"ts": now()})))
        elif "id" in msg and "method" in msg:
            await self.ws.send(m.encode(m.error(msg["id"], m.METHOD_NOT_FOUND, "Not supported by tnp-cli")))

    async def run_forever(self) -> None:
        while True:
            await self.handle(await _recv(self.ws, 120))

    async def close(self) -> None:
        await self.ws.close()


async def open_session(state: DeviceState) -> Session:
    info = state.load_bridge()
    key = state.key()
    ws = await _open(info.url, info.tls_spki_sha256)
    try:
        hello = await _verify_hello(ws, info.bridge_id, info.bridge_pk)
        nonce_d = b64u_encode(secrets.token_bytes(16))
        ts = now()
        sig_d = keys.sign(key, m.auth_signed_data(info.bridge_id, info.device_id,
                                                   hello["nonce_b"], nonce_d, ts))
        await ws.send(m.encode(m.request("a1", "auth", {
            "device_id": info.device_id, "nonce_d": nonce_d, "ts": ts, "sig_d": sig_d, "resume": False})))
        ok = await _recv(ws, 15)
        if ok.get("id") != "a1" or "result" not in ok:
            raise TnpError("Authentication failed.")
        await ws.send(m.encode(m.notification("capabilities.announce", {
            "device": {"name": info.name, "platform": PLATFORM},
            "capabilities": [], "events": [], "relayed": []})))
        ready = await _recv(ws, 15)
        if ready.get("method") != "ready":
            raise TnpError("The bridge did not send ready.")
    except BaseException:
        await ws.close()
        raise
    return Session(ws, ok["result"]["session_id"])
