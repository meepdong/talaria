"""`talaria` command: serve | pair | devices list | devices revoke."""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import hashlib
import ipaddress
import logging
import os
import socket
import ssl
import sys
from datetime import datetime
from pathlib import Path

from . import __version__
from .agents import AgentConfig, AgentMonitor, load_agents
from .blobs import BlobStore
from .accounts import OpenRouterAccount
from .chat import ChatService, ChatStore
from .todos import TodoStore
from .automations import AutomationStore, Automations
from .files import INBOX, FileRoot, FilesService
from .agent_tools import DEFAULT_PORT as AGENT_TOOLS_PORT
from .agent_tools import MIN_TOKEN, AgentTools
from .ops.client import DEFAULT_SOCKET as OPS_SOCKET
from .ops.client import OpsClient
from .server_ops import ServerOps
from .hermes import HermesClient, read_api_key
from .operator import APPROVAL_TIMEOUT_S, DEFAULT_TTL_S, confirm_request, create_pairing, wait_for_request
from .protocol import keys
from .protocol.encoding import b64u_encode, now
from .protocol.pairing import format_short_code
from .registry import Registry
from .server import BridgeServer, ServerSettings
from .updates import AppUpdates

TAILSCALE_CGNAT = ipaddress.ip_network("100.64.0.0/10")


def default_home() -> Path:
    return Path(os.environ.get("TALARIA_HOME") or Path.home() / ".talaria")


def open_home(home: Path) -> tuple[Registry, keys.PrivateKey]:
    home.mkdir(parents=True, exist_ok=True)
    return Registry(home / "bridge.db"), keys.load_or_create_private_key(home / "bridge_key.pem")


def is_private_address(host: str) -> bool:
    """True for loopback, private LAN and Tailscale (100.64.0.0/10) addresses."""
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror:
        return False
    for info in infos:
        ip = ipaddress.ip_address(info[4][0].split("%")[0])
        if not (ip.is_loopback or ip.is_private or ip in TAILSCALE_CGNAT):
            return False
    return bool(infos)


def is_loopback(host: str) -> bool:
    try:
        return all(ipaddress.ip_address(i[4][0].split("%")[0]).is_loopback
                   for i in socket.getaddrinfo(host, None))
    except socket.gaierror:
        return False


def cert_spki_sha256(cert_path: Path) -> str:
    from cryptography import x509
    from cryptography.hazmat.primitives import serialization

    cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
    spki = cert.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return b64u_encode(hashlib.sha256(spki).digest())


def make_chat(home: Path, agents: list[AgentConfig]) -> ChatService | None:
    """Chat for every agent with an api_url. An unreadable key file leaves that agent out."""
    clients, inboxes, accounts, roots, calendars = {}, {}, [], {}, {}
    for agent in agents:
        if agent.api_url is None:
            continue
        try:
            api_key = read_api_key(Path(agent.api_key_file))
        except (OSError, ValueError) as exc:
            print(f"WARNING: no chat for agent {agent.id}: cannot read its API key ({exc})", file=sys.stderr)
            continue
        clients[agent.id] = HermesClient(agent.api_url, api_key)
        if agent.inbox_dir is not None:
            inboxes[agent.id] = Path(agent.inbox_dir)
        roots[agent.id] = file_roots(agent)
        if agent.calendar_command:
            calendars[agent.id] = agent.calendar_command
        if agent.openrouter_key_file is not None:
            try:
                accounts.append(OpenRouterAccount(read_api_key(Path(agent.openrouter_key_file))))
            except (OSError, ValueError) as exc:
                print(f"WARNING: no OpenRouter balance: cannot read {agent.openrouter_key_file} ({exc})", file=sys.stderr)
    if not clients:
        return None

    async def not_serving(msg: dict) -> None:  # replaced by BridgeServer.broadcast
        pass

    return ChatService(ChatStore(home / "chat.db"), clients, not_serving,
                       blobs=BlobStore(home / "blobs"), inboxes=inboxes, accounts=accounts,
                       files=FilesService(roots), todos=TodoStore(home / "chat.db"),
                       automations=Automations(clients, AutomationStore(home / "chat.db"), calendars=calendars))


