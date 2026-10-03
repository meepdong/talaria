from __future__ import annotations

import asyncio
import json
import secrets
from dataclasses import dataclass, field
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator
from websockets.asyncio.client import ClientConnection, connect

from talaria_bridge.operator import create_pairing
from talaria_bridge.protocol import keys
from talaria_bridge.protocol import messages as m
from talaria_bridge.protocol.encoding import b64u_encode, now
from talaria_bridge.registry import Registry
from talaria_bridge.server import BridgeServer, ServerSettings

SPEC = Path(__file__).resolve().parents[2] / "spec"


def schema(name: str) -> Draft202012Validator:
    return Draft202012Validator(json.loads((SPEC / "schemas" / f"{name}.schema.json").read_text()))


def check(name: str, msg: dict) -> dict:
    schema(name).validate(msg)
    return msg


@dataclass
class Bridge:
    server: BridgeServer
    registry: Registry
    url: str

    def new_pairing(self, name: str | None = "Test device", ttl_s: int = 300):
        return create_pairing(self.registry, self.server.key, url=self.url, name=name, ttl_s=ttl_s)

    async def decide(self, token: str, state: str = "approved", timeout: float = 5) -> None:
        """Play the operator: wait for the pair request on this token and decide it."""
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        while loop.time() < deadline:
            req = self.registry.waiting_request_for(token)
            if req is not None:
                assert self.registry.decide_pair_request(req.id, state, now())
                return
            await asyncio.sleep(0.02)
        raise AssertionError("no pair request arrived")


@pytest.fixture
def settings() -> ServerSettings:
    return ServerSettings(port=0, approval_timeout_s=2, ping_interval_s=30, idle_timeout_s=90,
                          revocation_check_s=0.05, decision_poll_s=0.02)


@pytest.fixture
async def bridge(tmp_path: Path, settings: ServerSettings):
    registry = Registry(tmp_path / "bridge.db")
    server = BridgeServer(registry, keys.generate_key(), settings)
    async with server.serve() as ws_server:
        port = ws_server.sockets[0].getsockname()[1]
        yield Bridge(server, registry, f"ws://127.0.0.1:{port}/tnp")
    registry.close()


@dataclass
class Device:
    """A scriptable device that can also misbehave."""
    key: keys.PrivateKey = field(default_factory=keys.generate_key)
    name: str = "Test device"
    platform: str = "cli"

    @property
    def pk(self) -> str:
        return keys.public_key_b64u(self.key)

    @property
    def id(self) -> str:
        return keys.key_id(self.key)

    async def open(self, url: str) -> tuple[ClientConnection, dict]:
        ws = await connect(url, subprotocols=[m.SUBPROTOCOL], ping_interval=None)
        hello = check("hello", m.decode(await ws.recv()))
        p = hello["params"]
        assert keys.verify(keys.load_public_key(p["bridge_pk"]),
                           m.hello_signed_data(p["bridge_id"], p["nonce_b"], p["ts"]), p["sig_b"])
        return ws, p

    def pair_request(self, hello: dict, *, token: str | None = None, code: str | None = None,
                     ts: int | None = None, signer: keys.PrivateKey | None = None) -> dict:
        ts = now() if ts is None else ts
        secret = token if token is not None else code
        signed = m.pair_signed_data(hello["bridge_id"], hello["nonce_b"], self.pk, secret,
                                    self.name, self.platform, ts)
        params = {"device_pk": self.pk, "name": self.name, "platform": self.platform, "ts": ts,
                  "sig_d": keys.sign(signer or self.key, signed)}
        params["pair_token" if token is not None else "short_code"] = secret
        return check("pair.request", m.notification("pair.request", params))

    async def pair(self, url: str, **kwargs) -> dict:
        ws, hello = await self.open(url)
        await ws.send(m.encode(self.pair_request(hello, **kwargs)))
        reply = m.decode(await ws.recv())
        await ws.wait_closed()
        return reply

    def auth_request(self, hello: dict, *, ts: int | None = None, nonce_d: str | None = None,
                     signer: keys.PrivateKey | None = None) -> dict:
        ts = now() if ts is None else ts
        nonce_d = nonce_d or b64u_encode(secrets.token_bytes(16))
        sig = keys.sign(signer or self.key,
                        m.auth_signed_data(hello["bridge_id"], self.id, hello["nonce_b"], nonce_d, ts))
        return check("auth", m.request("a1", "auth", {
            "device_id": self.id, "nonce_d": nonce_d, "ts": ts, "sig_d": sig, "resume": False}))

    async def authenticate(self, url: str) -> ClientConnection:
        ws, hello = await self.open(url)
        await ws.send(m.encode(self.auth_request(hello)))
        check("auth.ok", m.decode(await ws.recv()))
        await ws.send(m.encode(check("capabilities.announce", m.notification("capabilities.announce", {
            "device": {"name": self.name, "platform": self.platform},
            "capabilities": [{"name": "notify.show", "v": 1, "tier": 0}], "events": [], "relayed": []}))))
        check("ready", m.decode(await ws.recv()))
        return ws


async def paired_device(bridge: Bridge) -> Device:
    device = Device()
    pairing = bridge.new_pairing()
    decide = asyncio.ensure_future(bridge.decide(pairing.payload.pair_token))
    reply = await device.pair(bridge.url, token=pairing.payload.pair_token)
    await decide
    assert reply["method"] == "pair.accepted", reply
    return device
