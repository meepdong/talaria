"""The "Tidying up this long chat…" note (§9): a big chat that shows nothing for a while is being compressed."""

from __future__ import annotations

import asyncio
from pathlib import Path

from talaria_bridge import chat as chat_module
from talaria_bridge.chat import ChatService, ChatStore


class Agent:
    """A Hermes that reads [per_call] tokens per model call, twice a turn, and is quiet for [quiet] s first."""

    def __init__(self):
        self.read, self.calls, self.per_call, self.quiet = 0, 0, 1000, 0.0

    async def create_session(self, session_id: str, title: str) -> dict:
        return {}

    async def session(self, session_id: str) -> dict:
        return {"input_tokens": self.read // 4, "cache_read_tokens": self.read - self.read // 4, "api_call_count": self.calls}

    async def chat_stream(self, session_id: str, content):
        yield "run.started", {"run_id": "r"}
        await asyncio.sleep(self.quiet)
        self.read += 2 * self.per_call
        self.calls += 2
        yield "assistant.delta", {"delta": "Done."}
        yield "assistant.completed", {"content": "Done."}
        yield "run.completed", {}
        yield "done", {}

    async def close(self) -> None:
        pass


async def turn(chat: ChatService, sent: list[dict], conv: str | None) -> tuple[str, list[str]]:
    sent.clear()
    result, t = await chat.send({"text": "And now?", **({"conversation_id": conv} if conv else {})})
    chat.start(t)
    for _ in range(300):
        if any(x["method"] == "chat.done" for x in sent):
            break
        await asyncio.sleep(0.01)
    await asyncio.sleep(0.05)  # the size is measured after the turn
    notes = [x["params"]["text"] for x in sent if x["method"] == "chat.delta" and x["params"]["kind"] == "commentary"]
    return result["conversation_id"], notes


async def test_a_big_quiet_chat_says_its_being_tidied(tmp_path: Path, monkeypatch):
    monkeypatch.setattr(chat_module, "TIDY_AFTER_S", 0.1)
    sent: list[dict] = []

    async def broadcast(msg: dict) -> None:
        sent.append(msg)

    agent = Agent()
    chat = ChatService(ChatStore(tmp_path / "chat.db"), {"hermes": agent}, broadcast)
    agent.quiet = 0.3
    conv, notes = await turn(chat, sent, None)
    assert notes == [], "small so far: a slow start is just a slow start"
    agent.per_call = 60_000
    conv, notes = await turn(chat, sent, conv)
    assert notes == [], "measured during this turn: big from now on"
    conv, notes = await turn(chat, sent, conv)
    assert notes == ["Tidying up this long chat…"]
    agent.quiet = 0
    conv, notes = await turn(chat, sent, conv)
    assert notes == [], "it answered at once: nothing to say"
    await chat.close()
