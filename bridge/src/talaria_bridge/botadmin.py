"""Making, changing and deleting bots (§18.8), as Hermes Desktop's bot editor does, through the doorway.

A bot is a Hermes profile. Its title and what it's for are kept as one description, "Title — about" (bots.py reads
the title from it, and Hermes reads the whole of it when it picks a bot for a job). Models are checked against the
bridge's OpenRouter guardrail, as everywhere else (§11)."""

from __future__ import annotations

import re
from urllib.parse import quote

from .bots import PREFIX, PROFILE_RE, Bots, profile_of
from .hermes_serve import HermesBackend, ServeError, ServeUnavailable
from .protocol import messages as m

ADMIN_METHODS = frozenset({"bots.describe", "bots.create", "bots.update", "bots.picture", "bots.delete"})
SEP = " — "
MAX_NAME, MAX_ABOUT, MAX_SOUL = 60, 2000, 40000
MAX_PICTURE_B64 = 699_000  # 512 KB, what bots.avatar gives back (bots.py MAX_AVATAR)
PROVIDER = "openrouter"


class AdminError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def slug(name: str) -> str:
    """The profile name for a bot called [name]: "Meeting Minder" → "meetingminder"."""
    s = re.sub(r"[^a-z0-9-]+", "", name.lower().replace(" ", ""))[:32].strip("-")
    if not s or not PROFILE_RE.match(s):
        raise AdminError(m.INVALID_PARAMS, "The name needs some letters or digits")
    return s


def _text(p: dict, key: str, most: int, *, blank: bool = True) -> str | None:
    if key not in p:
        return None
    v = p[key]
    if not (isinstance(v, str) and len(v) <= most and (blank or v.strip())):
        raise AdminError(m.INVALID_PARAMS, f"{key} must be text of at most {most} characters")
    return v.strip() if key != "personality" else v


def _names(p: dict, key: str) -> list[str] | None:
    if key not in p:
        return None
    v = p[key]
    if not (isinstance(v, list) and len(v) <= 500 and all(isinstance(x, str) and 0 < len(x) <= 120 for x in v)):
        raise AdminError(m.INVALID_PARAMS, f"{key} must be a list of names")
    return v


