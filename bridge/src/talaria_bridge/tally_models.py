"""Tally's models, any of them (§9 "Tally's models"): a brain that hears or reads, thinks and uses tools, and a voice.

A model is named `provider:model`, e.g. `gemini:gemini-3.5-flash` (the owner's Gemini key) or
`openrouter:openai/gpt-audio-mini` (the voice OpenRouter key, behind the owner's guardrail). Brains take the
conversation in OpenAI's chat shape (system/user/assistant/tool messages, `input_audio` parts, `tool_calls`) and
yield what they say as it comes; voices turn a sentence into 24 kHz 16-bit mono PCM, streamed. A brain that speaks
itself (GPT Audio) needs no voice. New providers are new adapters here; nothing else changes."""

from __future__ import annotations

import base64
import contextlib
import json
import logging
import time
from collections.abc import AsyncIterator
from dataclasses import dataclass

import httpx

log = logging.getLogger("talaria.tally")

OPENROUTER = "https://openrouter.ai/api/v1"
GEMINI = "https://generativelanguage.googleapis.com/v1beta"
PCM_RATE = 24_000
LIST_TTL_S = 600
CHUNK = PCM_RATE * 2 // 2  # half a second of PCM per talk.audio when a voice answers in one piece

# Gemini's prebuilt voices (the same for every Gemini TTS model)
GEMINI_VOICES = ("Kore", "Puck", "Charon", "Fenrir", "Leda", "Orus", "Aoede", "Zephyr", "Callirrhoe", "Autonoe",
                 "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi",
                 "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird",
                 "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat")
OPENAI_VOICES = ("shimmer", "marin", "cedar", "coral", "sage", "alloy", "ballad", "verse", "ash", "echo", "fable",
                 "nova", "onyx")
ELEVENLABS_VOICES = ("Sarah", "Laura", "Alice", "Matilda", "Jessica", "Lily", "River", "Roger", "George", "Brian",
                     "Daniel", "Liam", "Will", "Chris", "Eric", "Callum", "Charlie", "Bill")
SPEAKING_BRAINS = ("openai/gpt-audio",)  # OpenRouter models that answer in their own voice (prefix match)


class ModelError(Exception):
    """A model refused or couldn't be reached; the words are for the owner."""


@dataclass(frozen=True)
class Choice:
    id: str  # provider:model
    label: str
    hears: bool = False  # takes the owner's voice directly
    speaks: bool = False  # answers in its own voice


def split_id(model_id: str) -> tuple[str, str]:
    provider, sep, model = str(model_id).partition(":")
    if not sep or provider not in ("gemini", "openrouter") or not model:
        raise ValueError(f"a model is provider:model, provider gemini or openrouter: {model_id!r}")
    return provider, model


def _label(model: str) -> str:
    name = model.split("/")[-1].replace("-", " ").replace("gpt", "GPT").replace("tts", "TTS")
    return " ".join(w[:1].upper() + w[1:] if w.islower() else w for w in name.split())


# brains

class Brain:
    id: str
    hears = False
    speaks = False

    async def stream(self, messages: list[dict], tools: list[dict] | None, *, voice: str | None = None,
                     speak: bool = True) -> AsyncIterator[tuple[str, object]]:
        """("text", words) and, for a speaking brain, ("audio", base64 PCM) as they come; then ("calls", [tool
        calls in OpenAI's shape]) and ("raw", what the provider needs back with them) when it wants tools."""
        raise NotImplementedError
        yield  # pragma: no cover

    async def transcribe(self, audio: str, fmt: str, prompt: str) -> str | None:
        """What was said in [audio] (base64 WAV or MP3), or None if this brain can't tell."""
        return None


