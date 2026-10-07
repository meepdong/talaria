"""Group chats (rooms.py, spec/README.md §18.2): a paired device lists, opens, writes in, stops and approves in
Hermes rooms; a fake `hermes serve` keeps a room log the way the real one does (shapes recorded from a real room)."""

import asyncio
import json
import time
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
from talaria_bridge.rooms import Rooms
from talaria_bridge.server import BridgeServer, ServerSettings

from conftest import Bridge, check
from test_chat import KEY, FakeHermes, connected, recv

TOKEN = "r" * 43
GATEWAY = {"id": "install:abc", "kind": "gateway"}


class FakeRoomServe:
    def __init__(self):
        self.profiles = [{"name": "default", "is_default": True}, {"name": "scout", "display_name": "Scout"},
                         {"name": "research", "display_name": "Researcher"}, {"name": "coder"}]
        self.rooms: dict[str, dict] = {}
        self.logs: dict[str, list[dict]] = {}
        self.pending: dict[str, list[dict]] = {}
        self.working: dict[str, bool] = {}
        self.calls: list[tuple[str, dict]] = []
        self.ask_approval = False
        self.replies = {"scout": "Found two cafes.", "research": "Both are quiet before ten."}

    async def process_request(self, connection, request):
        if f"token={TOKEN}" not in request.path:
            return connection.respond(403, "bad token")

    async def handler(self, ws):
        await ws.send(json.dumps({"jsonrpc": "2.0", "method": "event", "params": {"type": "gateway.ready", "payload": {}}}) + "\n")
        async for raw in ws:
            for line in raw.splitlines():
                msg = json.loads(line)
                if "method" in msg:
                    await ws.send(json.dumps(self.answer(msg)) + "\n")

    def event(self, rid, kind, actor, payload):
        log = self.logs[rid]
        e = {"room_id": rid, "seq": len(log) + 1, "event_id": f"e{len(log) + 1}", "kind": kind, "actor": actor,
             "payload": payload, "created_at": time.time(), "authority_epoch": 1}
        log.append(e)
        self.rooms[rid]["latest_seq"] = e["seq"]
        self.rooms[rid]["updated_at"] = e["created_at"]
        return e

    def run_round(self, rid, thread):
        """What Hermes's room driver does: each member in turn replies (or asks for an approval first)."""
        room = self.rooms[rid]
        for i, mem in enumerate(room["members"]):
            if self.ask_approval and i == 1 and not self.pending.get(rid):
                self.pending[rid] = [{"kind": "approval", "task_id": f"dtask:{i}", "execution_generation": 1,
                                      "run_id": "run1", "session_id": "s1", "request_id": "apr-1",
                                      "approval": {"command": "rm -rf build", "description": "clean the build",
                                                   "choices": ["once", "always", "deny"]}}]
                return  # waits for the approval
            coords = {"discussion_event_id": "user:x", "member_id": mem["member_id"], "member_index": i,
                      "round_index": 0, "task_id": f"dtask:{i}", "thread_id": thread, "turn_id": f"d1.r0.p{i}"}
            self.event(rid, "message.member", {"kind": "member", "id": mem["member_id"], "profile": mem["profile"]},
                       {**coords, "text": self.replies.get(mem["profile"], f"Hi from {mem['profile']}, @user what do you think?")})
            self.event(rid, "turn.settled", GATEWAY, {**coords, "message_event_id": "dmessage:x", "passed": False,
                                                       "seen_through_seq": len(self.logs[rid])})
        self.event(rid, "room.activity", GATEWAY, {"discussion_event_id": "user:x", "reason_code": "silent_round",
                                                   "status": "settled", "thread_id": thread})
        self.working[rid] = False

    def answer(self, msg):
        method, p = msg["method"], msg.get("params") or {}

        def ok(result):
            return {"jsonrpc": "2.0", "id": msg["id"], "result": result}

        def err(code, text):
            return {"jsonrpc": "2.0", "id": msg["id"], "error": {"code": code, "message": text}}

        if PROBE_KEY in p:
            return err(4000, "invalid params")
        self.calls.append((method, p))
        if method == "client.capabilities":
            return ok({"server_requests": []})
        if method == "profiles.list":
            return ok({"profiles": self.profiles})
        if method == "groups.list":
            return ok({"rooms": list(self.rooms.values()), "next_offset": None})
        if method == "groups.create":
            for mem in p["members"]:
                assert set(mem) == {"member_id", "profile", "handle", "display_name"}, mem
            room = {"room_id": p["room_id"], "name": p["name"], "members": p["members"], "authority_gateway_id": "install:abc",
                    "authority_epoch": 1, "revision": 1, "created_at": time.time(), "updated_at": time.time(), "latest_seq": 0}
            self.rooms[p["room_id"]], self.logs[p["room_id"]] = room, []
            return ok({"room": room})
        rid = p.get("room_id")
        if rid not in self.rooms:
            return err(5110, "room not found")
        if method == "groups.log":
            events = [e for e in self.logs[rid] if e["seq"] > (p.get("since_seq") or 0)][: p.get("limit") or 200]
            cursor = events[-1]["seq"] if events else (p.get("since_seq") or 0)
            return ok({"events": events, "cursor": cursor, "latest_seq": len(self.logs[rid]),
                       "has_more": cursor < len(self.logs[rid]), "authority": {"gateway_id": "install:abc", "epoch": 1}})
        if method == "groups.state":
            return ok({"room": self.rooms[rid], "driver_status": {
                "running": True, "working": self.working.get(rid, False), "blocked": False, "counts": {},
                "pending_actions": self.pending.get(rid, []), "peer_routes": []}})
        if method == "groups.send":
            assert isinstance(p.get("event_id"), str) and set(p["payload"]) == {"text", "thread_id"}
            e = self.event(rid, "message.user", {"kind": "user", "id": "desktop"}, p["payload"])
            self.working[rid] = True
            self.run_round(rid, p["payload"]["thread_id"])
            return ok({"event": e, "client_event_id": p["event_id"], "accepted": True, "driver_started": True})
        if method == "groups.stop":
            self.event(rid, "room.stop_requested", GATEWAY, {})
            self.working[rid] = False
            return ok({"cancelled": 1})
        if method == "groups.rename":
            assert isinstance(p.get("event_id"), str)
            self.rooms[rid]["name"] = p["name"]
            self.event(rid, "room.renamed", GATEWAY, {"name": p["name"]})
            return ok({"room": self.rooms[rid]})
        if method == "groups.retry":
            if not any(a.get("task_id") == p["task_id"] for a in self.pending.get(rid, []) if a.get("kind") == "retry"):
                return err(4115, "no retryable room task matches task_id")
            self.pending[rid] = [a for a in self.pending[rid] if a.get("task_id") != p["task_id"]]
            self.working[rid] = True
            return ok({"retried": True, "task": {"task_id": p["task_id"]}})
        if method == "groups.disband":
            self.rooms[rid]["disbanded_at"] = time.time()
            return ok({"tombstone": {"room_id": rid}})
        if method == "groups.approve":
            action = (self.pending.get(rid) or [None])[0]
            if action is None or p["member_id"] != "research" or p["request_id"] != action["request_id"] \
                    or p["task_id"] != action["task_id"]:
                return err(4115, "room approval is no longer pending")
            self.pending[rid] = []
            self.ask_approval = False
            mem = self.rooms[rid]["members"][1]
            thread = self.logs[rid][0]["payload"]["thread_id"]
            self.event(rid, "message.member", {"kind": "member", "id": mem["member_id"]},
                       {"member_id": mem["member_id"], "member_index": 1, "thread_id": thread, "text": "Cleaned up."})
            self.event(rid, "room.activity", GATEWAY, {"status": "settled", "thread_id": thread})
            self.working[rid] = False
            return ok({"approved": True, "result": {}})
        return ok({})


