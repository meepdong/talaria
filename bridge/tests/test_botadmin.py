"""Making, changing and deleting bots (botadmin.py, spec/README.md §18.8): a fake hermes serve keeps profiles the way
Hermes does where the bridge relies on it (one description "Title — about", skills off by list, the expensive-model
confirmation, avatars, delete)."""

import asyncio
from pathlib import Path

import pytest
import websockets

from talaria_bridge.botadmin import BotAdmin, slug
from talaria_bridge.bots import Bots
from talaria_bridge.chat import ChatService, ChatStore
from talaria_bridge.hermes import HermesClient
from talaria_bridge.hermes_serve import PROBE_KEY, HermesBackend
from talaria_bridge.protocol import keys
from talaria_bridge.protocol import messages as m
from talaria_bridge.registry import Registry
from talaria_bridge.server import BridgeServer, ServerSettings

from conftest import Bridge, check
from test_bots import TOKEN, FakeBotServe
from test_chat import KEY, FakeHermes, connected
from test_commands import call

PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="


class AdminServe(FakeBotServe):
    def __init__(self):
        super().__init__()
        self.souls: dict[str, str] = {"scout": "You find things."}
        self.disabled: dict[str, list] = {"scout": ["spotify"]}
        self.models: dict[str, str] = {"scout": "qwen/qwen3.8-flash"}
        self.avatars: dict[str, str] = {}
        self.deleted: list[str] = []

    def _profile(self, name):
        return next((p for p in self.profiles if p["name"] == name), None)

    async def answer(self, ws, msg):
        method, p = msg["method"], msg.get("params") or {}

        async def ok(result):
            self.calls.append((method, p))
            await self.send(ws, {"jsonrpc": "2.0", "id": msg["id"], "result": result})

        async def err(code, text):
            self.calls.append((method, p))
            await self.send(ws, {"jsonrpc": "2.0", "id": msg["id"], "error": {"code": code, "message": text}})

        if PROBE_KEY in p:
            return await super().answer(ws, msg)
        if method == "profiles.create":
            if self._profile(p["name"]):
                return await err(4062, f"Profile '{p['name']}' already exists")
            self.profiles.append({"name": p["name"], "description": p.get("description", "")})
            self.souls[p["name"]] = p.get("soul", "")
            self.models[p["name"]] = p.get("model", "")
            return await ok({"ok": True, "name": p["name"]})
        if method == "profiles.describe":
            prof = self._profile(p["name"])
            if prof is None:
                return await err(4060, f"profile not found: {p['name']}")
            return await ok({"name": p["name"], "description": prof.get("description", ""), "soul": self.souls.get(p["name"], ""),
                             "model": {"provider": "openrouter", "default": self.models.get(p["name"], "")},
                             "skills": [{"name": n, "enabled": n not in self.disabled.get(p["name"], [])}
                                        for n in ("google-workspace", "spotify", "research")],
                             "toolsets": [{"name": "web", "label": "Web", "description": "Search the web", "enabled": True},
                                          {"name": "browser", "label": "Browser", "description": "", "enabled": False}],
                             "mcp_servers": [{"name": "homeassistant", "enabled": False, "transport": "http"}]})
        if method == "profiles.configure":
            prof = self._profile(p["name"])
            applied = {}
            if "description" in p:
                prof["description"] = p["description"]
                applied["description"] = True
            if "soul" in p:
                self.souls[p["name"]] = p["soul"]
                applied["soul"] = True
            if "disabled_skills" in p:
                self.disabled[p["name"]] = p["disabled_skills"]
                applied["skills"] = True
            if "model" in p:
                if p["model"].startswith("pricey/") and not p.get("confirm_expensive_model"):
                    return await ok({"ok": True, "applied": applied, "confirm_required": True,
                                     "confirm_message": "pricey/big costs $15 per million tokens"})
                self.models[p["name"]] = p["model"]
                applied["model"] = True
            return await ok({"ok": True, "applied": applied})
        if method == "profiles.set_asset":
            if p.get("clear"):
                self.avatars.pop(p["name"], None)
            else:
                self.avatars[p["name"]] = p["data"]
            return await ok({"ok": True, "asset": "avatar"})
        await super().answer(ws, msg)

    async def rest(self, path, method="GET", body=None):
        name = path.rsplit("/", 1)[1]
        self.profiles = [x for x in self.profiles if x["name"] != name]
        self.deleted.append(name)
        return {"ok": True}


