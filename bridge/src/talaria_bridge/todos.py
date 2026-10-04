"""To-dos (spec/README.md §13): a list kept on the bridge, the same on every device, with comments and groups."""

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
MAX_GROUP = 40
MAX_COMMENT = 2000
MAX_COMMENTS = 50

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
CREATE TABLE IF NOT EXISTS todo_comments (
    id TEXT PRIMARY KEY,
    todo_id TEXT NOT NULL,
    text TEXT NOT NULL,
    by TEXT NOT NULL,
    at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS todo_comments_todo ON todo_comments (todo_id);
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


def group_name(value) -> str | None:
    """A group, 1 to 40 characters, or None to clear it."""
    if value is None:
        return None
    if not isinstance(value, str) or not value.strip() or len(value.strip()) > MAX_GROUP:
        raise TodoError(m.INVALID_PARAMS, f"group must be 1 to {MAX_GROUP} characters, or null")
    return " ".join(value.split())


def _row(r: sqlite3.Row, comments: list[dict]) -> dict:
    todo = {"id": r["id"], "text": r["text"], "done": bool(r["done"]), "created_at": r["created_at"]}
    for key in ("done_at", "due", "conversation_id"):
        if r[key] is not None:
            todo[key] = r[key]
    if r["grp"] is not None:
        todo["group"] = r["grp"]
    if comments:
        todo["comments"] = comments
    return todo


class TodoStore:
    def __init__(self, path: Path | str):
        self.db = sqlite3.connect(str(path), isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        columns = {r[1] for r in self.db.execute("PRAGMA table_info(todos)")}
        if "grp" not in columns:  # added with groups
            self.db.execute("ALTER TABLE todos ADD COLUMN grp TEXT")

    def close(self) -> None:
        self.db.close()

    def list(self) -> list[dict]:
        open_ = self.db.execute("SELECT * FROM todos WHERE done = 0 ORDER BY created_at, rowid").fetchall()
        done = self.db.execute("SELECT * FROM todos WHERE done = 1 ORDER BY done_at DESC, rowid DESC LIMIT ?",
                               (DONE_SHOWN,)).fetchall()
        comments: dict[str, list[dict]] = {}
        for c in self.db.execute("SELECT * FROM todo_comments ORDER BY at, rowid"):
            comments.setdefault(c["todo_id"], []).append(_comment(c))
        return [_row(r, comments.get(r["id"], [])) for r in open_ + done]

    def get(self, todo_id) -> dict:
        if not isinstance(todo_id, str) or not 0 < len(todo_id) <= 64:
            raise TodoError(m.INVALID_PARAMS, "id must be a to-do id")
        r = self.db.execute("SELECT * FROM todos WHERE id = ?", (todo_id,)).fetchone()
        if r is None:
            raise TodoError(m.NOT_FOUND, "Unknown to-do")
        comments = self.db.execute("SELECT * FROM todo_comments WHERE todo_id = ? ORDER BY at, rowid", (todo_id,))
        return _row(r, [_comment(c) for c in comments])

    def add(self, p: dict) -> dict:
        text, due, group = _text(p.get("text")), _due(p.get("due")), group_name(p.get("group"))
        (count,) = self.db.execute("SELECT COUNT(*) FROM todos WHERE done = 0").fetchone()
        if count >= MAX_OPEN:
            raise TodoError(m.CONFLICT, f"There are already {MAX_OPEN} open to-dos")
        todo_id = "td-" + secrets.token_hex(8)
        self.db.execute("INSERT INTO todos (id, text, created_at, due, grp) VALUES (?, ?, ?, ?, ?)",
                        (todo_id, text, int(time.time()), due, group))
        # done ones beyond what's shown are forgotten, with their comments
        self.db.execute("DELETE FROM todos WHERE done = 1 AND id NOT IN "
                        "(SELECT id FROM todos WHERE done = 1 ORDER BY done_at DESC, rowid DESC LIMIT ?)",
                        (DONE_SHOWN,))
        self.db.execute("DELETE FROM todo_comments WHERE todo_id NOT IN (SELECT id FROM todos)")
        return self.get(todo_id)

    def update(self, p: dict) -> dict:
        todo = self.get(p.get("id"))
        changes: dict = {}
        if "text" in p:
            changes["text"] = _text(p["text"])
        if "due" in p:
            changes["due"] = _due(p["due"])
        if "group" in p:
            changes["grp"] = group_name(p["group"])
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
        self.db.execute("DELETE FROM todo_comments WHERE todo_id = ?", (todo["id"],))
        return {"id": todo["id"], "deleted": True}

    def comment(self, p: dict, by: str = "you") -> dict:
        todo = self.get(p.get("id"))
        text = p.get("text")
        if not isinstance(text, str) or not text.strip() or len(text) > MAX_COMMENT:
            raise TodoError(m.INVALID_PARAMS, f"text must be 1 to {MAX_COMMENT} characters")
        if len(todo.get("comments", [])) >= MAX_COMMENTS:
            raise TodoError(m.CONFLICT, f"A to-do holds at most {MAX_COMMENTS} comments")
        self.db.execute("INSERT INTO todo_comments (id, todo_id, text, by, at) VALUES (?, ?, ?, ?, ?)",
                        ("tc-" + secrets.token_hex(8), todo["id"], text.strip(), by, int(time.time())))
        return self.get(todo["id"])

    def uncomment(self, p: dict) -> dict:
        todo = self.get(p.get("id"))
        comment_id = p.get("comment_id")
        if not any(c["id"] == comment_id for c in todo.get("comments", [])):
            raise TodoError(m.NOT_FOUND, "Unknown comment")
        self.db.execute("DELETE FROM todo_comments WHERE id = ?", (comment_id,))
        return self.get(todo["id"])

    # groups, set by the agent (todo_groups.py)

    def open_todos(self) -> list[dict]:
        return [t for t in self.list() if not t["done"]]

    def set_groups(self, groups: dict[str, str], *, only_ungrouped: bool) -> int:
        """Put to-dos in groups; with [only_ungrouped], one that has a group keeps it. Returns how many changed."""
        changed = 0
        for todo_id, group in groups.items():
            sql = "UPDATE todos SET grp = ? WHERE id = ? AND done = 0" + (" AND grp IS NULL" if only_ungrouped else "")
            changed += self.db.execute(sql, (group, todo_id)).rowcount
        return changed

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
        if method == "todos.comment":
            return {"todo": self.comment(p)}, True
        if method == "todos.uncomment":
            return {"todo": self.uncomment(p)}, True
        raise TodoError(m.METHOD_NOT_FOUND, f"Method not found: {method}")


def _comment(c: sqlite3.Row) -> dict:
    return {"id": c["id"], "text": c["text"], "by": c["by"], "at": c["at"]}


TODO_METHODS = frozenset({"todos.list", "todos.add", "todos.update", "todos.delete", "todos.comment", "todos.uncomment",
                          "todos.regroup"})
