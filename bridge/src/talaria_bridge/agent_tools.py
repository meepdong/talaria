"""Tools for the agent (spec/README.md §15): the to-do list over MCP, on loopback.

A minimal MCP server on the Streamable HTTP transport: every POST gets one JSON-RPC response as
application/json, and there is no event stream. Each agent authenticates with its own token.
"""

from __future__ import annotations

import asyncio
import contextlib
import hmac
import json
import logging
from collections.abc import AsyncIterator, Awaitable, Callable
from urllib.parse import urlsplit

from .todos import TodoError, TodoStore

log = logging.getLogger("talaria.agent_tools")

DEFAULT_PORT = 8767
PROTOCOL_VERSIONS = ("2025-06-18", "2025-03-26", "2024-11-05")
MAX_BODY = 256 * 1024
MIN_TOKEN = 32
LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1", "[::1]"}

TOOLS = [
    {
        "name": "todo_list",
        "description": "List the owner's to-dos, the list shown on Home in the Talaria app on their phone and laptop."
                       " Open ones come first, oldest first.",
        "inputSchema": {"type": "object", "properties": {
            "include_done": {"type": "boolean", "description": "Also list the recently ticked-off ones."}}},
    },
    {
        "name": "todo_add",
        "description": "Add a to-do to the owner's list in the Talaria app. Use this when they ask you to remember,"
                       " note or add something to do.",
        "inputSchema": {"type": "object", "properties": {
            "text": {"type": "string", "description": "What to do, up to 500 characters."},
            "due": {"type": "string", "description": "Optional due date, YYYY-MM-DD."}},
            "required": ["text"]},
    },
    {
        "name": "todo_update",
        "description": "Change one of the owner's to-dos: tick it off (done: true), open it again, reword it or set its"
                       " due date (due: null clears it). Get ids from todo_list.",
        "inputSchema": {"type": "object", "properties": {
            "id": {"type": "string"},
            "text": {"type": "string"},
            "due": {"type": ["string", "null"], "description": "YYYY-MM-DD, or null to clear it."},
            "done": {"type": "boolean"}},
            "required": ["id"]},
    },
]

Changed = Callable[[], Awaitable[None]]


def _rpc_error(msg_id, code: int, message: str) -> dict:
    return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": code, "message": message}}


def _tool_result(value, is_error: bool = False) -> dict:
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    out: dict = {"content": [{"type": "text", "text": text}]}
    if is_error:
        out["isError"] = True
    return out


def _brief(todo: dict) -> dict:
    out = {"id": todo["id"], "text": todo["text"]}
    if todo.get("due"):
        out["due"] = todo["due"]
    if todo["done"]:
        out["done"] = True
    return out


