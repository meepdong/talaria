"""The operator's side of pairing: create a token, show it, and confirm the SAS."""

from __future__ import annotations

import queue
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass

from .protocol import keys
from .protocol.encoding import now
from .protocol.pairing import PairingPayload, new_pair_token, new_short_code
from .protocol.sas import EMOJI
from .registry import PairRequest, Registry

DEFAULT_TTL_S = 300
MAX_TTL_S = 900
APPROVAL_TIMEOUT_S = 120


@dataclass(frozen=True)
class NewPairing:
    payload: PairingPayload
    short_code: str

    @property
    def link(self) -> str:
        return self.payload.to_link()


def create_pairing(registry: Registry, key: keys.PrivateKey, *, url: str, name: str | None,
                   ttl_s: int = DEFAULT_TTL_S, tls_spki_sha256: str | None = None) -> NewPairing:
    if not 0 < ttl_s <= MAX_TTL_S:
        raise ValueError(f"pairing tokens last at most {MAX_TTL_S} seconds")
    t = now()
    token, code = new_pair_token(), new_short_code()
    registry.create_pairing(token, code, name, t, t + ttl_s)
    payload = PairingPayload(url=url, bridge_id=keys.key_id(key), bridge_pk=keys.public_key_b64u(key),
                             pair_token=token, exp=t + ttl_s, tls_spki_sha256=tls_spki_sha256)
    return NewPairing(payload, code)


def wait_for_request(registry: Registry, token: str, deadline: int,
                     poll_s: float = 0.25) -> PairRequest | None:
    while now() < deadline:
        req = registry.waiting_request_for(token)
        if req is not None:
            return req
        time.sleep(poll_s)
    return None


def emoji_names(emoji: str) -> str:
    lookup = {e: n for e, n in EMOJI}
    return ", ".join(lookup.get(ch, "?") for ch in emoji)


def ask_yes_no(prompt: str, timeout_s: float, read: Callable[[str], str] = input) -> bool | None:
    """True or False for an answer, None if nobody answered in time."""
    answers: queue.Queue[str] = queue.Queue()

    def reader() -> None:
        try:
            answers.put(read(prompt))
        except EOFError:
            answers.put("")

    threading.Thread(target=reader, daemon=True).start()
    try:
        answer = answers.get(timeout=timeout_s)
    except queue.Empty:
        return None
    return answer.strip().lower() in ("y", "yes")


def confirm_request(registry: Registry, req: PairRequest, *, timeout_s: float = APPROVAL_TIMEOUT_S,
                    read: Callable[[str], str] = input, out: Callable[[str], None] = print) -> str:
    """Show the SAS and record the operator's decision. Returns approved, rejected or expired."""
    digits = f"{req.sas_digits[:3]} {req.sas_digits[3:]}"
    out(f"\nThe device must show:  {digits}  {req.sas_emoji}  ({emoji_names(req.sas_emoji)})")
    answer = ask_yes_no(
        f'Approve "{req.device_name}" ({req.platform})?  Code {digits}  {req.sas_emoji}  [y/N] ',
        timeout_s, read)
    if answer is None:
        out("\nNo answer in time.")
        registry.decide_pair_request(req.id, "expired", now())
        return registry.get_pair_request(req.id).state
    state = "approved" if answer else "rejected"
    if not registry.decide_pair_request(req.id, state, now()):
        out("Too late: the device stopped waiting.")
        return registry.get_pair_request(req.id).state
    return state
