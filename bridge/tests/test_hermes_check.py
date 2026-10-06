"""The Hermes compatibility check (hermes_check.py): a fake Hermes that answers like the real one (0.21.5), then
renames or drops one thing at a time; the check must name exactly that."""

import json
from pathlib import Path

import httpx
import pytest

from talaria_bridge.automations import AutomationStore, Automations
from talaria_bridge.cli import main as cli_main
from talaria_bridge.hermes import HermesClient, HermesUnavailable
from talaria_bridge.protocol import messages as m
from talaria_bridge.hermes_check import CHECK_ID, CHECK_NAME, MISSING_JOB, HermesWatch, check_hermes

from conftest import check

KEY = "k" * 32


class RealishHermes:
    """Answers like Hermes 0.21.5's API server, as far as the bridge reads it. `drop` and `rename` change one
    answer's fields (by a key like "session", "messages", "run.completed"), `gone` removes an address."""

    def __init__(self):
        self.version = "0.21.5"
        self.sessions: dict[str, dict] = {}
        self.drop: dict[str, str] = {}
        self.rename: dict[str, tuple[str, str]] = {}
        self.gone: set[str] = set()
        self.requests: list[tuple[str, str]] = []
        self.turns = 0
        self.job = {"id": "784e2ad17a81", "name": "Morning summary", "state": "scheduled", "enabled": True,
                    "prompt": "Summarise.", "next_run_at": "2026-10-07T04:30:00+00:00", "last_run_at": None,
                    "last_status": None, "last_error": None, "schedule": {"kind": "cron", "display": "30 4 * * *"},
                    "schedule_display": "30 4 * * *"}

    def shape(self, key: str, obj: dict) -> dict:
        obj = dict(obj)
        if key in self.drop:
            obj.pop(self.drop[key], None)
        if key in self.rename:
            old, new = self.rename[key]
            obj[new] = obj.pop(old)
        return obj

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handle)

    def handle(self, request: httpx.Request) -> httpx.Response:
        method, path = request.method, request.url.path
        self.requests.append((method, path))
        assert request.headers["authorization"] == f"Bearer {KEY}"
        body = json.loads(request.content) if request.content else {}
        route = f"{method} {path}"
        for g in self.gone:
            if route.startswith(g):
                return httpx.Response(404, text="404: Not Found")

        def not_found(code: str, what: str) -> httpx.Response:
            return httpx.Response(404, json={"error": {"message": f"{what} not found", "type": "invalid_request_error",
                                                       "code": code}})

        if route == "GET /health":
            return httpx.Response(200, json=self.shape("health", {"status": "ok", "platform": "hermes-agent",
                                                                  "version": self.version}))
        if route == "GET /api/model/options":
            return httpx.Response(200, json=self.shape("options", {
                "provider": "openrouter", "model": "qwen/qwen3.8-flash",
                "providers": [self.shape("provider", {"slug": "openrouter", "name": "OpenRouter",
                                                      "models": ["qwen/qwen3.8-flash"], "authenticated": True})]}))
        if route == "POST /api/sessions":
            sid = body["id"]
            self.sessions[sid] = {"id": sid, "title": body.get("title"), "messages": []}
            return httpx.Response(200, json={"session": self.shape("session.created", {"id": sid, "title": body.get("title")})})
        if route == "GET /api/sessions":
            return httpx.Response(200, json=self.shape("cron", {"data": [{"id": "cron_784e2ad17a81_20261006_043000"}]}))
        if path.startswith("/api/sessions/"):
            parts = path.split("/")
            sid = parts[3]
            if sid not in self.sessions:
                return not_found("session_not_found", "Session")
            s = self.sessions[sid]
            tail = "/".join(parts[4:])
            if method == "PATCH" and not tail:
                s.update(body)
                return httpx.Response(200, json={"session": {"id": sid}})
            if method == "DELETE" and not tail:
                del self.sessions[sid]
                return httpx.Response(200, json={"deleted": True})
            if method == "GET" and not tail:
                return httpx.Response(200, json={"session": self.shape("session", {
                    "id": sid, "message_count": len(s["messages"]), "tool_call_count": 0, "input_tokens": 208,
                    "output_tokens": 23, "cache_read_tokens": 14336, "api_call_count": 1, "actual_cost_usd": None,
                    "estimated_cost_usd": 0.00027})})
            if method == "POST" and tail == "model":
                return httpx.Response(200, json={"ok": True})
            if method == "GET" and tail == "messages":
                return httpx.Response(200, json=self.shape("page", {
                    "data": [self.shape("messages", r) for r in reversed(s["messages"])]}))
            if method == "POST" and tail == "chat/stream":
                return self.turn(s, body["message"])
        if path.startswith("/v1/runs/"):
            return not_found("run_not_found", "Run")
        if route == "GET /api/jobs":
            return httpx.Response(200, json={"jobs": [self.shape("job", self.job)]})
        if path.startswith("/api/jobs/"):
            assert path.split("/")[3] == MISSING_JOB, "the check must never touch a real job"
            return httpx.Response(404, json={"error": "Job not found"})
        return httpx.Response(404, text="404: Not Found")

    def turn(self, s: dict, text: str) -> httpx.Response:
        self.turns += 1
        s["messages"] += [{"id": 1, "role": "user", "content": text, "timestamp": 1.0},
                          {"id": 2, "role": "assistant", "content": "OK", "timestamp": 2.0}]
        events = [("run.started", {"run_id": "run_1"}), ("message.started", {}),
                  ("assistant.delta", {"delta": "OK"}), ("assistant.completed", {"content": "OK"}),
                  ("run.completed", {"usage": {"input_tokens": 14544, "output_tokens": 23, "total_tokens": 14567},
                                     "runtime": {"provider": "openrouter", "model": "qwen/qwen3.8-flash"}}),
                  ("done", {})]
        lines = [": keepalive\n\n"] + [f"event: {n}\ndata: {json.dumps(self.shape(n, p))}\n\n" for n, p in events
                                       if n not in self.gone]
        return httpx.Response(200, text="".join(lines), headers={"content-type": "text/event-stream"})