@pytest.fixture
async def rooms_bridge(tmp_path: Path, settings: ServerSettings):
    fake = FakeRoomServe()
    serve = await websockets.serve(fake.handler, "127.0.0.1", 0, process_request=fake.process_request)
    backend = HermesBackend(f"ws://127.0.0.1:{serve.sockets[0].getsockname()[1]}/api/ws", TOKEN, backoff=(0.05, 0.1))
    hermes = FakeHermes()

    async def unused(msg: dict) -> None:
        pass

    chat = ChatService(ChatStore(tmp_path / "chat.db"), {"hermes": HermesClient("http://hermes.test", KEY, transport=hermes.transport())},
                       unused)
    chat.bots = Bots(backend)
    chat.bots.broadcast = lambda msg: chat.broadcast(msg)
    chat.rooms = Rooms(backend, chat.bots, assistant_name="Tally", fast_s=0.05, slow_s=0.2)
    chat.rooms.broadcast = lambda msg: chat.broadcast(msg)
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


_kept: dict[int, list[dict]] = {}


async def call(ws, msg_id: str, method: str, params: dict | None = None) -> dict:
    """A request and its reply, keeping the notifications that come first (a room update can) for until()."""
    await ws.send(m.encode(check(method, m.request(msg_id, method, params if params is not None else {}))))
    while True:
        msg = await recv(ws)
        if msg.get("id") == msg_id:
            return msg
        _kept.setdefault(id(ws), []).append(msg)


