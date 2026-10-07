"""Hermes's own / commands in a chat (commands.py, spec/README.md §18.3): listed, run at once, sent as a message,
put in the composer, or held for a signed approval; in a bot's chat and in a chat with Hermes itself."""

import asyncio
from pathlib import Path

import pytest
import websockets

from talaria_bridge.bots import Bots
from talaria_bridge.chat import ChatService, ChatStore
from talaria_bridge.commands import Commands
from talaria_bridge.hermes import HermesClient
from talaria_bridge.hermes_serve import HermesBackend
from talaria_bridge.protocol import keys
from talaria_bridge.protocol import messages as m
from talaria_bridge.registry import Registry
from talaria_bridge.server import BridgeServer, ServerSettings

from conftest import Bridge, check, paired_device
from test_bots import TOKEN, FakeBotServe
from test_chat import KEY, FakeHermes, connected, recv, until_done

SKILL = "[IMPORTANT: The user has invoked the weekly skill] Plan the week."
CATALOG = {
    "pairs": [["/help", "Show help"], ["/usage", "Show token usage"], ["/yolo", "Skip approvals"],
              ["/kanban", "Kanban board"], ["/plan", "Plan first"], ["/undo", "Back up a turn"], ["/quit", "Quit"],
              ["/retry", "Retry"], ["/weekly", "Weekly review"], ["/h", "Show help"]],
    "canon": {"/help": "/help", "/h": "/help", "/usage": "/usage", "/yolo": "/yolo", "/kanban": "/kanban",
              "/plan": "/plan", "/undo": "/undo", "/quit": "/quit", "/retry": "/retry"},
    "categories": [{"name": "Info", "pairs": [["/help", "Show help"], ["/usage", "Show token usage"]]},
                   {"name": "Session", "pairs": [["/undo", "Back up a turn"], ["/plan", "Plan first"]]}],
    "skills": {"/weekly": {"usage": 1, "origin": "local"}},
}


class CommandServe(FakeBotServe):
    """The bots' fake hermes serve, plus Hermes's commands and the API server's sessions (profile default)."""

    async def answer(self, ws, msg):
        method, p = msg["method"], msg.get("params") or {}

        async def ok(result):
            self.calls.append((method, p))
            await self.send(ws, {"jsonrpc": "2.0", "id": msg["id"], "result": result})

        async def err(code, text):
            self.calls.append((method, p))
            await self.send(ws, {"jsonrpc": "2.0", "id": msg["id"], "error": {"code": code, "message": text}})

        if method == "commands.catalog":
            return await ok(CATALOG)
        if method == "session.resume" and p.get("profile") == "default":
            return await ok({"session_id": "live-home", "resumed": p["session_id"], "session_key": p["session_id"]})
        if method == "slash.exec":
            name, _, arg = p["command"][1:].partition(" ")
            if name == "weekly":
                return await err(4018, "skill command: use command.dispatch for /weekly")
            if name == "plan":
                return await ok({"type": "send", "message": f"PLAN: {arg}"})
            if name == "undo":
                return await ok({"type": "prefill", "message": "Find me a cafe", "notice": "Backed up one turn"})
            return await ok({"output": f"{name} says {arg or 'hello'} in {p['session_id']}"})
        if method == "command.dispatch":
            return await ok({"type": "skill", "message": SKILL, "name": p["name"]})
        await super().answer(ws, msg)


@pytest.fixture
async def cmd_bridge(tmp_path: Path, settings: ServerSettings):
    fake = CommandServe()
    serve = await websockets.serve(fake.handler, "127.0.0.1", 0, process_request=fake.process_request)
    backend = HermesBackend(f"ws://127.0.0.1:{serve.sockets[0].getsockname()[1]}/api/ws", TOKEN, backoff=(0.05, 0.1))
    hermes = FakeHermes()

    async def unused(msg: dict) -> None:
        pass

    chat = ChatService(ChatStore(tmp_path / "chat.db"),
                       {"hermes": HermesClient("http://hermes.test", KEY, transport=hermes.transport())}, unused)
    chat.bots = Bots(backend)
    chat.commands = Commands(backend, chat, "hermes")
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


