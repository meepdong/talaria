"""Work orders: how the talker (Tally, spec/README.md §9 "Tally leads Talk") gives a worker a job, and how history
shows one. The worker gets who it's from and how to answer, then the brief; devices see the brief alone."""

from __future__ import annotations


WORK_ORDER = "🎙 Work order from "  # how a work order from the talker starts (§9 "Tally leads Talk")
ORDER_SPLIT = "\n---\n"  # between the order's instructions and its brief


def work_order(talker: str, brief: str, files: list[str] = ()) -> str:
    """What a worker gets from the talker: who it's from, how to answer, then the brief (and the files' lines)."""
    head = (f"{WORK_ORDER}{talker}, the voice the owner talks to in Talk. {talker} talks; you do the work. Do it, "
            f"then reply to {talker} in plain facts: what you did, what you found, what you checked, in one to five "
            "short sentences without Markdown. If something essential is missing, ask one clear question instead "
            "of guessing. The owner approves risky actions as usual.")
    return head + ORDER_SPLIT + brief.strip() + ("\n\n" + "\n".join(files) if files else "")


def mark_workers(messages: list[dict], worker: str) -> list[dict]:
    """In a page of history (oldest first): a work order shows as its brief, and it and its report carry `worker`."""
    ordered = False
    for msg in messages:
        if msg["role"] == "user":
            ordered = msg["text"].startswith(WORK_ORDER)
            if ordered:
                msg["text"] = msg["text"].split(ORDER_SPLIT, 1)[-1].strip()
        if ordered:
            msg["worker"] = worker
    return messages
