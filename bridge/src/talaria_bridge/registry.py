"""SQLite store for devices, pairing tokens and pending pair requests.

`talaria serve` and `talaria pair` run as separate processes and meet here: the server
records a pair request, the pair command shows the SAS prompt and writes the decision.
"""

from __future__ import annotations

import os
import sqlite3
from dataclasses import dataclass
from pathlib import Path

MAX_CODE_FAILURES = 5

SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY,
    public_key TEXT NOT NULL,
    name TEXT NOT NULL,
    platform TEXT NOT NULL,
    paired_at INTEGER NOT NULL,
    last_seen INTEGER,
    revoked_at INTEGER,
    capabilities TEXT
);
CREATE TABLE IF NOT EXISTS pairings (
    token TEXT PRIMARY KEY,
    short_code TEXT NOT NULL UNIQUE,
    name TEXT,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    used_at INTEGER,
    code_failures INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS pair_requests (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    token TEXT NOT NULL REFERENCES pairings(token),
    device_id TEXT NOT NULL,
    device_pk TEXT NOT NULL,
    device_name TEXT NOT NULL,
    platform TEXT NOT NULL,
    sas_digits TEXT NOT NULL,
    sas_emoji TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    state TEXT NOT NULL DEFAULT 'waiting',
    decided_at INTEGER
);
"""


@dataclass(frozen=True)
class Device:
    device_id: str
    public_key: str
    name: str
    platform: str
    paired_at: int
    last_seen: int | None
    revoked_at: int | None

    @property
    def revoked(self) -> bool:
        return self.revoked_at is not None


@dataclass(frozen=True)
class Pairing:
    token: str
    short_code: str
    name: str | None
    expires_at: int


@dataclass(frozen=True)
class PairRequest:
    id: int
    token: str
    device_id: str
    device_pk: str
    device_name: str
    platform: str
    sas_digits: str
    sas_emoji: str
    state: str


class ClaimError(Exception):
    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


class Registry:
    def __init__(self, path: Path | str):
        self.path = Path(path)
        new = not self.path.exists()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(self.path, isolation_level=None, timeout=10)
        self.db.row_factory = sqlite3.Row
        if new and os.name == "posix":
            os.chmod(self.path, 0o600)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript(SCHEMA)

    def close(self) -> None:
        self.db.close()

    # meta

    def get_meta(self, key: str) -> str | None:
        row = self.db.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
        return row["value"] if row else None

    def set_meta(self, key: str, value: str) -> None:
        self.db.execute(
            "INSERT INTO meta (key, value) VALUES (?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (key, value),
        )

    # pairing tokens

    def create_pairing(self, token: str, short_code: str, name: str | None,
                       now: int, expires_at: int) -> Pairing:
        self.db.execute(
            "INSERT INTO pairings (token, short_code, name, created_at, expires_at) "
            "VALUES (?, ?, ?, ?, ?)",
            (token, short_code, name, now, expires_at),
        )
        return Pairing(token, short_code, name, expires_at)

    def claim_pairing(self, *, now: int, token: str | None = None,
                      short_code: str | None = None) -> Pairing:
        """Consume a pairing token or short code. Single use: the first valid claim
        burns it, whatever the operator later decides."""
        self.db.execute("BEGIN IMMEDIATE")
        try:
            if token is not None:
                row = self.db.execute("SELECT * FROM pairings WHERE token = ?", (token,)).fetchone()
            else:
                row = self.db.execute(
                    "SELECT * FROM pairings WHERE short_code = ?", (short_code,)
                ).fetchone()
                if row is None or (row["used_at"] is None and row["expires_at"] > now
                                   and row["code_failures"] >= MAX_CODE_FAILURES):
                    reason = "rate_limited" if row is not None else "unknown_token"
                    self._record_code_failure(now)
                    self.db.execute("COMMIT")
                    raise ClaimError(reason)
            if row is None:
                raise ClaimError("unknown_token")
            if row["used_at"] is not None:
                raise ClaimError("already_used")
            if row["expires_at"] <= now:
                raise ClaimError("expired")
            self.db.execute("UPDATE pairings SET used_at = ? WHERE token = ?", (now, row["token"]))
            self.db.execute("COMMIT")
        except BaseException:
            if self.db.in_transaction:
                self.db.execute("ROLLBACK")
            raise
        return Pairing(row["token"], row["short_code"], row["name"], row["expires_at"])

    def _record_code_failure(self, now: int) -> None:
        """A wrong short code counts against every code still open, so guessing is
        limited to MAX_CODE_FAILURES tries in total (PROTOCOL §3.2)."""
        self.db.execute(
            "UPDATE pairings SET code_failures = code_failures + 1 "
            "WHERE used_at IS NULL AND expires_at > ?",
            (now,),
        )

    def get_pairing(self, token: str) -> Pairing | None:
        row = self.db.execute("SELECT * FROM pairings WHERE token = ?", (token,)).fetchone()
        return Pairing(row["token"], row["short_code"], row["name"], row["expires_at"]) if row else None

    # pair requests

    def add_pair_request(self, *, token: str, device_id: str, device_pk: str, device_name: str,
                         platform: str, sas_digits: str, sas_emoji: str, now: int) -> int:
        cur = self.db.execute(
            "INSERT INTO pair_requests (token, device_id, device_pk, device_name, platform, "
            "sas_digits, sas_emoji, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (token, device_id, device_pk, device_name, platform, sas_digits, sas_emoji, now),
        )
        return int(cur.lastrowid)

    def get_pair_request(self, request_id: int) -> PairRequest | None:
        row = self.db.execute("SELECT * FROM pair_requests WHERE id = ?", (request_id,)).fetchone()
        return self._pair_request(row) if row else None

    def waiting_request_for(self, token: str) -> PairRequest | None:
        row = self.db.execute(
            "SELECT * FROM pair_requests WHERE token = ? AND state = 'waiting' ORDER BY id LIMIT 1",
            (token,),
        ).fetchone()
        return self._pair_request(row) if row else None

    def decide_pair_request(self, request_id: int, state: str, now: int) -> bool:
        """Move a waiting request to approved, rejected or expired. Returns False if
        it was already decided (for example the server timed it out first)."""
        assert state in ("approved", "rejected", "expired")
        cur = self.db.execute(
            "UPDATE pair_requests SET state = ?, decided_at = ? WHERE id = ? AND state = 'waiting'",
            (state, now, request_id),
        )
        return cur.rowcount == 1

    @staticmethod
    def _pair_request(row: sqlite3.Row) -> PairRequest:
        return PairRequest(row["id"], row["token"], row["device_id"], row["device_pk"],
                           row["device_name"], row["platform"], row["sas_digits"],
                           row["sas_emoji"], row["state"])

    # devices

    def add_device(self, *, device_id: str, public_key: str, name: str, platform: str, now: int) -> None:
        self.db.execute(
            "INSERT INTO devices (device_id, public_key, name, platform, paired_at) "
            "VALUES (?, ?, ?, ?, ?) ON CONFLICT(device_id) DO UPDATE SET "
            "name = excluded.name, platform = excluded.platform, paired_at = excluded.paired_at",
            (device_id, public_key, name, platform, now),
        )

    def get_device(self, device_id: str) -> Device | None:
        row = self.db.execute("SELECT * FROM devices WHERE device_id = ?", (device_id,)).fetchone()
        return self._device(row) if row else None

    def list_devices(self) -> list[Device]:
        rows = self.db.execute("SELECT * FROM devices ORDER BY paired_at").fetchall()
        return [self._device(r) for r in rows]

    def find_device(self, id_or_prefix: str) -> Device:
        """Exact id, or a unique prefix of one, as typed in `talaria devices revoke`."""
        if not id_or_prefix.isalnum():
            raise LookupError(f"{id_or_prefix!r} is not a device id")
        rows = self.db.execute(
            "SELECT * FROM devices WHERE device_id LIKE ? || '%'", (id_or_prefix.upper(),)
        ).fetchall()
        if len(rows) != 1:
            raise LookupError(
                f"no device matches {id_or_prefix!r}" if not rows
                else f"{id_or_prefix!r} matches {len(rows)} devices; type more of the id"
            )
        return self._device(rows[0])

    def revoke_device(self, device_id: str, now: int) -> bool:
        cur = self.db.execute(
            "UPDATE devices SET revoked_at = ? WHERE device_id = ? AND revoked_at IS NULL",
            (now, device_id),
        )
        return cur.rowcount == 1

    def is_revoked(self, device_id: str) -> bool:
        row = self.db.execute(
            "SELECT revoked_at FROM devices WHERE device_id = ?", (device_id,)
        ).fetchone()
        return row is not None and row["revoked_at"] is not None

    def touch_device(self, device_id: str, now: int) -> None:
        self.db.execute("UPDATE devices SET last_seen = ? WHERE device_id = ?", (now, device_id))

    def set_capabilities(self, device_id: str, capabilities_json: str) -> None:
        self.db.execute(
            "UPDATE devices SET capabilities = ? WHERE device_id = ?", (capabilities_json, device_id)
        )

    @staticmethod
    def _device(row: sqlite3.Row) -> Device:
        return Device(row["device_id"], row["public_key"], row["name"], row["platform"],
                      row["paired_at"], row["last_seen"], row["revoked_at"])
