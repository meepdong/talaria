"""Talk's voice (§9): speech and the quick first line, through a fake OpenRouter."""

from __future__ import annotations

import base64
import json
from pathlib import Path

import httpx
import pytest
from conftest import check

from talaria_bridge.chat import ChatService, ChatStore, Conversation
from talaria_bridge.protocol import messages as m
from talaria_bridge.voice import VoiceError, VoiceService


def fake_openrouter(seen: list, *, speech_status=200, ack="  Sure, *checking* your calendar now. "):
    def handle(req: httpx.Request) -> httpx.Response:
        body = json.loads(req.content)
        seen.append((req.url.path, body, req.headers.get("authorization")))
        if req.url.path.endswith("/audio/speech"):
            if speech_status != 200:
                return httpx.Response(speech_status, json={"error": {"message": "Provider returned 400"}})
            if body["response_format"] == "pcm":
                return httpx.Response(200, content=b"\x01\x00" * 24, headers={"content-type": "audio/pcm;rate=24000;channels=1"})
            return httpx.Response(200, content=b"ID3fake-mp3", headers={"content-type": "audio/mpeg"})
        return httpx.Response(200, json={"choices": [{"message": {"content": ack}}]})
    return httpx.MockTransport(handle)


def result(name: str, value: dict) -> dict:
    return check(name, m.result("1", value))["result"]


async def test_speech_is_gemini_by_default_sent_on_as_wav():
    seen: list = []
    voice = VoiceService("sk-test", transport=fake_openrouter(seen))
    req = check("voice.speech", m.request("1", "voice.speech", {"text": "You have two meetings tomorrow."}))
    out = result("voice.speech.result", await voice.speech(req["params"]))
    audio = base64.b64decode(out["audio"])
    assert out["format"] == "wav" and audio[:4] == b"RIFF" and audio[8:16] == b"WAVEfmt " and audio.endswith(b"\x01\x00" * 24)
    assert int.from_bytes(audio[24:28], "little") == 24_000 and int.from_bytes(audio[40:44], "little") == 48
    path, body, auth = seen[0]
    assert path == "/api/v1/audio/speech" and auth == "Bearer sk-test"
    assert body == {"model": "google/gemini-3.8-flash-lite-tts", "input": "You have two meetings tomorrow.",
                    "voice": "Despina", "response_format": "pcm"}
    await voice.close()

    qwen = VoiceService("sk-test", engine="qwen", transport=fake_openrouter(seen))
    out = await qwen.speech({"text": "Ten thirty."})
    assert out["format"] == "mp3" and base64.b64decode(out["audio"]) == b"ID3fake-mp3"
    assert seen[-1][1]["model"] == "qwen/qwen-audio-3.0-tts-flash" and seen[-1][1]["voice"] == "loongeva_v3.6"
    with pytest.raises(ValueError):
        VoiceService("sk", engine="gemini", voice="loongeva_v3.6")
    for bad in ({}, {"text": ""}, {"text": "x" * 151}, {"text": "hi", "voice": "Cherry"}):
        with pytest.raises(VoiceError) as err:
            await voice.speech(bad)
        assert err.value.code == m.INVALID_PARAMS
    await qwen.close()


async def test_a_refused_voice_is_an_error_the_app_can_fall_back_on():
    voice = VoiceService("sk-test", transport=fake_openrouter([], speech_status=400))
    with pytest.raises(VoiceError) as err:
        await voice.speech({"text": "Hello."})
    assert err.value.code == m.AGENT_UNAVAILABLE and "400" in err.value.message
    await voice.close()


async def test_the_quick_line_comes_from_a_small_fast_model():
    seen: list = []
    voice = VoiceService("sk-test", transport=fake_openrouter(seen))
    req = check("voice.ack", m.request("1", "voice.ack", {"text": "what's on tomorrow?"}))
    assert result("voice.ack.result", await voice.ack(req["params"])) == {"text": "Sure, checking your calendar now."}
    _, body, _ = seen[0]
    assert body["model"] == "qwen/qwen3.7-flash" and body["reasoning"] == {"enabled": False} and body["max_tokens"] == 40
    assert body["messages"][1] == {"role": "user", "content": "what's on tomorrow?"}
    await voice.close()

    empty = VoiceService("sk-test", transport=fake_openrouter([], ack="  "))
    with pytest.raises(VoiceError):
        await empty.ack({"text": "hello"})
    await empty.close()


async def test_chat_routes_voice_and_gives_the_quick_line_the_last_answer(tmp_path: Path):
    seen: list = []

    async def unused(msg: dict) -> None:
        pass

    store = ChatStore(tmp_path / "chat.db")
    store.add(Conversation("c-1", "hermes", "talaria_1", "Plans", 1, 2, "assistant", "You have two meetings."))
    chat = ChatService(store, {}, unused, voice=VoiceService("sk", transport=fake_openrouter(seen)))
    res, _ = await chat.handle("voice.ack", {"text": "and the first?", "conversation_id": "c-1"})
    assert res == {"text": "Sure, checking your calendar now."}
    assert "You have two meetings." in seen[0][1]["messages"][1]["content"]
    res, _ = await chat.handle("voice.speech", {"text": "Ten thirty."})
    assert res["format"] == "wav"

    without = ChatService(ChatStore(tmp_path / "other.db"), {}, unused)
    with pytest.raises(Exception) as err:
        await without.handle("voice.speech", {"text": "hi"})
    assert err.value.code == m.METHOD_NOT_FOUND, "no voice key: the app uses the phone's own voice"
    await chat.close()
    store.close()
