"""Runs the real Python bridge for the Kotlin session tests.

Talks JSON lines on stdin/stdout:
  -> {"url": ...}                      once the server listens
  <- {"cmd": "pair"}                   -> {"link": ..., "short_code": ...}
  <- {"cmd": "revoke", "device_id": d} -> {"ok": true}
  <- {"cmd": "agent", "state": s}      -> {"ok": true}   (changes the fake agent's health)
Every pair request is approved automatically. EOF on stdin stops the bridge.
With --old, status.get is unknown, as on an M0 bridge.
Chat goes to a small fake Hermes: each reply streams "Hel", a web_search tool call and "lo",
then finishes as "Hello".
"""

from __future__ import annotations

import asyncio
import json
import time
import sys
import tempfile
from pathlib import Path

import httpx

from talaria_bridge.agents import AgentConfig, AgentMonitor
from talaria_bridge.blobs import BlobStore
from talaria_bridge.chat import ChatService, ChatStore
from talaria_bridge.hermes import HermesClient
from talaria_bridge.operator import create_pairing
from talaria_bridge.protocol import keys
from talaria_bridge.protocol import messages as m
from talaria_bridge.protocol.encoding import now
from talaria_bridge.registry import Registry
from talaria_bridge.server import BridgeServer, ServerSettings

agent_state = {"state": "ready", "model": "test/model"}


def probe(url: str, timeout_s: float) -> dict:
    return dict(agent_state)


class OldBridge(BridgeServer):
    async def _dispatch(self, ws, device_id, msg):
        if msg.get("method") == "status.get":
            await self._send(ws, m.error(msg.get("id"), m.METHOD_NOT_FOUND, "Method not found: status.get"))
            return
        await super()._dispatch(ws, device_id, msg)


class FakeHermes:
    """Just enough of the Hermes Sessions API for the chat proxy."""

    def __init__(self):
        self.sessions: dict[str, list[dict]] = {}

    async def handle(self, req: httpx.Request) -> httpx.Response:
        parts = req.url.path.split("/")
        if req.method == "POST" and req.url.path == "/api/sessions":
            self.sessions[json.loads(req.content)["id"]] = []
            return httpx.Response(201, json={"session": {}})
        if len(parts) < 4 or parts[3] not in self.sessions:
            return httpx.Response(404, json={"error": {"message": "not found", "code": "session_not_found"}})
        rows = self.sessions[parts[3]]
        if req.url.path.endswith("/messages"):
            limit, offset = int(req.url.params["limit"]), int(req.url.params["offset"])
            return httpx.Response(200, json={"data": rows[max(0, len(rows) - offset - limit):len(rows) - offset]})
        if req.url.path.endswith("/chat/stream"):
            return httpx.Response(200, content=self.stream(rows, json.loads(req.content)["message"]))
        return httpx.Response(200, json={})

    async def stream(self, rows: list[dict], text: str):
        def ev(name: str, payload: dict) -> bytes:
            return f"event: {name}\ndata: {json.dumps(payload)}\n\n".encode()
        rows.append({"id": len(rows) + 1, "role": "user", "content": text, "timestamp": int(time.time())})
        yield ev("run.started", {"run_id": f"run_{len(rows)}"})
        yield ev("assistant.delta", {"delta": "Hel"})
        await asyncio.sleep(0.05)
        yield ev("tool.started", {"tool_name": "web_search", "preview": "q"})
        yield ev("tool.completed", {"tool_name": "web_search", "preview": "ok"})
        yield ev("assistant.delta", {"delta": "lo"})
        rows.append({"id": len(rows) + 1, "role": "assistant", "content": "Hello", "timestamp": int(time.time())})
        yield ev("assistant.completed", {"content": "Hello"})
        yield ev("run.completed", {"usage": {"total_tokens": 3}, "runtime": {"model": "test/model"}})
        yield ev("done", {})


def emit(obj: dict) -> None:
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


async def main() -> None:
    home = Path(tempfile.mkdtemp(prefix="talaria-harness-"))
    registry = Registry(home / "bridge.db")
    key = keys.generate_key()
    monitor = AgentMonitor([AgentConfig("meep", "Meep", "http://127.0.0.1:9/health")],
                           interval_s=0.1, probe=probe)
    settings = ServerSettings(port=0, approval_timeout_s=10, revocation_check_s=0.1, decision_poll_s=0.02)
    hermes = HermesClient("http://hermes.test", "k", transport=httpx.MockTransport(FakeHermes().handle))

    async def unused(msg: dict) -> None:
        pass

    chat = ChatService(ChatStore(home / "chat.db"), {"meep": hermes}, unused,
                       blobs=BlobStore(home / "blobs"), inboxes={"meep": home / "inbox"})
    server = (OldBridge if "--old" in sys.argv else BridgeServer)(registry, key, settings, monitor, chat)
    tokens: list[str] = []

    async def approve() -> None:
        while True:
            for token in tokens:
                req = registry.waiting_request_for(token)
                if req is not None:
                    registry.decide_pair_request(req.id, "approved", now())
            await asyncio.sleep(0.02)

    async with server.serve() as ws_server:
        url = f"ws://127.0.0.1:{ws_server.sockets[0].getsockname()[1]}/tnp"
        emit({"url": url})
        approver = asyncio.ensure_future(approve())
        loop = asyncio.get_running_loop()
        while True:
            line = await loop.run_in_executor(None, sys.stdin.readline)
            if not line:
                break
            cmd = json.loads(line)
            if cmd["cmd"] == "pair":
                new = create_pairing(registry, key, url=url, name=cmd.get("name"), ttl_s=cmd.get("ttl", 300))
                tokens.append(new.payload.pair_token)
                emit({"link": new.link, "short_code": new.short_code})
            elif cmd["cmd"] == "revoke":
                emit({"ok": registry.revoke_device(cmd["device_id"], now())})
            elif cmd["cmd"] == "agent":
                agent_state.clear()
                agent_state.update({"state": cmd["state"]} if cmd["state"] != "ready"
                                   else {"state": "ready", "model": "test/model"})
                emit({"ok": True})
        approver.cancel()
    registry.close()


if __name__ == "__main__":
    asyncio.run(main())
