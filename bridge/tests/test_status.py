"""M1: status.get, status pushes, agent health checks and --behind-proxy."""

from __future__ import annotations

import asyncio
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest
from conftest import Bridge, check, paired_device

from talaria_bridge import __version__
from talaria_bridge.agents import AgentConfig, AgentMonitor, http_probe, load_agents
from talaria_bridge.cli import main
from talaria_bridge.protocol import keys
from talaria_bridge.protocol import messages as m
from talaria_bridge.registry import Registry
from talaria_bridge.server import BridgeServer, ServerSettings


async def status_get(ws, msg_id: str = "st-1") -> dict:
    await ws.send(m.encode(check("status.get", m.request(msg_id, "status.get"))))
    reply = check("status.result", m.decode(await asyncio.wait_for(ws.recv(), 5)))
    assert reply["id"] == msg_id
    return reply["result"]


async def test_status_get_without_agents(bridge: Bridge):
    device = await paired_device(bridge)
    ws = await device.authenticate(bridge.url)
    report = await status_get(ws)
    assert report["bridge"]["version"] == __version__
    assert report["bridge"]["uptime_s"] >= 0
    assert report["bridge"]["latency_ms"] is None
    assert report["agents"] == []
    assert report["device"]["session_id"].startswith("s-")
    await ws.close()


async def test_latency_from_heartbeat_ping(bridge: Bridge, settings: ServerSettings):
    settings.ping_interval_s = 0.1
    settings.revocation_check_s = 0.05
    device = await paired_device(bridge)
    ws = await device.authenticate(bridge.url)
    ping = check("ping", m.decode(await asyncio.wait_for(ws.recv(), 5)))
    await ws.send(m.encode(m.result(ping["id"], {"ts": 0})))
    await asyncio.sleep(0.05)
    report = await status_get(ws)
    assert isinstance(report["bridge"]["latency_ms"], int)
    await ws.close()


class FakeProbe:
    def __init__(self, result: dict):
        self.result = result
        self.calls = 0

    def __call__(self, url: str, timeout_s: float) -> dict:
        self.calls += 1
        return dict(self.result)


@pytest.fixture
async def agent_bridge(tmp_path: Path, settings: ServerSettings):
    probe = FakeProbe({"state": "ready", "model": "anthropic/claude-sonnet-5.5"})
    monitor = AgentMonitor([AgentConfig("hermes", "Hermes", "http://127.0.0.1:9/health")],
                           interval_s=0.05, probe=probe)
    registry = Registry(tmp_path / "bridge.db")
    server = BridgeServer(registry, keys.generate_key(), settings, monitor)
    async with server.serve() as ws_server:
        port = ws_server.sockets[0].getsockname()[1]
        yield Bridge(server, registry, f"ws://127.0.0.1:{port}/tnp"), probe
    registry.close()


async def test_status_reports_agents_and_pushes_changes(agent_bridge):
    bridge, probe = agent_bridge
    while probe.calls == 0:
        await asyncio.sleep(0.01)
    device = await paired_device(bridge)
    ws = await device.authenticate(bridge.url)
    report = await status_get(ws)
    assert report["agents"] == [{"id": "hermes", "name": "Hermes", "state": "ready",
                                 "model": "anthropic/claude-sonnet-5.5"}]

    probe.result = {"state": "offline", "detail": "not reachable: connection refused"}
    push = check("status", m.decode(await asyncio.wait_for(ws.recv(), 5)))
    assert push["params"]["agents"][0]["state"] == "offline"
    assert push["params"]["device"]["session_id"] == report["device"]["session_id"]
    await ws.close()


async def test_no_push_before_ready(agent_bridge):
    bridge, probe = agent_bridge
    device = await paired_device(bridge)
    ws, hello = await device.open(bridge.url)
    await ws.send(m.encode(device.auth_request(hello)))
    check("auth.ok", m.decode(await ws.recv()))
    probe.result = {"state": "degraded", "detail": "x"}
    with pytest.raises(asyncio.TimeoutError):
        await asyncio.wait_for(ws.recv(), 0.3)
    await ws.close()


# agents.json and the HTTP health check

def test_load_agents(tmp_path: Path):
    assert load_agents(tmp_path / "agents.json") == []
    path = tmp_path / "agents.json"
    path.write_text(json.dumps({"agents": [{"id": "hermes", "health_url": "http://127.0.0.1:8642/health"}]}))
    assert load_agents(path) == [AgentConfig("hermes", "hermes", "http://127.0.0.1:8642/health")]
    path.write_text(json.dumps({"agents": [{"id": "hermes", "health_url": "file:///etc/passwd"}]}))
    with pytest.raises(ValueError):
        load_agents(path)


@pytest.fixture
def health_server():
    responses: dict[str, tuple[int, bytes]] = {}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            code, body = responses.get(self.path, (404, b""))
            self.send_response(code)
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}", responses
    httpd.shutdown()
    httpd.server_close()


def test_http_probe_states(health_server):
    base, responses = health_server
    responses["/ok"] = (200, b'{"status": "ok", "model": "m1"}')
    responses["/plain"] = (200, b"fine")
    responses["/sick"] = (200, b'{"status": "provider error"}')
    responses["/500"] = (500, b"")
    assert http_probe(base + "/ok", 2) == {"state": "ready", "model": "m1"}
    assert http_probe(base + "/plain", 2) == {"state": "ready"}
    assert http_probe(base + "/sick", 2)["state"] == "degraded"
    assert http_probe(base + "/500", 2) == {"state": "degraded", "detail": "health check returned HTTP 500"}


def test_http_probe_offline():
    import socket

    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]  # closed again before the probe runs, so nothing listens
    assert http_probe(f"http://127.0.0.1:{port}/health", 2)["state"] == "offline"


# --behind-proxy

@pytest.mark.parametrize("extra", [
    ["--host", "0.0.0.0", "--url", "wss://bridge.example.ts.net/tnp"],
    ["--url", "ws://127.0.0.1:8765/tnp"],
    [],
    ["--url", "wss://bridge.example.ts.net/tnp", "--tls-cert", "c.pem", "--tls-key", "k.pem"],
    ["--url", "wss://bridge.example.ts.net/tnp", "--dev"],
])
def test_behind_proxy_refuses_bad_options(tmp_path: Path, extra: list[str]):
    assert main(["--home", str(tmp_path), "serve", "--behind-proxy", *extra]) == 2


def test_bad_agents_json_stops_serve(tmp_path: Path, capsys):
    (tmp_path / "agents.json").write_text('{"agents": [{"id": ""}]}')
    assert main(["--home", str(tmp_path), "serve", "--dev"]) == 2
    assert "agents.json" in capsys.readouterr().err
