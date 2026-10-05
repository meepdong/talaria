import json
import sys
import time
from datetime import datetime
from pathlib import Path

import httpx
import pytest

from talaria_bridge.automations import (
    AutomationError, AutomationStore, Automations, blocked_reason, calendar_event, check_when, job_spec, when_text,
)
from talaria_bridge.chat import ChatService, ChatStore
from talaria_bridge.hermes import HermesClient
from talaria_bridge.protocol import messages as m

from conftest import check
from test_chat import KEY, FakeHermes

MORNING = {"kind": "arrives", "watch": "an email from gemini-notes@google.com with today's catch-up transcript",
           "from": "10:30", "until": "13:00", "days": ["fri", "mon", "tue", "wed", "thu"],
           "fallback": "Summarise my day from my calendar only, and say the transcript hasn't come."}


class FakeJobs(FakeHermes):
    """FakeHermes plus its scheduled jobs and the cron sessions their runs are saved as."""

    def __init__(self):
        super().__init__()
        self.jobs: dict[str, dict] = {}
        self.actions: list[tuple[str, str]] = []

    async def handle(self, req: httpx.Request) -> httpx.Response:
        path, method = req.url.path, req.method
        if path == "/api/sessions" and method == "GET":
            assert req.url.params["source"] == "cron"
            return httpx.Response(200, json={"object": "list", "data": [
                {"id": sid, "source": "cron"} for sid in self.sessions if sid.startswith("cron_")]})
        if not path.startswith("/api/jobs"):
            return await super().handle(req)
        parts = path.split("/")  # ['', 'api', 'jobs', id?, action?]
        if len(parts) == 3:
            if method == "GET":
                assert req.url.params["include_disabled"] == "true"
                return httpx.Response(200, json={"jobs": list(self.jobs.values())})
            body = json.loads(req.content)
            if body["schedule"] == "bad":
                return httpx.Response(400, json={"error": {"message": "Invalid schedule", "code": "invalid_schedule"}})
            job_id = f"{len(self.jobs) + 1:012x}"
            self.jobs[job_id] = {"id": job_id, "enabled": True, "state": "scheduled",
                                 "schedule_display": body["schedule"], "next_run_at": "2026-10-05T10:30:00+00:00",
                                 **body}
            return httpx.Response(201, json={"job": self.jobs[job_id]})
        job = self.jobs.get(parts[3])
        if job is None:
            return httpx.Response(404, json={"error": {"message": "Job not found", "code": "not_found"}})
        if method == "PATCH":
            job.update(json.loads(req.content))
            return httpx.Response(200, json={"job": job})
        if method == "DELETE":
            del self.jobs[parts[3]]
            return httpx.Response(200, json={"ok": True})
        self.actions.append((parts[3], parts[4]))
        if parts[4] == "pause":
            job.update(enabled=False, state="paused")
        elif parts[4] == "resume":
            job.update(enabled=True, state="scheduled")
        return httpx.Response(200, json={"job": job})

    def ran(self, job_id: str, at: int, answer: str, status: str = "ok", tool: str | None = None) -> None:
        self.jobs[job_id].update(last_run_at=at, last_status=status)
        tools = [{"id": 3, "role": "tool", "content": tool, "timestamp": at + 2}] if tool else []
        self.sessions[f"cron_{job_id}_{time.strftime('%Y%m%d_%H%M%S', time.localtime(at))}"] = {
            "title": None, "messages": [
                {"id": 1, "role": "user", "content": "the prompt", "timestamp": at},
                *tools,
                {"id": 2, "role": "assistant", "content": answer, "timestamp": at + 5}]}


def setup(tmp_path: Path, **kwargs) -> tuple[Automations, FakeJobs, list[dict]]:
    hermes = FakeJobs()
    client = HermesClient("http://hermes.test", KEY, transport=hermes.transport())
    autos = Automations({"hermes": client}, AutomationStore(tmp_path / "chat.db"), **kwargs)
    sent: list[dict] = []

    async def notify(msg: dict) -> None:
        sent.append(msg)

    autos.notify = notify
    return autos, hermes, sent


def result(name: str, value: dict) -> dict:
    return check(name, m.result("1", value))["result"]


