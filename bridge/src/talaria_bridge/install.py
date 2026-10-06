"""`talaria setup` and `talaria doctor`: install a Talaria bridge next to a Hermes agent, and check one (bridge README,
"Quick install").

Run as root on the server where Hermes runs. Each step looks first and changes only what is missing, so running setup
again repairs an install and changes nothing on a healthy one. `--instance NAME` installs a second, separate bridge for
another person on the same server (their own Hermes user, ports, data and devices, and no server operations).

Every change goes through one `run` (commands) and plain file writes, so tests can check them without a server.
"""

from __future__ import annotations

import datetime as dt
import json
import os
import pwd
import re
import secrets
import shutil
import socket
import subprocess
import sys
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

from .agent_tools import DEFAULT_PORT as TOOLS_PORT

BRIDGE_PORT = 8443
HERMES_PORT = 8642
WORKSPACE_AGENT_PATH = "/workspace/projects"
INSTANCE = re.compile(r"^[a-z][a-z0-9-]{0,15}$")
PUBLISH_TARGETS = "publish-targets"  # in the main /etc/talaria: where the publisher also places releases

Run = Callable[..., tuple[int, str]]
"""run(argv, user=None) -> (exit code, output). Injectable for tests."""


def run_command(argv: list[str], user: str | None = None) -> tuple[int, str]:
    """Run argv (no shell), as [user] with their HOME when given: service users have no login shell."""
    if user is not None:
        argv = ["runuser", "-u", user, "--", "env", f"HOME={pwd.getpwnam(user).pw_dir}", f"USER={user}", *argv]
    done = subprocess.run(argv, capture_output=True, text=True, timeout=1800, cwd="/")
    return done.returncode, (done.stdout + done.stderr).strip()


@dataclass
class Layout:
    """Where things go; tests point these at a temporary folder."""
    etc: Path = Path("/etc")
    lib: Path = Path("/var/lib")
    systemd: Path = Path("/etc/systemd/system")
    venv: Path = Path("/opt/talaria/.venv")
    home: Callable[[str], Path] = lambda user: Path(pwd.getpwnam(user).pw_dir)


@dataclass
class Plan:
    instance: str = ""
    hermes_user: str = "hermes"
    channel: str = "stable"
    dns_name: str | None = None
    port: int | None = None
    tools_port: int | None = None
    ops: bool = False
    layout: Layout = field(default_factory=Layout)
    # --install-hermes: a new person's own Hermes (instances only)
    install_hermes: bool = False
    model: str = "qwen/qwen3.8-flash"
    openrouter_key_file: Path | None = None

    @property
    def suffix(self) -> str:
        return f"-{self.instance}" if self.instance else ""

    @property
    def user(self) -> str:
        return "talaria" + self.suffix

    @property
    def data(self) -> Path:
        return self.layout.lib / self.user

    @property
    def etc(self) -> Path:
        return self.layout.etc / self.user

    @property
    def unit(self) -> str:
        return "talaria-bridge" + self.suffix

    @property
    def hermes_home(self) -> Path:
        return self.layout.home(self.hermes_user) / ".hermes"

    @property
    def hermes_cli(self) -> Path:
        return self.layout.home(self.hermes_user) / ".local" / "bin" / "hermes"


class SetupError(Exception):
    """A step that can't go on, with what to do about it."""


@dataclass
class Report:
    lines: list[tuple[str, str]] = field(default_factory=list)  # (mark, text)
    hermes_changed: bool = False

    def ok(self, text: str) -> None:
        self.lines.append(("✓", text))

    def did(self, text: str) -> None:
        self.lines.append(("→", text))

    def bad(self, text: str) -> None:
        self.lines.append(("✗", text))


def read_env(path: Path) -> dict[str, str]:
    """A .env file's KEY=VALUE lines (export allowed, quotes stripped)."""
    out = {}
    try:
        text = path.read_text()
    except OSError:
        return out
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.removeprefix("export ").split("=", 1)
        out[key.strip()] = value.strip().strip("'\"")
    return out


def free_port(start: int, taken: set[int]) -> int:
    port = start
    while port in taken or _listening(port):
        port += 1
    return port


