"""Hermes's bots as Talaria chats (bots.py, spec/README.md §18.1): a paired device opens a bot's permanent chat and
talks to it; a fake `hermes serve` behaves like the real one where the bridge relies on it (profiles, the Bot Chat
title, live vs stored session ids, streamed events, an approval asked mid-turn)."""

import asyncio
import json
from pathlib import Path

import pytest
import websockets

from talaria_bridge.bots import Bots
from talaria_bridge.chat import ChatService, ChatStore
from talaria_bridge.hermes import HermesClient
from talaria_bridge.hermes_serve import PROBE_KEY, HermesBackend
from talaria_bridge.protocol import keys
from talaria_bridge.protocol import messages as m
from talaria_bridge.registry import Registry
from talaria_bridge.server import BridgeServer, ServerSettings

from conftest import Bridge, check
from test_chat import KEY, FakeHermes, call, connected, recv, until_done

TOKEN = "b" * 43


class FakeBotServe:
    def __init__(self):
        self.profiles = [{"name": "default", "is_default": True, "model": "qwen/qwen3.8-flash"},
                         {"name": "scout", "display_name": "Scout", "description": "Finds things", "has_avatar": True},
                         {"name": "coder", "model": "qwen/qwen3.8-flash"},
                         {"name": "chainmail", "display_name": "", "description": "ChainMail — Use for: anything in Gmail."}]
        self.chats: dict[str, dict] = {}  # profile -> {"stored", "messages"}
        self.calls: list[tuple[str, dict]] = []
        self.answers: list[dict] = []
        self.gen = 0
        self.ask_approval = False
        self.approved = asyncio.Event()
        self.conns: list = []

    async def process_request(self, connection, request):
        if f"token={TOKEN}" not in request.path:
            return connection.respond(403, "bad token")

    async def handler(self, ws):
        self.conns.append(ws)
        self.gen += 1
        await self.send(ws, {"jsonrpc": "2.0", "method": "event", "params": {"type": "gateway.ready", "payload": {}}})
        async for raw in ws:
            for line in raw.splitlines():
                msg = json.loads(line)
                if "method" not in msg:
                    self.answers.append(msg)
                    self.approved.set()
                    continue
                asyncio.ensure_future(self.answer(ws, msg))

    async def send(self, ws, msg):
        await ws.send(json.dumps(msg) + "\n")

    def live(self, profile: str) -> str:
        return f"live-{profile}-{self.gen}"

    async def answer(self, ws, msg):
        method, p = msg["method"], msg.get("params") or {}

        async def ok(result):
            await self.send(ws, {"jsonrpc": "2.0", "id": msg["id"], "result": result})

        async def err(code, text):
            await self.send(ws, {"jsonrpc": "2.0", "id": msg["id"], "error": {"code": code, "message": text}})

        if PROBE_KEY in p:
            return await err(4000, "invalid params")
        self.calls.append((method, p))
        profile = p.get("profile") or "default"
        chat = self.chats.get(profile)
        if method == "client.capabilities":
            return await ok({"server_requests": ["approval", "clarify"]})
        if method == "profiles.list":
            return await ok({"profiles": self.profiles})
        if method == "profiles.get_asset":
            return await ok({"found": True, "mime": "image/png", "data": "data:image/png;base64,iVBORw0KGgo="})
        if method == "session.create":
            self.chats[profile] = {"stored": f"stored-{profile}", "title": p.get("title"), "messages": []}
            return await ok({"session_id": self.live(profile), "stored_session_id": f"stored-{profile}",
                             "message_count": 0, "messages": [], "info": {}})
        if method == "session.resume":
            if chat is None or p["session_id"] not in ("Bot Chat", chat["stored"]) or chat["title"] != "Bot Chat":
                return await err(4007, "session not found")
            # as Hermes 0.21.5 answers a resume: the stored id as `resumed` and `session_key`, no stored_session_id
            return await ok({"session_id": self.live(profile), "resumed": chat["stored"], "session_key": chat["stored"],
                             "message_count": len(chat["messages"]), "messages": [], "info": {}})
        live_profile = next((name for name in self.chats if self.live(name) == p.get("session_id")), None)
        if method in ("session.history", "prompt.submit", "session.interrupt", "session.steer", "session.usage") \
                and live_profile != profile:
            return await err(4001, "session not found")  # a live id from an old connection, or another bot's
        chat = self.chats[profile]
        if method == "session.history":
            return await ok({"count": len(chat["messages"]), "messages": chat["messages"]})
        if method == "session.usage":
            return await ok({"input": 40, "output": 9, "calls": 2, "cost_usd": 0.0004})
        if method in ("session.interrupt", "session.steer"):
            return await ok({"status": "queued", "text": p.get("text", "")})
        if method == "prompt.submit":
            await ok({"status": "streaming"})
            sid = p["session_id"]
            chat["messages"].append({"role": "user", "text": p["text"], "timestamp": 1.0, "row_id": len(chat["messages"]) + 1})

            async def event(kind, payload=None):
                await self.send(ws, {"jsonrpc": "2.0", "method": "event",
                                     "params": {"type": kind, "session_id": sid, "payload": payload or {}}})
            await event("message.start")
            await event("tool.start", {"tool_id": "t1", "name": "web_search", "context": "weather"})
            await event("tool.complete", {"tool_id": "t1", "name": "web_search"})
            text = "Hi there"
            if self.ask_approval:
                self.approved.clear()
                await self.send(ws, {"jsonrpc": "2.0", "id": "srq-1", "method": "approval", "params": {
                    "session_id": sid, "request_id": "a1", "command": "rm -rf build", "description": "clean the build",
                    "choices": [{"id": "once"}, {"id": "deny"}]}})
                await self.approved.wait()
                text = "Cleaned" if self.answers[-1].get("result", {}).get("choice") == "once" else "Left it"
            await event("message.delta", {"text": text[:3]})
            await event("message.delta", {"text": text[3:]})
            chat["messages"].append({"role": "assistant", "text": text, "timestamp": 2.0, "row_id": len(chat["messages"]) + 1})
            await event("message.complete", {"text": text, "status": "complete",
                                             "usage": {"model": "qwen/qwen3.8-flash", "input": 40, "output": 9, "total": 49}})
            return
        await ok({"ok": True})

    def count(self, method: str) -> int:
        return sum(1 for name, _ in self.calls if name == method)


