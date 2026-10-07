"""Tally on any model (tally_models.py, talk.py, tally_chat.py; spec/README.md §9 "Tally's models", "Tally in chat"):
fakes of Gemini's API and OpenRouter answer the way they do where the bridge relies on it (the models list, streamed
text, function calls with thought signatures, the thinking level a model refuses, speech, the guardrail)."""

import asyncio
import base64
import json
from pathlib import Path

import httpx

from talaria_bridge.chat import ChatService, ChatStore, Conversation
from talaria_bridge.hermes import HermesClient
from talaria_bridge.protocol import messages as m
from talaria_bridge.tally_chat import TallyChat
from talaria_bridge.tally_models import sentences, to_gemini
from talaria_bridge.talk import Talker
from talaria_bridge.todos import TodoStore

from conftest import check
from test_chat import KEY, FakeHermes
from test_talk import done, sse


def g_sse(*chunks: dict) -> bytes:
    return b"".join(b"data: " + json.dumps(c).encode() + b"\r\n\r\n" for c in chunks)


def g_text(*pieces: str) -> bytes:
    return g_sse(*({"candidates": [{"content": {"role": "model", "parts": [{"text": p}]}}]} for p in pieces))


def g_call(name: str, args: dict) -> bytes:
    return g_sse({"candidates": [{"content": {"role": "model", "parts": [
        {"thought": True, "text": "", "thoughtSignature": "sig-1"},
        {"functionCall": {"name": name, "args": args}, "thoughtSignature": "sig-2"}]}}]})


class FakeGemini:
    def __init__(self, *replies: bytes, refuse_minimal: bool = False):
        self.replies = list(replies)
        self.bodies: list[dict] = []
        self.spoken: list[str] = []
        self.refuse_minimal = refuse_minimal

    def __call__(self, req: httpx.Request) -> httpx.Response:
        path = req.url.path
        if req.method == "GET" and path.endswith("/models"):
            return httpx.Response(200, json={"models": [
                {"name": "models/gemini-3.5-flash", "displayName": "Gemini 3.5 Flash", "supportedGenerationMethods": ["generateContent"]},
                {"name": "models/gemini-3.8-flash-tts", "displayName": "Gemini 3.8 Flash TTS", "supportedGenerationMethods": ["generateContent"]},
                {"name": "models/gemini-3.8-live", "supportedGenerationMethods": ["bidiGenerateContent"]},
                {"name": "models/gemini-3.1-flash-image", "supportedGenerationMethods": ["generateContent"]}]})
        body = json.loads(req.content)
        if "-tts:" in path:
            text = body["contents"][0]["parts"][0]["text"]
            self.spoken.append(text)
            audio = base64.b64encode(text.encode()).decode()
            return httpx.Response(200, content=g_sse({"candidates": [{"content": {"parts": [{"inlineData": {
                "mimeType": "audio/L16;codec=pcm;rate=24000", "data": audio}}]}}]}))
        if path.endswith(":generateContent"):  # writing down what was said
            return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": "What's on my day?"}]}}]})
        level = (body.get("generationConfig") or {}).get("thinkingConfig", {}).get("thinkingLevel")
        if self.refuse_minimal and level == "minimal":
            return httpx.Response(400, json={"error": {"message": "Thinking level MINIMAL is not supported for this model."}})
        self.bodies.append(body)
        return httpx.Response(200, content=self.replies.pop(0))


class FakeRouter:
    """OpenRouter for the lists and the guardrail: two allowed brains, one allowed voice, one blocked voice."""

    def __call__(self, req: httpx.Request) -> httpx.Response:
        if req.url.path.endswith("/models/user"):
            return httpx.Response(200, json={"data": [
                {"id": "openai/gpt-audio-mini", "name": "GPT Audio Mini",
                 "architecture": {"input_modalities": ["text", "audio"], "output_modalities": ["text", "audio"]}},
                {"id": "qwen/qwen3.8-flash", "name": "Qwen 3.8 Flash",
                 "architecture": {"input_modalities": ["text"], "output_modalities": ["text"]}}]})
        if req.url.path.endswith("/models"):
            return httpx.Response(200, json={"data": [
                {"id": "elevenlabs/eleven-v4-turbo", "name": "Eleven v4 Turbo", "architecture": {"output_modalities": ["speech"]}},
                {"id": "elevenlabs/eleven-v3", "name": "Eleven v3", "architecture": {"output_modalities": ["speech"]}}]})
        if req.url.path.endswith("/audio/speech"):
            body = json.loads(req.content)
            if body["model"] == "elevenlabs/eleven-v3":
                return httpx.Response(404, json={"error": {"message": "blocked by your guardrail"}})
            if not body["input"]:
                return httpx.Response(400, json={"error": {"message": "input is empty"}})
            return httpx.Response(200, content=b"\x01\x00" * 12000, headers={"content-type": "audio/pcm;rate=24000"})
        return httpx.Response(404)