class BotAdmin:
    def __init__(self, backend: HermesBackend, bots: Bots):
        self.backend = backend
        self.bots = bots

    async def _rpc(self, method: str, params: dict):
        try:
            return await self.backend.rpc(method, params, timeout=60)
        except ServeUnavailable:
            raise AdminError(m.AGENT_UNAVAILABLE, "Hermes's backend isn't reachable right now") from None
        except ServeError as exc:
            if "not found" in exc.message.lower():
                raise AdminError(m.NOT_FOUND, "Unknown bot") from None
            if "exists" in exc.message.lower():
                raise AdminError(m.CONFLICT, exc.message) from None
            raise AdminError(m.INVALID_PARAMS if 4000 <= exc.code < 5000 else m.AGENT_UNAVAILABLE, exc.message) from None

    def _profile(self, bot_id: object) -> str:
        profile = profile_of(bot_id) if isinstance(bot_id, str) else None
        if profile is None or profile == "default" or not self.bots.known(bot_id):
            raise AdminError(m.NOT_FOUND, "Unknown bot")
        return profile

    async def _model(self, model: str | None) -> None:
        if model is None:
            return
        allowed = await self.backend.allowed_models() if self.backend.allowed_models is not None else None
        if allowed is not None and model not in allowed:
            raise AdminError(m.INVALID_PARAMS, f"{model} isn't allowed by your OpenRouter guardrail")

    async def handle(self, method: str, p: dict) -> dict:
        if method == "bots.create":
            name = _text(p, "name", MAX_NAME, blank=False)
            about = _text(p, "about", MAX_ABOUT) or ""
            if name is None:
                raise AdminError(m.INVALID_PARAMS, "name is required")
            personality = _text(p, "personality", MAX_SOUL)
            model = _text(p, "model", 200, blank=False)
            await self._model(model)
            profile = slug(name)
            if self.bots.known(PREFIX + profile) or profile == "default":
                raise AdminError(m.CONFLICT, f"There's already a bot called {profile}")
            params = {"name": profile, "description": f"{name}{SEP}{about}" if about else name, "no_alias": True}
            if personality and personality.strip():
                params["soul"] = personality
            if model:
                params.update(model=model, provider=PROVIDER)
            await self._rpc("profiles.create", params)
            await self.bots.refresh()
            return {"bot_id": PREFIX + profile}

        profile = self._profile(p.get("bot_id"))
        if method == "bots.describe":
            d = await self._rpc("profiles.describe", {"name": profile})
            title, sep, rest = str(d.get("description") or "").partition(SEP)
            if not sep:
                title, rest = self.bots.name(PREFIX + profile), str(d.get("description") or "")
            model = (d.get("model") or {}).get("default") if isinstance(d.get("model"), dict) else None
            bot = {"bot_id": PREFIX + profile, "name": title.strip()[:MAX_NAME], "about": rest.strip(),
                   "personality": str(d.get("soul") or "")[:MAX_SOUL],
                   "skills": [{"name": str(s.get("name")), "enabled": bool(s.get("enabled"))}
                              for s in d.get("skills") or [] if isinstance(s, dict) and s.get("name")],
                   "toolsets": [{"name": str(t.get("name")), "label": str(t.get("label") or t.get("name")),
                                 "about": str(t.get("description") or ""), "enabled": bool(t.get("enabled"))}
                                for t in d.get("toolsets") or [] if isinstance(t, dict) and t.get("name")],
                   "connectors": [{"name": str(c.get("name")), "enabled": bool(c.get("enabled"))}
                                  for c in d.get("mcp_servers") or [] if isinstance(c, dict) and c.get("name")]}
            if isinstance(model, str) and model:
                bot["model"] = model
            return {"bot": bot}
        if method == "bots.update":
            change: dict = {"name": profile}
            name, about = _text(p, "name", MAX_NAME, blank=False), _text(p, "about", MAX_ABOUT)
            if name is not None or about is not None:
                now = await self._rpc("profiles.describe", {"name": profile})
                old_title, sep, old_rest = str(now.get("description") or "").partition(SEP)
                if not sep:
                    old_title, old_rest = self.bots.name(PREFIX + profile), str(now.get("description") or "")
                title, rest = name if name is not None else old_title.strip(), about if about is not None else old_rest.strip()
                change["description"] = f"{title}{SEP}{rest}" if rest else title
            personality = _text(p, "personality", MAX_SOUL)
            if personality is not None:
                change["soul"] = personality
            model = _text(p, "model", 200, blank=False)
            if model is not None:
                await self._model(model)
                change.update(model=model, provider=PROVIDER, confirm_expensive_model=p.get("confirm") is True)
            skills, toolsets, connectors = _names(p, "skills"), _names(p, "toolsets"), _names(p, "connectors")
            if skills is not None:
                installed = await self._rpc("profiles.describe", {"name": profile})
                on = set(skills)
                change["disabled_skills"] = [str(s["name"]) for s in installed.get("skills") or []
                                             if isinstance(s, dict) and s.get("name") and s["name"] not in on]
            if toolsets is not None:
                change["enabled_toolsets"] = toolsets
            if connectors is not None:
                change["enabled_mcp_servers"] = connectors
            if len(change) == 1:
                raise AdminError(m.INVALID_PARAMS, "Say what to change")
            done = await self._rpc("profiles.configure", change)
            applied = {k: bool(v) for k, v in (done.get("applied") or {}).items() if isinstance(v, bool)}
            out = {"applied": applied}
            if done.get("confirm_required"):
                out["confirm"] = str(done.get("confirm_message") or "This model is expensive. Use it anyway?")
            await self.bots.refresh()
            return out
        if method == "bots.picture":
            if p.get("clear") is True:
                await self._rpc("profiles.set_asset", {"name": profile, "asset": "avatar", "clear": True})
            else:
                data = p.get("data")
                if not (isinstance(data, str) and 0 < len(data) <= MAX_PICTURE_B64):
                    raise AdminError(m.INVALID_PARAMS, "data must be a picture of at most 512 KB, as base64")
                await self._rpc("profiles.set_asset", {"name": profile, "asset": "avatar", "data": data})
            await self.bots.refresh()
            return {}
        if method == "bots.delete":
            try:
                await self.backend.rest(f"/api/profiles/{quote(profile, safe='')}", "DELETE")
            except ServeUnavailable:
                raise AdminError(m.AGENT_UNAVAILABLE, "Hermes's backend isn't reachable right now") from None
            except ServeError as exc:
                raise AdminError(m.NOT_FOUND if exc.code == 404 else m.AGENT_UNAVAILABLE, exc.message) from None
            await self.bots.refresh()
            return {}
        raise AdminError(m.METHOD_NOT_FOUND, f"Method not found: {method}")
