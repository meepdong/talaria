"""Provider account balances for account.balance (spec/README.md §11).

Only OpenRouter for now. Its credits API needs a management key, which can also create and
delete API keys, so the key stays on the bridge: devices only get the number.
"""

from __future__ import annotations

import logging

import httpx

log = logging.getLogger("talaria.accounts")

OPENROUTER_CREDITS = "https://openrouter.ai/api/v1/credits"
OPENROUTER_TOP_UP = "https://openrouter.ai/settings/credits"


class OpenRouterAccount:
    provider = "openrouter"
    name = "OpenRouter"

    def __init__(self, management_key: str, *, transport: httpx.AsyncBaseTransport | None = None):
        self._http = httpx.AsyncClient(transport=transport, timeout=httpx.Timeout(10),
                                       headers={"Authorization": f"Bearer {management_key}",
                                                "User-Agent": "talaria-bridge"})

    async def close(self) -> None:
        await self._http.aclose()

    async def balance(self) -> dict:
        entry = {"provider": self.provider, "name": self.name, "top_up_url": OPENROUTER_TOP_UP}
        try:
            resp = await self._http.get(OPENROUTER_CREDITS)
            if resp.status_code in (401, 403):
                return {**entry, "error": "OpenRouter refused the key; it must be a management key"}
            resp.raise_for_status()
            data = resp.json().get("data") or {}
            total, used = data.get("total_credits"), data.get("total_usage")
            if not all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in (total, used)):
                return {**entry, "error": "OpenRouter sent an unexpected answer"}
            return {**entry, "remaining": round(total - used, 2), "currency": "USD"}
        except (httpx.HTTPError, ValueError, AttributeError) as exc:
            log.warning("OpenRouter balance failed: %s", exc)
            return {**entry, "error": "OpenRouter can't be reached right now"}
