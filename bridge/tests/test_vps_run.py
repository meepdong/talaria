import asyncio
import json
import sys
from pathlib import Path

import pytest

import talaria_bridge.agent_tools as agent_tools_module
from talaria_bridge.agent_tools import AgentTools
from talaria_bridge.todos import TodoStore
from talaria_bridge.vps_config import VPSAllowlist

from conftest import check
from test_chat import call, chat_bridge, connected, recv  # noqa: F401 (chat_bridge is a fixture)

# vps_run runs commands on the Linux VPS (process groups, /bin/echo); there is nothing to test on Windows.
pytestmark = pytest.mark.skipif(sys.platform == "win32", reason="vps_run is Linux-only")

ALLOWLIST = """
version: 1
commands:
  - name: "echo"
    path: "/bin/echo"
    args_pattern: "^hello( world)?$"
    timeout: 10
    description: "test"
  - name: "sleep"
    path: "/bin/sleep"
    args_pattern: "^[0-9]+$"
    timeout: 10
    description: "test"
"""


@pytest.fixture
def tools(tmp_path: Path):
    (tmp_path / "allow.yaml").write_text(ALLOWLIST)
    todos = TodoStore(tmp_path / "chat.db")

    async def changed() -> None:
        pass

    t = AgentTools(todos, {"t" * 40: "hermes"}, changed, allowlist=VPSAllowlist.load(str(tmp_path / "allow.yaml")))
    yield t
    todos.close()


def answer_with(tools: AgentTools, choice: str) -> list[dict]:
    """Every approval request is answered with [choice] as soon as it is broadcast."""
    sent: list[dict] = []

    async def broadcast(msg: dict) -> None:
        sent.append(msg)
        tools.resolve_vps_approval(msg["params"]["approval_id"], choice)

    tools.set_broadcast(broadcast)
    return sent


def result(out: dict) -> tuple[dict | str, bool]:
    text = out["content"][0]["text"]
    return (text if out.get("isError") else json.loads(text)), bool(out.get("isError"))


async def test_listed_only_with_an_allowlist(tools: AgentTools, tmp_path: Path):
    listed = await tools.rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, "hermes")
    assert "vps_run" in [t["name"] for t in listed["result"]["tools"]]

    async def changed() -> None:
        pass

    bare = AgentTools(tools.todos, {}, changed)
    listed = await bare.rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, "hermes")
    assert "vps_run" not in [t["name"] for t in listed["result"]["tools"]]
    called = await bare.rpc({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                             "params": {"name": "vps_run", "arguments": {"command": "echo"}}}, "hermes")
    assert called["error"]["message"] == "VPS commands are disabled"


async def test_approved_command_runs_without_a_shell(tools: AgentTools):
    sent = answer_with(tools, "once")
    out, err = result(await tools.call("vps_run", {"command": "echo", "args": ["hello", "world"], "cwd": "/"}, "hermes"))
    assert not err and out["exit_code"] == 0 and out["output"] == "hello world"
    assert sent[0]["method"] == "vps.approval.request"
    assert sent[0]["params"]["command"] == "echo" and sent[0]["params"]["args"] == ["hello", "world"]


async def test_denied_command_does_not_run(tools: AgentTools):
    answer_with(tools, "deny")
    out, err = result(await tools.call("vps_run", {"command": "echo", "args": ["hello"]}, "hermes"))
    assert err and "denied" in out


@pytest.mark.parametrize("args", [
    {"command": "rm", "args": ["-rf", "/"]},                # not allowlisted
    {"command": "echo", "args": ["hello", "; id"]},         # args outside the pattern
    {"command": "echo", "args": ["hello;", "id"]},
    {"command": "echo", "args": "hello"},                   # args must be a list
    {"command": "echo", "args": ["hello"], "timeout": 0},
])
async def test_rejected_before_asking(tools: AgentTools, args: dict):
    sent = answer_with(tools, "once")
    out, err = result(await tools.call("vps_run", args, "hermes"))
    assert err and sent == []


async def test_timeout_kills_the_command(tools: AgentTools):
    answer_with(tools, "once")
    out, err = result(await tools.call("vps_run", {"command": "sleep", "args": ["5"], "timeout": 1, "cwd": "/"},
                                       "hermes"))
    assert err and "timed out" in out


async def test_rate_limit(tools: AgentTools):
    answer_with(tools, "once")
    for _ in range(5):
        assert not result(await tools.call("vps_run", {"command": "echo", "args": ["hello"], "cwd": "/"}, "hermes"))[1]
    out, err = result(await tools.call("vps_run", {"command": "echo", "args": ["hello"], "cwd": "/"}, "hermes"))
    assert err and "Rate limit" in out


async def test_requests_follow_the_spec_and_expire(tools: AgentTools, monkeypatch):
    monkeypatch.setattr(agent_tools_module, "VPS_APPROVAL_TIMEOUT", 0.2)
    sent: list[dict] = []

    async def broadcast(msg: dict) -> None:
        sent.append(msg)

    tools.set_broadcast(broadcast)
    out, err = result(await tools.call("vps_run", {"command": "echo", "args": ["hello"], "cwd": "/"}, "hermes"))
    assert err and "timed out" in out
    request, done = sent
    check("vps.approval.request", request)
    assert check("vps.approval.done", done)["params"] == {"approval_id": request["params"]["approval_id"],
                                                           "choice": "expired"}
    assert tools.pending_vps_requests() == []


async def test_a_device_that_connects_later_still_gets_the_request(chat_bridge, tmp_path: Path):  # noqa: F811
    bridge, _ = chat_bridge
    chat = bridge.server.chat
    (tmp_path / "allow.yaml").write_text(ALLOWLIST)
    tools = AgentTools(chat.todos, {}, chat.todos_changed, allowlist=VPSAllowlist.load(str(tmp_path / "allow.yaml")))
    chat.agent_tools = tools
    tools.set_broadcast(bridge.server.broadcast)

    running = asyncio.create_task(tools.call("vps_run", {"command": "echo", "args": ["hello"], "cwd": "/"}, "hermes"))
    while not tools.pending_vps_requests():
        await asyncio.sleep(0.01)

    phone = await connected(bridge)  # opened after the agent asked
    while (msg := await recv(phone))["method"] != "vps.approval.request":
        pass
    approval_id = check("vps.approval.request", msg)["params"]["approval_id"]
    answer = check("vps.approve.result", await call(phone, "v1", "vps.approve",
                                                    {"approval_id": approval_id, "choice": "once"}))
    assert answer["result"] == {"approval_id": approval_id, "choice": "once"}
    out, err = result(await running)
    assert not err and out["output"] == "hello"
    assert tools.pending_vps_requests() == []
    again = await call(phone, "v2", "vps.approve", {"approval_id": approval_id, "choice": "once"})
    assert "error" in again

