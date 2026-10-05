"""M2: the chat proxy against a fake Hermes API server (spec/README.md §9)."""

from __future__ import annotations

import asyncio
import base64
import hashlib
import json
from pathlib import Path

import httpx
import pytest
from conftest import Bridge, check, paired_device

from talaria_bridge.agents import AgentConfig, load_agents
from talaria_bridge.blobs import BlobStore
from talaria_bridge.chat import ChatService, ChatStore, history_messages
from talaria_bridge.cli import make_chat
from talaria_bridge.hermes import HermesClient, parse_sse
from talaria_bridge.protocol import keys
from talaria_bridge.protocol import messages as m
from talaria_bridge.registry import Registry
from talaria_bridge.server import BridgeServer, ServerSettings
from talaria_bridge.todos import TodoStore

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
        self.messages: list = []  # every message a turn was sent with
        self.steered: list[tuple[str, str]] = []
        self.approvals: list[tuple[str, dict]] = []
        self.locks: dict[str, dict] = {}
        self.deleted: list[str] = []

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
        if path.startswith("/v1/runs/") and path.endswith("/steer"):
            self.steered.append((path.split("/")[3], json.loads(req.content)["input"]))
            return httpx.Response(200, json={"object": "hermes.run.steer", "accepted": True})
        if path.startswith("/v1/runs/") and path.endswith("/approval"):
            if not self.hold or self.release.is_set():
                return httpx.Response(409, json={"error": {"message": "Run has no pending approval",
                                                           "code": "approval_not_pending"}})
            self.approvals.append((path.split("/")[3], json.loads(req.content)))
            self.release.set()
            return httpx.Response(200, json={"object": "hermes.run.approval_response"})
        if path == "/api/model/options":
            return httpx.Response(200, json={"provider": "openrouter", "model": "m-1", "providers": [
                {"slug": "openrouter", "name": "OpenRouter", "authenticated": True, "models": ["m-1", "m-2"]},
                {"slug": "anthropic", "name": "Anthropic", "authenticated": False, "models": []}]})
        if path.startswith("/v1/runs/") and path.endswith("/stop"):
            self.stopped.append(path.split("/")[3])
            self.release.set()
            return httpx.Response(200, json={"status": "stopping"})
        parts = path.split("/")  # ['', 'api', 'sessions', id, ...]
        session = self.sessions.get(parts[3]) if len(parts) > 3 else None
        if session is None:
            return httpx.Response(404, json={"error": {"message": "Session not found", "code": "session_not_found"}})
        if method == "PATCH":
            session.update(json.loads(req.content))
            return httpx.Response(200, json={"session": {"id": parts[3]}})
        if method == "DELETE":
            self.deleted.append(parts[3])
            del self.sessions[parts[3]]
            return httpx.Response(200, json={"deleted": True})
        if path.endswith("/model"):
            body = json.loads(req.content)
            if body["model"] == "nope":
                return httpx.Response(400, json={"error": {"message": "unknown model", "code": "model_lock_unroutable"}})
            self.locks[parts[3]] = body
            return httpx.Response(200, json={"object": "hermes.session.model_lock"})
        if method == "GET" and len(parts) == 4:
            return httpx.Response(200, json={"session": {"id": parts[3], "message_count": len(session["messages"]),
                                                         "tool_call_count": 1, "input_tokens": 50, "output_tokens": 9,
                                                         "estimated_cost_usd": 0.0123}})
        if path.endswith("/messages"):
            assert req.url.params["order"] == "latest"
            limit, offset = int(req.url.params["limit"]), int(req.url.params["offset"])
            rows = session["messages"]
            page = rows[max(0, len(rows) - offset - limit):len(rows) - offset]
            return httpx.Response(200, json={"object": "list", "data": page})
        if path.endswith("/chat/stream"):
            text = json.loads(req.content)["message"]
            self.messages.append(text)
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

    chat = ChatService(ChatStore(tmp_path / "chat.db"), {"hermes": client}, unused,
                       blobs=BlobStore(tmp_path / "blobs"), inboxes={"hermes": tmp_path / "inbox"},
                       todos=TodoStore(tmp_path / "chat.db"))
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

    waiting = check("chat.send.result", await call(
        ws, "c2", "chat.send", {"text": "Again", "conversation_id": res["conversation_id"]}))["result"]
    assert waiting["queued"] is True
    listed = (await call(ws, "l1", "conversations.list"))["result"]["conversations"]
    assert listed[0]["active_turn_id"] == res["turn_id"]
    assert listed[0]["queued_turn_ids"] == [waiting["turn_id"]]
    # a queued turn that is cancelled ends without starting
    dropped = await call(ws, "x0", "chat.cancel", {"turn_id": waiting["turn_id"]})
    assert dropped["result"] == {"turn_id": waiting["turn_id"], "status": "cancelled"}

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
    started = (await until_done(ws, a["turn_id"]))[0]["params"]
    assert started["client_msg_id"] == "m-1"
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
    assert (await recv(ws))["error"]["code"] == m.NOT_FOUND

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


