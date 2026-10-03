"""`tnp-cli`: pair with a Talaria bridge and exercise the protocol from a terminal."""

from __future__ import annotations

import argparse
import asyncio
import os
import socket
import sys
from pathlib import Path

from talaria_bridge.protocol.encoding import EncodingError
from talaria_bridge.protocol.pairing import PairingPayload, normalize_short_code
from talaria_bridge.protocol.sas import Sas

from .client import DeviceState, TnpError, open_session, pair


def default_home() -> Path:
    return Path(os.environ.get("TNP_CLI_HOME") or Path.home() / ".tnp-cli")


def show_sas(sas: Sas) -> None:
    print(f"\nCheck the bridge terminal shows:  {sas.display()}  ({sas.emoji_names})")
    print("Approve there only if it matches. Waiting…")


async def cmd_pair(args: argparse.Namespace, state: DeviceState) -> int:
    if args.link:
        payload = PairingPayload.from_link(args.link)
        info = await pair(state, name=args.name, payload=payload, show_sas=show_sas)
    elif args.code and args.url:
        info = await pair(state, name=args.name, url=args.url,
                          short_code=normalize_short_code(args.code), show_sas=show_sas)
    else:
        print("Give a pairing link, or --code with --url.", file=sys.stderr)
        return 2
    print(f'Paired as "{info.name}" ({info.device_id}) with bridge {info.bridge_id}.')
    return 0


async def cmd_connect(args: argparse.Namespace, state: DeviceState) -> int:
    session = await open_session(state)
    print(f"Connected: session {session.session_id}")
    try:
        result = await session.ping()
        print(f"Ping OK (bridge time {result.get('ts')})")
        if not args.once:
            print("Staying connected. Press Ctrl+C to stop.")
            await session.run_forever()
    finally:
        await session.close()
    return 0


def cmd_whoami(args: argparse.Namespace, state: DeviceState) -> int:
    from talaria_bridge.protocol import keys

    print(f"device id: {keys.key_id(state.key())}")
    try:
        info = state.load_bridge()
        print(f"paired as: {info.name} with bridge {info.bridge_id} at {info.url}")
    except TnpError as exc:
        print(exc)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="tnp-cli", description="Talaria reference test client")
    parser.add_argument("--home", type=Path, default=default_home(),
                        help="data folder (default: $TNP_CLI_HOME or ~/.tnp-cli)")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("pair", help="pair with a bridge")
    p.add_argument("link", nargs="?", help="talaria://pair#… link printed by talaria pair")
    p.add_argument("--code", help="short code, as an alternative to the link")
    p.add_argument("--url", help="bridge address, needed with --code")
    p.add_argument("--name", default=socket.gethostname()[:64] or "tnp-cli")

    c = sub.add_parser("connect", help="authenticate and stay connected")
    c.add_argument("--once", action="store_true", help="ping once and disconnect")

    sub.add_parser("whoami", help="show this device's id and paired bridge")
    return parser


def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        # UTF-8 so the SAS emoji survive being piped or logged on Windows, where stdout
        # otherwise falls back to the locale code page and turns them into "?".
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    args = build_parser().parse_args(argv)
    state = DeviceState(args.home)
    try:
        if args.command == "whoami":
            return cmd_whoami(args, state)
        handler = cmd_pair if args.command == "pair" else cmd_connect
        return asyncio.run(handler(args, state))
    except (TnpError, EncodingError) as exc:
        print(exc, file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nStopped.")
        return 0


if __name__ == "__main__":
    sys.exit(main())
