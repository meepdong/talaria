"""Chat proxy (PROTOCOL §10.3, spec/README.md §9).

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
MAX_ATTACHMENTS = 10
MAX_INLINE_IMAGES = 7 * 1024 * 1024  # Hermes refuses requests over 10 MB, and base64 adds a third
# the line the bridge adds to a message for each file it saved to the agent's inbox (§10)
FILE_LINE = re.compile(r"^Attached file: (?P<path>.+) \((?P<mime>[^,()]+), (?P<size>\d+) bytes\)$")

Broadcast = Callable[[dict], Awaitable[None]]


class RpcError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def _id(value, name: str) -> str:
    if not (isinstance(value, str) and 0 < len(value) <= 64):
        raise RpcError(m.INVALID_PARAMS, f"{name} must be a string of 1 to 64 characters")
    return value


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
    last_text TEXT
);
"""


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


class ChatStore:
    def __init__(self, path: Path | str):
        self.db = sqlite3.connect(str(path), isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)

    def close(self) -> None:
        self.db.close()

    def add(self, c: Conversation) -> None:
        self.db.execute(
            "INSERT INTO conversations VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (c.id, c.agent_id, c.hermes_session_id, c.title, c.created_at, c.updated_at,
             c.last_role, c.last_text))

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

    def rename(self, conversation_id: str, title: str) -> None:
        self.db.execute("UPDATE conversations SET title = ? WHERE id = ?", (title, conversation_id))

    def delete(self, conversation_id: str) -> None:
        self.db.execute("DELETE FROM conversations WHERE id = ?", (conversation_id,))


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
    status: str = "running"
    text: str = ""
    tools: list[dict] = field(default_factory=list)
    commentary: list[str] = field(default_factory=list)
    waiting_for_approval: bool = False
    error: str | None = None
    usage: dict | None = None
    runtime: dict | None = None
    client_msg_id: str | None = None
    attachments: list[dict] = field(default_factory=list)
    content: str | list | None = None  # what goes to the agent, when it differs from user_text
    run_id: str | None = None
    task: asyncio.Task | None = None

    def snapshot(self) -> dict:
        snap = {"conversation_id": self.conversation_id, "turn_id": self.turn_id, "seq": self.seq,
                "status": self.status, "user_text": self.user_text, "text": self.text,
                "tools": [dict(t) for t in self.tools], "commentary": list(self.commentary),
                "waiting_for_approval": self.waiting_for_approval, "started_at": self.started_at}
        if self.attachments:
            snap["attachments"] = [dict(a) for a in self.attachments]
        for key in ("error", "usage", "runtime"):
            if getattr(self, key) is not None:
                snap[key] = getattr(self, key)
        return snap


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
            attachments.append({"kind": "file", "name": name, "mime": found["mime"], "size": int(found["size"])})
        else:
            lines.append(line)
    return "\n".join(lines), attachments


def _tool_names(tool_calls) -> list[str]:
    names = []
    for call in tool_calls if isinstance(tool_calls, list) else []:
        fn = call.get("function") if isinstance(call, dict) else None
        name = (fn or {}).get("name") if isinstance(fn, dict) else (call or {}).get("name")
        if isinstance(name, str) and name:
            names.append(name)
    return names


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


