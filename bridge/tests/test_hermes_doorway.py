"""The doorway to Hermes's other backend (spec/README.md §18): a paired device talks to a fake `hermes serve` through
the bridge. The fake behaves like the real one where it matters: it checks params before running a method (an
unknown parameter is refused with 4000), streams events, and asks questions of its own."""

import asyncio
import json
from pathlib import Path

import pytest
import websockets

from talaria_bridge.agents import load_agents
from talaria_bridge.hermes_check import check_serve
from talaria_bridge.hermes_serve import ALLOWED, PROBE_KEY, HermesBackend, filter_models
from talaria_bridge.protocol import keys
from talaria_bridge.protocol import messages as m
from talaria_bridge.registry import Registry
from talaria_bridge.server import BridgeServer, ServerSettings

from conftest import Bridge, check, paired_device
from test_server_ops import notification, request

TOKEN = "s" * 43
ALLOWED_MODELS = {"qwen/qwen3.8-flash", "openai/gpt-audio-mini"}


class FakeServe:
    def __init__(self, missing: tuple[str, ...] = ()):
        self.methods = (set(ALLOWED) - set(missing)) | {"cli.exec", "config.set", "client.capabilities"}
        self.calls: list[tuple[str, dict]] = []
        self.answers: list[dict] = []  # answers to our questions
        self.advertised = False
        self.conns: list = []

    async def process_request(self, connection, request):
        if f"token={TOKEN}" not in request.path:
            return connection.respond(403, "bad token")

    async def handler(self, ws):
        self.conns.append(ws)
        await ws.send(json.dumps({"jsonrpc": "2.0", "method": "event",
                                  "params": {"type": "gateway.ready", "payload": {"version": "0.21.5"}}}) + "\n")
        async for raw in ws:
            for line in raw.splitlines():
                msg = json.loads(line)
                if "method" not in msg:
                    self.answers.append(msg)
                    continue
                await ws.send(json.dumps(self.answer(msg)) + "\n")
                if msg["method"] == "prompt.submit" and PROBE_KEY not in msg["params"]:
                    sid = msg["params"].get("session_id", "")
                    for kind, payload in (("message.delta", {"text": "Hi"}), ("message.complete", {"text": "Hi"})):
                        await ws.send(json.dumps({"jsonrpc": "2.0", "method": "event",
                                                  "params": {"type": kind, "session_id": sid, "payload": payload}}) + "\n")

    def answer(self, msg: dict) -> dict:
        method, params = msg["method"], msg.get("params") or {}
        if method not in self.methods:
            return {"jsonrpc": "2.0", "id": msg["id"], "error": {"code": -32601, "message": f"unknown method: {method}"}}
        if PROBE_KEY in params:
            return {"jsonrpc": "2.0", "id": msg["id"], "error": {"code": 4000, "message": f"invalid params for {method}"}}
        self.calls.append((method, params))
        if method == "client.capabilities":
            self.advertised = params.get("server_requests") is True
            result = {"server_requests": ["approval", "clarify", "sudo", "secret"]}
        elif method == "profiles.list":
            result = {"profiles": [{"name": "default", "model": "qwen/qwen3.8-flash"}, {"name": "scout"}]}
        elif method == "groups.list":
            result = {"rooms": [], "next_offset": None}
        elif method == "session.list":
            result = {"sessions": [{"id": "s1", "title": "Hello"}]}
        elif method == "model.options":
            result = {"provider": "openrouter", "model": "qwen/qwen3.8-flash", "providers": [
                {"slug": "openrouter", "models": ["anthropic/claude-sonnet-5.5", "qwen/qwen3.8-flash"]},
                {"slug": "nous", "models": ["hermes-5"], "authenticated": False}]}
        elif method == "session.create":
            result = {"session_id": "live1", "stored_session_id": "20261006_x"}
        elif method == "session.list" and params.get("limit") == 9999:
            result = {"sessions": [{"id": "x" * 2_000_000}]}
        elif method == "groups.state":
            return {"jsonrpc": "2.0", "id": msg["id"], "error": {"code": 5110, "message": "room not found"}}
        else:
            result = {"ok": True}
        return {"jsonrpc": "2.0", "id": msg["id"], "result": result}

    async def ask(self, method: str, params: dict, rid: str = "srq-1") -> None:
        await self.conns[-1].send(json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params}) + "\n")

    async def event(self, kind: str, payload: dict) -> None:
        await self.conns[-1].send(json.dumps({"jsonrpc": "2.0", "method": "event",
                                              "params": {"type": kind, "payload": payload}}) + "\n")


