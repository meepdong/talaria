"""Terminals end to end (spec §16.1): the owner watches and types into root's tmux sessions through a grant."""

import asyncio
import json
import sqlite3
import sys
from pathlib import Path

import pytest

if sys.platform == "win32":
    pytest.skip("talaria-ops is Linux-only", allow_module_level=True)

from talaria_bridge.agent_tools import AgentTools
from talaria_bridge.ops.audit import AuditLog
from talaria_bridge.ops.catalogue import OpError, Run
from talaria_bridge.ops.client import OpsClient
from talaria_bridge.ops.daemon import OpsDaemon
from talaria_bridge.ops.terminal import GRANT_IDLE_S, GRANT_MAX_S, Terminals
from talaria_bridge.protocol import keys
from talaria_bridge.protocol import messages as m
from talaria_bridge.registry import Registry
from talaria_bridge.server import BridgeServer, ServerSettings
from talaria_bridge.server_ops import ServerOps
from talaria_bridge.todos import TodoStore

from conftest import Bridge, check, paired_device
from test_server_ops import TOKEN, _seen, notification, request, signed


class FakeTmux:
    """Root's tmux with one session, "claude": its screen can change and it can end."""

    def __init__(self):
        self.screen = "\x1b[1mClaude Code\x1b[0m\n> Do you want to proceed?\n  1. Yes\n  2. No"
        self.alive = True
        self.sent: list[list[str]] = []

    async def __call__(self, argv, **kw) -> Run:
        if argv[:2] == ["tmux", "list-panes"]:
            return Run(0, "claude\t1\t1\tclaude\t/root\t89\t33\t2\t1790000100\nclaude\t0\t1\tbash\t/root\t89\t33\t2\t1\n"
                       if self.alive else "no server running on /tmp/tmux-0/default\n")
        if argv[:2] == ["tmux", "display-message"]:
            assert argv[argv.index("-t") + 1] == "=claude:"
            return Run(0, "89\t33\t2\t1\tclaude\n") if self.alive else Run(1, "can't find session: claude\n")
        if argv[:2] == ["tmux", "capture-pane"]:
            return Run(0, self.screen + "\n\x1b]0;title\x07") if self.alive else Run(1, "")
        if argv[:2] == ["tmux", "send-keys"]:
            self.sent.append(argv[4:])
            return Run(0, "")
        return Run(0, "")


@pytest.fixture
async def term_bridge(tmp_path: Path, settings: ServerSettings):
    import os

    _seen.clear()  # buffered notifications are keyed by id(ws), which other tests' connections may have had

    registry = Registry(tmp_path / "bridge.db")
    tmux = FakeTmux()
    daemon = OpsDaemon(tmp_path / "bridge.db", AuditLog(tmp_path / "audit.jsonl"), tmux)
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
        yield Bridge(server, registry, f"ws://127.0.0.1:{port}/tnp"), tmux, tools, daemon
    todos.close()
    registry.close()


async def open_terminal(ws, device, op: str) -> str:
    asked = check("ops.run.result", await request(ws, f"o-{op}", "ops.run", {"op": op, "params": {"session": "claude"}}))
    assert asked["result"]["status"] == "pending"
    note = await notification(ws, "ops.approval.request")
    assert "claude" in note["summary"]
    await request(ws, f"a-{op}", "ops.approve", signed(device, note))
    done = await notification(ws, "ops.result")
    assert done["result"]["ok"] and done["approved_by"] == device.id
    data = done["result"]["data"]
    assert data["session"] == "claude" and data["control"] == (op == "terminal.control")
    return data["grant"]


async def test_watch_then_control_a_session(term_bridge):
    bridge, tmux, _, daemon = term_bridge
    device = await paired_device(bridge)
    phone = await device.authenticate(bridge.url)

    listed = check("ops.run.result", await request(phone, "s1", "ops.run", {"op": "tmux.sessions"}))["result"]
    assert listed["result"]["data"]["sessions"] == [
        {"name": "claude", "command": "claude", "path": "/root", "cols": 89, "rows": 33, "attached": 2,
         "activity": 1790000100}]
    assert "Do you want" not in json.dumps(listed), "the list never shows screen content"

    grant = await open_terminal(phone, device, "terminal.watch")
    watching = check("term.watch.result", await request(phone, "w1", "term.watch", {"grant": grant}))["result"]
    assert watching == {"grant": grant, "session": "claude", "control": False}
    screen = await notification(phone, "term.screen")
    assert screen["text"].startswith("\x1b[1mClaude Code\x1b[0m\n> Do you want")
    assert "title" not in screen["text"], "only colour sequences reach the device"
    assert (screen["cols"], screen["rows"], screen["cursor_x"], screen["cursor_y"]) == (89, 33, 2, 1)

    tmux.screen = "\x1b[1mClaude Code\x1b[0m\n> Working…"
    assert (await notification(phone, "term.screen"))["text"].endswith("Working…")

    refused = await request(phone, "k1", "term.keys", {"grant": grant, "keys": [{"key": "Enter"}]})
    assert refused["error"]["code"] == m.CONFLICT and "watch" in refused["error"]["message"]
    assert tmux.sent == []

    control = await open_terminal(phone, device, "terminal.control")
    await request(phone, "w2", "term.watch", {"grant": control})
    await notification(phone, "term.screen")
    keys_ = [{"text": "1"}, {"key": "Enter"}, {"text": "ls\x1b[31m -la"}]
    check("term.keys.result", await request(phone, "k2", "term.keys", {"grant": control, "keys": keys_}))
    assert tmux.sent == [["-l", "--", "1"], ["Enter"], ["-l", "--", "ls[31m -la"]], "no control characters in text"
    typed = [e for e in daemon.audit.tail(10) if e["op"] == "terminal.keys"]
    assert typed[-1]["typed"] == "1<Enter>ls[31m -la" and typed[-1]["device_id"] == device.id

    check("term.stop.result", await request(phone, "x1", "term.stop", {"grant": control}))
    gone = await request(phone, "k3", "term.keys", {"grant": control, "keys": [{"key": "Enter"}]})
    assert gone["error"]["code"] == m.CONFLICT

    tmux.alive = False  # the session ends: the watching device is told
    closed = await notification(phone, "term.closed")
    assert closed["grant"] == grant and "ended" in closed["reason"]
    await phone.close()


