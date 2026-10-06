"""Automations, calendar and Home (spec/README.md §14).

For Hermes an automation is one of its scheduled (cron) jobs. The bridge keeps what Talaria
knows about each job it made (the structured `when`, the task in the user's words, where
the result goes) in chat.db, keyed by the job id, and polls Hermes for the jobs and their
runs. Jobs made anywhere else are listed as `made_in: agent`, `when.kind: other`.

`arrives` and `after_event` have no Hermes trigger, so they become a job every 10 minutes
in a window whose prompt checks first and answers [SILENT] when there is nothing to do yet,
and leaves a marker file so it does the task once a day at most.
"""

from __future__ import annotations

import asyncio
import contextlib
import datetime as dt
import json
import logging
import re
import secrets
import sqlite3
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from pathlib import Path

from .hermes import HermesClient, HermesError, HermesJobs, HermesUnavailable
from .hermes_check import CHECK_ID, CHECK_NAME
from .protocol import messages as m

log = logging.getLogger("talaria.automations")

POLL_S = 60
MAX_NAME = 200
MAX_TASK = 4000
MAX_TASK_OUT = 5000
MAX_RUN_TEXT = 20000
RUNS_KEPT = 50
CALENDAR_TIMEOUT_S = 30
SILENT = "[SILENT]"
MAX_BLOCKED = 500
# Hermes's refusal when a cron job hits an approval (tools/approval.py, approvals.cron_mode: deny)
BLOCKED_IN_CRON = re.compile(r"BLOCKED: (.{1,600}?) but cron jobs run without a user present to approve it")
FLAGGED = re.compile(r"^Command flagged as dangerous \((.+)\)$")

DAYS = ("mon", "tue", "wed", "thu", "fri", "sat", "sun")
CRON_DOW = {"mon": 1, "tue": 2, "wed": 3, "thu": 4, "fri": 5, "sat": 6, "sun": 0}
DAY_NAMES = {"mon": "Mon", "tue": "Tue", "wed": "Wed", "thu": "Thu", "fri": "Fri", "sat": "Sat", "sun": "Sun"}
RESULT_TO = ("home", "chat", "log")
HHMM = re.compile(r"^([01][0-9]|2[0-3]):[0-5][0-9]$")
DISMISSED_KEPT_S = 30 * 86400  # archived runs can be read and restored for a month
MAX_ARCHIVED = 100
JOB_ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")

SCHEMA = """
CREATE TABLE IF NOT EXISTS automations (
    job_id TEXT PRIMARY KEY,
    agent_id TEXT NOT NULL,
    when_json TEXT,
    task TEXT,
    result_to TEXT NOT NULL,
    made_in TEXT NOT NULL,
    marker TEXT
);
CREATE TABLE IF NOT EXISTS automation_runs (
    job_id TEXT NOT NULL,
    at INTEGER NOT NULL,
    status TEXT NOT NULL,
    text TEXT,
    error TEXT,
    conversation_id TEXT,
    blocked TEXT,
    PRIMARY KEY (job_id, at)
);
CREATE TABLE IF NOT EXISTS automation_seen (
    job_id TEXT PRIMARY KEY,
    last_run_at INTEGER NOT NULL,
    status TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS home_dismissed (
    job_id TEXT NOT NULL,
    run_at INTEGER NOT NULL,
    PRIMARY KEY (job_id, run_at)
);
CREATE TABLE IF NOT EXISTS home_read (
    job_id TEXT NOT NULL,
    run_at INTEGER NOT NULL,
    PRIMARY KEY (job_id, run_at)
);
"""

RESULT_NOTES = {
    "home": "Your answer is shown as it is on the owner's Home screen, on their phone and laptop: keep it short and"
            " plain, with no preamble.",
    "chat": "Your answer opens as a new chat for the owner.",
    "log": "",
}

ARRIVES_PROMPT = """This is a Talaria automation, "{name}". It checks every 10 minutes, between {start} and {until} on {days}, whether something has arrived, and does its task once a day at most.

1. Look at the current local date and time. If it is before {start}, more than 10 minutes after {until}, or not one of these days: {days}, answer exactly {silent} and stop.
2. If the file {marker}-<today's date as YYYY-MM-DD> exists, the task is already done today: answer exactly {silent} and stop.
3. Check whether this has arrived today: {watch}
4. If it has, do the task below, then create that file (an empty file is fine; make the folder if needed), and answer with the result.
5. If it hasn't, {otherwise}

Task: {task}
{note}"""

AFTER_EVENT_PROMPT = """This is a Talaria automation, "{name}". It checks every 10 minutes on {days} whether a calendar event has ended, and does its task once a day at most.

1. Look at the current local date and time. If today is not one of these days: {days}, answer exactly {silent} and stop.
2. If the file {marker}-<today's date as YYYY-MM-DD> exists, the task is already done today: answer exactly {silent} and stop.
3. Check today's calendar for an event whose title contains "{event}". If there is none, or it hasn't ended yet, or fewer than {delay} minutes have passed since it ended, answer exactly {silent} and stop.
4. Otherwise do the task below, then create that file (an empty file is fine; make the folder if needed), and answer with the result.

Task: {task}
{note}"""