def test_jobs_for_each_kind_of_when():
    schedule, prompt = job_spec("Morning summary", check_when(MORNING), "Summarise it.", "home", "abc")
    assert schedule == "*/10 10-13 * * 1,2,3,4,5"
    assert "[SILENT]" in prompt and "gemini-notes@google.com" in prompt and "13:00 or later" in prompt
    assert "~/.talaria/automations/abc" in prompt and "Home screen" in prompt
    assert when_text(check_when(MORNING)).startswith("Weekdays 10:30–13:00")

    schedule, prompt = job_spec("Notes", check_when({"kind": "after_event", "event": "Catch-up", "delay_minutes": 30,
                                                     "days": ["sat", "sun"]}), "Write notes.", "log", "m")
    assert schedule == "*/10 6-23 * * 6,0" and '"Catch-up"' in prompt and "30 minutes" in prompt
    assert job_spec("x", {"kind": "time", "schedule": "0 9 * * *"}, "Do it.", "log", "m") == ("0 9 * * *", "Do it.")


@pytest.mark.parametrize("when", [
    None, {"kind": "other"}, {"kind": "time", "schedule": " "},
    {**MORNING, "until": "10:00"}, {**MORNING, "from": "9:00"}, {**MORNING, "days": []},
    {**MORNING, "days": ["mon", "mon"]},
    {"kind": "after_event", "event": "x", "delay_minutes": 241, "days": ["mon"]},
])
def test_bad_whens(when):
    with pytest.raises(AutomationError) as err:
        check_when(when)
    assert err.value.code == m.INVALID_PARAMS


async def test_add_list_change_and_delete(tmp_path: Path):
    autos, hermes, sent = setup(tmp_path)
    added = result("automations.result", await autos.add({"name": "Morning summary", "when": MORNING,
                                                          "task": "Summarise the transcript."}))["automation"]
    job = hermes.jobs[added["id"]]
    assert job["deliver"] == "local" and job["schedule"] == "*/10 10-13 * * 1,2,3,4,5"
    assert added["when"]["days"] == ["mon", "tue", "wed", "thu", "fri"], "days in week order"
    assert (added["task"], added["result_to"], added["made_in"], added["state"]) == (
        "Summarise the transcript.", "home", "talaria", "scheduled")
    assert added["next_run_at"] == int(datetime.fromisoformat("2026-10-05T10:30:00+00:00").timestamp())
    assert sent[-1]["method"] == "automations.changed"

    # a job made in a Hermes chat shows up too
    hermes.jobs["0000000000ff"] = {"id": "0000000000ff", "name": "Weekly review", "prompt": "Review my week",
                                   "schedule": {"kind": "cron", "expr": "0 17 * * 5", "display": "Fridays at 17:00"},
                                   "enabled": True, "state": "scheduled"}
    listed = result("automations.list.result", await autos.list({}))["automations"]
    other = next(a for a in listed if a["id"] == "0000000000ff")
    assert (other["when"], other["made_in"], other["result_to"], other["schedule_text"]) == (
        {"kind": "other"}, "agent", "log", "Fridays at 17:00")
    with pytest.raises(AutomationError) as err:
        await autos.update({"id": "0000000000ff", "when": MORNING})
    assert err.value.code == m.CONFLICT
    homed = (await autos.update({"id": "0000000000ff", "result_to": "home"}))["automation"]
    assert homed["result_to"] == "home" and homed["made_in"] == "agent"

    changed = (await autos.update({"id": added["id"], "when": {**MORNING, "until": "12:00"}, "paused": True}))
    assert hermes.jobs[added["id"]]["schedule"] == "*/10 10-12 * * 1,2,3,4,5"
    assert changed["automation"]["state"] == "paused" and (added["id"], "pause") in hermes.actions

    with pytest.raises(AutomationError) as err:
        await autos.add({"name": "x", "when": {"kind": "time", "schedule": "bad"}, "task": "y"})
    assert err.value.code == m.INVALID_PARAMS

    assert (await autos.delete({"id": added["id"]}))["deleted"] is True
    assert added["id"] not in hermes.jobs
    with pytest.raises(AutomationError) as err:
        await autos.run({"id": added["id"]})
    assert err.value.code == m.NOT_FOUND


