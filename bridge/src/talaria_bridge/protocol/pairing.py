"""Pairing payloads, talaria:// links and short codes (PROTOCOL §3.2, spec/README.md §3)."""

from __future__ import annotations

import json
import secrets
from dataclasses import dataclass

from .encoding import EncodingError, b64u_decode, b64u_encode

LINK_PREFIX = "talaria://pair#"
CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
SHORT_CODE_LEN = 8


@dataclass(frozen=True)
class PairingPayload:
    url: str
    bridge_id: str
    bridge_pk: str
    pair_token: str
    exp: int
    tls_spki_sha256: str | None = None

    def to_json(self) -> dict:
        data = {
            "tnp": 0,
            "url": self.url,
            "bridge_id": self.bridge_id,
            "bridge_pk": self.bridge_pk,
            "pair_token": self.pair_token,
            "exp": self.exp,
        }
        if self.tls_spki_sha256:
            data["tls_spki_sha256"] = self.tls_spki_sha256
        return data

    def to_link(self) -> str:
        body = json.dumps(self.to_json(), separators=(",", ":"), sort_keys=True)
        return LINK_PREFIX + b64u_encode(body.encode("utf-8"))

    @classmethod
    def from_json(cls, data: dict) -> "PairingPayload":
        if not isinstance(data, dict) or data.get("tnp") != 0:
            raise EncodingError("not a TNP v0 pairing payload")
        try:
            payload = cls(
                url=data["url"],
                bridge_id=data["bridge_id"],
                bridge_pk=data["bridge_pk"],
                pair_token=data["pair_token"],
                exp=data["exp"],
                tls_spki_sha256=data.get("tls_spki_sha256"),
            )
        except KeyError as exc:
            raise EncodingError(f"pairing payload is missing {exc}") from None
        if not all(isinstance(v, str) for v in (payload.url, payload.bridge_id,
                                                 payload.bridge_pk, payload.pair_token)):
            raise EncodingError("pairing payload fields must be strings")
        if not isinstance(payload.exp, int) or isinstance(payload.exp, bool):
            raise EncodingError("exp must be an integer")
        return payload

    @classmethod
    def from_link(cls, link: str) -> "PairingPayload":
        link = link.strip()
        if not link.startswith(LINK_PREFIX):
            raise EncodingError(f"pairing links start with {LINK_PREFIX}")
        try:
            data = json.loads(b64u_decode(link[len(LINK_PREFIX):]).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise EncodingError(f"pairing link is corrupt: {exc}") from None
        return cls.from_json(data)


def new_pair_token() -> str:
    """128-bit random token, base64url."""
    return b64u_encode(secrets.token_bytes(16))


def new_short_code() -> str:
    """8 Crockford base32 characters (40 bits), normalized form without the dash."""
    return "".join(secrets.choice(CROCKFORD) for _ in range(SHORT_CODE_LEN))


def format_short_code(code: str) -> str:
    return f"{code[:4]}-{code[4:]}"


def normalize_short_code(text: str) -> str:
    """Accept what people type: any case, dashes or spaces, O for 0 and I/L for 1."""
    cleaned = text.upper().replace("-", "").replace(" ", "")
    cleaned = cleaned.replace("O", "0").replace("I", "1").replace("L", "1")
    if len(cleaned) != SHORT_CODE_LEN or any(c not in CROCKFORD for c in cleaned):
        raise EncodingError("short codes are 8 characters, like ABCD-1234")
    return cleaned
