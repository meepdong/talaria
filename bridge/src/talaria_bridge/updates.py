"""App updates (spec/README.md §17): the bridge offers devices the release the publisher placed for its channel.

The publisher (`apppublish.py`, run as root) writes `<folder>/<channel>/<platform>.json` and the signed installer
next to it. The bridge only reads them: it never holds the release key.
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import re
from collections.abc import Awaitable, Callable
from pathlib import Path

from .protocol import messages as m

log = logging.getLogger("talaria.updates")

CHANNELS = ("beta", "stable")
PLATFORMS = ("android",)
CHUNK = 512 * 1024
WATCH_S = 60
FILE_NAME = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(-beta\.[0-9]+)?$")
MAX_NOTES = 4000

UPDATE_METHODS = frozenset({"app.latest", "app.read"})


class UpdateError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code, self.message = code, message


class AppUpdates:
    def __init__(self, folder: Path, channel: str = "stable"):
        if channel not in CHANNELS:
            raise ValueError(f"channel must be one of {', '.join(CHANNELS)}")
        self.folder, self.channel = folder, channel
        self.broadcast: Callable[[dict], Awaitable[None]] | None = None

    def release(self, platform: str) -> tuple[dict, Path] | None:
        """The release placed for this channel, and its installer; None when there is none (or it's unreadable)."""
        meta = self.folder / self.channel / f"{platform}.json"
        try:
            data = json.loads(meta.read_text())
        except FileNotFoundError:
            return None
        except (OSError, ValueError) as exc:
            log.warning("can't read %s: %s", meta, exc)
            return None
        try:
            name = data["file"]
            release = {"version": data["version"], "version_code": data["version_code"], "size": data["size"],
                       "sha256": data["sha256"], "published_at": data["published_at"]}
            ok = (isinstance(name, str) and FILE_NAME.match(name) and isinstance(release["version"], str)
                  and VERSION.match(release["version"]) and SHA256.match(str(release["sha256"]))
                  and all(isinstance(release[k], int) and not isinstance(release[k], bool) and release[k] > 0
                          for k in ("version_code", "size", "published_at")))
        except (KeyError, TypeError):
            ok = False
        if not ok:
            log.warning("ignoring %s: not a release", meta)
            return None
        if isinstance(data.get("notes"), str) and data["notes"].strip():
            release["notes"] = data["notes"][:MAX_NOTES]
        return release, self.folder / self.channel / name

    def _platform(self, p: dict) -> str:
        platform = p.get("platform")
        if platform not in PLATFORMS:
            raise UpdateError(m.INVALID_PARAMS, f"platform must be one of {', '.join(PLATFORMS)}")
        return platform

    def latest(self, p: dict) -> dict:
        found = self.release(self._platform(p))
        return {"channel": self.channel, **({"release": found[0]} if found else {})}

    def read(self, p: dict) -> dict:
        found = self.release(self._platform(p))
        if found is None:
            raise UpdateError(m.NOT_FOUND, "There is no release on this channel")
        release, path = found
        code, offset = p.get("version_code"), p.get("offset", 0)
        if not (isinstance(code, int) and not isinstance(code, bool)):
            raise UpdateError(m.INVALID_PARAMS, "version_code must be an integer")
        if code != release["version_code"]:
            raise UpdateError(m.CONFLICT, "A newer release replaced that one; ask app.latest again")
        if not (isinstance(offset, int) and not isinstance(offset, bool) and 0 <= offset <= release["size"]):
            raise UpdateError(m.INVALID_PARAMS, "offset must be within the installer")
        try:
            with open(path, "rb") as f:
                f.seek(offset)
                data = f.read(CHUNK)
        except OSError:
            raise UpdateError(m.NOT_FOUND, "The bridge can't read that release") from None
        return {"size": release["size"], "offset": offset, "data": base64.b64encode(data).decode("ascii"),
                "eof": offset + len(data) >= release["size"]}

    def handle(self, method: str, p: dict) -> dict:
        if method == "app.latest":
            return self.latest(p)
        if method == "app.read":
            return self.read(p)
        raise UpdateError(m.METHOD_NOT_FOUND, f"Method not found: {method}")

    async def check(self, seen: dict[str, int]) -> None:
        """Tell every device about a release placed since the last check ([seen]: platform → version code)."""
        for platform in PLATFORMS:
            found = await asyncio.to_thread(self.release, platform)
            code = found[0]["version_code"] if found else 0
            if platform in seen and code > seen[platform] and found and self.broadcast is not None:
                log.info("new %s release %s on %s", platform, found[0]["version"], self.channel)
                await self.broadcast(m.notification("app.available", {
                    "platform": platform, "channel": self.channel, "release": found[0]}))
            seen[platform] = code

    async def watch(self, every_s: float = WATCH_S) -> None:
        seen: dict[str, int] = {}
        while True:
            try:
                await self.check(seen)
            except Exception:
                log.exception("checking for app releases failed")
            await asyncio.sleep(every_s)
