"""Bots' routines and helper agents (routines.py, spec/README.md §18.6–18.7): a fake of Hermes's cron addresses and
of subagent.* behaves like Hermes's where the bridge relies on it (one store per profile, Talaria's automations left
out, results to the bot's chat, helpers seen only while a bot's reply runs)."""

import asyncio
import json
from pathlib import Path

import pytest
import websockets

from talaria_bridge.bots import Bots
from talaria_bridge.chat import ChatService, ChatStore
from talaria_bridge.hermes import HermesClient
from talaria_bridge.hermes_serve import PROBE_KEY, HermesBackend, ServeError
from talaria_bridge.protocol import keys
from talaria_bridge.protocol import messages as m
from talaria_bridge.registry import Registry
from talaria_bridge.routines import Routines
from talaria_bridge.server import BridgeServer, ServerSettings

from conftest import Bridge, check
from test_bots import TOKEN, FakeBotServe
from test_chat import KEY, FakeHermes, connected, recv, until_done
from test_commands import call


class FakeCron:
    def __init__(self):
        self.jobs = [
            {"id": "aaa111", "profile": "default", "name": "Morning summary", "prompt": "This is a Talaria automation, ...",
             "schedule_display": "*/10 11-12 * * 1-5", "enabled": True, "state": "scheduled", "deliver": "local"},
            {"id": "bbb222", "profile": "default", "name": "Flight watch", "prompt": "Check flight prices",
             "schedule": {"kind": "cron", "display": "0 */6 * * *"}, "enabled": True, "state": "scheduled",
             "next_run_at": "2026-10-07T12:00:00+00:00", "last_run_at": "2026-10-07T06:00:00+00:00",
             "last_status": "error", "last_error": "sandbox down", "deliver": "local"},
        ]
        self.calls: list[tuple[str, str, dict | None]] = []

    async def rest(self, path: str, method: str = "GET", body: dict | None = None):
        self.calls.append((method, path, body))
        base, _, query = path.partition("?")
        profile = query.removeprefix("profile=")
        if base == "/api/cron/jobs" and method == "GET":
            return self.jobs
        if base == "/api/cron/jobs" and method == "POST":
            if body["schedule"] == "whenever":
                raise ServeError(400, json.dumps({"detail": "Invalid schedule 'whenever'"}))
            job = {"id": f"new{len(self.jobs)}", "profile": profile, "name": body["name"], "prompt": body["prompt"],
                   "schedule_display": body["schedule"], "enabled": True, "state": "scheduled", "deliver": body["deliver"]}
            self.jobs.append(job)
            return {"job": job}
        parts = base.split("/")  # ["", "api", "cron", "jobs", id, action?]
        job = next((j for j in self.jobs if j["id"] == parts[4] and j["profile"] == profile), None)
        if job is None:
            raise ServeError(404, json.dumps({"detail": "Job not found"}))
        if method == "DELETE":
            self.jobs.remove(job)
            return {"ok": True}
        action = parts[5]
        if action == "pause":
            job.update(enabled=False, state="paused")
        elif action == "resume":
            job.update(enabled=True, state="scheduled")
        return {"job": job}


class HelperServe(FakeBotServe):
    """The bots' fake serve, whose replies start a helper agent and wait until the test lets them finish."""

    def __init__(self):
        super().__init__()
        self.helpers: list[dict] = []
        self.finish = asyncio.Event()

    async def answer(self, ws, msg):
        method, p = msg["method"], msg.get("params") or {}

        async def ok(result):
            self.calls.append((method, p))
            await self.send(ws, {"jsonrpc": "2.0", "id": msg["id"], "result": result})

        if PROBE_KEY in p:  # the doorway checking which methods exist
            return await super().answer(ws, msg)
        if method == "subagent.list":
            return await ok({"subagents": self.helpers, "delegations": []})
        if method == "subagent.steer":
            return await ok({"status": "queued", "subagent_id": p["subagent_id"], "text": p["text"]})
        if method == "subagent.interrupt":
            return await ok({"found": any(h["subagent_id"] == p["subagent_id"] for h in self.helpers),
                             "subagent_id": p["subagent_id"]})
        if method == "prompt.submit":
            self.helpers = [{"subagent_id": "sa-1", "goal": "Find three cafes", "status": "running", "tool_count": 2,
                             "last_tool": "web_search", "model": "qwen/qwen3.8-flash", "started_at": 1700000000,
                             "accepting_steer": True}]
            await self.finish.wait()
            self.helpers = []
        await super().answer(ws, msg)


@pytest.fixture
async def routines_bridge(tmp_path: Path, settings: ServerSettings):
    fake = HelperServe()
    serve = await websockets.serve(fake.handler, "127.0.0.1", 0, process_request=fake.process_request)
    backend = HermesBackend(f"ws://127.0.0.1:{serve.sockets[0].getsockname()[1]}/api/ws", TOKEN, backoff=(0.05, 0.1))
    cron = FakeCron()
    backend.rest = cron.rest
    hermes = FakeHermes()

    async def unused(msg: dict) -> None:
        pass

    chat = ChatService(ChatStore(tmp_path / "chat.db"),
                       {"hermes": HermesClient("http://hermes.test", KEY, transport=hermes.transport())}, unused)
    chat.bots = Bots(backend)
    chat.routines = Routines(backend, chat.bots, helpers_every_s=0.05)
    chat.routines.broadcast = lambda msg: chat.broadcast(msg)
    registry = Registry(tmp_path / "bridge.db")
    server = BridgeServer(registry, keys.generate_key(), settings, chat=chat, hermes=backend)
    async with server.serve() as ws_server:
        for _ in range(100):
            if chat.bots.roster():
                break
            await asyncio.sleep(0.02)
        yield Bridge(server, registry, f"ws://127.0.0.1:{ws_server.sockets[0].getsockname()[1]}/tnp"), fake, cron, chat
    fake.finish.set()
    chat.store.close()
    registry.close()
    serve.close()
    await serve.wait_closed()


