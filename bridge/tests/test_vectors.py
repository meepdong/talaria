"""The protocol core must reproduce every published test vector in spec/vectors."""

from __future__ import annotations

import json

import pytest
from conftest import SPEC

from talaria_bridge.protocol import keys
from talaria_bridge.protocol.encoding import EncodingError, b64u_decode, frame
from talaria_bridge.protocol.pairing import PairingPayload, normalize_short_code
from talaria_bridge.protocol.sas import EMOJI, derive_sas


def vectors(name: str) -> dict:
    return json.loads((SPEC / "vectors" / f"{name}.json").read_text(encoding="utf-8"))


def test_keys_and_ids():
    for case in vectors("keys")["keys"]:
        key = keys.private_key_from_scalar(int(case["private_scalar_hex"], 16))
        assert keys.public_key_b64u(key) == case["public_key"]
        assert keys.key_id(key) == case["id"] == keys.key_id(case["public_key"])
        assert keys.load_public_key(case["public_key"])


def test_frame():
    for case in vectors("frame")["cases"]:
        assert frame(*case["fields"]).hex() == case["hex"]


def test_signatures():
    by_role = {c["role"]: keys.load_public_key(c["public_key"]) for c in vectors("keys")["keys"]}
    for case in vectors("signatures")["cases"]:
        ok = keys.verify(by_role[case["signer"]], bytes.fromhex(case["data_hex"]), case["sig"])
        assert ok == case["valid"], case["name"]


def test_signed_data_builders_match():
    from talaria_bridge.protocol import messages as m

    data = vectors("signatures")
    i = data["inputs"]
    built = {
        "hello": m.hello_signed_data(i["bridge_id"], i["nonce_b"], i["ts"]),
        "auth": m.auth_signed_data(i["bridge_id"], i["device_id"], i["nonce_b"], i["nonce_d"], i["ts"]),
        "pair.request": m.pair_signed_data(i["bridge_id"], i["nonce_b"], i["device_pk"], i["pair_token"],
                                           i["name"], i["platform"], i["ts"]),
    }
    for case in data["cases"]:
        if case["name"] in built:
            assert built[case["name"]].hex() == case["data_hex"]


def test_sas():
    for case in vectors("sas")["cases"]:
        sas = derive_sas(case["bridge_pk"], case["device_pk"], case["pairing_secret"])
        assert sas.digits == case["digits"]
        assert list(sas.emoji_indices) == case["emoji_indices"]
        assert sas.emoji == case["emoji"]


def test_emoji_table_matches_spec():
    table = json.loads((SPEC / "sas-emoji.json").read_text(encoding="utf-8"))["emoji"]
    assert [(e["emoji"], e["name"]) for e in table] == list(EMOJI)


def test_pairing_links():
    data = vectors("pairing")
    for case in data["links"]:
        payload = PairingPayload.from_link(case["link"])
        assert payload.to_json() == case["payload"]
        assert payload.to_link() == case["link"]
    for case in data["short_codes"]:
        assert normalize_short_code(case["typed"]) == case["normalized"]
    for typed in data["short_codes_invalid"]:
        with pytest.raises(EncodingError):
            normalize_short_code(typed)


@pytest.mark.parametrize("text", ["AA==", "A+B/", "AB", "a b"])
def test_strict_base64url(text):
    with pytest.raises(EncodingError):
        b64u_decode(text)


def test_rejects_other_curves():
    from cryptography.hazmat.primitives.asymmetric import ec

    other = ec.generate_private_key(ec.SECP384R1())
    with pytest.raises(EncodingError):
        keys.load_public_key(keys.public_key_b64u(other))
