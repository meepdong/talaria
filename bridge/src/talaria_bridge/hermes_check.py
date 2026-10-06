"""Is Hermes still the Hermes the bridge was written against? (spec/README.md §9 "Hermes compatibility")

Hermes updates itself often. This checks every call the bridge makes to Hermes's API server (hermes.py) and
every field it reads from the answers, against a scratch session that it deletes afterwards, so an update that
renames or drops one is caught and named before the owner meets it as a broken chat:

- `talaria doctor --hermes` runs it by hand;
- the bridge runs it by itself (HermesWatch) when Hermes's version changes and once a day, and puts a card on
  Home when something Talaria relies on changed.

Calls that only make sense on a live run or a real job (stop, steer, approval; changing, running or deleting a
job) are checked with an id that doesn't exist: Hermes must answer "not found" in its own words, which shows the
call is still there, and nothing real is touched. Jobs are never created.
"""

from __future__ import annotations

import asyncio
import json
import logging
import secrets
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from pathlib import Path

import httpx

from .protocol import messages as m
from . import __version__ as BRIDGE_VERSION
from .hermes import HermesClient, HermesError, HermesUnavailable

log = logging.getLogger("talaria.hermes_check")

TURN_PROMPT = "This is an automatic check from Talaria. Reply with just: OK"
TURN_TIMEOUT_S = 180
MISSING_RUN = "run_talaria_check_missing"
MISSING_JOB = "000000000000"  # well-formed (12 hex digits), so Hermes looks it up and says it isn't there
JOB_STATES = ("scheduled", "paused", "running", "completed", "error")


@dataclass
class Finding:
    call: str  # e.g. "GET /api/sessions/{id}/messages"
    affects: str  # what in Talaria depends on it, in plain words
    problem: str = ""  # empty when the call is as expected

    @property
    def ok(self) -> bool:
        return not self.problem


@dataclass
class Report:
    version: str | None = None
    findings: list[Finding] = field(default_factory=list)
    turn: bool = False  # whether a real (tiny) chat turn was part of it

    @property
    def ok(self) -> bool:
        return all(f.ok for f in self.findings)

    @property
    def problems(self) -> list[Finding]:
        return [f for f in self.findings if not f.ok]

    def summary(self) -> str:
        """For Home: what changed and what it breaks, one line each."""
        lines = [f"Hermes {self.version or '(unknown version)'} changed something Talaria relies on:"]
        lines += [f"- {f.affects}: {f.call} {f.problem}" for f in self.problems]
        lines.append("Talaria needs a fix for this Hermes version.")
        return "\n".join(lines)


class _Checker:
    def __init__(self, client: HermesClient):
        self.client = client
        self.findings: list[Finding] = []

    def note(self, call: str, affects: str, problem: str = "") -> None:
        self.findings.append(Finding(call, affects, problem))

    async def raw(self, method: str, path: str, **kwargs) -> tuple[int, object]:
        """Status and parsed JSON body (None when the body isn't JSON, which is how Hermes answers an address it
        doesn't have at all: "404: Not Found")."""
        try:
            resp = await self.client._http.request(method, path, **kwargs)
        except httpx.HTTPError as exc:
            raise HermesUnavailable(f"{type(exc).__name__}: {exc}") from exc
        try:
            return resp.status_code, resp.json()
        except ValueError:
            return resp.status_code, None

    async def call(self, method: str, path: str, affects: str, *, shown: str | None = None, **kwargs) -> dict | None:
        """A call that must succeed with a JSON object. None (and a finding) when it doesn't."""
        shown = shown or f"{method} {path}"
        status, body = await self.raw(method, path, **kwargs)
        if status >= 400 or not isinstance(body, dict):
            self.note(shown, affects, _failed(status, body))
            return None
        return body

    async def missing(self, method: str, path: str, affects: str, shown: str, *, codes: tuple[str, ...] = (),
                      phrase: str = "not found", **kwargs) -> None:
        """A call made with an id that doesn't exist: Hermes must say "not found" in its own JSON words."""
        status, body = await self.raw(method, path, **kwargs)
        err = body.get("error") if isinstance(body, dict) else None
        code = err.get("code") if isinstance(err, dict) else None
        text = err.get("message") if isinstance(err, dict) else err
        if status == 404 and (code in codes or (isinstance(text, str) and phrase in text.lower())):
            self.note(shown, affects)
        elif status in (404, 405) and body is None:
            self.note(shown, affects, f"is gone (HTTP {status}: Hermes no longer has this address)")
        else:
            self.note(shown, affects, f"answered unexpectedly for an unknown id ({_failed(status, body)})")


