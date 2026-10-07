"""Hermes's Kanban board and usage (board.py, spec/README.md §18.4–18.5) through the doorway: a fake of the board's
REST addresses behaves like Hermes's where the bridge relies on it (columns, ready vs todo, refused moves, comments)."""

import asyncio
import json
from pathlib import Path

import pytest
import websockets

from talaria_bridge.board import Board
from talaria_bridge.bots import Bots
from talaria_bridge.chat import ChatService, ChatStore
from talaria_bridge.hermes import HermesClient
from talaria_bridge.hermes_serve import HermesBackend, ServeError
from talaria_bridge.protocol import keys
from talaria_bridge.protocol import messages as m
from talaria_bridge.registry import Registry
from talaria_bridge.server import BridgeServer, ServerSettings

from conftest import Bridge, check
from test_bots import TOKEN, FakeBotServe
from test_chat import KEY, FakeHermes, connected, recv
from test_commands import call

COLUMNS = ["triage", "todo", "scheduled", "ready", "running", "blocked", "review", "done"]


class FakeKanban:
    def __init__(self):
        self.tasks: dict[str, dict] = {}
        self.comments: dict[str, list] = {}
        self.event = 0
        self.calls: list[tuple[str, str, dict | None]] = []

    def _task(self, title, status="ready", assignee=None, **kw):
        tid = f"t_{len(self.tasks) + 1}"
        self.tasks[tid] = {"id": tid, "title": title, "body": kw.get("body"), "assignee": assignee, "status": status,
                           "priority": 0, "created_at": 1700000000 + len(self.tasks), "started_at": None,
                           "completed_at": None, "result": None, "last_failure_error": None, "latest_summary": None}
        self.comments[tid] = []
        self.event += 1
        return self.tasks[tid]

    async def rest(self, path: str, method: str = "GET", body: dict | None = None):
        self.calls.append((method, path, body))
        if path.startswith("/api/analytics/usage"):
            return {"daily": [{"day": "2026-10-05", "input_tokens": 100, "output_tokens": 20, "estimated_cost": 0.5,
                               "actual_cost": 0, "sessions": 2, "api_calls": 7},
                              {"day": "2026-10-06", "input_tokens": 50, "output_tokens": 5, "estimated_cost": 0.25,
                               "actual_cost": 0.3, "sessions": 1, "api_calls": 3}]}
        if path.startswith("/api/analytics/models"):
            return {"models": [{"model": "cheap/one", "estimated_cost": 0.05, "actual_cost": 0, "input_tokens": 5,
                                "output_tokens": 1, "sessions": 1, "api_calls": 1},
                               {"model": "qwen/qwen3.8-flash", "estimated_cost": 0.7, "actual_cost": 0,
                                "input_tokens": 145, "output_tokens": 24, "sessions": 2, "api_calls": 9}]}
        rest = path.removeprefix("/api/plugins/kanban")
        if rest == "/board":
            cols = {c: [] for c in COLUMNS}
            for t in self.tasks.values():
                if t["status"] in cols:
                    cols[t["status"]].append({**t, "comment_count": len(self.comments[t["id"]])})
            return {"columns": [{"name": c, "tasks": cols[c]} for c in COLUMNS], "latest_event_id": self.event}
        if rest == "/tasks" and method == "POST":
            status = "triage" if body["triage"] else "ready"
            return {"task": self._task(body["title"], status, body["assignee"], body=body["body"]),
                    **({} if body["assignee"] else {"warning": "no assignee"})}
        parts = rest.split("/")  # ["", "tasks", id, maybe "comments"]
        task = self.tasks.get(parts[2]) if len(parts) > 2 else None
        if task is None:
            raise ServeError(404, json.dumps({"detail": f"task {parts[-1]} not found"}))
        if len(parts) == 4 and method == "POST":
            self.comments[task["id"]].append({"author": body["author"], "body": body["body"], "created_at": 1700000100})
            self.event += 1
            return {"ok": True}
        if method == "PATCH":
            if body.get("status") == "ready" and task["title"] == "Blocked one":
                raise ServeError(409, json.dumps({"detail": "Cannot move to 'ready': blocked by parent(s) not done"}))
            for k in ("status", "title", "body", "priority"):
                if k in body:
                    task[k] = body[k]
            if "assignee" in body:
                task["assignee"] = body["assignee"] or None
            self.event += 1
            return {"task": task}
        return {"task": task, "comments": self.comments[task["id"]], "events": []}


@pytest.fixture
async def board_bridge(tmp_path: Path, settings: ServerSettings):
    fake = FakeBotServe()
    serve = await websockets.serve(fake.handler, "127.0.0.1", 0, process_request=fake.process_request)
    backend = HermesBackend(f"ws://127.0.0.1:{serve.sockets[0].getsockname()[1]}/api/ws", TOKEN, backoff=(0.05, 0.1))
    kanban = FakeKanban()
    backend.rest = kanban.rest
    hermes = FakeHermes()

    async def unused(msg: dict) -> None:
        pass

    chat = ChatService(ChatStore(tmp_path / "chat.db"),
                       {"hermes": HermesClient("http://hermes.test", KEY, transport=hermes.transport())}, unused)
    chat.bots = Bots(backend)
    chat.board = Board(backend, every_s=0.05)
    chat.board.broadcast = lambda msg: chat.broadcast(msg)
    registry = Registry(tmp_path / "bridge.db")
    server = BridgeServer(registry, keys.generate_key(), settings, chat=chat, hermes=backend)
    async with server.serve() as ws_server:
        for _ in range(100):
            if backend.connected:
                break
            await asyncio.sleep(0.02)
        yield Bridge(server, registry, f"ws://127.0.0.1:{ws_server.sockets[0].getsockname()[1]}/tnp"), kanban, chat
    chat.store.close()
    registry.close()
    serve.close()
    await serve.wait_closed()


