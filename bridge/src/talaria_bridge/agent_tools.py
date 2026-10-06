"""Tools for the agent (spec/README.md §15): the to-do list and server operations over MCP, on loopback.

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
from typing import TYPE_CHECKING
from urllib.parse import urlsplit

from .automations import AutomationError
from .todos import TodoError, TodoStore

if TYPE_CHECKING:
    from .automations import Automations
    from .server_ops import ServerOps

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
]

REPORT_TO = {
    "name": "automation_report_to",
    "description": "Choose where one of your scheduled (cron) jobs reports in Talaria, the owner's app (spec §14):"
                   " \"home\" shows each run's result on Home and as a notification on their phone and laptop,"
                   " \"chat\" opens a new Talaria chat per run, \"log\" keeps it in the job's history only."
                   " When the owner asks in Talaria for an automation, a reminder or a notification, don't ask where to"
                   " send it: create the job with your scheduling tool delivering locally (deliver: local), then call"
                   " this with its id and \"home\" (or \"chat\" if they want to discuss each result).",
    "inputSchema": {"type": "object", "properties": {
        "id": {"type": "string", "description": "The job's id, as your scheduling tool returned it."},
        "to": {"type": "string", "enum": ["home", "chat", "log"]}},
        "required": ["id", "to"]},
}

SEND_FILE = {
    "name": "send_file",
    "description": "Send the owner a file you made (a PDF, an image, a document, a spreadsheet, ...) in the Talaria chat"
                   " you are replying in: it appears there with Open and Share on their phone and laptop. Use it"
                   " whenever the result of a task is a file, instead of saying you can't send files or asking them"
                   " to look in a folder. Save the file in your workspace (/workspace/projects) first; files the owner"
                   " sent you (the inbox) work too. At most 2 GB.",
    "inputSchema": {"type": "object", "properties": {
        "path": {"type": "string", "description": "The file's full path as you see it, e.g. /workspace/projects/report.pdf."},
        "caption": {"type": "string", "description": "A short line shown with the file, e.g. \"Signed PDF\"."}},
        "required": ["path"]},
}

SERVER_OP = {
    "name": "server_op",
    "description": "Run an operation on the server that hosts you and Talaria (spec §16). Read operations answer at once:"
                   " system.overview, services.list, service.logs {service, lines}, docker.ps, tailscale.status,"
                   " bridge.version, ssh.recent_logins {lines}, ops.history {lines}. Operations that change something"
                   " ask the owner to approve on their phone first and wait up to 2 minutes: service.restart {service},"
                   " docker.restart {container}, bridge.update, disk.cleanup, apt.upgrade, system.reboot."
                   " If the owner denies or doesn't answer, it doesn't run; don't retry without asking them.",
    "inputSchema": {"type": "object", "properties": {
        "op": {"type": "string", "description": "The operation, e.g. system.overview or service.restart."},
        "params": {"type": "object", "description": "Its parameters, e.g. {\"service\": \"docker\"}."}},
        "required": ["op"]},
}

LIST_BOTS = {
    "name": "list_bots",
    "description": "The owner's specialist bots (Hermes profiles made in Hermes Desktop), each with what it's for. Use it"
                   " to pick the right bot before ask_bot.",
    "inputSchema": {"type": "object", "properties": {}},
}

ASK_BOT = {
    "name": "ask_bot",
    "description": "Hand a job to one of the owner's specialist bots (list_bots says who does what: mail, calendar, Drive,"
                   " sheets, PDFs, pictures, research, meetings, vendors...) when the job clearly fits one or the owner"
                   " names it. The bot works in its own chat (the owner sees it there) and reports back to you; you"
                   " then answer the owner yourself. Waits up to wait_seconds (default 240) for the report; a longer job"
                   " returns its id: check it with bot_job. Write a self-contained brief: the bot can't see this chat.",
    "inputSchema": {"type": "object", "properties": {
        "bot": {"type": "string", "description": "The bot's name or profile, as list_bots gives it."},
        "message": {"type": "string", "description": "The self-contained brief."},
        "files": {"type": "array", "items": {"type": "string"},
                  "description": "Optional: full paths of files the bot needs (the inbox or /workspace/projects)."},
        "wait_seconds": {"type": "integer", "minimum": 0, "maximum": 280}},
        "required": ["bot", "message"]},
}

BOT_JOB = {
    "name": "bot_job",
    "description": "How a job you gave a bot with ask_bot is going, and its report once it's done.",
    "inputSchema": {"type": "object", "properties": {"job": {"type": "string", "description": "The job id ask_bot gave."}},
                    "required": ["job"]},
}

BOT_JOBS_AT_ONCE = 3  # jobs handed to bots through these tools at the same time: bots have them too, so no chains

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
    if todo.get("group"):
        out["group"] = todo["group"]
    if todo.get("comments"):
        out["comments"] = [{"text": c["text"], "by": c["by"]} for c in todo["comments"]]
    return out


class AgentTools:
    def __init__(self, todos: TodoStore, tokens: dict[str, str], changed: Changed, ops: ServerOps | None = None,
                 automations: Automations | None = None, chat=None):
        """[tokens] maps each agent's token to its id; [changed] tells every device (todos.changed);
        [ops] adds server_op (§16) when talaria-ops is installed; [automations] adds automation_report_to (§14)."""
        self.todos = todos
        self.tokens = tokens
        self.changed = changed
        self.ops = ops
        self.automations = automations
        self.chat = chat  # ChatService: send_file (§15), when it shares folders; bots (§18.1) when it has them
        self._bot_jobs: list[str] = []  # turn ids of jobs handed to bots through ask_bot

    @property
    def tools(self) -> list[dict]:
        return (TOOLS + ([REPORT_TO] if self.automations is not None else [])
                + ([SEND_FILE] if self.chat is not None and self.chat.files is not None else [])
                + ([SERVER_OP] if self.ops is not None else [])
                + ([LIST_BOTS, ASK_BOT, BOT_JOB] if self.chat is not None and getattr(self.chat, "bots", None) is not None else []))

    async def _bots_tool(self, name: str, args: dict) -> dict:
        """Hermes hands a job to a bot (spec §15): a work order in the bot's chat, its report back here."""
        from .chat import RpcError
        from .workorders import work_order

        bots = self.chat.bots
        roster = bots.roster() if bots.backend.connected else []
        if name == "list_bots":
            if not roster:
                return _tool_result("No bots are available right now (none made, or Hermes's backend is down).")
            return _tool_result({"bots": [{"name": b["name"], "profile": b["profile"],
                                           **({"for": b["description"]} if b.get("description") else {})} for b in roster]})
        if name == "bot_job":
            return _tool_result(self._job_state(str(args.get("job") or "")))
        wanted = str(args.get("bot") or "").strip().lower().lstrip("@")
        bot = next((b for b in roster if wanted in (b["name"].lower(), b["profile"].lower())), None)
        if bot is None:
            names = ", ".join(b["name"] for b in roster) or "none"
            return _tool_result(f"No bot called {args.get('bot')!r}. The bots are: {names}.", is_error=True)
        message = str(args.get("message") or "").strip()
        if not message:
            return _tool_result("The message is empty.", is_error=True)
        running = [t for t in self._bot_jobs if self._running(t)]
        if len(running) >= BOT_JOBS_AT_ONCE:
            return _tool_result(f"{len(running)} bot jobs are already running; wait for one (bot_job) or do it yourself.",
                                is_error=True)
        files = [f"Attached file: {p}" for p in args.get("files") or [] if isinstance(p, str) and p.startswith("/")]
        brief = message + "\n\nDo this job yourself: don't hand it on to another bot."
        try:
            result, turn = await self.chat.send({"agent_id": bot["id"], "text": message}, worker=bot["name"],
                                                order=work_order("Hermes", brief, files, about="the owner's main agent"))
        except RpcError as exc:
            return _tool_result(exc.message, is_error=True)
        if turn is not None:
            self.chat.start(turn)
        job = result["turn_id"]
        self._bot_jobs = [t for t in self._bot_jobs if self._running(t)] + [job]
        wait = args.get("wait_seconds", 240)
        wait = wait if isinstance(wait, int) and not isinstance(wait, bool) and 0 <= wait <= 280 else 240
        for _ in range(wait * 2):
            if not self._running(job):
                break
            await asyncio.sleep(0.5)
        return _tool_result(self._job_state(job, bot["name"]))

    def _running(self, job: str) -> bool:
        turn = self.chat._turns.get(job)
        return turn is not None and turn.status in ("running", "queued")

    def _job_state(self, job: str, bot: str | None = None) -> dict:
        turn = self.chat._turns.get(job)
        if turn is None:
            return {"job": job, "status": "unknown", "note": "No such job, or it finished long ago: see the bot's chat."}
        out = {"job": job, "bot": bot or turn.worker or turn.agent_id, "status": turn.status}
        if turn.status in ("running", "queued"):
            out["note"] = "Still working. Check again later with bot_job; its report also lands in the bot's chat."
            if turn.waiting_for_approval:
                out["waiting_for"] = "the owner's approval"
        else:
            out["report"] = (turn.text or "")[:6000]
            if turn.error:
                out["error"] = turn.error
        return out

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
                                " shown on Home; choose where your scheduled jobs report in it (automation_report_to:"
                                " Home also notifies their devices, so you never need to ask where to send a result);"
                                " send them files you make (send_file); hand jobs to the owner's specialist bots and"
                                " get their reports (list_bots, ask_bot, bot_job); and, when offered, run operations"
                                " on the server with the owner's approval.",
            }}
        if method == "ping":
            return {"jsonrpc": "2.0", "id": msg_id, "result": {}}
        if method == "tools/list":
            return {"jsonrpc": "2.0", "id": msg_id, "result": {"tools": self.tools}}
        if method == "tools/call":
            name, args = params.get("name"), params.get("arguments") or {}
            if not isinstance(args, dict):
                return _rpc_error(msg_id, -32602, "arguments must be an object")
            if name not in {t["name"] for t in self.tools}:
                return _rpc_error(msg_id, -32602, f"Unknown tool: {name}")
            log.info("agent %s calls %s", agent_id, name)
            return {"jsonrpc": "2.0", "id": msg_id, "result": await self.call(name, args, agent_id)}
        return _rpc_error(msg_id, -32601, f"Method not found: {method}")

    async def call(self, name: str, args: dict, agent_id: str = "agent") -> dict:
        if name == "automation_report_to" and self.automations is not None:
            if args.get("to") not in ("home", "chat", "log"):
                return _tool_result("to must be home, chat or log", is_error=True)
            try:
                a = (await self.automations.update({"id": args.get("id"), "result_to": args["to"]}))["automation"]
            except AutomationError as exc:
                return _tool_result(exc.message, is_error=True)
            return _tool_result({"id": a["id"], "name": a["name"], "result_to": a["result_to"]})
        if name == "send_file" and self.chat is not None:
            from .chat import RpcError

            try:
                return _tool_result(await self.chat.agent_file(agent_id, args.get("path"), args.get("caption")))
            except RpcError as exc:
                return _tool_result(exc.message, is_error=True)
        if name in ("list_bots", "ask_bot", "bot_job") and self.chat is not None and self.chat.bots is not None:
            return await self._bots_tool(name, args)
        if name == "server_op" and self.ops is not None:
            result, is_error = await self.ops.agent_call(args.get("op"), args.get("params"), agent_id)
            return _tool_result(result, is_error=is_error)
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
            else:
                todo = self.todos.update({k: args[k] for k in ("id", "text", "due", "done", "group") if k in args})
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
