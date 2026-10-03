"""To-dos (spec/README.md §13): a short list kept on the bridge, the same on every device."""

from __future__ import annotations

import datetime as dt
import secrets
import sqlite3
import time
from pathlib import Path

from .protocol import messages as m

MAX_TEXT = 500
MAX_OPEN = 500
DONE_SHOWN = 50

SCHEMA = """
CREATE TABLE IF NOT EXISTS todos (
    id TEXT PRIMARY KEY,
    text TEXT NOT NULL,
    done INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    done_at INTEGER,
    due TEXT,
    conversation_id TEXT
);
"""


class TodoError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def _text(value) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > MAX_TEXT:
        raise TodoError(m.INVALID_PARAMS, f"text must be 1 to {MAX_TEXT} characters")
    return value.strip()


def _due(value) -> str | None:
    if value is None:
        return None
    try:
        if not isinstance(value, str) or len(value) != 10:
            raise ValueError
        dt.date.fromisoformat(value)
    except ValueError:
        raise TodoError(m.INVALID_PARAMS, "due must be a date, YYYY-MM-DD, or null") from None
    return value


def _row(r: sqlite3.Row) -> dict:
    todo = {"id": r["id"], "text": r["text"], "done": bool(r["done"]), "created_at": r["created_at"]}
    for key in ("done_at", "due", "conversation_id"):
        if r[key] is not None:
            todo[key] = r[key]
    return todo


class TodoStore:
    def __init__(self, path: Path | str):
        self.db = sqlite3.connect(str(path), isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)

    def close(self) -> None:
        self.db.close()

    def list(self) -> list[dict]:
        open_ = self.db.execute("SELECT * FROM todos WHERE done = 0 ORDER BY created_at, rowid").fetchall()
        done = self.db.execute("SELECT * FROM todos WHERE done = 1 ORDER BY done_at DESC, rowid DESC LIMIT ?",
                               (DONE_SHOWN,)).fetchall()
        return [_row(r) for r in open_ + done]

    def get(self, todo_id) -> dict:
        if not isinstance(todo_id, str) or not 0 < len(todo_id) <= 64:
            raise TodoError(m.INVALID_PARAMS, "id must be a to-do id")
        r = self.db.execute("SELECT * FROM todos WHERE id = ?", (todo_id,)).fetchone()
        if r is None:
            raise TodoError(m.NOT_FOUND, "Unknown to-do")
        return _row(r)

    def add(self, p: dict) -> dict:
        text, due = _text(p.get("text")), _due(p.get("due"))
        (count,) = self.db.execute("SELECT COUNT(*) FROM todos WHERE done = 0").fetchone()
        if count >= MAX_OPEN:
            raise TodoError(m.CONFLICT, f"There are already {MAX_OPEN} open to-dos")
        todo_id = "td-" + secrets.token_hex(8)
        self.db.execute("INSERT INTO todos (id, text, created_at, due) VALUES (?, ?, ?, ?)",
                        (todo_id, text, int(time.time()), due))
        # done ones beyond what's shown are forgotten
        self.db.execute("DELETE FROM todos WHERE done = 1 AND id NOT IN "
                        "(SELECT id FROM todos WHERE done = 1 ORDER BY done_at DESC, rowid DESC LIMIT ?)",
                        (DONE_SHOWN,))
        return self.get(todo_id)

    def update(self, p: dict) -> dict:
        todo = self.get(p.get("id"))
        changes: dict = {}
        if "text" in p:
            changes["text"] = _text(p["text"])
        if "due" in p:
            changes["due"] = _due(p["due"])
        if "done" in p:
            if not isinstance(p["done"], bool):
                raise TodoError(m.INVALID_PARAMS, "done must be true or false")
            if p["done"] != todo["done"]:
                changes["done"] = int(p["done"])
                changes["done_at"] = int(time.time()) if p["done"] else None
        if changes:
            sets = ", ".join(f"{k} = ?" for k in changes)
            self.db.execute(f"UPDATE todos SET {sets} WHERE id = ?", (*changes.values(), todo["id"]))
        return self.get(todo["id"])

    def delete(self, p: dict) -> dict:
        todo = self.get(p.get("id"))
        self.db.execute("DELETE FROM todos WHERE id = ?", (todo["id"],))
        return {"id": todo["id"], "deleted": True}

    def link(self, todo_id: str, conversation_id: str) -> None:
        """The to-do was handed to the agent in this conversation (chat.send `todo_id`)."""
        self.db.execute("UPDATE todos SET conversation_id = ? WHERE id = ?", (conversation_id, todo_id))

    def handle(self, method: str, p: dict) -> tuple[dict, bool]:
        """Returns (result, whether the list changed)."""
        if method == "todos.list":
            return {"todos": self.list()}, False
        if method == "todos.add":
            return {"todo": self.add(p)}, True
        if method == "todos.update":
            return {"todo": self.update(p)}, True
        if method == "todos.delete":
            return self.delete(p), True
        raise TodoError(m.METHOD_NOT_FOUND, f"Method not found: {method}")


TODO_METHODS = frozenset({"todos.list", "todos.add", "todos.update", "todos.delete"})
