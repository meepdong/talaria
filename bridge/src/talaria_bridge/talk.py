"""Talk 3 (spec/README.md §9): a voice talker in front of the agent.

GPT Audio Mini hears the owner's recorded words directly (no transcription), does the quick things itself with a
few tools (to-dos, the day's agenda), and streams its spoken answer to the device. Anything that needs the full
agent (web, email, calendar changes, files, the server, real thinking) it hands to the agent as a written brief in
the same chat; when the agent's reply comes, the talker says it in the same voice. The OpenRouter key stays here.

Devices get talk.audio (24 kHz 16-bit mono PCM, base64, in order), talk.text (what's being said) and talk.done,
all with the talk_id from talk.turn or talk.say. Alongside, the same model writes down what the owner said
(talk.heard), and both sides are kept in the conversation (chat.talk), so the chat shows what was said.
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
WHISPER_MODEL = "openai/whisper-large-v3-turbo"  # writes down what was said: a seventh of the talker's price
# the talker's voices, the owner's pick first-to-last; marin and cedar are OpenAI's newest and most natural
VOICES = ("shimmer", "marin", "cedar", "coral", "sage", "alloy", "ballad", "verse", "ash", "echo", "fable", "nova", "onyx")
TRANSCRIBERS = ("whisper", "talker")
VOICE_LABELS = {
    "shimmer": "Shimmer: bright and warm", "marin": "Marin: natural, relaxed", "cedar": "Cedar: natural, deeper",
    "coral": "Coral: cheerful", "sage": "Sage: calm and clear", "alloy": "Alloy: neutral", "ballad": "Ballad: soft",
    "verse": "Verse: expressive", "ash": "Ash: steady", "echo": "Echo: low and smooth", "fable": "Fable: storyteller",
    "nova": "Nova: crisp", "onyx": "Onyx: deep",
}
SAMPLE = "Hi, this is how I'd sound. Want me to add anything to your day?"
MAX_AUDIO_B64 = 900_000  # about 20 s of 16 kHz WAV, inside a 1 MiB frame
MAX_SAY = 2000
ACTIVE_S = 15 * 60  # a conversation talked in this recently hears its agent's replies spoken
MAX_ROUNDS = 3
MEMORY = 8  # recent spoken exchanges kept per conversation, as text
HOLD_S = 30  # an early turn the device neither commits nor cancels is dropped after this
FILLER = "Mm-hm, one sec."  # said while the talker uses a tool without having said anything
MAX_KEPT = 4000  # longest spoken message kept in a chat
# Whisper's prompt: the words it should expect; it also keeps Hinglish in Latin letters
HINT = "Talking to {name}, a personal assistant, about to-dos, the calendar and email.{people}"
HEAR = ("Write down exactly what is said in this recording, word for word, in the language spoken (Hinglish as "
        "spoken, in Latin letters). Don't answer it or add anything: output only the words, or nothing if no words "
        "are said.")

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


class _Hold:
    """An early turn (§9 talk.turn early): it runs, but what it says, does and keeps waits for talk.commit."""

    def __init__(self):
        self.committed = asyncio.Event()
        self.queue: list[dict] = []  # notifications held until committed, in order
        self.open = False  # committed and flushed: notifications go straight out
        self.tasks: list[asyncio.Task] = []


class _Dropped(Exception):
    """An early turn that was never committed."""


class Talker:
    def __init__(self, api_key: str, chat, *, voice: str = VOICES[0], name: str = "Hermes",
                 transcriber: str = TRANSCRIBERS[0], transport: httpx.AsyncBaseTransport | None = None):
        if voice not in VOICES:
            raise ValueError(f"talk voice must be one of {', '.join(VOICES)}")
        if transcriber not in TRANSCRIBERS:
            raise ValueError(f"talk transcriber must be one of {', '.join(TRANSCRIBERS)}")
        self.transcriber = transcriber
        chosen = chat.store.setting("talk.voice")  # what the owner picked on a device outlives the config
        if chosen in VOICES:
            voice = chosen
        self.chat = chat  # ChatService: to-dos, the calendar, sending briefs, broadcasting
        self.voice = voice
        self.name = name
        self.active: dict[str, float] = {}  # conversation_id -> when it was last talked in
        self.memory: dict[str, list[dict]] = {}  # conversation_id -> recent exchanges, as text
        self._tasks: set[asyncio.Task] = set()
        self._holds: dict[str, _Hold] = {}  # talk_id -> an early turn not yet committed
        self._fillers: dict[str, list[str]] = {}  # voice -> FILLER as PCM chunks (base64), made once
        self._http = httpx.AsyncClient(base_url=OPENROUTER, transport=transport, timeout=httpx.Timeout(60, connect=5),
                                       headers={"Authorization": f"Bearer {api_key}", "User-Agent": "talaria-bridge",
                                                "X-Title": "Talaria"})

    async def close(self) -> None:
        for task in list(self._tasks):
            task.cancel()
        await self._http.aclose()

    # requests

    async def turn(self, p: dict) -> dict:
        """talk.turn {audio, format, conversation_id?, early?} → {talk_id}; the answer streams as talk.* notifications.
        An early turn is sent at the owner's first pause: it starts at once, but nothing is said, done or kept until
        talk.commit (they really had finished), and talk.cancel (they went on talking) drops it."""
        audio, fmt = p.get("audio"), p.get("format", "wav")
        if not (isinstance(audio, str) and 0 < len(audio) <= MAX_AUDIO_B64):
            raise TalkError(m.INVALID_PARAMS, f"audio must be base64, at most {MAX_AUDIO_B64} characters")
        if fmt not in ("wav", "mp3"):
            raise TalkError(m.INVALID_PARAMS, "format must be wav or mp3")
        conv = self._conv(p.get("conversation_id"))
        talk_id = "tk_" + secrets.token_hex(8)
        sound = {"type": "input_audio", "input_audio": {"data": audio, "format": fmt}}
        system = self._system(conv)  # before the owner's words are kept, which makes them the chat's last message
        hold = _Hold() if p.get("early") is True else None
        if hold is not None:
            self._holds[talk_id] = hold
        hearing = asyncio.create_task(self._heard(talk_id, conv, sound, int(time.time()), hold))
        self._tasks.add(hearing)
        hearing.add_done_callback(self._tasks.discard)
        run = self._spawn(self._run(talk_id, conv, {"role": "user", "content": [sound]}, tools=True, hearing=hearing,
                                    system=system, hold=hold))
        if hold is not None:
            hold.tasks = [hearing, run]
        if self.voice not in self._fillers:
            self._spawn(self._make_filler(self.voice))
        return {"talk_id": talk_id}

    async def commit(self, p: dict) -> dict:
        """talk.commit {talk_id}: the owner had finished; the early turn goes ahead."""
        hold = self._holds.get(p.get("talk_id"))
        if hold is None:
            raise TalkError(m.NOT_FOUND, "No early turn with that talk_id")
        hold.committed.set()
        while hold.queue:
            await self.chat.broadcast(hold.queue.pop(0))
        hold.open = True
        return {}

    def cancel(self, p: dict) -> dict:
        """talk.cancel {talk_id}: the owner went on talking; drop the early turn (unknown ids are fine)."""
        hold = self._holds.pop(p.get("talk_id"), None)
        if hold is not None and not hold.committed.is_set():
            for task in hold.tasks:
                task.cancel()
        return {}

    async def say(self, p: dict) -> dict:
        """talk.say {text?, conversation_id?, voice?} → {talk_id}: the talker says this line, as it is; in [voice]
        if given (to hear a voice before picking it: then without text, a sample line)."""
        voice = p.get("voice")
        if voice is not None and voice not in VOICES:
            raise TalkError(m.INVALID_PARAMS, f"voice must be one of {', '.join(VOICES)}")
        text = SAMPLE if voice is not None and p.get("text") is None else p.get("text")
        if not (isinstance(text, str) and text.strip() and len(text) <= MAX_SAY):
            raise TalkError(m.INVALID_PARAMS, f"text must be 1 to {MAX_SAY} characters")
        conv = self._conv(p.get("conversation_id"))
        talk_id = "tk_" + secrets.token_hex(8)
        user = {"role": "user", "content": f"Say exactly this, word for word, and nothing else:\n{text.strip()}"}
        self._spawn(self._run(talk_id, conv, user, tools=False, remember=False, voice=voice))
        return {"talk_id": talk_id}

    def voices(self) -> dict:
        """talk.voices → {voice, voices: [{id, label}]}: the talker's voices, and the one it uses."""
        return {"voice": self.voice, "voices": [{"id": v, "label": VOICE_LABELS[v]} for v in VOICES]}

    def set_voice(self, p: dict) -> dict:
        """talk.voice {voice} → {voice}: the talker speaks in this voice from now on, on every device."""
        voice = p.get("voice")
        if voice not in VOICES:
            raise TalkError(m.INVALID_PARAMS, f"voice must be one of {', '.join(VOICES)}")
        self.voice = voice
        self.chat.store.set_setting("talk.voice", voice)
        if voice not in self._fillers:
            self._spawn(self._make_filler(voice))
        return {"voice": voice}

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

    def _spawn(self, coro) -> asyncio.Task:
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return task

    async def _out(self, hold: _Hold | None, msg: dict) -> None:
        """Send a talk notification, or hold it while its early turn isn't committed."""
        if hold is not None and not hold.open:
            hold.queue.append(msg)
        else:
            await self.chat.broadcast(msg)

    async def _go_ahead(self, hold: _Hold | None) -> None:
        """Before anything that can't be taken back: wait for an early turn to be committed."""
        if hold is not None:
            try:
                await asyncio.wait_for(hold.committed.wait(), HOLD_S)
            except asyncio.TimeoutError:
                raise _Dropped from None

    def _system(self, conv: str | None) -> dict:
        t = dt.datetime.now().astimezone()
        now = f"{t:%A} {t.day} {t:%B %Y, %H:%M %Z}"  # no %-d: Windows lacks it
        context = ""
        c = self.chat.store.get(conv) if conv else None
        if c is not None and c.last_role == "assistant" and c.last_text:
            context = f"\nThe agent's last message in this conversation: {c.last_text[:1500]}"
        return {"role": "system", "content": SYSTEM.format(name=self.name, now=now, context=context)}

    async def _run(self, talk_id: str, conv: str | None, user: dict, *, tools: bool, remember: bool = True,
                   unprompted: bool = False, hearing: asyncio.Task | None = None, system: dict | None = None,
                   voice: str | None = None, hold: _Hold | None = None) -> None:
        try:
            await self._talk(talk_id, conv, user, tools=tools, remember=remember, unprompted=unprompted,
                             hearing=hearing, system=system, voice=voice or self.voice, hold=hold)
        except _Dropped:
            log.info("talk %s: early turn never committed, dropped", talk_id)
            if hearing is not None:
                hearing.cancel()
        finally:
            if hold is not None:
                self._holds.pop(talk_id, None)

    async def _talk(self, talk_id: str, conv: str | None, user: dict, *, tools: bool, remember: bool, unprompted: bool,
                    hearing: asyncio.Task | None, system: dict | None, voice: str, hold: _Hold | None) -> None:
        messages = [system or self._system(conv), *self.memory.get(conv or "", []), user]
        said_all, error = [], None
        try:
            for _ in range(MAX_ROUNDS):
                said, calls = await self._stream(talk_id, conv, messages, tools, voice, hold)
                if said:
                    said_all.append(said)
                if not calls:
                    break
                if not said:  # going quiet to use a tool: say so
                    for i, chunk in enumerate(self._fillers.get(voice, [])):
                        await self._out(hold, m.notification("talk.audio", {"talk_id": talk_id, "seq": i, "data": chunk,
                                                                            **({"conversation_id": conv} if conv else {})}))
                await self._go_ahead(hold)
                if hearing is not None and any(c["function"]["name"] == "ask_hermes" for c in calls):
                    conv = (await hearing)[0]  # the owner's words go in the chat before the brief
                messages.append({"role": "assistant", "content": said or None, "tool_calls": calls})
                for call in calls:
                    result, conv = await self._tool(call, conv, talk_id)
                    messages.append({"role": "tool", "tool_call_id": call["id"], "content": result})
        except TalkError as exc:
            error = exc.message
        except _Dropped:
            raise
        except Exception as exc:  # noqa: BLE001 (tell the device, whatever it was)
            log.exception("talk %s failed", talk_id)
            error = f"Talk failed: {type(exc).__name__}"
        await self._go_ahead(hold)
        said = " ".join(said_all).strip()
        heard = None
        if hearing is not None:
            heard_in, heard = await hearing
            conv = conv or heard_in
            if conv and said:
                await self._keep(conv, "assistant", said)
        if remember and said:
            # the owner's audio isn't kept: their words as written down, or what the talker answered says what it heard
            if heard is None:
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
        await self._out(hold, m.notification("talk.done", done))

    async def _heard(self, talk_id: str, conv: str | None, sound: dict, at: int,
                     hold: _Hold | None = None) -> tuple[str | None, str]:
        """Write down what the owner said and keep it in the chat (starting one if needed): (conversation, words).
        Never raises: without the words, Talk carries on."""
        words = await self._whisper(sound) if self.transcriber == "whisper" else None
        if words is None:
            words = await self._listen(sound)
        words = words.strip('"“” ')[:MAX_KEPT - 2]
        if hold is not None:
            await hold.committed.wait()  # an early turn keeps nothing until it's committed (or is cancelled)
        try:
            if conv is None:
                conv = await self._new_chat(words or "Talk")
            if conv is not None and words:
                await self._keep(conv, "user", "🎙 " + words, at)
                await self.chat.broadcast(m.notification("talk.heard", {"talk_id": talk_id, "text": words,
                                                                        "conversation_id": conv}))
        except Exception:  # noqa: BLE001 (keeping it is a nicety; Talk goes on)
            log.exception("talk %s: couldn't keep what was said", talk_id)
        return conv, words

    async def _whisper(self, sound: dict) -> str | None:
        """Whisper's words, or None if it couldn't (the talker model writes them down instead)."""
        people = ", ".join(self._people())
        people = f" Names: {people}." if people else ""
        try:
            resp = await self._http.post("/audio/transcriptions", timeout=httpx.Timeout(20, connect=5), json={
                "model": WHISPER_MODEL, "input_audio": sound["input_audio"],
                "prompt": HINT.format(name=self.name, people=people)})
            if resp.status_code == 200:
                return str(resp.json().get("text") or "").strip()
            log.warning("whisper couldn't write down the words (%s): %s", resp.status_code, resp.text[:200])
        except (httpx.HTTPError, ValueError, AttributeError) as exc:
            log.warning("whisper couldn't write down the words: %s", type(exc).__name__)
        return None

    def _people(self) -> list[str]:
        """Capitalised words from the open to-dos (names, mostly), so Whisper spells them as the owner does."""
        if self.chat.todos is None:
            return []
        seen: list[str] = []
        for t in self.chat.todos.list():
            if t["done"]:
                continue
            for w in t["text"].split()[1:]:
                w = w.strip(".,;:!?'\"()")
                if len(w) > 2 and w[0].isupper() and w not in seen:
                    seen.append(w)
        return seen[:12]

    async def _listen(self, sound: dict) -> str:
        """The talker model's words for what was said ("" if it couldn't)."""
        words = ""
        try:
            resp = await self._http.post("/chat/completions", timeout=httpx.Timeout(30, connect=5), json={
                "model": TALKER_MODEL, "modalities": ["text"],
                "messages": [{"role": "system", "content": HEAR},
                             {"role": "user", "content": [{"type": "text", "text": "The recording:"}, sound]}]})
            if resp.status_code == 200:
                words = str(resp.json()["choices"][0]["message"].get("content") or "").strip()
            else:
                log.warning("talker couldn't write down the words (%s)", resp.status_code)
        except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError) as exc:
            log.warning("talker couldn't write down the words: %s", type(exc).__name__)
        return words

    async def _new_chat(self, words: str) -> str | None:
        agent = self.chat.default_agent
        if agent is None or agent not in self.chat.agents:
            return None
        conv = await self.chat._new_conversation(agent, self.chat.agents[agent], words)
        self.active[conv.id] = time.time()
        return conv.id

    async def _keep(self, conv: str, role: str, text: str, at: int | None = None) -> None:
        at = at or int(time.time())
        message = self.chat.store.add_talk(conv, at, role, text[:MAX_KEPT])
        if role == "user":
            self.chat.store.archive(conv, False)  # talking in an archived chat brings it back, as writing does
        self.chat.store.touch(conv, role, text, max(at, int(time.time())))
        await self.chat.broadcast(m.notification("chat.talk", {"conversation_id": conv, "message": message}))

    async def _make_filler(self, voice: str) -> None:
        """Record FILLER in [voice] once, for when the talker goes quiet to use a tool."""
        body = {"model": TALKER_MODEL, "stream": True, "modalities": ["text", "audio"],
                "audio": {"voice": voice, "format": "pcm16"},
                "messages": [{"role": "user", "content": f"Say exactly this, casually, and nothing else: {FILLER}"}]}
        chunks: list[str] = []
        try:
            async with self._http.stream("POST", "/chat/completions", json=body) as resp:
                if resp.status_code != 200:
                    return
                async for line in resp.aiter_lines():
                    if line.startswith("data:") and line.strip() != "data: [DONE]":
                        with contextlib.suppress(ValueError, AttributeError):
                            for choice in json.loads(line[5:]).get("choices") or []:
                                data = ((choice.get("delta") or {}).get("audio") or {}).get("data")
                                if data:
                                    chunks.append(data)
        except httpx.HTTPError:
            return
        if chunks:
            self._fillers[voice] = chunks

    async def _stream(self, talk_id: str, conv: str | None, messages: list[dict], tools: bool,
                      voice: str, hold: _Hold | None = None) -> tuple[str, list[dict]]:
        """One model call: its audio and words go to the devices as they come; returns what it said and its tool calls."""
        body = {"model": TALKER_MODEL, "stream": True, "modalities": ["text", "audio"],
                "audio": {"voice": voice, "format": "pcm16"}, "messages": messages}
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
                            await self._out(hold, m.notification("talk.audio", {"talk_id": talk_id, "seq": seq,
                                                                                    "data": audio["data"], **where}))
                            seq += 1
                        if audio.get("transcript"):
                            said += audio["transcript"]
                            await self._out(hold, m.notification("talk.text", {"talk_id": talk_id,
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


TALK_METHODS = frozenset({"talk.turn", "talk.say", "talk.end", "talk.voices", "talk.voice", "talk.commit", "talk.cancel"})
