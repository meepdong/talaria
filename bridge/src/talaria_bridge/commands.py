"""Hermes's own / commands in a chat (§18.3), run through the doorway (hermes_serve.py).

A command runs in the chat's Hermes session: a bot's chat (bots.py), or a conversation of the doorway's own agent,
whose API-server session hermes serve can resume too. Read-only commands answer at once; ones that change Hermes's
settings or state wait for a signed approval from a device, shown with the server-operation cards (§16)."""

from __future__ import annotations

import asyncio
import json
import logging
import secrets
import time
from collections.abc import Callable
from dataclasses import dataclass, field

from .bots import BotChatClient, profile_of
from .hermes import HermesError, HermesUnavailable
from .hermes_serve import HermesBackend
from .protocol import keys
from .protocol import messages as m

log = logging.getLogger(__name__)

COMMAND_METHODS = frozenset({"commands.list", "commands.run"})
OP = "hermes.command"
APPROVAL_TTL_S = 120
CATALOG_TTL_S = 300
MAX_LINE = 4000

# Talaria's own commands (§11): the device handles these, so Hermes's versions aren't offered.
TALARIA = frozenset({"model", "retry", "queue", "q", "steer", "btw", "status", "stop", "new", "reset"})

# Terminal, laptop or account things: no sense on a phone, or not for a chat to do.
NEVER = frozenset({
    "debug", "login", "logout", "topup", "subscription", "quit", "exit", "update", "paste", "image", "copy", "prompt",
    "mouse", "redraw", "clear", "skin", "indicator", "density", "statusbar", "sb", "battery", "palette", "pet",
    "hatch", "voice", "wake", "browser", "handoff", "export", "import", "worktree", "init", "diff", "egress",
    "codex-runtime", "resume", "sessions", "save", "restart", "gateway", "sethome", "set-home", "start", "approve",
    "deny", "journey",
})

# Change settings or state: asked first.
APPROVE = frozenset({
    "yolo", "approvals", "snapshot", "snap", "rollback", "personality", "fast", "busy", "verbose", "timestamps",
    "footer", "focus", "reload", "reload-mcp", "reload_mcp", "reload-skills", "reload_skills", "curator",
    "suggestions", "blueprint", "learn", "goal", "subgoal", "heartbeat", "loop", "moa",
})

# Read-only unless given arguments (then they change something).
ARGS_APPROVE = frozenset({"reasoning", "config", "profile"})

# Read-only for these first words (or none); any other subcommand changes something.
READ_SUBS = {
    "skills": {"", "list", "search", "inspect", "browse", "show", "info"},
    "cron": {"", "list", "status", "show"},
    "kanban": {"", "list", "show", "status", "board", "ls"},
    "tools": {"", "list", "show"},
    "toolsets": {"", "list", "show"},
    "memory": {"", "list", "show", "search", "status"},
    "plugins": {"", "list", "show"},
    "mcp": {"", "list", "status"},
}


class CommandError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def split(text: object) -> tuple[str, str]:
    """`/name arg…` → (name, arg)."""
    if not (isinstance(text, str) and text.startswith("/") and 1 < len(text) <= MAX_LINE):
        raise CommandError(m.INVALID_PARAMS, "text must be a command line, /name and its arguments")
    name, _, arg = text[1:].strip().partition(" ")
    if not name:
        raise CommandError(m.INVALID_PARAMS, "text must start with /name")
    return name.lower(), arg.strip()


def needs_approval(name: str, arg: str) -> bool:
    if name in APPROVE:
        return True
    if name in ARGS_APPROVE:
        return bool(arg)
    if name in READ_SUBS:
        return arg.split(" ", 1)[0].lower() not in READ_SUBS[name]
    return False


@dataclass
class _Catalog:
    at: float
    canon: dict[str, str]  # any name or alias, no slash → canonical name
    commands: list[dict]  # what commands.list shows
    skills: set[str]
    builtin: set[str]


@dataclass
class _Pending:
    note: dict  # the ops.approval.request params
    conversation_id: str
    text: str
    expires_at: float
    task: asyncio.Task | None = field(default=None, repr=False)


