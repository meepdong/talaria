"""Every session from every surface (history.py, spec/README.md §18.9) and editing a bot's chat (§18.10): fakes of
hermes serve's session addresses, Hermes's API fork, and a bot's prompt.submit with a rewind."""

import asyncio
import json
from pathlib import Path

import httpx
import pytest
import websockets

from talaria_bridge.bots import Bots
from talaria_bridge.chat import ChatService, ChatStore
from talaria_bridge.hermes import HermesClient
from talaria_bridge.hermes_serve import HermesBackend, ServeError
from talaria_bridge.history import History
from talaria_bridge.protocol import keys
from talaria_bridge.protocol import messages as m
from talaria_bridge.registry import Registry
from talaria_bridge.server import BridgeServer, ServerSettings

from conftest import Bridge, check
from test_bots import TOKEN, FakeBotServe
from test_chat import KEY, FakeHermes, connected, until_done
from test_commands import call

SESSIONS = [
    {"id": "tg_1", "source": "telegram", "title": "Flights to Nashik", "started_at": 1791100000.5, "last_active": 1791100500,
     "message_count": 6},
    {"id": "talaria_aside_9", "source": "api_server", "title": "aside", "started_at": 1791100001, "message_count": 2},
    {"id": "cron_1", "source": "cron", "title": "Morning summary", "started_at": 1791000000, "ended_at": 1791000040,
     "message_count": 4},
]


class ForkingHermes(FakeHermes):
    def __init__(self):
        super().__init__()
        self.forks: list[tuple[str, dict]] = []

    async def handle(self, req: httpx.Request) -> httpx.Response:
        if req.method == "POST" and req.url.path.endswith("/fork"):
            source = req.url.path.split("/")[3]
            if source == "missing":
                return httpx.Response(404, json={"error": {"message": "Session not found", "code": "not_found"}})
            body = json.loads(req.content)
            self.forks.append((source, body))
            self.sessions[body["id"]] = {"title": body.get("title"), "messages": []}
            return httpx.Response(201, json={"object": "hermes.session", "session": {"id": body["id"]}})
        return await super().handle(req)


class FakeSessions:
    def __init__(self):
        self.calls: list[str] = []

    async def rest(self, path: str, method: str = "GET", body: dict | None = None):
        self.calls.append(path)
        if path.startswith("/api/sessions/search"):
            return {"results": [
                {"session_id": "tg_1", "snippet": "cheap >>>flights<<<", "source": "telegram", "session_started": 1791100000},
                {"session_id": "tg_1", "snippet": "more >>>flights<<<", "source": "telegram"},
                {"session_id": "talaria_aside_9", "snippet": "x", "source": "api_server"}]}
        if path.startswith("/api/sessions?"):
            return {"sessions": SESSIONS, "total": 3}
        sid = path.split("/")[3].split("?")[0]
        if sid == "missing":
            raise ServeError(404, json.dumps({"detail": "Session not found"}))
        if "/messages" in path:
            return {"messages": [
                {"role": "user", "content": "Find flights to Nashik", "timestamp": 1791100000},
                {"role": "tool", "content": "{...}", "timestamp": 1791100001},
                {"role": "assistant", "content": [{"type": "text", "text": "Two flights on the 5th"}], "timestamp": 1791100002},
                {"role": "assistant", "content": "", "timestamp": 1791100003}]}
        return {"session": next((s for s in SESSIONS if s["id"] == sid), {"id": sid})}


class RewindServe(FakeBotServe):
    """The bots' fake serve whose history has row ids, and which cuts the chat on a rewind as Hermes does."""

    async def answer(self, ws, msg):
        method, p = msg["method"], msg.get("params") or {}
        if method == "prompt.submit" and p.get("truncate_before_row_id") is not None:
            chat = self.chats[p.get("profile") or "default"]
            chat["messages"] = [r for r in chat["messages"] if r["row_id"] < p["truncate_before_row_id"]]
        await super().answer(ws, msg)


@pytest.fixture
async def history_bridge(tmp_path: Path, settings: ServerSettings):
    fake = RewindServe()
    serve = await websockets.serve(fake.handler, "127.0.0.1", 0, process_request=fake.process_request)
    backend = HermesBackend(f"ws://127.0.0.1:{serve.sockets[0].getsockname()[1]}/api/ws", TOKEN, backoff=(0.05, 0.1))
    sessions = FakeSessions()
    backend.rest = sessions.rest
    hermes = ForkingHermes()

    async def unused(msg: dict) -> None:
        pass

    chat = ChatService(ChatStore(tmp_path / "chat.db"),
                       {"hermes": HermesClient("http://hermes.test", KEY, transport=hermes.transport())}, unused)
    chat.bots = Bots(backend)
    chat.past = History(backend, chat)
    registry = Registry(tmp_path / "bridge.db")
    server = BridgeServer(registry, keys.generate_key(), settings, chat=chat, hermes=backend)
    async with server.serve() as ws_server:
        for _ in range(100):
            if chat.bots.roster():
                break
            await asyncio.sleep(0.02)
        yield Bridge(server, registry, f"ws://127.0.0.1:{ws_server.sockets[0].getsockname()[1]}/tnp"), fake, sessions, hermes, chat
    chat.store.close()
    registry.close()
    serve.close()
    await serve.wait_closed()