def client(h: RealishHermes) -> HermesClient:
    return HermesClient("http://hermes", KEY, transport=h.transport())


async def test_todays_hermes_passes_and_the_scratch_session_is_deleted():
    h = RealishHermes()
    report = await check_hermes(client(h))
    assert report.ok, [(f.call, f.problem) for f in report.problems]
    assert report.version == "0.21.5" and report.turn and h.turns == 1
    assert len(report.findings) == 19
    assert h.sessions == {}  # deleted afterwards
    assert not any(method == "POST" and path == "/api/jobs" for method, path in h.requests)  # never makes a job


async def test_without_a_turn_no_message_is_sent():
    h = RealishHermes()
    report = await check_hermes(client(h), turn=False)
    assert report.ok and h.turns == 0 and not report.turn
    assert "chat/stream" not in " ".join(path for _, path in h.requests)


@pytest.mark.parametrize("change, call, words", [
    (("rename", "messages", ("role", "author")), "GET /api/sessions/{id}/messages", "'role' is missing"),
    (("drop", "session", "api_call_count"), "GET /api/sessions/{id}", "'api_call_count' is missing"),
    (("rename", "run.started", ("run_id", "id")), "POST /api/sessions/{id}/chat/stream", "'run_id' is missing"),
    (("drop", "run.completed", "runtime"), "POST /api/sessions/{id}/chat/stream", "run.completed 'runtime' is missing"),
    (("rename", "assistant.delta", ("delta", "text")), "POST /api/sessions/{id}/chat/stream", "no assistant.delta"),
    (("rename", "provider", ("slug", "id")), "GET /api/model/options", "'slug' is missing"),
    (("drop", "job", "state"), "GET /api/jobs", "'state' is missing"),
    (("rename", "page", ("data", "messages")), "GET /api/sessions/{id}/messages", "'data' is missing"),
    (("drop", "health", "version"), "GET /health", "'version' is missing"),
    (("gone", None, "POST /v1/runs/"), "POST /v1/runs/{id}/steer", "is gone"),
    (("gone", None, "GET /api/jobs"), "GET /api/jobs", "is gone"),
    (("gone", None, "done"), "POST /api/sessions/{id}/chat/stream", "no closing 'done' event"),
])
async def test_a_changed_field_or_address_is_named(change, call, words):
    h = RealishHermes()
    kind, key, value = change
    if kind == "gone":
        h.gone.add(value)
    else:
        getattr(h, kind)[key] = value
    report = await check_hermes(client(h))
    assert not report.ok
    named = [f for f in report.problems if f.call == call]
    assert named and words in named[0].problem, [(f.call, f.problem) for f in report.problems]
    assert h.sessions == {}  # cleaned up whatever failed
    assert call in report.summary() and "Talaria needs a fix" in report.summary()