class ChatService:
    def __init__(self, store: ChatStore, agents: dict[str, HermesClient], broadcast: Broadcast,
                 *, default_agent: str | None = None, blobs: BlobStore | None = None,
                 inboxes: dict[str, Path] | None = None):
        self.store = store
        self.agents = agents
        self.blobs = blobs
        self.inboxes = inboxes or {}  # agent_id -> folder the agent can read, for files
        self.broadcast = broadcast
        self.default_agent = default_agent or next(iter(agents), None)
        self._turns: OrderedDict[str, Turn] = OrderedDict()
        self._active: dict[str, str] = {}  # conversation_id -> running turn_id
        self._client_msgs: dict[str, tuple[float, dict]] = {}

    async def close(self) -> None:
        tasks = [t.task for t in self._turns.values() if t.task and not t.task.done()]
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        for client in self.agents.values():
            await client.close()

    # chat.send

    async def send(self, p: dict) -> tuple[dict, Turn | None]:
        """Validate and register a turn. The caller sends the result, then calls `start`.
        Returns (result, None) for a retried client_msg_id."""
        text = p.get("text", "")
        if not (isinstance(text, str) and len(text) <= MAX_TEXT):
            raise RpcError(m.INVALID_PARAMS, f"text must be at most {MAX_TEXT} characters")
        refs = p.get("attachments")
        if refs is not None and not (isinstance(refs, list) and 0 < len(refs) <= MAX_ATTACHMENTS
                                     and all(isinstance(r, dict) for r in refs)):
            raise RpcError(m.INVALID_PARAMS, f"attachments must list 1 to {MAX_ATTACHMENTS} blobs")
        if not text.strip() and not refs:
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
        blobs = self._blobs(refs or [])
        try:
            return await self._send(p, text, blobs, client_msg_id)
        except BaseException:
            for blob in blobs:
                self.blobs.release(blob)  # not sent: the device may retry with the same blobs
            raise

    async def _send(self, p: dict, text: str, blobs: list[Blob], client_msg_id: str | None) -> tuple[dict, Turn]:
        conv_id = p.get("conversation_id")
        if conv_id is not None:
            conv = self.store.get(_id(conv_id, "conversation_id"))
            if conv is None:
                raise RpcError(m.NOT_FOUND, "Unknown conversation")
            client = self._client(conv.agent_id)
            if conv_id in self._active:
                raise RpcError(m.CONFLICT, "A reply is still running in this conversation")
            self._check_files(conv.agent_id, blobs)
        else:
            agent_id = p.get("agent_id", self.default_agent)
            if agent_id is not None:
                _id(agent_id, "agent_id")
            client = self._client(agent_id)
            self._check_files(agent_id, blobs)
            conv = await self._new_conversation(agent_id, client, text.strip() or blobs[0].name)
            conv_id = conv.id

        if conv_id in self._active:  # another send won the race while the session was created
            raise RpcError(m.CONFLICT, "A reply is still running in this conversation")
        content = await asyncio.to_thread(self._content, conv, text, blobs) if blobs else None
        now_s = int(time.time())
        turn = Turn(conversation_id=conv_id, turn_id="t-" + secrets.token_hex(8), agent_id=conv.agent_id,
                    title=conv.title, user_text=text, started_at=now_s, client_msg_id=client_msg_id,
                    attachments=[b.meta() for b in blobs], content=content)
        self._active[conv_id] = turn.turn_id
        self._turns[turn.turn_id] = turn
        self._evict()
        self.store.touch(conv_id, "user", text.strip() or _attachments_preview(turn.attachments), now_s)
        result = {"conversation_id": conv_id, "turn_id": turn.turn_id, "title": conv.title}
        if client_msg_id is not None:
            self._client_msgs[client_msg_id] = (time.monotonic(), result)
        return result, turn

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
            if sum(b.size for b in blobs if b.kind == "image") > MAX_INLINE_IMAGES:
                raise RpcError(m.INVALID_PARAMS, "Photos too large for one message; send fewer or smaller ones")
        except (BlobError, RpcError) as exc:
            for blob in blobs:
                self.blobs.release(blob)
            raise exc if isinstance(exc, RpcError) else RpcError(exc.code, exc.message) from None
        return blobs

    def _check_files(self, agent_id: str | None, blobs: list[Blob]) -> None:
        if any(b.kind == "file" for b in blobs) and self.inboxes.get(agent_id) is None:
            raise RpcError(m.MODALITY_UNSUPPORTED, "This agent can only receive photos, not files")

    def _content(self, conv: Conversation, text: str, blobs: list[Blob]) -> str | list:
        """The message for the agent: images inline, files saved to its inbox with a line each.
        The blobs are used up once this returns."""
        lines = [text] if text.strip() else []
        images = []
        try:
            for blob in blobs:
                if blob.kind == "image":
                    data = base64.b64encode(blob.path.read_bytes()).decode("ascii")
                    images.append({"type": "input_image", "image_url": f"data:{blob.mime};base64,{data}"})
                    continue
                folder = self.inboxes[conv.agent_id] / conv.id
                folder.mkdir(parents=True, exist_ok=True, mode=0o750)
                target = folder / f"{blob.blob_id}-{safe_name(blob.name)}"
                shutil.copyfile(blob.path, target)
                os.chmod(target, 0o640)  # the agent reads it through the inbox's group
                lines.append(f"Attached file: {target} ({blob.mime}, {blob.size} bytes)")
        except OSError as exc:
            log.warning("could not prepare attachments: %s", exc)
            raise RpcError(m.INTERNAL_ERROR, "The bridge could not store the attachment") from None
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
            oldest = next((tid for tid, t in self._turns.items() if t.status != "running"), None)
            if oldest is None:
                return
            del self._turns[oldest]

    def start(self, turn: Turn) -> None:
        turn.task = asyncio.ensure_future(self._run(turn))

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
        try:
            if conv is None:
                raise HermesUnavailable("conversation was deleted")
            stream = self.agents[conv.agent_id].chat_stream(
                conv.hermes_session_id, turn.content if turn.content is not None else turn.user_text)
            async for name, payload in stream:
                if turn.waiting_for_approval and name != "approval.request":
                    turn.waiting_for_approval = False
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
                    what = payload.get("description") or payload.get("command") or "an action"
                    await self._delta(turn, "approval", text=f"Waiting for approval in Hermes: {what}"[:MAX_PREVIEW])
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
            turn.waiting_for_approval = False
            turn.status = status or "failed"
            if final_text is not None:
                turn.text = final_text
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

    # chat.cancel, chat.turn.get

    def _turn(self, p: dict) -> Turn:
        turn = self._turns.get(_id(p.get("turn_id"), "turn_id"))
        if turn is None:
            raise RpcError(m.NOT_FOUND, "Unknown or expired turn")
        return turn

    async def cancel(self, p: dict) -> dict:
        turn = self._turn(p)
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
        offset = 0
        if before is not None:
            if not (isinstance(before, str) and before.isdigit() and len(before) <= 9):
                raise RpcError(m.INVALID_PARAMS, "before must be a cursor from next_before")
            offset = int(before)
        try:
            rows = await self._client(conv.agent_id).messages(conv.hermes_session_id, limit=limit, offset=offset)
        except HermesError as exc:
            if exc.status == 404:
                return {"messages": [], "next_before": None}
            raise RpcError(m.AGENT_UNAVAILABLE, f"Agent error: {exc.message}") from None
        except HermesUnavailable as exc:
            raise RpcError(m.AGENT_UNAVAILABLE, f"Agent unavailable: {exc}") from None
        return {"messages": history_messages(rows),
                "next_before": str(offset + len(rows)) if len(rows) >= limit else None}

    def list(self) -> dict:
        out = []
        for c in self.store.all():
            item = {"conversation_id": c.id, "agent_id": c.agent_id, "title": c.title,
                    "created_at": c.created_at, "updated_at": c.updated_at}
            if c.last_role in ("user", "assistant") and c.last_text:
                item["last_message"] = {"role": c.last_role, "text": c.last_text[:LAST_MESSAGE_LEN]}
            if c.id in self._active:
                item["active_turn_id"] = self._active[c.id]
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

    async def handle(self, method: str, p: dict) -> tuple[dict, Turn | None]:
        """Dispatch one chat request. Returns (result, turn to start after the result is sent)."""
        if method in BLOB_METHODS:
            if self.blobs is None:
                raise RpcError(m.MODALITY_UNSUPPORTED, "This bridge takes no attachments")
            try:
                # file work off the event loop: a chunk is up to 512 KiB, a commit hashes 20 MiB
                return await asyncio.to_thread(self.blobs.handle, method, p), None
            except BlobError as exc:
                raise RpcError(exc.code, exc.message) from None
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
        raise RpcError(m.METHOD_NOT_FOUND, f"Method not found: {method}")


CHAT_METHODS = frozenset({"chat.send", "chat.cancel", "chat.turn.get", "chat.history",
                          "conversations.list", "conversations.rename", "conversations.delete"}) | BLOB_METHODS


def _attachments_preview(attachments: list[dict]) -> str:
    photos = sum(1 for a in attachments if a["kind"] == "image")
    files = [a["name"] for a in attachments if a["kind"] == "file"]
    if photos and not files:
        return "📷 Photo" if photos == 1 else f"📷 {photos} photos"
    if files and not photos:
        return "📎 " + ", ".join(files)
    return f"📎 {len(attachments)} attachments"