@pytest.fixture
async def bots_bridge(tmp_path: Path, settings: ServerSettings):
    fake = FakeBotServe()
    serve = await websockets.serve(fake.handler, "127.0.0.1", 0, process_request=fake.process_request)
    url = f"ws://127.0.0.1:{serve.sockets[0].getsockname()[1]}/api/ws"
    backend = HermesBackend(url, TOKEN, backoff=(0.05, 0.1))
    hermes = FakeHermes()

    async def unused(msg: dict) -> None:
        pass

    chat = ChatService(ChatStore(tmp_path / "chat.db"), {"hermes": HermesClient("http://hermes.test", KEY, transport=hermes.transport())},
                       unused)
    chat.bots = Bots(backend)
    chat.bots.broadcast = lambda msg: chat.broadcast(msg)
    registry = Registry(tmp_path / "bridge.db")
    server = BridgeServer(registry, keys.generate_key(), settings, chat=chat, hermes=backend)
    async with server.serve() as ws_server:
        for _ in range(100):
            if chat.bots.roster():
                break
            await asyncio.sleep(0.02)
        yield Bridge(server, registry, f"ws://127.0.0.1:{ws_server.sockets[0].getsockname()[1]}/tnp"), fake, chat
    chat.store.close()
    registry.close()
    serve.close()
    await serve.wait_closed()


async def test_the_roster_lists_bots_but_not_hermes_itself(bots_bridge):
    bridge, fake, _ = bots_bridge
    phone = await connected(bridge)
    listed = check("bots.list.result", await call(phone, "1", "bots.list"))["result"]
    assert listed["available"] is True
    assert [(b["id"], b["name"]) for b in listed["bots"]] == [("bot:chainmail", "ChainMail"), ("bot:coder", "coder"), ("bot:scout", "Scout")]
    assert listed["bots"][0]["description"] == "Use for: anything in Gmail."  # the title comes off the description
    assert listed["bots"][2]["description"] == "Finds things" and listed["bots"][2]["has_avatar"] is True
    pic = check("bots.avatar.result", await call(phone, "2", "bots.avatar", {"bot_id": "bot:scout"}))["result"]
    assert pic == {"found": True, "mime": "image/png", "data": "iVBORw0KGgo="}


async def test_the_roster_change_reaches_devices(bots_bridge):
    bridge, fake, chat = bots_bridge
    phone = await connected(bridge)
    fake.profiles.append({"name": "writer", "display_name": "Writer"})
    await chat.bots.refresh()
    while True:
        msg = await recv(phone)
        if msg.get("method") == "bots.changed":
            check("bots.changed", msg)
            assert "bot:writer" in [b["id"] for b in msg["params"]["bots"]]
            break


