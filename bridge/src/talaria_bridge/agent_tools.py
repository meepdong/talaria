"""Tools for the agent (spec/README.md §15): the to-do list and VPS commands over MCP, on loopback.

A minimal MCP server on the Streamable HTTP transport: every POST gets one JSON-RPC response as
application/json, and there is no event stream. Each agent authenticates with its own token.
"""

from __future__ import annotations

import asyncio
import contextlib
import hmac
import json
import logging
import os
import secrets
import shlex
from collections.abc import AsyncIterator, Awaitable, Callable
from urllib.parse import urlsplit

from .todos import TodoError, TodoStore
from .vps_config import VPSAllowlist, load_allowlist

log = logging.getLogger("talaria.agent_tools")

DEFAULT_PORT = 8767
PROTOCOL_VERSIONS = ("2025-06-18", "2025-03-26", "2024-11-05")
MAX_BODY = 256 * 1024
MIN_TOKEN = 32
LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1", "[::1]"}
VPS_APPROVAL_TIMEOUT = 120.0

TOOLS = [
    {
        "name": "todo_list",
        "description": "List the owner's to-dos, the list shown on Home in the Talaria app on their phone and laptop."
                       " Open ones come first, oldest first, with their group and the owner's comments.",
        "inputSchema": {"type": "object", "properties": {
            "include_done": {"type": "boolean", "description": "Also list the recently ticked-off ones."}}},
    },
    {
        "name": "todo_add",
        "description": "Add a to-do to the owner's list in the Talaria app. Use this when they ask you to remember,"
                       " note or add something to do.",
        "inputSchema": {"type": "object", "properties": {
            "text": {"type": "string", "description": "What to do, up to 500 characters."},
            "due": {"type": "string", "description": "Optional due date, YYYY-MM-DD."},
            "group": {"type": "string", "description": "Optional group, such as Home or Work: one already in"
                                                        " todo_list when it fits. Left out, Talaria sorts it in."}},
            "required": ["text"]},
    },
    {
        "name": "todo_update",
        "description": "Change one of the owner's to-dos: tick it off (done: true), open it again, reword it, set its"
                       " due date (due: null clears it) or move it to another group. Get ids from todo_list.",
        "inputSchema": {"type": "object", "properties": {
            "id": {"type": "string"},
            "text": {"type": "string"},
            "due": {"type": ["string", "null"], "description": "YYYY-MM-DD, or null to clear it."},
            "done": {"type": "boolean"},
            "group": {"type": ["string", "null"], "description": "Up to 40 characters, or null to clear it."}},
            "required": ["id"]},
    },
    {
        "name": "todo_comment",
        "description": "Add a comment to one of the owner's to-dos, such as progress or what you need from them."
                       " It shows under the to-do in the app, marked as yours.",
        "inputSchema": {"type": "object", "properties": {
            "id": {"type": "string"},
            "text": {"type": "string", "description": "Up to 2000 characters."}},
            "required": ["id", "text"]},
    },
    {
        "name": "vps_run",
        "description": "Run a command on the VPS host. Use for builds, deploys, service management, logs, or opencode."
                       " Every call requires on-device approval. Only allowlisted commands are permitted.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "Allowlist command name (e.g., 'gradlew', 'systemctl', 'opencode')"},
                "args": {"type": "array", "items": {"type": "string"}, "description": "Arguments array (no shell interpolation)"},
                "cwd": {"type": "string", "description": "Working directory", "default": "/opt/talaria"},
                "timeout": {"type": "integer", "description": "Timeout in seconds", "default": 300, "maximum": 1800},
                "stream_output": {"type": "boolean", "description": "Stream output back to chat in real-time", "default": True},
            },
            "required": ["command"],
        },
    },
]

Changed = Callable[[], Awaitable[None]]


class VPSCommandError(Exception):
    def __init__(self, message: str, exit_code: int | None = None, output: str | None = None):
        super().__init__(message)
        self.message = message
        self.exit_code = exit_code
        self.output = output


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
    if todo.get("group"):
        out["group"] = todo["group"]
    if todo.get("comments"):
        out["comments"] = [{"text": c["text"], "by": c["by"]} for c in todo["comments"]]
    return out


