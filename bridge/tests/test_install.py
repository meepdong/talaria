"""talaria setup / doctor (bridge README "Quick install"): every command and file, without a server."""

import json
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

if sys.platform == "win32":
    pytest.skip("talaria setup is for Linux servers", allow_module_level=True)

import talaria_bridge.install as inst
from talaria_bridge.install import Installer, Layout, Plan, doctor, read_env

USERS = {"hermes": 1000, "hermes-asha": 1001}


class FakeServer:
    """The commands setup runs, and a Hermes whose `config set` lands where `config get` and .env find it."""

    def __init__(self, tmp: Path):
        self.tmp = tmp
        self.calls: list[tuple[list[str], str | None]] = []
        self.config: dict[str, dict] = {"hermes-asha": {"terminal": {"backend": "docker", "docker_volumes": [
            f"{tmp}/home/hermes-asha/projects:/workspace/projects"]}}}
        self.acl: dict[str, str] = {}

    def __call__(self, argv, user=None):
        argv = list(argv)
        self.calls.append((argv, user))
        if argv[:3] == ["tailscale", "status", "--json"]:
            return 0, json.dumps({"Self": {"DNSName": "box.tail.ts.net."}})
        if argv[0].endswith("/hermes") and argv[1:3] == ["config", "get"]:
            node = self.config.get(user, {})
            for part in argv[3].split("."):
                node = node.get(part) if isinstance(node, dict) else None
            return (0, json.dumps(node)) if node is not None else (1, "")
        if argv[0].endswith("/hermes") and argv[1:3] == ["config", "set"]:
            key, value = argv[3], argv[4]
            if key.isupper():
                env = self.tmp / "home" / user / ".hermes" / ".env"
                env.parent.mkdir(parents=True, exist_ok=True)
                with env.open("a") as f:
                    f.write(f"{key}={value}\n")
            else:
                node = self.config.setdefault(user, {})
                *path, last = key.split(".")
                for part in path:
                    node = node.setdefault(part, {})
                try:
                    node[last] = json.loads(value)
                except ValueError:
                    node[last] = value
            return 0, ""
        if argv[0] == "getfacl":
            return 0, self.acl.get(argv[-1], "")
        if argv[0] == "setfacl":
            self.acl[argv[-1]] = self.acl.get(argv[-1], "") + f"\ndefault:user:{argv[-2].split(':')[1]}:r-x"
            return 0, ""
        if argv[0] == "install" and argv[1] == "-d":
            Path(argv[-1]).mkdir(parents=True, exist_ok=True)
            return 0, ""
        if argv[:2] == ["systemctl", "--user"] and "cat" in argv:
            return 0, "[Service]"
        return 0, ""

    def ran(self, *prefix: str) -> list[list[str]]:
        return [a for a, _ in self.calls if a[:len(prefix)] == list(prefix)]


@pytest.fixture
def box(tmp_path: Path, monkeypatch):
    for user in ("hermes", "hermes-asha"):
        cli = tmp_path / "home" / user / ".local" / "bin" / "hermes"
        cli.parent.mkdir(parents=True)
        cli.write_text("#!/bin/sh\n")
    (tmp_path / "venv" / "bin").mkdir(parents=True)
    (tmp_path / "venv" / "bin" / "talaria").write_text("#!/bin/sh\n")
    (tmp_path / "systemd").mkdir()
    # the owner's bridge, made by hand: ports 8443 / 8767 are taken
    (tmp_path / "systemd" / "talaria-bridge.service").write_text("ExecStart=/x/talaria serve --port 8443\n")
    created: set[str] = set()

    def getpwnam(name):
        if name in USERS or name in created:
            return SimpleNamespace(pw_dir=str(tmp_path / "home" / name), pw_gid=USERS.get(name, 2000))
        raise KeyError(name)

    monkeypatch.setattr(inst.pwd, "getpwnam", getpwnam)
    monkeypatch.setattr(inst, "_listening", lambda port: False)
    monkeypatch.setattr(inst.os, "chmod", lambda *a: None)
    server = FakeServer(tmp_path)
    original = server.__call__

    def run(argv, user=None):
        if argv[0] == "useradd":
            created.add(argv[-1])
        return original(argv, user)

    layout = Layout(etc=tmp_path / "etc", lib=tmp_path / "lib", systemd=tmp_path / "systemd", venv=tmp_path / "venv",
                    home=lambda user: tmp_path / "home" / user)
    return SimpleNamespace(tmp=tmp_path, server=server, run=run, layout=layout)


def setup(box, **kw) -> Installer:
    plan = Plan(instance="asha", hermes_user="hermes-asha", layout=box.layout, **kw)
    installer = Installer(plan, run=box.run)
    installer.install()
    return installer


