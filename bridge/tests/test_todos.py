from pathlib import Path

import pytest

from talaria_bridge.protocol import messages as m
from talaria_bridge.todos import DONE_SHOWN, TodoError, TodoStore

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