async def test_sessions_from_every_surface_listed_and_searched(history_bridge):
    bridge, _, sessions, _, _ = history_bridge
    phone = await connected(bridge)
    got = check("history.list.result", await call(phone, "1", "history.list"))["result"]
    assert [s["session_id"] for s in got["sessions"]] == ["tg_1", "cron_1"] and not got["has_more"]
    assert got["sessions"][0] == {"session_id": "tg_1", "title": "Flights to Nashik", "source": "telegram",
                                  "started_at": 1791100000, "last_active": 1791100500, "messages": 6}
    assert got["sessions"][1]["last_active"] == 1791000040
    assert "profile=default" in sessions.calls[-1] and "order=recent" in sessions.calls[-1]
    found = check("history.list.result", await call(phone, "2", "history.list", {"query": "flights"}))["result"]
    assert [(s["session_id"], s["snippet"], s["title"]) for s in found["sessions"]] == [("tg_1", "cheap >>>flights<<<", "Flights to Nashik")]
    await call(phone, "3", "history.list", {"bot_id": "bot:scout"})
    assert "profile=scout" in sessions.calls[-1]
    nobody = await call(phone, "4", "history.list", {"bot_id": "bot:ghost"})
    assert nobody["error"]["code"] == m.NOT_FOUND


async def test_a_session_is_read_without_tools_and_carried_on_in_talaria(history_bridge):
    bridge, _, _, hermes, chat = history_bridge
    phone = await connected(bridge)
    read = check("history.read.result", await call(phone, "1", "history.read", {"session_id": "tg_1"}))["result"]
    assert read == {"messages": [{"role": "user", "text": "Find flights to Nashik", "at": 1791100000},
                                 {"role": "assistant", "text": "Two flights on the 5th", "at": 1791100002}], "has_more": False}
    on = check("history.continue.result", await call(phone, "2", "history.continue", {"session_id": "tg_1"}))["result"]
    assert on["title"] == "Flights to Nashik (continued)" and hermes.forks[0][0] == "tg_1"
    conv = chat.store.get(on["conversation_id"])
    assert conv.agent_id == "hermes" and conv.hermes_session_id == hermes.forks[0][1]["id"]
    again = (await call(phone, "3", "history.continue", {"session_id": conv.hermes_session_id}))["result"]
    assert again["conversation_id"] == on["conversation_id"] and len(hermes.forks) == 1
    listed = (await call(phone, "4", "history.list"))["result"]
    assert all("conversation_id" not in s for s in listed["sessions"])  # the fork isn't in this fake's list
    missing = await call(phone, "5", "history.continue", {"session_id": "missing"})
    assert missing["error"]["code"] == m.NOT_FOUND


async def test_a_bots_chat_is_rewound_and_answered_again(history_bridge):
    bridge, fake, _, _, chat = history_bridge
    phone = await connected(bridge)
    conv = (await call(phone, "1", "bots.open", {"bot_id": "bot:scout"}))["result"]["conversation_id"]
    for i, text in enumerate(("First question", "Second question")):
        sent = (await call(phone, f"s{i}", "chat.send", {"conversation_id": conv, "text": text}))["result"]
        await until_done(phone, sent["turn_id"])
    history = (await call(phone, "2", "chat.history", {"conversation_id": conv}))["result"]["messages"]
    second = next(x for x in history if x["text"] == "Second question")
    edited = check("chat.edit.result", await call(phone, "3", "chat.edit",
                                                  {"conversation_id": conv, "message_id": second["id"], "text": "Second, better"}))
    await until_done(phone, edited["result"]["turn_id"])
    submits = [p for name, p in fake.calls if name == "prompt.submit"]
    assert submits[-1]["truncate_before_row_id"] == int(second["id"]) and submits[-1]["confirm_truncate"] is True
    after = (await call(phone, "4", "chat.history", {"conversation_id": conv}))["result"]["messages"]
    # (this fake stamps every question 1.0 and every answer 2.0, so order by Hermes's row ids)
    assert [x["text"] for x in sorted(after, key=lambda x: int(x["id"]))] == ["First question", "Hi there", "Second, better", "Hi there"]

    answer = next(x for x in after if x["role"] == "assistant")
    not_mine = await call(phone, "5", "chat.edit", {"conversation_id": conv, "message_id": answer["id"], "text": "x"})
    assert not_mine["error"]["code"] == m.NOT_FOUND
    hermes_chat = (await call(phone, "6", "chat.send", {"text": "Hello"}))["result"]
    await until_done(phone, hermes_chat["turn_id"])
    whole = await call(phone, "7", "chat.edit", {"conversation_id": hermes_chat["conversation_id"], "message_id": "1", "text": "x"})
    assert whole["error"]["code"] == m.INVALID_PARAMS