def _failed(status: int, body: object) -> str:
    if body is None:
        return f"is gone (HTTP {status}: Hermes no longer has this address)" if status in (404, 405) \
            else f"failed (HTTP {status}, not JSON)"
    if status >= 400:
        err = body.get("error") if isinstance(body, dict) else None
        msg = err.get("message") if isinstance(err, dict) else err
        return f"failed (HTTP {status}{f': {str(msg)[:200]}' if msg else ''})"
    return "answered something that isn't a JSON object"


def _fields(obj: object, wanted: dict[str, tuple[type, ...]], where: str) -> str:
    """Empty when obj is a dict with each wanted key of an accepted type; otherwise what's wrong."""
    if not isinstance(obj, dict):
        return f"{where} is missing or not an object"
    bad = []
    for key, types in wanted.items():
        value = obj.get(key)
        if key not in obj:
            bad.append(f"'{key}' is missing")
        elif not isinstance(value, types) or (isinstance(value, bool) and bool not in types):
            bad.append(f"'{key}' is {type(value).__name__}, expected {'/'.join(t.__name__ for t in types)}")
    return f"{where}: {', '.join(bad)}" if bad else ""


NUM = (int, float)
STR = (str,)
INT = (int,)


async def check_hermes(client: HermesClient, *, turn: bool = True) -> Report:
    """Run every check against Hermes. `turn` adds one tiny real chat turn (a fraction of a cent), the only way to
    check the reply stream. Raises HermesUnavailable when Hermes can't be reached at all."""
    c = _Checker(client)
    report = Report(turn=turn)

    health = await c.call("GET", "/health", "connection status")
    if health is not None:
        report.version = health.get("version") if isinstance(health.get("version"), str) else None
        if problem := _fields(health, {"status": STR, "version": STR}, "the answer"):
            c.note("GET /health", "connection status", problem)
        else:
            c.note("GET /health", "connection status")

    options = await c.call("GET", "/api/model/options", "choosing models")
    current: tuple[str, str] | None = None
    if options is not None:
        providers = options.get("providers")
        problem = ""
        if not isinstance(providers, list) or not providers:
            problem = "'providers' is missing or empty"
        else:
            problem = next((p for p in (_fields(row, {"slug": STR, "models": (list,)}, "a provider")
                                        for row in providers) if p), "")
        problem = problem or _fields(options, {"provider": STR, "model": STR}, "the answer")
        c.note("GET /api/model/options", "choosing models", problem)
        if isinstance(options.get("provider"), str) and isinstance(options.get("model"), str):
            current = (options["provider"], options["model"])

    sid = "talaria-check-" + secrets.token_hex(6)
    created = await c.call("POST", "/api/sessions", "starting a chat", json={"id": sid, "title": f"Talaria check {sid[-6:]}"})
    if created is None:
        report.findings = c.findings
        return report  # nothing else can be checked without a session
    session = created.get("session")
    if isinstance(session, dict) and session.get("id") not in (None, sid):
        c.note("POST /api/sessions", "starting a chat", f"ignored the session id Talaria chose (gave '{session.get('id')}')")
    else:
        c.note("POST /api/sessions", "starting a chat",
               "" if isinstance(session, dict) else "'session' is missing from the answer")
    try:
        path = f"/api/sessions/{sid}"
        shown = "PATCH /api/sessions/{id}"
        if await c.call("PATCH", path, "renaming chats", shown=shown, json={"title": f"Talaria check {sid[-6:]} renamed"}) is not None:
            c.note(shown + " (title)", "renaming chats")
        if await c.call("PATCH", path, "pinning chats", shown=shown, json={"pinned": True}) is not None:
            c.note(shown + " (pinned)", "pinning chats")
        if current is not None:
            shown = "POST /api/sessions/{id}/model"
            if await c.call("POST", path + "/model", "a chat's own model", shown=shown,
                            json={"provider": current[0], "model": current[1]}) is not None:
                c.note(shown, "a chat's own model")

        if turn:
            await _check_turn(c, client, sid)

        info = await c.call("GET", path, "chat details (messages, tokens, cost)", shown="GET /api/sessions/{id}")
        if info is not None:
            info = info.get("session", info)
            wanted = {"message_count": INT, "tool_call_count": INT, "input_tokens": INT, "output_tokens": INT,
                      "cache_read_tokens": INT, "api_call_count": INT}
            problem = _fields(info, wanted, "the session")
            if not problem and not any(isinstance(info.get(k), NUM) for k in ("actual_cost_usd", "estimated_cost_usd")):
                problem = "the session: no 'actual_cost_usd' or 'estimated_cost_usd'" if turn else ""
            c.note("GET /api/sessions/{id}", "chat details (messages, tokens, cost) and the tidying-up note", problem)

        page = await c.call("GET", path + "/messages", "chat history", shown="GET /api/sessions/{id}/messages",
                            params={"order": "latest", "limit": 10, "offset": 0, "inline_images": "false"})
        if page is not None:
            rows = page.get("data")
            if not isinstance(rows, list):
                problem = "'data' is missing or not a list"
            else:
                problem = next((p for p in (_fields(r, {"id": INT, "role": STR, "timestamp": NUM}, "a message")
                                            for r in rows) if p), "")
                roles = {r.get("role") for r in rows if isinstance(r, dict)}
                if not problem and turn and not {"user", "assistant"} <= roles:
                    problem = f"the turn's messages aren't there (roles seen: {sorted(str(x) for x in roles) or 'none'})"
                if not problem and any("content" not in r for r in rows if isinstance(r, dict)):
                    problem = "a message: 'content' is missing"
            c.note("GET /api/sessions/{id}/messages", "chat history", problem)
    finally:
        await _delete(c, sid)

    for action, body, affects in (("stop", {}, "the Stop button"), ("steer", {"input": "check"}, "notes while Hermes works"),
                                  ("approval", {"choice": "deny"}, "approvals")):
        await c.missing("POST", f"/v1/runs/{MISSING_RUN}/{action}", affects, f"POST /v1/runs/{{id}}/{action}",
                        codes=("run_not_found",), json=body)

    await _check_jobs(c)
    report.findings = c.findings
    return report