class OpenRouterBrain(Brain):
    def __init__(self, http: httpx.AsyncClient, model: str, hears: bool):
        self.http, self.model = http, model
        self.id = f"openrouter:{model}"
        self.speaks = model.startswith(SPEAKING_BRAINS)
        self.hears = hears or self.speaks

    async def stream(self, messages, tools, *, voice=None, speak=True):
        body = {"model": self.model, "stream": True, "messages": [_plain(x) for x in messages]}
        if self.speaks and speak:
            body.update(modalities=["text", "audio"], audio={"voice": voice or OPENAI_VOICES[0], "format": "pcm16"})
        if tools:
            body["tools"] = tools
        calls: dict[int, dict] = {}
        try:
            async with self.http.stream("POST", "/chat/completions", json=body) as resp:
                if resp.status_code != 200:
                    text = (await resp.aread())[:300].decode(errors="replace")
                    log.warning("%s refused (%s): %s", self.id, resp.status_code, text)
                    raise ModelError(f"Tally's model refused ({resp.status_code})")
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
                            yield "audio", audio["data"]
                        if audio.get("transcript"):
                            yield "text", audio["transcript"]
                        if isinstance(delta.get("content"), str) and delta["content"]:
                            yield "text", delta["content"]
                        for tc in delta.get("tool_calls") or []:
                            entry = calls.setdefault(tc.get("index", 0), {"id": "", "type": "function",
                                                                          "function": {"name": "", "arguments": ""}})
                            entry["id"] = tc.get("id") or entry["id"]
                            fn = tc.get("function") or {}
                            entry["function"]["name"] += fn.get("name") or ""
                            entry["function"]["arguments"] += fn.get("arguments") or ""
        except httpx.HTTPError as exc:
            raise ModelError(f"Tally's model can't be reached ({type(exc).__name__})") from None
        if calls:
            out = [c for _, c in sorted(calls.items())]
            for i, c in enumerate(out):
                c["id"] = c["id"] or f"call_{i}"
            yield "calls", out

    async def transcribe(self, audio, fmt, prompt):
        if not self.hears:
            return None
        try:
            resp = await self.http.post("/chat/completions", timeout=httpx.Timeout(30, connect=5), json={
                "model": self.model, "modalities": ["text"], "messages": [
                    {"role": "system", "content": prompt},
                    {"role": "user", "content": [{"type": "text", "text": "The recording:"},
                                                 {"type": "input_audio", "input_audio": {"data": audio, "format": fmt}}]}]})
            if resp.status_code == 200:
                return str(resp.json()["choices"][0]["message"].get("content") or "").strip()
        except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError) as exc:
            log.warning("%s couldn't write down the words: %s", self.id, type(exc).__name__)
        return None


def _plain(message: dict) -> dict:
    """A message without our own keys (provider-specific parts kept for another provider)."""
    return {k: v for k, v in message.items() if not k.startswith("_")}


class GeminiBrain(Brain):
    hears = True

    def __init__(self, http: httpx.AsyncClient, model: str):
        self.http, self.model = http, model
        self.id = f"gemini:{model}"
        self._thinking: dict | None | bool = False  # not yet known: the lightest level this model takes

    async def stream(self, messages, tools, *, voice=None, speak=True):
        body = to_gemini(messages, tools)
        for attempt in (0, 1, 2):
            level = self._thinking if self._thinking is not False else {"thinkingLevel": "minimal"}
            if level:
                body["generationConfig"] = {"thinkingConfig": level}
            else:
                body.pop("generationConfig", None)
            raw: list[dict] = []
            calls: list[dict] = []
            try:
                async with self.http.stream("POST", f"/models/{self.model}:streamGenerateContent?alt=sse", json=body) as resp:
                    if resp.status_code != 200:
                        text = (await resp.aread())[:400].decode(errors="replace")
                        if resp.status_code == 400 and "hinking" in text and attempt < 2:
                            # this model doesn't take that level: the next lighter one it does (or its default)
                            self._thinking = {"thinkingLevel": "low"} if level == {"thinkingLevel": "minimal"} else None
                            continue
                        log.warning("%s refused (%s): %s", self.id, resp.status_code, text)
                        busy = resp.status_code in (429, 503)
                        raise ModelError("Tally's model is busy right now, try again in a moment" if busy
                                         else f"Tally's model refused ({resp.status_code})")
                    self._thinking = level
                    async for line in resp.aiter_lines():
                        if not line.startswith("data:"):
                            continue
                        try:
                            d = json.loads(line[5:])
                        except ValueError:
                            continue
                        for cand in d.get("candidates") or []:
                            for part in (cand.get("content") or {}).get("parts") or []:
                                if part.get("thought"):
                                    raw.append(part)
                                    continue
                                if "functionCall" in part:
                                    raw.append(part)
                                    fc = part["functionCall"]
                                    calls.append({"id": fc.get("id") or f"call_{len(calls)}", "type": "function",
                                                  "function": {"name": fc.get("name", ""),
                                                               "arguments": json.dumps(fc.get("args") or {})}})
                                elif isinstance(part.get("text"), str):
                                    raw.append(part)
                                    if part["text"]:
                                        yield "text", part["text"]
            except httpx.HTTPError as exc:
                raise ModelError(f"Tally's model can't be reached ({type(exc).__name__})") from None
            if calls:
                yield "raw", raw
                yield "calls", calls
            return

    async def transcribe(self, audio, fmt, prompt):
        try:
            resp = await self.http.post(f"/models/{self.model}:generateContent", timeout=httpx.Timeout(30, connect=5), json={
                "systemInstruction": {"parts": [{"text": prompt}]},
                "contents": [{"role": "user", "parts": [{"inline_data": {"mime_type": f"audio/{fmt}", "data": audio}}]}]})
            if resp.status_code == 200:
                parts = resp.json()["candidates"][0]["content"]["parts"]
                return "".join(p.get("text", "") for p in parts if not p.get("thought")).strip()
            log.warning("%s couldn't write down the words (%s)", self.id, resp.status_code)
        except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError) as exc:
            log.warning("%s couldn't write down the words: %s", self.id, type(exc).__name__)
        return None


