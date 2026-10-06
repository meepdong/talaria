"""Talk 3 (spec/README.md §9): a voice talker in front of the agent.

GPT Audio Mini hears the owner's recorded words directly (no transcription), does the quick things itself with a
few tools (to-dos, the day's agenda), and streams its spoken answer to the device. Anything that needs the full
agent (web, email, calendar changes, files, the server, real thinking) it hands to the agent as a written brief in
the same chat; when the agent's reply comes, the talker says it in the same voice. The OpenRouter key stays here.

Devices get talk.audio (24 kHz 16-bit mono PCM, base64, in order), talk.text (what's being said) and talk.done,
all with the talk_id from talk.turn or talk.say.
"""

from __future__ import annotations

import asyncio
import base64
import contextlib
import datetime as dt
import json
import logging
import secrets
import time

import httpx

from .protocol import messages as m

log = logging.getLogger("talaria.talk")

OPENROUTER = "https://openrouter.ai/api/v1"
TALKER_MODEL = "openai/gpt-audio-mini"
VOICES = ("shimmer", "coral", "nova", "sage", "alloy", "ballad", "verse", "ash", "echo", "fable", "onyx")
MAX_AUDIO_B64 = 900_000  # about 20 s of 16 kHz WAV, inside a 1 MiB frame
MAX_SAY = 2000
ACTIVE_S = 15 * 60  # a conversation talked in this recently hears its agent's replies spoken
MAX_ROUNDS = 3
MEMORY = 8  # recent spoken exchanges kept per conversation, as text

SYSTEM = """You are the voice of {name}, the owner's personal assistant, in a spoken conversation. The owner talks \
to you; you answer out loud. Behind you is {name}'s full agent, which has the owner's memory, email, calendar, \
files, the web and a server. It's {now}.

How you talk:
- One or two short spoken sentences. Plain speech: no lists, Markdown, links or emoji. Say times and dates the way \
people say them ("ten thirty tomorrow").
- Warm, quick and natural; no filler like "Great question".

What you do yourself, with your tools:
- To-dos: add_todo, complete_todos (tick off by what the owner calls them), list_todos.
- The agenda: get_agenda (calendar events, to-dos due).
- After you change something, read back exactly what you did and check: "Added: send Shreyas the humanoid files. \
Did I get that right?"

What you hand to the agent, with ask_hermes:
- Anything else: the web, email, calendar changes, files, the server, the owner's history and memory, or anything \
that needs thinking. Say in a few words that you've asked it ("I've asked {name} to add that to your calendar; I'll \
tell you when it's done") and carry on.
- Write the brief so it stands on its own; the agent can't hear the audio:
  1. What the owner said, in plain words, with names spelled as heard and the likely spelling.
  2. The goal and what "done" looks like (for example: event created, with its time).
  3. Details: names, dates, times, amounts; and what you already did, so it isn't done twice.
  4. Relevant context from this conversation.
  5. Ask it to reply in one to three plain sentences saying what it did and what it checked, and to ask if \
something essential is missing rather than guess, above all before anything risky.

Rules:
- Never make up facts: no invented meetings, times, numbers or results. If you don't know, use a tool or ask the \
agent.
- Never approve anything for the owner and never ask the agent to approve or "always allow" anything: approvals are \
the owner's, on screen or by saying yes.
- When a result from the agent arrives, say it briefly in your own words and offer more if there's more.
{context}"""

TOOLS = [
    {"type": "function", "function": {
        "name": "add_todo", "description": "Add a to-do to the owner's list.",
        "parameters": {"type": "object", "properties": {
            "text": {"type": "string", "description": "the to-do, as the owner would write it"},
            "due": {"type": "string", "description": "optional: YYYY-MM-DD"}}, "required": ["text"]}}},
    {"type": "function", "function": {
        "name": "complete_todos", "description": "Tick off open to-dos by what the owner calls them.",
        "parameters": {"type": "object", "properties": {
            "items": {"type": "array", "items": {"type": "string"}}}, "required": ["items"]}}},
    {"type": "function", "function": {
        "name": "list_todos", "description": "The owner's open to-dos.",
        "parameters": {"type": "object", "properties": {}}}},
    {"type": "function", "function": {
        "name": "get_agenda", "description": "The owner's agenda for a day: calendar events and to-dos due.",
        "parameters": {"type": "object", "properties": {
            "day": {"type": "string", "enum": ["today", "tomorrow"]}}, "required": ["day"]}}},
    {"type": "function", "function": {
        "name": "ask_hermes",
        "description": "Hand a task to the full agent, which runs in the background and reports back. Use for "
                       "anything beyond to-dos and the agenda. Write a self-contained brief (see the rules).",
        "parameters": {"type": "object", "properties": {"brief": {"type": "string"}}, "required": ["brief"]}}},
]