async def _check_turn(c: _Checker, client: HermesClient, sid: str) -> None:
    shown, affects = "POST /api/sessions/{id}/chat/stream", "chat and Talk replies"
    seen: dict[str, dict] = {}
    deltas = 0
    stream = client.chat_stream(sid, TURN_PROMPT)
    try:
        async with asyncio.timeout(TURN_TIMEOUT_S):
            async for name, payload in stream:
                seen.setdefault(name, payload)
                if name == "assistant.delta" and isinstance(payload.get("delta"), str):
                    deltas += 1
                if name == "done":
                    break
    except HermesError as exc:
        c.note(shown, affects, f"failed (HTTP {exc.status}: {exc.message[:200]})")
        return
    except TimeoutError:
        c.note(shown, affects, f"no end of the reply within {TURN_TIMEOUT_S} s")
        return
    finally:
        await stream.aclose()
    problems = []
    if p := _fields(seen.get("run.started"), {"run_id": STR}, "event run.started"):
        problems.append(p)
    if not deltas:
        problems.append("no assistant.delta event with a 'delta' text")
    if p := _fields(seen.get("assistant.completed"), {"content": STR}, "event assistant.completed"):
        problems.append(p)
    end = next((n for n in ("run.completed", "run.failed", "run.cancelled") if n in seen), None)
    if end is None:
        problems.append("no run.completed / run.failed / run.cancelled event")
    elif end != "run.completed":
        problems.append(f"the turn ended with {end}: {str(seen[end].get('error') or seen[end].get('turn_exit_reason') or '')[:200]}")
    else:
        done = seen[end]
        if p := _fields(done.get("usage"), {"input_tokens": INT, "output_tokens": INT}, "run.completed 'usage'"):
            problems.append(p)
        if p := _fields(done.get("runtime"), {"provider": STR, "model": STR}, "run.completed 'runtime'"):
            problems.append(p)
    if "done" not in seen:
        problems.append("no closing 'done' event")
    c.note(shown, affects, "; ".join(problems))


