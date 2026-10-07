"""Typing with Tally (§9 "Tally in chat"): the same assistant as Talk, on the same model, with the same tools.

A Tally conversation is an ordinary conversation whose agent is `tally`. Its messages are kept by the bridge (the
talk store, as Talk's are: there's no Hermes session behind it); Tally reads the recent ones as her memory. She
answers easy things herself and hands work to Hermes or a bot with the tools Talk has; their reports come back here
and she retells them, in writing (and out loud while the conversation is being talked in)."""

from __future__ import annotations

import json
import logging
import secrets
import time
from collections.abc import AsyncIterator

from .hermes import HermesError
from .tally_models import ModelError, split_id

log = logging.getLogger("talaria.tally")

AGENT_ID = "tally"
HISTORY = 24  # recent messages Tally reads, both typed and spoken
MAX_ROUNDS = 4
WRITTEN = ("\n\nYou're in a typed chat now, not speaking: answer in short written replies, plain text (a short list "
           "is fine when it helps). Everything else above still holds.")


class TallyChat:
    """An agent client, shaped like hermes.HermesClient for the chat service, backed by the talker (talk.py)."""

    def __init__(self, talker, chat):
        self.talker = talker
        self.chat = chat

    # what the chat service calls

    async def create_session(self, session_id: str, title: str | None) -> dict:
        return {"id": session_id}

    async def rename_session(self, session_id: str, title: str) -> None:
        return None

    async def pin_session(self, session_id: str, pinned: bool) -> None:
        return None

    async def delete_session(self, session_id: str) -> None:
        return None

    async def session(self, session_id: str) -> dict:
        return {"id": session_id}

    async def messages(self, session_id: str, *, limit: int, offset: int) -> list[dict]:
        return []  # Tally's messages are the bridge's own (the talk store); chat.history adds them

    async def model_options(self) -> dict:
        """Tally's main model choices, in Hermes's shape, so a chat's model button picks Tally's model."""
        found = await self.talker.models.choices()
        groups: dict[str, list[str]] = {}
        for c in found["brains"]:
            provider, model = split_id(c.id)
            groups.setdefault(provider, []).append(model)
        names = {"gemini": "Gemini (your key)", "openrouter": "OpenRouter"}
        provider, model = split_id(self.talker.brain_id)
        return {"providers": [{"slug": slug, "name": names.get(slug, slug), "models": models}
                              for slug, models in groups.items()], "provider": provider, "model": model}

    async def set_session_model(self, session_id: str, provider: str, model: str) -> None:
        from .talk import TalkError
        try:
            await self.talker.configure({"brain": f"{provider}:{model}"})
        except TalkError as exc:
            raise HermesError(400, "bad_request", exc.message) from None

    async def stop_run(self, run_id: str) -> None:
        return None

    async def steer_run(self, run_id: str, text: str) -> bool:
        return False

    async def approve_run(self, run_id: str, choice: str, request_id: str | None = None) -> bool:
        return False

    async def close(self) -> None:
        return None

    # a turn

    def _conversation(self, session_id: str):
        return next((c for c in self.chat.store.all() if c.hermes_session_id == session_id), None)

    def _history(self, conv_id: str) -> list[dict]:
        rows = self.chat.store.talk_messages(conv_id)[-HISTORY:]
        out = []
        for r in rows:
            text = str(r.get("text") or "").removeprefix("🎙 ").strip()
            if text and r.get("role") in ("user", "assistant"):
                out.append({"role": r["role"], "content": text})
        return out

    async def chat_stream(self, session_id: str, text: str | list) -> AsyncIterator[tuple[str, dict]]:
        conv = self._conversation(session_id)
        if conv is None:
            yield "error", {"message": "Unknown Tally conversation"}
            return
        words = text if isinstance(text, str) else "\n".join(
            p.get("text", "") for p in text if isinstance(p, dict) and p.get("type") == "text")
        run_id = "tally_" + secrets.token_hex(6)
        yield "run.started", {"run_id": run_id}
        history = self._history(conv.id)
        self.chat.store.add_talk(conv.id, int(time.time()), "user", words[:4000])
        async for event in self._rounds(conv.id, history, {"role": "user", "content": words}, run_id):
            yield event

    async def write(self, conv_id: str, user: dict) -> None:
        """Tally writes in a conversation on her own (a worker's report, retold), kept and sent to every device."""
        reply = ""
        try:
            async for name, payload in self._rounds(conv_id, self._history(conv_id), user, "tally_" + secrets.token_hex(6)):
                if name == "assistant.completed":
                    reply = payload["content"]
        except Exception:  # noqa: BLE001 (a report not retold is logged, never fatal)
            log.exception("Tally couldn't write in %s", conv_id)
        if reply:
            await self.talker._keep(conv_id, "assistant", reply)

    async def _rounds(self, conv_id: str, history: list[dict], user: dict, run_id: str) -> AsyncIterator[tuple[str, dict]]:
        system = self.talker._system(conv_id)
        system = {**system, "content": system["content"] + WRITTEN}
        messages = [system, *history, user]
        from .talk import ASK_BOT, TOOLS
        tools = TOOLS + ([ASK_BOT] if self.talker._bots() else [])
        said_all: list[str] = []
        try:
            for _ in range(MAX_ROUNDS):
                said, calls, raw = "", [], []
                async for kind, value in self.talker.brain.stream(messages, tools, speak=False):
                    if kind == "text":
                        said += value
                        yield "assistant.delta", {"delta": value}
                    elif kind == "calls":
                        calls = value
                    elif kind == "raw":
                        raw = value
                if said.strip():
                    said_all.append(said.strip())
                if not calls:
                    break
                messages.append({"role": "assistant", "content": said or None, "tool_calls": calls,
                                 **({"_gemini": raw} if raw else {})})
                for call in calls:
                    name = call["function"]["name"]
                    try:
                        preview = str(next(iter(json.loads(call["function"]["arguments"] or "{}").values()), ""))[:80]
                    except (ValueError, StopIteration, AttributeError):
                        preview = ""
                    yield "tool.started", {"tool_name": name, "preview": preview}
                    result, _ = await self.talker._tool(call, conv_id, run_id)
                    yield "tool.completed", {"tool_name": name}
                    messages.append({"role": "tool", "tool_call_id": call["id"], "content": result})
                if said and not said.endswith((" ", "\n")):
                    yield "assistant.delta", {"delta": "\n\n"}
        except ModelError as exc:
            yield "run.failed", {"error": str(exc)}
            return
        reply = "\n\n".join(said_all).strip()
        if reply and user.get("role") == "user" and run_id.startswith("tally_") and isinstance(user.get("content"), str) \
                and not user["content"].startswith("[Report"):
            self.chat.store.add_talk(conv_id, int(time.time()), "assistant", reply[:4000])
        yield "assistant.completed", {"content": reply}
        provider, model = split_id(self.talker.brain_id)
        yield "run.completed", {"runtime": {"provider": provider, "model": model}}
        yield "done", {}