async def test_pin_and_hide(chat_bridge):
    bridge, hermes = chat_bridge
    phone, laptop = await connected(bridge), await connected(bridge)
    res = (await call(phone, "c1", "chat.send", {"text": "Keep this"}))["result"]
    await until_done(phone, res["turn_id"])
    conv = res["conversation_id"]
    pinned = check("conversations.result", await call(phone, "p1", "conversations.pin",
                                                      {"conversation_id": conv, "pinned": True}))
    assert pinned["result"] == {"conversation_id": conv, "pinned": True}
    assert hermes.sessions[f"talaria_{conv[2:]}"]["pinned"] is True
    listed = check("conversations.list.result", await call(phone, "l1", "conversations.list"))["result"]
    assert listed["conversations"][0]["pinned"] is True

    before = (await call(phone, "h1", "chat.history", {"conversation_id": conv}))["result"]["messages"]
    question = next(x for x in before if x["role"] == "user")
    check("chat.hide", m.request("x", "chat.hide", {"conversation_id": conv, "message_ids": [question["id"]]}))
    hid = check("chat.hide.result", await call(phone, "x1", "chat.hide",
                                               {"conversation_id": conv, "message_ids": [question["id"]]}))
    assert hid["result"] == {"conversation_id": conv, "message_ids": [question["id"]]}
    while (msg := await recv(laptop)).get("method") != "chat.hidden":
        pass
    assert check("chat.hidden", msg)["params"] == {"conversation_id": conv, "message_ids": [question["id"]]}
    after = (await call(laptop, "h2", "chat.history", {"conversation_id": conv}))["result"]["messages"]
    assert [x["id"] for x in after] == [x["id"] for x in before if x["id"] != question["id"]]
    assert len(hermes.sessions[f"talaria_{conv[2:]}"]["messages"]) >= 2, "Hermes keeps the hidden message"
    await phone.close()
    await laptop.close()


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
        {"id": 2, "role": "user", "content": [
            {"type": "text", "text": "Look\n\nAttached file: /in/c-1/b-0123456789abcdef01234567-a (1).pdf (application/pdf, 12 bytes)"},
            {"type": "image_url", "image_url": {"url": "data:image/png;base64,AA=="}}], "timestamp": 11},
        {"id": 4, "role": "tool", "content": "results", "timestamp": 13},
        {"id": 5, "role": "assistant", "content": "Found it", "timestamp": 14.5},
        {"id": 6, "role": "user", "content": "hidden", "timestamp": 15, "display_kind": "hidden"},
    ]
    assert history_messages(rows) == [
        {"id": "2", "role": "user", "text": "Look", "ts": 11, "attachments": [
            {"kind": "image", "name": "Photo", "mime": "image/png"},
            {"kind": "file", "name": "a (1).pdf", "mime": "application/pdf", "size": 12}]},
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


async def upload(ws, name: str, mime: str, data: bytes, chunk: int | None = None) -> dict:
    begin = check("blob.begin.result", await call(ws, "b0", "blob.begin", {
        "name": name, "mime": mime, "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}))["result"]
    step = chunk or begin["chunk_bytes"]
    for offset in range(0, len(data), step):
        put = check("blob.put.result", await call(ws, f"b{offset}", "blob.put", {
            "blob_id": begin["blob_id"], "offset": offset,
            "data": base64.b64encode(data[offset:offset + step]).decode()}))["result"]
        assert put["received"] == min(len(data), offset + step)
    return check("blob.commit.result", await call(ws, "bc", "blob.commit", {"blob_id": begin["blob_id"]}))["result"]


async def test_photo_and_file_reach_the_agent(chat_bridge, tmp_path: Path):
    bridge, hermes = chat_bridge
    ws = await connected(bridge)
    photo = b"\xff\xd8jpeg" * 100
    pdf = b"%PDF-1.7 report" * 70_000  # about 1 MiB: three chunks
    image = await upload(ws, "IMG_1.jpg", "image/jpeg", photo, chunk=500)
    assert image["kind"] == "image" and image["size"] == len(photo)
    doc = await upload(ws, "../Q3 report.pdf", "application/pdf", pdf)
    assert doc == {"blob_id": doc["blob_id"], "kind": "file", "name": "../Q3 report.pdf",
                   "mime": "application/pdf", "size": len(pdf)}

    res = check("chat.send.result", await call(ws, "s1", "chat.send", {
        "text": "", "attachments": [{"blob_id": image["blob_id"]}, {"blob_id": doc["blob_id"]}]}))["result"]
    assert res["title"] == "IMG_1.jpg"
    events = await until_done(ws, res["turn_id"])
    started = events[0]["params"]
    assert started["user_text"] == ""
    assert started["attachments"] == [
        {"kind": "image", "name": "IMG_1.jpg", "mime": "image/jpeg", "size": len(photo)},
        {"kind": "file", "name": "../Q3 report.pdf", "mime": "application/pdf", "size": len(pdf)}]

    sent = hermes.messages[-1]
    folder = tmp_path / "inbox" / res["conversation_id"]
    saved = folder / f"{doc['blob_id']}-Q3 report.pdf"
    saved_photo = folder / f"{image['blob_id']}-IMG_1.jpg"
    assert saved.read_bytes() == pdf
    assert saved_photo.read_bytes() == photo, "a photo is a file too, so the agent's tools can use it"
    assert sent == [
        {"type": "text", "text": f"Attached file: {saved_photo} (image/jpeg, {len(photo)} bytes)\n\n"
                                 f"Attached file: {saved} (application/pdf, {len(pdf)} bytes)"},
        {"type": "input_image", "image_url": "data:image/jpeg;base64," + base64.b64encode(photo).decode()}]

    # each blob is sent once
    again = await call(ws, "s2", "chat.send", {"text": "again", "conversation_id": res["conversation_id"],
                                               "attachments": [{"blob_id": image["blob_id"]}]})
    assert again["error"]["code"] == m.NOT_FOUND
    listed = (await call(ws, "l1", "conversations.list"))["result"]["conversations"]
    assert listed[0]["last_message"]["role"] == "assistant"

    history = (await call(ws, "h1", "chat.history", {"conversation_id": res["conversation_id"]}))["result"]
    assert history["messages"][0]["text"] == ""
    assert history["messages"][0]["attachments"] == [
        {"kind": "image", "name": "IMG_1.jpg", "mime": "image/jpeg", "size": len(photo)},
        {"kind": "file", "name": "Q3 report.pdf", "mime": "application/pdf", "size": len(pdf)}], "each once, by name"
    await ws.close()



async def test_a_batch_of_photos_reaches_the_agent_as_files(chat_bridge, tmp_path: Path):
    bridge, hermes = chat_bridge
    ws = await connected(bridge)
    photos = [await upload(ws, f"IMG_{i}.jpg", "image/jpeg", b"\xff\xd8jpeg%d" % i) for i in range(129)]
    await ws.send(m.encode(m.request("s0", "chat.send", {"text": "x", "attachments": [{"blob_id": p["blob_id"]} for p in photos]})))
    assert (await recv(ws))["error"]["code"] == m.INVALID_PARAMS  # 129: the schema allows 128

    params = {"text": "Make these a PDF", "attachments": [{"blob_id": p["blob_id"]} for p in photos[:128]]}
    check("chat.send", m.request("s1", "chat.send", params))
    res = check("chat.send.result", await call(ws, "s1", "chat.send", params))["result"]
    events = await until_done(ws, res["turn_id"])
    assert len(check("chat.started", events[0])["params"]["attachments"]) == 128
    sent = hermes.messages[-1]
    assert isinstance(sent, str), "more than 10 photos: none inline"
    lines = sent.split("\n\n")
    assert lines[0] == "Make these a PDF" and len(lines) == 129
    first = tmp_path / "inbox" / res["conversation_id"] / f"{photos[0]['blob_id']}-IMG_0.jpg"
    assert lines[1] == f"Attached file: {first} (image/jpeg, {len(b'\xff\xd8jpeg0')} bytes)"
    assert first.read_bytes() == b"\xff\xd8jpeg0"

    # without an inbox the bridge can't hand them over, and says so
    bridge.server.chat.inboxes.clear()
    more = [await upload(ws, f"B_{i}.png", "image/png", b"png%d" % i) for i in range(11)]
    refused = await call(ws, "s2", "chat.send", {"text": "x", "attachments": [{"blob_id": p["blob_id"]} for p in more]})
    assert refused["error"]["code"] == m.INVALID_PARAMS
    ok = await call(ws, "s3", "chat.send", {"text": "x", "attachments": [{"blob_id": p["blob_id"]} for p in more[:10]]})
    await until_done(ws, ok["result"]["turn_id"])
    assert sum(1 for part in hermes.messages[-1] if part["type"] == "input_image") == 10
    await ws.close()

async def test_upload_errors(chat_bridge):
    bridge, hermes = chat_bridge
    ws = await connected(bridge)
    data = b"hello world"
    digest = hashlib.sha256(data).hexdigest()
    bad_begins = [
        {"name": "a", "mime": "text/plain", "size": 21 * 1024 * 1024, "sha256": digest},
        {"name": "a", "mime": "not a type", "size": 11, "sha256": digest},
        {"name": "a", "mime": "text/plain", "size": 11, "sha256": "ABC"},
    ]
    for i, params in enumerate(bad_begins):
        await ws.send(m.encode(m.request(f"x{i}", "blob.begin", params)))
        assert (await recv(ws))["error"]["code"] == m.INVALID_PARAMS

    blob = (await call(ws, "b1", "blob.begin", {"name": "a.txt", "mime": "text/plain", "size": 11, "sha256": digest}))
    blob_id = blob["result"]["blob_id"]
    wrong_offset = await call(ws, "p1", "blob.put", {"blob_id": blob_id, "offset": 3, "data": "aGVsbG8="})
    assert wrong_offset["error"]["code"] == m.CONFLICT
    not_b64 = await call(ws, "p2", "blob.put", {"blob_id": blob_id, "offset": 0, "data": "@@@@"})
    assert not_b64["error"]["code"] == m.INVALID_PARAMS
    await call(ws, "p3", "blob.put", {"blob_id": blob_id, "offset": 0, "data": base64.b64encode(b"hello").decode()})
    short = await call(ws, "c1", "blob.commit", {"blob_id": blob_id})
    assert short["error"]["code"] == m.INVALID_PARAMS  # 5 of 11 bytes, so the blob is dropped
    gone = await call(ws, "c2", "blob.commit", {"blob_id": blob_id})
    assert gone["error"]["code"] == m.NOT_FOUND

    unfinished = (await call(ws, "b2", "blob.begin", {"name": "a", "mime": "text/plain", "size": 11, "sha256": digest}))
    send = await call(ws, "s1", "chat.send", {"text": "x", "attachments": [{"blob_id": unfinished["result"]["blob_id"]}]})
    assert send["error"]["code"] == m.NOT_FOUND
    await ws.close()


async def test_files_need_an_inbox_and_a_failed_send_keeps_the_blob(chat_bridge):
    bridge, hermes = chat_bridge
    bridge.server.chat.inboxes.clear()
    ws = await connected(bridge)
    doc = await upload(ws, "notes.txt", "text/plain", b"notes")
    refused = await call(ws, "s1", "chat.send", {"text": "read this", "attachments": [{"blob_id": doc["blob_id"]}]})
    assert refused["error"]["code"] == m.MODALITY_UNSUPPORTED
    photo = await upload(ws, "p.png", "image/png", b"png")
    hermes.down = True
    failed = await call(ws, "s2", "chat.send", {"text": "look", "attachments": [{"blob_id": photo["blob_id"]}]})
    assert failed["error"]["code"] == m.AGENT_UNAVAILABLE
    hermes.down = False
    ok = await call(ws, "s3", "chat.send", {"text": "look", "attachments": [{"blob_id": photo["blob_id"]}]})
    await until_done(ws, ok["result"]["turn_id"])
    assert hermes.messages[-1][0] == {"type": "text", "text": "look"}
    await ws.close()


def test_blob_store_expires_and_clears_leftovers(tmp_path: Path):
    now = [0.0]
    (tmp_path / "blobs").mkdir()
    (tmp_path / "blobs" / "b-old").write_bytes(b"left over")
    store = BlobStore(tmp_path / "blobs", clock=lambda: now[0])
    assert list((tmp_path / "blobs").iterdir()) == []
    data = b"x"
    begin = store.begin({"name": "x", "mime": "text/plain", "size": 1, "sha256": hashlib.sha256(data).hexdigest()})
    store.put({"blob_id": begin["blob_id"], "offset": 0, "data": "eA=="})
    store.commit({"blob_id": begin["blob_id"]})
    now[0] = 3601
    with pytest.raises(Exception, match="Unknown"):
        store.take(begin["blob_id"])
    assert list((tmp_path / "blobs").iterdir()) == []


async def test_queued_turns_run_in_order(chat_bridge):
    bridge, hermes = chat_bridge
    hermes.hold = True
    ws = await connected(bridge)
    first = (await call(ws, "c1", "chat.send", {"text": "First"}))["result"]
    conv = first["conversation_id"]
    queued = []
    for i in range(5):
        r = (await call(ws, f"q{i}", "chat.send", {"text": f"Next {i}", "conversation_id": conv, "client_msg_id": f"m-{i}"}))
        queued.append(r["result"]["turn_id"])
    full = await call(ws, "q9", "chat.send", {"text": "Too many", "conversation_id": conv})
    assert full["error"]["code"] == m.CONFLICT
    snap = (await call(ws, "g1", "chat.turn.get", {"turn_id": queued[0]}))["result"]["turn"]
    assert snap["status"] == "queued"

    hermes.hold = False
    hermes.release.set()
    order, seen = [], set()
    while len(seen) < 6:
        msg = await recv(ws)
        method = msg.get("method")
        if method == "chat.queued":
            check(method, msg)
            assert msg["params"]["client_msg_id"] == f"m-{msg['params']['position'] - 1}"
        if method in ("chat.started", "chat.done"):
            order.append((method, msg["params"]["turn_id"]))
            if method == "chat.done":
                seen.add(msg["params"]["turn_id"])
    starts = [t for kind, t in order if kind == "chat.started"]
    assert starts == queued  # the first had started before the loop
    assert [m_ for m_ in hermes.messages] == ["First"] + [f"Next {i}" for i in range(5)]
    await ws.close()


async def test_models_steer_status_and_aside(chat_bridge):
    bridge, hermes = chat_bridge
    ws = await connected(bridge)
    models = check("agent.models.result", await call(ws, "m1", "agent.models"))["result"]
    assert models == {"agent_id": "hermes", "current": {"provider": "openrouter", "model": "m-1"},
                      "providers": [{"id": "openrouter", "name": "OpenRouter", "models": ["m-1", "m-2"]}]}

    # a new conversation starts on the chosen model
    pick = {"provider": "openrouter", "model": "m-2"}
    res = (await call(ws, "c1", "chat.send", {"text": "Hi", "model": pick}))["result"]
    await until_done(ws, res["turn_id"])
    session_id = "talaria_" + res["conversation_id"][2:]
    assert hermes.locks[session_id] == pick
    listed = check("conversations.list.result", await call(ws, "l1", "conversations.list"))["result"]
    assert listed["conversations"][0]["model"] == pick
    bad = await call(ws, "s0", "conversations.set_model", {"conversation_id": res["conversation_id"],
                                                          "model": {"provider": "openrouter", "model": "nope"}})
    assert bad["error"]["code"] == m.INVALID_PARAMS
    moved = check("conversations.set_model.result", await call(ws, "s1", "conversations.set_model", {
        "conversation_id": res["conversation_id"], "model": {"provider": "openrouter", "model": "m-1"}}))
    assert moved["result"]["model"]["model"] == "m-1"

    status = check("chat.status.result", await call(ws, "st", "chat.status", {"conversation_id": res["conversation_id"]}))
    assert status["result"] == {"conversation_id": res["conversation_id"], "queued": 0,
                                "model": {"provider": "openrouter", "model": "m-1"}, "messages": 2,
                                "tool_calls": 1, "input_tokens": 50, "output_tokens": 9, "cost_usd": 0.0123}

    # steer needs a running turn
    hermes.hold = True
    run = (await call(ws, "c2", "chat.send", {"text": "Long", "conversation_id": res["conversation_id"]}))["result"]
    while (await recv(ws)).get("method") != "chat.delta":
        pass
    steer = check("chat.steer.result", await call(ws, "t1", "chat.steer", {"turn_id": run["turn_id"], "text": "focus on X"}))
    assert steer["result"] == {"turn_id": run["turn_id"], "accepted": True}
    assert hermes.steered == [("run_2", "focus on X")]

    # an aside answers in a throwaway session while the turn keeps running
    hermes.final = "Side answer"
    aside = check("chat.aside.result", await call(ws, "a1", "chat.aside", {
        "conversation_id": res["conversation_id"], "text": "what did I ask first?"}))["result"]
    hermes.release.set()
    while True:
        msg = await recv(ws)
        if msg.get("method") == "chat.aside.done":
            check("chat.aside.done", msg)
            break
    assert msg["params"] == {"conversation_id": res["conversation_id"], "aside_id": aside["aside_id"],
                             "question": "what did I ask first?", "status": "completed", "text": "Side answer"}
    assert "Side question: what did I ask first?" in hermes.messages[-1] and "User: Hi" in hermes.messages[-1]
    assert hermes.deleted and hermes.deleted[-1].startswith("talaria_aside_")
    late = await call(ws, "t2", "chat.steer", {"turn_id": "t-missing", "text": "x"})
    assert late["error"]["code"] == m.NOT_FOUND
    await ws.close()


async def test_approve_from_the_app(chat_bridge):
    bridge, hermes = chat_bridge
    hermes.hold = True
    hermes.script = [("approval.request", {"command": "rm -rf /tmp/build", "description": "recursive delete",
                                           "request_id": "req-1", "choices": ["once", "session", "deny"]}),
                     ("assistant.delta", {"delta": "Done"})]
    hermes.final = "Done"
    phone, laptop = await connected(bridge), await connected(bridge)
    res = (await call(phone, "c1", "chat.send", {"text": "Clean the build"}))["result"]
    for ws in (phone, laptop):
        while (msg := await recv(ws)).get("method") != "chat.delta":
            pass
        check("chat.delta", msg)
        assert msg["params"]["kind"] == "approval"
        assert msg["params"]["approval"] == {"choices": ["once", "session", "deny"], "command": "rm -rf /tmp/build",
                                             "description": "recursive delete", "request_id": "req-1"}
    snap = check("chat.turn.get.result", await call(phone, "g1", "chat.turn.get", {"turn_id": res["turn_id"]}))
    assert snap["result"]["turn"]["waiting_for_approval"] is True
    assert snap["result"]["turn"]["approval"]["request_id"] == "req-1"

    bad = await call(phone, "a0", "chat.approve", {"turn_id": res["turn_id"], "choice": "always"})
    assert bad["error"]["code"] == m.INVALID_PARAMS  # Hermes didn't offer it here
    ok = check("chat.approve.result", await call(laptop, "a1", "chat.approve", {"turn_id": res["turn_id"], "choice": "session"}))
    assert ok["result"] == {"turn_id": res["turn_id"], "choice": "session"}
    assert hermes.approvals == [("run_1", {"choice": "session", "request_id": "req-1"})]
    while (msg := await recv(phone)).get("method") != "chat.delta" or msg["params"]["kind"] != "approval_done":
        pass
    check("chat.delta", msg)
    assert msg["params"]["choice"] == "session"
    await until_done(phone, res["turn_id"])
    late = await call(phone, "a2", "chat.approve", {"turn_id": res["turn_id"], "choice": "once"})
    assert late["error"]["code"] == m.CONFLICT
    await phone.close()
    await laptop.close()


async def test_openrouter_balance(tmp_path: Path):
    from talaria_bridge.accounts import OpenRouterAccount

    async def answer(req: httpx.Request) -> httpx.Response:
        if req.headers["authorization"] == "Bearer good":
            return httpx.Response(200, json={"data": {"total_credits": 100.5, "total_usage": 25.754}})
        return httpx.Response(403, json={"error": {"message": "Only management keys"}})

    good = OpenRouterAccount("good", transport=httpx.MockTransport(answer))
    bad = OpenRouterAccount("plain", transport=httpx.MockTransport(answer))

    async def unused(msg: dict) -> None:
        pass

    chat = ChatService(ChatStore(tmp_path / "chat.db"), {}, unused, accounts=[good, bad])
    result = await chat.balance()
    check("account.balance.result", {"jsonrpc": "2.0", "tnp": 0, "id": "1", "result": result})
    url = "https://openrouter.ai/settings/credits"
    assert result["accounts"] == [
        {"provider": "openrouter", "name": "OpenRouter", "top_up_url": url, "remaining": 74.75, "currency": "USD"},
        {"provider": "openrouter", "name": "OpenRouter", "top_up_url": url,
         "error": "OpenRouter refused the key; it must be a management key"}]
    await chat.close()
    chat.store.close()


async def test_default_model_for_new_chats(chat_bridge):
    bridge, hermes = chat_bridge
    phone, laptop = await connected(bridge), await connected(bridge)
    pick = {"provider": "openrouter", "model": "m-2"}
    res = check("agent.set_default_model.result", await call(phone, "d1", "agent.set_default_model", {"model": pick}))
    assert res["result"] == {"agent_id": "hermes", "default": pick}
    while (msg := await recv(laptop)).get("method") != "agent.default_model":
        pass
    assert check("agent.default_model", msg)["params"] == {"agent_id": "hermes", "default": pick}
    models = check("agent.models.result", await call(laptop, "m1", "agent.models"))["result"]
    assert models["default"] == pick and models["current"]["model"] == "m-1"
    await laptop.close()

    # a new chat starts on it, and can still change
    first = (await call(phone, "c1", "chat.send", {"text": "Hi"}))["result"]
    await until_done(phone, first["turn_id"])
    assert hermes.locks["talaria_" + first["conversation_id"][2:]] == pick
    other = {"provider": "openrouter", "model": "m-1"}
    chosen = (await call(phone, "c2", "chat.send", {"text": "Hi", "model": other}))["result"]
    await until_done(phone, chosen["turn_id"])
    assert hermes.locks["talaria_" + chosen["conversation_id"][2:]] == other

    # one the agent can't route doesn't stop the chat
    await call(phone, "d2", "agent.set_default_model", {"model": {"provider": "openrouter", "model": "nope"}})
    kept = (await call(phone, "c3", "chat.send", {"text": "Hi"}))["result"]
    await until_done(phone, kept["turn_id"])
    assert "talaria_" + kept["conversation_id"][2:] not in hermes.locks

    cleared = check("agent.set_default_model.result", await call(phone, "d3", "agent.set_default_model", {"model": None}))
    assert cleared["result"] == {"agent_id": "hermes"}
    assert "default" not in (await call(phone, "m2", "agent.models"))["result"]


def test_store_adds_model_columns_to_an_old_database(tmp_path: Path):
    import sqlite3
    db = sqlite3.connect(tmp_path / "chat.db")
    db.execute("CREATE TABLE conversations (id TEXT PRIMARY KEY, agent_id TEXT NOT NULL, hermes_session_id TEXT NOT NULL,"
               " title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, last_role TEXT, last_text TEXT)")
    db.execute("INSERT INTO conversations VALUES ('c-1', 'hermes', 's', 'Old', 1, 2, NULL, NULL)")
    db.commit()
    db.close()
    store = ChatStore(tmp_path / "chat.db")
    assert store.get("c-1").model is None
    store.set_model("c-1", "openrouter", "m-1")
    assert store.get("c-1").model == {"provider": "openrouter", "model": "m-1"}
    store.close()