_seen: dict[int, list[dict]] = {}  # notifications that arrived while waiting for a reply


async def call(ws, msg_id: str, method: str, params: dict | None = None) -> dict:
    await ws.send(m.encode(check(method, m.request(msg_id, method, params if params is not None else {}))))
    while True:
        msg = await recv(ws)
        if msg.get("id") == msg_id:
            return msg
        if "method" in msg:
            _seen.setdefault(id(ws), []).append(msg)


async def note(ws, method: str) -> dict:
    kept = _seen.setdefault(id(ws), [])
    for msg in kept:
        if msg.get("method") == method:
            kept.remove(msg)
            return check(method, msg)["params"]
    while True:
        msg = await recv(ws)
        if msg.get("method") == method:
            return check(method, msg)["params"]


def signed(device, n: dict, choice: str = "once") -> dict:
    sig = keys.sign(device.key, m.ops_approve_signed_data(n["request_id"], device.id, n["op"], n["params_json"], choice))
    return {"request_id": n["request_id"], "choice": choice, "sig": sig}


async def bot_chat(phone, bot="bot:scout") -> str:
    return (await call(phone, "open", "bots.open", {"bot_id": bot}))["result"]["conversation_id"]


async def test_the_list_leaves_out_talarias_own_and_terminal_commands(cmd_bridge):
    bridge, _, _ = cmd_bridge
    phone = await connected(bridge)
    conv = await bot_chat(phone)
    listed = check("commands.list.result", await call(phone, "1", "commands.list", {"conversation_id": conv}))["result"]
    by_name = {c["name"]: c for c in listed["commands"]}
    assert listed["available"] and set(by_name) == {"help", "usage", "yolo", "kanban", "plan", "undo", "weekly"}
    assert by_name["help"]["category"] == "Info" and by_name["weekly"]["category"] == "Skills"
    assert by_name["yolo"]["approve"] and not by_name["help"]["approve"] and not by_name["kanban"]["approve"]


async def test_read_only_commands_answer_at_once_in_the_bots_session(cmd_bridge):
    bridge, fake, _ = cmd_bridge
    phone = await connected(bridge)
    conv = await bot_chat(phone)
    done = check("commands.run.result", await call(phone, "1", "commands.run", {"conversation_id": conv, "text": "/h"}))
    assert done["result"] == {"status": "done", "command": "/h", "output": "help says hello in live-scout-1"}
    board = (await call(phone, "2", "commands.run", {"conversation_id": conv, "text": "/kanban list"}))["result"]
    assert board["status"] == "done" and board["output"].startswith("kanban says list")
    assert [p["profile"] for name, p in fake.calls if name == "slash.exec"] == ["scout", "scout"]


async def test_a_skill_command_becomes_a_message_the_bot_answers(cmd_bridge):
    bridge, fake, _ = cmd_bridge
    phone = await connected(bridge)
    conv = await bot_chat(phone)
    sent = check("commands.run.result", await call(phone, "1", "commands.run", {"conversation_id": conv, "text": "/weekly"}))
    assert sent["result"]["status"] == "sent"
    events = await until_done(phone, sent["result"]["turn"]["turn_id"])
    assert events[0]["params"]["user_text"] == "/weekly" and events[-1]["params"]["text"] == "Hi there"
    assert [p["text"] for name, p in fake.calls if name == "prompt.submit"] == [SKILL]
    plan = (await call(phone, "2", "commands.run", {"conversation_id": conv, "text": "/plan a trip"}))["result"]
    await until_done(phone, plan["turn"]["turn_id"])
    assert [p["text"] for name, p in fake.calls if name == "prompt.submit"][-1] == "PLAN: a trip"


