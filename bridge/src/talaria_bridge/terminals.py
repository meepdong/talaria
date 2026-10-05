"""Terminals on the bridge (spec/README.md §16.1): relay a grant's screen to the device watching it, and its keys back.

talaria-ops holds the grants and checks the device on every read and key; the bridge only polls the screen while
a device watches, and sends it when it changes.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from collections.abc import Awaitable, Callable

from .ops.terminal import KEYS, MAX_KEYS, MAX_TEXT
from .protocol import messages as m
from .server_ops import OpsError, ServerOps

log = logging.getLogger("talaria.terminals")

POLL_S = 0.35
TERM_METHODS = frozenset({"term.watch", "term.keys", "term.stop"})

Send = Callable[[dict], Awaitable[None]]


def _keys(keys: object) -> list:
    ok = isinstance(keys, list) and 1 <= len(keys) <= MAX_KEYS and all(
        isinstance(k, dict) and ((set(k) == {"text"} and isinstance(k["text"], str) and 0 < len(k["text"]) <= MAX_TEXT)
                                 or (set(k) == {"key"} and k["key"] in KEYS)) for k in keys)
    if not ok:
        raise OpsError(m.INVALID_PARAMS, f"keys are 1 to {MAX_KEYS} of {{text}} or {{key}} ({', '.join(sorted(KEYS))})")
    return keys


class Watch:
    def __init__(self) -> None:
        self.task: asyncio.Task | None = None
        self.wake = asyncio.Event()


class Terminals:
    """One per device session: the grants it watches."""

    def __init__(self, ops: ServerOps, device_id: str, send: Send, poll_s: float = POLL_S):
        self.ops, self.device_id, self.send, self.poll_s = ops, device_id, send, poll_s
        self.watches: dict[str, Watch] = {}

    async def handle(self, method: str, p: dict) -> dict:
        grant = p.get("grant")
        if method == "term.watch":
            return await self.watch(grant)
        if method == "term.keys":
            await self.ops.terminal("term.keys", grant, self.device_id, keys=_keys(p.get("keys")))
            if grant in self.watches:
                self.watches[grant].wake.set()  # show what the keys did straight away
            return {}
        if method == "term.stop":
            self._stop(grant)
            with contextlib.suppress(OpsError):
                await self.ops.terminal("term.close", grant, self.device_id)
            return {}
        raise OpsError(m.METHOD_NOT_FOUND, f"Method not found: {method}")

    async def watch(self, grant: object) -> dict:
        screen = (await self.ops.terminal("term.screen", grant, self.device_id))["screen"]  # checks the grant
        self._stop(grant)
        w = self.watches[grant] = Watch()
        w.task = asyncio.ensure_future(self._poll(grant, w, screen))
        return {"grant": grant, "session": screen["session"], "control": screen["control"]}

    async def _poll(self, grant: str, w: Watch, first: dict) -> None:
        last = None
        screen = first
        try:
            while True:
                if screen != last:
                    await self.send(m.notification("term.screen", {"grant": grant, **screen}))
                    last = screen
                with contextlib.suppress(asyncio.TimeoutError):
                    await asyncio.wait_for(w.wake.wait(), self.poll_s)
                w.wake.clear()
                screen = (await self.ops.terminal("term.screen", grant, self.device_id))["screen"]
        except OpsError as exc:
            self.watches.pop(grant, None)
            with contextlib.suppress(Exception):
                await self.send(m.notification("term.closed", {"grant": grant, "reason": exc.message[:300]}))
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("terminal watch failed")
            self.watches.pop(grant, None)

    def _stop(self, grant: object) -> None:
        w = self.watches.pop(grant, None) if isinstance(grant, str) else None
        if w is not None and w.task is not None:
            w.task.cancel()

    def close(self) -> None:
        """The device disconnected: stop polling (its grants stay, so it can watch again after reconnecting)."""
        for grant in list(self.watches):
            self._stop(grant)