DESCRIBE_PROMPT = """The owner wants an automation, a scheduled job you run on your own. Create it now with your scheduling (cron) tool, delivering its results locally (deliver: local).

What they asked for: {text}

Give the job a short name. If the request is unclear or can't be scheduled, don't create anything and ask one short question instead. Reply in one or two sentences: what you set up and when it runs, or your question."""


RUN_IN_CHAT_PROMPT = """Run my automation "{name}" now, here in our chat, so I can approve anything it needs.

{task}"""

RUN_IN_CHAT_LAST = """

Its last run ({when}) {what}"""


class AutomationError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def _invalid(message: str) -> AutomationError:
    return AutomationError(m.INVALID_PARAMS, message)


def _str(value, name: str, limit: int, *, empty: bool = False) -> str:
    if not isinstance(value, str) or len(value) > limit or (not empty and not value.strip()):
        raise _invalid(f"{name} must be 1 to {limit} characters")
    return value.strip()


def _days(value) -> list[str]:
    if not (isinstance(value, list) and value and all(d in DAYS for d in value) and len(set(value)) == len(value)):
        raise _invalid("days must list some of mon, tue, wed, thu, fri, sat, sun")
    return [d for d in DAYS if d in value]


def check_when(when) -> dict:
    """A valid `when`, normalised. `other` is only for jobs made outside Talaria."""
    if not isinstance(when, dict):
        raise _invalid("when must be an object")
    kind = when.get("kind")
    if kind == "time":
        return {"kind": "time", "schedule": _str(when.get("schedule"), "schedule", 100)}
    if kind == "arrives":
        start, until = when.get("from"), when.get("until")
        if not (isinstance(start, str) and HHMM.match(start) and isinstance(until, str) and HHMM.match(until)):
            raise _invalid("from and until must be times, HH:MM")
        if until <= start:
            raise _invalid("until must be after from")
        out = {"kind": "arrives", "watch": _str(when.get("watch"), "watch", 500), "from": start, "until": until,
               "days": _days(when.get("days"))}
        if when.get("fallback") not in (None, ""):
            out["fallback"] = _str(when["fallback"], "fallback", 2000)
        return out
    if kind == "after_event":
        delay = when.get("delay_minutes")
        if not (isinstance(delay, int) and not isinstance(delay, bool) and 0 <= delay <= 240):
            raise _invalid("delay_minutes must be 0 to 240")
        return {"kind": "after_event", "event": _str(when.get("event"), "event", 200), "delay_minutes": delay,
                "days": _days(when.get("days"))}
    raise _invalid("when.kind must be time, arrives or after_event")


def _cron_days(days: list[str]) -> str:
    return "*" if len(days) == 7 else ",".join(str(CRON_DOW[d]) for d in days)


def days_text(days: list[str]) -> str:
    if len(days) == 7:
        return "every day"
    if days == ["mon", "tue", "wed", "thu", "fri"]:
        return "weekdays"
    if days == ["sat", "sun"]:
        return "weekends"
    return ", ".join(DAY_NAMES[d] for d in days)


def when_text(when: dict) -> str:
    """How an automation's timing reads in the app, such as "Weekdays 10:30–13:00, when … arrives"."""
    if when["kind"] == "arrives":
        return f"{days_text(when['days']).capitalize()} {when['from']}–{when['until']}, when it arrives: {when['watch']}"
    if when["kind"] == "after_event":
        after = f"{when['delay_minutes']} min after" if when["delay_minutes"] else "When"
        return f"{days_text(when['days']).capitalize()}, {after} “{when['event']}” ends"
    return when.get("schedule", "")


def job_spec(name: str, when: dict, task: str, result_to: str, marker: str) -> tuple[str, str]:
    """The Hermes job's schedule and prompt for an automation made in Talaria."""
    note = RESULT_NOTES[result_to]
    if when["kind"] == "time":
        return when["schedule"], f"{task}\n\n{note}".strip()
    path = f"~/.talaria/automations/{marker}"
    if when["kind"] == "arrives":
        start_h, until_h = int(when["from"][:2]), int(when["until"][:2])
        if when.get("fallback"):
            otherwise = (f"and the time is {when['until']} or later, do this instead: {when['fallback']} Then create"
                         f" that file and answer with the result. If it is earlier than {when['until']}, answer"
                         f" exactly {SILENT}.")
        else:
            otherwise = f"answer exactly {SILENT}."
        prompt = ARRIVES_PROMPT.format(name=name, start=when["from"], until=when["until"],
                                       days=days_text(when["days"]), silent=SILENT, marker=path,
                                       watch=when["watch"], otherwise=otherwise, task=task, note=note)
        return f"*/10 {start_h}-{until_h} * * {_cron_days(when['days'])}", prompt.strip()
    prompt = AFTER_EVENT_PROMPT.format(name=name, days=days_text(when["days"]), silent=SILENT, marker=path,
                                       event=when["event"], delay=when["delay_minutes"], task=task, note=note)
    return f"*/10 6-23 * * {_cron_days(when['days'])}", prompt.strip()