async def test_a_grant_is_only_for_the_device_that_approved_it(term_bridge):
    bridge, _, _, _ = term_bridge
    owner = await paired_device(bridge)
    phone = await owner.authenticate(bridge.url)
    grant = await open_terminal(phone, owner, "terminal.control")
    other = await paired_device(bridge)
    laptop = await other.authenticate(bridge.url)
    for method, params in (("term.watch", {"grant": grant}), ("term.keys", {"grant": grant, "keys": [{"text": "x"}]})):
        answer = await request(laptop, f"o-{method}", method, params)
        assert answer["error"]["code"] == m.CONFLICT
    for i, (method, params) in enumerate([("term.keys", {"grant": grant, "keys": [{"key": "rm -rf /"}]}),
                                          ("term.keys", {"grant": grant, "keys": []}),
                                          ("term.watch", {"grant": "tg-123"}), ("term.watch", {"grant": None})]):
        await phone.send(m.encode(m.request(f"b{i}", method, params)))  # outside the schema: sent raw
        while (msg := m.decode(await asyncio.wait_for(phone.recv(), 5))).get("id") != f"b{i}":
            pass
        assert msg["error"]["code"] == m.INVALID_PARAMS, (method, params)
    await phone.close()
    await laptop.close()


async def test_the_agent_can_never_open_terminals(term_bridge):
    bridge, tmux, tools, _ = term_bridge
    for op in ("tmux.sessions", "terminal.watch", "terminal.control"):
        result, is_error = await bridge.server.ops.agent_call(op, {"session": "claude"}, "hermes")
        assert is_error and "only for the owner's devices" in result
    assert not bridge.server.ops.pending, "no approval card was even asked for"
    assert tmux.sent == []


async def test_grants_end_when_idle_too_old_or_the_device_is_revoked(tmp_path: Path):
    t = [1_790_000_000]
    tmux = FakeTmux()
    terms = Terminals(tmux, lambda: t[0])
    g = terms.grant("PHONE", "claude", control=True)["grant"]
    with pytest.raises(OpError):
        terms.grant(None, "claude", control=False)  # never without an approving device
    t[0] += GRANT_IDLE_S - 1
    await terms.screen(g, "PHONE")  # used: the idle clock starts again
    t[0] += GRANT_IDLE_S - 1
    await terms.screen(g, "PHONE")
    t[0] += GRANT_IDLE_S
    with pytest.raises(OpError):
        await terms.screen(g, "PHONE")

    g = terms.grant("PHONE", "claude", control=False)["grant"]
    started, ended_at = t[0], None
    for _ in range(GRANT_MAX_S // (GRANT_IDLE_S - 60) + 2):
        t[0] += GRANT_IDLE_S - 60  # used every 29 minutes
        try:
            await terms.screen(g, "PHONE")
        except OpError:
            ended_at = t[0]
            break
    assert ended_at is not None and ended_at - started >= GRANT_MAX_S, "12 hours at most, however busy"

    # a revoked device's grant stops working at once
    registry = Registry(tmp_path / "bridge.db")
    phone = keys.generate_key()
    phone_id = keys.key_id(phone)
    registry.db.execute("INSERT INTO devices (device_id, public_key, name, platform, paired_at) VALUES (?, ?, ?, ?, ?)",
                        (phone_id, keys.public_key_b64u(phone), "Phone", "android", 1))
    registry.db.commit()
    daemon = OpsDaemon(tmp_path / "bridge.db", AuditLog(tmp_path / "audit.jsonl"), tmux, lambda: t[0])
    grant = daemon.terminals.grant(phone_id, "claude", control=False)["grant"]
    assert "screen" in await daemon.handle({"cmd": "term.screen", "grant": grant, "device_id": phone_id})
    registry.db.execute("UPDATE devices SET revoked_at = 1 WHERE device_id = ?", (phone_id,))
    registry.db.commit()
    assert "revoked" in (await daemon.handle({"cmd": "term.screen", "grant": grant, "device_id": phone_id}))["error"]
    registry.close()