class TalkError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class Talker:
    def __init__(self, api_key: str, chat, *, voice: str = VOICES[0], name: str = "Hermes",
                 transport: httpx.AsyncBaseTransport | None = None):
        if voice not in VOICES:
            raise ValueError(f"talk voice must be one of {', '.join(VOICES)}")
        self.chat = chat  # ChatService: to-dos, the calendar, sending briefs, broadcasting
        self.voice = voice
        self.name = name
        self.active: dict[str, float] = {}  # conversation_id -> when it was last talked in
        self.memory: dict[str, list[dict]] = {}  # conversation_id -> recent exchanges, as text
        self._tasks: set[asyncio.Task] = set()
        self._http = httpx.AsyncClient(base_url=OPENROUTER, transport=transport, timeout=httpx.Timeout(60, connect=5),
                                       headers={"Authorization": f"Bearer {api_key}", "User-Agent": "talaria-bridge",
                                                "X-Title": "Talaria"})

    async def close(self) -> None:
        for task in list(self._tasks):
            task.cancel()
        await self._http.aclose()

    # requests

    async def turn(self, p: dict) -> dict:
        """talk.turn {audio, format, conversation_id?} → {talk_id}; the answer streams as talk.* notifications."""
        audio, fmt = p.get("audio"), p.get("format", "wav")
        if not (isinstance(audio, str) and 0 < len(audio) <= MAX_AUDIO_B64):
            raise TalkError(m.INVALID_PARAMS, f"audio must be base64, at most {MAX_AUDIO_B64} characters")
        if fmt not in ("wav", "mp3"):
            raise TalkError(m.INVALID_PARAMS, "format must be wav or mp3")
        conv = self._conv(p.get("conversation_id"))
        talk_id = "tk_" + secrets.token_hex(8)
        user = {"role": "user", "content": [{"type": "input_audio", "input_audio": {"data": audio, "format": fmt}}]}
        self._spawn(self._run(talk_id, conv, user, tools=True))
        return {"talk_id": talk_id}

    async def say(self, p: dict) -> dict:
        """talk.say {text, conversation_id?} → {talk_id}: the talker says this line, as it is."""
        text = p.get("text")
        if not (isinstance(text, str) and text.strip() and len(text) <= MAX_SAY):
            raise TalkError(m.INVALID_PARAMS, f"text must be 1 to {MAX_SAY} characters")
        conv = self._conv(p.get("conversation_id"))
        talk_id = "tk_" + secrets.token_hex(8)
        user = {"role": "user", "content": f"Say exactly this, word for word, and nothing else:\n{text.strip()}"}
        self._spawn(self._run(talk_id, conv, user, tools=False, remember=False))
        return {"talk_id": talk_id}

    def end(self, p: dict) -> dict:
        """talk.end {conversation_id}: stop speaking the agent's replies in it."""
        conv = p.get("conversation_id")
        if isinstance(conv, str):
            self.active.pop(conv, None)
        return {}

    # the agent's replies

    def on_reply(self, conversation_id: str, text: str, status: str) -> None:
        """The agent finished a turn: if it's a conversation being talked in, say the result."""
        at = self.active.get(conversation_id)
        if at is None or time.time() - at > ACTIVE_S or not text.strip():
            return
        talk_id = "tk_" + secrets.token_hex(8)
        word = "finished" if status == "completed" else f"stopped ({status})"
        user = {"role": "user", "content": f"[{self.name} {word}. Its reply, for you to tell the owner briefly in "
                                           f"your own words:]\n{text.strip()[:4000]}"}
        self._spawn(self._run(talk_id, conversation_id, user, tools=False, unprompted=True))

    # the conversation

    def _conv(self, value) -> str | None:
        if value is None:
            return None
        if not (isinstance(value, str) and 0 < len(value) <= 64):
            raise TalkError(m.INVALID_PARAMS, "conversation_id must be a string of 1 to 64 characters")
        if self.chat.store.get(value) is None:
            raise TalkError(m.NOT_FOUND, "Unknown conversation")
        self.active[value] = time.time()
        return value

    def _spawn(self, coro) -> None:
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    def _system(self, conv: str | None) -> dict:
        t = dt.datetime.now().astimezone()
        now = f"{t:%A} {t.day} {t:%B %Y, %H:%M %Z}"  # no %-d: Windows lacks it
        context = ""
        c = self.chat.store.get(conv) if conv else None
        if c is not None and c.last_role == "assistant" and c.last_text:
            context = f"\nThe agent's last message in this conversation: {c.last_text[:1500]}"
        return {"role": "system", "content": SYSTEM.format(name=self.name, now=now, context=context)}

    async def _run(self, talk_id: str, conv: str | None, user: dict, *, tools: bool, remember: bool = True,
                   unprompted: bool = False) -> None:
        messages = [self._system(conv), *self.memory.get(conv or "", []), user]
        said_all, error = [], None
        try:
            for _ in range(MAX_ROUNDS):
                said, calls = await self._stream(talk_id, conv, messages, tools)
                if said:
                    said_all.append(said)
                if not calls:
                    break
                messages.append({"role": "assistant", "content": said or None, "tool_calls": calls})
                for call in calls:
                    result, conv = await self._tool(call, conv, talk_id)
                    messages.append({"role": "tool", "tool_call_id": call["id"], "content": result})
        except TalkError as exc:
            error = exc.message
        except Exception as exc:  # noqa: BLE001 (tell the device, whatever it was)
            log.exception("talk %s failed", talk_id)
            error = f"Talk failed: {type(exc).__name__}"
        said = " ".join(said_all).strip()
        if remember and said:
            # the owner's audio isn't kept; what the talker answered says what it heard
            heard = user["content"][:500] if isinstance(user["content"], str) else "(the owner spoke)"
            kept = self.memory.setdefault(conv or "", [])
            kept += [{"role": "user", "content": heard}, {"role": "assistant", "content": said}]
            del kept[:-2 * MEMORY]
        done = {"talk_id": talk_id, "text": said}
        if conv:
            done["conversation_id"] = conv
        if unprompted:
            done["unprompted"] = True
        if error:
            done["error"] = error
        await self.chat.broadcast(m.notification("talk.done", done))

    async def _stream(self, talk_id: str, conv: str | None, messages: list[dict], tools: bool) -> tuple[str, list[dict]]:
        """One model call: its audio and words go to the devices as they come; returns what it said and its tool calls."""
        body = {"model": TALKER_MODEL, "stream": True, "modalities": ["text", "audio"],
                "audio": {"voice": self.voice, "format": "pcm16"}, "messages": messages}
        if tools:
            body["tools"] = TOOLS
        said, calls, seq = "", {}, 0
        where = {"conversation_id": conv} if conv else {}  # devices know an unprompted reply by its conversation
        try:
            async with self._http.stream("POST", "/chat/completions", json=body) as resp:
                if resp.status_code != 200:
                    text = (await resp.aread())[:300].decode(errors="replace")
                    log.warning("talker refused (%s): %s", resp.status_code, text)
                    raise TalkError(m.AGENT_UNAVAILABLE, f"The voice model refused ({resp.status_code})")
                async for line in resp.aiter_lines():
                    if not line.startswith("data:") or line.strip() == "data: [DONE]":
                        continue
                    try:
                        chunk = json.loads(line[5:])
                    except ValueError:
                        continue
                    for choice in chunk.get("choices") or []:
                        delta = choice.get("delta") or {}
                        audio = delta.get("audio") or {}
                        if audio.get("data"):
                            await self.chat.broadcast(m.notification("talk.audio", {"talk_id": talk_id, "seq": seq,
                                                                                    "data": audio["data"], **where}))
                            seq += 1
                        if audio.get("transcript"):
                            said += audio["transcript"]
                            await self.chat.broadcast(m.notification("talk.text", {"talk_id": talk_id,
                                                                                   "text": audio["transcript"], **where}))
                        for tc in delta.get("tool_calls") or []:
                            entry = calls.setdefault(tc.get("index", 0), {"id": "", "type": "function",
                                                                          "function": {"name": "", "arguments": ""}})
                            entry["id"] = tc.get("id") or entry["id"]
                            fn = tc.get("function") or {}
                            entry["function"]["name"] += fn.get("name") or ""
                            entry["function"]["arguments"] += fn.get("arguments") or ""
        except httpx.HTTPError as exc:
            raise TalkError(m.AGENT_UNAVAILABLE, f"The voice model can't be reached ({type(exc).__name__})") from None
        out = [c for _, c in sorted(calls.items())]
        for i, c in enumerate(out):
            c["id"] = c["id"] or f"call_{i}"
        return said.strip(), out

    # tools

    async def _tool(self, call: dict, conv: str | None, talk_id: str) -> tuple[str, str | None]:
        name = call["function"]["name"]
        try:
            args = json.loads(call["function"]["arguments"] or "{}")
        except ValueError:
            return "Error: the arguments weren't JSON.", conv
        try:
            if name == "add_todo":
                return self._add_todo(args), conv
            if name == "complete_todos":
                return self._complete(args), conv
            if name == "list_todos":
                return self._open_todos(), conv
            if name == "get_agenda":
                return await self._agenda(args), conv
            if name == "ask_hermes":
                return await self._ask(args, conv, talk_id)
        except Exception as exc:  # noqa: BLE001 (the model hears what went wrong and says so)
            log.warning("talk tool %s failed: %s", name, exc)
            return f"Error: {getattr(exc, 'message', None) or exc}", conv
        return f"Error: no tool called {name}.", conv

    def _todos(self):
        if self.chat.todos is None:
            raise TalkError(m.METHOD_NOT_FOUND, "The bridge keeps no to-dos")
        return self.chat.todos

    def _changed(self) -> None:
        self._spawn(self.chat.broadcast(m.notification("todos.changed", {"todos": self._todos().list()})))

    def _add_todo(self, args: dict) -> str:
        params = {"text": str(args.get("text") or "").strip()}
        if args.get("due"):
            params["due"] = args["due"]
        todo = self._todos().add(params)
        self._changed()
        return f"Added: {todo['text']}" + (f" (due {todo['due']})" if todo.get("due") else "")

    def _complete(self, args: dict) -> str:
        store = self._todos()
        open_ = [t for t in store.list() if not t["done"]]
        done, missing = [], []
        for item in args.get("items") or []:
            words = {w for w in str(item).lower().split() if len(w) > 2}
            best = max(open_, key=lambda t: len(words & set(t["text"].lower().split())), default=None)
            if best is None or not words & set(best["text"].lower().split()):
                missing.append(str(item))
                continue
            store.update({"id": best["id"], "done": True})
            open_.remove(best)
            done.append(best["text"])
        if done:
            self._changed()
        return json.dumps({"ticked_off": done, "not_found": missing})

    def _open_todos(self) -> str:
        items = [{"text": t["text"], **({"due": t["due"]} if t.get("due") else {})}
                 for t in self._todos().list() if not t["done"]]
        return json.dumps({"open": items[:25], "more": max(0, len(items) - 25)})

    async def _agenda(self, args: dict) -> str:
        day = dt.date.today() + dt.timedelta(days=1 if args.get("day") == "tomorrow" else 0)
        out: dict = {"date": day.isoformat()}
        if self.chat.automations is not None:
            cal = await self.chat.automations.calendar_day({"date": day.isoformat()})
            out["events"] = [{"title": e["title"], "start": e["start"], "end": e["end"], "all_day": e["all_day"]}
                             for e in cal.get("events", [])]
            if cal.get("error"):
                out["calendar_error"] = cal["error"]
        if self.chat.todos is not None:
            out["todos_due"] = [t["text"] for t in self.chat.todos.list()
                                if not t["done"] and t.get("due") and t["due"] <= day.isoformat()]
        return json.dumps(out)

    async def _ask(self, args: dict, conv: str | None, talk_id: str) -> tuple[str, str | None]:
        brief = str(args.get("brief") or "").strip()
        if not brief:
            return "Error: the brief is empty.", conv
        params = {"text": f"🎙 From Talk: {brief}"}
        if conv:
            params["conversation_id"] = conv
        result, turn = await self.chat.send(params)
        if turn is not None:
            self.chat.start(turn)
        conv = result["conversation_id"]
        self.active[conv] = time.time()
        return f"Sent to {self.name}; it's working on it and its reply will be spoken when it comes.", conv


TALK_METHODS = frozenset({"talk.turn", "talk.say", "talk.end"})