async def allowed_models():
    return ALLOWED_MODELS


async def start_fake(fake: FakeServe):
    server = await websockets.serve(fake.handler, "127.0.0.1", 0, process_request=fake.process_request)
    return server, f"ws://127.0.0.1:{server.sockets[0].getsockname()[1]}/api/ws"


@pytest.fixture
async def doorway(tmp_path: Path, settings: ServerSettings):
    fake = FakeServe(missing=("session.steer",))
    server, url = await start_fake(fake)
    backend = HermesBackend(url, TOKEN, allowed_models=allowed_models, backoff=(0.05, 0.1))
    registry = Registry(tmp_path / "bridge.db")
    bridge = BridgeServer(registry, keys.generate_key(), settings, hermes=backend)
    async with bridge.serve() as ws_server:
        port = ws_server.sockets[0].getsockname()[1]
        for _ in range(100):
            if backend.connected:
                break
            await asyncio.sleep(0.02)
        yield Bridge(bridge, registry, f"ws://127.0.0.1:{port}/tnp"), fake, backend
    registry.close()
    server.close()
    await server.wait_closed()


async def phone_of(bridge: Bridge):
    device = await paired_device(bridge)
    return await device.authenticate(bridge.url)


async def test_capabilities_list_only_allowlisted_methods_this_hermes_has(doorway):
    bridge, fake, backend = doorway
    phone = await phone_of(bridge)
    caps = check("hermes.capabilities.result", await request(phone, "1", "hermes.capabilities", {}))["result"]
    assert caps["connected"] is True and caps["version"] == "0.21.5"
    assert "profiles.list" in caps["methods"] and "prompt.submit" in caps["methods"]
    assert "session.steer" not in caps["methods"]  # this Hermes lacks it: hidden
    assert "cli.exec" not in caps["methods"] and "config.set" not in caps["methods"]  # it has them; never offered
    assert caps["server_requests"] == ["approval", "clarify"] and caps["open_requests"] == []
    assert fake.advertised  # the bridge told Hermes it answers questions
    assert not any(PROBE_KEY in p for _, p in fake.calls)  # the probes never ran a method


async def test_calls_pass_through_and_risky_ones_are_refused(doorway):
    bridge, fake, _ = doorway
    phone = await phone_of(bridge)
    bots = check("hermes.call.result", await request(phone, "1", "hermes.call", {"method": "profiles.list"}))
    assert [b["name"] for b in bots["result"]["result"]["profiles"]] == ["default", "scout"]

    refused = {
        "cli.exec": ({"command": "ls"}, m.METHOD_NOT_FOUND),
        "config.set": ({"key": "model.default", "value": "x"}, m.METHOD_NOT_FOUND),
        "session.steer": ({"session_id": "s", "text": "x"}, m.METHOD_NOT_FOUND),
        "cron.manage": ({"action": "create", "name": "x"}, m.INVALID_PARAMS),
        "skills.manage": ({"action": "install", "query": "x"}, m.INVALID_PARAMS),
        "prompt.submit": ({"session_id": "s", "text": "x", "truncate_before_user_ordinal": 0, "confirm_truncate": True},
                          m.INVALID_PARAMS),
        "session.create": ({"model": "anthropic/claude-sonnet-5.5"}, m.INVALID_PARAMS),
        "groups.send": ({"_hosted_task": True}, m.INVALID_PARAMS),
    }
    for i, (method, (params, code)) in enumerate(refused.items()):
        answer = await request(phone, f"r{i}", "hermes.call", {"method": method, "params": params})
        assert answer["error"]["code"] == code, (method, answer)
    called = {name for name, _ in fake.calls}
    assert not called & {"cli.exec", "config.set", "cron.manage", "skills.manage", "prompt.submit", "session.create",
                         "groups.send"}

    ok = await request(phone, "c1", "hermes.call", {"method": "cron.manage", "params": {"action": "list"}})
    assert "result" in ok
    hermes_said = await request(phone, "e1", "hermes.call", {"method": "groups.state", "params": {"room_id": "r"}})
    assert hermes_said["error"]["code"] == m.BACKEND_ERROR
    assert hermes_said["error"]["data"] == {"code": 5110, "message": "room not found"}