class AgentTools:
    def __init__(
        self,
        todos: TodoStore,
        tokens: dict[str, str],
        changed: Changed,
        allowlist: VPSAllowlist | None = None,
        vps_enabled: bool = True,
    ):
        """[tokens] maps each agent's token to its id; [changed] tells every device (todos.changed)."""
        self.todos = todos
        self.tokens = tokens
        self.changed = changed
        self.allowlist = allowlist
        self.vps_enabled = vps_enabled
        self._rate_limits: dict[str, list[float]] = {}
        self._vps_approvals: dict[str, asyncio.Future[dict]] = {}

    def agent_for(self, authorization: str | None) -> str | None:
        if not authorization or not authorization.startswith("Bearer "):
            return None
        given = authorization[len("Bearer "):].strip().encode()
        found = None
        for token, agent_id in self.tokens.items():
            if hmac.compare_digest(given, token.encode()):
                found = agent_id
        return found

    def _check_rate_limit(self, agent_id: str) -> bool:
        """Check rate limit: max 5 commands per minute per agent."""
        import time
        now = time.time()
        window_start = now - 60
        if agent_id not in self._rate_limits:
            self._rate_limits[agent_id] = []
        self._rate_limits[agent_id] = [t for t in self._rate_limits[agent_id] if t > window_start]
        if len(self._rate_limits[agent_id]) >= 5:
            return False
        self._rate_limits[agent_id].append(now)
        return True

    async def _request_vps_approval(self, agent_id: str, request: dict) -> dict:
        """Send VPS approval request to all devices and wait for response."""
        approval_id = "vps-" + secrets.token_hex(8)
        future: asyncio.Future[dict] = asyncio.get_running_loop().create_future()
        self._vps_approvals[approval_id] = future

        # Broadcast approval request to all devices
        if hasattr(self, 'broadcast') and self.broadcast:
            await self.broadcast({
                "method": "vps.approval.request",
                "params": {
                    "approval_id": approval_id,
                    "command": request["command"],
                    "args": request["args"],
                    "cwd": request["cwd"],
                    "timeout": request["timeout"],
                    "agent_id": agent_id,
                }
            })

        try:
            result = await asyncio.wait_for(future, timeout=VPS_APPROVAL_TIMEOUT)
            return result
        except asyncio.TimeoutError:
            raise VPSCommandError("Approval request timed out")
        finally:
            self._vps_approvals.pop(approval_id, None)

    def resolve_vps_approval(self, approval_id: str, choice: str) -> bool:
        """Resolve a pending VPS approval from chat.approve."""
        future = self._vps_approvals.pop(approval_id, None)
        if future is None or future.done():
            return False
        future.set_result({"choice": choice})
        return True

    def set_broadcast(self, broadcast: Callable[[dict], Awaitable[None]]) -> None:
        """Set the broadcast function for sending approval requests."""
        self.broadcast = broadcast

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
                                " shown on Home, and can run allowlisted commands on the VPS.",
            }}
        if method == "ping":
            return {"jsonrpc": "2.0", "id": msg_id, "result": {}}
        if method == "tools/list":
            tools = TOOLS
            if not self.vps_enabled:
                tools = [t for t in TOOLS if t["name"] != "vps_run"]
            return {"jsonrpc": "2.0", "id": msg_id, "result": {"tools": tools}}
        if method == "tools/call":
            name, args = params.get("name"), params.get("arguments") or {}
            if not isinstance(args, dict):
                return _rpc_error(msg_id, -32602, "arguments must be an object")
            if name not in {t["name"] for t in TOOLS}:
                return _rpc_error(msg_id, -32602, f"Unknown tool: {name}")
            if name == "vps_run" and not self.vps_enabled:
                return _rpc_error(msg_id, -32602, "VPS commands are disabled")
            log.info("agent %s calls %s", agent_id, name)
            return {"jsonrpc": "2.0", "id": msg_id, "result": await self.call(name, args, agent_id)}
        return _rpc_error(msg_id, -32601, f"Method not found: {method}")

    async def call(self, name: str, args: dict, agent_id: str) -> dict:
        try:
            if name == "todo_list":
                todos = self.todos.list()
                if not args.get("include_done"):
                    todos = [t for t in todos if not t["done"]]
                return _tool_result({"todos": [_brief(t) for t in todos]})
            if name == "todo_add":
                todo = self.todos.add({k: args[k] for k in ("text", "due", "group") if k in args})
            elif name == "todo_comment":
                todo = self.todos.comment({k: args[k] for k in ("id", "text") if k in args}, by="agent")
            elif name == "vps_run":
                return await self._call_vps_run(args, agent_id)
            else:
                todo = self.todos.update({k: args[k] for k in ("id", "text", "due", "done", "group") if k in args})
        except TodoError as exc:
            return _tool_result(exc.message, is_error=True)
        except VPSCommandError as exc:
            return _tool_result(
                f"Command failed (exit {exc.exit_code}): {exc.message}\nOutput:\n{exc.output or '(none)'}",
                is_error=True,
            )
        except Exception as exc:
            log.exception("vps_run error")
            return _tool_result(f"Internal error: {exc}", is_error=True)
        await self.changed()
        return _tool_result({"todo": _brief(todo)})

    async def _call_vps_run(self, args: dict, agent_id: str) -> dict:
        log.info("agent %s calls vps_run: %s", agent_id, args)
        if not self.vps_enabled:
            raise VPSCommandError("VPS commands are disabled")
        if not self._check_rate_limit(agent_id):
            raise VPSCommandError("Rate limit exceeded: max 5 commands per minute")
        if self.allowlist is None:
            raise VPSCommandError("VPS allowlist not configured")

        command_name = args.get("command")
        if not isinstance(command_name, str):
            raise VPSCommandError("'command' must be a string")

        cmd_args = args.get("args", [])
        if not isinstance(cmd_args, list) or not all(isinstance(a, str) for a in cmd_args):
            raise VPSCommandError("'args' must be an array of strings")

        cwd = args.get("cwd", "/opt/talaria")
        if not isinstance(cwd, str):
            raise VPSCommandError("'cwd' must be a string")

        timeout = args.get("timeout", 300)
        if not isinstance(timeout, int) or timeout < 1 or timeout > 1800:
            raise VPSCommandError("'timeout' must be an integer between 1 and 1800")

        stream_output = args.get("stream_output", True)
        if not isinstance(stream_output, bool):
            raise VPSCommandError("'stream_output' must be a boolean")

        # Validate against allowlist
        try:
            entry = self.allowlist.validate(command_name, cmd_args)
        except ValueError as exc:
            raise VPSCommandError(str(exc))

        # Request approval from device
        approval_request = {
            "command": command_name,
            "args": cmd_args,
            "cwd": cwd,
            "timeout": timeout,
        }
        approval_result = await self._request_vps_approval(agent_id, approval_request)
        choice = approval_result.get("choice")
        if choice == "deny":
            raise VPSCommandError("Command denied by user")
        # For "once" and "session", we proceed. "always" would need persistent storage.

        # Build command
        cmd = [entry.path] + cmd_args

        log.info("agent %s runs VPS command: %s", agent_id, shlex.join(cmd))

        # Execute with streaming
        try:
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                cwd=cwd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                preexec_fn=os.setsid,  # Create new process group for clean kill
            )
        except Exception as exc:
            raise VPSCommandError(f"Failed to start command: {exc}")

        stdout_lines = []
        stderr_lines = []

        async def read_stream(stream, lines_list, prefix=""):
            while True:
                line = await stream.readline()
                if not line:
                    break
                decoded = line.decode("utf-8", "replace").rstrip("\n")
                lines_list.append(f"{prefix}{decoded}")

        try:
            await asyncio.wait_for(
                asyncio.gather(
                    read_stream(proc.stdout, stdout_lines, ""),
                    read_stream(proc.stderr, stderr_lines, "ERR: "),
                    proc.wait(),
                ),
                timeout=timeout,
            )
        except asyncio.TimeoutError:
            # Kill the entire process group
            try:
                os.killpg(os.getpgid(proc.pid), 9)
            except Exception:
                pass
            raise VPSCommandError(f"Command timed out after {timeout}s", exit_code=-1, output="\n".join(stdout_lines + stderr_lines))

        output = "\n".join(stdout_lines + stderr_lines)
        exit_code = proc.returncode

        if stream_output and output:
            # Return streaming-friendly format
            return _tool_result({
                "exit_code": exit_code,
                "output": output,
                "command": shlex.join(cmd),
                "cwd": cwd,
            })
        else:
            if exit_code != 0:
                raise VPSCommandError(f"Command exited with code {exit_code}", exit_code=exit_code, output=output)
            return _tool_result({
                "exit_code": exit_code,
                "output": output,
                "command": shlex.join(cmd),
                "cwd": cwd,
            })

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