def make(tmp_path: Path, gemini: FakeGemini, hermes: FakeHermes | None = None):
    sent: list[dict] = []

    async def broadcast(msg: dict) -> None:
        sent.append(msg)

    store = ChatStore(tmp_path / "chat.db")
    agents = {"hermes": HermesClient("http://hermes.test", KEY, transport=hermes.transport())} if hermes else {}
    chat = ChatService(store, agents, broadcast, todos=TodoStore(tmp_path / "chat.db"))
    chat.talker = Talker("sk", chat, name="Tally", worker="Hermes", gemini_key="gk", transcriber="talker",
                         transport=httpx.MockTransport(FakeRouter()), gemini_transport=httpx.MockTransport(gemini),
                         brain="gemini:gemini-3.5-flash", speech="gemini:gemini-3.8-flash-tts", voice="Kore")
    chat.tally = TallyChat(chat.talker, chat)
    return chat, sent


def test_sentences_are_cut_for_speaking_early():
    whole, rest = sentences("Sure, that's three sixty. And the rest is ")
    assert whole == ["Sure, that's three sixty."] and rest == " And the rest is "
    assert sentences("Ok.", final=True) == (["Ok."], "")


def test_the_conversation_in_geminis_shape():
    body = to_gemini([
        {"role": "system", "content": "You are Tally."},
        {"role": "user", "content": [{"type": "input_audio", "input_audio": {"data": "UklG", "format": "wav"}}]},
        {"role": "assistant", "content": None, "tool_calls": [{"id": "c0", "type": "function",
                                                               "function": {"name": "add_todo", "arguments": "{\"text\": \"milk\"}"}}],
         "_gemini": [{"functionCall": {"name": "add_todo", "args": {"text": "milk"}}, "thoughtSignature": "sig"}]},
        {"role": "tool", "tool_call_id": "c0", "content": "Added: milk"},
    ], [{"type": "function", "function": {"name": "list_todos", "description": "x", "parameters": {"type": "object", "properties": {}}}},
        {"type": "function", "function": {"name": "add_todo", "parameters": {"type": "object", "additionalProperties": False,
                                                                             "properties": {"text": {"type": "string"}}}}}])
    assert body["systemInstruction"] == {"parts": [{"text": "You are Tally."}]}
    assert body["contents"][0] == {"role": "user", "parts": [{"inline_data": {"mime_type": "audio/wav", "data": "UklG"}}]}
    assert body["contents"][1]["parts"][0]["thoughtSignature"] == "sig"  # Gemini needs its signature back
    assert body["contents"][2] == {"role": "user", "parts": [{"functionResponse": {"name": "add_todo", "response": {"result": "Added: milk"}}}]}
    decls = body["tools"][0]["functionDeclarations"]
    assert "parameters" not in decls[0] and "additionalProperties" not in decls[1]["parameters"]


async def test_choices_come_from_the_keys_and_the_guardrail(tmp_path: Path):
    chat, _ = make(tmp_path, FakeGemini())
    got = check("talk.setup.result", m.result("1", (await chat.handle("talk.setup", {}))[0]))["result"]
    assert got["brain"] == "gemini:gemini-3.5-flash" and got["speech"] == "gemini:gemini-3.8-flash-tts" and got["voice"] == "Kore"
    assert {b["id"]: (b["hears"], b["speaks"]) for b in got["brains"]} == {
        "gemini:gemini-3.5-flash": (True, False), "openrouter:openai/gpt-audio-mini": (True, True),
        "openrouter:qwen/qwen3.8-flash": (False, False)}
    assert sorted(v["id"] for v in got["speeches"]) == ["gemini:gemini-3.8-flash-tts", "openrouter:elevenlabs/eleven-v4-turbo"]
    assert "Kore" in got["voices"] and "Puck" in got["voices"]

    eleven = (await chat.handle("talk.configure", {"speech": "openrouter:elevenlabs/eleven-v4-turbo"}))[0]
    assert eleven["voice"] == "Sarah" and chat.store.setting("talk.speech") == "openrouter:elevenlabs/eleven-v4-turbo"
    own = (await chat.handle("talk.configure", {"brain": "openrouter:openai/gpt-audio-mini", "speech": None}))[0]
    assert own["speech"] is None and own["voice"] == "shimmer"
    for bad in ({"brain": "gemini:gemini-9"}, {"speech": "openrouter:elevenlabs/eleven-v3"},
                {"brain": "openrouter:qwen/qwen3.8-flash", "speech": None}, {"voice": "Nobody"}):
        try:
            await chat.handle("talk.configure", bad)
            raise AssertionError(f"accepted {bad}")
        except Exception as exc:  # RpcError
            assert getattr(exc, "code", None) == m.INVALID_PARAMS, bad


