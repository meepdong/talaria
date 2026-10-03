"""Files on the server (spec/README.md §12): read-only browsing of the agent's folders.

Each agent can share folders in `agents.json`:

    "files": [{"id": "workspace", "name": "Hermes workspace", "path": "/home/hermes/projects",
               "agent_path": "/workspace/projects"}]

`agent_path` is where the agent sees that folder (Hermes's Docker sandbox mounts it
elsewhere); it defaults to `path`. The inbox (§10) is added as the root `inbox`.
"""

from __future__ import annotations

import base64
import mimetypes
import os
import re
import stat
from dataclasses import dataclass
from pathlib import Path

from .protocol import messages as m

CHUNK = 512 * 1024
MAX_READ = 20 * 1024 * 1024
MAX_ENTRIES = 500
MAX_SCANNED = 20_000  # a search gives up after this many folder entries
INBOX = "inbox"


class FilesError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass(frozen=True)
class FileRoot:
    id: str
    name: str
    path: Path
    agent_path: str


@dataclass(frozen=True)
class Found:
    """A file inside a root, ready to name to the agent."""
    root: FileRoot
    rel: str
    real: Path
    size: int
    mime: str

    @property
    def name(self) -> str:
        return re.sub(r"^b-[0-9a-f]{24}-", "", self.real.name) or self.real.name

    @property
    def agent_path(self) -> str:
        return self.root.agent_path.rstrip("/") + "/" + self.rel


def _mime(name: str) -> str:
    return mimetypes.guess_type(name)[0] or "application/octet-stream"


def _parts(rel) -> list[str]:
    if rel is None:
        return []
    if not isinstance(rel, str) or len(rel) > 4096 or "\\" in rel or "\x00" in rel:
        raise FilesError(m.INVALID_PARAMS, "path must be a folder path inside the root, with /")
    parts = [p for p in rel.split("/") if p not in ("", ".")]
    if any(p == ".." for p in parts):
        raise FilesError(m.INVALID_PARAMS, "That path leads outside the folder")
    if any(p.startswith(".") for p in parts):
        raise FilesError(m.NOT_FOUND, "No such file or folder")
    return parts


def _inside(real: str, base: str) -> bool:
    return real == base or real.startswith(base.rstrip(os.sep) + os.sep)