async def test_a_session_hermes_wont_make_stops_the_check_early():
    h = RealishHermes()
    h.gone.add("POST /api/sessions")
    report = await check_hermes(client(h))
    assert [f.call for f in report.problems] == ["POST /api/sessions"]
    assert not any(path.startswith("/v1/runs") for _, path in h.requests)


async def test_hermes_down_is_not_a_compatibility_problem():
    def down(request):
        raise httpx.ConnectError("refused")

    with pytest.raises(HermesUnavailable):
        await check_hermes(HermesClient("http://hermes", KEY, transport=httpx.MockTransport(down)))


async def test_a_real_job_with_the_made_up_id_is_never_touched():
    h = RealishHermes()
    h.job["id"] = MISSING_JOB
    report = await check_hermes(client(h))  # RealishHermes asserts if any job route is called with a real id
    assert report.ok
    assert not any(path.startswith("/api/jobs/") for _, path in h.requests)


# the bridge watching by itself

class Clock:
    def __init__(self):
        self.t = 1_800_000_000.0

    def __call__(self) -> float:
        return self.t


def watch(tmp_path: Path, h: RealishHermes):
    c = client(h)
    autos = Automations({"hermes": c}, AutomationStore(tmp_path / "chat.db"))
    sent: list[dict] = []

    async def notify(msg):
        sent.append(msg)

    autos.notify = notify
    clock = Clock()
    return HermesWatch({"hermes": c}, autos, tmp_path / "hermes-check.json", now=clock,
                       bridge_version="test-bridge-1"), autos, sent, clock


async def test_watch_checks_on_a_new_version_and_daily_and_says_what_broke_on_home(tmp_path: Path):
    h = RealishHermes()
    w, autos, sent, clock = watch(tmp_path, h)
    await w.tick()
    assert h.turns == 1 and sent == []
    state = json.loads((tmp_path / "hermes-check.json").read_text())["hermes"]
    assert state["version"] == "0.21.5" and state["ok"] is True

    clock.t += 3600  # an hour later, same version: only /health
    await w.tick()
    assert h.turns == 1

    h.version = "0.22.0"  # Hermes updated itself and renamed a field
    h.rename["messages"] = ("role", "author")
    await w.tick()
    assert h.turns == 2
    ran = [check("automations.ran", x)["params"] for x in sent if x["method"] == "automations.ran"]
    assert len(ran) == 1 and ran[0]["id"] == CHECK_ID and ran[0]["name"] == CHECK_NAME
    assert ran[0]["result_to"] == "home" and ran[0]["run"]["status"] == "error"
    assert "Hermes 0.22.0" in ran[0]["run"]["error"] and "'role' is missing" in ran[0]["run"]["error"]
    home = check("home.get.result", m.result("1", autos.home({})))["result"]["results"]
    assert [(r["id"], r["name"], r["run"]["status"]) for r in home] == [(CHECK_ID, CHECK_NAME, "error")]

    sent.clear()
    clock.t += 25 * 3600  # a day later, still broken: a fresh card, but no second notification
    await w.tick()
    assert h.turns == 3
    assert [x["method"] for x in sent] == ["home.changed"]

    del h.rename["messages"]  # fixed by a new bridge: checked again at once, same Hermes version
    w.bridge_version = "test-bridge-2"  # never the real version, which moves with every release
    sent.clear()
    await w.tick()
    assert h.turns == 4
    assert autos.home({})["results"] == []
    assert [x["method"] for x in sent] == ["home.changed"]
    assert json.loads((tmp_path / "hermes-check.json").read_text())["hermes"]["ok"] is True


async def test_watch_skips_a_round_while_hermes_is_down(tmp_path: Path):
    def down(request):
        raise httpx.ConnectError("refused")

    c = HermesClient("http://hermes", KEY, transport=httpx.MockTransport(down))
    autos = Automations({"hermes": c}, AutomationStore(tmp_path / "chat.db"))
    w = HermesWatch({"hermes": c}, autos, tmp_path / "hermes-check.json")
    await w.tick()
    assert not (tmp_path / "hermes-check.json").exists() and autos.home({})["results"] == []


def test_doctor_offers_the_hermes_check(capsys):
    with pytest.raises(SystemExit) as done:
        cli_main(["doctor", "--help"])
    assert done.value.code == 0
    said = capsys.readouterr().out
    assert "--hermes" in said and "--no-turn" in said