async def test_runs_reach_home_and_chats(tmp_path: Path):
    chats = []
    autos, hermes, sent = setup(tmp_path, on_chat=lambda *a: chats.append(a) or "c-1")
    summary = (await autos.add({"name": "Morning summary", "when": MORNING, "task": "Summarise."}))["automation"]
    digest = (await autos.add({"name": "Digest", "when": {"kind": "time", "schedule": "0 18 * * *"},
                               "task": "Digest my email.", "result_to": "chat"}))["automation"]
    await autos.poll_once()
    sent.clear()

    now = int(time.time())
    hermes.ran(summary["id"], now - 600, "[SILENT]")
    await autos.poll_once()
    assert not [x for x in sent if x["method"] == "automations.ran"], "a check with nothing to do is quiet"
    listed = (await autos.list({}))["automations"]
    assert next(a for a in listed if a["id"] == summary["id"])["last_status"] == "nothing"

    hermes.ran(summary["id"], now - 60, "Decisions: ship Friday. For you: review the deck.")
    hermes.ran(digest["id"], now - 30, "3 emails need you.")
    await autos.poll_once()
    ran = [check("automations.ran", x)["params"] for x in sent if x["method"] == "automations.ran"]
    assert {(r["name"], r["result_to"], r["run"]["status"]) for r in ran} == {
        ("Morning summary", "home", "ok"), ("Digest", "chat", "ok")}
    assert next(r for r in ran if r["name"] == "Digest")["run"]["conversation_id"] == "c-1"
    assert chats[0][0] == "hermes" and chats[0][1].startswith(f"cron_{digest['id']}_")

    home = result("home.get.result", autos.home({}))
    assert [(r["name"], r["run"]["text"]) for r in home["results"]] == [
        ("Morning summary", "Decisions: ship Friday. For you: review the deck.")]
    runs = result("automations.runs.result", autos.runs({"id": summary["id"]}))["runs"]
    assert [r["status"] for r in runs] == ["ok"]

    sent.clear()
    await autos.poll_once()
    assert not [x for x in sent if x["method"] == "automations.ran"], "each run is announced once"


REFUSED = json.dumps({"output": "", "exit_code": -1, "error": (
    "BLOCKED: Command flagged as dangerous (recursive delete) but cron jobs run without a user present to approve "
    "it. Find an alternative approach that avoids this command. To allow dangerous commands in cron jobs, set "
    "approvals.cron_mode: approve in config.yaml."), "status": "blocked"})


def test_blocked_reason():
    assert blocked_reason([{"role": "tool", "content": REFUSED}]) == "recursive delete"
    plugin = ("BLOCKED: Tool 'send_email' requires approval (sends mail) but cron jobs run without a user present to "
              "approve it. Find an alternative approach.")
    assert blocked_reason([{"role": "tool", "content": [{"type": "text", "text": plugin}]}]) == \
        "Tool 'send_email' requires approval (sends mail)"
    assert blocked_reason([{"role": "tool", "content": "BLOCKED: User denied this command."}]) is None
    assert blocked_reason([{"role": "assistant", "content": "All done."}]) is None