async def test_model_options_only_show_allowed_models(doorway):
    bridge, _, _ = doorway
    phone = await phone_of(bridge)
    opts = (await request(phone, "1", "hermes.call", {"method": "model.options"}))["result"]["result"]
    assert opts["providers"] == [{"slug": "openrouter", "models": ["qwen/qwen3.8-flash"]}]


async def test_events_reach_every_device(doorway):
    bridge, fake, _ = doorway
    phone = await phone_of(bridge)
    created = await request(phone, "1", "hermes.call", {"method": "session.create", "params": {"title": "Ask scout"}})
    sid = created["result"]["result"]["session_id"]
    await request(phone, "2", "hermes.call", {"method": "prompt.submit", "params": {"session_id": sid, "text": "Hi"}})
    first = await notification(phone, "hermes.event")
    second = await notification(phone, "hermes.event")
    assert (first["type"], first["session_id"], second["type"]) == ("message.delta", "live1", "message.complete")


async def test_approval_goes_to_devices_and_back(doorway):
    bridge, fake, backend = doorway
    phone = await phone_of(bridge)
    await fake.ask("approval", {"session_id": "live1", "command": "rm -rf build", "description": "clean"})
    asked = await notification(phone, "hermes.request")
    assert asked["method"] == "approval" and asked["params"]["command"] == "rm -rf build"
    caps = (await request(phone, "1", "hermes.capabilities", {}))["result"]
    assert [q["request_id"] for q in caps["open_requests"]] == [asked["request_id"]]

    bad = await request(phone, "2", "hermes.respond", {"request_id": asked["request_id"], "result": {"choice": "maybe"}})
    assert bad["error"]["code"] == m.INVALID_PARAMS
    good = check("hermes.respond.result", await request(phone, "3", "hermes.respond",
                                                       {"request_id": asked["request_id"], "result": {"choice": "once"}}))
    assert good["result"] == {}
    done = await notification(phone, "hermes.request.done")
    assert done == {"request_id": asked["request_id"], "reason": "answered"}
    for _ in range(50):
        if fake.answers:
            break
        await asyncio.sleep(0.02)
    assert fake.answers == [{"jsonrpc": "2.0", "id": "srq-1", "result": {"choice": "once"}}]
    again = await request(phone, "4", "hermes.respond", {"request_id": asked["request_id"], "result": {"choice": "deny"}})
    assert again["error"]["code"] == m.NOT_FOUND


async def test_secrets_and_sudo_are_declined_and_withdrawn_questions_close(doorway):
    bridge, fake, _ = doorway
    phone = await phone_of(bridge)
    await fake.ask("sudo", {"prompt": "password"}, rid="srq-s")
    await fake.ask("secret", {"name": "GITHUB_TOKEN"}, rid="srq-t")
    for _ in range(50):
        if len(fake.answers) == 2:
            break
        await asyncio.sleep(0.02)
    assert [(a["id"], a["error"]["code"]) for a in fake.answers] == [("srq-s", -32601), ("srq-t", -32601)]

    await fake.ask("clarify", {"questions": [{"id": "q1", "text": "Which repo?"}]}, rid="srq-c")
    asked = await notification(phone, "hermes.request")
    await fake.event("request.cancel", {"id": "srq-c", "method": "clarify", "reason": "timeout"})
    assert await notification(phone, "hermes.request.done") == {"request_id": asked["request_id"], "reason": "withdrawn"}