def make_agent_tools(agents: list[AgentConfig], chat: ChatService | None, ops: ServerOps | None = None) -> AgentTools | None:
    """The MCP endpoint for every agent with a tools_key_file (§15). An unreadable or short key leaves it out."""
    if chat is None or chat.todos is None:
        return None
    tokens = {}
    for agent in agents:
        if agent.tools_key_file is None:
            continue
        try:
            token = read_api_key(Path(agent.tools_key_file))
        except (OSError, ValueError) as exc:
            print(f"WARNING: no tools for agent {agent.id}: cannot read {agent.tools_key_file} ({exc})", file=sys.stderr)
            continue
        if len(token) < MIN_TOKEN:
            print(f"WARNING: no tools for agent {agent.id}: its tools key needs {MIN_TOKEN}+ characters",
                  file=sys.stderr)
            continue
        tokens[token] = agent.id
    return AgentTools(chat.todos, tokens, chat.todos_changed, ops, chat.automations) if tokens else None


def file_roots(agent: AgentConfig) -> list[FileRoot]:
    """The folders devices can browse (§12): the inbox, then what agents.json shares."""
    roots = []
    if agent.inbox_dir is not None:
        roots.append(FileRoot(INBOX, "Sent from Talaria", Path(agent.inbox_dir), agent.inbox_dir))
    for f in agent.files:
        roots.append(FileRoot(f["id"], f.get("name") or f["id"], Path(f["path"]), f.get("agent_path") or f["path"]))
    return roots


def cmd_serve(args: argparse.Namespace) -> int:
    registry, key = open_home(args.home)
    settings = ServerSettings(host=args.host, port=args.port)
    if args.dev and args.behind_proxy:
        print("Use either --dev or --behind-proxy, not both.", file=sys.stderr)
        return 2
    if args.dev:
        if not is_loopback(args.host):
            print("--dev serves plain ws:// and only on a loopback address such as 127.0.0.1.",
                  file=sys.stderr)
            return 2
        url = args.url or f"ws://{args.host}:{args.port}/tnp"
        pin = None
    elif args.behind_proxy:
        if not is_loopback(args.host):
            print("--behind-proxy serves plain ws:// and only on a loopback address such as "
                  "127.0.0.1. The proxy (for example tailscale serve) provides TLS.", file=sys.stderr)
            return 2
        if args.tls_cert or args.tls_key or args.pin_cert:
            print("--behind-proxy takes no certificate: the proxy terminates TLS.", file=sys.stderr)
            return 2
        if not (args.url and args.url.startswith("wss://")):
            print("--behind-proxy needs --url wss://…/tnp, the address the proxy serves.",
                  file=sys.stderr)
            return 2
        url = args.url
        pin = None
    else:
        if not (args.tls_cert and args.tls_key and args.url):
            print("Without --dev the bridge needs --tls-cert, --tls-key and --url wss://…/tnp.\n"
                  "To try it on this computer, run: talaria serve --dev", file=sys.stderr)
            return 2
        if not args.url.startswith("wss://"):
            print("--url must start with wss://", file=sys.stderr)
            return 2
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(args.tls_cert, args.tls_key)
        settings.ssl = ctx
        url = args.url
        pin = cert_spki_sha256(Path(args.tls_cert)) if args.pin_cert else None
    if not is_private_address(args.host):
        print(f"WARNING: {args.host} is a public address. Talaria should only listen on "
              "localhost or your private network (Tailscale/WireGuard).", file=sys.stderr)

    registry.set_meta("url", url)
    if pin:
        registry.set_meta("tls_spki_sha256", pin)
    else:
        registry.db.execute("DELETE FROM meta WHERE key = 'tls_spki_sha256'")

    agents = load_agents(args.home / "agents.json")
    chat = make_chat(args.home, agents)
    # server operations (§16) when talaria-ops is installed: its socket exists
    ops = ServerOps(OpsClient(args.ops_socket)) if args.ops_socket.exists() else None
    updates = AppUpdates(args.home / "updates", args.update_channel)  # placed by talaria-publish-app (§17)
    bridge = BridgeServer(registry, key, settings, AgentMonitor(agents), chat, ops, updates)
    tools = make_agent_tools(agents, chat, ops)
    print(f"Talaria bridge {__version__}")
    print(f"  bridge id: {bridge.bridge_id}")
    if args.behind_proxy:
        print(f"  listening: ws://{args.host}:{args.port}/tnp, devices use {url}  (data in {args.home})")
    else:
        print(f"  listening: {url}  (data in {args.home})")
    print(f"  agents:    {', '.join(a.id for a in agents) or 'none configured (agents.json)'}")
    print(f"  chat:      {', '.join(chat.agents) if chat else 'off (no agent has api_url in agents.json)'}")
    if tools:
        print(f"  tools:     http://127.0.0.1:{args.agent_tools_port}/mcp for {', '.join(sorted(set(tools.tokens.values())))}")
    print(f"  server:    {'operations via ' + str(args.ops_socket) if ops else 'no operations (talaria-ops not installed)'}")
    print(f"  updates:   {args.update_channel} channel, from {updates.folder}")
    print("Pair a device from another terminal with: talaria pair --name \"My phone\"")
    sys.stdout.flush()  # under systemd stdout is a pipe, so these lines would wait in a buffer

    async def run() -> None:
        async with contextlib.AsyncExitStack() as stack:
            await stack.enter_async_context(bridge.serve())
            if tools:
                await stack.enter_async_context(tools.serve(args.agent_tools_port))
            await asyncio.get_running_loop().create_future()

    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        print("\nStopped.")
    return 0


