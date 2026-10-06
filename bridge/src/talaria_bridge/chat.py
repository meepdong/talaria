"""Chat proxy (PROTOCOL §10.3, spec/README.md §9 and §11).

One Talaria conversation is one Hermes session. The bridge holds the Hermes stream for each
turn, keeps a snapshot of it, and sends chat.started / chat.delta / chat.done to every device,
so a reply survives the phone dropping off and every device shows the same conversation.
"""

from __future__ import annotations

import asyncio
import base64
import contextlib
import logging
import os
import re
import secrets
import shutil
import sqlite3
import time
from collections import OrderedDict
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from pathlib import Path

from .accounts import OpenRouterAccount
from .talk import TALK_METHODS, TalkError
from .voice import VOICE_METHODS, VoiceError, VoiceService
from .automations import AUTOMATION_METHODS, AutomationError, Automations
from .files import FILES_METHODS, FilesError, FilesService, Found
from .todo_groups import GroupingError, group_todos
from .todos import TODO_METHODS, TodoError, TodoStore
from .blobs import BLOB_METHODS, Blob, BlobError, BlobStore, safe_name
from .hermes import HermesClient, HermesError, HermesUnavailable
from .protocol import messages as m

log = logging.getLogger("talaria.chat")

MAX_TEXT = 32000
MAX_TITLE = 100
MAX_PREVIEW = 500
LAST_MESSAGE_LEN = 200
KEPT_TURNS = 50
CLIENT_MSG_TTL_S = 600
HISTORY_DEFAULT, HISTORY_MAX = 50, 100
MAX_ATTACHMENTS = 128
MAX_FILE_CAPTION = 1000
TIDY_TOKENS = 48_000  # a chat this big may be compressed before the agent answers (Hermes compresses at 64k)
TIDY_AFTER_S = 8  # ... which is likely when a turn in it has shown nothing for this long
TIDYING = "Tidying up this long chat…"
RECENT_CHAT_S = 30 * 60  # send_file with no reply running: the agent's latest conversation, if this recent
MAX_INLINE_COUNT = 10  # more photos than this go to the agent's inbox as files (§10)
MAX_QUEUED = 5
GROUP_DELAY_S = 3.0  # how long new to-dos wait for more before they are grouped (§13)
MAX_NOTE = 4000
MAX_HIDE = 50
ASIDE_TRANSCRIPT_CHARS = 20000
MAX_INLINE_IMAGES = 7 * 1024 * 1024  # Hermes refuses requests over 10 MB, and base64 adds a third
# the line the bridge adds to a message for each file it saved to the agent's inbox (§10)
FILE_LINE = re.compile(r"^Attached file: (?P<path>.+) \((?P<mime>[^,()]+), (?P<size>\d+) bytes\)$")

Broadcast = Callable[[dict], Awaitable[None]]
Job = Callable[[], Awaitable[None]]

ASIDE_PROMPT = """A quick side question about the conversation below. Answer it briefly from the \
conversation; don't take any actions or change anything.

<conversation>
{transcript}
</conversation>

Side question: {question}"""


class RpcError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def _id(value, name: str) -> str:
    if not (isinstance(value, str) and 0 < len(value) <= 64):
        raise RpcError(m.INVALID_PARAMS, f"{name} must be a string of 1 to 64 characters")
    return value


def _model(value) -> tuple[str, str]:
    if not (isinstance(value, dict) and all(isinstance(value.get(k), str) and 0 < len(value[k]) <= 200
                                            for k in ("provider", "model"))):
        raise RpcError(m.INVALID_PARAMS, "model must be {provider, model}")
    return value["provider"], value["model"]


def _note(value, name: str) -> str:
    if not (isinstance(value, str) and value.strip() and len(value) <= MAX_NOTE):
        raise RpcError(m.INVALID_PARAMS, f"{name} must be 1 to {MAX_NOTE} characters")
    return value.strip()


def _title_from(text: str) -> str:
    line = " ".join(text.split())
    return line if len(line) <= 60 else line[:59].rstrip() + "…"


# conversations, stored next to bridge.db

SCHEMA = """
CREATE TABLE IF NOT EXISTS conversations (
    id TEXT PRIMARY KEY,
    agent_id TEXT NOT NULL,
    hermes_session_id TEXT NOT NULL,
    title TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    last_role TEXT,
    last_text TEXT,
    model_provider TEXT,
    model_name TEXT,
    pinned INTEGER,
    archived INTEGER
);
CREATE TABLE IF NOT EXISTS hidden_messages (
    conversation_id TEXT NOT NULL,
    message_id TEXT NOT NULL,
    PRIMARY KEY (conversation_id, message_id)
);
CREATE TABLE IF NOT EXISTS agent_files (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id TEXT NOT NULL,
    at INTEGER NOT NULL,
    root TEXT NOT NULL,
    path TEXT NOT NULL,
    name TEXT NOT NULL,
    mime TEXT NOT NULL,
    size INTEGER NOT NULL,
    caption TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS talk_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id TEXT NOT NULL,
    at INTEGER NOT NULL,
    role TEXT NOT NULL,
    text TEXT NOT NULL
);
"""
COLUMNS = ("id", "agent_id", "hermes_session_id", "title", "created_at", "updated_at", "last_role", "last_text",
           "model_provider", "model_name", "pinned", "archived")


@dataclass
class Conversation:
    id: str
    agent_id: str
    hermes_session_id: str
    title: str
    created_at: int
    updated_at: int
    last_role: str | None = None
    last_text: str | None = None
    model_provider: str | None = None  # the model the conversation is pinned to (§11)
    model_name: str | None = None
    pinned: int | None = None  # pinned to the top of the list (§9)
    archived: int | None = None  # off the list until opened from Archived, or written in again (§9)

    @property
    def model(self) -> dict | None:
        if self.model_provider and self.model_name:
            return {"provider": self.model_provider, "model": self.model_name}
        return None


