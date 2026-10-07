"""Talk (spec/README.md §9): Tally, a voice talker in front of the agent, on any model (tally_models.py).

Tally's model (GPT Audio Mini, a Gemini model on the owner's Gemini key, …) hears the owner's recorded words directly
when it can (a model that can't gets them written down first), does the quick things itself with a few tools
(to-dos, the day's agenda), and streams its spoken answer to the device: in its own voice, or sentence by sentence
through Tally's voice model (Gemini 3.8 Flash TTS, ElevenLabs, …). Anything that needs the full
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
import mimetypes
import secrets
import time

import httpx

from .workorders import work_order
from .protocol import messages as m
from .tally_models import ModelError, Models, OPENAI_VOICES, sentences, split_id

log = logging.getLogger("talaria.talk")

OPENROUTER = "https://openrouter.ai/api/v1"
TALKER_MODEL = "openai/gpt-audio-mini"
DEFAULT_BRAIN = f"openrouter:{TALKER_MODEL}"  # agents.json talk_brain changes it; devices too (talk.configure)
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

SYSTEM = """You are {name}, the owner's personal assistant, talking with them out loud. {persona}It's {now}.

You lead. You do the talking; your workers do the heavy work and report back to you, never to the owner:
- {worker}: the full agent on the owner's server, with their memory, email, calendar, files, the web and the \
server. Your main worker: ask_hermes.{bots}

How you talk:
- One or two short spoken sentences. Plain speech: no lists, Markdown, links or emoji. Say times and dates the way \
people say them ("ten thirty tomorrow").
- Warm, quick and natural; no filler like "Great question". You're one assistant: speak as yourself ("I've asked \
{worker} to…", "Research found…"), never like a relay.

What you do yourself, with your tools:
- To-dos: add_todo, complete_todos (tick off by what the owner calls them), list_todos.
- The agenda: get_agenda (calendar events, to-dos due).
- After you change something, read back exactly what you did and check: "Added: send Shreyas the humanoid files. \
Did I get that right?"

What you hand to a worker:
- Anything else: the web, email, calendar changes, files, the server, the owner's history and memory, or anything \
that needs thinking. Pick the worker that fits; you can ask several at once. Say in a few words who you asked \
("I've asked {worker} to add that; I'll tell you when it's done") and carry on talking.
- Write the brief so it stands on its own; workers can't hear the audio:
  1. What the owner said, in plain words, with names spelled as heard and the likely spelling.
  2. The goal and what "done" looks like (for example: event created, with its time).
  3. Details: names, dates, times, amounts; and what you already did, so it isn't done twice.
  4. Relevant context from this conversation.
- Files: you can't open files yourself (pictures, PDFs, videos, documents). {files}Hand the ones a job needs to \
the worker with `files` (their names as listed): {worker} reads PDFs and documents, sees pictures, and works with \
videos using its tools. Say what you're sending where.
- While work runs: check_work when the owner asks how it's going; add_to_work for a change or follow-up to running \
work ("also check tomorrow"); stop_work when they say stop.
- When a report arrives, tell the owner the result briefly in your own words and offer more if there's more. If a \
worker asks a question, ask the owner, then pass the answer on with add_to_work (or a new job if it finished).

Rules:
- Never make up facts: no invented meetings, times, numbers or results. If you don't know, use a tool or ask a \
worker.
- Never approve anything for the owner and never ask a worker to approve or "always allow" anything: approvals are \
the owner's, on screen or by saying yes.
{context}"""

BOTS_PROMPT = """
- The owner's bots, each a worker of its own (ask_bot), for when the job fits one or the owner names it: {roster}."""

FILES_PROMPT = "The owner sent these in this chat (newest first): {files}. "
NO_FILES = "The owner hasn't sent any in this chat. "

FILES_PARAM = {"type": "array", "items": {"type": "string"},
               "description": "optional: names of files the owner sent in this chat, as listed, for the worker"}

ASK_HERMES = {"type": "function", "function": {
    "name": "ask_hermes",
    "description": "Give your main worker (the full agent) a job; it runs in the background and reports back to you. "
                   "Use for anything beyond to-dos and the agenda. Write a self-contained brief (see the rules).",
    "parameters": {"type": "object", "properties": {"brief": {"type": "string"}, "files": FILES_PARAM},
                   "required": ["brief"]}}}

