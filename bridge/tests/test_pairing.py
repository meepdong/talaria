"""M0 exit criteria for pairing: good link, bad signatures, expired and reused tokens,
rejected SAS, revoked devices, and the short-code path."""

from __future__ import annotations

import asyncio

from conftest import Bridge, Device, check, paired_device

from talaria_bridge.protocol import keys
from talaria_bridge.protocol.encoding import now
from talaria_bridge.protocol.pairing import PairingPayload, format_short_code, normalize_short_code
from talaria_bridge.protocol.sas import derive_sas


async def test_pair_by_link(bridge: Bridge):
    device = Device(name="OnePlus 10 Pro")
    pairing = bridge.new_pairing(name=None)
    payload = PairingPayload.from_link(pairing.link)  # what the device would scan or paste
    decide = asyncio.ensure_future(bridge.decide(payload.pair_token))
    reply = await device.pair(bridge.url, token=payload.pair_token)
    await decide

    check("pair.accepted", reply)
    assert reply["params"] == {"device_id": device.id, "name": "OnePlus 10 Pro"}
    stored = bridge.registry.get_device(device.id)
    assert stored.public_key == device.pk and not stored.revoked


async def test_both_sides_compute_the_same_sas(bridge: Bridge):
    device = Device()
    pairing = bridge.new_pairing()
    token = pairing.payload.pair_token
    pair = asyncio.ensure_future(device.pair(bridge.url, token=token))
    while (req := bridge.registry.waiting_request_for(token)) is None:
        await asyncio.sleep(0.02)
    expected = derive_sas(pairing.payload.bridge_pk, device.pk, token)
    assert (req.sas_digits, req.sas_emoji) == (expected.digits, expected.emoji)
    bridge.registry.decide_pair_request(req.id, "approved", now())
    assert (await pair)["method"] == "pair.accepted"


async def test_operator_name_overrides_device_name(bridge: Bridge):
    device = Device(name="localhost")
    pairing = bridge.new_pairing(name="Maurice's laptop")
    decide = asyncio.ensure_future(bridge.decide(pairing.payload.pair_token))
    reply = await device.pair(bridge.url, token=pairing.payload.pair_token)
    await decide
    assert reply["params"]["name"] == "Maurice's laptop"


async def test_rejected_sas(bridge: Bridge):
    device = Device()
    pairing = bridge.new_pairing()
    decide = asyncio.ensure_future(bridge.decide(pairing.payload.pair_token, "rejected"))
    reply = await device.pair(bridge.url, token=pairing.payload.pair_token)
    await decide
    assert check("pair.rejected", reply)["params"]["reason"] == "sas_rejected"
    assert bridge.registry.get_device(device.id) is None


async def test_rejected_token_cannot_be_retried(bridge: Bridge):
    pairing = bridge.new_pairing()
    decide = asyncio.ensure_future(bridge.decide(pairing.payload.pair_token, "rejected"))
    await Device().pair(bridge.url, token=pairing.payload.pair_token)
    await decide
    reply = await Device().pair(bridge.url, token=pairing.payload.pair_token)
    assert reply["params"]["reason"] == "already_used"


async def test_approval_timeout(bridge: Bridge):
    device = Device()
    pairing = bridge.new_pairing()
    reply = await device.pair(bridge.url, token=pairing.payload.pair_token)  # nobody answers
    assert reply["params"]["reason"] == "timeout"
    assert bridge.registry.get_device(device.id) is None


async def test_reused_token(bridge: Bridge):
    pairing = bridge.new_pairing()
    decide = asyncio.ensure_future(bridge.decide(pairing.payload.pair_token))
    assert (await Device().pair(bridge.url, token=pairing.payload.pair_token))["method"] == "pair.accepted"
    await decide
    reply = await Device().pair(bridge.url, token=pairing.payload.pair_token)
    assert reply["params"]["reason"] == "already_used"


async def test_expired_token(bridge: Bridge):
    pairing = bridge.new_pairing()
    bridge.registry.db.execute("UPDATE pairings SET expires_at = ?", (now() - 1,))
    reply = await Device().pair(bridge.url, token=pairing.payload.pair_token)
    assert reply["params"]["reason"] == "expired"


async def test_unknown_token(bridge: Bridge):
    reply = await Device().pair(bridge.url, token="AAAAAAAAAAAAAAAAAAAAAA")
    assert reply["params"]["reason"] == "unknown_token"


async def test_bad_signature_does_not_burn_the_token(bridge: Bridge):
    device = Device()
    pairing = bridge.new_pairing()
    token = pairing.payload.pair_token
    reply = await device.pair(bridge.url, token=token, signer=keys.generate_key())
    assert reply["params"]["reason"] == "bad_signature"

    decide = asyncio.ensure_future(bridge.decide(token))
    assert (await device.pair(bridge.url, token=token))["method"] == "pair.accepted"
    await decide


async def test_stale_timestamp(bridge: Bridge):
    pairing = bridge.new_pairing()
    reply = await Device().pair(bridge.url, token=pairing.payload.pair_token, ts=now() - 600)
    assert reply["params"]["reason"] == "invalid_request"


async def test_revoked_device_cannot_pair_again(bridge: Bridge):
    device = await paired_device(bridge)
    bridge.registry.revoke_device(device.id, now())
    pairing = bridge.new_pairing()
    reply = await device.pair(bridge.url, token=pairing.payload.pair_token)
    assert reply["params"]["reason"] == "revoked"


async def test_pair_by_short_code(bridge: Bridge):
    device = Device()
    pairing = bridge.new_pairing()
    typed = format_short_code(pairing.short_code).lower()
    decide = asyncio.ensure_future(bridge.decide(pairing.payload.pair_token))
    reply = await device.pair(bridge.url, code=normalize_short_code(typed))
    await decide
    assert reply["method"] == "pair.accepted"


async def test_short_code_guessing_is_rate_limited(bridge: Bridge):
    pairing = bridge.new_pairing()
    wrong = "00000000" if pairing.short_code != "00000000" else "11111111"
    for _ in range(5):
        assert (await Device().pair(bridge.url, code=wrong))["params"]["reason"] == "unknown_token"
    reply = await Device().pair(bridge.url, code=pairing.short_code)
    assert reply["params"]["reason"] == "rate_limited"
    # The link still works.
    decide = asyncio.ensure_future(bridge.decide(pairing.payload.pair_token))
    assert (await Device().pair(bridge.url, token=pairing.payload.pair_token))["method"] == "pair.accepted"
    await decide


async def test_malformed_pair_request(bridge: Bridge):
    pairing = bridge.new_pairing()
    device = Device()
    ws, hello = await device.open(bridge.url)
    request = device.pair_request(hello, token=pairing.payload.pair_token)
    request["params"]["short_code"] = pairing.short_code  # both secrets: invalid
    from talaria_bridge.protocol import messages as m
    await ws.send(m.encode(request))
    reply = m.decode(await ws.recv())
    assert reply["params"]["reason"] == "invalid_request"