async def until(ws, method: str, cond=lambda p: True, timeout: float = 5) -> dict:
    kept = _kept.setdefault(id(ws), [])
    for msg in list(kept):
        kept.remove(msg)
        if msg.get("method") == method and cond(msg.get("params") or {}):
            return check(method, msg)["params"]
    end = asyncio.get_running_loop().time() + timeout
    while True:
        msg = await recv(ws, max(0.1, end - asyncio.get_running_loop().time()))
        if msg.get("method") == method and cond(msg.get("params") or {}):
            return check(method, msg)["params"]


async def test_make_a_room_talk_in_it_and_see_the_members_answer(rooms_bridge):
    bridge, fake, chat = rooms_bridge
    phone = await connected(bridge)
    made = check("rooms.create.result", await call(phone, "1", "rooms.create",
                                                    {"name": "Trip planning", "members": ["bot:scout", "bot:research", "assistant"]}))
    room = made["result"]["room"]
    assert [(x["name"], x["handle"], x.get("bot_id")) for x in room["members"]] == [
        ("Scout", "scout", "bot:scout"), ("Researcher", "researcher", "bot:research"), ("Tally", "hermes", None)]
    created = [p for name, p in fake.calls if name == "groups.create"][0]
    assert [x["profile"] for x in created["members"]] == ["scout", "research", "default"]
    listed = check("rooms.list.result", await call(phone, "2", "rooms.list"))["result"]
    assert [r["name"] for r in listed["rooms"]] == ["Trip planning"] and listed["available"]

    opened = check("rooms.open.result", await call(phone, "3", "rooms.open", {"room_id": room["id"]}))["result"]
    assert opened["messages"] == [] and opened["approvals"] == []
    sent = check("rooms.send.result", await call(phone, "4", "rooms.send", {"room_id": room["id"], "text": "Find a quiet cafe for tomorrow"}))
    mine = sent["result"]["message"]
    assert (mine["kind"], mine["speaker"], mine["text"]) == ("user", "You", "Find a quiet cafe for tomorrow")
    texts: list[tuple[str, str]] = []
    while len(texts) < 4:
        update = await until(phone, "rooms.update", lambda p: p["room_id"] == room["id"])
        texts += [(x["speaker"], x["text"]) for x in update["messages"]]
    assert texts[:4] == [("You", "Find a quiet cafe for tomorrow"), ("Scout", "Found two cafes."),
                         ("Researcher", "Both are quiet before ten."), ("Tally", "Hi from default, @user what do you think?")]
    assert update["needs_you"] is True  # Tally asked @user
    again = (await call(phone, "5", "rooms.open", {"room_id": room["id"]}))["result"]
    assert [x["speaker"] for x in again["messages"]] == ["You", "Scout", "Researcher", "Tally"]
    assert again["room"]["preview"] == {"speaker": "Tally", "text": "Hi from default, @user what do you think?"}
    thread = again["messages"][0]["thread_id"]
    reply = (await call(phone, "6", "rooms.send", {"room_id": room["id"], "text": "@scout the first one", "thread_id": thread}))["result"]
    assert reply["message"]["thread_id"] == thread


