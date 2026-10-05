"""Uploads for chat attachments (spec/README.md §10).

A device uploads a photo or file in chunks over its session with blob.begin, blob.put and
blob.commit, then attaches the committed blob to chat.send. Partial and committed blobs live
in a temporary folder and are dropped when sent, or after an hour.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import re
import secrets
import shutil
import time
from dataclasses import dataclass
from pathlib import Path

from .protocol import messages as m

MAX_BLOB = 2 * 1024 * 1024 * 1024  # 2 GiB per file (owner, 2026-10-05)
CHUNK_BYTES = 512 * 1024
MAX_PENDING_BYTES = 8 * 1024 * 1024 * 1024  # all uploads not yet sent
MIN_FREE_BYTES = 10 * 1024 * 1024 * 1024  # an upload never leaves the server with less free than this
BLOB_TTL_S = 3600
IMAGE_MIMES = frozenset({"image/jpeg", "image/png", "image/webp", "image/gif"})
MAX_INLINE_IMAGE = 5 * 1024 * 1024
_MIME = re.compile(r"^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")


class BlobError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def safe_name(name: str) -> str:
    """A file name that can't leave its folder or upset a shell: no separators, controls or dot files."""
    base = re.split(r"[\\/]", name)[-1]
    base = re.sub(r"[^\w.\- ()+,@]", "_", base).strip(" .")
    return base[:120] or "file"


@dataclass
class Blob:
    blob_id: str
    name: str
    mime: str
    size: int
    sha256: str
    path: Path
    created: float
    received: int = 0
    committed: bool = False
    claimed: bool = False  # taken by a chat.send that is still being prepared

    @property
    def kind(self) -> str:
        return "image" if self.mime in IMAGE_MIMES and self.size <= MAX_INLINE_IMAGE else "file"

    def meta(self) -> dict:
        return {"kind": self.kind, "name": self.name, "mime": self.mime, "size": self.size}


class BlobStore:
    def __init__(self, folder: Path, *, clock=time.monotonic):
        self.folder = folder
        self.clock = clock
        self._blobs: dict[str, Blob] = {}
        # leftovers from a previous run were never sent, and nothing refers to them now
        shutil.rmtree(folder, ignore_errors=True)
        folder.mkdir(parents=True, mode=0o700, exist_ok=True)

    def _expire(self) -> None:
        now = self.clock()
        for blob in [b for b in self._blobs.values() if now - b.created > BLOB_TTL_S]:
            self.discard(blob.blob_id)

    def _get(self, p: dict) -> Blob:
        blob_id = p.get("blob_id")
        blob = self._blobs.get(blob_id) if isinstance(blob_id, str) else None
        if blob is None:
            raise BlobError(m.NOT_FOUND, "Unknown or expired upload")
        return blob

    def begin(self, p: dict) -> dict:
        self._expire()
        name, mime, size, digest = p.get("name"), p.get("mime"), p.get("size"), p.get("sha256")
        if not (isinstance(name, str) and 0 < len(name) <= 255):
            raise BlobError(m.INVALID_PARAMS, "name must be 1 to 255 characters")
        if not (isinstance(mime, str) and len(mime) <= 255 and _MIME.match(mime.lower())):
            raise BlobError(m.INVALID_PARAMS, "mime must be a media type such as image/jpeg")
        if not (isinstance(size, int) and not isinstance(size, bool) and 0 < size <= MAX_BLOB):
            raise BlobError(m.INVALID_PARAMS, f"size must be 1 to {MAX_BLOB} bytes")
        if not (isinstance(digest, str) and _SHA256.match(digest)):
            raise BlobError(m.INVALID_PARAMS, "sha256 must be 64 lowercase hex characters")
        waiting = sum(b.size - b.received for b in self._blobs.values())
        if sum(b.size for b in self._blobs.values()) + size > MAX_PENDING_BYTES:
            raise BlobError(m.CONFLICT, "Too many uploads waiting to be sent; try again later")
        if self.free_bytes() - waiting - size < MIN_FREE_BYTES:
            raise BlobError(m.CONFLICT, f"Not enough space on the server for this file: it keeps at least "
                                        f"{MIN_FREE_BYTES // 2**30} GB free")
        blob_id = "b-" + secrets.token_hex(12)
        path = self.folder / blob_id
        path.touch(mode=0o600)
        self._blobs[blob_id] = Blob(blob_id, name, mime.lower(), size, digest, path, self.clock())
        return {"blob_id": blob_id, "chunk_bytes": CHUNK_BYTES}

    def put(self, p: dict) -> dict:
        blob = self._get(p)
        if blob.committed:
            raise BlobError(m.CONFLICT, "Upload already committed")
        offset, data = p.get("offset"), p.get("data")
        if offset != blob.received or isinstance(offset, bool):
            raise BlobError(m.CONFLICT, f"Expected offset {blob.received}")
        try:
            chunk = base64.b64decode(data, validate=True) if isinstance(data, str) else b""
        except (binascii.Error, ValueError):
            chunk = b""
        if not chunk:
            raise BlobError(m.INVALID_PARAMS, "data must be non-empty base64")
        if len(chunk) > CHUNK_BYTES or blob.received + len(chunk) > blob.size:
            raise BlobError(m.INVALID_PARAMS, "Chunk too large")
        with blob.path.open("ab") as f:
            f.write(chunk)
        blob.received += len(chunk)
        return {"blob_id": blob.blob_id, "received": blob.received}

    def commit(self, p: dict) -> dict:
        blob = self._get(p)
        if not blob.committed:
            digest = hashlib.sha256()
            with blob.path.open("rb") as f:
                for piece in iter(lambda: f.read(1 << 20), b""):
                    digest.update(piece)
            if blob.received != blob.size or digest.hexdigest() != blob.sha256:
                self.discard(blob.blob_id)
                raise BlobError(m.INVALID_PARAMS, "Size or sha256 does not match; upload again")
            blob.committed = True
        return {"blob_id": blob.blob_id, **blob.meta()}

    def take(self, blob_id) -> Blob:
        """Claim a committed blob for one chat.send. The caller then calls `discard` once it is
        sent, or `release` if the send fails, so it can be sent again."""
        self._expire()
        blob = self._blobs.get(blob_id) if isinstance(blob_id, str) else None
        if blob is None or not blob.committed or blob.claimed:
            raise BlobError(m.NOT_FOUND, "Unknown, unfinished or already sent attachment")
        blob.claimed = True
        return blob

    def release(self, blob: Blob) -> None:
        blob.claimed = False

    def free_bytes(self) -> int:
        return shutil.disk_usage(self.folder).free

    def discard(self, blob_id: str) -> None:
        blob = self._blobs.pop(blob_id, None)
        if blob is not None:
            blob.path.unlink(missing_ok=True)

    def handle(self, method: str, p: dict) -> dict:
        if method == "blob.begin":
            return self.begin(p)
        if method == "blob.put":
            return self.put(p)
        if method == "blob.commit":
            return self.commit(p)
        raise BlobError(m.METHOD_NOT_FOUND, f"Method not found: {method}")


BLOB_METHODS = frozenset({"blob.begin", "blob.put", "blob.commit"})
