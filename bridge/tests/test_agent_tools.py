import json
from pathlib import Path

import httpx
import pytest

from talaria_bridge.agent_tools import AgentTools
from talaria_bridge.agents import load_agents
from talaria_bridge.cli import make_agent_tools
from talaria_bridge.todos import TodoStore

from conftest import check
from test_chat import call, chat_bridge, connected, recv  # noqa: F401 (chat_bridge is a fixture)

TOKEN = "t" * 40
AUTH = {"Authorization": f"Bearer {TOKEN}"}


async def mcp(http: httpx.AsyncClient, msg_id: int, method: str, params: dict | None = None, **kw) -> dict:
    resp = await http.post("/mcp", json={"jsonrpc": "2.0", "id": msg_id, "method": method, "params": params or {}},
                           headers=kw.pop("headers", AUTH))
    assert resp.status_code == 200, resp.status_code
    return resp.json()


async def tool(http: httpx.AsyncClient, name: str, args: dict) -> tuple[dict | str, bool]:
    result = (await mcp(http, 9, "tools/call", {"name": name, "arguments": args}))["result"]
    text = result["content"][0]["text"]
    return (text if result.get("isError") else json.loads(text)), bool(result.get("isError"))


async def next_changed(ws) -> dict:
    while True:
        msg = await recv(ws)
        if msg.get("method") == "todos.changed":
            return check("todos.changed", msg)["params"]


async def test_hermes_keeps_the_list_every_device_sees(chat_bridge):  # noqa: F811
    bridge, _ = chat_bridge
    chat = bridge.server.chat
    phone = await connected(bridge)
    tools = AgentTools(chat.todos, {TOKEN: "hermes"}, chat.todos_changed)
    async with tools.serve(0) as server:
        port = server.sockets[0].getsockname()[1]
        async with httpx.AsyncClient(base_url=f"http://127.0.0.1:{port}") as http:
            init = await mcp(http, 1, "initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
                                                    "clientInfo": {"name": "hermes", "version": "0"}})
            assert init["result"]["protocolVersion"] == "2025-03-26"
            note = await http.post("/mcp", json={"jsonrpc": "2.0", "method": "notifications/initialized"},
                                   headers=AUTH)
            assert note.status_code == 202
            names = [t["name"] for t in (await mcp(http, 2, "tools/list"))["result"]["tools"]]
            assert names == ["todo_list", "todo_add", "todo_update", "todo_comment"]

            added, err = await tool(http, "todo_add", {"text": "Buy milk", "due": "2026-10-05"})
            assert not err and added["todo"]["text"] == "Buy milk" and added["todo"]["due"] == "2026-10-05"
            todo_id = added["todo"]["id"]
            seen = (await next_changed(phone))["todos"]
            assert [t["text"] for t in seen] == ["Buy milk"]
            listed = check("todos.list.result", await call(phone, "l1", "todos.list"))
            assert listed["result"]["todos"][0]["id"] == todo_id

            done, err = await tool(http, "todo_update", {"id": todo_id, "done": True})
            assert not err and done["todo"]["done"] is True
            assert (await next_changed(phone))["todos"][0]["done"] is True
            assert (await tool(http, "todo_list", {}))[0] == {"todos": []}
            assert len((await tool(http, "todo_list", {"include_done": True}))[0]["todos"]) == 1

            await tool(http, "todo_update", {"id": todo_id, "done": False, "group": "Errands"})
            await next_changed(phone)
            await call(phone, "c1", "todos.comment", {"id": todo_id, "text": "Oat milk"})
            noted, err = await tool(http, "todo_comment", {"id": todo_id, "text": "Added to the shop order"})
            assert not err and noted["todo"]["comments"] == [{"text": "Oat milk", "by": "you"},
                                                             {"text": "Added to the shop order", "by": "agent"}]
            seen = (await next_changed(phone))["todos"][0]
            assert seen["group"] == "Errands" and seen["comments"][1]["by"] == "agent"
            (listed,) = (await tool(http, "todo_list", {}))[0]["todos"]
            assert listed["group"] == "Errands" and len(listed["comments"]) == 2
            put, err = await tool(http, "todo_add", {"text": "Gym", "group": "Health"})
            assert put["todo"]["group"] == "Health"

            missing, err = await tool(http, "todo_update", {"id": "td-nope", "done": True})
            assert err and isinstance(missing, str)
            empty, err = await tool(http, "todo_add", {"text": ""})
            assert err
            unknown = await mcp(http, 3, "tools/call", {"name": "todo_delete", "arguments": {"id": todo_id}})
            assert unknown["error"]["code"] == -32602
            assert (await mcp(http, 4, "resources/list"))["error"]["code"] == -32601


async def test_only_its_agent_on_loopback(tmp_path: Path):
    todos = TodoStore(tmp_path / "chat.db")

    async def changed() -> None:
        pass

    tools = AgentTools(todos, {TOKEN: "hermes"}, changed)
    body = {"jsonrpc": "2.0", "id": 1, "method": "ping"}
    async with tools.serve(0) as server:
        port = server.sockets[0].getsockname()[1]
        async with httpx.AsyncClient(base_url=f"http://127.0.0.1:{port}") as http:
            assert (await http.post("/mcp", json=body)).status_code == 401
            assert (await http.post("/mcp", json=body, headers={"Authorization": "Bearer " + "x" * 40})).status_code == 401
            assert (await http.post("/mcp", json=body, headers={**AUTH, "Origin": "https://evil.example"})).status_code == 403
            assert (await http.post("/mcp", json=body, headers={**AUTH, "Origin": "http://localhost:3000"})).status_code == 200
            assert (await http.get("/mcp", headers=AUTH)).status_code == 405
            assert (await http.post("/other", json=body, headers=AUTH)).status_code == 404
            big = await http.post("/mcp", content=b"x" * (300 * 1024), headers=AUTH)
            assert big.status_code == 413
        async with httpx.AsyncClient(base_url=f"http://127.0.0.1:{port}") as http:
            bad = await http.post("/mcp", content=b"{nope", headers=AUTH)
            assert bad.status_code == 400 and bad.json()["error"]["code"] == -32700
            ok = await http.post("/mcp", json=[body, {"jsonrpc": "2.0", "method": "notifications/x"}], headers=AUTH)
            assert ok.json() == [{"jsonrpc": "2.0", "id": 1, "result": {}}]
    todos.close()


@pytest.mark.parametrize("key, ok", [("k" * 32, True), ("short", False)])
def test_tools_key_from_agents_json(tmp_path: Path, key: str, ok: bool):
    (tmp_path / "tools.key").write_text(key + "\n")
    (tmp_path / "agents.json").write_text(json.dumps({"agents": [
        {"id": "hermes", "health_url": "http://127.0.0.1:9119/api/status", "tools_key_file": str(tmp_path / "tools.key")}]}))
    agents = load_agents(tmp_path / "agents.json")
    assert agents[0].tools_key_file == str(tmp_path / "tools.key")

    class Chat:
        todos = TodoStore(tmp_path / "chat.db")

        async def todos_changed(self) -> None:
            pass

    tools = make_agent_tools(agents, Chat())
    assert (tools.tokens == {key: "hermes"}) if ok else tools is None
    assert make_agent_tools(agents, None) is None


def test_tools_key_file_must_be_absolute(tmp_path: Path):
    (tmp_path / "agents.json").write_text(json.dumps({"agents": [
        {"id": "hermes", "health_url": "http://127.0.0.1:9119/", "tools_key_file": "tools.key"}]}))
    with pytest.raises(ValueError, match="tools_key_file"):
        load_agents(tmp_path / "agents.json")