async def test_a_bot_has_one_chat_made_once_and_talks_like_hermes(bots_bridge):
    bridge, fake, _ = bots_bridge
    phone, laptop = await connected(bridge), await connected(bridge)
    opened = check("bots.open.result", await call(phone, "1", "bots.open", {"bot_id": "bot:scout"}))["result"]
    again = (await call(laptop, "2", "bots.open", {"bot_id": "bot:scout"}))["result"]
    assert again["conversation_id"] == opened["conversation_id"] and opened["title"] == "Scout"
    assert fake.count("session.create") == 1
    assert [p for name, p in fake.calls if name == "session.create"] == [{"title": "Bot Chat", "profile": "scout"}]

    sent = check("chat.send.result", await call(phone, "3", "chat.send",
                                               {"conversation_id": opened["conversation_id"], "text": "Find me a cafe"}))
    events = await until_done(phone, sent["result"]["turn_id"])
    done = events[-1]["params"]
    assert done["status"] == "completed" and done["text"] == "Hi there"
    assert done["usage"]["input_tokens"] == 40 and done["runtime"] == {"model": "qwen/qwen3.8-flash"}
    tools = [e["params"]["tool"] for e in events if e["params"].get("kind") == "tool_progress"]
    assert [t["state"] for t in tools] == ["started", "completed"] and tools[0]["preview"] == "weather"
    submitted = [p for name, p in fake.calls if name == "prompt.submit"]
    assert submitted == [{"session_id": "live-scout-1", "text": "Find me a cafe", "profile": "scout"}]

    history = check("chat.history.result", await call(laptop, "4", "chat.history",
                                                     {"conversation_id": opened["conversation_id"]}))["result"]
    assert [(x["role"], x["text"]) for x in history["messages"]] == [("user", "Find me a cafe"), ("assistant", "Hi there")]

    # writing to the bot (no conversation) goes into the same chat
    direct = await call(phone, "5", "chat.send", {"agent_id": "bot:scout", "text": "And one more"})
    assert direct["result"]["conversation_id"] == opened["conversation_id"]
    await until_done(phone, direct["result"]["turn_id"])
    listed = (await call(phone, "6", "conversations.list"))["result"]["conversations"]
    bot_rows = [c for c in listed if c["agent_id"] == "bot:scout"]
    assert len(bot_rows) == 1 and bot_rows[0]["last_message"]["text"] == "Hi there"


async def test_approval_in_a_bot_chat_uses_the_chats_own_card(bots_bridge):
    bridge, fake, _ = bots_bridge
    phone = await connected(bridge)
    conv = (await call(phone, "1", "bots.open", {"bot_id": "bot:coder"}))["result"]["conversation_id"]
    fake.ask_approval = True
    sent = await call(phone, "2", "chat.send", {"conversation_id": conv, "text": "Clean up"})
    turn_id = sent["result"]["turn_id"]
    while True:
        msg = await recv(phone)
        assert msg.get("method") != "hermes.request"  # not the generic card: the chat's own
        if msg.get("method") == "chat.delta" and msg["params"].get("kind") == "approval":
            check("chat.delta", msg)
            assert msg["params"]["approval"]["command"] == "rm -rf build"
            assert msg["params"]["approval"]["choices"] == ["once", "deny"]
            break
    answer = await call(phone, "3", "chat.approve", {"turn_id": turn_id, "choice": "once"})
    assert "result" in answer, answer
    events = await until_done(phone, turn_id)
    assert events[-1]["params"]["text"] == "Cleaned"
    assert fake.answers[-1] == {"jsonrpc": "2.0", "id": "srq-1", "result": {"choice": "once"}}


async def test_talaria_never_renames_deletes_or_repins_the_bots_chat(bots_bridge):
    bridge, fake, _ = bots_bridge
    phone = await connected(bridge)
    conv = (await call(phone, "1", "bots.open", {"bot_id": "bot:scout"}))["result"]["conversation_id"]
    await until_done(phone, (await call(phone, "2", "chat.send", {"conversation_id": conv, "text": "Hi"}))["result"]["turn_id"])
    before = len(fake.calls)
    assert "result" in await call(phone, "3", "conversations.rename", {"conversation_id": conv, "title": "My scout"})
    model = await call(phone, "4", "conversations.set_model",
                       {"conversation_id": conv, "model": {"provider": "openrouter", "model": "qwen/qwen3.8-flash"}})
    assert model["error"]["code"] == m.INVALID_PARAMS
    assert "result" in await call(phone, "5", "conversations.delete", {"conversation_id": conv})
    assert not any(name.startswith(("session.title", "session.delete", "command")) for name, _ in fake.calls[before:])
    assert fake.chats["scout"]["title"] == "Bot Chat"

    back = (await call(phone, "6", "bots.open", {"bot_id": "bot:scout"}))["result"]["conversation_id"]
    assert back != conv and fake.count("session.create") == 1  # the same Bot Chat, found again
    history = (await call(phone, "7", "chat.history", {"conversation_id": back}))["result"]["messages"]
    assert [x["text"] for x in history] == ["Hi", "Hi there"]


