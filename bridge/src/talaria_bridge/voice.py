"""Talk's voice (spec/README.md §9, Talk 2): natural speech and a quick first line, through OpenRouter.

voice.speech turns one short piece of text into speech with a neural voice: Gemini 3.8 Flash-Lite TTS by default
(the owner's pick: cheap; raw PCM, sent on as WAV), or Qwen-Audio TTS Flash (MP3, quicker to start). voice.ack
asks a fast small model for one short spoken line that acknowledges what was said, so Talk can answer within
about a second while the agent does the real work; it never answers the question itself. The key stays on the
bridge: devices get audio and a line of text.
"""

from __future__ import annotations

import base64
import logging
import struct
from dataclasses import dataclass

import httpx

from .protocol import messages as m

log = logging.getLogger("talaria.voice")

OPENROUTER = "https://openrouter.ai/api/v1"


@dataclass(frozen=True)
class SpeechModel:
    model: str
    voices: tuple[str, ...]  # the first is the default: Despina, the owner's pick; female voices first
    pcm: bool  # raw 24 kHz 16-bit mono, wrapped as WAV for devices; else MP3


SPEECH_MODELS = {
    "gemini": SpeechModel("google/gemini-3.8-flash-lite-tts", ("Despina", "Kore", "Aoede", "Leda"), pcm=True),
    "qwen": SpeechModel("qwen/qwen-audio-3.0-tts-flash", ("loongeva_v3.6", "longanhuan_v3.6", "loongjohn"), pcm=False),
}
VOICES = tuple(v for s in SPEECH_MODELS.values() for v in s.voices)
ACK_MODEL = "qwen/qwen3.7-flash"
MAX_SPEECH = 150  # characters per voice.speech: about 10 s, some 500 KB of WAV, inside a 1 MiB frame once encoded
PCM_RATE = 24_000
MAX_ACK_INPUT = 2000
MAX_ACK = 160

ACK_PROMPT = """You are the voice of the owner's personal assistant in a spoken conversation. The owner just \
said something, and the assistant is already working on it; its real answer will follow. Reply with ONE short \
spoken sentence, under 12 words, that shows you heard them, such as "Sure, checking your calendar now." or \
"Okay, give me a moment." Never answer the question, never state facts, numbers or times, never ask anything, \
no emoji or Markdown. For a greeting or thanks, a brief friendly reply is fine."""


class VoiceError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class VoiceService:
    def __init__(self, api_key: str, *, engine: str = "gemini", voice: str | None = None,
                 transport: httpx.AsyncBaseTransport | None = None):
        if engine not in SPEECH_MODELS:
            raise ValueError(f"voice engine must be one of {', '.join(SPEECH_MODELS)}")
        self.engine = SPEECH_MODELS[engine]
        if voice is not None and voice not in self.engine.voices:
            raise ValueError(f"voice must be one of {', '.join(self.engine.voices)}")
        self.voice = voice or self.engine.voices[0]
        self._http = httpx.AsyncClient(base_url=OPENROUTER, transport=transport,
                                       timeout=httpx.Timeout(20, connect=5),
                                       headers={"Authorization": f"Bearer {api_key}", "User-Agent": "talaria-bridge",
                                                "X-Title": "Talaria"})

    async def close(self) -> None:
        await self._http.aclose()

    async def speech(self, p: dict) -> dict:
        """voice.speech {text, voice?} → {format: "mp3" | "wav", audio: base64}."""
        text = p.get("text")
        if not (isinstance(text, str) and text.strip() and len(text) <= MAX_SPEECH):
            raise VoiceError(m.INVALID_PARAMS, f"text must be 1 to {MAX_SPEECH} characters")
        voice = p.get("voice", self.voice)
        if voice not in self.engine.voices:
            raise VoiceError(m.INVALID_PARAMS, f"voice must be one of {', '.join(self.engine.voices)}")
        try:
            resp = await self._http.post("/audio/speech", json={
                "model": self.engine.model, "input": text.strip(), "voice": voice,
                "response_format": "pcm" if self.engine.pcm else "mp3"})
        except httpx.HTTPError as exc:
            log.warning("speech failed: %s", exc)
            raise VoiceError(m.AGENT_UNAVAILABLE, "The voice service can't be reached right now") from None
        if resp.status_code != 200 or not resp.headers.get("content-type", "").startswith("audio/"):
            log.warning("speech refused (%s): %s", resp.status_code, resp.text[:300])
            raise VoiceError(m.AGENT_UNAVAILABLE, f"The voice service refused ({resp.status_code})")
        if self.engine.pcm:
            return {"format": "wav", "audio": base64.b64encode(wav(resp.content)).decode()}
        return {"format": "mp3", "audio": base64.b64encode(resp.content).decode()}

    async def ack(self, p: dict) -> dict:
        """voice.ack {text, context?} → {text}: one short line to say while the agent works."""
        text = p.get("text")
        if not (isinstance(text, str) and text.strip() and len(text) <= MAX_ACK_INPUT):
            raise VoiceError(m.INVALID_PARAMS, f"text must be 1 to {MAX_ACK_INPUT} characters")
        context = p.get("context")
        if context is not None and not isinstance(context, str):
            raise VoiceError(m.INVALID_PARAMS, "context must be a string")
        said = text.strip()
        if context:
            said = f"(The assistant's last words were: {context.strip()[:MAX_ACK_INPUT]})\n\n{said}"
        try:
            resp = await self._http.post("/chat/completions", timeout=httpx.Timeout(6, connect=3), json={
                "model": ACK_MODEL, "max_tokens": 40, "temperature": 0.6, "reasoning": {"enabled": False},
                "messages": [{"role": "system", "content": ACK_PROMPT}, {"role": "user", "content": said}]})
            resp.raise_for_status()
            line = resp.json()["choices"][0]["message"]["content"]
        except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError) as exc:
            log.warning("ack failed: %s", exc)
            raise VoiceError(m.AGENT_UNAVAILABLE, "No quick line this time") from None
        line = " ".join(str(line or "").replace("*", "").split())[:MAX_ACK]
        if not line:
            raise VoiceError(m.AGENT_UNAVAILABLE, "No quick line this time")
        return {"text": line}


def wav(pcm: bytes, rate: int = PCM_RATE) -> bytes:
    """16-bit mono PCM with a WAV header, so any player can play it."""
    return (b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt "
            + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16) + b"data" + struct.pack("<I", len(pcm)) + pcm)


VOICE_METHODS = frozenset({"voice.speech", "voice.ack"})