async def test_blocked_runs_reach_home_once_and_run_in_chat(tmp_path: Path):
    autos, hermes, sent = setup(tmp_path)
    tidy = (await autos.add({"name": "Tidy downloads", "when": {"kind": "time", "schedule": "*/10 * * * *"},
                             "task": "Delete files older than a week in ~/Downloads.", "result_to": "log"}))["automation"]
    await autos.poll_once()
    sent.clear()

    now = int(time.time())
    hermes.ran(tidy["id"], now - 50, "I couldn't delete them: the command needs approval.", tool=REFUSED)
    await autos.poll_once()
    ran = [check("automations.ran", x)["params"] for x in sent if x["method"] == "automations.ran"]
    assert [(r["result_to"], r["run"]["status"], r["run"]["blocked"]) for r in ran] == [
        ("log", "blocked", "recursive delete")]
    assert ran[0]["run"]["text"].startswith("I couldn't delete them")
    listed = check("automations.list.result", m.result("1", await autos.list({})))["result"]["automations"]
    assert next(a for a in listed if a["id"] == tidy["id"])["last_status"] == "blocked"
    home = result("home.get.result", autos.home({}))
    assert [(r["name"], r["run"]["status"]) for r in home["results"]] == [("Tidy downloads", "blocked")]

    sent.clear()
    hermes.ran(tidy["id"], now - 30, "Still blocked.", tool=REFUSED)
    await autos.poll_once()
    assert not [x for x in sent if x["method"] == "automations.ran"], "blocked again: no second notification"
    runs = result("automations.runs.result", autos.runs({"id": tidy["id"]}))["runs"]
    assert [r["status"] for r in runs] == ["blocked", "blocked"]

    hermes.ran(tidy["id"], now - 10, "Deleted 12 files.")
    await autos.poll_once()
    assert result("home.get.result", autos.home({}))["results"] == [], "a log job leaves Home once it runs"

    async def unused(msg: dict) -> None:
        pass

    chat = ChatService(ChatStore(tmp_path / "chat.db"), dict(autos.clients), unused, automations=autos)
    res, turn = await chat.handle("automations.run_in_chat", {"id": tidy["id"]})
    check("chat.send.result", m.result("1", res))
    assert turn.conversation_id == res["conversation_id"] and turn.agent_id == "hermes"
    assert turn.user_text.startswith('Run my automation "Tidy downloads" now')
    assert "Delete files older than a week in ~/Downloads.\n\nIts last run (" in turn.user_text
    assert turn.user_text.endswith("answered:\n> Deleted 12 files."), "Hermes knows what the last run said"
    assert hermes.actions == [], "the job itself isn't run or changed"
    check("automations.run_in_chat", m.request("2", "automations.run_in_chat", {"id": tidy["id"]}))
    with pytest.raises(Exception) as err:
        await chat.handle("automations.run_in_chat", {"id": "000000000fff"})
    assert err.value.code == m.NOT_FOUND
    chat.store.close()



async def test_home_dismiss_takes_one_run_off_home(tmp_path: Path):
    autos, hermes, sent = setup(tmp_path)
    tidy = (await autos.add({"name": "Tidy downloads", "when": {"kind": "time", "schedule": "*/10 * * * *"},
                             "task": "Delete old files.", "result_to": "log"}))["automation"]
    summary = (await autos.add({"name": "Morning summary", "when": MORNING, "task": "Summarise."}))["automation"]
    now = int(time.time())
    hermes.ran(tidy["id"], now - 180, "Blocked earlier.", tool=REFUSED)
    await autos.poll_once()
    hermes.ran(summary["id"], now - 120, "Ship Friday.")
    hermes.ran(tidy["id"], now - 60, "Blocked.", tool=REFUSED)
    await autos.poll_once()
    home = result("home.get.result", autos.home({}))["results"]
    assert [r["name"] for r in home] == ["Tidy downloads", "Morning summary"]
    blocked_at = home[0]["run"]["at"]

    sent.clear()
    req = check("home.dismiss", m.request("2", "home.dismiss", {"id": tidy["id"], "at": blocked_at}))
    assert result("home.dismiss.result", await autos.handle("home.dismiss", req["params"])) == {}
    changed = [check("home.changed", x)["params"] for x in sent if x["method"] == "home.changed"]
    assert [[r["name"] for r in c["results"]] for c in changed] == [["Morning summary"]]
    assert [r["name"] for r in autos.home({})["results"]] == ["Morning summary"]

    # an older run of the dismissed job doesn't take its place; dismissing again is fine
    await autos.dismiss({"id": tidy["id"], "at": blocked_at})
    assert [r["name"] for r in autos.home({})["results"]] == ["Morning summary"]
    await autos.dismiss({"id": summary["id"], "at": home[1]["run"]["at"]})
    assert autos.home({})["results"] == []

    # a new blocked run of the same job shows again
    hermes.ran(tidy["id"], now - 10, "Blocked again.", tool=REFUSED)
    await autos.poll_once()
    assert [(r["name"], r["run"]["text"]) for r in autos.home({})["results"]] == [("Tidy downloads", "Blocked again.")]

    for bad in ({"id": tidy["id"]}, {"at": blocked_at}, {"id": "../x", "at": 1}, {"id": tidy["id"], "at": -1},
                {"id": tidy["id"], "at": True}, {"id": tidy["id"], "at": "1"}):
        with pytest.raises(AutomationError) as err:
            await autos.handle("home.dismiss", bad)
        assert err.value.code == m.INVALID_PARAMS


