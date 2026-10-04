"""VPS command allowlist configuration."""

from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import yaml


@dataclass
class AllowlistEntry:
    name: str
    path: str
    args_pattern: str
    timeout: int
    description: str

    def __post_init__(self):
        self._regex = re.compile(self.args_pattern)

    def matches(self, args: list[str]) -> bool:
        """Check if the arguments match the allowlist pattern."""
        args_str = " ".join(args)
        return bool(self._regex.fullmatch(args_str))

    def validate_path(self, requested_path: str) -> bool:
        """Validate the requested command path matches exactly."""
        return os.path.realpath(requested_path) == os.path.realpath(self.path)


class VPSAllowlist:
    def __init__(self, entries: list[AllowlistEntry]):
        self.entries = {e.name: e for e in entries}

    @classmethod
    def load(cls, path: Optional[str] = None) -> "VPSAllowlist":
        config_path = path or os.environ.get("VPS_ALLOWLIST_PATH", "/etc/talaria/vps-allowlist.yaml")
        with open(config_path) as f:
            data = yaml.safe_load(f)
        if not isinstance(data, dict) or "commands" not in data:
            raise ValueError("Invalid allowlist format: missing 'commands' key")
        entries = [AllowlistEntry(**cmd) for cmd in data["commands"]]
        return cls(entries)

    def get_entry(self, command_name: str) -> Optional[AllowlistEntry]:
        return self.entries.get(command_name)

    def validate(self, command_name: str, args: list[str]) -> AllowlistEntry:
        """Validate a command request against the allowlist."""
        entry = self.get_entry(command_name)
        if entry is None:
            raise ValueError(f"Command '{command_name}' not in allowlist")
        if not entry.matches(args):
            raise ValueError(f"Arguments {args!r} do not match allowlist pattern for '{command_name}'")
        return entry

    def list_commands(self) -> list[str]:
        return list(self.entries.keys())


def load_allowlist() -> VPSAllowlist:
    """Load allowlist from default path or environment."""
    return VPSAllowlist.load()