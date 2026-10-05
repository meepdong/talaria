"""Terminals (spec/README.md §16.1): the owner's tmux sessions, read and typed into through a grant.

Not a terminal emulator: the screen comes from `tmux capture-pane` (rendered, with colour) and keys go in with
`tmux send-keys`, so sessions are never resized and other attached clients are left alone. A grant is created only
when a device approves terminal.watch or terminal.control, belongs to that device and that session, and ends after
30 idle minutes, 12 hours, or term.close.
"""

from __future__ import annotations

import re
import secrets
from contextvars import ContextVar
from dataclasses import dataclass

from .catalogue import OpError, Runner

GRANT_IDLE_S = 30 * 60
GRANT_MAX_S = 12 * 3600
MAX_GRANTS = 32
MAX_TEXT = 2000
MAX_KEYS = 50
MAX_SCREEN = 256 * 1024
KEYS = frozenset({"Enter", "Escape", "Tab", "BTab", "BSpace", "Space", "Up", "Down", "Left", "Right", "Home", "End",
                  "PPage", "NPage", "C-c", "C-d", "C-z", "C-l", "C-r", "C-o"})
SESSION_NAME = re.compile(r"^[A-Za-z0-9_.:@+-]{1,64}$")
# anything but SGR (colour) sequences is dropped from the screen; capture-pane -e emits only those, this is a guard
NOT_SGR = re.compile(r"\x1b(?!\[[0-9;:]*m)(\[[0-9;?]*[A-Za-z]|\][^\x07]*\x07|.)")

# who approved the operation running now (set by the daemon around an approved op's run)
APPROVED_BY: ContextVar[str | None] = ContextVar("approved_by", default=None)

PANE_FORMAT = "\t".join(["#{session_name}", "#{window_active}", "#{pane_active}", "#{pane_current_command}",
                         "#{pane_current_path}", "#{pane_width}", "#{pane_height}", "#{session_attached}",
                         "#{window_activity}"])
SCREEN_FORMAT = "\t".join(["#{pane_width}", "#{pane_height}", "#{cursor_x}", "#{cursor_y}", "#{pane_current_command}"])


@dataclass
class Grant:
    device_id: str
    session: str
    control: bool
    created: int
    last_used: int

    def expires_at(self) -> int:
        return min(self.last_used + GRANT_IDLE_S, self.created + GRANT_MAX_S)


def _target(session: str) -> str:
    # "=name:" is exactly that session (no prefix matching), its current window, the active pane
    return f"={session}:"


class Terminals:
    def __init__(self, run: Runner, clock):
        self.run = run
        self.clock = clock
        self.grants: dict[str, Grant] = {}

    # tmux

    async def sessions(self) -> list[dict]:
        """The active pane of each of root's tmux sessions; empty when no tmux server is running."""
        run = await self.run(["tmux", "list-panes", "-a", "-F", PANE_FORMAT], timeout=10)
        if run.exit_code != 0:
            return []  # "no server running" and the like: no sessions
        out = []
        for line in run.output.splitlines():
            f = line.split("\t")
            if len(f) != 9 or f[1] != "1" or f[2] != "1" or not SESSION_NAME.match(f[0]):
                continue
            try:
                out.append({"name": f[0], "command": f[3][:64], "path": f[4][:300], "cols": int(f[5]),
                            "rows": int(f[6]), "attached": int(f[7]), "activity": int(f[8])})
            except ValueError:
                continue
        return sorted(out, key=lambda s: -s["activity"])

    async def names(self, ctx=None) -> list[str]:
        return [s["name"] for s in await self.sessions()]

    # grants

    def grant(self, device_id: str | None, session: str, control: bool) -> dict:
        if not device_id:
            raise OpError("a terminal opens only with a device's approval")
        self._expire()
        if len(self.grants) >= MAX_GRANTS:
            oldest = min(self.grants, key=lambda g: self.grants[g].last_used)
            del self.grants[oldest]
        now = self.clock()
        grant_id = "tg-" + secrets.token_hex(16)
        g = Grant(device_id, session, control, now, now)
        self.grants[grant_id] = g
        return {"grant": grant_id, "session": session, "control": control, "expires_at": g.expires_at()}

    def _use(self, grant_id: object, device_id: object) -> Grant:
        self._expire()
        g = self.grants.get(grant_id) if isinstance(grant_id, str) else None
        if g is None or g.device_id != device_id:
            raise OpError("no such terminal grant, or it ended")
        g.last_used = self.clock()
        return g

    def _expire(self) -> None:
        now = self.clock()
        for gid in [gid for gid, g in self.grants.items() if g.expires_at() <= now]:
            del self.grants[gid]

    def close(self, grant_id: object, device_id: object) -> Grant:
        g = self._use(grant_id, device_id)
        del self.grants[grant_id]
        return g

    # screen and keys

    async def screen(self, grant_id: object, device_id: object) -> dict:
        g = self._use(grant_id, device_id)
        info = await self.run(["tmux", "display-message", "-p", "-t", _target(g.session), SCREEN_FORMAT], timeout=10)
        text = await self.run(["tmux", "capture-pane", "-p", "-e", "-t", _target(g.session)], timeout=10)
        f = info.output.strip().split("\t")
        if info.exit_code != 0 or text.exit_code != 0 or len(f) != 5:
            del self.grants[grant_id]
            raise OpError(f"the session {g.session} has ended")
        return {"session": g.session, "cols": int(f[0]), "rows": int(f[1]), "cursor_x": int(f[2]),
                "cursor_y": int(f[3]), "command": f[4][:64], "control": g.control,
                "text": NOT_SGR.sub("", text.output).rstrip("\n")[:MAX_SCREEN]}

    async def keys(self, grant_id: object, device_id: object, keys: object) -> tuple[Grant, str]:
        """Type [keys] into the grant's session; returns the grant and what was typed, for the audit log."""
        g = self._use(grant_id, device_id)
        if not g.control:
            raise OpError("this terminal is open to watch, not to type: ask for control")
        if not isinstance(keys, list) or not 1 <= len(keys) <= MAX_KEYS:
            raise OpError(f"keys must list 1 to {MAX_KEYS} keys")
        steps: list[list[str]] = []
        typed = []
        for k in keys:
            if isinstance(k, dict) and set(k) == {"text"} and isinstance(k["text"], str) and 0 < len(k["text"]) <= MAX_TEXT:
                text = "".join(ch for ch in k["text"] if ch == "\t" or ch >= " ")  # no control characters in text
                if text:
                    steps.append(["-l", "--", text])
                    typed.append(text)
            elif isinstance(k, dict) and set(k) == {"key"} and k["key"] in KEYS:
                steps.append([k["key"]])
                typed.append(f"<{k['key']}>")
            else:
                raise OpError(f"keys are {{text}} (at most {MAX_TEXT} characters) or {{key}} (one of {', '.join(sorted(KEYS))})")
        for step in steps:
            run = await self.run(["tmux", "send-keys", "-t", _target(g.session), *step], timeout=10)
            if run.exit_code != 0:
                raise OpError(f"the session {g.session} has ended")
        return g, "".join(typed)