_SCHEMA_KEYS = ("type", "description", "properties", "items", "enum", "required")


def _schema(s: dict) -> dict:
    """An OpenAI tool schema as Gemini takes it (its subset of OpenAPI)."""
    out = {k: v for k, v in s.items() if k in _SCHEMA_KEYS}
    if "properties" in out:
        out["properties"] = {k: _schema(v) for k, v in out["properties"].items()}
    if "items" in out:
        out["items"] = _schema(out["items"])
    return out


def to_gemini(messages: list[dict], tools: list[dict] | None) -> dict:
    """The conversation in Gemini's shape: system instruction, user/model turns, function calls and answers."""
    body: dict = {"contents": []}
    names: dict[str, str] = {}
    for msg in messages:
        role = msg.get("role")
        if role == "system":
            body["systemInstruction"] = {"parts": [{"text": msg["content"]}]}
            continue
        if role == "tool":
            part = {"functionResponse": {"name": names.get(msg.get("tool_call_id"), "tool"),
                                         "response": {"result": msg.get("content", "")}}}
            last = body["contents"][-1] if body["contents"] else None
            if last and last["role"] == "user" and all("functionResponse" in p for p in last["parts"]):
                last["parts"].append(part)
            else:
                body["contents"].append({"role": "user", "parts": [part]})
            continue
        if role == "assistant":
            for c in msg.get("tool_calls") or []:
                names[c["id"]] = c["function"]["name"]
            parts = msg.get("_gemini")
            if not parts:
                parts = [{"text": msg["content"]}] if msg.get("content") else []
                for c in msg.get("tool_calls") or []:
                    with contextlib.suppress(ValueError):
                        parts.append({"functionCall": {"name": c["function"]["name"],
                                                       "args": json.loads(c["function"]["arguments"] or "{}")}})
            if parts:
                body["contents"].append({"role": "model", "parts": parts})
            continue
        content = msg.get("content")
        parts = []
        if isinstance(content, str):
            parts.append({"text": content})
        else:
            for p in content or []:
                if p.get("type") == "text":
                    parts.append({"text": p["text"]})
                elif p.get("type") == "input_audio":
                    a = p["input_audio"]
                    parts.append({"inline_data": {"mime_type": f"audio/{a.get('format', 'wav')}", "data": a["data"]}})
        if parts:
            body["contents"].append({"role": "user", "parts": parts})
    if tools:
        decls = []
        for t in tools:
            fn = t["function"]
            d = {"name": fn["name"], "description": fn.get("description", "")}
            params = fn.get("parameters") or {}
            if params.get("properties"):
                d["parameters"] = _schema(params)
            decls.append(d)
        body["tools"] = [{"functionDeclarations": decls}]
    return body


# voices

class Voice:
    id: str
    voices: tuple[str, ...] = ()

    async def speak(self, text: str, voice: str) -> AsyncIterator[str]:
        """[text] said in [voice]: base64 24 kHz 16-bit mono PCM pieces, in order."""
        raise NotImplementedError
        yield  # pragma: no cover