class ChatStore:
    def __init__(self, path: Path | str):
        self.db = sqlite3.connect(str(path), isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        have = {row["name"] for row in self.db.execute("PRAGMA table_info(conversations)")}
        for column in ("model_provider", "model_name"):  # added in M2 §11
            if column not in have:
                self.db.execute(f"ALTER TABLE conversations ADD COLUMN {column} TEXT")
        if "pinned" not in have:
            self.db.execute("ALTER TABLE conversations ADD COLUMN pinned INTEGER")
        if "archived" not in have:
            self.db.execute("ALTER TABLE conversations ADD COLUMN archived INTEGER")
        self.db.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        self.db.execute("CREATE TABLE IF NOT EXISTS default_models "
                        "(agent_id TEXT PRIMARY KEY, provider TEXT NOT NULL, model TEXT NOT NULL)")

    def close(self) -> None:
        self.db.close()

    def add(self, c: Conversation) -> None:
        self.db.execute(
            f"INSERT INTO conversations ({', '.join(COLUMNS)}) VALUES ({', '.join('?' * len(COLUMNS))})",
            tuple(getattr(c, k) for k in COLUMNS))

    def get(self, conversation_id: str) -> Conversation | None:
        row = self.db.execute("SELECT * FROM conversations WHERE id = ?", (conversation_id,)).fetchone()
        return Conversation(**dict(row)) if row else None

    def all(self) -> list[Conversation]:
        rows = self.db.execute("SELECT * FROM conversations ORDER BY updated_at DESC, created_at DESC")
        return [Conversation(**dict(r)) for r in rows]

    def touch(self, conversation_id: str, role: str, text: str, at: int) -> None:
        self.db.execute(
            "UPDATE conversations SET updated_at = ?, last_role = ?, last_text = ? WHERE id = ?",
            (at, role, text[:LAST_MESSAGE_LEN], conversation_id))

    def add_file(self, conversation_id: str, at: int, root: str, path: str, name: str, mime: str, size: int,
                 caption: str) -> int:
        cur = self.db.execute("INSERT INTO agent_files (conversation_id, at, root, path, name, mime, size, caption)"
                              " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", (conversation_id, at, root, path, name, mime, size, caption))
        return int(cur.lastrowid)

    def agent_files(self, conversation_id: str) -> list[dict]:
        """The files the agent sent into a conversation (send_file, §15), as assistant messages, oldest first."""
        rows = self.db.execute("SELECT * FROM agent_files WHERE conversation_id = ? ORDER BY at, id", (conversation_id,))
        return [file_message(r) for r in rows]

    def add_talk(self, conversation_id: str, at: int, role: str, text: str) -> dict:
        """A spoken exchange from Talk (§9 chat.talk), kept with the conversation; returns it as a message."""
        cur = self.db.execute("INSERT INTO talk_messages (conversation_id, at, role, text) VALUES (?, ?, ?, ?)",
                              (conversation_id, at, role, text))
        return {"id": f"t-{cur.lastrowid}", "role": role, "text": text, "ts": at}

    def talk_messages(self, conversation_id: str) -> list[dict]:
        rows = self.db.execute("SELECT * FROM talk_messages WHERE conversation_id = ? ORDER BY at, id", (conversation_id,))
        return [{"id": f"t-{r['id']}", "role": r["role"], "text": r["text"], "ts": r["at"]} for r in rows]

    def setting(self, key: str) -> str | None:
        row = self.db.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
        return row["value"] if row else None

    def set_setting(self, key: str, value: str) -> None:
        self.db.execute("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", (key, value))

    def set_model(self, conversation_id: str, provider: str, model: str) -> None:
        self.db.execute("UPDATE conversations SET model_provider = ?, model_name = ? WHERE id = ?",
                        (provider, model, conversation_id))

    def default_model(self, agent_id: str) -> tuple[str, str] | None:
        """Talaria's default model for the agent's new chats (§11), if one is set."""
        row = self.db.execute("SELECT provider, model FROM default_models WHERE agent_id = ?", (agent_id,)).fetchone()
        return (row["provider"], row["model"]) if row else None

    def set_default_model(self, agent_id: str, model: tuple[str, str] | None) -> None:
        if model is None:
            self.db.execute("DELETE FROM default_models WHERE agent_id = ?", (agent_id,))
        else:
            self.db.execute("INSERT OR REPLACE INTO default_models (agent_id, provider, model) VALUES (?, ?, ?)",
                            (agent_id, *model))

    def rename(self, conversation_id: str, title: str) -> None:
        self.db.execute("UPDATE conversations SET title = ? WHERE id = ?", (title, conversation_id))

    def pin(self, conversation_id: str, pinned: bool) -> None:
        self.db.execute("UPDATE conversations SET pinned = ? WHERE id = ?", (1 if pinned else None, conversation_id))

    def archive(self, conversation_id: str, archived: bool) -> None:
        self.db.execute("UPDATE conversations SET archived = ? WHERE id = ?", (1 if archived else None, conversation_id))

    def hide(self, conversation_id: str, message_ids: list[str]) -> None:
        self.db.executemany("INSERT OR IGNORE INTO hidden_messages (conversation_id, message_id) VALUES (?, ?)",
                            [(conversation_id, i) for i in message_ids])

    def hidden(self, conversation_id: str) -> set[str]:
        rows = self.db.execute("SELECT message_id FROM hidden_messages WHERE conversation_id = ?", (conversation_id,))
        return {r["message_id"] for r in rows}

    def delete(self, conversation_id: str) -> None:
        self.db.execute("DELETE FROM conversations WHERE id = ?", (conversation_id,))
        self.db.execute("DELETE FROM hidden_messages WHERE conversation_id = ?", (conversation_id,))
        self.db.execute("DELETE FROM talk_messages WHERE conversation_id = ?", (conversation_id,))


# turns

@dataclass
class Turn:
    conversation_id: str
    turn_id: str
    agent_id: str
    title: str
    user_text: str
    started_at: int
    seq: int = 0
    status: str = "running"  # or "queued" until the turn before it ends (§11)
    text: str = ""
    tools: list[dict] = field(default_factory=list)
    commentary: list[str] = field(default_factory=list)
    waiting_for_approval: bool = False
    approval: dict | None = None  # the pending request: {choices, command?, description?, request_id?}
    error: str | None = None
    usage: dict | None = None
    runtime: dict | None = None
    client_msg_id: str | None = None
    attachments: list[dict] = field(default_factory=list)
    content: str | list | None = None  # what goes to the agent, when it differs from user_text
    run_id: str | None = None
    resolves: tuple[str, int] | None = None  # (job id, at): the blocked run this turn retries
    task: asyncio.Task | None = None

    def snapshot(self) -> dict:
        snap = {"conversation_id": self.conversation_id, "turn_id": self.turn_id, "seq": self.seq,
                "status": self.status, "user_text": self.user_text, "text": self.text,
                "tools": [dict(t) for t in self.tools], "commentary": list(self.commentary),
                "waiting_for_approval": self.waiting_for_approval, "started_at": self.started_at}
        if self.attachments:
            snap["attachments"] = [dict(a) for a in self.attachments]
        if self.waiting_for_approval and self.approval is not None:
            snap["approval"] = dict(self.approval)
        for key in ("error", "usage", "runtime"):
            if getattr(self, key) is not None:
                snap[key] = getattr(self, key)
        return snap


APPROVAL_CHOICES = ("once", "session", "always", "deny")


def _approval(payload: dict) -> dict:
    """What a device needs from Hermes's approval.request: what to run, why, and the allowed answers."""
    choices = payload.get("choices")
    if not (isinstance(choices, list) and choices):
        choices = ["once", "deny"]
    pending: dict = {"choices": [c for c in choices if c in APPROVAL_CHOICES] or ["once", "deny"]}
    for key in ("command", "description", "request_id"):
        value = payload.get(key)
        if isinstance(value, str) and value:
            pending[key] = value[:MAX_PREVIEW] if key != "request_id" else value[:256]
    return pending


def _usage(raw) -> dict | None:
    if not isinstance(raw, dict):
        return None
    usage = {k: raw[k] for k in ("input_tokens", "output_tokens", "total_tokens")
             if isinstance(raw.get(k), int) and not isinstance(raw.get(k), bool) and raw[k] >= 0}
    return usage or None


def _runtime(raw) -> dict | None:
    if not isinstance(raw, dict):
        return None
    runtime = {k: raw[k] for k in ("provider", "model") if isinstance(raw.get(k), str) and raw[k]}
    return runtime or None


def _image_mime(part: dict) -> str:
    ref = part.get("image_url")
    url = ref.get("url") if isinstance(ref, dict) else ref
    match = re.match(r"^data:(image/[\w.+-]+);", url) if isinstance(url, str) else None
    return match.group(1) if match else "image/*"


def _message_content(content) -> tuple[str, list[dict]]:
    """A Hermes message's text and its attachments: inline images, and the files the bridge
    saved to the agent's inbox (their `Attached file:` lines leave the text)."""
    parts, attachments = [], []
    for part in [content] if isinstance(content, str) else content if isinstance(content, list) else []:
        if isinstance(part, str):
            parts.append(part)
        elif isinstance(part, dict):
            if isinstance(part.get("text"), str):
                parts.append(part["text"])
            elif "image" in str(part.get("type", "")):
                attachments.append({"kind": "image", "name": "Photo", "mime": _image_mime(part)})
    lines = []
    for line in "\n".join(p for p in parts if p).split("\n"):
        found = FILE_LINE.match(line.strip())
        if found:
            name = os.path.basename(found["path"])
            name = re.sub(r"^b-[0-9a-f]{24}-", "", name)
            kind = "image" if found["mime"].startswith("image/") else "file"
            attachments.append({"kind": kind, "name": name, "mime": found["mime"], "size": int(found["size"])})
        else:
            lines.append(line)
    if any(a["kind"] == "image" and a["name"] != "Photo" for a in attachments):
        # photos saved to the inbox are also inline: list each once, by its name
        attachments = [a for a in attachments if not (a["kind"] == "image" and a["name"] == "Photo" and "size" not in a)]
    return "\n".join(lines), attachments


def _tool_names(tool_calls) -> list[str]:
    names = []
    for call in tool_calls if isinstance(tool_calls, list) else []:
        fn = call.get("function") if isinstance(call, dict) else None
        name = (fn or {}).get("name") if isinstance(fn, dict) else (call or {}).get("name")
        if isinstance(name, str) and name:
            names.append(name)
    return names


def file_message(r) -> dict:
    """An agent_files row as the assistant message devices show (§9 chat.file)."""
    kind = "image" if r["mime"].startswith("image/") else "file"
    return {"id": f"f-{r['id']}", "role": "assistant", "text": r["caption"], "ts": r["at"],
            "attachments": [{"kind": kind, "name": r["name"], "mime": r["mime"], "size": r["size"],
                             "root": r["root"], "path": r["path"]}]}


def page_start(messages: list[dict]) -> int | None:
    times = [x["ts"] for x in messages if isinstance(x.get("ts"), int)]
    return min(times) if times else None


def _rank(x: dict) -> int:
    """Within a second: what the owner said in Talk, then the agent's files, then the agent's session, then the talker."""
    if x["id"].startswith("t-"):
        return 0 if x["role"] == "user" else 3
    return 1 if x["id"].startswith("f-") else 2


def with_files(messages: list[dict], files: list[dict], upper: int | None, oldest: bool) -> list[dict]:
    """A history page with the bridge's own messages (the agent's files, Talk) at their time: those from its first
    message's time (or any, on the oldest page) up to [upper], the first time of the newer page (none on the
    newest). So pages split them with no gap and nothing twice."""
    lo = page_start(messages)
    page = [f for f in files if (upper is None or f["ts"] < upper) and (oldest or (lo is not None and f["ts"] >= lo))]
    if not page:
        return messages
    merged = messages + page
    # stable: a file sent while a reply ran sorts before the reply that finished after it
    return sorted(merged, key=lambda x: (x["ts"] if isinstance(x.get("ts"), int) else 0, _rank(x)))


def history_messages(rows: list[dict]) -> list[dict]:
    """Hermes session rows to Talaria messages: user and assistant text only, oldest first.
    Tools called by assistant steps without text are listed on the next assistant message."""
    def key(row: dict):
        ts = row.get("timestamp")
        return (ts if isinstance(ts, (int, float)) else 0, row.get("id") if isinstance(row.get("id"), int) else 0)

    out, pending_tools = [], []
    for row in sorted(rows, key=key):
        role = row.get("role")
        if role not in ("user", "assistant") or row.get("display_kind") == "hidden":
            continue
        text, attachments = _message_content(row.get("content"))
        text = text.strip()
        if role == "assistant":
            pending_tools += _tool_names(row.get("tool_calls"))
            attachments = []
        if not text and not attachments:
            continue
        ts = row.get("timestamp")
        msg = {"id": str(row.get("id", "")), "role": role, "text": text,
               "ts": int(ts) if isinstance(ts, (int, float)) and not isinstance(ts, bool) else None}
        if attachments:
            msg["attachments"] = attachments
        if role == "assistant" and pending_tools:
            msg["tools"], pending_tools = pending_tools, []
        out.append(msg)
    return out


def _inline(blobs: list[Blob]) -> bool:
    """A message's photos go inline when Hermes can take them in one request; otherwise all go to the inbox."""
    images = [b for b in blobs if b.kind == "image"]
    return len(images) <= MAX_INLINE_COUNT and sum(b.size for b in images) <= MAX_INLINE_IMAGES


class ChatService:
    def __init__(self, store: ChatStore, agents: dict[str, HermesClient], broadcast: Broadcast,
                 *, default_agent: str | None = None, blobs: BlobStore | None = None,
                 inboxes: dict[str, Path] | None = None, accounts: list[OpenRouterAccount] | None = None,
                 files: FilesService | None = None, todos: TodoStore | None = None,
                 automations: Automations | None = None, voice: VoiceService | None = None):
        self.store = store
        self.talker = None  # Talk 3's voice talker (talk.py), set by make_chat when it has a voice key
        self.voice = voice  # Talk's natural voice and quick first line (§9)
        self.automations = automations  # the agent's scheduled jobs, the calendar and Home (§14)
        if automations is not None:
            automations.notify = lambda msg: self.broadcast(msg)
            automations.on_chat = self._automation_chat
        self.todos = todos  # the shared to-do list (§13)
        self.files = files  # browsing the agent's folders (§12)
        self.agents = agents
        self.blobs = blobs
        self.inboxes = inboxes or {}  # agent_id -> folder the agent can read, for files
        self.broadcast = broadcast
        self.default_agent = default_agent or next(iter(agents), None)
        self._turns: OrderedDict[str, Turn] = OrderedDict()
        self._active: dict[str, str] = {}  # conversation_id -> running turn_id
        self._queues: dict[str, list[Turn]] = {}  # conversation_id -> turns waiting, in order (§11)
        self.accounts = accounts or []
        self._jobs: set[asyncio.Task] = set()
        self._client_msgs: dict[str, tuple[float, dict]] = {}
        self._grouping: asyncio.Task | None = None
        self._sizes: dict[str, int] = {}  # conversation_id -> about how many tokens the agent reads per call
        self._group_tried: set[str] = set()  # to-dos already handed to the agent to group

    def _automation_chat(self, agent_id: str, session_id: str, title: str, at: int, text: str) -> str:
        """An automation run whose result goes to a chat: the run's own Hermes session becomes a conversation."""
        conv = Conversation(id="c-" + secrets.token_hex(8), agent_id=agent_id, hermes_session_id=session_id,
                            title=title[:MAX_TITLE], created_at=at, updated_at=at, last_role="assistant",
                            last_text=text[:MAX_PREVIEW])
        self.store.add(conv)
        return conv.id

    async def run_background(self) -> None:
        """What runs while the bridge serves: watching the agent's jobs (§14)."""
        if self.automations is not None:
            await self.automations.poll()

    async def close(self) -> None:
        tasks = [t.task for t in self._turns.values() if t.task and not t.task.done()] + list(self._jobs)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        for client in self.agents.values():
            await client.close()
        for account in self.accounts:
            await account.close()
        if self.voice is not None:
            await self.voice.close()
        if self.talker is not None:
            await self.talker.close()

    # chat.send

    async def send(self, p: dict, resolves: tuple[str, int] | None = None) -> tuple[dict, Turn | None]:
        """Validate and register a turn. The caller sends the result, then calls `start`.
        Returns (result, None) for a retried client_msg_id."""
        text = p.get("text", "")
        if not (isinstance(text, str) and len(text) <= MAX_TEXT):
            raise RpcError(m.INVALID_PARAMS, f"text must be at most {MAX_TEXT} characters")
        refs = p.get("attachments")
        if refs is not None and not (isinstance(refs, list) and 0 < len(refs) <= MAX_ATTACHMENTS
                                     and all(isinstance(r, dict) for r in refs)):
            raise RpcError(m.INVALID_PARAMS, f"attachments must list 1 to {MAX_ATTACHMENTS} blobs")
        file_refs = p.get("files")
        if file_refs is not None and not (isinstance(file_refs, list) and 0 < len(file_refs) <= MAX_ATTACHMENTS
                                          and all(isinstance(r, dict) for r in file_refs)):
            raise RpcError(m.INVALID_PARAMS, f"files must list 1 to {MAX_ATTACHMENTS} server files")
        if len(refs or []) + len(file_refs or []) > MAX_ATTACHMENTS:
            raise RpcError(m.INVALID_PARAMS, f"A message can carry {MAX_ATTACHMENTS} attachments")
        if not text.strip() and not refs and not file_refs:
            raise RpcError(m.INVALID_PARAMS, "Send some text or an attachment")
        client_msg_id = p.get("client_msg_id")
        if client_msg_id is not None:
            _id(client_msg_id, "client_msg_id")
            now = time.monotonic()
            for k, (t, _) in list(self._client_msgs.items()):
                if now - t > CLIENT_MSG_TTL_S:
                    del self._client_msgs[k]
            if client_msg_id in self._client_msgs:
                return dict(self._client_msgs[client_msg_id][1]), None
        todo_id = p.get("todo_id")
        if todo_id is not None:
            if self.todos is None:
                raise RpcError(m.NOT_FOUND, "Unknown to-do")
            try:
                self.todos.get(todo_id)
            except TodoError as exc:
                raise RpcError(exc.code, exc.message) from None
        found = await asyncio.to_thread(self._server_files, p, file_refs or [])
        blobs = self._blobs(refs or [])
        try:
            result, turn = await self._send(p, text, blobs, client_msg_id, found, resolves)
        except BaseException:
            for blob in blobs:
                self.blobs.release(blob)  # not sent: the device may retry with the same blobs
            raise
        if todo_id is not None:
            self.todos.link(todo_id, result["conversation_id"])
            await self.todos_changed()
        return result, turn

    async def todos_changed(self) -> None:
        await self.broadcast(m.notification("todos.changed", {"todos": self.todos.list()}))
        self._group_soon()

    def _group_soon(self) -> None:
        """New to-dos without a group: ask the agent to group them, once a few more have had time to come (§13)."""
        if self.todos is None or self.default_agent not in self.agents:
            return
        if self._grouping is not None and not self._grouping.done():
            return
        if not any(t["id"] not in self._group_tried for t in self.todos.open_todos() if "group" not in t):
            return
        self._grouping = asyncio.create_task(self._group_later())
        self._jobs.add(self._grouping)
        self._grouping.add_done_callback(self._jobs.discard)

    async def _group_later(self) -> None:
        await asyncio.sleep(GROUP_DELAY_S)
        self._group_tried.update(t["id"] for t in self.todos.open_todos() if "group" not in t)
        try:
            changed = await group_todos(self.todos, self.agents[self.default_agent], everything=False)
        except GroupingError as exc:
            log.warning("to-dos not grouped: %s", exc)
            return
        except Exception:
            log.exception("grouping to-dos failed")
            return
        if changed:
            await self.broadcast(m.notification("todos.changed", {"todos": self.todos.list()}))

    async def regroup(self) -> dict:
        if self.default_agent not in self.agents:
            raise RpcError(m.AGENT_UNAVAILABLE, "No agent to sort the to-dos")
        try:
            await group_todos(self.todos, self.agents[self.default_agent], everything=True)
        except GroupingError as exc:
            raise RpcError(m.AGENT_UNAVAILABLE, str(exc)) from None
        todos = self.todos.list()
        await self.broadcast(m.notification("todos.changed", {"todos": todos}))
        return {"todos": todos}

    def _server_files(self, p: dict, refs: list[dict]) -> list[Found]:
        """chat.send's `files` (§12): files already on the server, named to the agent where they are."""
        if not refs:
            return []
        if self.files is None:
            raise RpcError(m.MODALITY_UNSUPPORTED, "This bridge shares no folders")
        agent_id = p.get("agent_id")
        if p.get("conversation_id") is not None:
            conv = self.store.get(_id(p["conversation_id"], "conversation_id"))
            agent_id = conv.agent_id if conv is not None else agent_id
        try:
            return [self.files.file(agent_id, r.get("root"), r.get("path")) for r in refs]
        except FilesError as exc:
            raise RpcError(exc.code, exc.message) from None

    async def _send(self, p: dict, text: str, blobs: list[Blob], client_msg_id: str | None,
                    found: list[Found] = (), resolves: tuple[str, int] | None = None) -> tuple[dict, Turn]:
        model = _model(p["model"]) if p.get("model") is not None else None
        conv_id = p.get("conversation_id")
        if conv_id is not None:
            conv = self.store.get(_id(conv_id, "conversation_id"))
            if conv is None:
                raise RpcError(m.NOT_FOUND, "Unknown conversation")
            client = self._client(conv.agent_id)
            self._check_queue(conv_id)
            self._check_files(conv.agent_id, blobs)
        else:
            agent_id = p.get("agent_id", self.default_agent)
            if agent_id is not None:
                _id(agent_id, "agent_id")
            client = self._client(agent_id)
            self._check_files(agent_id, blobs)
            conv = await self._new_conversation(agent_id, client,
                                                text.strip() or (blobs[0].name if blobs else found[0].name))
            conv_id = conv.id
            if model is None and (default := self.store.default_model(conv.agent_id)) is not None:
                try:
                    await self._pin(conv, client, *default)
                except RpcError as exc:  # the chat still starts, on the agent's own default
                    log.warning("default model %s/%s not pinned: %s", *default, exc.message)

        if model is not None and conv.model != {"provider": model[0], "model": model[1]}:
            await self._pin(conv, client, *model)
        self._check_queue(conv_id)  # again: other sends may have come in while this one waited
        content = await asyncio.to_thread(self._content, conv, text, blobs, found) if blobs or found else None
        self._check_queue(conv_id)
        queued = conv_id in self._active
        now_s = int(time.time())
        turn = Turn(conversation_id=conv_id, turn_id="t-" + secrets.token_hex(8), agent_id=conv.agent_id,
                    title=conv.title, user_text=text, started_at=now_s, client_msg_id=client_msg_id,
                    attachments=[b.meta() for b in blobs] + [
                        {"kind": "file", "name": f.name, "mime": f.mime, "size": f.size} for f in found],
                    content=content,
                    status="queued" if queued else "running",
                    resolves=resolves)
        if queued:
            self._queues.setdefault(conv_id, []).append(turn)
        else:
            self._active[conv_id] = turn.turn_id
        self._turns[turn.turn_id] = turn
        self._evict()
        self.store.touch(conv_id, "user", text.strip() or _attachments_preview(turn.attachments), now_s)
        self.store.archive(conv_id, False)  # writing in an archived chat brings it back
        result = {"conversation_id": conv_id, "turn_id": turn.turn_id, "title": conv.title}
        if queued:
            result["queued"] = True
        if client_msg_id is not None:
            self._client_msgs[client_msg_id] = (time.monotonic(), result)
        return result, turn

    def _check_queue(self, conv_id: str) -> None:
        if conv_id in self._active and len(self._queues.get(conv_id, [])) >= MAX_QUEUED:
            raise RpcError(m.CONFLICT, f"{MAX_QUEUED} messages are already waiting in this conversation")

    async def _pin(self, conv: Conversation, client: HermesClient, provider: str, model: str) -> None:
        try:
            await client.set_session_model(conv.hermes_session_id, provider, model)
        except HermesError as exc:
            if exc.status < 500:
                raise RpcError(m.INVALID_PARAMS, f"Can't use that model: {exc.message}") from None
            raise RpcError(m.AGENT_UNAVAILABLE, f"Agent error: {exc.message}") from None
        except HermesUnavailable as exc:
            raise RpcError(m.AGENT_UNAVAILABLE, f"Agent unavailable: {exc}") from None
        self.store.set_model(conv.id, provider, model)
        conv.model_provider, conv.model_name = provider, model

    def _blobs(self, refs: list[dict]) -> list[Blob]:
        if not refs:
            return []
        if self.blobs is None:
            raise RpcError(m.MODALITY_UNSUPPORTED, "This bridge takes no attachments")
        ids = [r.get("blob_id") for r in refs]
        if len(set(map(str, ids))) != len(ids):
            raise RpcError(m.INVALID_PARAMS, "The same attachment is listed twice")
        blobs = []
        try:
            for blob_id in ids:
                blobs.append(self.blobs.take(blob_id))
        except (BlobError, RpcError) as exc:
            for blob in blobs:
                self.blobs.release(blob)
            raise exc if isinstance(exc, RpcError) else RpcError(exc.code, exc.message) from None
        return blobs

    def _check_files(self, agent_id: str | None, blobs: list[Blob]) -> None:
        if self.inboxes.get(agent_id) is not None:
            return
        if any(b.kind == "file" for b in blobs):
            raise RpcError(m.MODALITY_UNSUPPORTED, "This agent can only receive photos, not files")
        if not _inline(blobs):
            raise RpcError(m.INVALID_PARAMS, "Photos too large for one message; send fewer or smaller ones")

    def _content(self, conv: Conversation, text: str, blobs: list[Blob], found: list[Found] = ()) -> str | list:
        """The message for the agent: every attachment saved to its inbox with a line each, so its tools can open
        it (a signature to put on a PDF), and photos also inline when they fit, so it sees them (§10).
        The blobs are used up once this returns."""
        lines = [text] if text.strip() else []
        images = []
        inbox = self.inboxes.get(conv.agent_id)
        try:
            inline = _inline(blobs)
            for blob in blobs:
                if blob.kind == "image" and inline:
                    data = base64.b64encode(blob.path.read_bytes()).decode("ascii")
                    images.append({"type": "input_image", "image_url": f"data:{blob.mime};base64,{data}"})
                if inbox is None:
                    continue  # photos only (checked in _check_files)
                folder = inbox / conv.id
                folder.mkdir(parents=True, exist_ok=True, mode=0o750)
                target = folder / f"{blob.blob_id}-{safe_name(blob.name)}"
                try:
                    os.replace(blob.path, target)  # moved, not copied: a 2 GB file needs no second 2 GB
                except OSError:
                    shutil.copyfile(blob.path, target)  # another filesystem
                os.chmod(target, 0o640)  # the agent reads it through the inbox's group
                lines.append(f"Attached file: {target} ({blob.mime}, {blob.size} bytes)")
        except OSError as exc:
            log.warning("could not prepare attachments: %s", exc)
            raise RpcError(m.INTERNAL_ERROR, "The bridge could not store the attachment") from None
        for f in found:
            lines.append(f"Attached file: {f.agent_path} ({f.mime}, {f.size} bytes)")
        for blob in blobs:
            self.blobs.discard(blob.blob_id)
        message = "\n\n".join(lines)
        if not images:
            return message
        return ([{"type": "text", "text": message}] if message else []) + images

    def _client(self, agent_id: str | None) -> HermesClient:
        if agent_id is None or agent_id not in self.agents:
            raise RpcError(m.AGENT_UNAVAILABLE,
                           "No chat agent is configured on the bridge" if agent_id is None
                           else f"Agent {agent_id!r} has no chat configured")
        return self.agents[agent_id]

    async def _new_conversation(self, agent_id: str, client: HermesClient, text: str) -> Conversation:
        token = secrets.token_hex(8)
        title = _title_from(text)
        try:
            await client.create_session(f"talaria_{token}", title)
        except (HermesError, HermesUnavailable) as exc:
            log.warning("could not create a Hermes session: %s", exc)
            raise RpcError(m.AGENT_UNAVAILABLE, f"Agent unavailable: {exc}") from None
        now_s = int(time.time())
        conv = Conversation(id=f"c-{token}", agent_id=agent_id, hermes_session_id=f"talaria_{token}",
                            title=title, created_at=now_s, updated_at=now_s)
        self.store.add(conv)
        return conv

    def _evict(self) -> None:
        while len(self._turns) > KEPT_TURNS:
            oldest = next((tid for tid, t in self._turns.items() if t.status not in ("running", "queued")), None)
            if oldest is None:
                return
            del self._turns[oldest]

    def start(self, job: Turn | Job) -> None:
        """Start what a request left to do once its result is sent: a turn, or a job like an aside."""
        if not isinstance(job, Turn):
            task = asyncio.ensure_future(job())
            self._jobs.add(task)
            task.add_done_callback(self._jobs.discard)
        elif job.status == "queued":
            self._spawn(self._announce_queued(job))
        else:
            job.task = asyncio.ensure_future(self._run(job))

    def _spawn(self, coro) -> None:
        task = asyncio.ensure_future(coro)
        self._jobs.add(task)
        task.add_done_callback(self._jobs.discard)

    async def _announce_queued(self, turn: Turn) -> None:
        queue = self._queues.get(turn.conversation_id, [])
        if turn not in queue:
            return  # already started or cancelled
        params = {"conversation_id": turn.conversation_id, "turn_id": turn.turn_id, "user_text": turn.user_text,
                  "position": queue.index(turn) + 1}
        if turn.client_msg_id is not None:
            params["client_msg_id"] = turn.client_msg_id
        if turn.attachments:
            params["attachments"] = [dict(a) for a in turn.attachments]
        with contextlib.suppress(Exception):
            await self.broadcast(m.notification("chat.queued", params))

    # the Hermes stream

    async def _emit(self, turn: Turn, method: str, params: dict) -> None:
        if method != "chat.started":
            turn.seq += 1
            params = {"conversation_id": turn.conversation_id, "turn_id": turn.turn_id,
                      "seq": turn.seq, **params}
        await self.broadcast(m.notification(method, params))

    async def _delta(self, turn: Turn, kind: str, **fields) -> None:
        await self._emit(turn, "chat.delta", {"kind": kind, **fields})

    async def _run(self, turn: Turn) -> None:
        started = {"conversation_id": turn.conversation_id, "turn_id": turn.turn_id, "agent_id": turn.agent_id,
                   "title": turn.title, "user_text": turn.user_text, "started_at": turn.started_at}
        if turn.client_msg_id is not None:
            started["client_msg_id"] = turn.client_msg_id
        if turn.attachments:
            started["attachments"] = [dict(a) for a in turn.attachments]
        await self._emit(turn, "chat.started", started)
        conv = self.store.get(turn.conversation_id)
        final_text: str | None = None
        status: str | None = None
        progressed = asyncio.Event()
        watch = None
        before = None
        try:
            if conv is None:
                raise HermesUnavailable("conversation was deleted")
            client = self.agents[conv.agent_id]
            before = await self._totals(client, conv.hermes_session_id)
            if self._sizes.get(conv.id, 0) >= TIDY_TOKENS:
                watch = asyncio.create_task(self._tidy_watch(turn, progressed))
            stream = client.chat_stream(conv.hermes_session_id, turn.content if turn.content is not None else turn.user_text)
            async for name, payload in stream:
                if name not in ("run.started", "message.started"):
                    progressed.set()
                if turn.waiting_for_approval and name != "approval.request":
                    turn.waiting_for_approval, turn.approval = False, None
                if name == "run.started":
                    run_id = payload.get("run_id")
                    turn.run_id = run_id if isinstance(run_id, str) else None
                elif name == "assistant.delta":
                    delta = payload.get("delta")
                    if isinstance(delta, str) and delta:
                        turn.text += delta
                        await self._delta(turn, "text", text=delta)
                elif name in ("tool.started", "tool.completed", "tool.failed"):
                    tool = {"name": str(payload.get("tool_name") or "tool"), "state": name.split(".")[1]}
                    preview = payload.get("preview")
                    if isinstance(preview, str) and preview:
                        tool["preview"] = preview[:MAX_PREVIEW]
                    turn.tools.append(tool)
                    await self._delta(turn, "tool_progress", tool=tool)
                elif name == "assistant.commentary":
                    text = payload.get("text")
                    if isinstance(text, str) and text.strip():
                        turn.commentary.append(text)
                        await self._delta(turn, "commentary", text=text)
                elif name == "approval.request":
                    turn.waiting_for_approval = True
                    turn.approval = _approval(payload)
                    what = turn.approval.get("description") or turn.approval.get("command") or "an action"
                    await self._delta(turn, "approval", text=f"Hermes asks to run: {what}"[:MAX_PREVIEW],
                                      approval=dict(turn.approval))
                elif name == "assistant.completed":
                    content = payload.get("content")
                    if isinstance(content, str):
                        final_text = content
                elif name in ("run.completed", "run.failed", "run.cancelled"):
                    status = name.split(".")[1]
                    turn.usage = _usage(payload.get("usage"))
                    turn.runtime = _runtime(payload.get("runtime"))
                    if status == "failed":
                        turn.error = str(payload.get("error") or payload.get("turn_exit_reason") or "The agent failed")
                elif name == "error":
                    turn.error = str(payload.get("message") or "The agent failed")
                elif name == "done":
                    break
            if status is None:
                status = "failed"
                turn.error = turn.error or "The agent stream ended without a result"
        except asyncio.CancelledError:
            status = "cancelled"
        except HermesError as exc:
            status, turn.error = "failed", f"Agent error: {exc.message}"
        except HermesUnavailable as exc:
            status, turn.error = "failed", f"Agent unavailable: {exc}"
        except Exception as exc:  # never leave a turn running
            log.exception("chat turn %s failed", turn.turn_id)
            status, turn.error = "failed", f"Bridge error: {type(exc).__name__}"
        finally:
            progressed.set()
            if watch is not None:
                watch.cancel()
            if before is not None and conv is not None:
                self._spawn(self._measure(self.agents[conv.agent_id], conv, before))
            turn.waiting_for_approval, turn.approval = False, None
            turn.status = status or "failed"
            if final_text is not None:
                turn.text = final_text
            # hand the conversation straight to the next queued turn, so no new send jumps the queue
            queue = self._queues.get(turn.conversation_id) or []
            following = queue.pop(0) if queue else None
            if not queue:
                self._queues.pop(turn.conversation_id, None)
            if following is not None and self.store.get(turn.conversation_id) is not None:
                self._active[turn.conversation_id] = following.turn_id
            else:
                following = None
                self._active.pop(turn.conversation_id, None)
            if turn.text and self.store.get(turn.conversation_id) is not None:
                self.store.touch(turn.conversation_id, "assistant", turn.text, int(time.time()))
            done = {"status": turn.status, "text": turn.text}
            for key in ("error", "usage", "runtime"):
                if getattr(turn, key) is not None:
                    done[key] = getattr(turn, key)
            with contextlib.suppress(Exception):
                await asyncio.shield(self._emit(turn, "chat.done", done))
            log.info("turn %s in %s: %s", turn.turn_id, turn.conversation_id, turn.status)
            if self.talker is not None and turn.status in ("completed", "failed"):
                # a conversation being talked in hears the agent's reply (Talk 3)
                self.talker.on_reply(turn.conversation_id, turn.text or (turn.error or ""), turn.status)
            if turn.resolves is not None and turn.status == "completed" and self.automations is not None:
                with contextlib.suppress(Exception):  # the blocked run worked in a chat: Home lets it go (§14)
                    await asyncio.shield(self.automations.dismiss_run(*turn.resolves))
            if following is not None:
                following.status, following.started_at = "running", int(time.time())
                self.start(following)

    # chat.cancel, chat.turn.get

    # how big a chat is: the agent compresses big ones before answering, which takes a while (§9 "Tidying up")

    async def _totals(self, client: HermesClient, session_id: str) -> tuple[int, int] | None:
        """The session's tokens read so far and its model calls, from the agent; None if it can't say."""
        try:
            info = await client.session(session_id)
            read = sum(int(info.get(k) or 0) for k in ("input_tokens", "cache_read_tokens", "cache_write_tokens"))
            return read, int(info.get("api_call_count") or 0)
        except Exception:  # noqa: BLE001 (a nicety: without it there's no tidying note)
            return None

    async def _measure(self, client: HermesClient, conv: Conversation, before: tuple[int, int]) -> None:
        after = await self._totals(client, conv.hermes_session_id)
        if after is not None and after[1] > before[1]:
            self._sizes[conv.id] = (after[0] - before[0]) // (after[1] - before[1])

    async def _tidy_watch(self, turn: Turn, progressed: asyncio.Event) -> None:
        try:
            await asyncio.wait_for(progressed.wait(), TIDY_AFTER_S)
        except asyncio.TimeoutError:
            turn.commentary.append(TIDYING)
            await self._delta(turn, "commentary", text=TIDYING)

    def _turn(self, p: dict) -> Turn:
        turn = self._turns.get(_id(p.get("turn_id"), "turn_id"))
        if turn is None:
            raise RpcError(m.NOT_FOUND, "Unknown or expired turn")
        return turn

    async def cancel(self, p: dict) -> dict:
        turn = self._turn(p)
        queue = self._queues.get(turn.conversation_id, [])
        if turn.status == "queued" and turn in queue:
            queue.remove(turn)
            turn.status = "cancelled"
            with contextlib.suppress(Exception):
                await self._emit(turn, "chat.done", {"status": "cancelled", "text": ""})
            return {"turn_id": turn.turn_id, "status": "cancelled"}
        if turn.status != "running":
            return {"turn_id": turn.turn_id, "status": turn.status}
        stopped = False
        if turn.run_id is not None:
            client = self.agents.get(turn.agent_id)
            try:
                if client is not None:
                    await client.stop_run(turn.run_id)
                    stopped = True
            except (HermesError, HermesUnavailable) as exc:
                log.warning("stop for run %s failed (%s); closing the stream instead", turn.run_id, exc)
        if not stopped and turn.task is not None:
            turn.task.cancel()  # Hermes interrupts a run whose stream closes
        return {"turn_id": turn.turn_id, "status": "stopping"}

    def turn_get(self, p: dict) -> dict:
        return {"turn": self._turn(p).snapshot()}

    # chat.history, conversations.*

    def _conversation(self, p: dict) -> Conversation:
        conv = self.store.get(_id(p.get("conversation_id"), "conversation_id"))
        if conv is None:
            raise RpcError(m.NOT_FOUND, "Unknown conversation")
        return conv

    async def history(self, p: dict) -> dict:
        conv = self._conversation(p)
        limit = p.get("limit", HISTORY_DEFAULT)
        if not (isinstance(limit, int) and not isinstance(limit, bool) and 1 <= limit <= HISTORY_MAX):
            raise RpcError(m.INVALID_PARAMS, f"limit must be 1 to {HISTORY_MAX}")
        before = p.get("before")
        offset, upper = 0, None
        if before is not None:
            # "<offset>" or "<offset>:<the newer page's first time>" (for the agent's files, §9); opaque to devices
            found = re.fullmatch(r"(\d{1,9})(?::(\d{1,12}))?", before) if isinstance(before, str) else None
            if found is None:
                raise RpcError(m.INVALID_PARAMS, "before must be a cursor from next_before")
            offset = int(found[1])
            upper = int(found[2]) if found[2] else None
        try:
            rows = await self._client(conv.agent_id).messages(conv.hermes_session_id, limit=limit, offset=offset)
        except HermesError as exc:
            if exc.status == 404:
                return {"messages": [], "next_before": None}
            raise RpcError(m.AGENT_UNAVAILABLE, f"Agent error: {exc.message}") from None
        except HermesUnavailable as exc:
            raise RpcError(m.AGENT_UNAVAILABLE, f"Agent unavailable: {exc}") from None
        hidden = self.store.hidden(conv.id)
        page = history_messages(rows)
        kept = self.store.agent_files(conv.id) + self.store.talk_messages(conv.id)
        messages = with_files(page, kept, upper=upper, oldest=len(rows) < limit)
        start = page_start(page)
        cursor = f"{offset + len(rows)}" + (f":{start}" if start is not None else "")
        return {"messages": [x for x in messages if x["id"] not in hidden],
                "next_before": cursor if len(rows) >= limit else None}

    async def agent_file(self, agent_id: str, path: object, caption: object = None) -> dict:
        """send_file (§15): a file the agent made, into the conversation it is replying in, on every device."""
        if caption is not None and not (isinstance(caption, str) and len(caption) <= MAX_FILE_CAPTION):
            raise RpcError(m.INVALID_PARAMS, f"caption must be text of at most {MAX_FILE_CAPTION} characters")
        if self.files is None:
            raise RpcError(m.NOT_FOUND, "This bridge shares no folders, so it can't send files")
        try:
            found = await asyncio.to_thread(self.files.find, agent_id, path)
        except FilesError as exc:
            raise RpcError(exc.code, exc.message) from None
        conv = self._replying_in(agent_id)
        if conv is None:
            raise RpcError(m.CONFLICT, "No Talaria chat with you is going on, so there's nowhere to send it")
        at = int(time.time())
        text = (caption or "").strip()
        fid = self.store.add_file(conv.id, at, found.root.id, found.rel, found.name, found.mime, found.size, text)
        message = file_message({"id": fid, "conversation_id": conv.id, "at": at, "root": found.root.id,
                                "path": found.rel, "name": found.name, "mime": found.mime, "size": found.size,
                                "caption": text})
        self.store.touch(conv.id, "assistant", text or f"📎 {found.name}", at)
        await self.broadcast(m.notification("chat.file", {"conversation_id": conv.id, "message": message}))
        return {"conversation_id": conv.id, "name": found.name, "size": found.size}

    def _replying_in(self, agent_id: str) -> Conversation | None:
        """The conversation the agent is replying in now (the latest started), or else its latest of the last 30 min."""
        running = [(t.started_at, conv_id) for conv_id, turn_id in self._active.items()
                   if (t := self._turns.get(turn_id)) is not None and t.agent_id == agent_id]
        if running:
            return self.store.get(max(running)[1])
        recent = [c for c in self.store.all() if c.agent_id == agent_id and c.updated_at >= time.time() - RECENT_CHAT_S]
        return max(recent, key=lambda c: c.updated_at) if recent else None

    def list(self) -> dict:
        out = []
        for c in self.store.all():
            item = {"conversation_id": c.id, "agent_id": c.agent_id, "title": c.title,
                    "created_at": c.created_at, "updated_at": c.updated_at}
            if c.last_role in ("user", "assistant") and c.last_text:
                item["last_message"] = {"role": c.last_role, "text": c.last_text[:LAST_MESSAGE_LEN]}
            if c.id in self._active:
                item["active_turn_id"] = self._active[c.id]
            if self._queues.get(c.id):
                item["queued_turn_ids"] = [t.turn_id for t in self._queues[c.id]]
            if c.model is not None:
                item["model"] = c.model
            if c.pinned:
                item["pinned"] = True
            if c.archived:
                item["archived"] = True
            out.append(item)
        return {"conversations": out}

    async def rename(self, p: dict) -> dict:
        conv = self._conversation(p)
        title = p.get("title")
        if not (isinstance(title, str) and title.strip() and len(title) <= MAX_TITLE):
            raise RpcError(m.INVALID_PARAMS, f"title must be 1 to {MAX_TITLE} characters")
        title = " ".join(title.split())
        client = self.agents.get(conv.agent_id)
        if client is not None:
            try:
                await client.rename_session(conv.hermes_session_id, title)
            except (HermesError, HermesUnavailable) as exc:
                # The Talaria title is what the apps show; Hermes catching up later is fine.
                log.warning("could not rename Hermes session %s: %s", conv.hermes_session_id, exc)
        self.store.rename(conv.id, title)
        return {"conversation_id": conv.id, "title": title}

    async def pin(self, p: dict) -> dict:
        conv = self._conversation(p)
        pinned = p.get("pinned")
        if not isinstance(pinned, bool):
            raise RpcError(m.INVALID_PARAMS, "pinned must be true or false")
        client = self.agents.get(conv.agent_id)
        if client is not None:
            try:
                await client.pin_session(conv.hermes_session_id, pinned)
            except (HermesError, HermesUnavailable) as exc:
                log.warning("could not pin Hermes session %s: %s", conv.hermes_session_id, exc)
        self.store.pin(conv.id, pinned)
        return {"conversation_id": conv.id, "pinned": pinned}

    async def archive(self, p: dict) -> dict:
        """Take a conversation off the list (or put it back with archived: false). Nothing is deleted."""
        conv = self._conversation(p)
        archived = p.get("archived")
        if not isinstance(archived, bool):
            raise RpcError(m.INVALID_PARAMS, "archived must be true or false")
        self.store.archive(conv.id, archived)
        return {"conversation_id": conv.id, "archived": archived}

    async def hide(self, p: dict) -> dict:
        """Hide messages from Talaria on every device. The agent's session keeps them."""
        conv = self._conversation(p)
        ids = p.get("message_ids")
        if not (isinstance(ids, list) and 0 < len(ids) <= MAX_HIDE and all(isinstance(i, str) and 0 < len(i) <= 64
                                                                          for i in ids)):
            raise RpcError(m.INVALID_PARAMS, f"message_ids must list 1 to {MAX_HIDE} message ids")
        ids = list(dict.fromkeys(ids))
        self.store.hide(conv.id, ids)
        result = {"conversation_id": conv.id, "message_ids": ids}
        await self.broadcast(m.notification("chat.hidden", result))
        return result

    async def delete(self, p: dict) -> dict:
        conv = self._conversation(p)
        if conv.id in self._active:
            raise RpcError(m.CONFLICT, "Stop the running reply before deleting")
        client = self.agents.get(conv.agent_id)
        if client is not None:
            try:
                await client.delete_session(conv.hermes_session_id)
            except HermesError as exc:
                raise RpcError(m.AGENT_UNAVAILABLE, f"Agent error: {exc.message}") from None
            except HermesUnavailable as exc:
                raise RpcError(m.AGENT_UNAVAILABLE, f"Agent unavailable: {exc}") from None
        self.store.delete(conv.id)
        return {"conversation_id": conv.id, "deleted": True}

    # §11: models, steer, aside, status, balance

    async def models(self, p: dict) -> dict:
        agent_id = p.get("agent_id", self.default_agent)
        if agent_id is not None:
            _id(agent_id, "agent_id")
        client = self._client(agent_id)
        try:
            data = await client.model_options()
        except HermesError as exc:
            raise RpcError(m.AGENT_UNAVAILABLE, f"Agent error: {exc.message}") from None
        except HermesUnavailable as exc:
            raise RpcError(m.AGENT_UNAVAILABLE, f"Agent unavailable: {exc}") from None
        # only what the OpenRouter key may use (its guardrail), when the bridge holds that key (§11)
        allowed = await self.voice.allowed_models() if self.voice is not None else None
        providers = []
        for row in data.get("providers") or []:
            if not isinstance(row, dict) or row.get("authenticated") is False:
                continue  # Hermes lists providers it has no key for, to set up in `hermes model`
            slug = row.get("slug")
            models = [x for x in row.get("models") or [] if isinstance(x, str) and 0 < len(x) <= 200]
            if slug == "openrouter" and allowed:
                models = [x for x in models if x in allowed]
            if isinstance(slug, str) and 0 < len(slug) <= 200 and models:
                providers.append({"id": slug, "name": str(row.get("name") or slug), "models": models})
        result = {"agent_id": agent_id, "providers": providers}
        provider, model = data.get("provider"), data.get("model")
        if all(isinstance(x, str) and 0 < len(x) <= 200 for x in (provider, model)):
            result["current"] = {"provider": provider, "model": model}
        if (default := self.store.default_model(agent_id)) is not None:
            result["default"] = {"provider": default[0], "model": default[1]}
        return result

    async def set_default_model(self, p: dict) -> dict:
        agent_id = p.get("agent_id", self.default_agent)
        if agent_id is not None:
            _id(agent_id, "agent_id")
        self._client(agent_id)  # a known agent
        model = _model(p["model"]) if p.get("model") is not None else None
        self.store.set_default_model(agent_id, model)
        result = {"agent_id": agent_id}
        if model is not None:
            result["default"] = {"provider": model[0], "model": model[1]}
        await self.broadcast(m.notification("agent.default_model", result))
        return result

    async def set_model(self, p: dict) -> dict:
        conv = self._conversation(p)
        provider, model = _model(p.get("model"))
        await self._pin(conv, self._client(conv.agent_id), provider, model)
        return {"conversation_id": conv.id, "model": {"provider": provider, "model": model}}

    async def steer(self, p: dict) -> dict:
        turn = self._turn(p)
        text = _note(p.get("text"), "text")
        if turn.status != "running" or turn.run_id is None:
            raise RpcError(m.CONFLICT, "That reply isn't running")
        try:
            accepted = await self._client(turn.agent_id).steer_run(turn.run_id, text)
        except HermesError as exc:
            raise RpcError(m.AGENT_UNAVAILABLE, f"Agent error: {exc.message}") from None
        except HermesUnavailable as exc:
            raise RpcError(m.AGENT_UNAVAILABLE, f"Agent unavailable: {exc}") from None
        return {"turn_id": turn.turn_id, "accepted": accepted}

    async def approve(self, p: dict) -> dict:
        turn = self._turn(p)
        choice = p.get("choice")
        if choice not in APPROVAL_CHOICES:
            raise RpcError(m.INVALID_PARAMS, "choice must be one of " + ", ".join(APPROVAL_CHOICES))
        pending = turn.approval
        if turn.status != "running" or turn.run_id is None or not turn.waiting_for_approval or pending is None:
            raise RpcError(m.CONFLICT, "Nothing is waiting for approval")
        if choice not in pending["choices"]:
            raise RpcError(m.INVALID_PARAMS, "Hermes doesn't offer that choice here")
        try:
            answered = await self._client(turn.agent_id).approve_run(turn.run_id, choice, pending.get("request_id"))
        except HermesError as exc:
            raise RpcError(m.AGENT_UNAVAILABLE, f"Agent error: {exc.message}") from None
        except HermesUnavailable as exc:
            raise RpcError(m.AGENT_UNAVAILABLE, f"Agent unavailable: {exc}") from None
        if turn.approval is pending:  # the stream may have moved on meanwhile
            turn.waiting_for_approval, turn.approval = False, None
        if not answered:
            raise RpcError(m.CONFLICT, "Nothing is waiting for approval")
        # tells every device, so the card goes away on the phone and the laptop alike
        await self._delta(turn, "approval_done", choice=choice)
        return {"turn_id": turn.turn_id, "choice": choice}

    def aside(self, p: dict) -> tuple[dict, Job]:
        conv = self._conversation(p)
        question = _note(p.get("text"), "text")
        client = self._client(conv.agent_id)
        aside_id = "a-" + secrets.token_hex(8)
        return {"aside_id": aside_id}, lambda: self._aside(conv, client, aside_id, question)

    async def _aside(self, conv: Conversation, client: HermesClient, aside_id: str, question: str) -> None:
        """Ask in a throwaway Hermes session that carries the conversation's recent messages, so
        the conversation itself gains nothing and its running turn isn't disturbed."""
        session_id = f"talaria_aside_{secrets.token_hex(8)}"
        created, status, text, error = False, "failed", "", None
        try:
            rows = await client.messages(conv.hermes_session_id, limit=40, offset=0)
            transcript = "\n\n".join(
                f"{'User' if x['role'] == 'user' else 'Assistant'}: {x['text']}" for x in history_messages(rows))
            await client.create_session(session_id, None)
            created = True
            if conv.model is not None:
                with contextlib.suppress(HermesError):
                    await client.set_session_model(session_id, conv.model_provider, conv.model_name)
            prompt = ASIDE_PROMPT.format(transcript=transcript[-ASIDE_TRANSCRIPT_CHARS:], question=question)
            async for name, payload in client.chat_stream(session_id, prompt):
                if name == "assistant.delta" and isinstance(payload.get("delta"), str):
                    text += payload["delta"]
                elif name == "assistant.completed" and isinstance(payload.get("content"), str):
                    text = payload["content"]
                elif name in ("run.completed", "run.failed", "run.cancelled"):
                    status = "completed" if name == "run.completed" else "failed"
                    if status == "failed":
                        error = str(payload.get("error") or "The agent couldn't answer")
                elif name == "done":
                    break
            if status != "completed":
                error = error or "The agent couldn't answer"
        except HermesError as exc:
            error = f"Agent error: {exc.message}"
        except HermesUnavailable as exc:
            error = f"Agent unavailable: {exc}"
        except Exception as exc:
            log.exception("aside %s failed", aside_id)
            error = f"Bridge error: {type(exc).__name__}"
        finally:
            if created:
                with contextlib.suppress(Exception):
                    await asyncio.shield(client.delete_session(session_id))
        done = {"conversation_id": conv.id, "aside_id": aside_id, "question": question,
                "status": "completed" if error is None else "failed", "text": text}
        if error is not None:
            done["error"] = error
        with contextlib.suppress(Exception):
            await self.broadcast(m.notification("chat.aside.done", done))

    async def status(self, p: dict) -> dict:
        conv = self._conversation(p)
        try:
            info = await self._client(conv.agent_id).session(conv.hermes_session_id)
        except HermesError as exc:
            if exc.status != 404:
                raise RpcError(m.AGENT_UNAVAILABLE, f"Agent error: {exc.message}") from None
            info = {}
        except HermesUnavailable as exc:
            raise RpcError(m.AGENT_UNAVAILABLE, f"Agent unavailable: {exc}") from None
        result: dict = {"conversation_id": conv.id, "queued": len(self._queues.get(conv.id, []))}
        if conv.model is not None:
            result["model"] = conv.model
        for ours, theirs in (("messages", "message_count"), ("tool_calls", "tool_call_count"),
                             ("input_tokens", "input_tokens"), ("output_tokens", "output_tokens")):
            value = info.get(theirs)
            if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
                result[ours] = value
        cost = info.get("actual_cost_usd") or info.get("estimated_cost_usd")
        if isinstance(cost, (int, float)) and not isinstance(cost, bool) and cost >= 0:
            result["cost_usd"] = round(float(cost), 4)
        if conv.id in self._active:
            result["active_turn_id"] = self._active[conv.id]
        return result

    async def balance(self) -> dict:
        return {"accounts": list(await asyncio.gather(*(a.balance() for a in self.accounts)))}

    async def handle(self, method: str, p: dict) -> tuple[dict, Turn | Job | None]:
        """Dispatch one chat request. Returns (result, turn to start after the result is sent)."""
        if method in BLOB_METHODS:
            if self.blobs is None:
                raise RpcError(m.MODALITY_UNSUPPORTED, "This bridge takes no attachments")
            try:
                # file work off the event loop: a chunk is up to 512 KiB, a commit hashes up to 2 GiB
                return await asyncio.to_thread(self.blobs.handle, method, p), None
            except BlobError as exc:
                raise RpcError(exc.code, exc.message) from None
        if method in FILES_METHODS:
            if self.files is None:
                raise RpcError(m.MODALITY_UNSUPPORTED, "This bridge shares no folders")
            try:
                return await asyncio.to_thread(self.files.handle, method, p), None
            except FilesError as exc:
                raise RpcError(exc.code, exc.message) from None
        if method == "automations.run_in_chat":
            if self.automations is None:
                raise RpcError(m.METHOD_NOT_FOUND, f"Method not found: {method}")
            try:
                agent_id, text, blocked = await self.automations.chat_task(p)
            except AutomationError as exc:
                raise RpcError(exc.code, exc.message) from None
            return await self.send({"agent_id": agent_id, "text": text}, resolves=blocked)
        if method in AUTOMATION_METHODS:
            if self.automations is None:
                raise RpcError(m.METHOD_NOT_FOUND, f"Method not found: {method}")
            try:
                return await self.automations.handle(method, p), None
            except AutomationError as exc:
                raise RpcError(exc.code, exc.message) from None
        if method in TODO_METHODS:
            if self.todos is None:
                raise RpcError(m.METHOD_NOT_FOUND, f"Method not found: {method}")
            if method == "todos.regroup":
                return await self.regroup(), None
            try:
                result, changed = self.todos.handle(method, p)
            except TodoError as exc:
                raise RpcError(exc.code, exc.message) from None
            if changed:
                await self.todos_changed()
            return result, None
        if method == "chat.send":
            return await self.send(p)
        if method == "chat.cancel":
            return await self.cancel(p), None
        if method == "chat.turn.get":
            return self.turn_get(p), None
        if method == "chat.history":
            return await self.history(p), None
        if method == "conversations.list":
            return self.list(), None
        if method == "conversations.rename":
            return await self.rename(p), None
        if method == "conversations.delete":
            return await self.delete(p), None
        if method == "conversations.pin":
            return await self.pin(p), None
        if method == "conversations.archive":
            return await self.archive(p), None
        if method == "chat.hide":
            return await self.hide(p), None
        if method == "conversations.set_model":
            return await self.set_model(p), None
        if method == "agent.models":
            return await self.models(p), None
        if method == "agent.set_default_model":
            return await self.set_default_model(p), None
        if method == "chat.steer":
            return await self.steer(p), None
        if method == "chat.approve":
            return await self.approve(p), None
        if method == "chat.aside":
            return self.aside(p)
        if method == "chat.status":
            return await self.status(p), None
        if method == "account.balance":
            return await self.balance(), None
        if method in TALK_METHODS:
            if self.talker is None:
                raise RpcError(m.METHOD_NOT_FOUND, f"Method not found: {method}")
            try:
                if method == "talk.turn":
                    return await self.talker.turn(p), None
                if method == "talk.say":
                    return await self.talker.say(p), None
                if method == "talk.commit":
                    return await self.talker.commit(p), None
                if method == "talk.cancel":
                    return self.talker.cancel(p), None
                if method == "talk.voices":
                    return self.talker.voices(), None
                if method == "talk.voice":
                    return self.talker.set_voice(p), None
                return self.talker.end(p), None
            except TalkError as exc:
                raise RpcError(exc.code, exc.message) from None
        if method in VOICE_METHODS:
            if self.voice is None:
                raise RpcError(m.METHOD_NOT_FOUND, f"Method not found: {method}")
            p = dict(p)
            if method == "voice.ack" and p.get("conversation_id") is not None:
                conv = self._conversation(p)
                if conv.last_role == "assistant" and conv.last_text:
                    p["context"] = conv.last_text
            try:
                return await (self.voice.speech(p) if method == "voice.speech" else self.voice.ack(p)), None
            except VoiceError as exc:
                raise RpcError(exc.code, exc.message) from None
        raise RpcError(m.METHOD_NOT_FOUND, f"Method not found: {method}")


CHAT_METHODS = frozenset({"chat.send", "chat.cancel", "chat.turn.get", "chat.history",
                          "conversations.list", "conversations.rename", "conversations.delete",
                          "conversations.set_model", "agent.models", "agent.set_default_model", "chat.steer", "chat.approve", "chat.aside", "chat.status",
                          "automations.run_in_chat", "conversations.pin", "conversations.archive", "chat.hide",
                          "account.balance"}) | VOICE_METHODS | TALK_METHODS | BLOB_METHODS | FILES_METHODS | TODO_METHODS | AUTOMATION_METHODS


def _attachments_preview(attachments: list[dict]) -> str:
    photos = sum(1 for a in attachments if a["kind"] == "image")
    files = [a["name"] for a in attachments if a["kind"] == "file"]
    if photos and not files:
        return "📷 Photo" if photos == 1 else f"📷 {photos} photos"
    if files and not photos:
        return "📎 " + ", ".join(files)
    return f"📎 {len(attachments)} attachments"
