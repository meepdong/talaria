"""M0 exit criteria for sessions: bad signatures, replays, revoked devices, heartbeats."""

from __future__ import annotations

import asyncio

import pytest
from conftest import Bridge, Device, check, paired_device
from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed, InvalidStatus

from talaria_bridge.protocol import keys
from talaria_bridge.protocol import messages as m
from talaria_bridge.protocol.encoding import now


async def closed_with(ws) -> int:
    with pytest.raises(ConnectionClosed):
        while True:
            await asyncio.wait_for(ws.recv(), 5)
    return ws.close_code


async def test_session_handshake_and_ping(bridge: Bridge):
    device = await paired_device(bridge)
    ws = await device.authenticate(bridge.url)
    await ws.send(m.encode(m.request("d-1", "ping")))
    check("ping.result", m.decode(await ws.recv()))
    await ws.send(m.encode(m.request("d-2", "no.such.method")))
    reply = check("error", m.decode(await ws.recv()))
    assert reply["error"]["code"] == m.METHOD_NOT_FOUND
    await ws.close()
    assert bridge.registry.get_device(device.id).last_seen is not None


async def test_bad_signature(bridge: Bridge):
    device = await paired_device(bridge)
    ws, hello = await device.open(bridge.url)
    await ws.send(m.encode(device.auth_request(hello, signer=keys.generate_key())))
    assert await closed_with(ws) == m.CLOSE_BAD_SIGNATURE


async def test_replayed_auth_on_a_new_connection(bridge: Bridge):
    device = await paired_device(bridge)
    ws, hello = await device.open(bridge.url)
    captured = device.auth_request(hello)
    await ws.send(m.encode(captured))
    check("auth.ok", m.decode(await ws.recv()))
    await ws.close()

    ws2, _ = await device.open(bridge.url)  # new nonce_b, so the old signature no longer fits
    await ws2.send(m.encode(captured))
    assert await closed_with(ws2) == m.CLOSE_BAD_SIGNATURE


async def test_repeated_device_nonce(bridge: Bridge):
    device = await paired_device(bridge)
    ws, hello = await device.open(bridge.url)
    await ws.send(m.encode(device.auth_request(hello, nonce_d="AAAAAAAAAAAAAAAAAAAAAA")))
    check("auth.ok", m.decode(await ws.recv()))
    await ws.close()

    ws2, hello2 = await device.open(bridge.url)
    await ws2.send(m.encode(device.auth_request(hello2, nonce_d="AAAAAAAAAAAAAAAAAAAAAA")))
    assert await closed_with(ws2) == m.CLOSE_BAD_SIGNATURE


async def test_stale_timestamp(bridge: Bridge):
    device = await paired_device(bridge)
    ws, hello = await device.open(bridge.url)
    await ws.send(m.encode(device.auth_request(hello, ts=now() - 121)))
    assert await closed_with(ws) == m.CLOSE_BAD_SIGNATURE


async def test_unknown_device(bridge: Bridge):
    device = Device()
    ws, hello = await device.open(bridge.url)
    await ws.send(m.encode(device.auth_request(hello)))
    assert await closed_with(ws) == m.CLOSE_BAD_SIGNATURE


async def test_revoked_device(bridge: Bridge):
    device = await paired_device(bridge)
    bridge.registry.revoke_device(device.id, now())
    ws, hello = await device.open(bridge.url)
    await ws.send(m.encode(device.auth_request(hello)))
    assert await closed_with(ws) == m.CLOSE_REVOKED


async def test_revoking_closes_an_open_session(bridge: Bridge):
    device = await paired_device(bridge)
    ws = await device.authenticate(bridge.url)
    bridge.registry.revoke_device(device.id, now())
    assert await closed_with(ws) == m.CLOSE_REVOKED


async def test_must_start_with_auth_or_pairing(bridge: Bridge):
    device = Device()
    ws, _ = await device.open(bridge.url)
    await ws.send(m.encode(m.request("x", "ping")))
    reply = check("error", m.decode(await ws.recv()))
    assert reply["error"]["code"] == m.NOT_AUTHENTICATED
    assert await closed_with(ws) == m.CLOSE_PROTOCOL


async def test_subprotocol_required(bridge: Bridge):
    with pytest.raises(InvalidStatus) as exc:
        await connect(bridge.url, ping_interval=None)
    assert exc.value.response.status_code == 400


async def test_unknown_path(bridge: Bridge):
    with pytest.raises(InvalidStatus):
        await connect(bridge.url.replace("/tnp", "/other"), subprotocols=[m.SUBPROTOCOL])


class TestHeartbeat:
    @pytest.fixture
    def settings(self, settings):
        settings.ping_interval_s = 0.1
        settings.idle_timeout_s = 0.3
        return settings

    async def test_bridge_pings_when_idle(self, bridge: Bridge):
        device = await paired_device(bridge)
        ws = await device.authenticate(bridge.url)
        ping = check("ping", m.decode(await asyncio.wait_for(ws.recv(), 2)))
        await ws.send(m.encode(m.result(ping["id"], {"ts": now()})))
        await ws.close()

    async def test_silent_device_is_dropped(self, bridge: Bridge):
        device = await paired_device(bridge)
        ws = await device.authenticate(bridge.url)
        assert await closed_with(ws) == m.CLOSE_TIMEOUT  # never answers the pings

    async def test_answering_pings_keeps_the_session(self, bridge: Bridge):
        device = await paired_device(bridge)
        ws = await device.authenticate(bridge.url)
        loop = asyncio.get_running_loop()
        end = loop.time() + 1.0  # over three idle timeouts
        while loop.time() < end:
            msg = m.decode(await asyncio.wait_for(ws.recv(), 1))
            await ws.send(m.encode(m.result(msg["id"], {"ts": now()})))
        await ws.send(m.encode(m.request("d-1", "ping")))
        while "result" not in (msg := m.decode(await ws.recv())) or msg["id"] != "d-1":
            pass
        await ws.close()