def _listening(port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(0.2)
        return s.connect_ex(("127.0.0.1", port)) == 0


class Installer:
    def __init__(self, plan: Plan, run: Run = run_command, report: Report | None = None, dry_run: bool = False):
        self.plan, self.run, self.dry_run = plan, run, dry_run
        self.report = report or Report()

    # helpers

    def sh(self, argv: list[str], user: str | None = None, what: str = "") -> str:
        if self.dry_run:
            self.report.did(f"would run: {' '.join(argv)}" + (f" (as {user})" if user else ""))
            return ""
        code, out = self.run(argv, user=user) if user else self.run(argv)
        if code != 0:
            raise SetupError(f"{what or argv[0]} failed: {out[-400:]}")
        return out

    def hermes(self, *args: str) -> str:
        return self.sh([str(self.plan.hermes_cli), *args], user=self.plan.hermes_user, what=f"hermes {args[0]}")

    def write(self, path: Path, text: str, mode: int, owner: str | None = None, group: str | None = None) -> bool:
        """Write [text] unless it's already there; True when it changed."""
        try:
            if path.read_text() == text:
                return False
        except OSError:
            pass
        if self.dry_run:
            self.report.did(f"would write {path}")
            return True
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        os.chmod(path, mode)
        if owner or group:
            self.sh(["chown", f"{owner or 'root'}:{group or owner}", str(path)])
        return True

    def secret(self, path: Path, group: str, value: str | None = None) -> str:
        """A secret file only root and [group] can read; made (or set to [value]) when missing or different."""
        try:
            current = path.read_text().strip()
        except OSError:
            current = ""
        want = value or current or secrets.token_hex(32)
        if current != want:
            self.write(path, want + "\n", 0o640, "root", group)
            self.report.did(f"wrote {path}")
        return want

    # steps

    def prepare_hermes(self) -> None:
        """--install-hermes: a Linux user with their own Hermes (OpenRouter, a cheap model, its API on), kept running."""
        p = self.plan
        if not p.install_hermes:
            return
        if not p.instance:
            raise SetupError("--install-hermes is for another person's instance (--instance NAME)")
        try:
            pwd.getpwnam(p.hermes_user)
            self.report.ok(f"user {p.hermes_user}")
        except KeyError:
            self.sh(["useradd", "--create-home", "--shell", "/bin/bash", p.hermes_user])
            self.report.did(f"created the user {p.hermes_user}")
        self.sh(["loginctl", "enable-linger", p.hermes_user])  # their Hermes keeps running when nobody is logged in
        if not p.hermes_cli.exists():
            self.sh(["bash", "-c", "curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash -s -- --non-interactive"],
                    user=p.hermes_user, what="installing Hermes")
            self.report.did(f"installed Hermes for {p.hermes_user}")
        if p.openrouter_key_file is not None:
            key = p.openrouter_key_file.read_text().strip()
            if read_env(p.hermes_home / ".env").get("OPENROUTER_API_KEY") != key:
                self.hermes("config", "set", "OPENROUTER_API_KEY", key)
                self.report.hermes_changed = True
                self.report.did("set their OpenRouter key")
        model = self.hget("model") or {}
        if model.get("default") != p.model or model.get("provider") != "openrouter":
            self.hermes("config", "set", "model.provider", "openrouter")
            self.hermes("config", "set", "model.default", p.model)
            self.report.hermes_changed = True
            self.report.did(f"their model: {p.model} on OpenRouter")
        code, _ = self.run(["systemctl", "--user", "-M", f"{p.hermes_user}@", "cat", "hermes-gateway"])
        if code != 0 and not self.dry_run:
            self.hermes("gateway", "install")
            self.report.did(f"Hermes runs as a service for {p.hermes_user}")

    def preflight(self) -> None:
        p = self.plan
        if p.instance and not INSTANCE.match(p.instance):
            raise SetupError("--instance takes a short name: lowercase letters, digits and -, e.g. --instance asha")
        if p.ops and p.instance:
            raise SetupError("Server operations (talaria-ops) belong to the server's owner: leave --ops out for an instance")
        try:
            pwd.getpwnam(p.hermes_user)
        except KeyError:
            raise SetupError(f"There is no user {p.hermes_user!r}. Install Hermes for them first: "
                             f"sudo -iu {p.hermes_user} bash -c 'curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash'") from None
        if not p.hermes_cli.exists():
            raise SetupError(f"Hermes isn't installed for {p.hermes_user} ({p.hermes_cli} is missing)")
        if not (p.layout.venv / "bin" / "talaria").exists():
            raise SetupError(f"The Talaria bridge isn't installed in {p.layout.venv}: see bridge/README.md")
        if p.dns_name is None:
            code, out = self.run(["tailscale", "status", "--json"])
            try:
                p.dns_name = json.loads(out)["Self"]["DNSName"].rstrip(".") if code == 0 else None
            except (ValueError, KeyError, TypeError):
                p.dns_name = None
            if not p.dns_name:
                raise SetupError("Tailscale isn't up (no MagicDNS name). Run tailscale up, or pass --dns-name")
        self.report.ok(f"Hermes for {p.hermes_user}, Tailscale as {p.dns_name}, bridge in {p.layout.venv}")

    def ports(self) -> None:
        """This instance's ports: kept from its unit and agents.json, else the first free ones."""
        p = self.plan
        taken_bridge, taken_tools = set(), set()
        for unit in p.layout.systemd.glob("talaria-bridge*.service"):
            if unit.name != f"{p.unit}.service":
                found = re.search(r"--port (\d+)", unit.read_text())
                if found:
                    taken_bridge.add(int(found[1]))
                found = re.search(r"--agent-tools-port (\d+)", unit.read_text())
                taken_tools.add(int(found[1]) if found else TOOLS_PORT)
        mine = p.layout.systemd / f"{p.unit}.service"
        text = mine.read_text() if mine.exists() else ""
        if p.port is None:
            found = re.search(r"--port (\d+)", text)
            p.port = int(found[1]) if found else (BRIDGE_PORT if not p.instance else free_port(BRIDGE_PORT + 1, taken_bridge))
        if p.tools_port is None:
            found = re.search(r"--agent-tools-port (\d+)", text)
            p.tools_port = int(found[1]) if found else (
                TOOLS_PORT if not p.instance and TOOLS_PORT not in taken_tools else free_port(TOOLS_PORT + 1, taken_tools))
        self.report.ok(f"ports: devices {p.port}, Hermes tools {p.tools_port} (loopback)")

    def user(self) -> None:
        p = self.plan
        try:
            pwd.getpwnam(p.user)
            self.report.ok(f"user {p.user}")
        except KeyError:
            self.sh(["useradd", "--system", "--user-group", "--home-dir", str(p.data), "--shell", "/usr/sbin/nologin", p.user])
            self.report.did(f"created the user {p.user}")
        for path, owner, mode in ((p.data, p.user, "0750"), (p.etc, "root", "0750")):
            if not path.is_dir():
                self.sh(["install", "-d", "-o", owner, "-g", p.user, "-m", mode, str(path)])
                self.report.did(f"made {path}")

    def hermes_api(self) -> None:
        """Hermes's API server on, with a key the bridge holds, on a port no other Hermes uses."""
        p = self.plan
        env = read_env(p.hermes_home / ".env")
        changed = False
        if env.get("API_SERVER_ENABLED", "").lower() != "true":
            self.hermes("config", "set", "API_SERVER_ENABLED", "true")
            changed = True
        key = env.get("API_SERVER_KEY") or secrets.token_hex(32)
        if not env.get("API_SERVER_KEY"):
            self.hermes("config", "set", "API_SERVER_KEY", key)
            changed = True
        port = int(env.get("API_SERVER_PORT") or HERMES_PORT)
        if p.instance and not env.get("API_SERVER_PORT"):
            # another person's Hermes on the same server: not the owner's port
            port = free_port(HERMES_PORT + 1, {HERMES_PORT})
            self.hermes("config", "set", "API_SERVER_PORT", str(port))
            changed = True
        self.secret(p.etc / "hermes-api.key", p.user, key)
        self.api_port = port
        self.report.hermes_changed |= changed
        (self.report.did if changed else self.report.ok)(f"Hermes API server on 127.0.0.1:{port}")

    def hermes_tools(self) -> None:
        """Talaria's tools in Hermes (MCP): a token both sides hold, and the server entry."""
        p = self.plan
        token = self.secret(p.etc / "hermes-tools.key", p.user)
        env = read_env(p.hermes_home / ".env")
        url = f"http://127.0.0.1:{p.tools_port}/mcp"
        server = self.hget("mcp_servers.talaria") or {}
        changed = False
        if env.get("TALARIA_TOOLS_KEY") != token:
            self.hermes("config", "set", "TALARIA_TOOLS_KEY", token)
            changed = True
        if server.get("url") != url:
            self.hermes("config", "set", "mcp_servers.talaria.url", url)
            changed = True
        if (server.get("headers") or {}).get("Authorization") not in ("Bearer ${TALARIA_TOOLS_KEY}", f"Bearer {token}"):
            self.hermes("config", "set", "mcp_servers.talaria.headers", json.dumps({"Authorization": "Bearer ${TALARIA_TOOLS_KEY}"}))
            changed = True
        self.report.hermes_changed |= changed
        (self.report.did if changed else self.report.ok)(f"Talaria's tools in Hermes at {url}")

    def hget(self, key: str):
        """A Hermes setting as Hermes resolves it (its own CLI, so its config format stays its business)."""
        p = self.plan
        if not p.hermes_cli.exists():
            return None
        code, out = self.run([str(p.hermes_cli), "config", "get", key, "--json", "--raw"], user=p.hermes_user)
        try:
            return json.loads(out) if code == 0 and out.strip() else None
        except ValueError:
            return None

    def folders(self) -> None:
        """The inbox (files sent to Hermes) and Hermes's workspace (files it makes), shared both ways."""
        p = self.plan
        hermes_group = pwd.getpwnam(p.hermes_user).pw_gid
        inbox = p.data / "inbox"
        if not inbox.is_dir():
            self.sh(["install", "-d", "-o", p.user, "-g", str(hermes_group), "-m", "2750", str(inbox)])
            self.report.did(f"made the inbox {inbox}, readable by {p.hermes_user}")
        terminal = self.hget("terminal") or {}
        self.workspace = p.layout.home(p.hermes_user) / "projects"
        self.workspace_agent = str(self.workspace)
        if terminal.get("backend") == "docker":
            volumes = list(terminal.get("docker_volumes") or [])
            for v in volumes:  # where the sandbox sees the workspace
                parts = str(v).split(":")
                if len(parts) >= 2 and parts[1] == WORKSPACE_AGENT_PATH:
                    self.workspace, self.workspace_agent = Path(parts[0]), WORKSPACE_AGENT_PATH
            want = f"{inbox}:{inbox}:ro"
            if want not in volumes:
                self.hermes("config", "set", "terminal.docker_volumes", json.dumps(volumes + [want]))
                self.report.hermes_changed = True
                self.report.did(f"Hermes's sandbox reads the inbox ({inbox})")
        if not self.workspace.is_dir():
            self.sh(["install", "-d", "-o", p.hermes_user, "-g", str(hermes_group), "-m", "0775", str(self.workspace)])
        home = str(p.layout.home(p.hermes_user))
        code, acl = self.run(["getfacl", "-p", "--omit-header", home])
        if f"user:{p.user}:" not in acl:
            self.sh(["setfacl", "-m", f"u:{p.user}:x", home])
        code, acl = self.run(["getfacl", "-p", "--omit-header", str(self.workspace)])
        if f"default:user:{p.user}:r" not in acl:
            self.sh(["setfacl", "-R", "-m", f"u:{p.user}:rX,d:u:{p.user}:rX", str(self.workspace)])
            self.report.did(f"Hermes's workspace {self.workspace} readable by {p.user}")
        else:
            self.report.ok(f"Hermes's workspace {self.workspace} readable by {p.user}")

    def agents_json(self) -> None:
        p = self.plan
        path = p.data / "agents.json"
        try:
            old = json.loads(path.read_text())["agents"][0]
        except (OSError, ValueError, KeyError, IndexError, TypeError):
            old = {}
        agent = {
            **old,  # keeps what was set by hand: calendar_command, openrouter_key_file, ...
            "id": old.get("id", "hermes"), "name": old.get("name", "Hermes"),
            "health_url": f"http://127.0.0.1:{self.api_port}/health",
            "api_url": f"http://127.0.0.1:{self.api_port}",
            "api_key_file": str(p.etc / "hermes-api.key"),
            "tools_key_file": str(p.etc / "hermes-tools.key"),
            "inbox_dir": str(p.data / "inbox"),
            "files": old.get("files") or [{"id": "workspace", "name": "Hermes workspace", "path": str(self.workspace),
                                          "agent_path": self.workspace_agent}],
        }
        if agent == old:
            self.report.ok(f"{path}")
        elif self.write(path, json.dumps({"agents": [agent]}, indent=2) + "\n", 0o640, p.user, p.user):
            self.report.did(f"wrote {path}")
        else:
            self.report.ok(f"{path}")

    def certificate(self) -> None:
        """A self-signed certificate for the MagicDNS name; devices pin its key at pairing (--pin-cert)."""
        p = self.plan
        cert, key = p.data / "certs" / "bridge_cert.pem", p.data / "certs" / "bridge_key.pem"
        if cert.exists() and key.exists():
            self.report.ok(f"certificate {cert}")
            return
        if self.dry_run:
            self.report.did(f"would make a certificate for {p.dns_name}")
            return
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.x509.oid import NameOID

        private = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, p.dns_name)])
        now = dt.datetime.now(dt.timezone.utc)
        built = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(private.public_key())
                 .serial_number(x509.random_serial_number()).not_valid_before(now - dt.timedelta(days=1))
                 .not_valid_after(now + dt.timedelta(days=3650))
                 .add_extension(x509.SubjectAlternativeName([x509.DNSName(p.dns_name)]), critical=False)
                 .sign(private, hashes.SHA256()))
        self.sh(["install", "-d", "-o", p.user, "-g", p.user, "-m", "0750", str(cert.parent)])
        self.write(key, private.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                              serialization.NoEncryption()).decode(), 0o640, p.user, p.user)
        self.write(cert, built.public_bytes(serialization.Encoding.PEM).decode(), 0o640, p.user, p.user)
        self.report.did(f"made a certificate for {p.dns_name}")

    def units(self) -> None:
        p = self.plan
        bridge = p.layout.venv / "bin" / "talaria"
        unit = f"""# Written by talaria setup{f' --instance {p.instance}' if p.instance else ''}; run it again to repair.
[Unit]
Description=Talaria bridge{f' ({p.instance})' if p.instance else ''}
After=network-online.target tailscaled.service
Wants=network-online.target

[Service]
User={p.user}
Group={p.user}
ExecStart={bridge} --home {p.data} serve --host 0.0.0.0 --port {p.port} --agent-tools-port {p.tools_port} --tls-cert {p.data}/certs/bridge_cert.pem --tls-key {p.data}/certs/bridge_key.pem --url wss://{p.dns_name}:{p.port}/tnp --pin-cert
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=tmpfs
BindReadOnlyPaths={self.workspace}
ReadWritePaths={p.data}
PrivateTmp=true
Environment=TALARIA_UPDATE_CHANNEL={p.channel}

[Install]
WantedBy=multi-user.target
"""
        path = p.layout.systemd / f"{p.unit}.service"
        if path.exists() and "Written by talaria setup" not in path.read_text():
            # an install made by hand (the owner's): leave its unit alone, only say what differs
            self.report.ok(f"{path} was written by hand: left as it is")
            self.unit_changed = False
            return
        self.unit_changed = self.write(path, unit, 0o644)
        (self.report.did if self.unit_changed else self.report.ok)(f"{path}")

    def publish_target(self) -> None:
        """Another person's bridge gets signed releases from the owner's publisher, for its channel."""
        p = self.plan
        if not p.instance:
            return
        targets = p.layout.etc / "talaria" / PUBLISH_TARGETS
        line = f"{p.channel} {p.data / 'updates'} {p.user}"
        existing = targets.read_text().splitlines() if targets.exists() else []
        if line not in existing:
            others = [x for x in existing if x.split()[1:2] != [str(p.data / "updates")]]  # e.g. its old channel
            self.write(targets, "\n".join(others + [line]) + "\n", 0o644)
            self.report.did(f"the owner's publisher also places {p.channel} releases in {p.data / 'updates'}")
        else:
            self.report.ok(f"releases ({p.channel}) reach {p.data / 'updates'}")

    def start(self) -> None:
        p = self.plan
        self.sh(["systemctl", "daemon-reload"])
        self.sh(["systemctl", "enable", "--now", p.unit])
        if self.unit_changed:
            self.sh(["systemctl", "restart", p.unit])
        self.report.ok(f"{p.unit} running")
        if self.report.hermes_changed:
            self.sh(["systemctl", "--user", "-M", f"{p.hermes_user}@", "restart", "hermes-gateway"])
            self.report.did(f"restarted Hermes for {p.hermes_user}, so it sees the changes")

    def install(self) -> Report:
        self.unit_changed = False
        self.api_port = HERMES_PORT
        for step in (self.prepare_hermes, self.preflight, self.ports, self.user, self.hermes_api, self.hermes_tools, self.folders,
                     self.agents_json, self.certificate, self.units, self.publish_target, self.start):
            step()
        return self.report