def print_qr(link: str) -> None:
    import qrcode

    qr = qrcode.QRCode(border=1)
    qr.add_data(link)
    qr.make(fit=True)
    qr.print_ascii(invert=True)


def cmd_pair(args: argparse.Namespace) -> int:
    registry, key = open_home(args.home)
    url = args.url or registry.get_meta("url")
    if not url:
        print("Start the bridge once first (talaria serve), or pass --url.", file=sys.stderr)
        return 2
    new = create_pairing(registry, key, url=url, name=args.name, ttl_s=args.ttl,
                         tls_spki_sha256=registry.get_meta("tls_spki_sha256"))
    minutes, seconds = divmod(args.ttl, 60)
    if not args.no_qr:
        print_qr(new.link)
        print("Scan with the Talaria app")
    print(f"Paste this link (expires in {minutes}:{seconds:02d}, keep it private):")
    print(f"  {new.link}")
    print(f"or enter code {format_short_code(new.short_code)} with the bridge address {url}")
    print("\nWaiting for the device…")

    req = wait_for_request(registry, new.payload.pair_token, new.payload.exp)
    if req is None:
        print("The pairing link expired. Run talaria pair again.")
        return 1
    state = confirm_request(registry, req, timeout_s=APPROVAL_TIMEOUT_S)
    if state == "approved":
        print(f'Approved. "{req.device_name}" is paired as {req.device_id}.')
        return 0
    if state == "rejected":
        print("Rejected. The link is used up. If the codes didn't match, someone else may "
              "have the link: run talaria pair again and share the new one carefully.")
    else:
        print("Not paired: no decision in time. Run talaria pair again.")
    return 1


def fmt_time(ts: int | None) -> str:
    return datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M") if ts else "never"