async def _delete(c: _Checker, sid: str) -> None:
    shown = "DELETE /api/sessions/{id}"
    try:
        if await c.call("DELETE", f"/api/sessions/{sid}", "deleting chats", shown=shown) is None:
            return
        status, body = await c.raw("GET", f"/api/sessions/{sid}")
        c.note(shown, "deleting chats", "" if status == 404 else f"the session is still there after deleting (HTTP {status})")
    except HermesUnavailable as exc:
        c.note(shown, "deleting chats", f"Hermes went away ({exc}); the scratch session {sid} may be left over")


async def _check_jobs(c: _Checker) -> None:
    affects = "automations and Home"
    data = await c.call("GET", "/api/jobs", affects, params={"include_disabled": "true"})
    ids: set | None = None
    if data is not None:
        jobs = data.get("jobs", data.get("data"))
        if not isinstance(jobs, list):
            problem = "'jobs' is missing or not a list"
            ids = None
        else:
            ids = {j.get("id") for j in jobs if isinstance(j, dict)}
            wanted = {"id": STR, "name": (str, type(None)), "state": STR, "enabled": (bool,), "prompt": (str, type(None)),
                      "next_run_at": (str, int, float, type(None)), "last_run_at": (str, int, float, type(None)),
                      "last_status": (str, type(None)), "last_error": (str, type(None))}
            problem = next((p for p in (_fields(j, wanted, f"job {j.get('id') if isinstance(j, dict) else '?'}")
                                        for j in jobs) if p), "")
            if not problem:
                odd = sorted({str(j["state"]) for j in jobs if j["state"] not in JOB_STATES})
                problem = f"unknown job states {odd}" if odd else ""
            if not problem and not all(isinstance(j.get("schedule_display"), str) or isinstance(j.get("schedule"), dict)
                                       for j in jobs):
                problem = "a job has neither 'schedule_display' nor a 'schedule' object"
        c.note("GET /api/jobs", affects, problem)

    if ids is not None and MISSING_JOB not in ids:  # only when the list loaded: never touch a real job
        for method, path, shown in (("PATCH", f"/api/jobs/{MISSING_JOB}", "PATCH /api/jobs/{id}"),
                                    ("POST", f"/api/jobs/{MISSING_JOB}/run", "POST /api/jobs/{id}/run"),
                                    ("POST", f"/api/jobs/{MISSING_JOB}/pause", "POST /api/jobs/{id}/pause"),
                                    ("DELETE", f"/api/jobs/{MISSING_JOB}", "DELETE /api/jobs/{id}")):
            kwargs = {"json": {"name": "x"}} if method == "PATCH" else {"json": {}} if method == "POST" else {}
            await c.missing(method, path, affects, shown, **kwargs)
    runs = await c.call("GET", "/api/sessions", "automation results on Home", shown="GET /api/sessions?source=cron",
                        params={"source": "cron", "limit": 5})
    if runs is not None:
        rows = runs.get("data", runs.get("sessions"))
        problem = "'data' is missing or not a list" if not isinstance(rows, list) else next(
            (f"a session has no 'id'" for r in rows if not isinstance(r, dict) or not isinstance(r.get("id") or r.get("session_id"), str)), "")
        c.note("GET /api/sessions?source=cron", "automation results on Home", problem)


