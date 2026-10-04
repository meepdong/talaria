from pathlib import Path

import pytest

import json
import sqlite3

import talaria_bridge.chat
from talaria_bridge.protocol import messages as m
from talaria_bridge.todo_groups import GroupingError, parse_groups
from talaria_bridge.todos import DONE_SHOWN, MAX_COMMENTS, TodoError, TodoStore

from conftest import check
from test_chat import call, chat_bridge, connected, recv, until_done  # noqa: F401 (chat_bridge is a fixture)


def test_list_order_and_changes(tmp_path: Path):
    todos = TodoStore(tmp_path / "chat.db")
    a = todos.add({"text": "  Book flights  "})
    b = todos.add({"text": "Call the bank", "due": "2026-10-05"})
    assert a["text"] == "Book flights" and a["done"] is False and "due" not in a
    assert b["due"] == "2026-10-05"

    done = todos.update({"id": a["id"], "done": True})
    assert done["done"] is True and done["done_at"] >= done["created_at"]
    assert [t["id"] for t in todos.list()] == [b["id"], a["id"]], "open first, then done"

    cleared = todos.update({"id": b["id"], "due": None, "text": "Call the bank about the card"})
    assert "due" not in cleared and cleared["text"] == "Call the bank about the card"
    reopened = todos.update({"id": a["id"], "done": False})
    assert reopened["done"] is False and "done_at" not in reopened

    assert todos.delete({"id": a["id"]}) == {"id": a["id"], "deleted": True}
    assert [t["id"] for t in todos.list()] == [b["id"]]


def test_only_recent_done_are_kept(tmp_path: Path):
    todos = TodoStore(tmp_path / "chat.db")
    for i in range(DONE_SHOWN + 5):
        todos.update({"id": todos.add({"text": f"t{i}"})["id"], "done": True})
    todos.add({"text": "open"})
    assert len(todos.list()) == DONE_SHOWN + 1


@pytest.mark.parametrize("method, params, code", [
    ("todos.add", {"text": "   "}, m.INVALID_PARAMS),
    ("todos.add", {"text": "x" * 501}, m.INVALID_PARAMS),
    ("todos.add", {"text": "x", "due": "2026-02-30"}, m.INVALID_PARAMS),
    ("todos.update", {"id": "td-nope"}, m.NOT_FOUND),
    ("todos.delete", {"id": ""}, m.INVALID_PARAMS),
    ("todos.add", {"text": "x", "group": "g" * 41}, m.INVALID_PARAMS),
    ("todos.comment", {"id": "td-nope", "text": "hi"}, m.NOT_FOUND),
])
def test_bad_requests(tmp_path: Path, method: str, params: dict, code: int):
    with pytest.raises(TodoError) as err:
        TodoStore(tmp_path / "chat.db").handle(method, params)
    assert err.value.code == code


async def next_changed(ws) -> dict:
    while True:
        msg = await recv(ws)
        if msg.get("method") == "todos.changed":
            return check("todos.changed", msg)["params"]


async def test_every_device_sees_changes_and_handing_one_to_hermes(chat_bridge):  # noqa: F811
    bridge, _ = chat_bridge
    phone, laptop = await connected(bridge), await connected(bridge)
    added = check("todos.result", await call(phone, "a1", "todos.add", {"text": "Summarise the Q3 report"}))
    todo = added["result"]["todo"]
    assert (await next_changed(laptop))["todos"] == [todo]

    res = check("chat.send.result", await call(phone, "s1", "chat.send", {"text": todo["text"], "todo_id": todo["id"]}))
    linked = (await next_changed(laptop))["todos"][0]
    assert linked["conversation_id"] == res["result"]["conversation_id"]
    await until_done(phone, res["result"]["turn_id"])

    listed = check("todos.list.result", await call(laptop, "l1", "todos.list"))
    assert listed["result"]["todos"] == [linked]

    unknown = await call(phone, "s2", "chat.send", {"text": "x", "todo_id": "td-nope"})
    assert unknown["error"]["code"] == m.NOT_FOUND
    gone = check("todos.delete.result", await call(laptop, "d1", "todos.delete", {"id": todo["id"]}))
    assert gone["result"]["deleted"] is True


