"""Server operations on the bridge (PROTOCOL §10.8): devices and the agent ask, a device approves, talaria-ops checks and runs.

The bridge only relays. It never decides an approval itself: talaria-ops checks each device signature against
the registry, so a compromised bridge can't run a tier 1-2 operation on its own.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field

from .ops.client import OpsClient, OpsRefused, OpsUnavailable  # noqa: F401 (re-exported for cli)
from .protocol import messages as m
from .protocol.encoding import now

log = logging.getLogger("talaria.server_ops")

AGENT_LIMIT_PER_MIN = 5  # tier 1-2 requests the agent may make per minute


class OpsError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code, self.message = code, message


@dataclass
class Pending:
    notification: dict  # the ops.approval.request params
    decided: asyncio.Future = field(default_factory=lambda: asyncio.get_running_loop().create_future())  # choice
    finished: asyncio.Future = field(default_factory=lambda: asyncio.get_running_loop().create_future())  # result


class ServerOps:
    def __init__(self, client: OpsClient, broadcast: Callable[[dict], Awaitable[None]] | None = None):
        self.client = client
        self.broadcast = broadcast
        self.pending: dict[str, Pending] = {}
        self._tiers: dict[str, int] = {}
        self._agent_calls: dict[str, list[float]] = {}
        self._tasks: set[asyncio.Task] = set()

    # Devices

    async def catalogue(self) -> dict:
        ops = await self._call(self.client.catalogue())
        self._tiers = {o["op"]: o["tier"] for o in ops}
        return {"ops": ops}

    async def run(self, op: object, params: object, requested_by: str) -> dict:
        """ops.run: tier 0 answers at once; tier 1-2 become an approval request on every device."""
        if not isinstance(op, str):
            raise OpsError(m.INVALID_PARAMS, "op is required")
        params = params if params is not None else {}
        if not isinstance(params, dict):
            raise OpsError(m.INVALID_PARAMS, "params must be an object")
        if await self._tier(op) == 0:
            return {"status": "done", "result": await self._call(self.client.run(op, params, requested_by))}
        pending = await self._ask(op, params, requested_by)
        return {"status": "pending", "request_id": pending.notification["request_id"]}

    async def approve(self, device_id: str, p: dict) -> dict:
        """ops.approve: hand the device's signed answer to talaria-ops, which checks it and runs the operation."""
        request_id, choice, sig = p.get("request_id"), p.get("choice"), p.get("sig")
        if not isinstance(request_id, str) or choice not in ("once", "deny") or not isinstance(sig, str):
            raise OpsError(m.INVALID_PARAMS, "request_id, choice (once or deny) and sig are required")
        pending = self.pending.get(request_id)
        if pending is None or pending.decided.done():
            raise OpsError(m.CONFLICT, "Nothing is waiting for this approval")
        accepted = asyncio.get_running_loop().create_future()

        async def on_accepted() -> None:
            await self._decided(request_id, pending, choice)
            if not accepted.done():
                accepted.set_result(None)

        task = asyncio.ensure_future(self._execute(request_id, pending, device_id, choice, sig, on_accepted))
        self._keep(task)
        await asyncio.wait([accepted, task], return_when=asyncio.FIRST_COMPLETED)
        if task.done() and task.exception() is not None:
            exc = task.exception()
            raise exc if isinstance(exc, OpsError) else OpsError(m.AGENT_UNAVAILABLE, str(exc))
        return {"request_id": request_id, "choice": choice}

    def pending_notifications(self) -> list[dict]:
        """Approval requests still open, for a device whose session just became ready."""
        return [m.notification("ops.approval.request", p.notification)
                for p in self.pending.values() if not p.decided.done()]

    # Agent

    async def agent_call(self, op: object, params: object, agent_id: str) -> tuple[dict | str, bool]:
        """The server_op tool: (result, is_error). Tier 1-2 wait for the owner to answer on a device."""
        try:
            if not isinstance(op, str):
                return "op is required", True
            if await self._tier(op) == 0:
                return await self._call(self.client.run(op, params if params is not None else {}, f"agent:{agent_id}")), False
            if not self._agent_allowed(agent_id):
                return f"Too many requests: at most {AGENT_LIMIT_PER_MIN} operations needing approval per minute", True
            pending = await self._ask(op, params if params is not None else {}, f"agent:{agent_id}")
            choice = await pending.decided
            if choice != "once":
                return ("The owner denied it" if choice == "deny"
                        else "Nobody approved it within 2 minutes, so it did not run"), True
            result = await pending.finished
            return result, not result.get("ok", False)
        except OpsError as exc:
            return exc.message, True

    # Internals

    async def _ask(self, op: str, params: dict, requested_by: str) -> Pending:
        prepared = await self._call(self.client.prepare(op, params, requested_by))
        note = {k: prepared[k] for k in ("request_id", "op", "params_json", "tier", "summary", "expires_at")}
        note["requested_by"] = requested_by
        pending = Pending(note)
        self.pending[note["request_id"]] = pending
        self._keep(asyncio.ensure_future(self._expire_later(note["request_id"], pending)))
        await self._send(m.notification("ops.approval.request", note))
        return pending

    async def _execute(self, request_id: str, pending: Pending, device_id: str, choice: str, sig: str,
                       on_accepted: Callable[[], Awaitable[None]]) -> None:
        try:
            result = await self._call(self.client.execute(request_id, device_id, choice, sig, on_accepted))
        except OpsError as exc:
            if not pending.decided.done():
                raise  # refused before it ran (e.g. a bad signature): approve() reports it, the request stays open
            result = {"op": pending.notification["op"], "ok": False, "exit_code": None,
                      "summary": f"{pending.notification['summary']}: {exc.message}", "output": ""}
        if choice == "deny":
            await self._decided(request_id, pending, "deny")
            return
        if not pending.finished.done():
            pending.finished.set_result(result)
        await self._send(m.notification("ops.result", {"request_id": request_id,
                                                       "requested_by": pending.notification["requested_by"],
                                                       "approved_by": device_id, "result": result}))

    async def _decided(self, request_id: str, pending: Pending, choice: str) -> None:
        if pending.decided.done():
            return
        pending.decided.set_result(choice)
        if choice != "once":
            self.pending.pop(request_id, None)
        await self._send(m.notification("ops.approval.done", {"request_id": request_id, "choice": choice}))
        if choice == "once":  # keep it until the result is out, then forget it
            pending.finished.add_done_callback(lambda _: self.pending.pop(request_id, None))

    async def _expire_later(self, request_id: str, pending: Pending) -> None:
        await asyncio.sleep(max(0, pending.notification["expires_at"] - now()))
        if not pending.decided.done():
            await self._decided(request_id, pending, "expired")

    async def _tier(self, op: str) -> int:
        if op not in self._tiers:
            await self.catalogue()
        if op not in self._tiers:
            raise OpsError(m.INVALID_PARAMS, f"unknown operation {op!r}")
        return self._tiers[op]

    async def _call(self, coro):
        try:
            return await coro
        except OpsRefused as exc:
            raise OpsError(m.INVALID_PARAMS, str(exc)) from exc
        except OpsUnavailable as exc:
            raise OpsError(m.AGENT_UNAVAILABLE, "Server operations are unavailable: talaria-ops is not running") from exc

    def _agent_allowed(self, agent_id: str) -> bool:
        t = time.monotonic()
        calls = [c for c in self._agent_calls.get(agent_id, []) if c > t - 60]
        if len(calls) >= AGENT_LIMIT_PER_MIN:
            self._agent_calls[agent_id] = calls
            return False
        self._agent_calls[agent_id] = calls + [t]
        return True

    async def _send(self, msg: dict) -> None:
        if self.broadcast is not None:
            with contextlib.suppress(Exception):
                await self.broadcast(msg)

    def _keep(self, task: asyncio.Task) -> None:
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
