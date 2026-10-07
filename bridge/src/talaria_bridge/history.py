"""Every session Hermes has had, from every surface (§18.9): listed, searched, read, and carried on in Talaria.

Lists and transcripts come from `hermes serve`'s session addresses (the ones Hermes Desktop's history uses, any
profile). Carrying one on uses Hermes's API's fork, so the original (a Telegram chat, say) stays as it was and the
copy is a new Talaria conversation."""

from __future__ import annotations

import secrets
import time
from urllib.parse import quote, urlencode

from .board import ASSISTANT, BoardError, _why
from .bots import profile_of
from .hermes import HermesError, HermesUnavailable
from .hermes_serve import HermesBackend, ServeError, ServeUnavailable
from .protocol import messages as m

HISTORY_METHODS = frozenset({"history.list", "history.read", "history.continue"})
PAGE = 30
MESSAGES_PAGE = 80
MAX_TEXT = 8000
HIDDEN = ("talaria_aside_",)  # Talaria's throwaway side-question sessions


def _int(v: object) -> int | None:
    return int(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else None


def _text(content: object) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):  # content blocks
        return "\n".join(str(b.get("text", "")) for b in content if isinstance(b, dict) and b.get("type") in ("text", "input_text"))
    return ""


class History:
    def __init__(self, backend: HermesBackend, chat):
        self.backend = backend
        self.chat = chat  # ChatService: Talaria's conversations, the default agent's Hermes client

    async def _rest(self, path: str, method: str = "GET", body: dict | None = None):
        try:
            return await self.backend.rest(path, method, body)
        except ServeUnavailable:
            raise BoardError(m.AGENT_UNAVAILABLE, "Hermes's backend isn't reachable right now") from None
        except ServeError as exc:
            code = {404: m.NOT_FOUND, 400: m.INVALID_PARAMS, 422: m.INVALID_PARAMS}.get(exc.code)
            raise BoardError(code or m.AGENT_UNAVAILABLE, _why(exc)) from None

    def _profile(self, p: dict) -> str:
        bot = p.get("bot_id")
        if bot is None or bot == ASSISTANT:
            return "default"
        profile = profile_of(bot) if isinstance(bot, str) else None
        if profile is None or (self.chat.bots is not None and not self.chat.bots.known(bot)):
            raise BoardError(m.NOT_FOUND, "Unknown bot")
        return profile

    def _ours(self) -> dict[str, str]:
        """Hermes session id → Talaria conversation id."""
        return {c.hermes_session_id: c.id for c in self.chat.store.all()}

    @staticmethod
    def _offset(p: dict) -> int:
        offset = p.get("offset", 0)
        if not (isinstance(offset, int) and not isinstance(offset, bool) and 0 <= offset <= 100_000):
            raise BoardError(m.INVALID_PARAMS, "offset must be a whole number from 0")
        return offset

    async def handle(self, method: str, p: dict) -> dict:
        if method == "history.list":
            return await self.list(p)
        if method == "history.read":
            return await self.read(p)
        return await self.carry_on(p)

    async def list(self, p: dict) -> dict:
        profile, offset = self._profile(p), self._offset(p)
        query = p.get("query")
        ours = self._ours()
        if query is not None:
            if not (isinstance(query, str) and query.strip() and len(query) <= 200):
                raise BoardError(m.INVALID_PARAMS, "query must be 1 to 200 characters")
            hits = await self._rest("/api/sessions/search?" + urlencode({"q": query.strip(), "limit": 60, "profile": profile}))
            seen: dict[str, dict] = {}
            for h in (hits or {}).get("results") or []:
                sid = h.get("session_id") if isinstance(h, dict) else None
                if isinstance(sid, str) and sid not in seen and not sid.startswith(HIDDEN):
                    seen[sid] = h
            rows = []
            for sid, h in list(seen.items())[offset:offset + PAGE]:
                try:
                    info = await self._rest(f"/api/sessions/{quote(sid, safe='')}?" + urlencode({"profile": profile}))
                except BoardError:
                    info = {}
                info = info.get("session", info) if isinstance(info, dict) else {}
                rows.append(self._row({**info, "id": sid, "source": h.get("source") or info.get("source"),
                                       "started_at": info.get("started_at") or h.get("session_started"),
                                       "last_active": h.get("last_active") or info.get("last_active")}, ours,
                                      snippet=str(h.get("snippet") or "")[:500]))
            return {"sessions": rows, "has_more": len(seen) > offset + PAGE}
        got = await self._rest("/api/sessions?" + urlencode({"limit": PAGE + 1, "offset": offset, "order": "recent",
                                                             "profile": profile, "min_messages": 1}))
        listed = [s for s in (got or {}).get("sessions") or [] if isinstance(s, dict)
                  and isinstance(s.get("id"), str) and not s["id"].startswith(HIDDEN)]
        return {"sessions": [self._row(s, ours) for s in listed[:PAGE]], "has_more": len(listed) > PAGE}

    @staticmethod
    def _row(s: dict, ours: dict[str, str], snippet: str | None = None) -> dict:
        sid = str(s["id"])
        row = {"session_id": sid, "title": str(s.get("title") or "Untitled")[:200], "source": str(s.get("source") or ""),
               "started_at": _int(s.get("started_at")) or 0,
               "last_active": _int(s.get("last_active")) or _int(s.get("ended_at")),
               "messages": _int(s.get("message_count")) or 0}
        if snippet:
            row["snippet"] = snippet
        if sid in ours:
            row["conversation_id"] = ours[sid]
        return row

    async def read(self, p: dict) -> dict:
        profile, offset = self._profile(p), self._offset(p)
        sid = p.get("session_id")
        if not (isinstance(sid, str) and 0 < len(sid) <= 200):
            raise BoardError(m.INVALID_PARAMS, "session_id is required")
        got = await self._rest(f"/api/sessions/{quote(sid, safe='')}/messages?" + urlencode(
            {"profile": profile, "limit": MESSAGES_PAGE + 1, "offset": offset, "include_compacted": "false", "inline_images": "false"}))
        rows = [r for r in (got or {}).get("messages") or [] if isinstance(r, dict)]
        out = []
        for r in rows[:MESSAGES_PAGE]:
            text = _text(r.get("content")).strip()
            if r.get("role") in ("user", "assistant") and text:
                out.append({"role": r["role"], "text": text[:MAX_TEXT], "at": _int(r.get("timestamp")) or 0})
        return {"messages": out, "has_more": len(rows) > MESSAGES_PAGE}

    async def carry_on(self, p: dict) -> dict:
        sid = p.get("session_id")
        if not (isinstance(sid, str) and 0 < len(sid) <= 200):
            raise BoardError(m.INVALID_PARAMS, "session_id is required")
        found = next((c for c in self.chat.store.all() if c.hermes_session_id == sid), None)
        if found is not None:
            return {"conversation_id": found.id, "title": found.title}
        agent = self.chat.default_agent
        client = self.chat.agents.get(agent) if agent is not None else None
        if client is None:
            raise BoardError(m.AGENT_UNAVAILABLE, "No chat agent is configured on the bridge")
        from .chat import MAX_TITLE, Conversation  # here: chat.py imports this module

        info = await self._rest(f"/api/sessions/{quote(sid, safe='')}?" + urlencode({"profile": "default"}))
        info = info.get("session", info) if isinstance(info, dict) else {}
        base = str(info.get("title") or "Earlier chat")[:MAX_TITLE - 20]
        token = secrets.token_hex(8)
        for title in (f"{base} (continued)", f"{base} (continued {token[:4]})"):  # Hermes keeps titles unique
            try:
                await client._call("POST", f"/api/sessions/{quote(sid, safe='')}/fork", json={"id": f"talaria_{token}", "title": title})
                break
            except HermesError as exc:
                if "already in use" in exc.message and not title.endswith(f"{token[:4]})"):
                    continue
                raise BoardError(m.NOT_FOUND if exc.status == 404 else m.AGENT_UNAVAILABLE, f"Hermes couldn't copy it: {exc.message}") from None
            except HermesUnavailable as exc:
                raise BoardError(m.AGENT_UNAVAILABLE, f"Hermes isn't reachable: {exc}") from None
        now_s = int(time.time())
        conv = Conversation(id=f"c-{token}", agent_id=agent, hermes_session_id=f"talaria_{token}", title=title,
                            created_at=now_s, updated_at=now_s)
        self.chat.store.add(conv)
        return {"conversation_id": conv.id, "title": title}