async def test_prefill_and_refusals(cmd_bridge):
    bridge, _, _ = cmd_bridge
    phone = await connected(bridge)
    conv = await bot_chat(phone)
    undo = (await call(phone, "1", "commands.run", {"conversation_id": conv, "text": "/undo"}))["result"]
    assert undo == {"status": "prefill", "command": "/undo", "text": "Find me a cafe", "output": "Backed up one turn"}
    quit_ = await call(phone, "2", "commands.run", {"conversation_id": conv, "text": "/quit"})
    assert quit_["error"]["code"] == m.INVALID_PARAMS and "computer" in quit_["error"]["message"]
    unknown = await call(phone, "3", "commands.run", {"conversation_id": conv, "text": "/nope"})
    assert unknown["error"]["code"] == m.NOT_FOUND
    nowhere = await call(phone, "4", "commands.run", {"conversation_id": "c-0000000000000000", "text": "/help"})
    assert nowhere["error"]["code"] == m.NOT_FOUND


async def test_a_change_waits_for_a_signed_approval(cmd_bridge):
    bridge, fake, _ = cmd_bridge
    device = await paired_device(bridge)
    phone = await device.authenticate(bridge.url)
    conv = await bot_chat(phone)
    asked = check("commands.run.result", await call(phone, "1", "commands.run", {"conversation_id": conv, "text": "/yolo"}))
    assert asked["result"]["status"] == "pending" and asked["result"]["request_id"].startswith("hc-")
    card = await note(phone, "ops.approval.request")
    assert card["op"] == "hermes.command" and card["request_id"] == asked["result"]["request_id"]
    assert "/yolo" in card["summary"] and card["requested_by"] == f"device:{device.id}"
    assert not any(name == "slash.exec" for name, _ in fake.calls)

    # a device that comes back sees it again
    laptop = await connected(bridge)
    assert (await note(laptop, "ops.approval.request"))["request_id"] == card["request_id"]

    forged = dict(signed(device, card), sig=signed(device, card, "deny")["sig"])
    bad = await call(phone, "2", "ops.approve", forged)
    assert bad["error"]["code"] == m.INVALID_PARAMS
    ok = check("ops.approve.result", await call(phone, "3", "ops.approve", signed(device, card)))
    assert ok["result"] == {"request_id": card["request_id"], "choice": "once"}
    assert (await note(phone, "ops.approval.done"))["choice"] == "once"
    result = await note(phone, "ops.result")
    assert result["result"]["op"] == "hermes.command" and result["result"]["ok"]
    assert result["result"]["output"] == "yolo says hello in live-scout-1"
    again = await call(phone, "4", "ops.approve", signed(device, card))
    assert again["error"]["code"] == m.CONFLICT


async def test_a_denied_change_never_runs(cmd_bridge):
    bridge, fake, _ = cmd_bridge
    device = await paired_device(bridge)
    phone = await device.authenticate(bridge.url)
    conv = await bot_chat(phone)
    await call(phone, "1", "commands.run", {"conversation_id": conv, "text": "/kanban create Fix the gate"})
    card = await note(phone, "ops.approval.request")
    assert check("ops.approve.result", await call(phone, "2", "ops.approve", signed(device, card, "deny")))
    assert (await note(phone, "ops.approval.done"))["choice"] == "deny"
    assert not any(name == "slash.exec" for name, _ in fake.calls)


async def test_commands_run_in_a_chat_with_hermes_itself(cmd_bridge):
    bridge, fake, _ = cmd_bridge
    phone = await connected(bridge)
    sent = (await call(phone, "1", "chat.send", {"text": "Hello"}))["result"]
    await until_done(phone, sent["turn_id"])
    done = (await call(phone, "2", "commands.run", {"conversation_id": sent["conversation_id"], "text": "/usage"}))["result"]
    assert done == {"status": "done", "command": "/usage", "output": "usage says hello in live-home"}
    resumed = [p for name, p in fake.calls if name == "session.resume" and p.get("profile") == "default"]
    assert resumed[0]["session_id"] == f"talaria_{sent['conversation_id'][2:]}"