async def test_stop_and_notes_reach_the_bot(bots_bridge):
    bridge, fake, chat = bots_bridge
    phone = await connected(bridge)
    conv = (await call(phone, "1", "bots.open", {"bot_id": "bot:scout"}))["result"]["conversation_id"]
    fake.ask_approval = True  # holds the turn open
    turn_id = (await call(phone, "2", "chat.send", {"conversation_id": conv, "text": "Long job"}))["result"]["turn_id"]
    for _ in range(100):
        if any(name == "prompt.submit" for name, _ in fake.calls):
            break
        await asyncio.sleep(0.02)
    await call(phone, "3", "chat.steer", {"turn_id": turn_id, "text": "only the cafes"})
    await call(phone, "4", "chat.cancel", {"turn_id": turn_id})
    for _ in range(100):
        if fake.count("session.interrupt"):
            break
        await asyncio.sleep(0.02)
    assert ("session.steer", {"session_id": "live-scout-1", "text": "only the cafes", "profile": "scout"}) in fake.calls
    assert fake.count("session.interrupt") == 1
    fake.approved.set()


async def test_after_a_reconnect_the_bots_chat_is_resumed_again(bots_bridge):
    bridge, fake, chat = bots_bridge
    phone = await connected(bridge)
    conv = (await call(phone, "1", "bots.open", {"bot_id": "bot:scout"}))["result"]["conversation_id"]
    await fake.conns[-1].close()
    for _ in range(200):
        if fake.gen == 2 and chat.bots.backend.connected:
            break
        await asyncio.sleep(0.02)
    sent = await call(phone, "2", "chat.send", {"conversation_id": conv, "text": "Still there?"})
    events = await until_done(phone, sent["result"]["turn_id"])
    assert events[-1]["params"]["status"] == "completed"
    assert ("session.resume", {"session_id": "stored-scout", "omit_messages": True, "profile": "scout"}) in fake.calls
    assert [p["session_id"] for name, p in fake.calls if name == "prompt.submit"] == ["live-scout-2"]


async def test_a_bot_chat_made_in_hermes_desktop_is_found(bots_bridge):
    """The owner's bots already have a Bot Chat from Hermes Desktop: Talaria resumes it, never makes another."""
    bridge, fake, _ = bots_bridge
    fake.chats["scout"] = {"stored": "desktop-scout", "title": "Bot Chat",
                           "messages": [{"role": "user", "text": "From the laptop", "timestamp": 1.0, "row_id": 1}]}
    phone = await connected(bridge)
    conv = (await call(phone, "1", "bots.open", {"bot_id": "bot:scout"}))["result"]["conversation_id"]
    assert fake.count("session.create") == 0
    history = (await call(phone, "2", "chat.history", {"conversation_id": conv}))["result"]["messages"]
    assert [x["text"] for x in history] == ["From the laptop"]


async def test_unknown_bots_and_attachments_are_refused(bots_bridge):
    bridge, fake, _ = bots_bridge
    phone = await connected(bridge)
    assert (await call(phone, "1", "bots.open", {"bot_id": "bot:nobody"}))["error"]["code"] == m.NOT_FOUND
    assert (await call(phone, "2", "chat.send", {"agent_id": "bot:nobody", "text": "hi"}))["error"]["code"] == m.NOT_FOUND
    assert fake.count("session.create") == 0


async def test_photos_and_files_to_a_bot_are_refused_not_dropped(bots_bridge):
    from types import SimpleNamespace
    from talaria_bridge.chat import RpcError
    bridge, fake, chat = bots_bridge
    photo = SimpleNamespace(kind="image", name="a.jpg")
    for p in ({"agent_id": "bot:scout"}, {"conversation_id": (await chat._bot_conversation("bot:scout")).id}):
        with pytest.raises(RpcError) as err:
            await chat._send(p, "look", [photo], None)
        assert err.value.code == m.MODALITY_UNSUPPORTED
    assert fake.count("prompt.submit") == 0


