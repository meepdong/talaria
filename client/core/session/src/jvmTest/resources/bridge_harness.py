"""Runs the real Python bridge for the Kotlin session tests.

Talks JSON lines on stdin/stdout:
  -> {"url": ...}                      once the server listens
  <- {"cmd": "pair"}                   -> {"link": ..., "short_code": ...}
  <- {"cmd": "revoke", "device_id": d} -> {"ok": true}
  <- {"cmd": "agent", "state": s}      -> {"ok": true}   (changes the fake agent's health)
Every pair request is approved automatically. EOF on stdin stops the bridge.
With --old, status.get is unknown, as on an M0 bridge.
"""

from __future__ import annotations

import asyncio
import json
import sys
import tempfile
from pathlib import Path

from talaria_bridge.agents import AgentConfig, AgentMonitor
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
    server = (OldBridge if "--old" in sys.argv else BridgeServer)(registry, key, settings, monitor)
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