class AgentTools:
    def __init__(self, todos: TodoStore, tokens: dict[str, str], changed: Changed):
        """[tokens] maps each agent's token to its id; [changed] tells every device (todos.changed)."""
        self.todos = todos
        self.tokens = tokens
        self.changed = changed

    def agent_for(self, authorization: str | None) -> str | None:
        if not authorization or not authorization.startswith("Bearer "):
            return None
        given = authorization[len("Bearer "):].strip().encode()
        found = None
        for token, agent_id in self.tokens.items():
            if hmac.compare_digest(given, token.encode()):
                found = agent_id
        return found

    # JSON-RPC

    async def rpc(self, msg, agent_id: str) -> dict | None:
        """One JSON-RPC message; None for a notification."""
        if not isinstance(msg, dict) or msg.get("jsonrpc") != "2.0" or not isinstance(msg.get("method"), str):
            return _rpc_error(msg.get("id") if isinstance(msg, dict) else None, -32600, "Invalid request")
        msg_id, method = msg.get("id"), msg["method"]
        params = msg.get("params") if isinstance(msg.get("params"), dict) else {}
        if "id" not in msg:
            return None  # notifications/initialized and the like
        if method == "initialize":
            asked = params.get("protocolVersion")
            return {"jsonrpc": "2.0", "id": msg_id, "result": {
                "protocolVersion": asked if asked in PROTOCOL_VERSIONS else PROTOCOL_VERSIONS[0],
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "talaria", "version": "1"},
                "instructions": "Talaria is the owner's app on their phone and laptop. These tools keep its to-do list,"
                                " shown on Home.",
            }}
        if method == "ping":
            return {"jsonrpc": "2.0", "id": msg_id, "result": {}}
        if method == "tools/list":
            return {"jsonrpc": "2.0", "id": msg_id, "result": {"tools": TOOLS}}
        if method == "tools/call":
            name, args = params.get("name"), params.get("arguments") or {}
            if not isinstance(args, dict):
                return _rpc_error(msg_id, -32602, "arguments must be an object")
            if name not in {t["name"] for t in TOOLS}:
                return _rpc_error(msg_id, -32602, f"Unknown tool: {name}")
            log.info("agent %s calls %s", agent_id, name)
            return {"jsonrpc": "2.0", "id": msg_id, "result": await self.call(name, args)}
        return _rpc_error(msg_id, -32601, f"Method not found: {method}")

    async def call(self, name: str, args: dict) -> dict:
        try:
            if name == "todo_list":
                todos = self.todos.list()
                if not args.get("include_done"):
                    todos = [t for t in todos if not t["done"]]
                return _tool_result({"todos": [_brief(t) for t in todos]})
            if name == "todo_add":
                todo = self.todos.add({k: args[k] for k in ("text", "due") if k in args})
            else:
                todo = self.todos.update({k: args[k] for k in ("id", "text", "due", "done") if k in args})
        except TodoError as exc:
            return _tool_result(exc.message, is_error=True)
        await self.changed()
        return _tool_result({"todo": _brief(todo)})

    # HTTP

    async def respond(self, method: str, target: str, headers: dict[str, str], body: bytes) -> tuple[int, bytes]:
        if urlsplit(target).path != "/mcp":
            return 404, b""
        origin = headers.get("origin")
        if origin and urlsplit(origin).hostname not in LOOPBACK_HOSTS:
            return 403, b""
        agent_id = self.agent_for(headers.get("authorization"))
        if agent_id is None:
            return 401, b""
        if method != "POST":
            return 405, b""
        try:
            msg = json.loads(body)
        except ValueError:
            return 400, json.dumps(_rpc_error(None, -32700, "Parse error")).encode()
        if isinstance(msg, list):
            answers = [a for a in [await self.rpc(x, agent_id) for x in msg] if a is not None]
            return (200, json.dumps(answers).encode()) if answers else (202, b"")
        answer = await self.rpc(msg, agent_id)
        return (200, json.dumps(answer).encode()) if answer is not None else (202, b"")

    async def _client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            while True:
                line = await reader.readline()
                if not line:
                    break
                method, target, _ = line.decode("latin-1").split(" ", 2)
                headers: dict[str, str] = {}
                while (h := await reader.readline()) not in (b"\r\n", b"\n", b""):
                    key, _, value = h.decode("latin-1").partition(":")
                    headers[key.strip().lower()] = value.strip()
                length = int(headers.get("content-length") or 0)
                if length > MAX_BODY or "transfer-encoding" in headers:
                    status, payload, keep = 413, b"", False
                else:
                    body = await reader.readexactly(length) if length else b""
                    status, payload = await self.respond(method, target, headers, body)
                    keep = headers.get("connection", "").lower() != "close"
                reason = {200: "OK", 202: "Accepted", 400: "Bad Request", 401: "Unauthorized", 403: "Forbidden",
                          404: "Not Found", 405: "Method Not Allowed", 413: "Payload Too Large"}[status]
                head = [f"HTTP/1.1 {status} {reason}", f"Content-Length: {len(payload)}"]
                if payload:
                    head.append("Content-Type: application/json")
                if status == 405:
                    head.append("Allow: POST")
                if not keep:
                    head.append("Connection: close")
                writer.write(("\r\n".join(head) + "\r\n\r\n").encode() + payload)
                await writer.drain()
                if not keep:
                    break
        except (asyncio.IncompleteReadError, ConnectionError, ValueError, UnicodeDecodeError):
            pass
        finally:
            writer.close()
            with contextlib.suppress(Exception):
                await writer.wait_closed()

    @contextlib.asynccontextmanager
    async def serve(self, port: int = DEFAULT_PORT, host: str = "127.0.0.1") -> AsyncIterator[asyncio.Server]:
        server = await asyncio.start_server(self._client, host, port)
        try:
            yield server
        finally:
            server.close()
            await server.wait_closed()