# doctor

@dataclass
class Check:
    ok: bool
    what: str
    fix: str = ""


def doctor(plan: Plan, run: Run = run_command, http: Callable[[str, dict | None, bytes | None], tuple[int, bytes]] | None = None) -> list[Check]:
    """Plain-words checks of an install, each with what to do when it fails."""
    import urllib.request

    def get(url: str, headers: dict | None = None, body: bytes | None = None) -> tuple[int, bytes]:
        req = urllib.request.Request(url, data=body, headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, r.read()
        except Exception as exc:  # noqa: BLE001 - any failure is a failed check
            return getattr(exc, "code", 0) or 0, str(exc).encode()

    http = http or get
    p = plan
    out: list[Check] = []
    code, _ = run(["systemctl", "is-active", "--quiet", p.unit])
    out.append(Check(code == 0, f"the bridge service {p.unit} is running",
                     f"journalctl -u {p.unit} -n 50 to see why; talaria setup{f' --instance {p.instance}' if p.instance else ''} repairs it"))
    try:
        agent = json.loads((p.data / "agents.json").read_text())["agents"][0]
    except (OSError, ValueError, KeyError, IndexError):
        out.append(Check(False, f"{p.data / 'agents.json'} describes Hermes", "run talaria setup"))
        return out
    status, _ = http(agent.get("health_url", ""), None, None)
    out.append(Check(status == 200, f"Hermes answers at {agent.get('health_url')}",
                     f"systemctl --user -M {p.hermes_user}@ restart hermes-gateway; check API_SERVER_ENABLED in its .env"))
    try:
        token = Path(agent["tools_key_file"]).read_text().strip()
        unit = (p.layout.systemd / f"{p.unit}.service").read_text()
        found = re.search(r"--agent-tools-port (\d+)", unit)
        port = int(found[1]) if found else TOOLS_PORT
        status, body = http(f"http://127.0.0.1:{port}/mcp", {"Authorization": f"Bearer {token}", "content-type": "application/json"},
                            json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}).encode())
        tools = [t["name"] for t in json.loads(body)["result"]["tools"]] if status == 200 else []
    except (OSError, KeyError, ValueError, TypeError):
        tools = []
    out.append(Check("send_file" in tools, f"the bridge offers Hermes its tools ({len(tools)}: {', '.join(tools) or 'none'})",
                     "the bridge isn't serving tools: check tools_key_file in agents.json and restart the bridge"))
    code, said = run([str(p.hermes_cli), "mcp", "test", "talaria"], user=p.hermes_user)
    out.append(Check(code == 0 and "Tools discovered" in said, "Hermes reaches Talaria's tools",
                     f"talaria setup{f' --instance {p.instance}' if p.instance else ''} rewires it; then restart Hermes"))
    cert = p.data / "certs" / "bridge_cert.pem"
    try:
        from cryptography import x509
        days = (x509.load_pem_x509_certificate(cert.read_bytes()).not_valid_after_utc - dt.datetime.now(dt.timezone.utc)).days
        out.append(Check(days > 30, f"the certificate is valid for {days} more days", "delete it and run talaria setup for a new one (devices then pair again)"))
    except (OSError, ValueError):
        out.append(Check(False, "the bridge has a certificate", "run talaria setup"))
    inbox = Path(agent.get("inbox_dir", p.data / "inbox"))
    out.append(Check(inbox.is_dir(), f"the inbox {inbox} exists", "run talaria setup"))
    release = p.data / "updates" / p.channel / "android.json"
    try:
        version = json.loads(release.read_text())["version"]
        out.append(Check(True, f"app updates: {version} on the {p.channel} channel"))
    except (OSError, ValueError, KeyError):
        out.append(Check(False, f"app updates on the {p.channel} channel",
                         "none placed yet: the owner's talaria-publish-app places them (systemctl start talaria-publish-app)"))
    code, said = run([str(p.layout.venv / "bin" / "talaria"), "--home", str(p.data), "devices", "list"], user=p.user)
    paired = sum(1 for line in said.splitlines() if line.split()[-1:] == ["active"])
    out.append(Check(paired > 0, f"{paired} device{'s' if paired != 1 else ''} paired",
                     f"pair one: sudo -u {p.user} {p.layout.venv}/bin/talaria --home {p.data} pair --name \"Phone\""))
    return out