class Commands:
    """commands.list / commands.run for the chat service (chat.py), and their approvals (server.py)."""

    def __init__(self, backend: HermesBackend, chat, home_agent: str | None):
        self.backend = backend
        self.chat = chat  # ChatService: conversations, bots, send, broadcast
        self.home_agent = home_agent  # the agent whose own sessions hermes serve can resume (the default profile)
        self._home = BotChatClient(backend, "default")
        self._catalogs: dict[str, _Catalog] = {}
        self.pending: dict[str, _Pending] = {}
        self.clock: Callable[[], float] = time.time

    # which chat

    def _where(self, conversation_id: object):
        """(conversation, profile, session client) for a chat the doorway serves."""
        if not isinstance(conversation_id, str) or not conversation_id:
            raise CommandError(m.INVALID_PARAMS, "conversation_id is required")
        conv = self.chat.store.get(conversation_id)
        if conv is None:
            raise CommandError(m.NOT_FOUND, "Unknown conversation")
        profile = profile_of(conv.agent_id)
        if profile is not None and self.chat.bots is not None:
            return conv, profile, self.chat.bots.client(conv.agent_id)
        if conv.agent_id == self.home_agent:
            return conv, "default", self._home
        raise CommandError(m.METHOD_NOT_FOUND, "Hermes's commands only run in your assistant's and bots' chats")

    async def _catalog(self, profile: str) -> _Catalog:
        known = self._catalogs.get(profile)
        if known is not None and self.clock() - known.at < CATALOG_TTL_S:
            return known
        try:
            raw = await self.backend.rpc("commands.catalog", {"profile": profile})
        except Exception as exc:
            raise CommandError(m.AGENT_UNAVAILABLE, f"Hermes's commands aren't available: {exc}") from None
        if not isinstance(raw, dict):
            raise CommandError(m.AGENT_UNAVAILABLE, "Hermes didn't list its commands")
        canon = {k.lstrip("/").lower(): v.lstrip("/").lower() for k, v in (raw.get("canon") or {}).items()
                 if isinstance(k, str) and isinstance(v, str)}
        skills = {k.lstrip("/").lower() for k in (raw.get("skills") or {}) if isinstance(k, str)}
        category = {}
        for cat in raw.get("categories") or []:
            for pair in (cat.get("pairs") or []) if isinstance(cat, dict) else []:
                if isinstance(pair, list) and pair and isinstance(pair[0], str):
                    category.setdefault(pair[0].lstrip("/").lower(), str(cat.get("name") or ""))
        commands, seen = [], set()
        for pair in raw.get("pairs") or []:
            if not (isinstance(pair, list) and len(pair) == 2 and all(isinstance(x, str) for x in pair)):
                continue
            name = pair[0].lstrip("/").lower()
            main = canon.get(name, name)
            if main in seen or main in TALARIA or main in NEVER or name in TALARIA or name in NEVER:
                continue
            seen.add(main)
            commands.append({"name": main, "about": pair[1][:300],
                             "category": "Skills" if main in skills else category.get(main, "Other")[:60],
                             "approve": needs_approval(main, "") or main in APPROVE})
        builtin = set(canon) | set(category) | {c["name"] for c in commands}
        cat = _Catalog(self.clock(), canon, commands, skills, builtin)
        self._catalogs[profile] = cat
        return cat

    # commands.list / commands.run

    async def handle(self, method: str, p: dict, requested_by: str = "device"):
        """(result, turn or None), like ChatService.handle."""
        if method == "commands.list":
            _, profile, _ = self._where(p.get("conversation_id"))
            if not self.backend.connected:
                return {"commands": [], "available": False}, None
            return {"commands": (await self._catalog(profile)).commands, "available": True}, None
        return await self.run(p.get("conversation_id"), p.get("text"), requested_by)

    async def run(self, conversation_id: object, text: object, requested_by: str):
        conv, profile, client = self._where(conversation_id)
        name, arg = split(text)
        line = f"/{name} {arg}".strip()
        if not self.backend.connected:
            raise CommandError(m.AGENT_UNAVAILABLE, "Hermes's backend isn't reachable right now")
        cat = await self._catalog(profile)
        main = cat.canon.get(name, name)
        if main in NEVER or name in NEVER:
            raise CommandError(m.INVALID_PARAMS, f"/{name} only works in Hermes on a computer")
        if main not in cat.builtin and main not in cat.skills:
            raise CommandError(m.NOT_FOUND, f"Hermes doesn't know /{name}. Type / to see the commands.")
        if needs_approval(main, arg):
            return await self._ask(conv, line, requested_by), None
        return await self._exec(conv, profile, client, main, arg, line)

    async def _exec(self, conv, profile: str, client: BotChatClient, name: str, arg: str, line: str):
        try:
            live = await client.live(conv.hermes_session_id)
            try:
                out = await self.backend.rpc("slash.exec", {"session_id": live, "command": f"/{name} {arg}".strip(),
                                                            "profile": profile}, timeout=120)
            except Exception as exc:
                if getattr(exc, "code", None) != 4018:
                    raise
                out = await self.backend.rpc("command.dispatch", {"name": name, "arg": arg, "session_id": live,
                                                                  "profile": profile}, timeout=120)
        except CommandError:
            raise
        except (HermesError, HermesUnavailable) as exc:
            raise CommandError(m.AGENT_UNAVAILABLE, f"Hermes couldn't run {line}: {exc}") from None
        except Exception as exc:
            if getattr(exc, "code", None) == 4018:
                raise CommandError(m.NOT_FOUND, f"Hermes doesn't run {line} here") from None
            raise CommandError(m.AGENT_UNAVAILABLE, f"Hermes couldn't run {line}: {exc}") from None
        return await self._answer(conv, profile, client, line, out if isinstance(out, dict) else {}, hops=0)

    async def _answer(self, conv, profile, client, line: str, out: dict, hops: int):
        kind = out.get("type")
        notice = out.get("notice") if isinstance(out.get("notice"), str) else ""
        if kind == "alias" and isinstance(out.get("target"), str) and hops < 3:
            name, arg = split(out["target"] if out["target"].startswith("/") else "/" + out["target"])
            return await self._exec(conv, profile, client, name, arg, line)
        if kind in ("send", "skill") and isinstance(out.get("message"), str) and out["message"].strip():
            result, turn = await self.chat.send({"conversation_id": conv.id, "text": line}, order=out["message"])
            return {"status": "sent", "command": line, "turn": result,
                    **({"output": notice} if notice else {})}, turn
        if kind == "prefill" and isinstance(out.get("message"), str):
            return {"status": "prefill", "command": line, "text": out["message"],
                    **({"output": notice} if notice else {})}, None
        output = out.get("output") if isinstance(out.get("output"), str) else notice
        return {"status": "done", "command": line, "output": output or "(no output)"}, None

    # approvals (§18.3, shown as server-operation cards)

    async def _ask(self, conv, line: str, requested_by: str) -> dict:
        self._expire()
        request_id = "hc-" + secrets.token_hex(8)
        expires = self.clock() + APPROVAL_TTL_S
        params_json = json.dumps({"command": line, "conversation_id": conv.id}, sort_keys=True, separators=(",", ":"))
        note = {"request_id": request_id, "op": OP, "params_json": params_json, "tier": 1,
                "summary": f"Run {line[:200]} in “{conv.title[:60]}”", "expires_at": int(expires),
                "requested_by": requested_by}
        self.pending[request_id] = _Pending(note, conv.id, line, expires)
        await self._send(m.notification("ops.approval.request", note))
        return {"status": "pending", "command": line, "request_id": request_id}

    async def _send(self, msg: dict) -> None:
        if self.chat.broadcast is not None:
            await self.chat.broadcast(msg)

    def pending_notifications(self) -> list[dict]:
        self._expire()
        return [m.notification("ops.approval.request", p.note) for p in self.pending.values() if p.task is None]

    def _expire(self) -> None:
        for rid in [r for r, p in self.pending.items() if p.task is None and p.expires_at <= self.clock()]:
            del self.pending[rid]

    async def approve(self, device_id: str, public_key_b64: str, p: dict) -> dict:
        """ops.approve for an hc- request: check the device's signature here, then run the command."""
        request_id, choice, sig = p.get("request_id"), p.get("choice"), p.get("sig")
        if not isinstance(request_id, str) or choice not in ("once", "deny") or not isinstance(sig, str):
            raise CommandError(m.INVALID_PARAMS, "request_id, choice (once or deny) and sig are required")
        self._expire()
        pending = self.pending.get(request_id)
        if pending is None or pending.task is not None:
            raise CommandError(m.CONFLICT, "Nothing is waiting for this approval")
        data = m.ops_approve_signed_data(request_id, device_id, OP, pending.note["params_json"], choice)
        if not keys.verify(keys.load_public_key(public_key_b64), data, sig):
            raise CommandError(m.INVALID_PARAMS, "signature does not verify")
        if choice == "deny":
            del self.pending[request_id]
            await self._send(m.notification("ops.approval.done", {"request_id": request_id, "choice": "deny"}))
            return {"request_id": request_id, "choice": choice}
        pending.task = asyncio.ensure_future(self._approved(request_id, pending, device_id))
        await self._send(m.notification("ops.approval.done", {"request_id": request_id, "choice": "once"}))
        return {"request_id": request_id, "choice": choice}

    async def _approved(self, request_id: str, pending: _Pending, device_id: str) -> None:
        ok, output, turn = False, "", None
        try:
            conv, profile, client = self._where(pending.conversation_id)
            name, arg = split(pending.text)
            result, turn = await self._exec(conv, profile, client, (await self._catalog(profile)).canon.get(name, name),
                                            arg, pending.text)
            ok = True
            output = result.get("output") or result.get("text") or (
                "Sent to the chat" if result["status"] == "sent" else "")
        except CommandError as exc:
            output = exc.message
        except Exception as exc:  # keep the card honest whatever happened
            log.exception("approved command %s failed", pending.text)
            output = str(exc)
        finally:
            self.pending.pop(request_id, None)
        await self._send(m.notification("ops.result", {
            "request_id": request_id, "requested_by": pending.note["requested_by"], "approved_by": device_id,
            "result": {"op": OP, "ok": ok, "exit_code": None, "summary": pending.text[:200], "output": output,
                       "finished_at": int(self.clock())}}))
        if turn is not None:
            self.chat.start(turn)
