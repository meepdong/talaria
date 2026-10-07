"""Hermes's Kanban board (§18.4) and what Hermes spent (§18.5), through the doorway (hermes_serve.py).

Both live behind `hermes serve`'s REST addresses, the ones Hermes Desktop uses. A task given to a bot and made
`ready` is picked up by Hermes's own dispatcher; the bridge only reads and changes the board. While a device has the
board open it is checked every few seconds, and devices hear when it changed."""

from __future__ import annotations

import asyncio
import json
import logging
import time
from collections.abc import Awaitable, Callable
from urllib.parse import quote

from .bots import PREFIX, profile_of
from .hermes_serve import HermesBackend, ServeError, ServeUnavailable
from .protocol import messages as m

log = logging.getLogger(__name__)

BOARD_METHODS = frozenset({"board.get", "board.task", "board.add", "board.update", "board.comment", "usage.get"})
KANBAN = "/api/plugins/kanban"
COLUMNS = ("triage", "todo", "scheduled", "ready", "running", "blocked", "review", "done")
STATUSES = frozenset(COLUMNS) | {"archived"}
ASSISTANT = "assistant"  # the owner's own assistant: Hermes's default profile
WATCH_S = 600  # board.get watches the board this long
EVERY_S = 5.0  # how often a watched board is looked at
SLOW_S = 30.0  # and one nobody has open (devices show which bots are at work)
MAX_TITLE, MAX_BODY = 300, 8000


class BoardError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def _assignee_out(profile: object) -> str | None:
    if not isinstance(profile, str) or not profile:
        return None
    return ASSISTANT if profile == "default" else PREFIX + profile


def _assignee_in(who: object) -> str:
    """A bot id, "assistant" or "" (nobody) → Hermes's profile name ("" for nobody)."""
    if who == "":
        return ""
    if who == ASSISTANT:
        return "default"
    profile = profile_of(who) if isinstance(who, str) else None
    if profile is None:
        raise BoardError(m.INVALID_PARAMS, "assignee must be a bot id, \"assistant\" or \"\"")
    return profile


def _text(v: object) -> str | None:
    return v if isinstance(v, str) and v.strip() else None


