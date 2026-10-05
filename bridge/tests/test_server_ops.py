"""Server operations end to end: a device or the agent asks, a device signs, talaria-ops checks and runs (§10.8, §16)."""

import asyncio
import json
import sys
from pathlib import Path

import pytest

if sys.platform == "win32":
    pytest.skip("talaria-ops is Linux-only", allow_module_level=True)

import talaria_bridge.ops.daemon as ops_daemon
from talaria_bridge.agent_tools import AgentTools
from talaria_bridge.ops.audit import AuditLog
from talaria_bridge.ops.catalogue import Run
from talaria_bridge.ops.client import OpsClient
from talaria_bridge.ops.daemon import OpsDaemon
from talaria_bridge.protocol import keys
from talaria_bridge.protocol import messages as m
from talaria_bridge.registry import Registry
from talaria_bridge.server import BridgeServer, ServerSettings
from talaria_bridge.server_ops import ServerOps
from talaria_bridge.todos import TodoStore

from conftest import Bridge, check, paired_device
from test_ops import FakeRunner

TOKEN = "t" * 40


@pytest.fixture
async def ops_bridge(tmp_path: Path, settings: ServerSettings):
    import os

    _seen.clear()  # buffers are keyed by id(ws), and ids are reused: never carry one over from another test

    registry = Registry(tmp_path / "bridge.db")
    runner = FakeRunner({("docker", "ps", "--format", "{{.Names}}"): Run(0, "web\n")})
    daemon = OpsDaemon(tmp_path / "bridge.db", AuditLog(tmp_path / "audit.jsonl"), runner)
    sock = tmp_path / "run" / "ops.sock"
    ops_server = await daemon.serve(sock, {os.getuid()})
    ops = ServerOps(OpsClient(sock))
    server = BridgeServer(registry, keys.generate_key(), settings, ops=ops)
    todos = TodoStore(tmp_path / "chat.db")

    async def changed() -> None:
        pass

    tools = AgentTools(todos, {TOKEN: "hermes"}, changed, ops)
    async with ops_server, server.serve() as ws_server:
        port = ws_server.sockets[0].getsockname()[1]
        yield Bridge(server, registry, f"ws://127.0.0.1:{port}/tnp"), runner, tools, daemon
    todos.close()
    registry.close()


_seen: dict[int, list[dict]] = {}  # notifications that arrived while waiting for a reply, per connection


async def request(ws, msg_id: str, method: str, params: dict) -> dict:
    await ws.send(m.encode(check(method, m.request(msg_id, method, params))))
    while True:
        msg = m.decode(await asyncio.wait_for(ws.recv(), 5))
        if msg.get("id") == msg_id:
            return msg
        _seen.setdefault(id(ws), []).append(msg)


async def notification(ws, method: str) -> dict:
    kept = _seen.setdefault(id(ws), [])
    for msg in kept:
        if msg.get("method") == method:
            kept.remove(msg)
            return check(method, msg)["params"]
    while True:
        msg = m.decode(await asyncio.wait_for(ws.recv(), 5))
        if msg.get("method") == method:
            return check(method, msg)["params"]


def signed(device, note: dict, choice: str = "once") -> dict:
    sig = keys.sign(device.key, m.ops_approve_signed_data(note["request_id"], device.id, note["op"],
                                                          note["params_json"], choice))
    return {"request_id": note["request_id"], "choice": choice, "sig": sig}


async def test_a_device_reads_at_once_and_approves_a_change(ops_bridge):
    bridge, runner, _, daemon = ops_bridge
    device = await paired_device(bridge)
    phone = await device.authenticate(bridge.url)

    cat = check("ops.catalogue.result", await request(phone, "c1", "ops.catalogue", {}))
    assert {o["op"]: o["tier"] for o in cat["result"]["ops"]}["apt.upgrade"] == 1

    read = check("ops.run.result", await request(phone, "r1", "ops.run", {"op": "docker.ps"}))
    assert read["result"]["status"] == "done" and read["result"]["result"]["op"] == "docker.ps"

    asked = check("ops.run.result", await request(phone, "r2", "ops.run",
                                                  {"op": "docker.restart", "params": {"container": "web"}}))
    assert asked["result"]["status"] == "pending"
    note = await notification(phone, "ops.approval.request")
    assert note["request_id"] == asked["result"]["request_id"] and note["params_json"] == '{"container":"web"}'
    assert note["summary"] == "Restart container web" and note["requested_by"] == f"device:{device.id}"
    assert ["docker", "restart", "web"] not in runner.argvs()

    answer = check("ops.approve.result", await request(phone, "a1", "ops.approve", signed(device, note)))
    assert answer["result"] == {"request_id": note["request_id"], "choice": "once"}
    assert (await notification(phone, "ops.approval.done"))["choice"] == "once"
    done = await notification(phone, "ops.result")
    assert done["result"]["ok"] and done["approved_by"] == device.id
    assert ["docker", "restart", "web"] in runner.argvs()
    assert daemon.audit.tail(1)[0]["approved_by"] == device.id


