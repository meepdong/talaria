"""Agent health for the status report (PROTOCOL §10.1).

Agents are listed in `agents.json` in the bridge home. In M1 the bridge only checks that
each agent's health URL answers; chat and the full agent registry come in M2.

    {"agents": [{"id": "hermes", "name": "Hermes",
                 "health_url": "http://127.0.0.1:8642/health"}]}
"""

from __future__ import annotations

import asyncio
import json
import logging
import urllib.error
import urllib.request
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger("talaria.agents")

AGENT_STATES = ("ready", "degraded", "offline", "unknown")
HEALTHY_WORDS = ("ok", "healthy", "ready", "up", "pass")


@dataclass(frozen=True)
class AgentConfig:
    id: str
    name: str
    health_url: str
    api_url: str | None = None  # chat (M2): the Hermes API server, e.g. http://127.0.0.1:8642
    api_key_file: str | None = None  # a file holding its API_SERVER_KEY, readable only by the bridge
    inbox_dir: str | None = None  # chat attachments (§10): where the bridge saves files the agent can read
    openrouter_key_file: str | None = None  # an OpenRouter management key, for account.balance (§11)
    files: tuple[dict, ...] = ()  # shared folders (§12): {"id", "name", "path", "agent_path"?}
    calendar_command: tuple[str, ...] = ()  # calendar.day (§14): a command printing a day's events as JSON
    tools_key_file: str | None = None  # tools for the agent (§15): the token it sends to the bridge's MCP endpoint
    voice_key_file: str | None = None  # an OpenRouter key for Talk's voice (§9): speech and the quick first line
    voice_engine: str | None = None  # "gemini" (default) or "qwen"
    voice_name: str | None = None  # one of that engine's voices; its first by default
    talk_voice: str | None = None  # Talk 3's talker voice (talk.py VOICES); shimmer by default; devices can change it
    talk_transcriber: str | None = None  # who writes down what was said in Talk: "whisper" (default) or "talker"


def load_agents(path: Path) -> list[AgentConfig]:
    """Read agents.json. A missing file means no agents are configured."""
    if not path.exists():
        return []
    data = json.loads(path.read_text(encoding="utf-8"))
    agents = []
    for entry in data.get("agents", []):
        agent_id, url = entry.get("id"), entry.get("health_url")
        if not (isinstance(agent_id, str) and agent_id and isinstance(url, str)
                and url.startswith(("http://", "https://"))):
            raise ValueError(f"{path}: each agent needs an id and an http(s) health_url")
        api_url, key_file = entry.get("api_url"), entry.get("api_key_file")
        if api_url is not None and not (isinstance(api_url, str) and api_url.startswith(("http://", "https://"))
                                        and isinstance(key_file, str) and key_file):
            raise ValueError(f"{path}: an agent's api_url must be http(s) and come with api_key_file")
        inbox = entry.get("inbox_dir")
        if inbox is not None and not (isinstance(inbox, str) and Path(inbox).is_absolute()):
            raise ValueError(f"{path}: an agent's inbox_dir must be an absolute path")
        balance_key = entry.get("openrouter_key_file")
        if balance_key is not None and not (isinstance(balance_key, str) and Path(balance_key).is_absolute()):
            raise ValueError(f"{path}: an agent's openrouter_key_file must be an absolute path")
        files = entry.get("files", [])
        if not (isinstance(files, list) and all(
                isinstance(f, dict) and isinstance(f.get("id"), str) and f["id"] and f["id"] != "inbox"
                and isinstance(f.get("path"), str) and Path(f["path"]).is_absolute()
                and isinstance(f.get("agent_path", f["path"]), str) for f in files)):
            raise ValueError(f"{path}: an agent's files must list {{id, name, path, agent_path?}} with absolute paths"
                             " (the id inbox is taken)")
        calendar = entry.get("calendar_command", [])
        if not (isinstance(calendar, list) and all(isinstance(a, str) and a for a in calendar)
                and (not calendar or Path(calendar[0]).is_absolute())):
            raise ValueError(f"{path}: an agent's calendar_command must be a list of arguments starting with an"
                             " absolute path")
        voice_key = entry.get("voice_key_file")
        if voice_key is not None and not (isinstance(voice_key, str) and Path(voice_key).is_absolute()):
            raise ValueError(f"{path}: an agent's voice_key_file must be an absolute path")
        tools_key = entry.get("tools_key_file")
        if tools_key is not None and not (isinstance(tools_key, str) and Path(tools_key).is_absolute()):
            raise ValueError(f"{path}: an agent's tools_key_file must be an absolute path")
        agents.append(AgentConfig(agent_id, entry.get("name") or agent_id, url,
                                  api_url, key_file if api_url is not None else None, inbox, balance_key,
                                  tuple(files), tuple(calendar), tools_key, voice_key,
                                  entry.get("voice_engine"), entry.get("voice_name"), entry.get("talk_voice"),
                                  entry.get("talk_transcriber")))
    return agents


