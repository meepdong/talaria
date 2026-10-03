"""Client for the Hermes API server's Sessions API, which the chat proxy uses (spec/README.md §9).

    POST   /api/sessions                      create a session (one per Talaria conversation)
    POST   /api/sessions/{id}/chat/stream     one turn, streamed back as server-sent events
    POST   /v1/runs/{run_id}/stop             stop a running turn
    POST   /v1/runs/{run_id}/steer            a note for a running turn (§11)
    GET    /api/model/options                 the models the agent can use (§11)
    POST   /api/sessions/{id}/model           pin a session to a model (§11)
    GET    /api/sessions/{id}                 session info, for chat.status (§11)
    GET    /api/sessions/{id}/messages        history
    PATCH  /api/sessions/{id}                 rename
    DELETE /api/sessions/{id}                 delete

Every call carries the agent's API key. Devices never see it.
"""

from __future__ import annotations

import json
import logging
from collections.abc import AsyncIterator
from pathlib import Path

import httpx

log = logging.getLogger("talaria.hermes")

# Hermes sends a keepalive comment every 10 s while a tool runs, so a minute of silence is a failure.
STREAM_TIMEOUT = httpx.Timeout(connect=10, read=60, write=30, pool=10)
CALL_TIMEOUT = httpx.Timeout(15)


class HermesError(Exception):
    """Hermes answered with an error status."""

    def __init__(self, status: int, code: str, message: str):
        super().__init__(f"HTTP {status} {code}: {message}")
        self.status = status
        self.code = code
        self.message = message


class HermesUnavailable(Exception):
    """Hermes could not be reached."""


def read_api_key(path: Path) -> str:
    key = path.read_text(encoding="utf-8").strip()
    if not key:
        raise ValueError(f"{path} is empty")
    return key


class HermesClient:
    def __init__(self, base_url: str, api_key: str, *, transport: httpx.AsyncBaseTransport | None = None):
        self.base_url = base_url.rstrip("/")
        self._http = httpx.AsyncClient(
            base_url=self.base_url, transport=transport, timeout=CALL_TIMEOUT,
            headers={"Authorization": f"Bearer {api_key}", "User-Agent": "talaria-bridge"})

    async def close(self) -> None:
        await self._http.aclose()

    async def _call(self, method: str, path: str, **kwargs) -> dict:
        try:
            resp = await self._http.request(method, path, **kwargs)
        except httpx.HTTPError as exc:
            raise HermesUnavailable(f"{type(exc).__name__}: {exc}") from exc
        if resp.status_code >= 400:
            raise _error_from(resp.status_code, resp.content)
        try:
            data = resp.json()
        except ValueError:
            raise HermesError(resp.status_code, "bad_response", "response is not JSON") from None
        if not isinstance(data, dict):
            raise HermesError(resp.status_code, "bad_response", "response is not an object")
        return data

    async def create_session(self, session_id: str, title: str | None) -> dict:
        body: dict = {"id": session_id}
        if title:
            body["title"] = title
        try:
            data = await self._call("POST", "/api/sessions", json=body)
        except HermesError as exc:
            if exc.code != "invalid_title" or not title:
                raise
            # Hermes keeps titles unique and may refuse one; the session is what matters.
            data = await self._call("POST", "/api/sessions", json={"id": session_id})
        return data.get("session") or {}

    async def rename_session(self, session_id: str, title: str) -> None:
        await self._call("PATCH", f"/api/sessions/{session_id}", json={"title": title})

    async def delete_session(self, session_id: str) -> None:
        try:
            await self._call("DELETE", f"/api/sessions/{session_id}")
        except HermesError as exc:
            if exc.status != 404:
                raise

    async def messages(self, session_id: str, *, limit: int, offset: int) -> list[dict]:
        """One page of the session's messages, newest page first (Hermes `order=latest`)."""
        data = await self._call("GET", f"/api/sessions/{session_id}/messages", params={
            "order": "latest", "limit": limit, "offset": offset, "inline_images": "false"})
        rows = data.get("data")
        return [r for r in rows if isinstance(r, dict)] if isinstance(rows, list) else []

    async def stop_run(self, run_id: str) -> None:
        await self._call("POST", f"/v1/runs/{run_id}/stop", json={})

    async def steer_run(self, run_id: str, text: str) -> bool:
        """False when the run no longer takes notes (it was finishing)."""
        try:
            await self._call("POST", f"/v1/runs/{run_id}/steer", json={"input": text})
        except HermesError as exc:
            if exc.status == 409:
                return False
            raise
        return True

    async def model_options(self) -> dict:
        return await self._call("GET", "/api/model/options")

    async def set_session_model(self, session_id: str, provider: str, model: str) -> None:
        await self._call("POST", f"/api/sessions/{session_id}/model", json={"provider": provider, "model": model})

    async def session(self, session_id: str) -> dict:
        data = await self._call("GET", f"/api/sessions/{session_id}")
        info = data.get("session", data)
        return info if isinstance(info, dict) else {}

    async def chat_stream(self, session_id: str, text: str | list) -> AsyncIterator[tuple[str, dict]]:
        """Run one turn and yield its events as (name, payload) until the stream ends.
        `text` is the message: a string, or text and input_image parts."""
        try:
            async with self._http.stream("POST", f"/api/sessions/{session_id}/chat/stream",
                                         json={"message": text}, timeout=STREAM_TIMEOUT) as resp:
                if resp.status_code >= 400:
                    raise _error_from(resp.status_code, await resp.aread())
                async for event in parse_sse(resp.aiter_lines()):
                    yield event
        except httpx.HTTPError as exc:
            raise HermesUnavailable(f"{type(exc).__name__}: {exc}") from exc


async def parse_sse(lines: AsyncIterator[str]) -> AsyncIterator[tuple[str, dict]]:
    """Server-sent events: `event:` and `data:` lines, a blank line ends each event, and lines
    starting with `:` are keepalive comments. Events whose data isn't a JSON object are skipped."""
    name, data = "message", []
    async for line in lines:
        if line == "":
            if data:
                try:
                    payload = json.loads("\n".join(data))
                except ValueError:
                    payload = None
                if isinstance(payload, dict):
                    yield name, payload
                else:
                    log.debug("skipping SSE event %s with non-object data", name)
            name, data = "message", []
        elif line.startswith(":"):
            continue
        else:
            field, _, value = line.partition(":")
            value = value[1:] if value.startswith(" ") else value
            if field == "event":
                name = value
            elif field == "data":
                data.append(value)


def _error_from(status: int, body: bytes) -> HermesError:
    code, message = "http_error", body[:200].decode("utf-8", "replace")
    try:
        info = json.loads(body)
    except ValueError:
        info = None
    if isinstance(info, dict):
        err = info.get("error", info)
        if isinstance(err, dict):
            code = str(err.get("code") or err.get("type") or code)
            message = str(err.get("message") or message)
        elif isinstance(err, str):
            message = err
    return HermesError(status, code, message)