def _int(v: object) -> int | None:
    return int(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else None


def task_out(t: dict, comments: int | None = None) -> dict:
    """Hermes's task, as devices get it (§18.4)."""
    status = t.get("status") if t.get("status") in STATUSES else "todo"
    return {"id": str(t.get("id")), "title": str(t.get("title") or "")[:MAX_TITLE], "status": status,
            "priority": _int(t.get("priority")) or 0, "created_at": _int(t.get("created_at")) or 0,
            "body": _text(t.get("body")), "assignee": _assignee_out(t.get("assignee")),
            "started_at": _int(t.get("started_at")), "completed_at": _int(t.get("completed_at")),
            "summary": _text(t.get("latest_summary")), "result": _text(t.get("result")),
            "error": _text(t.get("last_failure_error")),
            "comments": comments if comments is not None else (_int(t.get("comment_count")) or 0)}


def columns_out(board: dict) -> list[dict]:
    found = {c.get("name"): c.get("tasks") or [] for c in board.get("columns") or [] if isinstance(c, dict)}
    return [{"name": name, "tasks": [task_out(t) for t in found.get(name, []) if isinstance(t, dict)]}
            for name in COLUMNS]


def _use(row: dict) -> dict:
    actual, estimate = row.get("actual_cost") or 0, row.get("estimated_cost") or 0
    return {"cost_usd": round(float(actual or estimate), 6), "estimated": not actual,
            "input_tokens": int(row.get("input_tokens") or 0), "output_tokens": int(row.get("output_tokens") or 0),
            "sessions": int(row.get("sessions") or 0), "calls": int(row.get("api_calls") or 0)}


def _why(exc: ServeError) -> str:
    """FastAPI's {"detail": …}, or the text."""
    try:
        detail = json.loads(exc.message).get("detail")
    except (ValueError, AttributeError):
        detail = None
    return detail if isinstance(detail, str) and detail else exc.message


class Board:
    def __init__(self, backend: HermesBackend, *, every_s: float = EVERY_S, slow_s: float = SLOW_S,
                 now: Callable[[], float] = time.monotonic):
        self.backend = backend
        self.every_s = every_s
        self.slow_s = slow_s
        self._looked = 0.0
        self.now = now
        self.broadcast: Callable[[dict], Awaitable[None]] | None = None  # set by the chat service
        self._watched_until = 0.0
        self._seen: object = None  # the board's latest event id, last time it was looked at

    async def _rest(self, path: str, method: str = "GET", body: dict | None = None) -> dict:
        try:
            answer = await self.backend.rest(path, method, body)
        except ServeUnavailable:
            raise BoardError(m.AGENT_UNAVAILABLE, "Hermes's backend isn't reachable right now") from None
        except ServeError as exc:
            code = {404: m.NOT_FOUND, 409: m.CONFLICT, 400: m.INVALID_PARAMS, 422: m.INVALID_PARAMS}.get(exc.code)
            raise BoardError(code or m.AGENT_UNAVAILABLE, _why(exc)) from None
        if not isinstance(answer, dict):
            raise BoardError(m.AGENT_UNAVAILABLE, "Hermes answered something unexpected")
        return answer

    async def run(self) -> None:
        """Look at the board (often while a device watches it, now and then otherwise); say when it changed."""
        while True:
            try:
                due = self._watched_until > self.now() or self.now() - self._looked >= self.slow_s
                if self.backend.connected and due:
                    self._looked = self.now()
                    board = await self._rest(f"{KANBAN}/board")
                    seen = board.get("latest_event_id")
                    if self._seen is not None and seen != self._seen and self.broadcast is not None:
                        await self.broadcast(m.notification("board.changed", {"columns": columns_out(board)}))
                    self._seen = seen
            except asyncio.CancelledError:
                raise
            except BoardError as exc:
                log.info("board: %s", exc.message)
            except Exception:  # noqa: BLE001 (keep watching whatever one round hit)
                log.exception("board watch failed")
            await asyncio.sleep(self.every_s)

    async def _changed(self) -> None:
        """After a device's own change: everyone hears it now, not at the next look."""
        board = await self._rest(f"{KANBAN}/board")
        self._seen = board.get("latest_event_id")
        if self.broadcast is not None:
            await self.broadcast(m.notification("board.changed", {"columns": columns_out(board)}))

    async def handle(self, method: str, p: dict) -> dict:
        if method == "usage.get":
            return await self.usage(p.get("days"))
        if method == "board.get":
            if not self.backend.connected:
                return {"columns": [{"name": n, "tasks": []} for n in COLUMNS], "available": False}
            board = await self._rest(f"{KANBAN}/board")
            self._watched_until = self.now() + WATCH_S
            self._seen = board.get("latest_event_id")
            return {"columns": columns_out(board), "available": True}
        if method == "board.add":
            title = _text(p.get("title"))
            if title is None or len(title) > MAX_TITLE:
                raise BoardError(m.INVALID_PARAMS, f"title must be 1 to {MAX_TITLE} characters")
            body = p.get("body")
            if body is not None and not (isinstance(body, str) and len(body) <= MAX_BODY):
                raise BoardError(m.INVALID_PARAMS, f"body must be at most {MAX_BODY} characters")
            assignee = _assignee_in(p["assignee"]) if p.get("assignee") not in (None, "") else None
            triage = p.get("triage") is True
            made = await self._rest(f"{KANBAN}/tasks", "POST", {
                "title": title.strip(), "body": body or None, "assignee": assignee, "triage": triage})
            task = made.get("task")
            if not isinstance(task, dict):
                raise BoardError(m.AGENT_UNAVAILABLE, "Hermes didn't make the task")
            if assignee is None and not triage and task.get("status") == "ready":  # nobody to pick it up yet
                task = (await self._rest(f"{KANBAN}/tasks/{quote(str(task['id']))}", "PATCH",
                                         {"status": "todo"})).get("task") or task
            await self._changed()
            out = {"task": task_out(task, 0)}
            if isinstance(made.get("warning"), str) and made["warning"]:
                out["warning"] = made["warning"]
            return out
        task_id = p.get("task_id")
        if not (isinstance(task_id, str) and 0 < len(task_id) <= 64):
            raise BoardError(m.INVALID_PARAMS, "task_id is required")
        path = f"{KANBAN}/tasks/{quote(task_id, safe='')}"
        if method == "board.task":
            got = await self._rest(path)
            comments = [{"author": str(c.get("author") or ""), "text": str(c.get("body") or ""),
                         "at": _int(c.get("created_at")) or 0} for c in got.get("comments") or [] if isinstance(c, dict)]
            return {"task": task_out(got.get("task") or {}, len(comments)), "comments": comments}
        if method == "board.comment":
            text = _text(p.get("text"))
            if text is None or len(text) > MAX_BODY:
                raise BoardError(m.INVALID_PARAMS, f"text must be 1 to {MAX_BODY} characters")
            await self._rest(f"{path}/comments", "POST", {"body": text, "author": "owner"})
            await self._changed()
            return {}
        if method == "board.update":
            change: dict = {}
            if "status" in p:
                if p["status"] not in STATUSES or p["status"] == "running":
                    raise BoardError(m.INVALID_PARAMS, "status must be a column other than running, or archived")
                change["status"] = p["status"]
            if "assignee" in p:
                change["assignee"] = _assignee_in(p["assignee"])
            for key, most in (("title", MAX_TITLE), ("body", MAX_BODY)):
                if key in p:
                    if not (isinstance(p[key], str) and len(p[key]) <= most and (key == "body" or p[key].strip())):
                        raise BoardError(m.INVALID_PARAMS, f"{key} must be text of at most {most} characters")
                    change[key] = p[key]
            if "priority" in p:
                if _int(p["priority"]) is None or not -100 <= p["priority"] <= 100:
                    raise BoardError(m.INVALID_PARAMS, "priority must be a whole number from -100 to 100")
                change["priority"] = p["priority"]
            if not change:
                raise BoardError(m.INVALID_PARAMS, "Say what to change")
            done = await self._rest(path, "PATCH", change)
            await self._changed()
            return {"task": task_out(done.get("task") or {})}
        raise BoardError(m.METHOD_NOT_FOUND, f"Method not found: {method}")

    async def usage(self, days: object) -> dict:
        if not (isinstance(days, int) and not isinstance(days, bool) and 1 <= days <= 365):
            raise BoardError(m.INVALID_PARAMS, "days must be 1 to 365")
        by_day = (await self._rest(f"/api/analytics/usage?days={days}")).get("daily") or []
        by_model = (await self._rest(f"/api/analytics/models?days={days}")).get("models") or []
        rows = [{"day": str(d.get("day")), **_use(d)} for d in by_day if isinstance(d, dict) and d.get("day")]
        models = sorted(({"model": str(x.get("model")), **_use(x)} for x in by_model if isinstance(x, dict) and x.get("model")),
                        key=lambda x: -x["cost_usd"])
        total = {"cost_usd": round(sum(r["cost_usd"] for r in rows), 6), "estimated": any(r["estimated"] and r["cost_usd"] > 0 for r in rows),
                 **{k: sum(r[k] for r in rows) for k in ("input_tokens", "output_tokens", "sessions", "calls")}}
        return {"days": days, "total": total, "by_day": rows, "by_model": models}