async def test_a_bad_signature_is_refused_and_the_request_stays_open(ops_bridge):
    bridge, runner, _, _ = ops_bridge
    device = await paired_device(bridge)
    phone = await device.authenticate(bridge.url)
    await request(phone, "r1", "ops.run", {"op": "disk.cleanup"})
    note = await notification(phone, "ops.approval.request")
    forged = {**signed(device, note), "sig": keys.sign(keys.generate_key(), b"x")}
    refused = await request(phone, "a1", "ops.approve", forged)
    assert "signature does not verify" in refused["error"]["message"]
    assert not any(a[:1] == ["journalctl"] for a in runner.argvs())
    ok = await request(phone, "a2", "ops.approve", signed(device, note))
    assert "result" in ok


async def test_the_agent_waits_for_the_owner(ops_bridge):
    bridge, runner, tools, _ = ops_bridge
    device = await paired_device(bridge)
    phone = await device.authenticate(bridge.url)
    names = [t["name"] for t in (await tools.rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, "hermes"))
             ["result"]["tools"]]
    assert "server_op" in names

    async def tool(op, params=None):
        out = await tools.call("server_op", {"op": op, **({"params": params} if params else {})}, "hermes")
        text = out["content"][0]["text"]
        return (text if out.get("isError") else json.loads(text)), bool(out.get("isError"))

    overview, err = await tool("services.list")
    assert not err and overview["op"] == "services.list"

    call = asyncio.ensure_future(tool("service.restart", {"service": "docker"}))
    note = await notification(phone, "ops.approval.request")
    assert note["requested_by"] == "agent:hermes" and note["summary"] == "Restart docker"
    await request(phone, "a1", "ops.approve", signed(device, note, "deny"))
    text, err = await call
    assert err and "denied" in text
    assert ["systemctl", "restart", "--no-block", "docker.service"] not in runner.argvs()

    call = asyncio.ensure_future(tool("service.restart", {"service": "docker"}))
    note = await notification(phone, "ops.approval.request")
    await request(phone, "a2", "ops.approve", signed(device, note))
    result, err = await call
    assert not err and result["summary"] == "restarting docker"

    bad, err = await tool("rm.rf")
    assert err and "unknown operation" in bad


async def test_a_device_that_connects_later_still_gets_the_request(ops_bridge):
    bridge, _, tools, _ = ops_bridge
    device = await paired_device(bridge)
    call = asyncio.ensure_future(tools.call("server_op", {"op": "apt.upgrade"}, "hermes"))
    while not bridge.server.ops.pending:
        await asyncio.sleep(0.01)
    phone = await device.authenticate(bridge.url)  # opened after the agent asked
    note = await notification(phone, "ops.approval.request")
    assert note["op"] == "apt.upgrade" and note["tier"] == 1
    await request(phone, "a1", "ops.approve", signed(device, note))
    out = await call
    assert not out.get("isError")


async def test_unanswered_requests_expire_everywhere(ops_bridge, monkeypatch):
    monkeypatch.setattr(ops_daemon, "APPROVAL_TTL_S", 1)
    bridge, runner, tools, _ = ops_bridge
    device = await paired_device(bridge)
    phone = await device.authenticate(bridge.url)
    call = asyncio.ensure_future(tools.call("server_op", {"op": "system.reboot"}, "hermes"))
    note = await notification(phone, "ops.approval.request")
    assert note["tier"] == 2
    assert (await notification(phone, "ops.approval.done")) == {"request_id": note["request_id"], "choice": "expired"}
    out = await call
    assert out["isError"] and "within 2 minutes" in out["content"][0]["text"]
    assert not any("reboot" in " ".join(a) for a in runner.argvs())
    assert bridge.server.ops.pending == {}


async def test_without_talaria_ops_nothing_is_offered(tmp_path: Path, settings: ServerSettings):
    todos = TodoStore(tmp_path / "chat.db")

    async def changed() -> None:
        pass

    tools = AgentTools(todos, {TOKEN: "hermes"}, changed)
    names = [t["name"] for t in (await tools.rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, "hermes"))
             ["result"]["tools"]]
    assert "server_op" not in names
    ops = ServerOps(OpsClient(tmp_path / "missing.sock"))
    text, err = await ops.agent_call("system.overview", {}, "hermes")
    assert err and "talaria-ops is not running" in text
    todos.close()