def test_comments_and_groups(tmp_path: Path):
    todos = TodoStore(tmp_path / "chat.db")
    a = todos.add({"text": "Book flights", "group": "  Travel   plans "})
    assert a["group"] == "Travel plans" and "comments" not in a
    with_note = todos.comment({"id": a["id"], "text": " Window seat "})
    (note,) = with_note["comments"]
    assert note["text"] == "Window seat" and note["by"] == "you" and note["id"].startswith("tc-")
    todos.comment({"id": a["id"], "text": "Found one at 9:10"}, by="agent")
    assert [c["by"] for c in todos.list()[0]["comments"]] == ["you", "agent"]
    assert [c["text"] for c in todos.uncomment({"id": a["id"], "comment_id": note["id"]})["comments"]] == [
        "Found one at 9:10"]
    with pytest.raises(TodoError) as err:
        todos.uncomment({"id": a["id"], "comment_id": note["id"]})
    assert err.value.code == m.NOT_FOUND
    assert "group" not in todos.update({"id": a["id"], "group": None})

    b = todos.add({"text": "Buy milk"})
    for i in range(MAX_COMMENTS):
        todos.comment({"id": b["id"], "text": str(i)})
    with pytest.raises(TodoError) as err:
        todos.comment({"id": b["id"], "text": "one more"})
    assert err.value.code == m.CONFLICT
    todos.delete({"id": b["id"]})
    assert todos.db.execute("SELECT COUNT(*) FROM todo_comments WHERE todo_id = ?", (b["id"],)).fetchone()[0] == 0

    assert todos.set_groups({a["id"]: "Travel"}, only_ungrouped=True) == 1
    assert todos.set_groups({a["id"]: "Trips"}, only_ungrouped=True) == 0, "a group set before stays"
    assert todos.set_groups({a["id"]: "Trips"}, only_ungrouped=False) == 1


def test_an_older_list_gains_groups(tmp_path: Path):
    db = sqlite3.connect(tmp_path / "chat.db")
    db.execute("CREATE TABLE todos (id TEXT PRIMARY KEY, text TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0, "
               "created_at INTEGER NOT NULL, done_at INTEGER, due TEXT, conversation_id TEXT)")
    db.execute("INSERT INTO todos (id, text, created_at) VALUES ('td-1', 'Old one', 1)")
    db.commit()
    db.close()
    todos = TodoStore(tmp_path / "chat.db")
    assert todos.list() == [{"id": "td-1", "text": "Old one", "done": False, "created_at": 1}]
    assert todos.update({"id": "td-1", "group": "Home"})["group"] == "Home"


def test_parse_groups():
    ids = {"td-1", "td-2", "td-3"}
    reply = 'Sure:\n```json\n{"td-1": "Home", "td-2": " Work ", "td-3": "", "td-9": "Elsewhere"}\n```'
    assert parse_groups(reply, ids) == {"td-1": "Home", "td-2": "Work"}
    with pytest.raises(GroupingError):
        parse_groups("I can't", ids)


async def test_comments_reach_every_device(chat_bridge):  # noqa: F811
    bridge, _ = chat_bridge
    phone, laptop = await connected(bridge), await connected(bridge)
    todo = check("todos.result", await call(phone, "a1", "todos.add", {"text": "Renew passport", "group": "Admin"}))
    todo_id = todo["result"]["todo"]["id"]
    await next_changed(laptop)
    noted = check("todos.result", await call(phone, "c1", "todos.comment", {"id": todo_id, "text": "Photos first"}))
    comment = noted["result"]["todo"]["comments"][0]
    assert (await next_changed(laptop))["todos"][0]["comments"] == [comment]
    check("todos.result", await call(laptop, "u1", "todos.uncomment", {"id": todo_id, "comment_id": comment["id"]}))
    assert "comments" not in (await next_changed(phone))["todos"][0]


async def test_new_todos_are_grouped_by_the_agent(chat_bridge, monkeypatch):  # noqa: F811
    monkeypatch.setattr(talaria_bridge.chat, "GROUP_DELAY_S", 0.05)
    bridge, hermes = chat_bridge
    phone = await connected(bridge)
    kept = check("todos.result", await call(phone, "a0", "todos.add", {"text": "Gym", "group": "Health"}))["result"]["todo"]
    milk = check("todos.result", await call(phone, "a1", "todos.add", {"text": "Buy milk"}))["result"]["todo"]
    hermes.final = json.dumps({milk["id"]: "Errands", kept["id"]: "Errands"})  # the add's own todos.changed came first
    grouped = {t["id"]: t.get("group") for t in (await next_changed(phone))["todos"]}
    assert grouped == {kept["id"]: "Health", milk["id"]: "Errands"}, "only the ungrouped one moves"
    asked = hermes.messages[-1]
    assert "Buy milk" in asked and "Gym" not in asked and "Health" in asked
    assert not [s for s in hermes.sessions if s.startswith("talaria_groups_")], "the throwaway session is deleted"

    hermes.final = json.dumps({milk["id"]: "Shopping", kept["id"]: "Fitness"})
    res = check("todos.regroup.result", await call(phone, "r1", "todos.regroup"))
    assert {t["id"]: t["group"] for t in res["result"]["todos"]} == {kept["id"]: "Fitness", milk["id"]: "Shopping"}
    hermes.down = True
    failed = await call(phone, "r2", "todos.regroup")
    assert failed["error"]["code"] == m.AGENT_UNAVAILABLE
