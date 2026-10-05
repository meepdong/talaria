"""send_file (spec §15, §9 chat.file): the agent sends the owner a file it made, into the chat it's replying in."""

import asyncio
import json
from pathlib import Path

import pytest
from conftest import check

from talaria_bridge.agent_tools import AgentTools
from talaria_bridge.chat import ChatService, ChatStore, with_files
from talaria_bridge.files import INBOX, FileRoot, FilesService
from talaria_bridge.hermes import HermesClient
from talaria_bridge.protocol import messages as m
from talaria_bridge.todos import TodoStore

from test_chat import KEY, FakeHermes


@pytest.fixture
def service(tmp_path: Path):
    hermes = FakeHermes()
    sent: list[dict] = []

    async def broadcast(msg: dict) -> None:
        sent.append(msg)

    (tmp_path / "ws").mkdir()
    (tmp_path / "inbox").mkdir()
    files = FilesService({"hermes": [FileRoot(INBOX, "Sent from Talaria", tmp_path / "inbox", str(tmp_path / "inbox")),
                                     FileRoot("workspace", "Hermes workspace", tmp_path / "ws", "/workspace/projects")]})
    chat = ChatService(ChatStore(tmp_path / "chat.db"), {"hermes": HermesClient("http://hermes.test", KEY, transport=hermes.transport())},
                       broadcast, files=files, todos=TodoStore(tmp_path / "chat.db"))

    async def changed() -> None:
        pass

    tools = AgentTools(chat.todos, {"t" * 40: "hermes"}, changed, chat=chat)
    yield chat, tools, hermes, sent
    chat.store.close()


def result(r: dict) -> tuple[object, bool]:
    text = r["content"][0]["text"]
    try:
        return json.loads(text), r.get("isError", False)
    except ValueError:
        return text, r.get("isError", False)


async def test_a_file_lands_in_the_chat_being_replied_in(service, tmp_path: Path):
    chat, tools, hermes, sent = service
    assert "send_file" in {t["name"] for t in tools.tools}
    (tmp_path / "ws" / "photos_signed.pdf").write_bytes(b"%PDF signed")

    nothing, is_error = result(await tools.call("send_file", {"path": "/workspace/projects/photos_signed.pdf"}, "hermes"))
    assert is_error and "nowhere to send" in nothing

    hermes.hold = True  # a reply is running in this conversation
    res, turn = await chat.handle("chat.send", {"text": "Put my signature on the PDF"})
    chat.start(turn)
    await asyncio.sleep(0.05)
    done, is_error = result(await tools.call("send_file", {"path": "/workspace/projects/photos_signed.pdf",
                                                           "caption": "Signed PDF"}, "hermes"))
    assert not is_error and done == {"conversation_id": res["conversation_id"], "name": "photos_signed.pdf", "size": 11}
    note = check("chat.file", next(x for x in sent if x["method"] == "chat.file"))["params"]
    assert note["conversation_id"] == res["conversation_id"]
    assert note["message"]["text"] == "Signed PDF" and note["message"]["role"] == "assistant"
    assert note["message"]["attachments"] == [{"kind": "file", "name": "photos_signed.pdf", "mime": "application/pdf",
                                               "size": 11, "root": "workspace", "path": "photos_signed.pdf"}]
    hermes.release.set()
    await turn.task

    # the file is in the history, at its time, and opens with files.read
    history = check("chat.history.result", m.result("1", await chat.history({"conversation_id": res["conversation_id"]})))
    msgs = history["result"]["messages"]
    files = [x for x in msgs if x["id"].startswith("f-")]
    assert len(files) == 1 and len(msgs) == 3, "the user's message, the reply and the file, once"
    att = files[0]["attachments"][0]
    assert chat.files.read({"root": att["root"], "path": att["path"]})["data"]

    # an inbox file works too (the real path), and an image is an image
    (tmp_path / "inbox" / "c-1").mkdir()
    (tmp_path / "inbox" / "c-1" / "b-0123456789abcdef01234567-sig.png").write_bytes(b"\x89PNG")
    img, is_error = result(await tools.call("send_file", {"path": str(tmp_path / "inbox" / "c-1" / "b-0123456789abcdef01234567-sig.png")}, "hermes"))
    assert not is_error and img["name"] == "sig.png"
    assert [x for x in sent if x["method"] == "chat.file"][-1]["params"]["message"]["attachments"][0]["kind"] == "image"


async def test_only_files_in_shared_folders(service, tmp_path: Path):
    chat, tools, hermes, _ = service
    res, turn = await chat.handle("chat.send", {"text": "hi"})
    chat.start(turn)
    await turn.task
    (tmp_path / "ws" / ".env").write_text("SECRET=1")
    (tmp_path / "secret.txt").write_text("x")
    (tmp_path / "ws" / "big.bin").write_bytes(b"")
    with open(tmp_path / "ws" / "big.bin", "r+b") as f:
        f.truncate(2 * 1024 * 1024 * 1024 + 1)  # sparse: nothing is written
    (tmp_path / "ws" / "escape").symlink_to(tmp_path / "secret.txt")
    for path, why in [("/etc/shadow", "isn't in a folder"), ("/workspace/projects/.env", "No such file"),
                      ("/workspace/projects/../secret.txt", "outside"), ("/workspace/projects/escape", "outside"),
                      ("/workspace/projects/big.bin", "over 2 GB"), ("/workspace/projects/missing.pdf", "No such file"),
                      ("workspace/projects/x", "full path"), (None, "full path")]:
        text, is_error = result(await tools.call("send_file", {"path": path}, "hermes"))
        assert is_error and why in text, (path, text)
    text, is_error = result(await tools.call("send_file", {"path": "/workspace/projects/big.bin", "caption": "x" * 1001}, "hermes"))
    assert is_error and "caption" in text


def test_history_pages_show_each_file_once():
    def msg(i, ts): return {"id": str(i), "role": "user", "text": "", "ts": ts}
    files = [{"id": f"f-{n}", "role": "assistant", "text": "", "ts": ts, "attachments": []} for n, ts in enumerate([5, 15, 25, 99])]
    older = [msg(1, 1), msg(2, 10)]       # page 2: ts 1..10
    newer = [msg(3, 20), msg(4, 30)]      # page 1 (newest): ts 20..30
    on_newer = [x["id"] for x in with_files(newer, files, upper=None, oldest=False) if x["id"].startswith("f-")]
    on_older = [x["id"] for x in with_files(older, files, upper=20, oldest=True) if x["id"].startswith("f-")]
    assert on_newer == ["f-2", "f-3"] and on_older == ["f-0", "f-1"], "15 sits between the pages: the older one has it"
    assert sorted(on_older + on_newer) == ["f-0", "f-1", "f-2", "f-3"], "every file on exactly one page"