async def test_routines_leave_out_talarias_automations(routines_bridge):
    bridge, _, _, _ = routines_bridge
    phone = await connected(bridge)
    got = check("routines.list.result", await call(phone, "1", "routines.list"))["result"]
    assert got["available"] and [r["name"] for r in got["routines"]] == ["Flight watch"]
    r = got["routines"][0]
    assert r["bot_id"] == "assistant" and r["schedule"] == "0 */6 * * *" and r["last_status"] == "error"
    assert r["next_run_at"] == 1791374400 and r["last_error"] == "sandbox down" and not r["to_chat"]


async def test_a_bots_routine_reports_to_its_chat(routines_bridge):
    bridge, _, cron, _ = routines_bridge
    phone = await connected(bridge)
    made = check("routines.add.result", await call(phone, "1", "routines.add", {
        "bot_id": "bot:scout", "name": "Cafe of the week", "schedule": "every monday 8am", "task": "Find a new cafe"}))
    r = made["result"]["routine"]
    assert r["bot_id"] == "bot:scout" and r["to_chat"] and r["schedule"] == "every monday 8am"
    assert cron.calls[-1] == ("POST", "/api/cron/jobs?profile=scout", {"name": "Cafe of the week", "schedule": "every monday 8am",
                                                                     "prompt": "Find a new cafe", "deliver": "bot-chat:scout"})
    bad = await call(phone, "2", "routines.add", {"bot_id": "bot:scout", "name": "x", "schedule": "whenever", "task": "y"})
    assert bad["error"]["code"] == m.INVALID_PARAMS and "whenever" in bad["error"]["message"]
    mine = await call(phone, "3", "routines.add", {"bot_id": "assistant", "name": "x", "schedule": "every 2h", "task": "y"})
    assert mine["error"]["code"] == m.NOT_FOUND  # the assistant's routines are §14's automations
    nobody = await call(phone, "4", "routines.add", {"bot_id": "bot:ghost", "name": "x", "schedule": "every 2h", "task": "y"})
    assert nobody["error"]["code"] == m.NOT_FOUND


async def test_pause_resume_run_and_remove(routines_bridge):
    bridge, _, cron, _ = routines_bridge
    phone = await connected(bridge)
    paused = check("routines.set.result", await call(phone, "1", "routines.set",
                                                     {"bot_id": "assistant", "routine_id": "bbb222", "action": "pause"}))
    assert paused["result"]["routine"]["enabled"] is False and paused["result"]["routine"]["state"] == "paused"
    await call(phone, "2", "routines.set", {"bot_id": "assistant", "routine_id": "bbb222", "action": "run"})
    assert cron.calls[-1][:2] == ("POST", "/api/cron/jobs/bbb222/trigger?profile=default")
    gone = check("routines.set.result", await call(phone, "3", "routines.set",
                                                   {"bot_id": "assistant", "routine_id": "bbb222", "action": "remove"}))
    assert gone["result"] == {} and [j["id"] for j in cron.jobs] == ["aaa111"]
    missing = await call(phone, "4", "routines.set", {"bot_id": "assistant", "routine_id": "bbb222", "action": "pause"})
    assert missing["error"]["code"] == m.NOT_FOUND


async def test_helpers_show_while_a_bots_reply_runs_and_take_notes(routines_bridge):
    bridge, fake, _, _ = routines_bridge
    phone = await connected(bridge)
    conv = (await call(phone, "1", "bots.open", {"bot_id": "bot:scout"}))["result"]["conversation_id"]
    sent = (await call(phone, "2", "chat.send", {"conversation_id": conv, "text": "Find cafes"}))["result"]
    while True:
        msg = await recv(phone)
        if msg.get("method") == "helpers.update" and msg["params"]["helpers"]:
            break
    seen = check("helpers.update", msg)["params"]
    assert seen["conversation_id"] == conv
    assert seen["helpers"] == [{"id": "sa-1", "goal": "Find three cafes", "status": "running", "tools": 2,
                                "last_tool": "web_search", "model": "qwen/qwen3.8-flash", "started_at": 1700000000,
                                "can_steer": True}]
    steered = check("helpers.steer.result", await call(phone, "3", "helpers.steer",
                                                       {"conversation_id": conv, "helper_id": "sa-1", "text": "Only vegan ones"}))
    assert steered["result"] == {"queued": True}
    assert [p for name, p in fake.calls if name == "subagent.steer"][0]["session_id"] == "live-scout-1"
    stopped = check("helpers.stop.result", await call(phone, "4", "helpers.stop", {"conversation_id": conv, "helper_id": "sa-1"}))
    assert stopped["result"] == {"stopped": True}
    fake.finish.set()
    await until_done(phone, sent["turn_id"])
    while True:
        msg = await recv(phone)
        if msg.get("method") == "helpers.update":
            assert msg["params"]["helpers"] == []
            break
    hermes_chat = (await call(phone, "5", "chat.send", {"text": "Hi"}))["result"]
    nope = await call(phone, "6", "helpers.stop", {"conversation_id": hermes_chat["conversation_id"], "helper_id": "sa-1"})
    assert nope["error"]["code"] == m.NOT_FOUND