async def test_tally_hears_thinks_and_speaks_sentence_by_sentence(tmp_path: Path):
    gemini = FakeGemini(g_text("Your day is clear. ", "Want me to add anything?"), refuse_minimal=True)
    chat, sent = make(tmp_path, gemini)
    await chat.handle("talk.turn", {"audio": "UklGRg==", "format": "wav"})
    end = await done(sent)
    assert end["text"] == "Your day is clear. Want me to add anything?" and "error" not in end
    assert gemini.spoken[-2:] == ["Your day is clear.", "Want me to add anything?"]  # each sentence as soon as it's whole
    audio = [base64.b64decode(x["params"]["data"]).decode() for x in sent if x["method"] == "talk.audio"]
    assert audio == ["Your day is clear.", "Want me to add anything?"]
    first = gemini.bodies[0]
    assert first["contents"][-1]["parts"][0]["inline_data"]["mime_type"] == "audio/wav"  # she hears the recording itself
    assert first["generationConfig"] == {"thinkingConfig": {"thinkingLevel": "low"}}  # the lightest level this model takes


async def test_tool_calls_round_trip_with_geminis_signatures(tmp_path: Path):
    gemini = FakeGemini(g_call("add_todo", {"text": "Buy milk"}), g_text("Added buy milk. Anything else?"))
    chat, sent = make(tmp_path, gemini)
    await chat.handle("talk.turn", {"audio": "UklGRg==", "format": "wav"})
    end = await done(sent)
    assert [t["text"] for t in chat.todos.list()] == ["Buy milk"] and end["text"] == "Added buy milk. Anything else?"
    again = gemini.bodies[1]["contents"]
    assert [p.get("thoughtSignature") for p in again[-2]["parts"]] == ["sig-1", "sig-2"]
    assert again[-1]["parts"][0]["functionResponse"]["name"] == "add_todo"


async def test_typed_chat_with_tally_and_her_jobs_come_back_to_her(tmp_path: Path):
    hermes = FakeHermes()
    gemini = FakeGemini(g_call("ask_hermes", {"brief": "Find two cafes near Indiranagar"}),
                        g_text("I've asked Hermes to find cafes."),
                        g_text("Hermes found Matteo and Third Wave."))
    chat, sent = make(tmp_path, gemini, hermes)
    result, turn = await chat.handle("chat.send", {"agent_id": "tally", "text": "Find me a cafe for tomorrow"})
    chat.start(turn)
    for _ in range(300):
        if any(x["method"] == "chat.done" and x["params"]["turn_id"] == result["turn_id"] for x in sent):
            break
        await asyncio.sleep(0.01)
    conv = chat.store.get(result["conversation_id"])
    assert conv.agent_id == "tally"
    deltas = "".join(x["params"].get("text", "") for x in sent if x["method"] == "chat.delta"
                     and x["params"]["turn_id"] == result["turn_id"] and x["params"].get("kind") == "text")
    assert "asked Hermes" in deltas
    linked = chat.store.setting(f"tally.worker.{conv.id}")
    assert linked and linked != conv.id and chat.store.get(linked).agent_id == "hermes"  # the job runs beside her chat
    for _ in range(300):  # Hermes answers ("Hello"); Tally retells it in writing in her conversation
        kept = [x for x in sent if x["method"] == "chat.talk" and x["params"]["conversation_id"] == conv.id]
        if kept:
            break
        await asyncio.sleep(0.01)
    assert kept and kept[-1]["params"]["message"]["text"] == "Hermes found Matteo and Third Wave."
    history = [(r["role"], r["text"]) for r in chat.store.talk_messages(conv.id)]
    assert history[:2] == [("user", "Find me a cafe for tomorrow"), ("assistant", "I've asked Hermes to find cafes.")]
    models = (await chat.handle("agent.models", {"agent_id": "tally"}))[0]
    assert models["current"] == {"provider": "gemini", "model": "gemini-3.5-flash"}
    await chat.close()