def hermes_checks(plan: Plan, *, turn: bool = True) -> list[Check]:
    """doctor --hermes: every call the bridge makes to Hermes still works as the bridge expects (hermes_check.py)."""
    import asyncio

    from .hermes import HermesClient, HermesUnavailable, read_api_key
    from .hermes_check import check_hermes

    try:
        agents = [a for a in json.loads((plan.data / "agents.json").read_text())["agents"] if a.get("api_url")]
    except (OSError, ValueError, KeyError, TypeError):
        return [Check(False, f"{plan.data / 'agents.json'} describes Hermes", "run talaria setup")]
    out: list[Check] = []
    for agent in agents:
        async def one(a: dict = agent):
            client = HermesClient(a["api_url"], read_api_key(Path(a["api_key_file"])))
            try:
                return await check_hermes(client, turn=turn)
            finally:
                await client.close()
        try:
            report = asyncio.run(one())
        except (OSError, ValueError, KeyError, TypeError) as exc:
            out.append(Check(False, f"read the API key of Hermes at {agent.get('api_url')}", f"{exc}; talaria setup repairs it"))
            continue
        except HermesUnavailable as exc:
            out.append(Check(False, f"Hermes answers at {agent['api_url']} ({exc})",
                             f"systemctl --user -M {plan.hermes_user}@ restart hermes-gateway"))
            continue
        good = len(report.findings) - len(report.problems)
        out.append(Check(report.ok, f"Hermes {report.version or '(unknown version)'}: {good} of {len(report.findings)} calls"
                         f" Talaria relies on answer as expected{'' if report.turn else ' (without a test message)'}",
                         "Talaria needs a fix for this Hermes version; each line below says what changed"))
        out += [Check(False, f"{f.call} ({f.affects})", f.problem) for f in report.problems]
    return out