async def test_an_approval_in_a_room_is_answered_from_the_phone(rooms_bridge):
    bridge, fake, chat = rooms_bridge
    phone = await connected(bridge)
    room = (await call(phone, "1", "rooms.create", {"name": "Cleanup", "members": ["bot:scout", "bot:research"]}))["result"]["room"]
    fake.ask_approval = True
    await call(phone, "2", "rooms.send", {"room_id": room["id"], "text": "Clean the build folder"})
    waiting = await until(phone, "rooms.update", lambda p: p["approvals"])
    approval = waiting["approvals"][0]
    assert approval == {"approval_id": "apr-1", "member": "Researcher", "command": "rm -rf build",
                        "description": "clean the build", "choices": ["once", "deny"]}
    assert waiting["needs_you"] is True
    listed = (await call(phone, "3", "rooms.list"))["result"]["rooms"][0]
    assert listed["needs_you"] is True
    from talaria_bridge.rooms import RoomError
    with pytest.raises(RoomError) as bad:  # "always" isn't offered in rooms (the schema refuses it on the wire too)
        await chat.rooms.handle("rooms.approve", {"room_id": room["id"], "approval_id": "apr-1", "choice": "always"})
    assert bad.value.code == m.INVALID_PARAMS
    check("rooms.approve.result", await call(phone, "5", "rooms.approve", {"room_id": room["id"], "approval_id": "apr-1", "choice": "once"}))
    tried = [p["member_id"] for name, p in fake.calls if name == "groups.approve"]
    assert tried[-1] == "research"  # the bridge guessed (or found) who asked
    done = await until(phone, "rooms.update", lambda p: any(x["text"] == "Cleaned up." for x in p["messages"]))
    assert done["approvals"] == []
    gone = await call(phone, "6", "rooms.approve", {"room_id": room["id"], "approval_id": "apr-1", "choice": "once"})
    assert gone["error"]["code"] == m.NOT_FOUND


async def test_stop_unknown_rooms_and_bad_requests(rooms_bridge):
    bridge, fake, chat = rooms_bridge
    phone = await connected(bridge)
    room = (await call(phone, "1", "rooms.create", {"name": "Two", "members": ["bot:scout", "bot:coder"]}))["result"]["room"]
    stopped = check("rooms.stop.result", await call(phone, "2", "rooms.stop", {"room_id": room["id"]}))["result"]
    assert stopped == {"stopped": 1}
    note = await until(phone, "rooms.update", lambda p: any(x["kind"] == "note" for x in p["messages"]))
    assert [x["text"] for x in note["messages"] if x["kind"] == "note"] == ["Stopped. Mention a member (or @all) to go on."]
    for params, code in (({"room_id": "r-nope"}, m.NOT_FOUND),):
        assert (await call(phone, "3", "rooms.open", params))["error"]["code"] == code
    # one member, a repeated member (both refused by the schema check in call()), then an unknown bot
    unknown = await call(phone, "4", "rooms.create", {"name": "x", "members": ["bot:scout", "bot:nobody"]})
    assert unknown["error"]["code"] == m.NOT_FOUND
    twice = await chat.rooms.handle("rooms.list", {})
    assert [r["name"] for r in twice["rooms"]] == ["Two"]
    from talaria_bridge.rooms import RoomError
    for bad in ({"name": "x", "members": ["bot:scout"]}, {"name": "x", "members": ["bot:scout", "bot:scout"]}, {"name": " ", "members": ["bot:scout", "bot:coder"]}):
        with pytest.raises(RoomError) as err:
            await chat.rooms.handle("rooms.create", bad)
        assert err.value.code == m.INVALID_PARAMS
    assert len([1 for name, _ in fake.calls if name == "groups.create"]) == 1