def http_probe(url: str, timeout_s: float) -> dict:
    """One health check. Returns {"state", "detail"?, "model"?}. Blocking: run it in a thread."""
    try:
        with urllib.request.urlopen(url, timeout=timeout_s) as resp:
            body = resp.read(64 * 1024)
    except urllib.error.HTTPError as exc:
        return {"state": "degraded", "detail": f"health check returned HTTP {exc.code}"}
    except (urllib.error.URLError, OSError) as exc:
        reason = getattr(exc, "reason", exc)
        return {"state": "offline", "detail": f"not reachable: {reason}"}
    result: dict = {"state": "ready"}
    try:
        info = json.loads(body)
    except ValueError:
        return result  # any 2xx without JSON counts as up
    if isinstance(info, dict):
        status = info.get("status")
        if isinstance(status, str) and status.lower() not in HEALTHY_WORDS:
            result = {"state": "degraded", "detail": f"reports status {status!r}"}
        model = info.get("model")
        if isinstance(model, str):
            result["model"] = model
    return result


Probe = Callable[[str, float], dict]


class AgentMonitor:
    """Checks every agent on an interval and remembers the latest result."""

    def __init__(self, agents: list[AgentConfig], *, interval_s: float = 30, timeout_s: float = 5,
                 probe: Probe = http_probe):
        self.agents = agents
        self.interval_s = interval_s
        self.timeout_s = timeout_s
        self.probe = probe
        self._states: dict[str, dict] = {a.id: {"state": "unknown"} for a in agents}

    def report(self) -> list[dict]:
        return [{"id": a.id, "name": a.name, **self._states[a.id]} for a in self.agents]

    async def check_all(self) -> bool:
        """Probe every agent once. Returns True if any agent's report changed."""
        results = await asyncio.gather(*(self._check(a) for a in self.agents))
        changed = False
        for agent, result in zip(self.agents, results):
            if result != self._states[agent.id]:
                log.info("agent %s is now %s", agent.id, result.get("state"))
                self._states[agent.id] = result
                changed = True
        return changed

    async def _check(self, agent: AgentConfig) -> dict:
        result = await asyncio.to_thread(self.probe, agent.health_url, self.timeout_s)
        if result.get("state") != "offline" or not agent.api_url:
            return result
        # health_url is often Hermes's dashboard, a separate process; chat only needs the API server
        api = await asyncio.to_thread(self.probe, agent.api_url.rstrip("/") + "/health", self.timeout_s)
        if api.get("state") != "ready":
            return result
        return {**api, "detail": f"chat is up, but its health check isn't answering ({agent.health_url})"}

    async def run(self, on_change: Callable[[], Awaitable[None]]) -> None:
        while True:
            if await self.check_all():
                await on_change()
            await asyncio.sleep(self.interval_s)