class FilesService:
    def __init__(self, roots: dict[str, list[FileRoot]], default_agent: str | None = None):
        self._roots = roots  # agent_id -> its roots
        self.default_agent = default_agent or next(iter(roots), None)

    def _agent(self, p: dict) -> str:
        agent_id = p.get("agent_id", self.default_agent)
        if agent_id is None or agent_id not in self._roots:
            raise FilesError(m.NOT_FOUND, "That agent shares no folders")
        return agent_id

    def _root(self, agent_id: str, root_id) -> FileRoot:
        for root in self._roots.get(agent_id, []):
            if root.id == root_id:
                return root
        raise FilesError(m.NOT_FOUND, "Unknown folder")

    def _resolve(self, root: FileRoot, rel) -> tuple[list[str], Path]:
        parts = _parts(rel)
        base = os.path.realpath(root.path)
        real = os.path.realpath(root.path.joinpath(*parts))
        if not _inside(real, base):
            raise FilesError(m.INVALID_PARAMS, "That path leads outside the folder")
        if not os.path.exists(real):
            raise FilesError(m.NOT_FOUND, "No such file or folder")
        return parts, Path(real)

    # files.roots

    def roots(self, p: dict) -> dict:
        agent_id = p.get("agent_id", self.default_agent)
        out = []
        for root in self._roots.get(agent_id, []) if agent_id is not None else []:
            item = {"id": root.id, "name": root.name}
            if not os.access(root.path, os.R_OK | os.X_OK):
                item["error"] = "The bridge can't read this folder"
            out.append(item)
        return {"agent_id": agent_id or "", "roots": out}

    # files.list

    def list(self, p: dict) -> dict:
        agent_id = self._agent(p)
        root = self._root(agent_id, p.get("root"))
        parts, folder = self._resolve(root, p.get("path"))
        if not folder.is_dir():
            raise FilesError(m.INVALID_PARAMS, "That is a file, not a folder")
        query = p.get("query")
        if query is not None and not (isinstance(query, str) and 0 < len(query) <= 200):
            raise FilesError(m.INVALID_PARAMS, "query must be 1 to 200 characters")
        base = os.path.realpath(root.path)
        try:
            entries, truncated = (self._search(folder, parts, base, query.lower()) if query
                                  else self._folder(folder, parts, base))
        except PermissionError:
            raise FilesError(m.NOT_FOUND, "The bridge can't read that folder") from None
        entries.sort(key=lambda e: (e["kind"] != "folder", -e["modified"], e["name"].lower()))
        if len(entries) > MAX_ENTRIES:
            entries, truncated = entries[:MAX_ENTRIES], True
        return {"root": root.id, "path": "/".join(parts), "entries": entries, "truncated": truncated}

    @staticmethod
    def _entry(path: Path, rel: list[str], base: str) -> dict | None:
        """One listing row, or None for what isn't shown (hidden, outside the root, unreadable)."""
        if path.name.startswith("."):
            return None
        real = os.path.realpath(path)
        if not _inside(real, base):
            return None
        try:
            st = os.stat(real)
        except OSError:
            return None
        # files sent from Talaria are saved as b-<blob id>-<name> (§10): show the name
        entry = {"name": re.sub(r"^b-[0-9a-f]{24}-", "", path.name) or path.name,
                 "path": "/".join([*rel, path.name]), "modified": int(st.st_mtime)}
        if stat.S_ISDIR(st.st_mode):
            entry["kind"] = "folder"
        elif stat.S_ISREG(st.st_mode):
            entry.update(kind="file", size=st.st_size, mime=_mime(path.name))
        else:
            return None
        return entry

    def _folder(self, folder: Path, parts: list[str], base: str) -> tuple[list[dict], bool]:
        out = []
        with os.scandir(folder) as it:
            for item in it:
                entry = self._entry(Path(item.path), parts, base)
                if entry is not None:
                    out.append(entry)
        return out, False

    def _search(self, folder: Path, parts: list[str], base: str, query: str) -> tuple[list[dict], bool]:
        out, scanned = [], 0
        for here, dirs, files in os.walk(folder, followlinks=False):
            dirs[:] = [d for d in dirs if not d.startswith(".")]
            rel = parts + list(Path(here).relative_to(folder).parts)
            for name in files:
                scanned += 1
                if scanned > MAX_SCANNED:
                    return out, True
                if query in name.lower():
                    entry = self._entry(Path(here) / name, rel, base)
                    if entry is not None:
                        out.append(entry)
            scanned += len(dirs)
        return out, False

    # files.read

    def file(self, agent_id: str | None, root_id, rel) -> Found:
        """A file the agent can be pointed at (files.read, and chat.send's `files`)."""
        agent_id = self._agent({} if agent_id is None else {"agent_id": agent_id})
        root = self._root(agent_id, root_id)
        parts, real = self._resolve(root, rel)
        if not parts or not real.is_file():
            raise FilesError(m.INVALID_PARAMS, "That is a folder, not a file")
        size = real.stat().st_size
        if size > MAX_READ:
            raise FilesError(m.INVALID_PARAMS, "That file is over 20 MB")
        return Found(root, "/".join(parts), real, size, _mime(real.name))

    def read(self, p: dict) -> dict:
        found = self.file(p.get("agent_id"), p.get("root"), p.get("path"))
        offset = p.get("offset", 0)
        if not (isinstance(offset, int) and not isinstance(offset, bool) and 0 <= offset <= found.size):
            raise FilesError(m.INVALID_PARAMS, "offset must be within the file")
        try:
            with open(found.real, "rb") as f:
                f.seek(offset)
                data = f.read(CHUNK)
        except OSError:
            raise FilesError(m.NOT_FOUND, "The bridge can't read that file") from None
        return {"size": found.size, "mime": found.mime, "offset": offset,
                "data": base64.b64encode(data).decode("ascii"), "eof": offset + len(data) >= found.size}

    def handle(self, method: str, p: dict) -> dict:
        if method == "files.roots":
            return self.roots(p)
        if method == "files.list":
            return self.list(p)
        if method == "files.read":
            return self.read(p)
        raise FilesError(m.METHOD_NOT_FOUND, f"Method not found: {method}")


FILES_METHODS = frozenset({"files.roots", "files.list", "files.read"})