async def test_home_read_archive_and_restore(tmp_path: Path):
    autos, hermes, sent = setup(tmp_path)
    summary = (await autos.add({"name": "Morning summary", "when": MORNING, "task": "Summarise."}))["automation"]
    now = int(time.time())
    hermes.ran(summary["id"], now - 60, "Ship Friday.")
    await autos.poll_once()
    at = autos.home({})["results"][0]["run"]["at"]
    assert "read" not in autos.home({})["results"][0], "new results are unread"

    sent.clear()
    req = check("home.read", m.request("1", "home.read", {"id": summary["id"], "at": at}))
    assert result("home.read.result", await autos.handle("home.read", req["params"])) == {}
    assert result("home.get.result", autos.home({}))["results"][0]["read"] is True
    changed = [check("home.changed", x)["params"] for x in sent if x["method"] == "home.changed"]
    assert changed[-1]["results"][0]["read"] is True, "every device hears it"
    await autos.handle("home.read", {"id": summary["id"], "at": at, "read": False})
    assert "read" not in autos.home({})["results"][0]

    await autos.handle("home.dismiss", {"id": summary["id"], "at": at})
    assert autos.home({})["results"] == []
    req = check("home.archived", m.request("2", "home.archived", {}))
    archived = result("home.archived.result", await autos.handle("home.archived", req["params"]))["results"]
    assert [(r["name"], r["run"]["text"]) for r in archived] == [("Morning summary", "Ship Friday.")]

    req = check("home.restore", m.request("3", "home.restore", {"id": summary["id"], "at": at}))
    assert result("home.restore.result", await autos.handle("home.restore", req["params"])) == {}
    assert [r["name"] for r in autos.home({})["results"]] == ["Morning summary"]
    assert autos.archived({})["results"] == []
    with pytest.raises(AutomationError) as err:
        await autos.handle("home.read", {"id": summary["id"], "at": at, "read": "yes"})
    assert err.value.code == m.INVALID_PARAMS


async def test_run_in_chat_says_why_it_was_blocked(tmp_path: Path):
    autos, hermes, sent = setup(tmp_path)
    tidy = (await autos.add({"name": "Tidy downloads", "when": {"kind": "time", "schedule": "*/10 * * * *"},
                             "task": "Delete old files.", "result_to": "log"}))["automation"]
    hermes.ran(tidy["id"], int(time.time()) - 60, "Blocked.", tool=REFUSED)
    await autos.poll_once()
    _, text, blocked = await autos.chat_task({"id": tidy["id"]})
    assert blocked is not None
    assert "Delete old files.\n\nIts last run (" in text and ") was blocked: it needed my approval for " in text


async def test_run_in_chat_resolves_the_blocked_run(tmp_path: Path):
    autos, hermes, sent = setup(tmp_path)
    tidy = (await autos.add({"name": "Tidy downloads", "when": {"kind": "time", "schedule": "*/10 * * * *"},
                             "task": "Delete old files.", "result_to": "log"}))["automation"]
    hermes.ran(tidy["id"], int(time.time()) - 60, "Blocked.", tool=REFUSED)
    await autos.poll_once()
    assert len(autos.home({})["results"]) == 1

    async def unused(msg: dict) -> None:
        pass

    chat = ChatService(ChatStore(tmp_path / "chat.db"), dict(autos.clients), unused, automations=autos)
    autos.notify = lambda msg: unused(sent.append(msg))  # ChatService rewires it to broadcast
    script, hermes.script = hermes.script, [("run.failed", {"error": "boom"}), ("done", {})]  # the turn fails
    _, turn = await chat.handle("automations.run_in_chat", {"id": tidy["id"]})
    chat.start(turn)
    await turn.task
    assert turn.status == "failed"
    assert len(autos.home({})["results"]) == 1, "a failed chat leaves the card"
    hermes.script = script
    sent.clear()
    _, turn = await chat.handle("automations.run_in_chat", {"id": tidy["id"]})
    chat.start(turn)
    await turn.task
    assert turn.status == "completed"
    assert autos.home({})["results"] == []
    assert [check("home.changed", x)["params"]["results"] for x in sent if x["method"] == "home.changed"] == [[]]
    chat.store.close()