def _ts(value) -> int | None:
    """Unix seconds from a number or an ISO 8601 time."""
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        return int(value / 1000) if value > 10**11 else int(value)
    if isinstance(value, str) and value:
        try:
            parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return None
        if parsed.tzinfo is None:
            parsed = parsed.astimezone()
        return int(parsed.timestamp())
    return None


def _schedule_display(job: dict) -> str:
    sched = job.get("schedule")
    for value in (job.get("schedule_display"), sched.get("display") if isinstance(sched, dict) else None,
                  sched.get("expr") if isinstance(sched, dict) else sched):
        if isinstance(value, str) and value:
            return value
    return ""


def _fingerprint(jobs: list[dict]) -> list:
    keys = ("id", "name", "state", "enabled", "next_run_at", "last_run_at", "last_status", "schedule_display")
    return sorted(json.dumps([j.get(k) for k in keys], default=str) for j in jobs)


@dataclass
class Meta:
    job_id: str
    agent_id: str
    when: dict | None
    task: str | None
    result_to: str
    made_in: str
    marker: str | None


class AutomationStore:
    def __init__(self, path: Path | str):
        self.db = sqlite3.connect(str(path), isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        if "blocked" not in {r["name"] for r in self.db.execute("PRAGMA table_info(automation_runs)")}:
            self.db.execute("ALTER TABLE automation_runs ADD COLUMN blocked TEXT")  # a chat.db from before blocked runs

    def close(self) -> None:
        self.db.close()

    def meta(self, job_id: str) -> Meta | None:
        r = self.db.execute("SELECT * FROM automations WHERE job_id = ?", (job_id,)).fetchone()
        if r is None:
            return None
        return Meta(r["job_id"], r["agent_id"], json.loads(r["when_json"]) if r["when_json"] else None, r["task"],
                    r["result_to"], r["made_in"], r["marker"])

    def all_meta(self) -> list[Meta]:
        return [m_ for m_ in (self.meta(r["job_id"]) for r in self.db.execute("SELECT job_id FROM automations"))
                if m_ is not None]

    def save(self, meta: Meta) -> None:
        self.db.execute(
            "INSERT OR REPLACE INTO automations (job_id, agent_id, when_json, task, result_to, made_in, marker)"
            " VALUES (?, ?, ?, ?, ?, ?, ?)",
            (meta.job_id, meta.agent_id, json.dumps(meta.when) if meta.when else None, meta.task, meta.result_to,
             meta.made_in, meta.marker))

    def forget(self, job_id: str) -> None:
        for table in ("automations", "automation_runs", "automation_seen"):
            self.db.execute(f"DELETE FROM {table} WHERE job_id = ?", (job_id,))

    def seen(self, job_id: str) -> tuple[int, str] | None:
        """The last run the bridge looked at, and how it went."""
        r = self.db.execute("SELECT last_run_at, status FROM automation_seen WHERE job_id = ?", (job_id,)).fetchone()
        return (r["last_run_at"], r["status"]) if r else None

    def mark_seen(self, job_id: str, at: int, status: str) -> None:
        self.db.execute("INSERT OR REPLACE INTO automation_seen (job_id, last_run_at, status) VALUES (?, ?, ?)",
                        (job_id, at, status))

    def add_run(self, job_id: str, run: dict) -> None:
        self.db.execute(
            "INSERT OR REPLACE INTO automation_runs (job_id, at, status, text, error, conversation_id, blocked)"
            " VALUES (?, ?, ?, ?, ?, ?, ?)",
            (job_id, run["at"], run["status"], run.get("text"), run.get("error"), run.get("conversation_id"),
             run.get("blocked")))
        self.db.execute("DELETE FROM automation_runs WHERE job_id = ? AND at NOT IN (SELECT at FROM automation_runs"
                        " WHERE job_id = ? ORDER BY at DESC LIMIT ?)", (job_id, job_id, RUNS_KEPT))

    def runs(self, job_id: str, limit: int) -> list[dict]:
        rows = self.db.execute("SELECT * FROM automation_runs WHERE job_id = ? ORDER BY at DESC LIMIT ?",
                               (job_id, limit)).fetchall()
        return [_run(r) for r in rows]

    def runs_since(self, at: int) -> list[tuple[str, dict]]:
        rows = self.db.execute("SELECT * FROM automation_runs WHERE at >= ? ORDER BY at DESC", (at,)).fetchall()
        return [(r["job_id"], _run(r)) for r in rows]

    def is_dismissed(self, job_id: str, run_at: int) -> bool:
        r = self.db.execute("SELECT 1 FROM home_dismissed WHERE job_id = ? AND run_at = ?", (job_id, run_at)).fetchone()
        return r is not None

    def dismiss(self, job_id: str, run_at: int) -> None:
        self.db.execute("INSERT OR REPLACE INTO home_dismissed (job_id, run_at) VALUES (?, ?)", (job_id, run_at))
        self._forget_old()

    def restore(self, job_id: str, run_at: int) -> None:
        self.db.execute("DELETE FROM home_dismissed WHERE job_id = ? AND run_at = ?", (job_id, run_at))

    def is_read(self, job_id: str, run_at: int) -> bool:
        r = self.db.execute("SELECT 1 FROM home_read WHERE job_id = ? AND run_at = ?", (job_id, run_at)).fetchone()
        return r is not None

    def set_read(self, job_id: str, run_at: int, read: bool) -> None:
        if read:
            self.db.execute("INSERT OR REPLACE INTO home_read (job_id, run_at) VALUES (?, ?)", (job_id, run_at))
        else:
            self.db.execute("DELETE FROM home_read WHERE job_id = ? AND run_at = ?", (job_id, run_at))
        self._forget_old()

    def archived(self) -> list[tuple[str, dict]]:
        """Archived runs of the last month that are still in the run log, newest first."""
        rows = self.db.execute(
            "SELECT r.* FROM automation_runs r JOIN home_dismissed d ON d.job_id = r.job_id AND d.run_at = r.at"
            " WHERE r.at >= ? ORDER BY r.at DESC LIMIT ?", (int(time.time()) - DISMISSED_KEPT_S, MAX_ARCHIVED)).fetchall()
        return [(r["job_id"], _run(r)) for r in rows]

    def _forget_old(self) -> None:
        old = int(time.time()) - DISMISSED_KEPT_S
        self.db.execute("DELETE FROM home_dismissed WHERE run_at < ?", (old,))
        self.db.execute("DELETE FROM home_read WHERE run_at < ?", (old,))


def _run(r: sqlite3.Row) -> dict:
    run = {"at": r["at"], "status": r["status"]}
    for key in ("text", "error", "blocked", "conversation_id"):
        if r[key]:
            run[key] = r[key]
    return run


def blocked_reason(rows: list[dict]) -> str | None:
    """What Hermes refused to do in a cron run because nobody was there to approve it, if anything."""
    for row in rows:
        content = row.get("content")
        text = content if isinstance(content, str) else json.dumps(content, ensure_ascii=False)
        found = BLOCKED_IN_CRON.search(text.replace('\\"', '"'))
        if found:
            subject = found.group(1).strip()
            flagged = FLAGGED.match(subject)
            return (flagged.group(1) if flagged else subject)[:MAX_BLOCKED]
    return None


def calendar_event(e: dict) -> dict | None:
    """One event from the Google Workspace skill's `calendar list`, as calendar.day lists it."""
    if not isinstance(e, dict) or e.get("status") == "cancelled":
        return None

    def when(value) -> str | None:
        if isinstance(value, dict):
            value = value.get("dateTime") or value.get("date")
        return value if isinstance(value, str) and value else None

    start, end = when(e.get("start")), when(e.get("end"))
    if start is None:
        return None
    event = {"title": str(e.get("summary") or e.get("title") or "(No title)"), "start": start, "end": end or start,
             "all_day": len(start) == 10}
    if isinstance(e.get("location"), str) and e["location"]:
        event["location"] = e["location"]
    return event


Notify = Callable[[dict], Awaitable[None]]
OnChat = Callable[[str, str, str, int, str], str]  # agent_id, session id, title, at, text -> conversation id


class Automations:
    def __init__(self, clients: dict[str, HermesClient], store: AutomationStore, *,
                 default_agent: str | None = None, calendars: dict[str, tuple[str, ...]] | None = None,
                 on_chat: OnChat | None = None):
        self.clients = clients
        self.jobs = {agent_id: HermesJobs(c) for agent_id, c in clients.items()}
        self.store = store
        self.default_agent = default_agent or next(iter(clients), None)
        self.calendars = calendars or {}
        self.on_chat = on_chat
        self.notify: Notify | None = None  # set by the chat service: its broadcast
        self._jobs: dict[str, list[dict]] = {}
        self._started = int(time.time())
        self._lock = asyncio.Lock()

    # the list

    def _agent(self, p: dict) -> str:
        agent_id = p.get("agent_id", self.default_agent)
        if agent_id not in self.jobs:
            raise AutomationError(m.NOT_FOUND, "Unknown agent")
        return agent_id

    def _cached(self, job_id: str) -> tuple[str, dict] | None:
        for agent_id, jobs in self._jobs.items():
            for job in jobs:
                if job.get("id") == job_id:
                    return agent_id, job
        return None

    async def _find(self, job_id) -> tuple[str, dict]:
        if not (isinstance(job_id, str) and JOB_ID.match(job_id)):
            raise _invalid("id must be an automation id")
        found = self._cached(job_id)
        if found is None:  # made since the last look, or the bridge just started
            for agent_id in self.jobs:
                await self._refresh(agent_id)
            found = self._cached(job_id)
        if found is None:
            raise AutomationError(m.NOT_FOUND, "Unknown automation")
        return found

    def automation(self, agent_id: str, job: dict) -> dict:
        job_id = str(job.get("id"))
        meta = self.store.meta(job_id)
        talaria = meta is not None and meta.made_in == "talaria" and meta.when is not None
        when = meta.when if talaria else {"kind": "other"}
        prompt = job.get("prompt") if isinstance(job.get("prompt"), str) else ""
        state = job.get("state") if job.get("state") in ("scheduled", "paused", "running", "completed", "error") \
            else "scheduled"
        if job.get("enabled") is False and state == "scheduled":
            state = "paused"
        out = {
            "id": job_id,
            "name": str(job.get("name") or "Untitled")[:MAX_NAME],
            "when": when,
            "task": (meta.task if talaria and meta.task else prompt)[:MAX_TASK_OUT],
            "result_to": meta.result_to if meta else "log",
            "made_in": "talaria" if talaria else "agent",
            "state": state,
            "schedule_text": when_text(when) if talaria and when["kind"] != "time" else
            (_schedule_display(job) or when.get("schedule", "")),
        }
        for key in ("next_run_at", "last_run_at"):
            at = _ts(job.get(key))
            if at is not None:
                out[key] = at
        seen = self.store.seen(job_id)
        if seen is not None and seen[0] == out.get("last_run_at"):
            out["last_status"] = seen[1]
        elif job.get("last_status") in ("ok", "error"):
            out["last_status"] = job["last_status"]
        if isinstance(job.get("last_error"), str) and job["last_error"] and out.get("last_status") == "error":
            out["last_error"] = job["last_error"]
        return out

    def list_all(self) -> list[dict]:
        out = [self.automation(a, j) for a, jobs in self._jobs.items() for j in jobs]
        return sorted(out, key=lambda a: (a["state"] == "paused", a.get("next_run_at") or 2**40, a["name"].lower()))

    async def _refresh(self, agent_id: str) -> list[dict]:
        try:
            jobs = await self.jobs[agent_id].list()
        except HermesError as exc:
            raise AutomationError(m.AGENT_UNAVAILABLE, f"Agent error: {exc.message}") from None
        except HermesUnavailable as exc:
            raise AutomationError(m.AGENT_UNAVAILABLE, f"Agent unavailable: {exc}") from None
        self._jobs[agent_id] = [j for j in jobs if isinstance(j.get("id"), str)]
        return self._jobs[agent_id]

    async def _changed(self) -> None:
        if self.notify is not None:
            await self.notify(m.notification("automations.changed", {"automations": self.list_all()}))

    async def _call(self, coro):
        try:
            return await coro
        except HermesError as exc:
            code = m.INVALID_PARAMS if 400 <= exc.status < 500 and exc.status != 404 else \
                m.NOT_FOUND if exc.status == 404 else m.AGENT_UNAVAILABLE
            raise AutomationError(code, f"Agent error: {exc.message}") from None
        except HermesUnavailable as exc:
            raise AutomationError(m.AGENT_UNAVAILABLE, f"Agent unavailable: {exc}") from None

    # requests

    async def list(self, p: dict) -> dict:
        agent_id = p.get("agent_id")
        for a in ([self._agent(p)] if agent_id is not None else list(self.jobs)):
            await self._refresh(a)
        return {"automations": [x for x in self.list_all() if agent_id is None or self._agent_of(x["id"]) == agent_id]}

    def _agent_of(self, job_id: str) -> str | None:
        for agent_id, jobs in self._jobs.items():
            if any(j.get("id") == job_id for j in jobs):
                return agent_id
        return None

    async def add(self, p: dict) -> dict:
        agent_id = self._agent(p)
        name = _str(p.get("name"), "name", MAX_NAME)
        when = check_when(p.get("when"))
        task = _str(p.get("task"), "task", MAX_TASK)
        result_to = p.get("result_to", "home")
        if result_to not in RESULT_TO:
            raise _invalid("result_to must be home, chat or log")
        marker = secrets.token_hex(6)
        schedule, prompt = job_spec(name, when, task, result_to, marker)
        job = await self._call(self.jobs[agent_id].create(
            {"name": name, "schedule": schedule, "prompt": prompt, "deliver": "local"}))
        job_id = job.get("id") if isinstance(job, dict) else None
        if not isinstance(job_id, str):
            raise AutomationError(m.AGENT_UNAVAILABLE, "The agent didn't say which job it made")
        self.store.save(Meta(job_id, agent_id, when, task, result_to, "talaria", marker))
        return {"automation": await self._after_change(agent_id, job_id)}

    async def _after_change(self, agent_id: str, job_id: str) -> dict:
        await self._refresh(agent_id)
        await self._changed()
        return self.automation(*await self._find(job_id))

    async def update(self, p: dict) -> dict:
        agent_id, job = await self._find(p.get("id"))
        job_id = job["id"]
        meta = self.store.meta(job_id)
        talaria = meta is not None and meta.made_in == "talaria" and meta.when is not None
        body: dict = {}
        name = _str(p["name"], "name", MAX_NAME) if "name" in p else str(job.get("name") or "Untitled")
        if "name" in p:
            body["name"] = name
        result_to = p.get("result_to", meta.result_to if meta else "log")
        if result_to not in RESULT_TO:
            raise _invalid("result_to must be home, chat or log")
        if "when" in p and not talaria:
            raise AutomationError(m.CONFLICT, "This automation was made outside Talaria; its timing can't change here")
        if talaria:
            when = check_when(p["when"]) if "when" in p else meta.when
            task = _str(p["task"], "task", MAX_TASK) if "task" in p else meta.task
            if {"name", "when", "task", "result_to"} & p.keys():
                body["schedule"], body["prompt"] = job_spec(name, when, task, result_to, meta.marker or job_id)
            meta = Meta(job_id, agent_id, when, task, result_to, "talaria", meta.marker)
        else:
            if "task" in p:
                body["prompt"] = _str(p["task"], "task", MAX_TASK)
            meta = Meta(job_id, agent_id, None, None, result_to, "agent", None)
        if body:
            await self._call(self.jobs[agent_id].update(job_id, body))
        if "paused" in p:
            if not isinstance(p["paused"], bool):
                raise _invalid("paused must be true or false")
            await self._call(self.jobs[agent_id].action(job_id, "pause" if p["paused"] else "resume"))
        self.store.save(meta)
        return {"automation": await self._after_change(agent_id, job_id)}

    async def run(self, p: dict) -> dict:
        agent_id, job = await self._find(p.get("id"))
        await self._call(self.jobs[agent_id].action(job["id"], "run"))
        return {"automation": await self._after_change(agent_id, job["id"])}

    async def chat_task(self, p: dict) -> tuple[str, str, tuple[str, int] | None]:
        """automations.run_in_chat: the agent, the message that runs the automation's task in a chat, and the
        job's latest run when it was blocked (that run leaves Home once the chat turn completes)."""
        agent_id, job = await self._find(p.get("id"))
        item = self.automation(agent_id, job)
        task = item["task"].strip()
        if not task:
            raise AutomationError(m.CONFLICT, "This automation has no task to run in a chat")
        latest = self.store.runs(job["id"], 1)
        blocked = (job["id"], latest[0]["at"]) if latest and latest[0]["status"] == "blocked" else None
        text = RUN_IN_CHAT_PROMPT.format(name=item["name"], task=task)
        if latest:
            text += last_run_note(latest[0])
        return agent_id, text, blocked

    async def delete(self, p: dict) -> dict:
        agent_id, job = await self._find(p.get("id"))
        await self._call(self.jobs[agent_id].delete(job["id"]))
        self.store.forget(job["id"])
        await self._refresh(agent_id)
        await self._changed()
        return {"id": job["id"], "deleted": True}

    def runs(self, p: dict) -> dict:
        job_id = p.get("id")
        if not (isinstance(job_id, str) and JOB_ID.match(job_id)):
            raise _invalid("id must be an automation id")
        limit = p.get("limit", 10)
        if not (isinstance(limit, int) and not isinstance(limit, bool) and 1 <= limit <= 50):
            raise _invalid("limit must be 1 to 50")
        return {"runs": self.store.runs(job_id, limit)}

    async def describe(self, p: dict) -> dict:
        agent_id = self._agent(p)
        text = _str(p.get("text"), "text", MAX_TASK)
        client = self.clients[agent_id]
        session_id = f"talaria_auto_{secrets.token_hex(8)}"
        reply, error = "", None
        try:
            await client.create_session(session_id, None)
            try:
                async for name, payload in client.chat_stream(session_id, DESCRIBE_PROMPT.format(text=text)):
                    if name == "assistant.delta" and isinstance(payload.get("delta"), str):
                        reply += payload["delta"]
                    elif name == "assistant.completed" and isinstance(payload.get("content"), str):
                        reply = payload["content"]
                    elif name == "run.failed":
                        error = str(payload.get("error") or "The agent couldn't set it up")
                    elif name == "done":
                        break
            finally:
                with contextlib.suppress(HermesError, HermesUnavailable):
                    await client.delete_session(session_id)
        except HermesError as exc:
            raise AutomationError(m.AGENT_UNAVAILABLE, f"Agent error: {exc.message}") from None
        except HermesUnavailable as exc:
            raise AutomationError(m.AGENT_UNAVAILABLE, f"Agent unavailable: {exc}") from None
        if error and not reply.strip():
            raise AutomationError(m.AGENT_UNAVAILABLE, error)
        await self._refresh(agent_id)
        await self._changed()
        return {"reply": reply.strip(), "automations": self.list_all()}

    # calendar.day and home.get

    async def calendar_day(self, p: dict) -> dict:
        date = p.get("date")
        if date is None:
            day = dt.date.today()
        else:
            try:
                day = dt.date.fromisoformat(date) if isinstance(date, str) and len(date) == 10 else None
            except ValueError:
                day = None
            if day is None:
                raise _invalid("date must be YYYY-MM-DD")
        agent_id = p.get("agent_id", self.default_agent)
        command = self.calendars.get(agent_id) if isinstance(agent_id, str) else None
        out = {"date": day.isoformat(), "events": []}
        if not command:
            out["error"] = "No calendar is set up on the bridge"
            return out
        proc = None
        try:
            proc = await asyncio.create_subprocess_exec(
                *command, day.isoformat(), stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
            stdout, stderr = await asyncio.wait_for(proc.communicate(), CALENDAR_TIMEOUT_S)
        except (OSError, asyncio.TimeoutError) as exc:
            if proc is not None:
                with contextlib.suppress(ProcessLookupError):
                    proc.kill()
            out["error"] = f"The calendar couldn't be read: {type(exc).__name__}"
            return out
        if proc.returncode != 0:
            log.warning("calendar command failed (%s): %s", proc.returncode, stderr.decode(errors="replace")[-500:])
            out["error"] = "The calendar couldn't be read; see the bridge log"
            return out
        try:
            data = json.loads(stdout)
        except ValueError:
            out["error"] = "The calendar answered something that isn't JSON"
            return out
        items = data.get("events", data.get("items")) if isinstance(data, dict) else data
        events = [e for e in (calendar_event(x) for x in items or []) if e is not None]
        out["events"] = sorted(events, key=lambda e: (not e["all_day"], _ts(e["start"]) or 0))
        return out

    def _names(self) -> dict[str, str]:
        names = {str(j.get("id")): str(j.get("name") or "Untitled") for jobs in self._jobs.values() for j in jobs}
        names[CHECK_ID] = CHECK_NAME  # the bridge's own card (hermes_check.py)
        return names

    def home(self, p: dict) -> dict:
        today = dt.date.today()
        start = int(dt.datetime.combine(today, dt.time()).timestamp())
        home = {meta.job_id for meta in self.store.all_meta() if meta.result_to == "home"} | {CHECK_ID}
        names = self._names()
        results, done = [], set()
        for job_id, run in self.store.runs_since(start):
            if job_id in done or run["status"] == "nothing":
                continue
            if (job_id in home or run["status"] == "blocked") and not self.store.is_dismissed(job_id, run["at"]):
                item = {"id": job_id, "name": names.get(job_id, "Automation")[:MAX_NAME], "run": run}
                if self.store.is_read(job_id, run["at"]):
                    item["read"] = True
                results.append(item)
            done.add(job_id)  # only the latest run counts
        return {"date": today.isoformat(), "results": results}

    def archived(self, p: dict) -> dict:
        """home.archived: what was archived off Home in the last month, to read again or restore."""
        names = self._names()
        return {"results": [{"id": job_id, "name": names.get(job_id, "Automation")[:MAX_NAME], "run": run}
                            for job_id, run in self.store.archived()]}

    # watching Hermes

    async def poll(self) -> None:
        """Every minute: notice changed jobs and finished runs."""
        while True:
            try:
                await self.poll_once()
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 (keep polling whatever one round hit)
                log.exception("automations poll failed")
            await asyncio.sleep(POLL_S)

    async def poll_once(self) -> None:
        async with self._lock:
            changed = False
            for agent_id, jobs_api in self.jobs.items():
                before = self._jobs.get(agent_id)
                try:
                    jobs = await self._refresh(agent_id)
                except AutomationError:
                    continue
                if before is not None and _fingerprint(before) != _fingerprint(jobs):
                    changed = True
                for job in jobs:
                    if await self._check_run(agent_id, jobs_api, job):
                        changed = True
            if changed:
                await self._changed()

    async def _check_run(self, agent_id: str, jobs_api: HermesJobs, job: dict) -> bool:
        job_id, last = job["id"], _ts(job.get("last_run_at"))
        seen = self.store.seen(job_id)
        if last is None or (seen is not None and seen[0] == last):
            return False
        run = {"at": last, "status": "nothing"}
        if job.get("last_status") == "error":
            run["status"] = "error"
            run["error"] = str(job.get("last_error") or "The run failed")[:2000]
        session_id = None
        if run["status"] != "error":
            session_id, text, blocked = await self._run_text(agent_id, jobs_api, job_id)
            if text and SILENT not in text:
                run.update(status="ok", text=text[:MAX_RUN_TEXT])
            if blocked:
                run.update(status="blocked", blocked=blocked)
        self.store.mark_seen(job_id, last, run["status"])
        if run["status"] == "nothing":
            return True  # a check that found nothing to do: last_status only
        meta = self.store.meta(job_id)
        result_to = meta.result_to if meta else "log"
        name = str(job.get("name") or "Untitled")[:MAX_NAME]
        if result_to == "chat" and session_id and run["status"] == "ok" and self.on_chat is not None:
            day = dt.datetime.fromtimestamp(last)
            run["conversation_id"] = self.on_chat(agent_id, session_id, f"{name} · {day.day} {day:%b}", last, run.get("text", ""))
        self.store.add_run(job_id, run)
        again = run["status"] == "blocked" and seen is not None and seen[1] == "blocked"
        if self.notify is not None and last >= self._started - POLL_S and not again:
            await self.notify(m.notification("automations.ran",
                                             {"id": job_id, "name": name, "result_to": result_to, "run": run}))
        return True

    async def _run_text(self, agent_id: str, jobs_api: HermesJobs, job_id: str) -> tuple[str | None, str, str | None]:
        """The latest run's answer, from the session Hermes saved it as, and what it was blocked from doing."""
        from .chat import history_messages  # chat imports this module

        try:
            sessions = await jobs_api.run_sessions(job_id, 1)
            if not sessions:
                return None, "", None
            rows = await self.clients[agent_id].messages(sessions[0], limit=20, offset=0)
        except (HermesError, HermesUnavailable) as exc:
            log.warning("couldn't read the run of %s: %s", job_id, exc)
            return None, "", None
        replies = [x["text"] for x in history_messages(rows) if x["role"] == "assistant"]
        return sessions[0], (replies[-1].strip() if replies else ""), blocked_reason(rows)

    async def dismiss(self, p: dict) -> dict:
        """home.dismiss: archive one run off Home on every device."""
        await self.dismiss_run(*_home_run(p))
        return {}

    async def restore(self, p: dict) -> dict:
        """home.restore: put an archived run back (Home shows it again if it's still today's latest)."""
        self.store.restore(*_home_run(p))
        await self._home_changed()
        return {}

    async def mark_read(self, p: dict) -> dict:
        """home.read: mark one run read (or unread with read: false) on every device."""
        job_id, run_at = _home_run(p)
        read = p.get("read", True)
        if not isinstance(read, bool):
            raise _invalid("read must be true or false")
        self.store.set_read(job_id, run_at, read)
        await self._home_changed()
        return {}

    async def _home_changed(self) -> None:
        if self.notify is not None:
            await self.notify(m.notification("home.changed", self.home({})))

    async def dismiss_run(self, job_id: str, run_at: int) -> None:
        self.store.dismiss(job_id, run_at)
        await self._home_changed()

    async def handle(self, method: str, p: dict) -> dict:
        if method == "automations.list":
            return await self.list(p)
        if method == "automations.add":
            return await self.add(p)
        if method == "automations.describe":
            return await self.describe(p)
        if method == "automations.update":
            return await self.update(p)
        if method == "automations.run":
            return await self.run(p)
        if method == "automations.delete":
            return await self.delete(p)
        if method == "automations.runs":
            return self.runs(p)
        if method == "calendar.day":
            return await self.calendar_day(p)
        if method == "home.get":
            return self.home(p)
        if method == "home.dismiss":
            return await self.dismiss(p)
        if method == "home.restore":
            return await self.restore(p)
        if method == "home.read":
            return await self.mark_read(p)
        if method == "home.archived":
            return self.archived(p)
        raise AutomationError(m.METHOD_NOT_FOUND, f"Method not found: {method}")


AUTOMATION_METHODS = frozenset({"automations.list", "automations.add", "automations.describe", "automations.update",
                                "automations.run", "automations.delete", "automations.runs", "calendar.day",
                                "home.get", "home.dismiss", "home.restore", "home.read", "home.archived"})


def last_run_note(run: dict) -> str:
    """What Hermes is told about the run before, so "run it again" comes with why it's being run again."""
    at = dt.datetime.fromtimestamp(run["at"])
    when = f"{at.day} {at:%b %H:%M}"  # no %-d: Windows doesn't have it
    if run["status"] == "blocked":
        what = f"was blocked: it needed my approval for {run.get('blocked') or 'something'}, and I wasn't there."
    elif run["status"] == "error":
        what = f"failed: {run.get('error') or 'no reason given'}"
    elif run.get("text"):
        what = "answered:\n" + "\n".join("> " + line for line in run["text"].strip()[:MAX_LAST_RUN].splitlines())
    else:
        return ""
    return RUN_IN_CHAT_LAST.format(when=when, what=what)


MAX_LAST_RUN = 4000


def _home_run(p: dict) -> tuple[str, int]:
    """The run a home.* request names: the automation's id and the run's time."""
    job_id, run_at = p.get("id"), p.get("at")
    if not (isinstance(job_id, str) and JOB_ID.match(job_id)):
        raise _invalid("id must be an automation id")
    if not (isinstance(run_at, int) and not isinstance(run_at, bool) and run_at >= 0):
        raise _invalid("at must be a non-negative integer")
    return job_id, run_at
