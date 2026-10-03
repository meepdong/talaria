"""Short authentication string shown on both sides during pairing (spec/README.md §4)."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

from .encoding import frame

SAS_LABEL = "tnp0-sas"

# Index → (emoji, English name). Must match spec/sas-emoji.json exactly; a test checks it.
EMOJI: tuple[tuple[str, str], ...] = (
    ("🐶", "dog"), ("🐱", "cat"), ("🐭", "mouse"), ("🐹", "hamster"),
    ("🐰", "rabbit"), ("🦊", "fox"), ("🐻", "bear"), ("🐼", "panda"),
    ("🐨", "koala"), ("🐯", "tiger"), ("🦁", "lion"), ("🐮", "cow"),
    ("🐷", "pig"), ("🐸", "frog"), ("🐵", "monkey"), ("🐔", "chicken"),
    ("🐧", "penguin"), ("🐦", "bird"), ("🦆", "duck"), ("🦉", "owl"),
    ("🐴", "horse"), ("🦄", "unicorn"), ("🐝", "bee"), ("🐛", "caterpillar"),
    ("🦋", "butterfly"), ("🐌", "snail"), ("🐢", "turtle"), ("🐍", "snake"),
    ("🐙", "octopus"), ("🦀", "crab"), ("🐬", "dolphin"), ("🐳", "whale"),
    ("🌵", "cactus"), ("🌲", "tree"), ("🍄", "mushroom"), ("🌻", "sunflower"),
    ("🌙", "moon"), ("⭐", "star"), ("🔥", "fire"), ("🌈", "rainbow"),
    ("🍎", "apple"), ("🍌", "banana"), ("🍇", "grapes"), ("🍓", "strawberry"),
    ("🍒", "cherries"), ("🍋", "lemon"), ("🍉", "watermelon"), ("🍕", "pizza"),
    ("🍩", "doughnut"), ("🎂", "cake"), ("🎸", "guitar"), ("🎺", "trumpet"),
    ("🥁", "drum"), ("🎲", "dice"), ("🚀", "rocket"), ("🚲", "bicycle"),
    ("⚓", "anchor"), ("🔑", "key"), ("🔔", "bell"), ("💡", "light bulb"),
    ("📚", "books"), ("🎈", "balloon"), ("🏆", "trophy"), ("🧲", "magnet"),
)
assert len(EMOJI) == 64


@dataclass(frozen=True)
class Sas:
    digits: str
    emoji_indices: tuple[int, int, int]

    @property
    def emoji(self) -> str:
        return "".join(EMOJI[i][0] for i in self.emoji_indices)

    @property
    def emoji_names(self) -> str:
        return ", ".join(EMOJI[i][1] for i in self.emoji_indices)

    def display(self) -> str:
        return f"{self.digits[:3]} {self.digits[3:]}  {self.emoji}"


def derive_sas(bridge_pk: str, device_pk: str, pairing_secret: str) -> Sas:
    """bridge_pk and device_pk are wire public keys; pairing_secret is the pair_token
    string, or the normalized short code when pairing by code."""
    h = hashlib.sha256(frame(SAS_LABEL, bridge_pk, device_pk, pairing_secret)).digest()
    digits = f"{int.from_bytes(h[0:4], 'big') % 1_000_000:06d}"
    return Sas(digits, (h[4] % 64, h[5] % 64, h[6] % 64))
