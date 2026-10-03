"""M0 exit: pair the CLI client by link, then connect, against a real bridge."""

from __future__ import annotations

import asyncio
import datetime
import hashlib
import ssl
from pathlib import Path

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

from talaria_bridge.operator import confirm_request, create_pairing, wait_for_request
from talaria_bridge.protocol import keys
from talaria_bridge.protocol.encoding import b64u_encode, now
from talaria_bridge.protocol.pairing import PairingPayload, normalize_short_code
from talaria_bridge.registry import Registry
from talaria_bridge.server import BridgeServer, ServerSettings
from tnp_cli.client import DeviceState, TnpError, open_session, pair


def self_signed(tmp: Path) -> tuple[Path, Path, str]:
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "talaria-test")])
    t = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(t - datetime.timedelta(minutes=1)).not_valid_after(t + datetime.timedelta(days=1))
            .sign(key, hashes.SHA256()))
    cert_path, key_path = tmp / "cert.pem", tmp / "key.pem"
    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                           serialization.NoEncryption()))
    spki = key.public_key().public_bytes(serialization.Encoding.DER,
                                         serialization.PublicFormat.SubjectPublicKeyInfo)
    return cert_path, key_path, b64u_encode(hashlib.sha256(spki).digest())


class Operator:
    """Runs the `talaria pair` confirmation in a thread, answering like a person would."""

    def __init__(self, registry: Registry, token: str, deadline: int, answer: str):
        self.registry, self.token, self.deadline, self.answer = registry, token, deadline, answer
        self.shown: list[str] = []

    def run(self) -> str:
        # Its own connection, like the separate `talaria pair` process.
        registry = Registry(self.registry.path)
        try:
            req = wait_for_request(registry, self.token, self.deadline, poll_s=0.02)
            assert req is not None
            return confirm_request(registry, req, timeout_s=5, read=lambda _: self.answer,
                                   out=self.shown.append)
        finally:
            registry.close()


@pytest.fixture
async def bridge(tmp_path: Path, request):
    tls = getattr(request, "param", None) == "tls"
    settings = ServerSettings(port=0, approval_timeout_s=5, revocation_check_s=0.05, decision_poll_s=0.02)
    pin = None
    if tls:
        cert, key, pin = self_signed(tmp_path)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(cert, key)
        settings.ssl = ctx
    registry = Registry(tmp_path / "bridge.db")
    server = BridgeServer(registry, keys.generate_key(), settings)
    async with server.serve() as ws_server:
        port = ws_server.sockets[0].getsockname()[1]
        url = f"{'wss' if tls else 'ws'}://127.0.0.1:{port}/tnp"
        yield server, registry, url, pin
    registry.close()


async def pair_by_link(bridge, state: DeviceState, answer: str = "y"):
    server, registry, url, pin = bridge
    new = create_pairing(registry, server.key, url=url, name="Test client", tls_spki_sha256=pin)
    operator = Operator(registry, new.payload.pair_token, new.payload.exp, answer)
    decision = asyncio.get_running_loop().run_in_executor(None, operator.run)
    shown = []
    try:
        info = await pair(state, name="ignored", payload=PairingPayload.from_link(new.link),
                          show_sas=shown.append)
    finally:
        result = await decision
    return info, shown[0], operator, result


async def test_pair_by_link_then_connect(bridge, tmp_path):
    state = DeviceState(tmp_path / "device")
    info, sas, operator, decision = await pair_by_link(bridge, state)
    assert decision == "approved"
    assert sas.emoji in operator.shown[0] and sas.digits[:3] in operator.shown[0]
    assert info.name == "Test client"
    assert state.bridge_path.exists() and state.key_path.exists()

    session = await open_session(state)
    assert "ts" in await session.ping()
    await session.close()


@pytest.mark.parametrize("bridge", ["tls"], indirect=True)
async def test_pair_and_connect_over_pinned_tls(bridge, tmp_path):
    state = DeviceState(tmp_path / "device")
    info, *_ = await pair_by_link(bridge, state)
    assert info.tls_spki_sha256
    session = await open_session(state)
    await session.ping()
    await session.close()


@pytest.mark.parametrize("bridge", ["tls"], indirect=True)
async def test_wrong_tls_pin_is_refused(bridge, tmp_path):
    state = DeviceState(tmp_path / "device")
    await pair_by_link(bridge, state)
    info = state.load_bridge()
    info.tls_spki_sha256 = "A" * 43
    state.save_bridge(info)
    with pytest.raises(TnpError, match="certificate"):
        await open_session(state)


async def test_operator_rejects(bridge, tmp_path):
    state = DeviceState(tmp_path / "device")
    with pytest.raises(TnpError, match="sas_rejected"):
        await pair_by_link(bridge, state, answer="n")
    assert not state.bridge_path.exists()


async def test_pair_by_short_code(bridge, tmp_path):
    server, registry, url, _ = bridge
    new = create_pairing(registry, server.key, url=url, name=None)
    operator = Operator(registry, new.payload.pair_token, new.payload.exp, "yes")
    decision = asyncio.get_running_loop().run_in_executor(None, operator.run)
    info = await pair(DeviceState(tmp_path / "d"), name="Laptop", url=url,
                      short_code=normalize_short_code(new.short_code.lower()))
    assert await decision == "approved"
    assert info.name == "Laptop"


async def test_revoked_client_is_disconnected(bridge, tmp_path):
    _, registry, _, _ = bridge
    state = DeviceState(tmp_path / "device")
    info, *_ = await pair_by_link(bridge, state)
    session = await open_session(state)
    registry.revoke_device(info.device_id, now())
    with pytest.raises(TnpError, match="revoked"):
        await asyncio.wait_for(session.run_forever(), 5)
    with pytest.raises(TnpError, match="revoked"):
        await open_session(state)


async def test_wrong_bridge_key_is_refused(bridge, tmp_path):
    state = DeviceState(tmp_path / "device")
    await pair_by_link(bridge, state)
    info = state.load_bridge()
    impostor = keys.generate_key()
    info.bridge_pk, info.bridge_id = keys.public_key_b64u(impostor), keys.key_id(impostor)
    state.save_bridge(info)
    with pytest.raises(TnpError, match="not the bridge"):
        await open_session(state)


async def test_expired_link_is_refused_locally(bridge, tmp_path):
    server, registry, url, _ = bridge
    new = create_pairing(registry, server.key, url=url, name=None)
    expired = PairingPayload(**{**new.payload.__dict__, "exp": now() - 1})
    with pytest.raises(TnpError, match="expired"):
        await pair(DeviceState(tmp_path / "d"), name="x", payload=expired)


def test_plain_ws_only_to_loopback():
    from tnp_cli.client import _ssl_context

    assert _ssl_context("ws://127.0.0.1:8765/tnp", None) is None
    with pytest.raises(TnpError):
        _ssl_context("ws://meep-vps.tailnet.ts.net/tnp", None)
