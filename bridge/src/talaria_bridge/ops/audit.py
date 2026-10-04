"""The talaria-ops audit log: one JSON line per operation, readable only by root."""

from __future__ import annotations

import json
import os
from pathlib import Path


class AuditLog:
    def __init__(self, path: Path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)

    def append(self, entry: dict) -> None:
        fd = os.open(self.path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        with os.fdopen(fd, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False, separators=(",", ":")) + "\n")

    def tail(self, n: int) -> list[dict]:
        try:
            lines = self.path.read_text(encoding="utf-8").splitlines()[-n:]
        except OSError:
            return []
        out = []
        for line in lines:
            try:
                out.append(json.loads(line))
            except ValueError:
                continue
        return out