def cmd_devices_list(args: argparse.Namespace) -> int:
    registry, _ = open_home(args.home)
    devices = registry.list_devices()
    if not devices:
        print("No paired devices. Pair one with: talaria pair --name \"My phone\"")
        return 0
    print(f"{'DEVICE ID':<27} {'NAME':<24} {'PLATFORM':<10} {'LAST SEEN':<17} STATUS")
    for d in devices:
        status = f"revoked {fmt_time(d.revoked_at)}" if d.revoked else "active"
        print(f"{d.device_id:<27} {d.name[:24]:<24} {d.platform[:10]:<10} "
              f"{fmt_time(d.last_seen):<17} {status}")
    return 0


def cmd_devices_revoke(args: argparse.Namespace) -> int:
    registry, _ = open_home(args.home)
    try:
        device = registry.find_device(args.device_id)
    except LookupError as exc:
        print(exc, file=sys.stderr)
        return 1
    if not registry.revoke_device(device.device_id, now()):
        print(f'"{device.name}" was already revoked.')
        return 0
    print(f'Revoked "{device.name}" ({device.device_id}). Any open session closes within a '
          "second, and this key can no longer connect or pair again.")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="talaria", description="Talaria bridge")
    parser.add_argument("--home", type=Path, default=default_home(),
                        help="data folder (default: $TALARIA_HOME or ~/.talaria)")
    parser.add_argument("-v", "--verbose", action="store_true")
    parser.add_argument("--version", action="version", version=f"talaria {__version__}")
    sub = parser.add_subparsers(dest="command", required=True)

    serve = sub.add_parser("serve", help="run the TNP endpoint")
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8765)
    serve.add_argument("--agent-tools-port", type=int, default=AGENT_TOOLS_PORT,
                       help="loopback port of the agents' MCP tools (§15)")
    serve.add_argument("--ops-socket", type=Path, default=OPS_SOCKET,
                       help="talaria-ops's socket; server operations are offered when it exists (§16)")
    serve.add_argument("--update-channel", choices=["beta", "stable"],
                       default=os.environ.get("TALARIA_UPDATE_CHANNEL", "stable"),
                       help="which app releases this bridge offers its devices (§17; default $TALARIA_UPDATE_CHANNEL or stable)")
    serve.add_argument("--url", help="address devices use, e.g. wss://meep-vps.tailnet.ts.net/tnp")
    serve.add_argument("--dev", action="store_true", help="plain ws:// on loopback, for local testing")
    serve.add_argument("--behind-proxy", action="store_true",
                       help="plain ws:// on loopback behind a TLS proxy such as tailscale serve; "
                            "devices use the wss:// --url")
    serve.add_argument("--tls-cert")
    serve.add_argument("--tls-key")
    serve.add_argument("--pin-cert", action="store_true",
                       help="self-signed certificate: devices pin its key from the pairing payload")
    serve.set_defaults(func=cmd_serve)

    pair = sub.add_parser("pair", help="pair a new device")
    pair.add_argument("--name", help="label for the device, e.g. \"OnePlus 10 Pro\"")
    pair.add_argument("--ttl", type=int, default=DEFAULT_TTL_S, help="link lifetime in seconds (max 900)")
    pair.add_argument("--url", help="override the bridge address put in the link")
    pair.add_argument("--no-qr", action="store_true")
    pair.set_defaults(func=cmd_pair)

    devices = sub.add_parser("devices", help="list or revoke paired devices")
    dsub = devices.add_subparsers(dest="devices_command", required=True)
    dsub.add_parser("list").set_defaults(func=cmd_devices_list)
    revoke = dsub.add_parser("revoke")
    revoke.add_argument("device_id", help="device id or a unique prefix of it")
    revoke.set_defaults(func=cmd_devices_revoke)
    return parser


def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        # UTF-8 so the SAS emoji survive being piped or logged on Windows, where stdout
        # otherwise falls back to the locale code page and turns them into "?".
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    args = build_parser().parse_args(argv)
    logging.basicConfig(level=logging.INFO if args.verbose else logging.WARNING,
                        format="%(asctime)s %(name)s %(message)s")
    try:
        return args.func(args)
    except ValueError as exc:
        print(exc, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