async def changed(ws) -> dict:
    while True:
        msg = await recv(ws)
        if msg.get("method") == "board.changed":
            return check("board.changed", msg)["params"]


async def test_the_board_shows_every_column_and_who_has_each_task(board_bridge):
    bridge, kanban, _ = board_bridge
    kanban._task("Draft the vendor email", "running", "chainmail")
    kanban._task("Weekly review", "done", "default")
    phone = await connected(bridge)
    got = check("board.get.result", await call(phone, "1", "board.get"))["result"]
    assert got["available"] and [c["name"] for c in got["columns"]] == COLUMNS
    running = got["columns"][4]["tasks"]
    assert running[0]["title"] == "Draft the vendor email" and running[0]["assignee"] == "bot:chainmail"
    assert got["columns"][7]["tasks"][0]["assignee"] == "assistant"


async def test_a_task_for_a_bot_starts_one_for_nobody_waits(board_bridge):
    bridge, kanban, _ = board_bridge
    phone = await connected(bridge)
    given = check("board.add.result", await call(phone, "1", "board.add",
                                                  {"title": "Summarise the Monday call", "assignee": "bot:meetingminder"}))
    assert given["result"]["task"]["status"] == "ready" and given["result"]["task"]["assignee"] == "bot:meetingminder"
    assert kanban.calls[0] == ("POST", "/api/plugins/kanban/tasks", {"title": "Summarise the Monday call", "body": None,
                                                                    "assignee": "meetingminder", "triage": False})
    loose = (await call(phone, "2", "board.add", {"title": "Think about Q4"}))["result"]
    assert loose["task"]["status"] == "todo" and loose["task"]["assignee"] is None
    mine = (await call(phone, "3", "board.add", {"title": "Mine", "assignee": "assistant", "triage": True}))["result"]
    assert mine["task"]["status"] == "triage" and kanban.tasks[mine["task"]["id"]]["assignee"] == "default"
    bad = await call(phone, "4", "board.add", {"title": "x", "assignee": "nobody"})
    assert bad["error"]["code"] == m.INVALID_PARAMS


async def test_moving_giving_and_commenting(board_bridge):
    bridge, kanban, _ = board_bridge
    t = kanban._task("Book the hall", "todo")
    blocked = kanban._task("Blocked one", "todo")
    phone, laptop = await connected(bridge), await connected(bridge)
    await call(laptop, "0", "board.get")
    moved = check("board.update.result", await call(phone, "1", "board.update",
                                                     {"task_id": t["id"], "status": "ready", "assignee": "bot:vendordesk"}))
    assert moved["result"]["task"]["status"] == "ready" and kanban.tasks[t["id"]]["assignee"] == "vendordesk"
    assert [x["title"] for x in (await changed(laptop))["columns"][3]["tasks"]] == ["Book the hall"]
    refused = await call(phone, "2", "board.update", {"task_id": blocked["id"], "status": "ready"})
    assert refused["error"]["code"] == m.CONFLICT and "blocked by parent" in refused["error"]["message"]
    assert (await call(phone, "3", "board.update", {"task_id": t["id"], "status": "running"}))["error"]["code"] == m.INVALID_PARAMS
    assert (await call(phone, "4", "board.update", {"task_id": "t_99", "status": "done"}))["error"]["code"] == m.NOT_FOUND
    unassigned = (await call(phone, "5", "board.update", {"task_id": t["id"], "assignee": ""}))["result"]
    assert unassigned["task"]["assignee"] is None

    assert check("board.comment.result", await call(phone, "6", "board.comment", {"task_id": t["id"], "text": "Ask for 50 seats"}))
    full = check("board.task.result", await call(phone, "7", "board.task", {"task_id": t["id"]}))["result"]
    assert full["comments"] == [{"author": "owner", "text": "Ask for 50 seats", "at": 1700000100}]
    assert full["task"]["comments"] == 1


async def test_a_watched_board_tells_devices_when_hermes_changes_it(board_bridge):
    bridge, kanban, _ = board_bridge
    phone = await connected(bridge)
    await call(phone, "1", "board.get")
    t = kanban._task("A bot made this", "ready", "research")
    seen = await changed(phone)
    assert seen["columns"][3]["tasks"][0]["id"] == t["id"]


async def test_usage_adds_up_days_and_ranks_models(board_bridge):
    bridge, _, _ = board_bridge
    phone = await connected(bridge)
    used = check("usage.get.result", await call(phone, "1", "usage.get", {"days": 7}))["result"]
    assert used["total"]["cost_usd"] == 0.8 and used["total"]["estimated"] and used["total"]["calls"] == 10
    assert used["by_day"][1] == {"day": "2026-10-06", "cost_usd": 0.3, "estimated": False, "input_tokens": 50,
                                 "output_tokens": 5, "sessions": 1, "calls": 3}
    assert [x["model"] for x in used["by_model"]] == ["qwen/qwen3.8-flash", "cheap/one"]
    await phone.send(m.encode(m.request("2", "usage.get", {"days": 0})))  # not what the schema allows
    while (msg := await recv(phone)).get("id") != "2":
        pass
    assert msg["error"]["code"] == m.INVALID_PARAMS