async def test_without_a_doorway_there_are_no_bots(chat_bridge_plain):
    phone = await connected(chat_bridge_plain)
    assert (await call(phone, "1", "bots.list"))["error"]["code"] == m.METHOD_NOT_FOUND


@pytest.fixture
async def chat_bridge_plain(tmp_path: Path, settings: ServerSettings):
    hermes = FakeHermes()

    async def unused(msg: dict) -> None:
        pass

    chat = ChatService(ChatStore(tmp_path / "chat.db"), {"hermes": HermesClient("http://hermes.test", KEY, transport=hermes.transport())}, unused)
    registry = Registry(tmp_path / "bridge.db")
    server = BridgeServer(registry, keys.generate_key(), settings, chat=chat)
    async with server.serve() as ws_server:
        yield Bridge(server, registry, f"ws://127.0.0.1:{ws_server.sockets[0].getsockname()[1]}/tnp")
    chat.store.close()
    registry.close()


async def test_hermes_hands_a_job_to_a_bot_and_gets_the_report(bots_bridge):
    """Item 14: Hermes's MCP tools (spec §15) — list_bots, ask_bot (waits for the report), bot_job."""
    from talaria_bridge.agent_tools import AgentTools
    from talaria_bridge.todos import TodoStore

    bridge, fake, chat = bots_bridge
    tools = AgentTools(TodoStore(":memory:"), {"t": "hermes"},
                       lambda: asyncio.sleep(0), chat=chat)
    names = {t["name"] for t in tools.tools}
    assert {"list_bots", "ask_bot", "bot_job"} <= names
    listed = json.loads((await tools.call("list_bots", {}))["content"][0]["text"])
    assert {"name": "Scout", "profile": "scout", "for": "Finds things"} in listed["bots"]

    phone = await connected(bridge)
    answer = await tools.call("ask_bot", {"bot": "scout", "message": "Find a quiet cafe near Indiranagar",
                                          "files": ["/var/lib/talaria/inbox/c-1/b1-map.png", "relative.png"]})
    report = json.loads(answer["content"][0]["text"])
    assert report["status"] == "completed" and report["report"] == "Hi there" and report["bot"] == "Scout"
    order = [p["text"] for name, p in fake.calls if name == "prompt.submit"][-1]
    assert order.startswith("🎙 Work order from Hermes, the owner's main agent.")
    assert "don't hand it on to another bot" in order
    assert "Attached file: /var/lib/talaria/inbox/c-1/b1-map.png" in order and "relative.png" not in order
    started = None
    while started is None:  # the owner sees the job as a card in Scout's chat
        msg = await recv(phone)
        if msg.get("method") == "chat.started":
            started = check("chat.started", msg)["params"]
    assert started["worker"] == "Scout" and started["user_text"] == "Find a quiet cafe near Indiranagar"

    fake.ask_approval = True  # a long job: comes back running, with its id
    long = json.loads((await tools.call("ask_bot", {"bot": "Scout", "message": "Clean up", "wait_seconds": 0}))["content"][0]["text"])
    assert long["status"] == "running" and "bot_job" in long["note"]
    for _ in range(100):
        if json.loads((await tools.call("bot_job", {"job": long["job"]}))["content"][0]["text"]).get("waiting_for"):
            break
        await asyncio.sleep(0.02)
    turn = chat._turns[long["job"]]
    await chat.approve({"turn_id": turn.turn_id, "choice": "once"})
    for _ in range(200):
        state = json.loads((await tools.call("bot_job", {"job": long["job"]}))["content"][0]["text"])
        if state["status"] != "running":
            break
        await asyncio.sleep(0.02)
    assert state["status"] == "completed" and state["report"] == "Cleaned"

    nobody = await tools.call("ask_bot", {"bot": "nobody", "message": "x"})
    assert nobody.get("isError") and "The bots are: ChainMail, coder, Scout" in nobody["content"][0]["text"]
    tools._bot_jobs = ["t-a", "t-b", "t-c"]
    from types import SimpleNamespace
    for t in tools._bot_jobs:
        chat._turns[t] = SimpleNamespace(status="running")
    busy = await tools.call("ask_bot", {"bot": "scout", "message": "one more"})
    assert busy.get("isError") and "3 bot jobs are already running" in busy["content"][0]["text"]
    for t in ("t-a", "t-b", "t-c"):
        del chat._turns[t]
