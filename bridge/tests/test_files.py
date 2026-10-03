import base64
import os
from pathlib import Path

import pytest

from talaria_bridge.agents import load_agents
from talaria_bridge.cli import file_roots
from talaria_bridge.files import CHUNK, FileRoot, FilesError, FilesService
from talaria_bridge.protocol import messages as m

from conftest import check
from test_chat import call, chat_bridge, connected, until_done  # noqa: F401 (chat_bridge is a fixture)


def tree(tmp_path: Path) -> Path:
    ws = tmp_path / "projects"
    (ws / "notes").mkdir(parents=True)
    (ws / "notes" / "catch-up transcript.txt").write_text("decisions")
    (ws / "report.md").write_text("# Q3")
    (ws / ".secret").write_text("no")
    (ws / ".git").mkdir()
    (ws / ".git" / "transcript-config").write_text("no")
    os.utime(ws / "report.md", (2_000_000_000, 2_000_000_000))
    outside = tmp_path / "outside.txt"
    outside.write_text("private")
    (ws / "escape.txt").symlink_to(outside)
    (ws / "link-in").symlink_to(ws / "notes")
    return ws


def service(tmp_path: Path) -> FilesService:
    inbox = tmp_path / "inbox"
    (inbox / "c-1").mkdir(parents=True)
    (inbox / "c-1" / "b-0123456789abcdef01234567-Q3 report.pdf").write_bytes(b"%PDF")
    return FilesService({"hermes": [
        FileRoot("inbox", "Sent from Talaria", inbox, str(inbox)),
        FileRoot("workspace", "Hermes workspace", tree(tmp_path), "/workspace/projects"),
    ]})


def test_roots_list_and_search(tmp_path: Path):
    files = service(tmp_path)
    assert files.roots({}) == {"agent_id": "hermes", "roots": [
        {"id": "inbox", "name": "Sent from Talaria"}, {"id": "workspace", "name": "Hermes workspace"}]}

    top = files.list({"root": "workspace"})
    assert top["path"] == "" and top["truncated"] is False
    assert [(e["name"], e["kind"]) for e in top["entries"]] == [
        ("link-in", "folder"), ("notes", "folder"), ("report.md", "file")], "hidden and outside links are left out"
    report = top["entries"][2]
    assert report == {"name": "report.md", "path": "report.md", "kind": "file", "size": 4,
                      "mime": "text/markdown", "modified": 2_000_000_000}

    notes = files.list({"root": "workspace", "path": "notes/"})
    assert [e["path"] for e in notes["entries"]] == ["notes/catch-up transcript.txt"]

    found = files.list({"root": "workspace", "query": "TRANSCRIPT"})
    assert [e["path"] for e in found["entries"]] == ["notes/catch-up transcript.txt"]

    inbox = files.list({"root": "inbox", "path": "c-1"})
    assert [(e["name"], e["path"]) for e in inbox["entries"]] == [
        ("Q3 report.pdf", "c-1/b-0123456789abcdef01234567-Q3 report.pdf")]


@pytest.mark.parametrize("params, code", [
    ({"root": "nope"}, m.NOT_FOUND),
    ({"root": "workspace", "path": "../outside.txt"}, m.INVALID_PARAMS),
    ({"root": "workspace", "path": "escape.txt"}, m.INVALID_PARAMS),
    ({"root": "workspace", "path": ".git"}, m.NOT_FOUND),
    ({"root": "workspace", "path": "missing"}, m.NOT_FOUND),
    ({"root": "workspace", "path": "report.md"}, m.INVALID_PARAMS),
    ({"root": "workspace", "path": "a\\b"}, m.INVALID_PARAMS),
])
def test_list_refuses_paths_outside_or_hidden(tmp_path: Path, params: dict, code: int):
    with pytest.raises(FilesError) as err:
        service(tmp_path).list(params)
    assert err.value.code == code


def test_read_in_chunks(tmp_path: Path):
    files = service(tmp_path)
    big = tmp_path / "projects" / "big.bin"
    big.write_bytes(os.urandom(CHUNK + 10))
    first = files.read({"root": "workspace", "path": "big.bin"})
    assert first["size"] == CHUNK + 10 and first["eof"] is False and first["mime"] == "application/octet-stream"
    data = base64.b64decode(first["data"])
    rest = files.read({"root": "workspace", "path": "big.bin", "offset": len(data)})
    assert rest["eof"] is True
    assert data + base64.b64decode(rest["data"]) == big.read_bytes()
    with pytest.raises(FilesError):
        files.read({"root": "workspace", "path": "notes"})
    with pytest.raises(FilesError):
        files.read({"root": "workspace", "path": "escape.txt"})


def test_shared_folders_in_agents_json(tmp_path: Path):
    path = tmp_path / "agents.json"
    path.write_text('{"agents": [{"id": "hermes", "health_url": "http://x/health", "inbox_dir": "/var/lib/talaria/inbox",'
                    ' "files": [{"id": "workspace", "name": "Hermes workspace", "path": "/home/hermes/projects",'
                    ' "agent_path": "/workspace/projects"}]}]}')
    roots = file_roots(load_agents(path)[0])
    assert [(r.id, r.agent_path) for r in roots] == [
        ("inbox", "/var/lib/talaria/inbox"), ("workspace", "/workspace/projects")]
    path.write_text('{"agents": [{"id": "hermes", "health_url": "http://x/health", "files": [{"id": "inbox", "path": "/x"}]}]}')
    with pytest.raises(ValueError):
        load_agents(path)


async def test_browse_and_ask_about_a_server_file(chat_bridge, tmp_path: Path):  # noqa: F811
    bridge, hermes = chat_bridge
    bridge.server.chat.files = service(tmp_path)
    ws = await connected(bridge)
    roots = check("files.roots.result", await call(ws, "r1", "files.roots"))["result"]
    assert [r["id"] for r in roots["roots"]] == ["inbox", "workspace"]
    listed = check("files.list.result", await call(ws, "l1", "files.list", {"root": "workspace", "path": "notes"}))
    path = listed["result"]["entries"][0]["path"]
    read = check("files.read.result", await call(ws, "f1", "files.read", {"root": "workspace", "path": path}))
    assert base64.b64decode(read["result"]["data"]) == b"decisions"
    bad = await call(ws, "f2", "files.read", {"root": "workspace", "path": "../outside.txt"})
    assert bad["error"]["code"] == m.INVALID_PARAMS

    res = check("chat.send.result", await call(ws, "s1", "chat.send", {
        "text": "What was decided?", "files": [{"root": "workspace", "path": path}]}))["result"]
    events = await until_done(ws, res["turn_id"])
    assert events[0]["params"]["attachments"] == [
        {"kind": "file", "name": "catch-up transcript.txt", "mime": "text/plain", "size": 9}]
    assert hermes.messages[-1] == ("What was decided?\n\nAttached file: /workspace/projects/notes/catch-up transcript.txt"
                                   " (text/plain, 9 bytes)")
    history = (await call(ws, "h1", "chat.history", {"conversation_id": res["conversation_id"]}))["result"]
    assert history["messages"][0]["text"] == "What was decided?"
    assert history["messages"][0]["attachments"][0]["name"] == "catch-up transcript.txt"
    await ws.close()