def test_a_second_person_gets_a_separate_bridge(box):
    i = setup(box)
    p = i.plan
    assert (p.user, p.port, p.tools_port) == ("talaria-asha", 8444, 8768), "next to the owner's 8443 / 8767"
    assert box.server.ran("useradd")[0][-1] == "talaria-asha"
    env = read_env(box.tmp / "home" / "hermes-asha" / ".hermes" / ".env")
    assert env["API_SERVER_ENABLED"] == "true" and env["API_SERVER_PORT"] == "8643", "not the owner's Hermes port"
    assert (p.etc / "hermes-api.key").read_text().strip() == env["API_SERVER_KEY"]
    assert (p.etc / "hermes-tools.key").read_text().strip() == env["TALARIA_TOOLS_KEY"]
    mcp = box.server.config["hermes-asha"]["mcp_servers"]["talaria"]
    assert mcp == {"url": "http://127.0.0.1:8768/mcp", "headers": {"Authorization": "Bearer ${TALARIA_TOOLS_KEY}"}}
    volumes = box.server.config["hermes-asha"]["terminal"]["docker_volumes"]
    assert f"{p.data}/inbox:{p.data}/inbox:ro" in volumes, "Hermes's sandbox reads the inbox"

    agent = json.loads((p.data / "agents.json").read_text())["agents"][0]
    assert agent["api_url"] == "http://127.0.0.1:8643" and agent["inbox_dir"] == f"{p.data}/inbox"
    assert agent["files"] == [{"id": "workspace", "name": "Hermes workspace", "path": f"{box.tmp}/home/hermes-asha/projects",
                               "agent_path": "/workspace/projects"}]
    assert (p.data / "certs" / "bridge_cert.pem").read_text().startswith("-----BEGIN CERTIFICATE-----")

    unit = (box.layout.systemd / "talaria-bridge-asha.service").read_text()
    for part in ("User=talaria-asha", "--port 8444", "--agent-tools-port 8768", "wss://box.tail.ts.net:8444/tnp",
                 "--pin-cert", "ProtectHome=tmpfs", f"BindReadOnlyPaths={box.tmp}/home/hermes-asha/projects",
                 "TALARIA_UPDATE_CHANNEL=stable", "ProtectSystem=strict"):
        assert part in unit, part
    assert "ops.sock" not in unit, "no server operations for another person"
    assert (box.layout.etc / "talaria" / "publish-targets").read_text() == f"stable {p.data}/updates talaria-asha\n"
    assert box.server.ran("systemctl", "enable", "--now", "talaria-bridge-asha")
    assert box.server.ran("systemctl", "--user", "-M", "hermes-asha@", "restart", "hermes-gateway")
    assert not (box.layout.systemd / "talaria-bridge.service").read_text().count("asha"), "the owner's bridge untouched"


def test_running_it_again_changes_nothing(box):
    setup(box)
    box.server.calls.clear()
    again = setup(box)
    assert box.server.ran(str(box.tmp / "home" / "hermes-asha" / ".local" / "bin" / "hermes"), "config", "set") == []
    assert not box.server.ran("systemctl", "--user"), "Hermes isn't restarted for nothing"
    assert not box.server.ran("systemctl", "restart"), "nor the bridge"
    assert not box.server.ran("useradd") and not box.server.ran("setfacl")
    assert [t for m, t in again.report.lines if m != "✓"] == []


def test_refusals(box, monkeypatch):
    with pytest.raises(inst.SetupError, match="lowercase"):
        Installer(Plan(instance="Asha!", layout=box.layout), run=box.run).install()
    with pytest.raises(inst.SetupError, match="no user 'nobody-here'"):
        Installer(Plan(instance="bo", hermes_user="nobody-here", layout=box.layout), run=box.run).install()
    with pytest.raises(inst.SetupError, match="owner"):
        Installer(Plan(instance="bo", hermes_user="hermes-asha", ops=True, layout=box.layout), run=box.run).install()


def test_doctor_says_what_to_fix(box):
    i = setup(box)
    p = i.plan
    (p.data / "updates" / "stable").mkdir(parents=True)
    (p.data / "updates" / "stable" / "android.json").write_text(json.dumps({"version": "0.2.0"}))

    def http(url, headers=None, body=None):
        if url.endswith("/health"):
            return 200, b"{}"
        return 200, json.dumps({"result": {"tools": [{"name": "todo_list"}, {"name": "send_file"}]}}).encode()

    def run(argv, user=None):
        if argv[:2] == ["systemctl", "is-active"]:
            return 3, "inactive"
        if argv[-2:] == ["devices", "list"]:
            return 0, "DEVICE ID   NAME   PLATFORM   LAST SEEN   STATUS\nABC   Phone   android   now   active\n"
        if argv[1:3] == ["mcp", "test"]:
            return 0, "✓ Tools discovered: 7"
        return 0, ""

    checks = {c.what: c for c in doctor(p, run=run, http=http)}
    service = next(c for w, c in checks.items() if "service" in w)
    assert not service.ok and "journalctl -u talaria-bridge-asha" in service.fix
    assert all(c.ok for w, c in checks.items() if c is not service), [w for w, c in checks.items() if not c.ok]
    assert "1 device paired" in checks


def test_the_publisher_places_releases_for_other_bridges(tmp_path: Path, monkeypatch):
    from talaria_bridge.apppublish import Publisher, read_targets

    monkeypatch.setattr("os.chown", lambda *a: None)
    out = tmp_path / "updates"
    (out / "stable").mkdir(parents=True)
    (out / "stable" / "talaria-android-0.2.0.apk").write_bytes(b"signed")
    (out / "stable" / "android.json").write_text(json.dumps({"version": "0.2.0", "version_code": 20099, "size": 6,
                                                            "sha256": "0" * 64, "notes": "", "file": "talaria-android-0.2.0.apk",
                                                            "published_at": 1}))
    targets = tmp_path / "publish-targets"
    targets.write_text(f"stable {tmp_path}/asha/updates talaria-asha\nbogus line\n")
    pub = Publisher(out, "meepdong/talaria", tmp_path / "k", tmp_path / "pw", "ab" * 32, tmp_path / "bt", group=None,
                    get=lambda u: b"<feed></feed>", run=lambda a: "")
    pub.targets = [(c, d, None) for c, d, _ in read_targets(targets)]
    assert pub.publish() == ["stable 0.2.0 for None"]
    theirs = json.loads((tmp_path / "asha" / "updates" / "stable" / "android.json").read_text())
    assert theirs["version"] == "0.2.0"
    assert (tmp_path / "asha" / "updates" / "stable" / "talaria-android-0.2.0.apk").read_bytes() == b"signed"
    assert pub.publish() == [], "already there"
