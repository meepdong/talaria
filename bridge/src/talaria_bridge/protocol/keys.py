"""ECDSA P-256 identities: key ids, signing and verification (PROTOCOL §3.1)."""

from __future__ import annotations

import base64
import hashlib
import os
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from .encoding import EncodingError, b64u_decode, b64u_encode

PrivateKey = ec.EllipticCurvePrivateKey
PublicKey = ec.EllipticCurvePublicKey


def generate_key() -> PrivateKey:
    return ec.generate_private_key(ec.SECP256R1())


def private_key_from_scalar(scalar: int) -> PrivateKey:
    """Only for test vectors: real keys come from generate_key or an OS keystore."""
    return ec.derive_private_key(scalar, ec.SECP256R1())


def spki_der(key: PrivateKey | PublicKey) -> bytes:
    public = key.public_key() if isinstance(key, ec.EllipticCurvePrivateKey) else key
    return public.public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )


def public_key_b64u(key: PrivateKey | PublicKey) -> str:
    """The wire form of a public key: base64url of its DER SubjectPublicKeyInfo."""
    return b64u_encode(spki_der(key))


def load_public_key(text: str) -> PublicKey:
    """Parse a wire public key, accepting only P-256."""
    der = b64u_decode(text)
    try:
        key = serialization.load_der_public_key(der)
    except ValueError as exc:
        raise EncodingError(f"invalid public key: {exc}") from None
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(
        key.curve, ec.SECP256R1
    ):
        raise EncodingError("public key must be ECDSA P-256")
    if spki_der(key) != der:
        raise EncodingError("non-canonical public key encoding")
    return key


def key_id(key: PrivateKey | PublicKey | str) -> str:
    """bridge_id / device_id: base32(SHA-256(SPKI DER)), uppercase, unpadded, first 26 chars."""
    der = b64u_decode(key) if isinstance(key, str) else spki_der(key)
    digest = hashlib.sha256(der).digest()
    return base64.b32encode(digest).decode("ascii").rstrip("=")[:26]


def sign(key: PrivateKey, data: bytes) -> str:
    """ECDSA P-256 / SHA-256, DER signature, base64url."""
    return b64u_encode(key.sign(data, ec.ECDSA(hashes.SHA256())))


def verify(key: PublicKey, data: bytes, signature: str) -> bool:
    try:
        key.verify(b64u_decode(signature), data, ec.ECDSA(hashes.SHA256()))
    except (InvalidSignature, EncodingError, ValueError):
        return False
    return True


def save_private_key(key: PrivateKey, path: Path) -> None:
    pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as fh:
        fh.write(pem)


def load_private_key(path: Path) -> PrivateKey:
    key = serialization.load_pem_private_key(path.read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(
        key.curve, ec.SECP256R1
    ):
        raise ValueError(f"{path} is not an ECDSA P-256 key")
    return key


def load_or_create_private_key(path: Path) -> PrivateKey:
    if path.exists():
        return load_private_key(path)
    key = generate_key()
    save_private_key(key, path)
    return key