class GeminiVoice(Voice):
    voices = GEMINI_VOICES

    def __init__(self, http: httpx.AsyncClient, model: str):
        self.http, self.model = http, model
        self.id = f"gemini:{model}"

    async def speak(self, text, voice):
        body = {"contents": [{"role": "user", "parts": [{"text": text}]}],
                "generationConfig": {"responseModalities": ["AUDIO"], "speechConfig": {
                    "voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice}}}}}
        try:
            async with self.http.stream("POST", f"/models/{self.model}:streamGenerateContent?alt=sse", json=body) as resp:
                if resp.status_code != 200:
                    text = (await resp.aread())[:300].decode(errors="replace")
                    log.warning("%s refused (%s): %s", self.id, resp.status_code, text)
                    raise ModelError(f"Tally's voice refused ({resp.status_code})")
                async for line in resp.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    with contextlib.suppress(ValueError, KeyError, IndexError, TypeError):
                        for part in json.loads(line[5:])["candidates"][0]["content"].get("parts", []):
                            if "inlineData" in part:
                                yield part["inlineData"]["data"]
        except httpx.HTTPError as exc:
            raise ModelError(f"Tally's voice can't be reached ({type(exc).__name__})") from None


class OpenRouterVoice(Voice):
    def __init__(self, http: httpx.AsyncClient, model: str):
        self.http, self.model = http, model
        self.id = f"openrouter:{model}"
        self.voices = ELEVENLABS_VOICES if model.startswith("elevenlabs/") else \
            GEMINI_VOICES if model.startswith("google/") else OPENAI_VOICES if model.startswith("openai/") else ()

    async def speak(self, text, voice):
        try:
            resp = await self.http.post("/audio/speech", timeout=httpx.Timeout(30, connect=5), json={
                "model": self.model, "input": text, "response_format": "pcm", **({"voice": voice} if voice else {})})
        except httpx.HTTPError as exc:
            raise ModelError(f"Tally's voice can't be reached ({type(exc).__name__})") from None
        if resp.status_code != 200 or not resp.headers.get("content-type", "").startswith("audio/"):
            log.warning("%s refused (%s): %s", self.id, resp.status_code, resp.text[:300])
            raise ModelError(f"Tally's voice refused ({resp.status_code})")
        pcm = resp.content
        for i in range(0, len(pcm), CHUNK):
            yield base64.b64encode(pcm[i:i + CHUNK]).decode()


# what the keys allow

class Models:
    """The brains and voices the owner's keys allow, made on demand."""

    def __init__(self, openrouter_key: str | None, gemini_key: str | None, *,
                 openrouter: httpx.AsyncBaseTransport | None = None, gemini: httpx.AsyncBaseTransport | None = None):
        self.or_http = httpx.AsyncClient(base_url=OPENROUTER, transport=openrouter, timeout=httpx.Timeout(60, connect=5),
                                         headers={"Authorization": f"Bearer {openrouter_key}", "User-Agent": "talaria-bridge",
                                                  "X-Title": "Talaria"}) if openrouter_key else None
        self.g_http = httpx.AsyncClient(base_url=GEMINI, transport=gemini, timeout=httpx.Timeout(60, connect=5),
                                        headers={"x-goog-api-key": gemini_key}) if gemini_key else None
        self._lists: tuple[float, dict] | None = None
        self._brains: dict[str, Brain] = {}
        self._voices: dict[str, Voice] = {}

    async def close(self) -> None:
        for h in (self.or_http, self.g_http):
            if h is not None:
                await h.aclose()

    async def choices(self) -> dict:
        """{brains: [Choice], voices: [Choice]}: what the keys allow, cached for [LIST_TTL_S]."""
        if self._lists is not None and time.monotonic() - self._lists[0] < LIST_TTL_S:
            return self._lists[1]
        brains: list[Choice] = []
        voices: list[Choice] = []
        if self.g_http is not None:
            try:
                resp = await self.g_http.get("/models", params={"pageSize": 200}, timeout=httpx.Timeout(15, connect=5))
                for mdl in resp.json().get("models", []):
                    name = str(mdl.get("name", "")).removeprefix("models/")
                    methods = mdl.get("supportedGenerationMethods") or []
                    if "generateContent" not in methods or not name.startswith("gemini"):
                        continue
                    if name.endswith("-tts") or "-tts-" in name:
                        voices.append(Choice(f"gemini:{name}", mdl.get("displayName") or _label(name)))
                    elif not any(x in name for x in ("image", "embedding", "live", "native-audio", "robotics", "computer")):
                        brains.append(Choice(f"gemini:{name}", mdl.get("displayName") or _label(name), hears=True))
            except (httpx.HTTPError, ValueError, AttributeError) as exc:
                log.warning("Gemini's models unknown: %s", exc)
        if self.or_http is not None:
            try:
                allowed = (await self.or_http.get("/models/user", timeout=httpx.Timeout(15, connect=5))).json()["data"]
                for mdl in allowed:
                    arch = mdl.get("architecture") or {}
                    ins, outs = arch.get("input_modalities") or [], arch.get("output_modalities") or []
                    if "text" in outs:
                        speaks = "audio" in outs and str(mdl["id"]).startswith(SPEAKING_BRAINS)
                        brains.append(Choice(f"openrouter:{mdl['id']}", mdl.get("name") or _label(mdl["id"]),
                                             hears="audio" in ins, speaks=speaks))
                speech = (await self.or_http.get("/models", params={"output_modalities": "all"},
                                                 timeout=httpx.Timeout(15, connect=5))).json()["data"]
                ids = {mdl["id"] for mdl in allowed}
                for mdl in speech:  # speech-only models are hidden from /models/user: a test call tells the guardrail
                    if "speech" in ((mdl.get("architecture") or {}).get("output_modalities") or []) \
                            and (mdl["id"] in ids or await self._voice_allowed(mdl["id"])):
                        voices.append(Choice(f"openrouter:{mdl['id']}", mdl.get("name") or _label(mdl["id"])))
            except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
                log.warning("OpenRouter's models unknown: %s", exc)
        out = {"brains": sorted(brains, key=lambda c: c.label.lower()), "voices": sorted(voices, key=lambda c: c.label.lower())}
        if brains or voices:
            self._lists = (time.monotonic(), out)
        return out

    async def _voice_allowed(self, model: str) -> bool:
        """The guardrail answers 404 for a model it blocks, before any speech is made (or charged)."""
        try:
            resp = await self.or_http.post("/audio/speech", timeout=httpx.Timeout(15, connect=5),
                                           json={"model": model, "input": "", "response_format": "pcm"})
        except httpx.HTTPError:
            return False
        return resp.status_code != 404

    def brain(self, model_id: str, hears: bool | None = None) -> Brain:
        if model_id not in self._brains:
            provider, model = split_id(model_id)
            if provider == "gemini":
                if self.g_http is None:
                    raise ValueError("no Gemini key on the bridge (agents.json gemini_key_file)")
                self._brains[model_id] = GeminiBrain(self.g_http, model)
            else:
                if self.or_http is None:
                    raise ValueError("no OpenRouter voice key on the bridge (agents.json voice_key_file)")
                self._brains[model_id] = OpenRouterBrain(self.or_http, model, bool(hears))
        brain = self._brains[model_id]
        if hears is not None and isinstance(brain, OpenRouterBrain):
            brain.hears = hears or brain.speaks
        return brain

    def voice(self, model_id: str) -> Voice:
        if model_id not in self._voices:
            provider, model = split_id(model_id)
            if provider == "gemini":
                if self.g_http is None:
                    raise ValueError("no Gemini key on the bridge (agents.json gemini_key_file)")
                self._voices[model_id] = GeminiVoice(self.g_http, model)
            else:
                if self.or_http is None:
                    raise ValueError("no OpenRouter voice key on the bridge (agents.json voice_key_file)")
                self._voices[model_id] = OpenRouterVoice(self.or_http, model)
        return self._voices[model_id]


def sentences(buffer: str, final: bool = False) -> tuple[list[str], str]:
    """Whole sentences out of [buffer] (to start speaking before the reply is finished), and what's left."""
    out, start = [], 0
    for i, ch in enumerate(buffer):
        if ch in ".!?…" and (i + 1 == len(buffer) or buffer[i + 1] in " \n\"'”)") and i + 1 - start >= 12:
            if final or i + 1 < len(buffer):
                out.append(buffer[start:i + 1].strip())
                start = i + 1
        elif ch == "\n" and buffer[start:i].strip():
            out.append(buffer[start:i].strip())
            start = i + 1
    rest = buffer[start:]
    if final and rest.strip():
        out.append(rest.strip())
        rest = ""
    return [s for s in out if s], rest