async def test_the_doorway_reconnects_and_says_so(doorway):
    bridge, fake, backend = doorway
    phone = await phone_of(bridge)
    await fake.ask("approval", {"command": "ls"})
    asked = await notification(phone, "hermes.request")
    await fake.conns[-1].close()
    assert await notification(phone, "hermes.request.done") == {"request_id": asked["request_id"], "reason": "disconnected"}
    down = await notification(phone, "hermes.changed")
    assert down["connected"] is False and down["methods"] == []
    gone = await request(phone, "1", "hermes.call", {"method": "profiles.list"})
    assert gone["error"]["code"] in (m.AGENT_UNAVAILABLE, m.METHOD_NOT_FOUND)
    up = await notification(phone, "hermes.changed")
    assert up["connected"] is True and "profiles.list" in up["methods"]


async def test_a_too_big_answer_is_refused_not_sent(doorway, monkeypatch):
    import talaria_bridge.hermes_serve as hs_mod
    bridge, _, _ = doorway
    monkeypatch.setattr(hs_mod, "FRAME_ROOM", 50)
    phone = await phone_of(bridge)
    big = await request(phone, "1", "hermes.call", {"method": "session.list", "params": {"limit": 5}})
    assert big["error"]["code"] == m.CONFLICT


async def test_without_a_doorway_the_methods_say_so(bridge):
    phone = await phone_of(bridge)
    answer = await request(phone, "1", "hermes.capabilities", {})
    assert answer["error"]["code"] == m.METHOD_NOT_FOUND


async def test_the_daily_check_names_missing_methods_and_a_dead_doorway(doorway):
    _, fake, backend = doorway
    findings = await check_serve(backend)
    assert [f.problem for f in findings if not f.ok] == ["gone in this Hermes, so hidden on the phone: session.steer"]
    assert {f.call for f in findings} >= {"hermes serve profiles.list", "hermes serve groups.list", "hermes serve session.list"}
    await fake.conns[-1].close()
    for _ in range(50):
        if not backend.connected:
            break
        await asyncio.sleep(0.01)
    if not backend.connected:
        findings = await check_serve(backend)
        assert findings[0].call == "hermes serve" and "isn't connected" in findings[0].problem


def test_agents_json_accepts_only_a_local_hermes_serve(tmp_path: Path):
    def load(entry: dict):
        (tmp_path / "agents.json").write_text(json.dumps({"agents": [
            {"id": "hermes", "health_url": "http://127.0.0.1:8642/health", **entry}]}))
        return load_agents(tmp_path / "agents.json")[0]

    agent = load({"serve_url": "ws://127.0.0.1:9119/api/ws", "serve_key_file": "/etc/talaria/hermes-serve.key"})
    assert agent.serve_url == "ws://127.0.0.1:9119/api/ws" and agent.serve_key_file == "/etc/talaria/hermes-serve.key"
    for bad in ({"serve_url": "ws://srv.example.ts.net:9119/api/ws", "serve_key_file": "/k"},
                {"serve_url": "wss://127.0.0.1:9119/api/ws", "serve_key_file": "/k"},
                {"serve_url": "ws://127.0.0.1/api/ws", "serve_key_file": "/k"},
                {"serve_url": "ws://127.0.0.1:9119/api/ws", "serve_key_file": "relative.key"},
                {"serve_url": "ws://127.0.0.1:9119/api/ws"}):
        with pytest.raises(ValueError):
            load(bad)
    assert load({}).serve_url is None


def test_filter_models_keeps_shape_and_drops_unauthenticated():
    out = filter_models({"providers": [{"slug": "openrouter", "models": ["a", "b"]}, {"slug": "x", "models": ["c"],
                                                                                        "authenticated": False}]}, {"b"})
    assert out == {"providers": [{"slug": "openrouter", "models": ["b"]}]}