@pytest.fixture
async def admin_bridge(tmp_path: Path, settings: ServerSettings):
    fake = AdminServe()
    serve = await websockets.serve(fake.handler, "127.0.0.1", 0, process_request=fake.process_request)

    async def allowed():
        return {"qwen/qwen3.8-flash", "pricey/big", "google/gemini-3-flash"}

    backend = HermesBackend(f"ws://127.0.0.1:{serve.sockets[0].getsockname()[1]}/api/ws", TOKEN, backoff=(0.05, 0.1),
                            allowed_models=allowed)
    backend.rest = fake.rest
    hermes = FakeHermes()

    async def unused(msg: dict) -> None:
        pass

    chat = ChatService(ChatStore(tmp_path / "chat.db"),
                       {"hermes": HermesClient("http://hermes.test", KEY, transport=hermes.transport())}, unused)
    chat.bots = Bots(backend)
    chat.bots.broadcast = lambda msg: chat.broadcast(msg)
    chat.botadmin = BotAdmin(backend, chat.bots)
    registry = Registry(tmp_path / "bridge.db")
    server = BridgeServer(registry, keys.generate_key(), settings, chat=chat, hermes=backend)
    async with server.serve() as ws_server:
        for _ in range(100):
            if chat.bots.roster():
                break
            await asyncio.sleep(0.02)
        yield Bridge(server, registry, f"ws://127.0.0.1:{ws_server.sockets[0].getsockname()[1]}/tnp"), fake, chat
    chat.store.close()
    registry.close()
    serve.close()
    await serve.wait_closed()


def test_profile_names_from_bot_names():
    assert slug("Meeting Minder") == "meetingminder" and slug("PDF-Wiz 2") == "pdf-wiz2"


async def test_a_bots_settings_read_and_change(admin_bridge):
    bridge, fake, chat = admin_bridge
    phone = await connected(bridge)
    got = check("bots.describe.result", await call(phone, "1", "bots.describe", {"bot_id": "bot:scout"}))["result"]["bot"]
    assert got["name"] == "Scout" and got["about"] == "Finds things" and got["personality"] == "You find things."
    assert got["model"] == "qwen/qwen3.8-flash" and [s["name"] for s in got["skills"] if not s["enabled"]] == ["spotify"]
    assert got["toolsets"][0] == {"name": "web", "label": "Web", "about": "Search the web", "enabled": True}

    done = check("bots.update.result", await call(phone, "2", "bots.update", {
        "bot_id": "bot:scout", "name": "Scout", "about": "Finds places to eat", "personality": "Be brief.",
        "skills": ["research"]}))["result"]
    assert done["applied"] == {"description": True, "soul": True, "skills": True}
    assert fake._profile("scout")["description"] == "Scout — Finds places to eat"
    assert fake.disabled["scout"] == ["google-workspace", "spotify"]
    assert next(b for b in chat.bots.roster() if b["id"] == "bot:scout")["description"] == "Finds places to eat"


async def test_models_go_through_the_guardrail_and_the_expensive_check(admin_bridge):
    bridge, fake, _ = admin_bridge
    phone = await connected(bridge)
    blocked = await call(phone, "1", "bots.update", {"bot_id": "bot:scout", "model": "anthropic/claude-opus"})
    assert blocked["error"]["code"] == m.INVALID_PARAMS and "guardrail" in blocked["error"]["message"]
    asked = (await call(phone, "2", "bots.update", {"bot_id": "bot:scout", "model": "pricey/big"}))["result"]
    assert asked["confirm"] == "pricey/big costs $15 per million tokens" and fake.models["scout"] == "qwen/qwen3.8-flash"
    sure = (await call(phone, "3", "bots.update", {"bot_id": "bot:scout", "model": "pricey/big", "confirm": True}))["result"]
    assert sure["applied"] == {"model": True} and fake.models["scout"] == "pricey/big"


async def test_a_new_bot_its_picture_and_deleting_it(admin_bridge):
    bridge, fake, chat = admin_bridge
    phone = await connected(bridge)
    made = check("bots.create.result", await call(phone, "1", "bots.create", {
        "name": "Trip Planner", "about": "Plans trips", "personality": "Love trains.", "model": "google/gemini-3-flash"}))
    assert made["result"] == {"bot_id": "bot:tripplanner"}
    assert [p for name, p in fake.calls if name == "profiles.create"][0] == {
        "name": "tripplanner", "description": "Trip Planner — Plans trips", "no_alias": True, "soul": "Love trains.",
        "model": "google/gemini-3-flash", "provider": "openrouter"}
    assert any(b["id"] == "bot:tripplanner" and b["name"] == "Trip Planner" for b in chat.bots.roster())
    again = await call(phone, "2", "bots.create", {"name": "trip planner", "about": ""})
    assert again["error"]["code"] == m.CONFLICT

    assert check("bots.picture.result", await call(phone, "3", "bots.picture", {"bot_id": "bot:tripplanner", "data": PNG}))
    assert fake.avatars["tripplanner"] == PNG
    await call(phone, "4", "bots.picture", {"bot_id": "bot:tripplanner", "clear": True})
    assert "tripplanner" not in fake.avatars

    conv = (await call(phone, "5", "bots.open", {"bot_id": "bot:tripplanner"}))["result"]["conversation_id"]
    assert check("bots.delete.result", await call(phone, "6", "bots.delete", {"bot_id": "bot:tripplanner"}))
    assert fake.deleted == ["tripplanner"] and chat.store.get(conv) is None
    assert not chat.bots.known("bot:tripplanner")
    gone = await call(phone, "7", "bots.describe", {"bot_id": "bot:tripplanner"})
    assert gone["error"]["code"] == m.NOT_FOUND
