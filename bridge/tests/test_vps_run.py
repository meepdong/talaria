import json
from pathlib import Path

import pytest

from talaria_bridge.agent_tools import AgentTools
from talaria_bridge.todos import TodoStore
from talaria_bridge.vps_config import VPSAllowlist

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
