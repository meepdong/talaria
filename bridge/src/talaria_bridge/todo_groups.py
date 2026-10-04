"""Sorting to-dos into groups (spec/README.md §13): the agent reads them and names a group for each."""

from __future__ import annotations

import contextlib
import json
import logging
import re
import secrets

from .hermes import HermesClient, HermesError, HermesUnavailable
from .todos import MAX_GROUP, TodoError, TodoStore, group_name

log = logging.getLogger("talaria.todo_groups")

MAX_GROUPS = 12

GROUP_PROMPT = """Sort these to-dos into groups by what they are about (for example Home, Work, Errands, \
Health, Money), so related ones sit together. Use a group that's already in use when one fits; otherwise make \
a short new one, at most {max_len} characters, and keep the number of groups small (at most {max_groups}).

Groups already in use: {groups}

To-dos to sort (id: text):
{todos}

Don't use any tools and don't change anything. Answer with only a JSON object mapping each id to its group, \
like {{"td-1": "Home"}}."""

JSON_OBJECT = re.compile(r"\{.*\}", re.DOTALL)


class GroupingError(Exception):
    pass


def parse_groups(reply: str, ids: set[str]) -> dict[str, str]:
    """The agent's {id: group} answer, keeping only the ids asked about and valid group names."""
    found = JSON_OBJECT.search(reply)
    if not found:
        raise GroupingError("The agent's answer had no JSON object")
    try:
        data = json.loads(found.group(0))
    except ValueError:
        raise GroupingError("The agent's answer wasn't valid JSON") from None
    if not isinstance(data, dict):
        raise GroupingError("The agent's answer wasn't a JSON object")
    out = {}
    for todo_id, group in data.items():
        if todo_id not in ids:
            continue
        with contextlib.suppress(TodoError):
            name = group_name(group)
            if name is not None:
                out[todo_id] = name
    return out


async def ask_agent(client: HermesClient, prompt: str) -> str:
    """One question in a throwaway Hermes session, deleted afterwards."""
    session_id = f"talaria_groups_{secrets.token_hex(8)}"
    reply, error = "", None
    try:
        await client.create_session(session_id, None)
        try:
            async for name, payload in client.chat_stream(session_id, prompt):
                if name == "assistant.delta" and isinstance(payload.get("delta"), str):
                    reply += payload["delta"]
                elif name == "assistant.completed" and isinstance(payload.get("content"), str):
                    reply = payload["content"]
                elif name == "run.failed":
                    error = str(payload.get("error") or "The agent couldn't answer")
                elif name == "done":
                    break
        finally:
            with contextlib.suppress(HermesError, HermesUnavailable):
                await client.delete_session(session_id)
    except HermesError as exc:
        raise GroupingError(f"Agent error: {exc.message}") from None
    except HermesUnavailable as exc:
        raise GroupingError(f"Agent unavailable: {exc}") from None
    if error and not reply.strip():
        raise GroupingError(error)
    return reply


async def group_todos(store: TodoStore, client: HermesClient, *, everything: bool) -> int:
    """Ask the agent to group the open to-dos: those without a group, or with [everything] all of them.
    Returns how many to-dos changed group."""
    open_ = store.open_todos()
    todos = open_ if everything else [t for t in open_ if "group" not in t]
    if not todos:
        return 0
    groups = sorted({t["group"] for t in open_ if "group" in t})
    prompt = GROUP_PROMPT.format(
        max_len=MAX_GROUP, max_groups=MAX_GROUPS,
        groups=", ".join(groups) or "none yet",
        todos="\n".join(f"{t['id']}: {' '.join(t['text'].split())}" for t in todos))
    answer = parse_groups(await ask_agent(client, prompt), {t["id"] for t in todos})
    changed = store.set_groups(answer, only_ungrouped=not everything)
    log.info("grouped %d of %d to-dos", changed, len(todos))
    return changed