async def test_describe_asks_hermes_and_lists_what_it_made(tmp_path: Path):
    autos, hermes, _ = setup(tmp_path)
    hermes.final = "Done: “Unread email” runs weekdays at 8:00."
    hermes.jobs["0000000000aa"] = {"id": "0000000000aa", "name": "Unread email", "prompt": "Summarise unread email",
                                   "schedule": "0 8 * * 1-5", "enabled": True, "state": "scheduled"}
    out = result("automations.describe.result",
                 await autos.describe({"text": "every weekday at 8, summarise my unread email"}))
    assert out["reply"] == "Done: “Unread email” runs weekdays at 8:00."
    assert "every weekday at 8, summarise my unread email" in hermes.messages[-1]
    assert [a["name"] for a in out["automations"]] == ["Unread email"]
    assert not [s for s in hermes.sessions if s.startswith("talaria_auto_")], "the throwaway session is deleted"


async def test_calendar_day(tmp_path: Path):
    script = tmp_path / "calendar.py"
    script.write_text("import json, sys\n"
                      "assert sys.argv[1] == '2026-10-05'\n"
                      "print(json.dumps([\n"
                      " {'summary': 'Company catch-up', 'start': '2026-10-05T10:00:00+05:30',"
                      "  'end': '2026-10-05T10:30:00+05:30', 'location': 'Meet'},\n"
                      " {'summary': 'Holiday', 'start': {'date': '2026-10-05'}, 'end': {'date': '2026-10-06'}},\n"
                      " {'summary': 'Gone', 'start': '2026-10-05T09:00:00Z', 'end': '2026-10-05T09:30:00Z',"
                      "  'status': 'cancelled'}]))\n")
    autos, _, _ = setup(tmp_path, calendars={"hermes": (sys.executable, str(script))})
    day = result("calendar.day.result", await autos.calendar_day({"date": "2026-10-05"}))
    assert [(e["title"], e["all_day"]) for e in day["events"]] == [("Holiday", True), ("Company catch-up", False)]
    assert day["events"][1]["location"] == "Meet"

    none, _, _ = setup(tmp_path)
    empty = result("calendar.day.result", await none.calendar_day({}))
    assert empty["events"] == [] and "No calendar" in empty["error"]
    with pytest.raises(AutomationError):
        await none.calendar_day({"date": "5 Oct"})
    assert calendar_event({"summary": "x"}) is None


async def test_the_agent_sends_its_own_job_to_home(tmp_path: Path):
    from talaria_bridge.agent_tools import AgentTools
    from talaria_bridge.todos import TodoStore

    autos, hermes, sent = setup(tmp_path)
    todos = TodoStore(tmp_path / "chat.db")

    async def changed() -> None:
        pass

    tools = AgentTools(todos, {"t" * 40: "hermes"}, changed, automations=autos)
    listed = await tools.rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, "hermes")
    report = next(t for t in listed["result"]["tools"] if t["name"] == "automation_report_to")
    assert "don't ask where to send it" in report["description"]

    # Hermes makes a job with its own scheduling tool, in a chat: Talaria hasn't seen it yet
    hermes.jobs["00000000abcd"] = {"id": "00000000abcd", "name": "Water the plants", "enabled": True,
                                   "state": "scheduled", "schedule": "0 8 * * *", "prompt": "Remind me.",
                                   "deliver": "local"}

    async def call(args: dict) -> tuple[dict | str, bool]:
        out = await tools.call("automation_report_to", args, "hermes")
        text = out["content"][0]["text"]
        return (text if out.get("isError") else json.loads(text)), bool(out.get("isError"))

    assert await call({"id": "00000000abcd", "to": "home"}) == (
        {"id": "00000000abcd", "name": "Water the plants", "result_to": "home"}, False)
    assert hermes.jobs["00000000abcd"]["prompt"] == "Remind me.", "Hermes's job itself is left alone"
    text, err = await call({"id": "00000000ffff", "to": "home"})
    assert err and "Unknown automation" in text
    text, err = await call({"id": "00000000abcd", "to": "telegram"})
    assert err and "home, chat or log" in text

    await autos.poll_once()
    sent.clear()
    hermes.ran("00000000abcd", int(time.time()) - 30, "Time to water the plants.")
    await autos.poll_once()
    ran = [check("automations.ran", x)["params"] for x in sent if x["method"] == "automations.ran"]
    assert [(r["name"], r["result_to"]) for r in ran] == [("Water the plants", "home")]  # devices notify
    assert [r["name"] for r in result("home.get.result", autos.home({}))["results"]] == ["Water the plants"]
    todos.close()