# the bridge watching by itself

CHECK_ID = "hermes-check"  # its Home card's id (spec/README.md §14: a system card, not one of Hermes's jobs)
CHECK_NAME = "Hermes compatibility check"
VERSION_EVERY_S = 3600  # how often to look at Hermes's version (one cheap call)
FULL_EVERY_S = 24 * 3600  # a full check at least this often, even when the version didn't change
FIRST_AFTER_S = 300  # let Hermes and the bridge settle after a restart first

Notify = Callable[[dict], Awaitable[None]]


class HermesWatch:
    """Checks each agent's Hermes when its version (or the bridge's) changes and once a day; puts what broke on Home."""

    def __init__(self, clients: dict[str, HermesClient], automations, state_path: Path, *, turn: bool = True,
                 now: Callable[[], float] = time.time, bridge_version: str = BRIDGE_VERSION):
        self.clients = clients
        self.automations = automations  # automations.Automations: its store holds the Home card
        self.state_path = state_path
        self.turn = turn
        self.now = now
        self.bridge_version = bridge_version  # a new bridge (maybe the fix) checks again at once

    def _state(self) -> dict:
        try:
            data = json.loads(self.state_path.read_text())
            return data if isinstance(data, dict) else {}
        except (OSError, ValueError):
            return {}

    def _save(self, state: dict) -> None:
        tmp = self.state_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(state, indent=1))
        tmp.replace(self.state_path)

    async def run(self) -> None:
        await asyncio.sleep(FIRST_AFTER_S)
        while True:
            try:
                await self.tick()
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 (keep watching whatever one round hit)
                log.exception("Hermes check failed")
            await asyncio.sleep(VERSION_EVERY_S)

    async def tick(self) -> None:
        state = self._state()
        for agent_id, client in self.clients.items():
            last = state.get(agent_id) or {}
            try:
                version = (await client._call("GET", "/health")).get("version")
            except (HermesError, HermesUnavailable):
                continue  # Hermes down is the status page's news, not a compatibility problem
            if (version == last.get("version") and last.get("bridge") == self.bridge_version
                    and self.now() - float(last.get("checked_at") or 0) < FULL_EVERY_S):
                continue
            try:
                report = await check_hermes(client, turn=self.turn)
            except HermesUnavailable as exc:
                log.warning("Hermes went away during the check: %s", exc)
                continue
            problems = [f"{f.call} {f.problem}" for f in report.problems]
            entry = {"version": report.version or version, "bridge": self.bridge_version,
                     "checked_at": int(self.now()), "ok": report.ok,
                     "problems": problems, "card_at": last.get("card_at")}
            if report.ok:
                log.info("Hermes %s: all %d checks pass", report.version, len(report.findings))
                if last.get("card_at"):
                    await self._clear(int(last["card_at"]))
                entry["card_at"] = None
            else:
                log.warning("Hermes %s changed: %s", report.version, "; ".join(problems))
                entry["card_at"] = await self._card(report, renotify=problems != last.get("problems"),
                                                    agent=agent_id if len(self.clients) > 1 else None)
            state[agent_id] = entry
            self._save(state)

    async def _card(self, report: Report, *, renotify: bool, agent: str | None) -> int | None:
        if self.automations is None:
            return None
        at = int(self.now())
        text = report.summary() if agent is None else f"({agent}) {report.summary()}"
        run = {"at": at, "status": "error", "error": text[:2000]}
        self.automations.store.add_run(CHECK_ID, run)
        if self.automations.notify is not None:
            if renotify:
                await self.automations.notify(m.notification(
                    "automations.ran", {"id": CHECK_ID, "name": CHECK_NAME, "result_to": "home", "run": run}))
            await self.automations.notify(m.notification("home.changed", self.automations.home({})))
        return at

    async def _clear(self, card_at: int) -> None:
        if self.automations is not None:
            await self.automations.dismiss_run(CHECK_ID, card_at)