async def test_a_room_made_on_the_laptop_appears_on_the_phone(rooms_bridge):
    bridge, fake, chat = rooms_bridge
    phone = await connected(bridge)
    fake.rooms["desk-1"] = {"room_id": "desk-1", "name": "From Desktop", "members": [
        {"member_id": "scout", "profile": "scout", "handle": "scout"}, {"member_id": "coder", "profile": "coder", "handle": "coder"}],
        "authority_gateway_id": "install:abc", "authority_epoch": 1, "revision": 1, "created_at": 1.0, "updated_at": 2.0, "latest_seq": 0}
    fake.logs["desk-1"] = []
    fake.event("desk-1", "message.user", {"kind": "user", "id": "desktop"}, {"text": "Hello from the laptop", "thread_id": "t1"})
    changed = await until(phone, "rooms.changed", lambda p: any(r["id"] == "desk-1" for r in p["rooms"]))
    row = next(r for r in changed["rooms"] if r["id"] == "desk-1")
    assert row["preview"] == {"speaker": "You", "text": "Hello from the laptop"} and row["name"] == "From Desktop"


async def test_without_a_doorway_there_are_no_rooms(tmp_path: Path, settings: ServerSettings):
    hermes = FakeHermes()

    async def unused(msg: dict) -> None:
        pass

    chat = ChatService(ChatStore(tmp_path / "chat.db"), {"hermes": HermesClient("http://hermes.test", KEY, transport=hermes.transport())}, unused)
    registry = Registry(tmp_path / "bridge.db")
    server = BridgeServer(registry, keys.generate_key(), settings, chat=chat)
    async with server.serve() as ws_server:
        phone = await connected(Bridge(server, registry, f"ws://127.0.0.1:{ws_server.sockets[0].getsockname()[1]}/tnp"))
        assert (await call(phone, "1", "rooms.list"))["error"]["code"] == m.METHOD_NOT_FOUND
    chat.store.close()
    registry.close()


async def test_a_room_is_renamed_its_stuck_turns_retried_and_it_is_disbanded(rooms_bridge):
    bridge, fake, chat = rooms_bridge
    phone = await connected(bridge)
    room = (await call(phone, "1", "rooms.create", {"name": "Trip planning", "members": ["bot:scout", "bot:research"]}))["result"]["room"]
    renamed = check("rooms.rename.result", await call(phone, "2", "rooms.rename", {"room_id": room["id"], "name": "Goa trip"}))
    assert renamed["result"]["room"]["name"] == "Goa trip" and fake.rooms[room["id"]]["name"] == "Goa trip"
    seen = await until(phone, "rooms.changed", lambda p: any(r["name"] == "Goa trip" for r in p["rooms"]))
    assert [r["name"] for r in seen["rooms"]] == ["Goa trip"]

    fake.pending[room["id"]] = [{"kind": "retry", "task_id": "task-7"}]
    opened = check("rooms.open.result", await call(phone, "3", "rooms.open", {"room_id": room["id"]}))["result"]
    assert opened["stuck"] == 1
    retried = check("rooms.retry.result", await call(phone, "4", "rooms.retry", {"room_id": room["id"]}))
    assert retried["result"] == {"retried": 1}
    assert [p["task_id"] for name, p in fake.calls if name == "groups.retry"] == ["task-7"]

    assert check("rooms.disband.result", await call(phone, "5", "rooms.disband", {"room_id": room["id"]}))
    assert (await until(phone, "rooms.changed", lambda p: p["rooms"] == []))["rooms"] == []
    gone = await call(phone, "6", "rooms.open", {"room_id": room["id"]})
    assert gone["error"]["code"] == m.NOT_FOUND
    bad = await call(phone, "7", "rooms.create", {"name": "x", "members": ["bot:scout", "bot:research"]})
    assert "result" in bad

