"""Regenerate the TNP v0 test vectors in this folder.

    python spec/vectors/generate.py

Run from the talaria/ folder with talaria-bridge installed. The keys are fixed test keys
(never use them for anything real). ECDSA signatures are randomized, so regenerating
changes the signature values; any valid signature over the listed bytes is correct, and
implementations must accept the published ones.
"""

from __future__ import annotations

import json
from pathlib import Path

from talaria_bridge.protocol import keys
from talaria_bridge.protocol import messages as m
from talaria_bridge.protocol.encoding import frame
from talaria_bridge.protocol.pairing import PairingPayload, normalize_short_code
from talaria_bridge.protocol.sas import derive_sas

HERE = Path(__file__).parent

BRIDGE_SCALAR = 0x1111111111111111111111111111111111111111111111111111111111111111
DEVICE_SCALAR = 0x2222222222222222222222222222222222222222222222222222222222222222
OTHER_SCALAR = 0x3333333333333333333333333333333333333333333333333333333333333333

bridge = keys.private_key_from_scalar(BRIDGE_SCALAR)
device = keys.private_key_from_scalar(DEVICE_SCALAR)
other = keys.private_key_from_scalar(OTHER_SCALAR)
BRIDGE_PK, DEVICE_PK = keys.public_key_b64u(bridge), keys.public_key_b64u(device)
BRIDGE_ID, DEVICE_ID = keys.key_id(bridge), keys.key_id(device)

NONCE_B = "q83vEjRWeJCrze8SNFZ4kA"
NONCE_D = "3q2-7wEjRWeJq83vASNFZw"
PAIR_TOKEN = "AAECAwQFBgcICQoLDA0ODw"
SHORT_CODE = "HX492KQ7"
TS = 1790000000
NAME, PLATFORM = "OnePlus 10 Pro", "android"


def write(name: str, data: dict) -> None:
    (HERE / name).write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def keys_vectors() -> dict:
    return {
        "description": "Wire public keys (base64url DER SPKI) and ids (base32 SHA-256, 26 chars).",
        "keys": [
            {"role": role, "private_scalar_hex": f"{scalar:064x}",
             "public_key": keys.public_key_b64u(k), "id": keys.key_id(k)}
            for role, scalar, k in (("bridge", BRIDGE_SCALAR, bridge), ("device", DEVICE_SCALAR, device),
                                    ("other", OTHER_SCALAR, other))
        ],
    }


def frame_vectors() -> dict:
    cases = [[], [""], ["tnp0-hello"], ["a", "bc"], ["ab", "c"], ["🦊", "1790000000"]]
    return {
        "description": "frame(): each field as a 4-byte big-endian length then its UTF-8 bytes.",
        "cases": [{"fields": c, "hex": frame(*c).hex()} for c in cases],
    }


def signature_vectors() -> dict:
    hello = m.hello_signed_data(BRIDGE_ID, NONCE_B, TS)
    auth = m.auth_signed_data(BRIDGE_ID, DEVICE_ID, NONCE_B, NONCE_D, TS)
    pair = m.pair_signed_data(BRIDGE_ID, NONCE_B, DEVICE_PK, PAIR_TOKEN, NAME, PLATFORM, TS)
    sig_hello, sig_auth, sig_pair = keys.sign(bridge, hello), keys.sign(device, auth), keys.sign(device, pair)
    auth_other_nonce = m.auth_signed_data(BRIDGE_ID, DEVICE_ID, "AAAAAAAAAAAAAAAAAAAAAA", NONCE_D, TS)
    return {
        "description": "ECDSA P-256 / SHA-256 signatures (DER, base64url) over frame() data. "
                       "Verifiers must accept every case with valid=true and reject every case with valid=false.",
        "inputs": {"bridge_id": BRIDGE_ID, "device_id": DEVICE_ID, "bridge_pk": BRIDGE_PK,
                   "device_pk": DEVICE_PK, "nonce_b": NONCE_B, "nonce_d": NONCE_D,
                   "pair_token": PAIR_TOKEN, "name": NAME, "platform": PLATFORM, "ts": TS},
        "cases": [
            {"name": "hello", "signer": "bridge", "data_hex": hello.hex(), "sig": sig_hello, "valid": True},
            {"name": "auth", "signer": "device", "data_hex": auth.hex(), "sig": sig_auth, "valid": True},
            {"name": "pair.request", "signer": "device", "data_hex": pair.hex(), "sig": sig_pair, "valid": True},
            {"name": "auth signed by the wrong key", "signer": "device", "data_hex": auth.hex(),
             "sig": keys.sign(other, auth), "valid": False},
            {"name": "auth replayed against a new nonce_b", "signer": "device",
             "data_hex": auth_other_nonce.hex(), "sig": sig_auth, "valid": False},
            {"name": "hello with ts changed", "signer": "bridge",
             "data_hex": m.hello_signed_data(BRIDGE_ID, NONCE_B, TS + 1).hex(), "sig": sig_hello, "valid": False},
        ],
    }


def sas_vectors() -> dict:
    cases = []
    for label, secret, dpk in (("pair_token", PAIR_TOKEN, DEVICE_PK), ("short code", SHORT_CODE, DEVICE_PK),
                               ("other device key", PAIR_TOKEN, keys.public_key_b64u(other))):
        sas = derive_sas(BRIDGE_PK, dpk, secret)
        cases.append({"name": label, "bridge_pk": BRIDGE_PK, "device_pk": dpk, "pairing_secret": secret,
                      "digits": sas.digits, "emoji_indices": list(sas.emoji_indices), "emoji": sas.emoji})
    return {
        "description": "h = SHA-256(frame(\"tnp0-sas\", bridge_pk, device_pk, pairing_secret)); "
                       "digits = uint32_be(h[0:4]) mod 10^6, zero-padded; emoji = h[4], h[5], h[6] mod 64 "
                       "into spec/sas-emoji.json.",
        "cases": cases,
    }


def pairing_vectors() -> dict:
    payload = PairingPayload(url="wss://meep-vps.tailnet.ts.net/tnp", bridge_id=BRIDGE_ID,
                             bridge_pk=BRIDGE_PK, pair_token=PAIR_TOKEN, exp=TS + 300)
    pinned = PairingPayload(url="wss://100.101.102.103:8765/tnp", bridge_id=BRIDGE_ID,
                            bridge_pk=BRIDGE_PK, pair_token=PAIR_TOKEN, exp=TS + 300,
                            tls_spki_sha256="47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU")
    typed = ["hx49-2kq7", "HX49 2KQ7", "HX492KQ7", "hx49-2kqo"]
    return {
        "description": "Links are talaria://pair# + base64url(compact JSON with sorted keys). "
                       "Decoders must accept any key order. Short codes normalize typed input.",
        "links": [{"payload": p.to_json(), "link": p.to_link()} for p in (payload, pinned)],
        "short_codes": [{"typed": t, "normalized": normalize_short_code(t)} for t in typed],
        "short_codes_invalid": ["HX49-2KQ", "HX49-2KQ7U", "HX49-2KQ!"],
    }


if __name__ == "__main__":
    write("keys.json", keys_vectors())
    write("frame.json", frame_vectors())
    write("signatures.json", signature_vectors())
    write("sas.json", sas_vectors())
    write("pairing.json", pairing_vectors())
    print(f"wrote vectors to {HERE}")