def main_setup(args) -> int:
    plan = Plan(instance=args.instance or "", hermes_user=args.hermes_user or ("hermes" if not args.instance else f"hermes-{args.instance}"),
                channel=args.channel, dns_name=args.dns_name, ops=False, install_hermes=args.install_hermes,
                model=args.model, openrouter_key_file=args.openrouter_key_file)
    if os.geteuid() != 0 and not args.dry_run:
        print("talaria setup must run as root (sudo)", file=sys.stderr)
        return 2
    installer = Installer(plan, dry_run=args.dry_run)
    try:
        report = installer.install()
    except SetupError as exc:
        for mark, text in installer.report.lines:
            print(f" {mark} {text}")
        print(f" ✗ {exc}", file=sys.stderr)
        return 1
    for mark, text in report.lines:
        print(f" {mark} {text}")
    if not args.dry_run:
        print(f"\nDone. Pair a phone: sudo -u {plan.user} {plan.layout.venv}/bin/talaria --home {plan.data} pair --name \"Phone\"")
        print(f"Check it any time: sudo {plan.layout.venv}/bin/talaria doctor{f' --instance {plan.instance}' if plan.instance else ''}")
    return 0


def main_doctor(args) -> int:
    plan = Plan(instance=args.instance or "", hermes_user=args.hermes_user or ("hermes" if not args.instance else f"hermes-{args.instance}"),
                channel=args.channel)
    unit = plan.layout.systemd / f"{plan.unit}.service"
    if unit.exists():
        found = re.search(r"TALARIA_UPDATE_CHANNEL=(\w+)", unit.read_text() + "".join(
            f.read_text() for f in (plan.layout.systemd / f"{plan.unit}.service.d").glob("*.conf")))
        plan.channel = found[1] if found else plan.channel
    checks = doctor(plan)
    if getattr(args, "hermes", False):
        checks += hermes_checks(plan, turn=not args.no_turn)
    for c in checks:
        print(f" {'✓' if c.ok else '✗'} {c.what}" + ("" if c.ok else f"\n     → {c.fix}"))
    bad = sum(1 for c in checks if not c.ok)
    print("\nAll good." if not bad else f"\n{bad} problem{'s' if bad != 1 else ''} to fix.")
    return 0 if not bad else 1
