"""Talk 3's talker (§9): the voice model hears audio, uses its tools, briefs the agent, and speaks."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path

import httpx
import pytest
from conftest import check

from talaria_bridge.chat import ChatService, ChatStore, Conversation
from talaria_bridge.protocol import messages as m
from talaria_bridge.talk import Talker, TalkError
from talaria_bridge.todos import TodoStore


def sse(*chunks: dict) -> bytes:
    return b"".join(b"data: " + json.dumps(c).encode() + b"\n\n" for c in chunks) + b"data: [DONE]\n\n"


def voice_says(text: str) -> bytes:
    return sse({"choices": [{"delta": {"audio": {"data": "AAAA", "transcript": text}}}]},
               {"choices": [{"delta": {"audio": {"data": "BBBB"}}}]})


def calls(*tool_calls: tuple[str, dict]) -> bytes:
    return sse({"choices": [{"delta": {"tool_calls": [
        {"index": i, "id": f"c{i}", "type": "function", "function": {"name": n, "arguments": json.dumps(a)}}
        for i, (n, a) in enumerate(tool_calls)]}}]})


class FakeOpenRouter:
    """The talker's streamed answers, in order; and what it writes down, for the requests that aren't streamed."""
    def __init__(self, *replies: bytes, heard: str = "Add send Shreyas the humanoid files to my to-dos"):
        self.replies = list(replies)
        self.heard = heard
        self.bodies: list[dict] = []
        self.hearing: list[dict] = []

    def __call__(self, req: httpx.Request) -> httpx.Response:
        body = json.loads(req.content)
        if not body.get("stream"):
            self.hearing.append(body)
            return httpx.Response(200, json={"choices": [{"message": {"role": "assistant", "content": self.heard}}]})
        self.bodies.append(body)
        return httpx.Response(200, content=self.replies.pop(0), headers={"content-type": "text/event-stream"})


def setup(tmp_path: Path, *replies: bytes):
    sent: list[dict] = []

    async def broadcast(msg: dict) -> None:
        sent.append(msg)

    store = ChatStore(tmp_path / "chat.db")
    store.add(Conversation("c-1", "hermes", "talaria_1", "Plans", 1, 2, "assistant", "Your BOM review moved to four."))
    chat = ChatService(store, {}, broadcast, todos=TodoStore(tmp_path / "chat.db"))
    fake = FakeOpenRouter(*replies)
    chat.talker = Talker("sk", chat, transport=httpx.MockTransport(fake))
    return chat, fake, sent


async def done(sent: list[dict]) -> dict:
    for _ in range(200):
        found = [x for x in sent if x["method"] == "talk.done"]
        if found:
            return check("talk.done", found[-1])["params"]
        await asyncio.sleep(0.01)
    raise AssertionError("no talk.done")


async def test_it_hears_does_the_quick_things_and_reads_them_back(tmp_path: Path):
    chat, fake, sent = setup(tmp_path,
                             calls(("add_todo", {"text": "Send Shreyas the humanoid files"}), ("get_agenda", {"day": "today"})),
                             voice_says("Added: send Shreyas the humanoid files. Nothing else on today. Did I get that right?"))
    req = check("talk.turn", m.request("1", "talk.turn", {"audio": "UklGRg==", "format": "wav", "conversation_id": "c-1"}))
    res, _ = await chat.handle("talk.turn", req["params"])
    check("talk.turn.result", m.result("1", res))
    end = await done(sent)
    assert end["text"].startswith("Added: send Shreyas") and end["conversation_id"] == "c-1"
    assert [t["text"] for t in chat.todos.list()] == ["Send Shreyas the humanoid files"]
    first = fake.bodies[0]
    assert first["model"] == "openai/gpt-audio-mini" and first["stream"] is True and first["modalities"] == ["text", "audio"]
    assert first["audio"] == {"voice": "shimmer", "format": "pcm16"}
    assert first["messages"][-1]["content"][0] == {"type": "input_audio", "input_audio": {"data": "UklGRg==", "format": "wav"}}
    assert "Your BOM review moved to four." in first["messages"][0]["content"], "the agent's last words as context"
    assert "Never make up facts" in first["messages"][0]["content"]
    tool_results = [x for x in fake.bodies[1]["messages"] if x["role"] == "tool"]
    assert tool_results[0]["content"].startswith("Added: Send Shreyas") and '"date"' in tool_results[1]["content"]
    audio = [check("talk.audio", x)["params"] for x in sent if x["method"] == "talk.audio"]
    assert [a["seq"] for a in audio] == [0, 1] and audio[0]["data"] == "AAAA"
    assert audio[0]["conversation_id"] == "c-1", "so devices recognise the agent's replies spoken on their own"
    assert any(x["method"] == "todos.changed" for x in sent)
    await chat.close()


async def test_what_was_said_is_written_down_and_kept_in_the_chat(tmp_path: Path):
    chat, fake, sent = setup(tmp_path, voice_says("Added it. Anything else?"), voice_says("You've got the BOM review."))
    await chat.handle("talk.turn", {"audio": "UklGRg==", "conversation_id": "c-1"})
    await done(sent)
    hear = fake.hearing[0]
    assert hear["model"] == "openai/gpt-audio-mini" and hear["modalities"] == ["text"] and "stream" not in hear
    assert "word for word" in hear["messages"][0]["content"]
    assert hear["messages"][1]["content"][1] == {"type": "input_audio", "input_audio": {"data": "UklGRg==", "format": "wav"}}
    heard = [check("talk.heard", x)["params"] for x in sent if x["method"] == "talk.heard"]
    assert heard[0]["text"] == "Add send Shreyas the humanoid files to my to-dos" and heard[0]["conversation_id"] == "c-1"
    kept = [check("chat.talk", x)["params"] for x in sent if x["method"] == "chat.talk"]
    assert [(k["conversation_id"], k["message"]["role"], k["message"]["text"]) for k in kept] == [
        ("c-1", "user", "🎙 Add send Shreyas the humanoid files to my to-dos"), ("c-1", "assistant", "Added it. Anything else?")]
    assert [x["text"] for x in chat.store.talk_messages("c-1")] == [k["message"]["text"] for k in kept]
    assert chat.store.get("c-1").last_text == "Added it. Anything else?"

    # the next turn remembers the words, not "(the owner spoke)"
    fake.heard = "What's on today?"
    await chat.handle("talk.turn", {"audio": "UklGRg==", "conversation_id": "c-1"})
    for _ in range(200):
        if len(chat.store.talk_messages("c-1")) == 4:
            break
        await asyncio.sleep(0.01)
    remembered = fake.bodies[1]["messages"][1:-1]
    assert remembered == [{"role": "user", "content": "Add send Shreyas the humanoid files to my to-dos"},
                          {"role": "assistant", "content": "Added it. Anything else?"}]

    # an approval's question and the retelling of the agent's replies aren't kept
    fake.replies += [voice_says("I need your okay.")]
    sent.clear()
    await chat.handle("talk.say", {"text": "I need your okay.", "conversation_id": "c-1"})
    await done(sent)
    assert not [x for x in sent if x["method"] in ("chat.talk", "talk.heard")]
    await chat.close()


async def test_talk_without_a_chat_starts_one_titled_with_the_first_words(tmp_path: Path):
    chat, fake, sent = setup(tmp_path, voice_says("Added it."))
    sessions: list[tuple[str, str]] = []

    class Agent:
        async def create_session(self, session_id: str, title: str) -> dict:
            sessions.append((session_id, title))
            return {}

        async def close(self) -> None:
            pass

    chat.agents = {"hermes": Agent()}
    chat.default_agent = "hermes"
    await chat.handle("talk.turn", {"audio": "UklGRg=="})
    end = await done(sent)
    conv = end["conversation_id"]
    assert conv != "c-1" and sessions[0][0] == chat.store.get(conv).hermes_session_id
    assert chat.store.get(conv).title.startswith("Add send Shreyas")
    assert [x["role"] for x in chat.store.talk_messages(conv)] == ["user", "assistant"]
    assert conv in chat.talker.active, "the agent's replies there are spoken"
    await chat.close()


async def test_without_the_words_talk_carries_on(tmp_path: Path):
    chat, fake, sent = setup(tmp_path, voice_says("Sorry, say that again?"))
    fake.heard = ""
    await chat.handle("talk.turn", {"audio": "UklGRg==", "conversation_id": "c-1"})
    end = await done(sent)
    assert end["text"] == "Sorry, say that again?" and "error" not in end
    assert not [x for x in sent if x["method"] == "talk.heard"]
    assert [x["role"] for x in chat.store.talk_messages("c-1")] == ["assistant"]
    await chat.close()


async def test_ticking_off_by_name_and_briefing_the_agent(tmp_path: Path):
    chat, fake, sent = setup(tmp_path,
                             calls(("complete_todos", {"items": ["the bank", "passport"]})),
                             voice_says("Ticked off the bank call. I couldn't find passport."))
    chat.todos.add({"text": "Call the bank about the card"})
    chat.todos.add({"text": "Buy milk"})
    await chat.handle("talk.turn", {"audio": "UklGRg==", "conversation_id": "c-1"})
    await done(sent)
    done_texts = [t["text"] for t in chat.todos.list() if t["done"]]
    assert done_texts == ["Call the bank about the card"]
    assert json.loads([x for x in fake.bodies[1]["messages"] if x["role"] == "tool"][0]["content"]) == \
        {"ticked_off": ["Call the bank about the card"], "not_found": ["passport"]}

    sent.clear()
    briefs: list[dict] = []

    async def send(p):
        briefs.append(p)
        return {"conversation_id": "c-1", "turn_id": "t-9"}, None

    chat.send = send
    fake.replies += [calls(("ask_hermes", {"brief": "Add 'Dentist' to the calendar for Fri 9 Oct 17:00 IST."})),
                     voice_says("I've asked Hermes to add the dentist; I'll tell you when it's done.")]
    await chat.handle("talk.turn", {"audio": "UklGRg==", "conversation_id": "c-1"})
    await done(sent)
    order = [x["method"] for x in sent if x["method"] in ("chat.talk", "talk.done")]
    assert order == ["chat.talk", "chat.talk", "talk.done"]
    assert briefs == [{"text": "🎙 From Talk: Add 'Dentist' to the calendar for Fri 9 Oct 17:00 IST.", "conversation_id": "c-1"}]
    await chat.close()


async def test_the_agents_reply_is_spoken_in_a_conversation_being_talked_in(tmp_path: Path):
    chat, fake, sent = setup(tmp_path, voice_says("Done, the dentist is in your calendar for Friday at five."))
    chat.talker.on_reply("c-1", "Created 'Dentist', Fri 9 Oct 17:00.", "completed")
    await asyncio.sleep(0.05)
    assert not sent, "not talked in: nothing said"
    chat.talker.active["c-1"] = __import__("time").time()
    chat.talker.on_reply("c-1", "Created 'Dentist', Fri 9 Oct 17:00.", "completed")
    end = await done(sent)
    assert end["unprompted"] is True and "Friday at five" in end["text"]
    assert "Created 'Dentist'" in fake.bodies[0]["messages"][-1]["content"] and "tools" not in fake.bodies[0]
    req = check("talk.end", m.request("2", "talk.end", {"conversation_id": "c-1"}))
    assert (await chat.handle("talk.end", req["params"]))[0] == {}
    assert "c-1" not in chat.talker.active
    await chat.close()


async def test_say_and_refusals(tmp_path: Path):
    chat, fake, sent = setup(tmp_path, voice_says("I need your okay for this."))
    req = check("talk.say", m.request("1", "talk.say", {"text": "I need your okay for this.", "conversation_id": "c-1"}))
    await chat.handle("talk.say", req["params"])
    await done(sent)
    assert "word for word" in fake.bodies[0]["messages"][-1]["content"]
    for method, bad in (("talk.turn", {}), ("talk.turn", {"audio": "x", "format": "ogg"}), ("talk.turn", {"audio": "x" * 900_001}),
                        ("talk.turn", {"audio": "x", "conversation_id": "c-404"}), ("talk.say", {"text": ""})):
        with pytest.raises(Exception) as err:
            await chat.handle(method, bad)
        assert err.value.code in (m.INVALID_PARAMS, m.NOT_FOUND)
    without = ChatService(ChatStore(tmp_path / "other.db"), {}, sent.append)
    with pytest.raises(Exception) as err:
        await without.handle("talk.turn", {"audio": "x"})
    assert err.value.code == m.METHOD_NOT_FOUND
    await chat.close()


async def test_a_refusing_model_ends_the_turn_with_an_error(tmp_path: Path):
    chat, fake, sent = setup(tmp_path)

    def refuse(req):
        return httpx.Response(404, json={"error": {"message": "Model blocked by guardrail"}})

    chat.talker._http._transport = httpx.MockTransport(refuse)
    await chat.handle("talk.turn", {"audio": "UklGRg==", "conversation_id": "c-1"})
    end = await done(sent)
    assert "refused (404)" in end["error"] and end["text"] == ""
    with pytest.raises(TalkError):
        raise TalkError(1, "x")
    await chat.close()
