"""Bots' routines (§18.6) and live helper agents (§18.7), through the doorway (hermes_serve.py).

Routines are Hermes's cron jobs, one store per profile, read and changed through `hermes serve`'s cron addresses (the
ones Hermes Desktop uses). Talaria's own automations (§14) are cron jobs of the default profile too; §14 shows them,
so they are left out here. Helpers are the agents a bot starts during a reply: while a bot's turn runs, the bridge
looks at them every few seconds (`subagent.list` in that chat's session) and tells devices."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable
from datetime import datetime
from urllib.parse import quote

from .board import ASSISTANT, BoardError, _why
from .bots import PREFIX, profile_of
from .hermes_serve import HermesBackend, ServeError, ServeUnavailable
from .protocol import messages as m

log = logging.getLogger(__name__)

ROUTINE_METHODS = frozenset({"routines.list", "routines.add", "routines.set"})
HELPER_METHODS = frozenset({"helpers.steer", "helpers.stop"})
TALARIA_AUTOMATION = "This is a Talaria automation"  # how §14's automations start (automations.py)
ACTIONS = ("pause", "resume", "run", "remove")
MAX_TASK_OUT = 2000
HELPERS_EVERY_S = 3.0


def _epoch(v: object) -> int | None:
    if not isinstance(v, str) or not v:
        return None
    try:
        return int(datetime.fromisoformat(v).timestamp())
    except ValueError:
        return None


def _bot(profile: object) -> str:
    return ASSISTANT if profile in (None, "", "default") else PREFIX + str(profile)


def routine_out(j: dict) -> dict:
    s = j.get("schedule")
    sched = j.get("schedule_display") or (s.get("display") if isinstance(s, dict) else s)
    status = j.get("last_status")
    return {"id": str(j.get("id")), "bot_id": _bot(j.get("profile")), "name": str(j.get("name") or "")[:200],
            "schedule": str(sched or ""), "task": str(j.get("prompt") or "")[:MAX_TASK_OUT],
            "enabled": bool(j.get("enabled", True)), "state": str(j.get("state") or ""),
            "next_run_at": _epoch(j.get("next_run_at")), "last_run_at": _epoch(j.get("last_run_at")),
            "last_status": status if status in ("ok", "error") else None,
            "last_error": j.get("last_error") if isinstance(j.get("last_error"), str) and j["last_error"] else None,
            "to_chat": str(j.get("deliver") or "").startswith("bot-chat")}


class Routines:
    """routines.* for devices; helpers.* and the helpers watch for bots' chats."""

    def __init__(self, backend: HermesBackend, bots, *, helpers_every_s: float = HELPERS_EVERY_S):
        self.backend = backend
        self.bots = bots  # Bots (bots.py): who exists, and each bot's chat client
        self.helpers_every_s = helpers_every_s
        self.broadcast: Callable[[dict], Awaitable[None]] | None = None  # set by the chat service
        self._watching: dict[str, asyncio.Task] = {}  # conversation id -> its helpers watch

    async def _rest(self, path: str, method: str = "GET", body: dict | None = None):
        try:
            return await self.backend.rest(path, method, body)
        except ServeUnavailable:
            raise BoardError(m.AGENT_UNAVAILABLE, "Hermes's backend isn't reachable right now") from None
        except ServeError as exc:
            code = {404: m.NOT_FOUND, 400: m.INVALID_PARAMS, 422: m.INVALID_PARAMS}.get(exc.code)
            raise BoardError(code or m.AGENT_UNAVAILABLE, _why(exc)) from None

    def _profile(self, bot_id: object, *, assistant: bool) -> str:
        if bot_id == ASSISTANT and assistant:
            return "default"
        profile = profile_of(bot_id) if isinstance(bot_id, str) else None
        if profile is None or (self.bots is not None and not self.bots.known(bot_id)):
            raise BoardError(m.NOT_FOUND, "Unknown bot")
        return profile

    async def handle(self, method: str, p: dict) -> dict:
        if method == "routines.list":
            if not self.backend.connected:
                return {"routines": [], "available": False}
            jobs = await self._rest("/api/cron/jobs?profile=all")
            return {"routines": [routine_out(j) for j in jobs if isinstance(j, dict)
                                 and not str(j.get("prompt") or "").startswith(TALARIA_AUTOMATION)],
                    "available": True}
        if method == "routines.add":
            profile = self._profile(p.get("bot_id"), assistant=False)
            fields = {}
            for key, most in (("name", 200), ("schedule", 200), ("task", 8000)):
                v = p.get(key)
                if not (isinstance(v, str) and v.strip() and len(v) <= most):
                    raise BoardError(m.INVALID_PARAMS, f"{key} must be 1 to {most} characters")
                fields[key] = v.strip()
            made = await self._rest(f"/api/cron/jobs?profile={quote(profile)}", "POST", {
                "name": fields["name"], "schedule": fields["schedule"], "prompt": fields["task"],
                "deliver": f"bot-chat:{profile}"})
            job = made.get("job", made) if isinstance(made, dict) else {}
            return {"routine": routine_out({"profile": profile, **job})}
        if method == "routines.set":
            profile = self._profile(p.get("bot_id"), assistant=True)
            rid, action = p.get("routine_id"), p.get("action")
            if not (isinstance(rid, str) and 0 < len(rid) <= 64):
                raise BoardError(m.INVALID_PARAMS, "routine_id is required")
            if action not in ACTIONS:
                raise BoardError(m.INVALID_PARAMS, "action must be pause, resume, run or remove")
            path = f"/api/cron/jobs/{quote(rid, safe='')}"
            q = f"?profile={quote(profile)}"
            if action == "remove":
                await self._rest(path + q, "DELETE")
                return {}
            done = await self._rest(f"{path}/{'trigger' if action == 'run' else action}{q}", "POST")
            job = done.get("job", done) if isinstance(done, dict) else {}
            return {"routine": routine_out({"profile": profile, **job})} if isinstance(job, dict) and job.get("id") else {}
        raise BoardError(m.METHOD_NOT_FOUND, f"Method not found: {method}")

    # helpers (§18.7)

    def watch_helpers(self, conversation_id: str, client, stored: str) -> None:
        """Called when a turn starts in a bot's chat: look at its helpers until the turn ends (stop_helpers)."""
        if conversation_id in self._watching:
            return
        self._watching[conversation_id] = asyncio.ensure_future(self._helpers(conversation_id, client, stored))

    async def stop_helpers(self, conversation_id: str) -> None:
        task = self._watching.pop(conversation_id, None)
        if task is not None:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    async def _helpers(self, conversation_id: str, client, stored: str) -> None:
        last: list | None = None
        try:
            while True:
                await asyncio.sleep(self.helpers_every_s)
                try:
                    live = await client.live(stored)
                    got = await self.backend.rpc("subagent.list", {"session_id": live, "profile": client.profile})
                except Exception as exc:  # noqa: BLE001 (a missed look is no reason to stop looking)
                    log.debug("helpers: %s", exc)
                    continue
                helpers = [helper_out(h) for h in (got or {}).get("subagents") or [] if isinstance(h, dict)]
                if helpers != last:
                    last = helpers
                    await self._send(conversation_id, helpers)
        finally:
            if last:
                await self._send(conversation_id, [])

    async def _send(self, conversation_id: str, helpers: list) -> None:
        if self.broadcast is not None:
            await self.broadcast(m.notification("helpers.update", {"conversation_id": conversation_id, "helpers": helpers}))

    async def helper_call(self, method: str, p: dict, client, stored: str) -> dict:
        hid = p.get("helper_id")
        if not (isinstance(hid, str) and 0 < len(hid) <= 128):
            raise BoardError(m.INVALID_PARAMS, "helper_id is required")
        try:
            live = await client.live(stored)
            if method == "helpers.steer":
                text = p.get("text")
                if not (isinstance(text, str) and text.strip() and len(text) <= 4000):
                    raise BoardError(m.INVALID_PARAMS, "text must be 1 to 4000 characters")
                r = await self.backend.rpc("subagent.steer", {"session_id": live, "subagent_id": hid, "text": text.strip(),
                                                              "profile": client.profile})
                return {"queued": (r or {}).get("status") == "queued"}
            r = await self.backend.rpc("subagent.interrupt", {"session_id": live, "subagent_id": hid, "profile": client.profile})
            return {"stopped": bool((r or {}).get("found"))}
        except BoardError:
            raise
        except ServeUnavailable:
            raise BoardError(m.AGENT_UNAVAILABLE, "Hermes's backend isn't reachable right now") from None
        except Exception as exc:  # noqa: BLE001
            raise BoardError(m.AGENT_UNAVAILABLE, f"Hermes couldn't reach the helper: {exc}") from None


def helper_out(h: dict) -> dict:
    started = h.get("started_at")
    return {"id": str(h.get("subagent_id")), "goal": str(h.get("goal") or "")[:500], "status": str(h.get("status") or ""),
            "tools": int(h.get("tool_count") or 0),
            "last_tool": h.get("last_tool") if isinstance(h.get("last_tool"), str) else None,
            "model": h.get("model") if isinstance(h.get("model"), str) else None,
            "started_at": int(started) if isinstance(started, (int, float)) else None,
            "can_steer": bool(h.get("accepting_steer"))}