WORK_TOOLS = [
    {"type": "function", "function": {
        "name": "check_work", "description": "How the jobs you gave workers are going: who, what, how long, what they're doing.",
        "parameters": {"type": "object", "properties": {}}}},
    {"type": "function", "function": {
        "name": "add_to_work",
        "description": "Add a note to a worker's running job (a change, a follow-up, the owner's answer to its question).",
        "parameters": {"type": "object", "properties": {
            "text": {"type": "string"},
            "worker": {"type": "string", "description": "optional: which worker; the latest job otherwise"}},
            "required": ["text"]}}},
    {"type": "function", "function": {
        "name": "stop_work", "description": "Stop a worker's running job when the owner says so.",
        "parameters": {"type": "object", "properties": {
            "worker": {"type": "string", "description": "optional: which worker; the latest job otherwise"}}}}},
]

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
    ASK_HERMES,
] + WORK_TOOLS

ASK_BOT = {"type": "function", "function": {
    "name": "ask_bot",
    "description": "Give one of the owner's bots (a worker of its own, Hermes Desktop's Bots) a job in its own chat; it "
                   "reports back to you. When the job fits the bot or the owner names it.",
    "parameters": {"type": "object", "properties": {
        "bot": {"type": "string", "description": "the bot's name, as listed"},
        "message": {"type": "string", "description": "the brief, self-contained"}, "files": FILES_PARAM},
        "required": ["bot", "message"]}}}



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
    def __init__(self, api_key: str | None, chat, *, voice: str | None = None, name: str = "Hermes",
                 transcriber: str = TRANSCRIBERS[0], transport: httpx.AsyncBaseTransport | None = None,
                 worker: str | None = None, persona: str = "", gemini_key: str | None = None,
                 gemini_transport: httpx.AsyncBaseTransport | None = None, brain: str | None = None,
                 speech: str | None = None):
        if transcriber not in TRANSCRIBERS:
            raise ValueError(f"talk transcriber must be one of {', '.join(TRANSCRIBERS)}")
        self.transcriber = transcriber
        self.models = Models(api_key, gemini_key, openrouter=transport, gemini=gemini_transport)
        self.chat = chat  # ChatService: to-dos, the calendar, sending briefs, broadcasting
        # Tally's models: what the owner picked on a device outlives the config (agents.json talk_brain/talk_speech)
        self.brain_id = chat.store.setting("talk.brain") or brain or DEFAULT_BRAIN
        speech_set = chat.store.setting("talk.speech")
        self.speech_id = (speech_set if speech_set is not None else speech) or None  # None: the brain's own voice
        split_id(self.brain_id)
        if self.speech_id:
            split_id(self.speech_id)
        chosen = chat.store.setting("talk.voice")
        voices = self._voices()
        self.voice = chosen or voice or (voices[0] if voices else "")
        if voices and self.voice not in voices:
            self.voice = voices[0]
        self.name = name  # who the owner talks to: the assistant's own name ("Tally"), agents.json talk_name
        self.worker = worker or name  # the main worker, the agent ("Hermes")
        self.persona = persona.strip()
        self.work: dict[str, dict] = {}  # turn id of a job given to a worker -> {worker, conv, talk, brief, at}
        self.active: dict[str, float] = {}  # conversation_id -> when it was last talked in
        self.memory: dict[str, list[dict]] = {}  # conversation_id -> recent exchanges, as text
        self._tasks: set[asyncio.Task] = set()
        self._holds: dict[str, _Hold] = {}  # talk_id -> an early turn not yet committed
        self._fillers: dict[str, list[str]] = {}  # "<voice model>|<voice>" -> FILLER as PCM chunks (base64), made once

    async def close(self) -> None:
        for task in list(self._tasks):
            task.cancel()
        await self.models.close()

    # Tally's models (§9)

    @property
    def brain(self):
        return self.models.brain(self.brain_id)

    @property
    def speaker(self):
        """Tally's voice model, or None when the brain speaks itself."""
        if self.speech_id:
            return self.models.voice(self.speech_id)
        if self.brain.speaks:
            return None
        raise TalkError(m.AGENT_UNAVAILABLE, "Tally's model can't speak: pick a voice for her")

    def _voices(self) -> tuple[str, ...]:
        if self.speech_id:
            return self.models.voice(self.speech_id).voices
        return OPENAI_VOICES if self.brain.speaks else ()

    def _filler_key(self, voice: str) -> str:
        return f"{self.speech_id or self.brain_id}|{voice}"

    async def setup(self) -> dict:
        """talk.setup → Tally's models, voice and the choices the keys allow."""
        found = await self.models.choices()
        return {"brain": self.brain_id, "speech": self.speech_id, "voice": self.voice,
                "brains": [{"id": c.id, "label": c.label, "hears": c.hears, "speaks": c.speaks} for c in found["brains"]],
                "speeches": [{"id": c.id, "label": c.label} for c in found["voices"]],
                "voices": list(self._voices())}

    async def configure(self, p: dict) -> dict:
        """talk.configure {brain?, speech?, voice?}: Tally's main model and her voice, for every device."""
        found = await self.models.choices()
        brains = {c.id: c for c in found["brains"]}
        speeches = {c.id for c in found["voices"]}
        brain, speech = p.get("brain", self.brain_id), p.get("speech", self.speech_id)
        if brain not in brains and brain != self.brain_id:
            raise TalkError(m.INVALID_PARAMS, "brain must be one of the models your keys allow (talk.setup)")
        if speech is not None and speech != "" and speech not in speeches and speech != self.speech_id:
            raise TalkError(m.INVALID_PARAMS, "speech must be one of the voice models your keys allow, or null")
        speech = speech or None
        hears = brains[brain].hears if brain in brains else None
        chosen = self.models.brain(brain, hears)
        if speech is None and not chosen.speaks:
            raise TalkError(m.INVALID_PARAMS, "That model can't speak: pick a voice model for Tally too")
        self.brain_id, self.speech_id = brain, speech
        self.chat.store.set_setting("talk.brain", brain)
        self.chat.store.set_setting("talk.speech", speech or "")
        voices = self._voices()
        wanted = p.get("voice", self.voice)
        if voices and wanted not in voices:
            if "voice" in p:
                raise TalkError(m.INVALID_PARAMS, f"voice must be one of {', '.join(voices)}")
            wanted = voices[0]
        self.voice = wanted
        self.chat.store.set_setting("talk.voice", wanted)
        if self._filler_key(wanted) not in self._fillers:
            self._spawn(self._make_filler(wanted))
        return await self.setup()

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
        if self._filler_key(self.voice) not in self._fillers:
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
        if voice is not None and voice not in self._voices():
            raise TalkError(m.INVALID_PARAMS, f"voice must be one of {', '.join(self._voices())}")
        text = SAMPLE if voice is not None and p.get("text") is None else p.get("text")
        if not (isinstance(text, str) and text.strip() and len(text) <= MAX_SAY):
            raise TalkError(m.INVALID_PARAMS, f"text must be 1 to {MAX_SAY} characters")
        conv = self._conv(p.get("conversation_id"))
        talk_id = "tk_" + secrets.token_hex(8)
        if self.speech_id:  # a voice model says it as it is: no model in between
            self._spawn(self._say_line(talk_id, conv, text.strip(), voice or self.voice))
            return {"talk_id": talk_id}
        user = {"role": "user", "content": f"Say exactly this, word for word, and nothing else:\n{text.strip()}"}
        self._spawn(self._run(talk_id, conv, user, tools=False, remember=False, voice=voice))
        return {"talk_id": talk_id}

    async def _say_line(self, talk_id: str, conv: str | None, text: str, voice: str) -> None:
        where = {"conversation_id": conv} if conv else {}
        error = None
        try:
            seq = 0
            await self.chat.broadcast(m.notification("talk.text", {"talk_id": talk_id, "text": text, **where}))
            async for chunk in self.speaker.speak(text, voice):
                await self.chat.broadcast(m.notification("talk.audio", {"talk_id": talk_id, "seq": seq, "data": chunk, **where}))
                seq += 1
        except (ModelError, TalkError) as exc:
            error = str(exc)
        done = {"talk_id": talk_id, "text": text, **where}
        if error:
            done["error"] = error
        await self.chat.broadcast(m.notification("talk.done", done))

    def voices(self) -> dict:
        """talk.voices → {voice, voices: [{id, label}]}: the talker's voices, and the one it uses."""
        return {"voice": self.voice, "voices": [{"id": v, "label": VOICE_LABELS.get(v, v)} for v in self._voices()]}

    def set_voice(self, p: dict) -> dict:
        """talk.voice {voice} → {voice}: the talker speaks in this voice from now on, on every device."""
        voice = p.get("voice")
        if voice not in self._voices():
            raise TalkError(m.INVALID_PARAMS, f"voice must be one of {', '.join(self._voices())}")
        self.voice = voice
        self.chat.store.set_setting("talk.voice", voice)
        if self._filler_key(voice) not in self._fillers:
            self._spawn(self._make_filler(voice))
        return {"voice": voice}

    def end(self, p: dict) -> dict:
        """talk.end {conversation_id}: stop speaking the agent's replies in it."""
        conv = p.get("conversation_id")
        if isinstance(conv, str):
            self.active.pop(conv, None)
        return {}

    # the agent's replies

    def on_reply(self, conversation_id: str, text: str, status: str, turn_id: str | None = None) -> None:
        """A turn finished: a worker's report on a job the talker gave it is said where the talker is; otherwise,
        in a conversation being talked in, the agent's reply is said."""
        word = "finished" if status == "completed" else f"stopped ({status})"
        job = self.work.pop(turn_id, None) if turn_id else None
        if job is not None:
            if time.time() - job["at"] > 6 * 3600 or not (text.strip() or status != "completed"):
                return
            still = [w["worker"] for w in self.work.values() if w["talk"] == job["talk"]]
            note = f" Still working: {', '.join(still)}." if still else ""
            user = {"role": "user", "content": f"[Report from your worker {job['worker']}, {word}, on: "
                                               f"{job['brief'][:300]}.{note} Tell the owner briefly in your own "
                                               f"words:]\n{(text.strip() or '(no report)')[:4000]}"}
            talked = time.time() - self.active.get(job["talk"] or "", 0) <= ACTIVE_S
            tally = getattr(self.chat, "tally", None)
            if tally is not None and self._is_tally(job["talk"]) and not talked:
                self._spawn(tally.write(job["talk"], user))
                return
            self._spawn(self._run("tk_" + secrets.token_hex(8), job["talk"], user, tools=True, unprompted=True,
                                  keep=True))
            return
        at = self.active.get(conversation_id)
        if at is None or time.time() - at > ACTIVE_S or not text.strip():
            return
        talk_id = "tk_" + secrets.token_hex(8)
        user = {"role": "user", "content": f"[{self.worker} {word}. Its reply, for you to tell the owner briefly in "
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
        roster = self._bots()
        bots = BOTS_PROMPT.format(roster="; ".join(
            b["name"] + (f" ({b.get('description') or b.get('role')})" if b.get("description") or b.get("role") else "")
            for b in roster)) if roster else ""
        files = self._files(conv)
        listed = FILES_PROMPT.format(files="; ".join(f"{f['name']} ({f['kind']}, {f['size']})" for f in files)) \
            if files else NO_FILES
        persona = self.persona + " " if self.persona else ""
        return {"role": "system", "content": SYSTEM.format(name=self.name, worker=self.worker, persona=persona, now=now,
                                                           context=context, bots=bots, files=listed)}

    def _files(self, conv: str | None) -> list[dict]:
        """Files the owner sent in this chat, newest first: they're in the agent's inbox, under the chat's id (§10)."""
        inbox = self.chat.inboxes.get(self.chat.default_agent) if conv else None
        folder = inbox / conv if inbox is not None else None
        if folder is None or not folder.is_dir():
            return []
        out = []
        for path in sorted(folder.iterdir(), key=lambda x: x.stat().st_mtime, reverse=True)[:30]:
            if not path.is_file():
                continue
            name = path.name.split("-", 1)[1] if "-" in path.name else path.name  # "<blob id>-<name>"
            mime = mimetypes.guess_type(name)[0] or "application/octet-stream"
            kind = ("picture" if mime.startswith("image/") else "video" if mime.startswith("video/") else
                    "audio" if mime.startswith("audio/") else "PDF" if mime == "application/pdf" else "file")
            size = path.stat().st_size
            out.append({"name": name, "kind": kind, "mime": mime, "bytes": size, "path": str(path),
                        "size": f"{max(1, round(size / 1024))} KB" if size < 1024 * 1024 else f"{size / 1048576:.1f} MB"})
        return out

    def _file_lines(self, conv: str | None, names) -> tuple[list[str], list[str]]:
        """The workers' lines for the files named (as _files lists them), and the names not found."""
        if not isinstance(names, list):
            return [], []
        files, lines, missing = self._files(conv), [], []
        for wanted in names:
            w = str(wanted).strip().lower()
            f = next((f for f in files if f["name"].lower() == w), None) or next(
                (f for f in files if w and w in f["name"].lower()), None)
            if f is None:
                missing.append(str(wanted))
            else:
                lines.append(f"Attached file: {f['path']} ({f['mime']}, {f['bytes']} bytes)")
        return lines, missing

    def _bots(self) -> list[dict]:
        bots = getattr(self.chat, "bots", None)
        return bots.roster() if bots is not None and bots.backend.connected else []

    async def _run(self, talk_id: str, conv: str | None, user: dict, *, tools: bool, remember: bool = True,
                   unprompted: bool = False, hearing: asyncio.Task | None = None, system: dict | None = None,
                   voice: str | None = None, hold: _Hold | None = None, keep: bool = False) -> None:
        try:
            await self._talk(talk_id, conv, user, tools=tools, remember=remember, unprompted=unprompted,
                             hearing=hearing, system=system, voice=voice or self.voice, hold=hold, keep=keep)
        except _Dropped:
            log.info("talk %s: early turn never committed, dropped", talk_id)
            if hearing is not None:
                hearing.cancel()
        finally:
            if hold is not None:
                self._holds.pop(talk_id, None)

    async def _talk(self, talk_id: str, conv: str | None, user: dict, *, tools: bool, remember: bool, unprompted: bool,
                    hearing: asyncio.Task | None, system: dict | None, voice: str, hold: _Hold | None,
                    keep: bool = False) -> None:
        said_all, error = [], None
        try:
            if hearing is not None and not self.brain.hears:  # a model that can't hear gets the words written down
                conv_in, words = await asyncio.shield(hearing)
                conv = conv or conv_in
                user = {"role": "user", "content": words or "(the owner said something that couldn't be made out)"}
            messages = [system or self._system(conv), *self.memory.get(conv or "", []), user]
            for _ in range(MAX_ROUNDS):
                said, calls, raw = await self._stream(talk_id, conv, messages, tools, voice, hold)
                if said:
                    said_all.append(said)
                if not calls:
                    break
                if not said:  # going quiet to use a tool: say so
                    for i, chunk in enumerate(self._fillers.get(self._filler_key(voice), [])):
                        await self._out(hold, m.notification("talk.audio", {"talk_id": talk_id, "seq": i, "data": chunk,
                                                                            **({"conversation_id": conv} if conv else {})}))
                await self._go_ahead(hold)
                if hearing is not None and any(c["function"]["name"] in ("ask_hermes", "ask_bot") for c in calls):
                    conv = (await hearing)[0]  # the owner's words go in the chat before the brief
                messages.append({"role": "assistant", "content": said or None, "tool_calls": calls,
                                 **({"_gemini": raw} if raw else {})})
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
        elif keep and conv and said:
            await self._keep(conv, "assistant", said)  # retelling a worker's report: it's the chat's message
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
        if self.models.or_http is None:
            return None
        try:
            resp = await self.models.or_http.post("/audio/transcriptions", timeout=httpx.Timeout(20, connect=5), json={
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
        """Tally's own model's words for what was said ("" if it couldn't)."""
        a = sound["input_audio"]
        return await self.brain.transcribe(a["data"], a.get("format", "wav"), HEAR) or ""

    async def _new_chat(self, words: str) -> str | None:
        tally = getattr(self.chat, "tally", None)
        if tally is not None:  # Talk is Tally: what's said starts a Tally conversation, typed and spoken in one place
            conv = await self.chat._new_conversation("tally", tally, words)
            self.active[conv.id] = time.time()
            return conv.id
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
        chunks: list[str] = []
        try:
            speaker = self.speaker
            if speaker is not None:
                async for chunk in speaker.speak(FILLER, voice):
                    chunks.append(chunk)
            else:
                said = [{"role": "user", "content": f"Say exactly this, casually, and nothing else: {FILLER}"}]
                async for kind, value in self.brain.stream(said, None, voice=voice):
                    if kind == "audio":
                        chunks.append(value)
        except (ModelError, TalkError) as exc:
            log.info("no filler in %s: %s", voice, exc)
            return
        if chunks:
            self._fillers[self._filler_key(voice)] = chunks

    async def _stream(self, talk_id: str, conv: str | None, messages: list[dict], tools: bool,
                      voice: str, hold: _Hold | None = None) -> tuple[str, list[dict], list]:
        """One model call: its words and voice go to the devices as they come (a voice model says each sentence as
        soon as it's whole); returns what it said, its tool calls and the provider's own parts for them."""
        where = {"conversation_id": conv} if conv else {}  # devices know an unprompted reply by its conversation
        speaker = self.speaker
        said, pending, calls, raw = "", "", [], []
        seq = 0
        lines: asyncio.Queue = asyncio.Queue()

        async def speak_lines() -> None:
            nonlocal seq
            while (line := await lines.get()) is not None:
                await self._out(hold, m.notification("talk.text", {"talk_id": talk_id, "text": line + " ", **where}))
                async for chunk in speaker.speak(line, voice):
                    await self._out(hold, m.notification("talk.audio", {"talk_id": talk_id, "seq": seq, "data": chunk, **where}))
                    seq += 1

        speaking = asyncio.create_task(speak_lines()) if speaker is not None else None
        try:
            async for kind, value in self.brain.stream(messages, (TOOLS + ([ASK_BOT] if self._bots() else [])) if tools else None,
                                                       voice=voice):
                if kind == "text":
                    said += value
                    if speaker is None:
                        await self._out(hold, m.notification("talk.text", {"talk_id": talk_id, "text": value, **where}))
                    else:
                        whole, pending = sentences(pending + value)
                        for line in whole:
                            lines.put_nowait(line)
                elif kind == "audio":
                    await self._out(hold, m.notification("talk.audio", {"talk_id": talk_id, "seq": seq, "data": value, **where}))
                    seq += 1
                elif kind == "calls":
                    calls = value
                elif kind == "raw":
                    raw = value
            if speaker is not None:
                for line in sentences(pending, final=True)[0]:
                    lines.put_nowait(line)
        except ModelError as exc:
            raise TalkError(m.AGENT_UNAVAILABLE, str(exc)) from None
        finally:
            if speaking is not None:
                lines.put_nowait(None)
                try:
                    await speaking
                except ModelError as exc:
                    log.warning("talk %s: Tally's voice failed: %s", talk_id, exc)
        return said.strip(), calls, raw

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
            if name == "ask_bot":
                return await self._ask_bot(args, conv), conv
            if name == "check_work":
                return self._check_work(conv), conv
            if name == "add_to_work":
                return await self._add_to_work(args, conv), conv
            if name == "stop_work":
                return await self._stop_work(args, conv), conv
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
        lines, missing = self._file_lines(conv, args.get("files"))
        if missing:
            return f"Error: no file called {', '.join(missing)} in this chat. {self._listed(conv)}", conv
        params = {"text": brief}
        talk = None
        if conv and self._is_tally(conv):
            # a Tally conversation: the job goes to its linked conversation with the agent, the report comes back here
            talk = conv
            linked = self.chat.store.setting(f"tally.worker.{conv}")
            if linked and self.chat.store.get(linked) is not None:
                params["conversation_id"] = linked
            else:
                params["agent_id"] = self.chat.default_agent
        elif conv:
            params["conversation_id"] = conv
        result, turn = await self.chat.send(params, worker=self.worker, order=work_order(self.name, brief, lines))
        if turn is not None:
            self.chat.start(turn)
        if talk is not None:
            self.chat.store.set_setting(f"tally.worker.{talk}", result["conversation_id"])
            self._job(result, self.worker, result["conversation_id"], brief, talk=talk)
            return f"Gave {self.worker} the job; its report comes to you when it's done.", conv
        conv = result["conversation_id"]
        self.active[conv] = time.time()
        self._job(result, self.worker, conv, brief)
        sent = f" with {len(lines)} file{'s' if len(lines) != 1 else ''}" if lines else ""
        return f"Gave {self.worker} the job{sent}; its report comes to you when it's done.", conv

    async def _ask_bot(self, args: dict, conv: str | None) -> str:
        wanted = str(args.get("bot") or "").strip().lower().lstrip("@")
        message = str(args.get("message") or "").strip()
        roster = self._bots()
        bot = next((b for b in roster if wanted in (b["name"].lower(), b["profile"].lower())), None) or next(
            (b for b in roster if wanted and (wanted in b["name"].lower() or wanted in b["profile"].lower())), None)
        if bot is None:
            names = ", ".join(b["name"] for b in roster) or "none"
            return f"Error: no bot called {args.get('bot')!r}. The bots are: {names}."
        if not message:
            return "Error: the message is empty."
        lines, missing = self._file_lines(conv, args.get("files"))
        if missing:
            return f"Error: no file called {', '.join(missing)} in this chat. {self._listed(conv)}"
        result, turn = await self.chat.send({"agent_id": bot["id"], "text": message}, worker=bot["name"],
                                            order=work_order(self.name, message, lines))
        if turn is not None:
            self.chat.start(turn)
        self._job(result, bot["name"], result["conversation_id"], message, talk=conv)
        return f"Gave {bot['name']} the job; its report comes to you when it's done."

    def _is_tally(self, conv: str | None) -> bool:
        c = self.chat.store.get(conv) if conv else None
        return c is not None and c.agent_id == "tally"

    def _listed(self, conv: str | None) -> str:
        files = self._files(conv)
        return f"The files are: {', '.join(f['name'] for f in files)}." if files else "The owner sent no files here."

    def _job(self, result: dict, worker: str, conv: str, brief: str, talk: str | None = None) -> None:
        turn_id = result.get("turn_id")
        if isinstance(turn_id, str):
            self.work[turn_id] = {"worker": worker, "conv": conv, "talk": talk if talk is not None else conv,
                                  "brief": brief, "at": time.time()}

    def _jobs(self, talk: str | None, worker: str | None) -> list[tuple[str, dict]]:
        """The jobs still running that the talker gave from this conversation, newest first (by worker if named)."""
        w = (worker or "").strip().lower()
        jobs = [(tid, j) for tid, j in self.work.items() if j["talk"] == talk or talk is None]
        if w:
            jobs = [(tid, j) for tid, j in jobs if w in j["worker"].lower()]
        return sorted(jobs, key=lambda x: x[1]["at"], reverse=True)

    def _check_work(self, conv: str | None) -> str:
        out = []
        for tid, job in self._jobs(conv, None):
            turn = self.chat._turns.get(tid)
            item = {"worker": job["worker"], "job": job["brief"][:200], "minutes": round((time.time() - job["at"]) / 60, 1),
                    "status": turn.status if turn is not None else "unknown"}
            if turn is not None:
                if turn.tools:
                    item["doing"] = [t.get("preview") or t["name"] for t in turn.tools[-3:]]
                if turn.waiting_for_approval:
                    item["waiting_for"] = "the owner's approval"
            out.append(item)
        return json.dumps({"running": out} if out else {"running": [], "note": "No jobs are running."})

    async def _add_to_work(self, args: dict, conv: str | None) -> str:
        text = str(args.get("text") or "").strip()
        jobs = self._jobs(conv, args.get("worker"))
        if not text:
            return "Error: the note is empty."
        if not jobs:
            return "Error: no job is running for that; give a new job instead."
        tid, job = jobs[0]
        try:
            await self.chat.steer({"turn_id": tid, "text": f"From {self.name}: {text}"})
        except Exception as exc:  # noqa: BLE001 (the talker hears why)
            return f"Error: couldn't add it ({getattr(exc, 'message', exc)}); give a new job instead."
        return f"Added to {job['worker']}'s job."

    async def _stop_work(self, args: dict, conv: str | None) -> str:
        jobs = self._jobs(conv, args.get("worker"))
        if not jobs:
            return "Error: no job is running for that."
        tid, job = jobs[0]
        try:
            await self.chat.cancel({"turn_id": tid})
        except Exception as exc:  # noqa: BLE001
            return f"Error: couldn't stop it ({getattr(exc, 'message', exc)})."
        return f"Stopped {job['worker']}'s job."


TALK_METHODS = frozenset({"talk.turn", "talk.say", "talk.end", "talk.voices", "talk.voice", "talk.commit", "talk.cancel",
                          "talk.setup", "talk.configure"})
