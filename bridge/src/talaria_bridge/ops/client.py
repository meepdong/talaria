"""The bridge's side of talaria-ops: one request per connection over the Unix socket (spec/README.md §16)."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path

DEFAULT_SOCKET = Path("/run/talaria-ops/ops.sock")


class OpsUnavailable(Exception):
    """talaria-ops isn't running or couldn't be reached."""


class OpsRefused(Exception):
    """talaria-ops answered with an error, such as bad parameters or a signature that doesn't verify."""


class OpsClient:
    def __init__(self, path: Path = DEFAULT_SOCKET):
        self.path = path

    async def request(self, req: dict, timeout: float, accepted=None) -> dict:
        """Send one request and return its answer. An execute is answered twice: first {"accepted": true} once the
        signature checks out (then [accepted] is awaited), and the result when the operation is done."""
        try:
            reader, writer = await asyncio.wait_for(asyncio.open_unix_connection(str(self.path), limit=4 * 1024 * 1024), 5)
        except (OSError, asyncio.TimeoutError) as exc:
            raise OpsUnavailable(f"talaria-ops is not reachable ({exc})") from exc
        try:
            writer.write(json.dumps(req).encode() + b"\n")
            await writer.drain()
            line = await asyncio.wait_for(reader.readline(), timeout)
            if line and json.loads(line).get("accepted"):
                if accepted is not None:
                    await accepted()
                line = await asyncio.wait_for(reader.readline(), timeout)
        except (OSError, asyncio.TimeoutError) as exc:
            raise OpsUnavailable(f"talaria-ops did not answer ({exc.__class__.__name__})") from exc
        finally:
            writer.close()
        if not line:
            raise OpsUnavailable("talaria-ops closed the connection")
        answer = json.loads(line)
        if "error" in answer:
            raise OpsRefused(answer["error"])
        return answer

    async def available(self) -> bool:
        try:
            await self.catalogue()
            return True
        except (OpsUnavailable, OpsRefused):
            return False

    async def catalogue(self) -> list[dict]:
        return (await self.request({"cmd": "catalogue"}, 30))["ops"]

    async def run(self, op: str, params: dict, requested_by: str) -> dict:
        return (await self.request({"cmd": "run", "op": op, "params": params, "requested_by": requested_by}, 120))["result"]

    async def prepare(self, op: str, params: dict, requested_by: str) -> dict:
        return await self.request({"cmd": "prepare", "op": op, "params": params, "requested_by": requested_by}, 60)

    async def execute(self, request_id: str, device_id: str, choice: str, sig: str, accepted=None) -> dict | None:
        """Run an approved operation; [accepted] is awaited as soon as talaria-ops has checked the signature."""
        # apt.upgrade may take up to 30 min; bridge.update runs the test suite
        return (await self.request({"cmd": "execute", "request_id": request_id, "device_id": device_id,
                                    "choice": choice, "sig": sig}, 2100, accepted))["result"]
