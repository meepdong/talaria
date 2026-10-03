"""M2: the chat proxy against a fake Hermes API server (spec/README.md §9)."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path

import httpx
import pytest
from conftest import Bridge, check, paired_device

from talaria_bridge.agents import AgentConfig, load_agents
from talaria_bridge.chat import ChatService, ChatStore, history_messages
from talaria_bridge.cli import make_chat
from talaria_bridge.hermes import HermesClient, parse_sse
from talaria_bridge.protocol import keys
from talaria_bridge.protocol import messages as m
from talaria_bridge.registry import Registry
from talaria_bridge.server import BridgeServer, ServerSettings

KEY = "test-key"


def sse(name: str, payload: dict) -> bytes:
    return f"event: {name}\ndata: {json.dumps(payload)}\n\n".encode()


class FakeHermes:
    """The parts of the Hermes API server the bridge uses. A turn streams `script`; when `hold`
    is set it pauses after the first delta until released or stopped."""

    def __init__(self):
        self.sessions: dict[str, dict] = {}
        self.script = [
            ("assistant.delta", {"delta": "Hel"}),
            ("tool.started", {"tool_name": "web_search", "preview": "weather"}),
            ("tool.completed", {"tool_name": "web_search", "preview": "sunny"}),
            ("assistant.delta", {"delta": "lo"}),
        ]
        self.final = "Hello"
        self.hold = False
        self.release = asyncio.Event()
        self.stopped: list[str] = []
        self.runs = 0
        self.down = False

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handle)

    async def handle(self, req: httpx.Request) -> httpx.Response:
        if self.down:
            raise httpx.ConnectError("connection refused", request=req)
        if req.headers.get("authorization") != f"Bearer {KEY}":
            return httpx.Response(401, json={"error": {"message": "bad key", "code": "unauthorized"}})
        path, method = req.url.path, req.method
        if method == "POST" and path == "/api/sessions":
            body = json.loads(req.content)
            self.sessions[body["id"]] = {"title": body.get("title"), "messages": []}
            return httpx.Response(201, json={"object": "hermes.session", "session": {"id": body["id"]}})
        if path.startswith("/v1/runs/") and path.endswith("/stop"):
            self.stopped.append(path.split("/")[3])
            self.release.set()
            return httpx.Response(200, json={"status": "stopping"})
        parts = path.split("/")  # ['', 'api', 'sessions', id, ...]
        session = self.sessions.get(parts[3]) if len(parts) > 3 else None
        if session is None:
            return httpx.Response(404, json={"error": {"message": "Session not found", "code": "session_not_found"}})
        if method == "PATCH":
            session["title"] = json.loads(req.content)["title"]
            return httpx.Response(200, json={"session": {"id": parts[3]}})
        if method == "DELETE":
            del self.sessions[parts[3]]
            return httpx.Response(200, json={"deleted": True})
        if path.endswith("/messages"):
            assert req.url.params["order"] == "latest"
            limit, offset = int(req.url.params["limit"]), int(req.url.params["offset"])
            rows = session["messages"]
            page = rows[max(0, len(rows) - offset - limit):len(rows) - offset]
            return httpx.Response(200, json={"object": "list", "data": page})
        if path.endswith("/chat/stream"):
            text = json.loads(req.content)["message"]
            return httpx.Response(200, headers={"content-type": "text/event-stream"},
                                  content=self.stream(session, text))
        return httpx.Response(404)

    async def stream(self, session: dict, text: str):
        self.runs += 1
        run_id = f"run_{self.runs}"
        session["messages"].append({"id": len(session["messages"]) + 1, "role": "user", "content": text,
                                    "timestamp": 1000 + len(session["messages"])})
        yield sse("run.started", {"run_id": run_id, "user_message": {"role": "user", "content": text}})
        yield b": keepalive\n\n"
        for i, (name, payload) in enumerate(self.script):
            yield sse(name, {"run_id": run_id, **payload})
            if i == 0 and self.hold:
                await self.release.wait()
                if run_id in self.stopped:
                    yield sse("assistant.completed", {"content": "Hel", "completed": False, "interrupted": True})
                    yield sse("run.cancelled", {"completed": False, "interrupted": True})
                    yield sse("done", {})
                    return
        session["messages"].append({"id": len(session["messages"]) + 1, "role": "assistant",
                                    "content": self.final, "timestamp": 1000 + len(session["messages"])})
        yield sse("assistant.completed", {"content": self.final, "completed": True})
        yield sse("run.completed", {"usage": {"input_tokens": 5, "output_tokens": 2, "total_tokens": 7},
                                    "runtime": {"provider": "openrouter", "model": "m-1", "route_source": "global"}})
        yield sse("done", {})


@pytest.fixture
async def chat_bridge(tmp_path: Path, settings: ServerSettings):
    hermes = FakeHermes()
    client = HermesClient("http://hermes.test", KEY, transport=hermes.transport())

    async def unused(msg: dict) -> None:
        pass

    chat = ChatService(ChatStore(tmp_path / "chat.db"), {"hermes": client}, unused)
    registry = Registry(tmp_path / "bridge.db")
    server = BridgeServer(registry, keys.generate_key(), settings, chat=chat)
    async with server.serve() as ws_server:
        port = ws_server.sockets[0].getsockname()[1]
        yield Bridge(server, registry, f"ws://127.0.0.1:{port}/tnp"), hermes
    chat.store.close()
    registry.close()


async def recv(ws, timeout: float = 5) -> dict:
    return m.decode(await asyncio.wait_for(ws.recv(), timeout))


async def call(ws, msg_id: str, method: str, params: dict | None = None) -> dict:
    """Send a request and return its reply, collecting notifications that arrive first."""
    await ws.send(m.encode(check(method, m.request(msg_id, method, params if params is not None else {}))))
    while True:
        msg = await recv(ws)
        if msg.get("id") == msg_id:
            return msg


async def until_done(ws, turn_id: str) -> list[dict]:
    events = []
    while True:
        msg = await recv(ws)
        if msg.get("method") in ("chat.started", "chat.delta", "chat.done"):
            check(msg["method"], msg)
            if msg["params"]["turn_id"] == turn_id:
                events.append(msg)
                if msg["method"] == "chat.done":
                    return events


async def connected(bridge: Bridge):
    device = await paired_device(bridge)
    return await device.authenticate(bridge.url)


async def test_send_streams_to_every_device(chat_bridge):
    bridge, hermes = chat_bridge
    phone, laptop = await connected(bridge), await connected(bridge)
    reply = check("chat.send.result", await call(phone, "c1", "chat.send", {"text": "Hi  there\nHermes"}))
    res = reply["result"]
    assert res["conversation_id"].startswith("c-") and res["title"] == "Hi there Hermes"
    assert hermes.sessions[f"talaria_{res['conversation_id'][2:]}"]["title"] == "Hi there Hermes"

    for ws in (phone, laptop):
        events = await until_done(ws, res["turn_id"])
        assert events[0]["method"] == "chat.started"
        assert events[0]["params"]["user_text"] == "Hi  there\nHermes"
        deltas = [e["params"] for e in events[1:-1]]
        assert [d["seq"] for d in deltas] == [1, 2, 3, 4]
        assert [d["kind"] for d in deltas] == ["text", "tool_progress", "tool_progress", "text"]
        assert deltas[1]["tool"] == {"name": "web_search", "state": "started", "preview": "weather"}
        done = events[-1]["params"]
        assert done == {"conversation_id": res["conversation_id"], "turn_id": res["turn_id"], "seq": 5,
                        "status": "completed", "text": "Hello",
                        "usage": {"input_tokens": 5, "output_tokens": 2, "total_tokens": 7},
                        "runtime": {"provider": "openrouter", "model": "m-1"}}
    await phone.close()
    await laptop.close()


async def test_follow_up_list_and_history(chat_bridge):
    bridge, hermes = chat_bridge
    ws = await connected(bridge)
    first = (await call(ws, "c1", "chat.send", {"text": "One"}))["result"]
    await until_done(ws, first["turn_id"])
    second = (await call(ws, "c2", "chat.send", {"text": "Two", "conversation_id": first["conversation_id"]}))["result"]
    assert second["conversation_id"] == first["conversation_id"]
    await until_done(ws, second["turn_id"])

    listed = check("conversations.list.result", await call(ws, "l1", "conversations.list"))["result"]
    assert [c["conversation_id"] for c in listed["conversations"]] == [first["conversation_id"]]
    assert listed["conversations"][0]["last_message"] == {"role": "assistant", "text": "Hello"}
    assert "active_turn_id" not in listed["conversations"][0]

    page = check("chat.history.result", await call(ws, "h1", "chat.history", {
        "conversation_id": first["conversation_id"], "limit": 2}))["result"]
    assert [(x["role"], x["text"]) for x in page["messages"]] == [("user", "Two"), ("assistant", "Hello")]
    assert page["next_before"] == "2"
    older = (await call(ws, "h2", "chat.history", {
        "conversation_id": first["conversation_id"], "limit": 2, "before": page["next_before"]}))["result"]
    assert [x["text"] for x in older["messages"]] == ["One", "Hello"]
    last = (await call(ws, "h3", "chat.history", {
        "conversation_id": first["conversation_id"], "limit": 2, "before": "4"}))["result"]
    assert last == {"messages": [], "next_before": None}
    await ws.close()


async def test_busy_cancel_and_catch_up(chat_bridge):
    bridge, hermes = chat_bridge
    hermes.hold = True
    ws = await connected(bridge)
    res = (await call(ws, "c1", "chat.send", {"text": "Long job"}))["result"]
    # wait for the first text delta, so the run id is known
    while True:
        msg = await recv(ws)
        if msg.get("method") == "chat.delta":
            break

    busy = await call(ws, "c2", "chat.send", {"text": "Again", "conversation_id": res["conversation_id"]})
    assert busy["error"]["code"] == m.CONFLICT
    listed = (await call(ws, "l1", "conversations.list"))["result"]["conversations"]
    assert listed[0]["active_turn_id"] == res["turn_id"]

    # a second device catches up from the snapshot
    other = await connected(bridge)
    snap = check("chat.turn.get.result", await call(other, "g1", "chat.turn.get", {"turn_id": res["turn_id"]}))
    turn = snap["result"]["turn"]
    assert (turn["status"], turn["text"], turn["seq"], turn["user_text"]) == ("running", "Hel", 1, "Long job")

    stop = check("chat.cancel.result", await call(ws, "x1", "chat.cancel", {"turn_id": res["turn_id"]}))
    assert stop["result"] == {"turn_id": res["turn_id"], "status": "stopping"}
    assert hermes.stopped == ["run_1"]
    done = (await until_done(ws, res["turn_id"]))[-1]["params"]
    assert (done["status"], done["text"]) == ("cancelled", "Hel")
    again = await call(ws, "x2", "chat.cancel", {"turn_id": res["turn_id"]})
    assert again["result"]["status"] == "cancelled"
    await ws.close()
    await other.close()


async def test_retry_with_client_msg_id_sends_once(chat_bridge):
    bridge, hermes = chat_bridge
    ws = await connected(bridge)
    a = (await call(ws, "c1", "chat.send", {"text": "Once", "client_msg_id": "m-1"}))["result"]
    await until_done(ws, a["turn_id"])
    b = (await call(ws, "c2", "chat.send", {"text": "Once", "client_msg_id": "m-1"}))["result"]
    assert a == b and hermes.runs == 1
    await ws.close()


async def test_errors(chat_bridge):
    bridge, hermes = chat_bridge
    ws = await connected(bridge)
    cases = [
        ("chat.send", {"text": ""}, m.INVALID_PARAMS),
        ("chat.send", {"text": "x", "conversation_id": "c-nope"}, m.NOT_FOUND),
        ("chat.send", {"text": "x", "agent_id": "scout"}, m.AGENT_UNAVAILABLE),
        ("chat.turn.get", {"turn_id": "t-nope"}, m.NOT_FOUND),
        ("chat.history", {"conversation_id": "c-nope"}, m.NOT_FOUND),
    ]
    for i, (method, params, code) in enumerate(cases):
        await ws.send(m.encode(m.request(f"e{i}", method, params)))
        reply = await recv(ws)
        assert reply["error"]["code"] == code, (method, params, reply)
    await ws.send(m.encode(m.request("a1", "chat.send", {"text": "x", "attachments": [{"blob_id": "b-1"}]})))
    assert (await recv(ws))["error"]["code"] == m.MODALITY_UNSUPPORTED

    hermes.down = True
    reply = await call(ws, "d1", "chat.send", {"text": "Anyone there?"})
    assert reply["error"]["code"] == m.AGENT_UNAVAILABLE
    assert (await call(ws, "d2", "conversations.list"))["result"] == {"conversations": []}
    await ws.close()


async def test_hermes_drops_mid_turn(chat_bridge):
    bridge, hermes = chat_bridge
    ws = await connected(bridge)
    first = (await call(ws, "c1", "chat.send", {"text": "One"}))["result"]
    await until_done(ws, first["turn_id"])
    hermes.down = True
    turn = (await call(ws, "c2", "chat.send", {"text": "Two", "conversation_id": first["conversation_id"]}))["result"]
    done = (await until_done(ws, turn["turn_id"]))[-1]["params"]
    assert done["status"] == "failed" and done["error"].startswith("Agent unavailable")
    await ws.close()


async def test_rename_and_delete(chat_bridge):
    bridge, hermes = chat_bridge
    ws = await connected(bridge)
    res = (await call(ws, "c1", "chat.send", {"text": "Name me"}))["result"]
    await until_done(ws, res["turn_id"])
    conv = res["conversation_id"]
    renamed = check("conversations.result", await call(ws, "r1", "conversations.rename",
                                                       {"conversation_id": conv, "title": " Trip  plans "}))
    assert renamed["result"] == {"conversation_id": conv, "title": "Trip plans"}
    assert hermes.sessions[f"talaria_{conv[2:]}"]["title"] == "Trip plans"
    gone = check("conversations.result", await call(ws, "d1", "conversations.delete", {"conversation_id": conv}))
    assert gone["result"] == {"conversation_id": conv, "deleted": True}
    assert hermes.sessions == {}
    assert (await call(ws, "l1", "conversations.list"))["result"] == {"conversations": []}
    await ws.close()


async def test_chat_needs_ready(chat_bridge):
    bridge, hermes = chat_bridge
    device = await paired_device(bridge)
    ws, hello = await device.open(bridge.url)
    await ws.send(m.encode(device.auth_request(hello)))
    check("auth.ok", await recv(ws))
    await ws.send(m.encode(m.request("c1", "chat.send", {"text": "Too early"})))
    assert (await recv(ws))["error"]["code"] == m.INVALID_REQUEST
    assert hermes.sessions == {}
    await ws.close()


async def test_no_chat_configured(bridge: Bridge):
    ws = await connected(bridge)
    reply = await call(ws, "c1", "chat.send", {"text": "Hello?"})
    assert reply["error"]["code"] == m.AGENT_UNAVAILABLE
    await ws.close()


# units

def test_history_messages():
    rows = [
        {"id": 3, "role": "assistant", "content": None, "timestamp": 12,
         "tool_calls": [{"function": {"name": "web_search"}}]},
        {"id": 2, "role": "user", "content": [{"type": "text", "text": "Look"}, {"type": "image_url"}], "timestamp": 11},
        {"id": 4, "role": "tool", "content": "results", "timestamp": 13},
        {"id": 5, "role": "assistant", "content": "Found it", "timestamp": 14.5},
        {"id": 6, "role": "user", "content": "hidden", "timestamp": 15, "display_kind": "hidden"},
    ]
    assert history_messages(rows) == [
        {"id": "2", "role": "user", "text": "Look\n[image]", "ts": 11},
        {"id": "5", "role": "assistant", "text": "Found it", "ts": 14, "tools": ["web_search"]},
    ]


async def test_parse_sse():
    async def lines():
        for line in [": keepalive", "", "event: a", 'data: {"x": 1}', "", "data: [1]", "",
                     "event: b", "data: {", 'data: "y": 2}', ""]:
            yield line
    assert [e async for e in parse_sse(lines())] == [("a", {"x": 1}), ("b", {"y": 2})]


def test_load_agents_with_chat(tmp_path: Path):
    path = tmp_path / "agents.json"
    key_file = tmp_path / "hermes.key"
    path.write_text(json.dumps({"agents": [{"id": "hermes", "health_url": "http://127.0.0.1:9119/api/status",
                                            "api_url": "http://127.0.0.1:8642", "api_key_file": str(key_file)}]}))
    agents = load_agents(path)
    assert agents == [AgentConfig("hermes", "hermes", "http://127.0.0.1:9119/api/status",
                                  "http://127.0.0.1:8642", str(key_file))]
    assert make_chat(tmp_path, agents) is None  # key file missing: chat stays off
    key_file.write_text("secret\n")
    chat = make_chat(tmp_path, agents)
    assert chat is not None and list(chat.agents) == ["hermes"]
    chat.store.close()
    asyncio.run(chat.agents["hermes"].close())

    path.write_text(json.dumps({"agents": [{"id": "hermes", "health_url": "http://x/h", "api_url": "http://x"}]}))
    with pytest.raises(ValueError):
        load_agents(path)
