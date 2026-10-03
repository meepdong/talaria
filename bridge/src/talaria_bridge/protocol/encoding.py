"""Byte-level encodings shared by every TNP implementation (spec/README.md §1)."""

from __future__ import annotations

import base64
import binascii
import time


class EncodingError(ValueError):
    """A value on the wire is not in its canonical encoding."""


def b64u_encode(data: bytes) -> str:
    """base64url without padding."""
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def b64u_decode(text: str) -> bytes:
    """Strict base64url decode: no padding, no stray characters, canonical form only."""
    if not isinstance(text, str) or "=" in text:
        raise EncodingError("expected unpadded base64url text")
    try:
        data = base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))
    except (binascii.Error, ValueError) as exc:
        raise EncodingError(f"invalid base64url: {exc}") from None
    if b64u_encode(data) != text:
        raise EncodingError("non-canonical base64url")
    return data


def frame(*fields: str | bytes) -> bytes:
    """Unambiguous concatenation, written ‖ in PROTOCOL.md.

    Each field is a 4-byte big-endian length followed by its bytes. Text fields are
    their exact wire string encoded as UTF-8.
    """
    out = bytearray()
    for field in fields:
        data = field.encode("utf-8") if isinstance(field, str) else bytes(field)
        out += len(data).to_bytes(4, "big")
        out += data
    return bytes(out)


def now() -> int:
    return int(time.time())
