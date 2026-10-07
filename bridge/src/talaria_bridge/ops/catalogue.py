"""The server operations talaria-ops offers (PROTOCOL §10.8, spec/README.md §16).

Each operation is a fixed piece of code with typed parameters. Commands are argv lists built here,
never a shell string, and parameters are checked against enums, bounds or live lists before anything runs.
"""

from __future__ import annotations

import asyncio
import json
import re
import os
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from pathlib import Path

OUTPUT_LIMIT = 64 * 1024

REPO = "/opt/talaria"
BRIDGE_PYTHON = "/opt/talaria/.venv/bin/python"
HERMES_LOG = "/home/hermes/.hermes/logs/agent.log"
HERMES_HOME = "/home/hermes/.hermes"
HERMES_CLI = "/home/hermes/.local/bin/hermes"
SKILLS_OFF = "skills.platform_disabled.api_server"  # the skills Hermes doesn't load for Talaria (its API server)

# name -> (kind, unit). "system" units are managed with systemctl; Hermes runs as a user unit of `hermes`.
SERVICES: dict[str, tuple[str, str]] = {
    "talaria-bridge": ("system", "talaria-bridge.service"),
    "talaria-ops": ("system", "talaria-ops.service"),
    "docker": ("system", "docker.service"),
    "tailscaled": ("system", "tailscaled.service"),
    "ssh": ("system", "ssh.service"),
    "hermes-gateway": ("user:hermes", "hermes-gateway.service"),
}
# talaria-ops can't restart itself mid-request, and ssh is socket-activated (restarting it gains nothing).
RESTARTABLE = ["talaria-bridge", "docker", "tailscaled", "hermes-gateway"]


class OpError(Exception):
    """A request talaria-ops refuses; the message is shown to the owner or the agent."""


@dataclass
class Run:
    exit_code: int | None  # None: it timed out and was killed
    output: str


Runner = Callable[..., Awaitable[Run]]
"""runner(argv, *, user=None, env=None, cwd=None, timeout=60) -> Run. Injectable for tests."""


@dataclass
class Outcome:
    ok: bool
    summary: str
    output: str = ""
    exit_code: int | None = 0
    data: object = None

    def as_dict(self, op: str, started_at: int, finished_at: int) -> dict:
        out = {"op": op, "ok": self.ok, "exit_code": self.exit_code, "summary": self.summary[:500],
               "output": self.output[-OUTPUT_LIMIT:], "started_at": started_at, "finished_at": finished_at}
        if self.data is not None:
            out["data"] = self.data
        return out


@dataclass
class Param:
    type: str  # "string" | "integer"
    enum: list[str] | None = None
    minimum: int | None = None
    maximum: int | None = None
    default: object = None
    choices: Callable[["Context"], Awaitable[list[str]]] | None = None  # a live list, e.g. running containers
    pattern: str | None = None  # a free string must match this (full match)
    max_length: int | None = None

    def describe(self, live: list[str] | None) -> dict:
        out: dict = {"type": self.type}
        values = live if live is not None else self.enum
        if values is not None:
            out["enum"] = values
        if self.minimum is not None:
            out["minimum"] = self.minimum
        if self.maximum is not None:
            out["maximum"] = self.maximum
        if self.default is not None:
            out["default"] = self.default
        if self.pattern is not None:
            out["pattern"] = self.pattern
        if self.max_length is not None:
            out["maxLength"] = self.max_length
        return out


@dataclass
class Context:
    run: Runner
    audit_tail: Callable[[int], list[dict]] = lambda n: []
    proc: Path = Path("/proc")
    hermes_log: Path = Path(HERMES_LOG)
    hermes_home: Path = Path(HERMES_HOME)
    terminals: object = None  # terminal.Terminals: root's tmux sessions and the grants to them (§16.1)


@dataclass
class Op:
    name: str
    tier: int
    title: str
    do: Callable[[Context, dict], Awaitable[Outcome]]
    params: dict[str, Param] = field(default_factory=dict)
    summary: Callable[[dict], str] | None = None

    def describe_summary(self, params: dict) -> str:
        return self.summary(params) if self.summary else self.title


# Validation

async def check_params(op: Op, given: object, ctx: Context) -> dict:
    """The parameters with defaults filled in; OpError for anything unknown, missing, mistyped or out of range."""
    if given is None:
        given = {}
    if not isinstance(given, dict):
        raise OpError("params must be an object")
    unknown = set(given) - set(op.params)
    if unknown:
        raise OpError(f"{op.name} takes no parameter {sorted(unknown)[0]!r}")
    out = {}
    for name, spec in op.params.items():
        value = given.get(name, spec.default)
        if value is None:
            raise OpError(f"{op.name} needs {name!r}")
        if spec.type == "integer":
            if isinstance(value, bool) or not isinstance(value, int):
                raise OpError(f"{name} must be an integer")
            if (spec.minimum is not None and value < spec.minimum) or (spec.maximum is not None and value > spec.maximum):
                raise OpError(f"{name} must be between {spec.minimum} and {spec.maximum}")
        else:
            if not isinstance(value, str):
                raise OpError(f"{name} must be a string")
            allowed = await spec.choices(ctx) if spec.choices else spec.enum
            if allowed is not None and value not in allowed:
                raise OpError(f"{name} must be one of {', '.join(allowed) or '(none available)'}")
            if spec.max_length is not None and len(value) > spec.max_length:
                raise OpError(f"{name} must be at most {spec.max_length} characters")
            if spec.pattern is not None and not re.fullmatch(spec.pattern, value):
                raise OpError(f"{name} isn't valid here")
        out[name] = value
    return out


def params_json(params: dict) -> str:
    """The exact string devices sign (spec/README.md §16)."""
    return json.dumps(params, sort_keys=True, separators=(",", ":"))


# Helpers

def _done(run: Run, summary: str, data: object = None) -> Outcome:
    if run.exit_code is None:
        return Outcome(False, f"{summary}: timed out", run.output, None, data)
    ok = run.exit_code == 0
    return Outcome(ok, summary if ok else f"{summary}: failed (exit {run.exit_code})", run.output, run.exit_code, data)


def _systemctl(kind: str) -> list[str]:
    return ["systemctl", "--user", "-M", "hermes@"] if kind == "user:hermes" else ["systemctl"]


def _tail_file(path: Path, lines: int) -> str:
    try:
        with path.open("rb") as f:
            f.seek(0, os.SEEK_END)
            f.seek(max(0, f.tell() - 512 * 1024))
            text = f.read().decode("utf-8", "replace")
    except OSError as exc:
        return f"(cannot read {path}: {exc.strerror})"
    return "\n".join(text.splitlines()[-lines:])


async def _containers(ctx: Context) -> list[str]:
    run = await ctx.run(["docker", "ps", "--format", "{{.Names}}"], timeout=20)
    return sorted(n for n in run.output.split() if n) if run.exit_code == 0 else []


# Tier 0: read

async def system_overview(ctx: Context, p: dict) -> Outcome:
    def read(name: str) -> str:
        try:
            return (ctx.proc / name).read_text()
        except OSError:
            return ""
    mem = {}
    for line in read("meminfo").splitlines():
        key, _, rest = line.partition(":")
        if rest.strip().split():
            mem[key] = int(rest.split()[0]) * 1024
    disk = os.statvfs("/")
    uptime = read("uptime").split()
    updates = await ctx.run(["/usr/lib/update-notifier/apt-check"], timeout=60)
    upd = updates.output.strip().splitlines()[-1].split(";") if updates.exit_code == 0 and updates.output.strip() else []
    failed = await ctx.run(["systemctl", "--failed", "--plain", "--no-legend"], timeout=20)
    data = {
        "hostname": os.uname().nodename,
        "kernel": os.uname().release,
        "uptime_s": int(float(uptime[0])) if uptime else None,
        "load": [float(x) for x in read("loadavg").split()[:3]] if read("loadavg") else [],
        "cpus": os.cpu_count(),
        "mem_total": mem.get("MemTotal"), "mem_available": mem.get("MemAvailable"),
        "disk_total": disk.f_blocks * disk.f_frsize, "disk_free": disk.f_bavail * disk.f_frsize,
        "updates": int(upd[0]) if len(upd) == 2 and upd[0].isdigit() else None,
        "security_updates": int(upd[1]) if len(upd) == 2 and upd[1].isdigit() else None,
        "reboot_required": Path("/var/run/reboot-required").exists(),
        "failed_units": [line.split()[0] for line in failed.output.splitlines() if line.strip()],
    }
    used = 100 - round(100 * data["disk_free"] / data["disk_total"]) if data["disk_total"] else 0
    summary = (f"up {data['uptime_s'] // 3600 if data['uptime_s'] else '?'} h, disk {used}% used, "
               f"{data['updates'] if data['updates'] is not None else '?'} updates"
               + (", reboot required" if data["reboot_required"] else "")
               + (f", {len(data['failed_units'])} failed units" if data["failed_units"] else ""))
    return Outcome(True, summary, json.dumps(data, indent=1), 0, data)


async def services_list(ctx: Context, p: dict) -> Outcome:
    data = []
    for name, (kind, unit) in SERVICES.items():
        run = await ctx.run([*_systemctl(kind), "is-active", unit], timeout=20)
        data.append({"service": name, "state": (run.output.strip().splitlines() or ["unknown"])[-1]})
    down = [d["service"] for d in data if d["state"] != "active"]
    return Outcome(True, "all services active" if not down else f"not active: {', '.join(down)}",
                   "\n".join(f"{d['service']}: {d['state']}" for d in data), 0, data)


async def service_logs(ctx: Context, p: dict) -> Outcome:
    kind, unit = SERVICES[p["service"]]
    if kind == "user:hermes":  # Hermes writes its own log files, not the journal
        return Outcome(True, f"last {p['lines']} lines of Hermes's agent.log", _tail_file(ctx.hermes_log, p["lines"]))
    run = await ctx.run(["journalctl", "-u", unit, "-n", str(p["lines"]), "--no-pager", "-o", "short-iso"], timeout=30)
    return _done(run, f"last {p['lines']} log lines of {p['service']}")


async def docker_ps(ctx: Context, p: dict) -> Outcome:
    run = await ctx.run(["docker", "ps", "--format", "{{json .}}"], timeout=20)
    rows = []
    for line in run.output.splitlines():
        try:
            c = json.loads(line)
        except ValueError:
            continue
        rows.append({"name": c.get("Names"), "image": c.get("Image"), "status": c.get("Status")})
    return _done(run, f"{len(rows)} containers running", rows)


async def tailscale_status(ctx: Context, p: dict) -> Outcome:
    run = await ctx.run(["tailscale", "status", "--json"], timeout=20)
    if run.exit_code != 0:
        return _done(run, "tailscale status")
    try:
        d = json.loads(run.output)
    except ValueError:
        return Outcome(False, "tailscale status: unreadable output", run.output[-2000:], run.exit_code)
    peers = [{"name": x.get("HostName"), "os": x.get("OS"), "online": bool(x.get("Online")),
              "last_seen": x.get("LastSeen")} for x in (d.get("Peer") or {}).values()]
    online = sum(1 for x in peers if x["online"])
    text = "\n".join(f"{x['name']} ({x['os']}): {'online' if x['online'] else 'offline'}" for x in peers)
    return Outcome(True, f"{online} of {len(peers)} devices online", text, 0,
                   {"self": (d.get("Self") or {}).get("HostName"), "peers": peers})


async def bridge_version(ctx: Context, p: dict) -> Outcome:
    git = ["git", "-C", REPO]
    fetch = await ctx.run([*git, "fetch", "-q", "origin"], user="talaria", timeout=60)
    head = await ctx.run([*git, "log", "-1", "--format=%h %s"], user="talaria", timeout=20)
    behind = await ctx.run([*git, "rev-list", "--count", "HEAD..origin/main"], user="talaria", timeout=20)
    n = int(behind.output.strip()) if behind.exit_code == 0 and behind.output.strip().isdigit() else None
    note = "" if fetch.exit_code == 0 else " (couldn't reach GitHub)"
    summary = (f"up to date{note}" if n == 0 else f"{n} commits behind GitHub{note}" if n else f"unknown{note}")
    return Outcome(True, summary, head.output.strip(), 0, {"head": head.output.strip(), "behind": n})


async def ssh_recent_logins(ctx: Context, p: dict) -> Outcome:
    run = await ctx.run(["journalctl", "-u", "ssh", "--grep", "Accepted", "-n", str(p["lines"]),
                         "--no-pager", "-o", "short-iso"], timeout=30)
    if run.exit_code == 1 and "No entries" in run.output:
        return Outcome(True, "no recent logins", "", 0)
    return _done(run, f"last {p['lines']} SSH logins")


async def ops_history(ctx: Context, p: dict) -> Outcome:
    rows = ctx.audit_tail(p["lines"])
    text = "\n".join(f"{time.strftime('%Y-%m-%d %H:%M', time.gmtime(r.get('ts', 0)))}  {r.get('op')}  "
                     f"{r.get('outcome')}  by {r.get('requested_by')}" for r in rows)
    return Outcome(True, f"last {len(rows)} operations", text, 0, rows)


# Tier 1: change

async def service_restart(ctx: Context, p: dict) -> Outcome:
    kind, unit = SERVICES[p["service"]]
    run = await ctx.run([*_systemctl(kind), "restart", "--no-block", unit], timeout=30)
    return _done(run, f"restarting {p['service']}")


async def docker_restart(ctx: Context, p: dict) -> Outcome:
    run = await ctx.run(["docker", "restart", p["container"]], timeout=120)
    return _done(run, f"restarted {p['container']}")


async def bridge_update(ctx: Context, p: dict) -> Outcome:
    git = ["git", "-C", REPO]
    log: list[str] = []

    async def step(argv: list[str], timeout: int, **kw) -> Run:
        run = await ctx.run(argv, user="talaria", timeout=timeout, **kw)
        log.append(f"$ {' '.join(argv)}\n{run.output.strip()}")
        return run

    old = (await step([*git, "rev-parse", "HEAD"], 20)).output.strip()
    pulled = await step([*git, "pull", "--ff-only", "origin", "main"], 120)
    if pulled.exit_code != 0:
        return Outcome(False, "bridge update: git pull failed, nothing changed", "\n\n".join(log), pulled.exit_code)
    new = (await step([*git, "rev-parse", "HEAD"], 20)).output.strip()
    if new == old:
        return Outcome(True, "bridge already up to date", "\n\n".join(log), 0)
    installed = await step([BRIDGE_PYTHON, "-m", "pip", "install", "-q", "-e", f"{REPO}/bridge"], 300)
    tested = await step([BRIDGE_PYTHON, "-m", "pytest", "-q", "-p", "no:cacheprovider"], 600, cwd=f"{REPO}/bridge") \
        if installed.exit_code == 0 else installed
    if tested.exit_code != 0:
        await step([*git, "reset", "-q", "--hard", old], 30)
        await step([BRIDGE_PYTHON, "-m", "pip", "install", "-q", "-e", f"{REPO}/bridge"], 300)
        return Outcome(False, f"bridge update failed its tests; rolled back to {old[:7]}", "\n\n".join(log),
                       tested.exit_code)
    restart = await ctx.run(["systemctl", "restart", "--no-block", "talaria-bridge.service"], timeout=30)
    log.append(f"$ systemctl restart --no-block talaria-bridge.service\n{restart.output.strip()}")
    return Outcome(restart.exit_code == 0, f"bridge updated {old[:7]} → {new[:7]}, restarting",
                   "\n\n".join(log), restart.exit_code)


async def disk_cleanup(ctx: Context, p: dict) -> Outcome:
    journal = await ctx.run(["journalctl", "--vacuum-size=200M"], timeout=120)
    images = await ctx.run(["docker", "image", "prune", "-f"], timeout=300)
    ok = journal.exit_code == 0 and images.exit_code == 0
    return Outcome(ok, "cleaned the journal and unused Docker images" if ok else "disk cleanup: a step failed",
                   f"{journal.output.strip()}\n\n{images.output.strip()}",
                   0 if ok else (journal.exit_code or images.exit_code))


async def apt_upgrade(ctx: Context, p: dict) -> Outcome:
    env = {"DEBIAN_FRONTEND": "noninteractive"}
    update = await ctx.run(["apt-get", "update", "-q"], env=env, timeout=300)
    if update.exit_code != 0:
        return _done(update, "apt-get update")
    upgrade = await ctx.run(["apt-get", "-y", "-q", "-o", "Dpkg::Options::=--force-confold", "upgrade"],
                            env=env, timeout=1800)
    out = _done(upgrade, "packages upgraded")
    out.output = f"{update.output.strip()}\n\n{upgrade.output.strip()}"
    if out.ok and Path("/var/run/reboot-required").exists():
        out.summary += "; a reboot is required"
    return out


# Terminals (§16.1): devices only; the agent's server_op refuses them

async def tmux_sessions(ctx: Context, p: dict) -> Outcome:
    sessions = await ctx.terminals.sessions()
    return Outcome(True, f"{len(sessions)} tmux session{'' if len(sessions) == 1 else 's'}", data={"sessions": sessions})


def _skill_files(home: Path) -> list[dict]:
    """Hermes's installed skills, from each SKILL.md's front matter: {name, description, category}."""
    found: dict[str, dict] = {}
    root = home / "skills"
    try:
        files = sorted(root.rglob("SKILL.md"))
    except OSError:  # not there, or not readable (as talaria-ops isn't root in tests)
        files = []
    for f in files:
        try:
            text = f.read_text(errors="replace")[:4000]
        except OSError:
            continue
        meta = text.split("---", 2)[1] if text.startswith("---") and text.count("---") >= 2 else ""
        name = re.search(r"(?m)^name:\s*(.+?)\s*$", meta)
        if name is None:
            continue
        desc = re.search(r"(?m)^description:\s*(.+?)\s*$", meta)
        n = name[1].strip("\"'")
        found.setdefault(n, {"name": n, "description": desc[1].strip("\"'")[:200] if desc else "",
                             "category": f.relative_to(root).parts[0]})
    return sorted(found.values(), key=lambda x: (x["category"], x["name"]))


async def _skills_off(ctx: Context) -> list[str] | None:
    run = await ctx.run([HERMES_CLI, "config", "get", SKILLS_OFF], user="hermes", timeout=60)
    if run.exit_code != 0:
        return None
    return re.findall(r"(?m)^\s*-\s*(\S+)\s*$", run.output)


async def _skill_names(ctx: Context) -> list[str]:
    return [s["name"] for s in await asyncio.to_thread(_skill_files, ctx.hermes_home)]


async def hermes_skills(ctx: Context, p: dict) -> Outcome:
    skills = await asyncio.to_thread(_skill_files, ctx.hermes_home)
    off = await _skills_off(ctx)
    if off is None:
        return Outcome(False, "couldn't read Hermes's skills settings", "", None)
    rows = [{**s, "enabled": s["name"] not in off} for s in skills]
    on = sum(r["enabled"] for r in rows)
    return Outcome(True, f"{on} of {len(rows)} skills on for Talaria", "", 0, rows)


async def hermes_skill_set(ctx: Context, p: dict) -> Outcome:
    off = await _skills_off(ctx)
    if off is None:
        return Outcome(False, "couldn't read Hermes's skills settings", "", None)
    name, on = p["skill"], p["enabled"] == "on"
    new = [s for s in off if s != name] + ([] if on else [name])
    if sorted(new) == sorted(off):
        return Outcome(True, f"{name} was already {'on' if on else 'off'} for Talaria", "", 0)
    run = await ctx.run([HERMES_CLI, "config", "set", SKILLS_OFF, json.dumps(sorted(new))], user="hermes", timeout=60)
    if run.exit_code != 0:
        return _done(run, f"turning {name} {'on' if on else 'off'}")
    kind, unit = SERVICES["hermes-gateway"]
    restart = await ctx.run([*_systemctl(kind), "restart", "--no-block", unit], timeout=30)
    return _done(restart, f"{name} is {'on' if on else 'off'} for Talaria; Hermes is restarting")


SKILL_CHANGES = r"[A-Za-z0-9._-]{1,80}=(on|off)(,[A-Za-z0-9._-]{1,80}=(on|off)){0,99}"


def _skill_changes(text: str) -> dict[str, bool]:
    return {name: state == "on" for name, _, state in (part.partition("=") for part in text.split(","))}


async def hermes_skills_set(ctx: Context, p: dict) -> Outcome:
    """Several skills on or off for Talaria with one config change and one Hermes restart (#52)."""
    off = await _skills_off(ctx)
    if off is None:
        return Outcome(False, "couldn't read Hermes's skills settings", "", None)
    changes = _skill_changes(p["changes"])
    known = set(await _skill_names(ctx))
    unknown = sorted(n for n in changes if n not in known)
    if unknown:
        return Outcome(False, f"no skill called {', '.join(unknown)}", "", None)
    new = set(off)
    for name, on in changes.items():
        (new.discard if on else new.add)(name)
    if sorted(new) == sorted(off):
        return Outcome(True, "nothing to change: the skills were already like that", "", 0)
    run = await ctx.run([HERMES_CLI, "config", "set", SKILLS_OFF, json.dumps(sorted(new))], user="hermes", timeout=60)
    if run.exit_code != 0:
        return _done(run, "changing the skills")
    kind, unit = SERVICES["hermes-gateway"]
    restart = await ctx.run([*_systemctl(kind), "restart", "--no-block", unit], timeout=30)
    ons = sum(1 for on in changes.values() if on)
    return _done(restart, f"{len(changes)} skill change{'s' if len(changes) != 1 else ''} ({ons} on, "
                          f"{len(changes) - ons} off) for Talaria; Hermes is restarting once")


def _skills_summary(p: dict) -> str:
    changes = _skill_changes(p["changes"])
    on = [n for n, v in changes.items() if v]
    off = [n for n, v in changes.items() if not v]
    parts = ([f"turn on {', '.join(on)}"] if on else []) + ([f"turn off {', '.join(off)}"] if off else [])
    return ("For Talaria, " + "; ".join(parts) + " (Hermes restarts once; a reply in progress stops)")[:500]


async def _tmux_names(ctx: Context) -> list[str]:
    return await ctx.terminals.names() if ctx.terminals is not None else []


async def _open_terminal(ctx: Context, p: dict, control: bool) -> Outcome:
    from .terminal import APPROVED_BY

    grant = ctx.terminals.grant(APPROVED_BY.get(), p["session"], control)
    return Outcome(True, f"{'Typing in' if control else 'Watching'} the tmux session {p['session']}", data=grant)


async def terminal_watch(ctx: Context, p: dict) -> Outcome:
    return await _open_terminal(ctx, p, control=False)


async def terminal_control(ctx: Context, p: dict) -> Outcome:
    return await _open_terminal(ctx, p, control=True)


async def tmux_new(ctx: Context, p: dict) -> Outcome:
    """A new root tmux session (any folder, any command), opened at once for typing on the approving device."""
    from .terminal import APPROVED_BY

    if p["name"] in await ctx.terminals.names():
        return Outcome(False, f"There is already a session called {p['name']}")
    if not os.path.isdir(p["folder"]):
        return Outcome(False, f"{p['folder']} isn't a folder on the server")
    argv = ["tmux", "new-session", "-d", "-s", p["name"], "-c", p["folder"]]
    if p["command"].strip():
        argv.append(p["command"])  # tmux runs it with the default shell, as typing it there would
    run = await ctx.run(argv, timeout=20)
    if run.exit_code != 0:
        return _done(run, f"Couldn't start the session {p['name']}")
    grant = ctx.terminals.grant(APPROVED_BY.get(), p["name"], control=True)
    return Outcome(True, f"Started the tmux session {p['name']} in {p['folder']}", run.output, 0, grant)


async def tmux_kill(ctx: Context, p: dict) -> Outcome:
    run = await ctx.run(["tmux", "kill-session", "-t", f"={p['session']}"], timeout=20)
    ctx.terminals.forget(p["session"])
    return _done(run, f"Ended the tmux session {p['session']}")


# Tier 2: disruptive

async def system_reboot(ctx: Context, p: dict) -> Outcome:
    # in 10 s, so this result still reaches the devices
    run = await ctx.run(["systemd-run", "--on-active=10", "--timer-property=AccuracySec=1s",
                         "/bin/systemctl", "reboot"], timeout=30)
    return _done(run, "rebooting in 10 seconds")


SERVICE_PARAM = Param("string", enum=list(SERVICES))
OPS: dict[str, Op] = {op.name: op for op in [
    Op("system.overview", 0, "Server overview", system_overview),
    Op("services.list", 0, "Services", services_list),
    Op("service.logs", 0, "Service logs", service_logs,
       {"service": SERVICE_PARAM, "lines": Param("integer", minimum=1, maximum=500, default=100)},
       lambda p: f"Last {p['lines']} log lines of {p['service']}"),
    Op("docker.ps", 0, "Docker containers", docker_ps),
    Op("tailscale.status", 0, "Tailscale devices", tailscale_status),
    Op("bridge.version", 0, "Bridge version", bridge_version),
    Op("ssh.recent_logins", 0, "Recent SSH logins", ssh_recent_logins,
       {"lines": Param("integer", minimum=1, maximum=100, default=20)}),
    Op("ops.history", 0, "Operations history", ops_history,
       {"lines": Param("integer", minimum=1, maximum=200, default=50)}),
    Op("service.restart", 1, "Restart a service", service_restart,
       {"service": Param("string", enum=RESTARTABLE)}, lambda p: f"Restart {p['service']}"),
    Op("docker.restart", 1, "Restart a container", docker_restart,
       {"container": Param("string", choices=_containers)}, lambda p: f"Restart container {p['container']}"),
    Op("bridge.update", 1, "Update the bridge", bridge_update, {},
       lambda p: "Update the bridge from GitHub (tests run first; rolls back on failure), then restart it"),
    Op("disk.cleanup", 1, "Clean up disk", disk_cleanup, {},
       lambda p: "Trim the system journal to 200 MB and delete unused Docker images"),
    Op("apt.upgrade", 1, "Upgrade packages", apt_upgrade, {}, lambda p: "Install all pending package updates"),
    Op("system.reboot", 2, "Reboot the server", system_reboot, {}, lambda p: "Reboot the server now"),
    Op("hermes.skills", 0, "Hermes's skills", hermes_skills),
    Op("hermes.skill.set", 1, "Turn a skill on or off", hermes_skill_set,
       {"skill": Param("string", choices=_skill_names), "enabled": Param("string", enum=["on", "off"])},
       lambda p: f"Turn the skill {p['skill']} {p['enabled']} for Talaria (Hermes restarts; a reply in progress stops)"),
    Op("hermes.skills.set", 1, "Change several skills", hermes_skills_set,
       {"changes": Param("string", pattern=SKILL_CHANGES, max_length=8200)}, _skills_summary),
    Op("tmux.sessions", 0, "Terminal sessions", tmux_sessions),
    Op("terminal.watch", 1, "Watch a terminal", terminal_watch, {"session": Param("string", choices=_tmux_names)},
       lambda p: f"Watch the tmux session {p['session']} on this device for up to 30 minutes"),
    Op("terminal.control", 2, "Type in a terminal", terminal_control, {"session": Param("string", choices=_tmux_names)},
       lambda p: f"Type in the tmux session {p['session']} from this device (as root) for up to 30 minutes"),
    Op("tmux.new", 2, "New terminal session", tmux_new, {
        "name": Param("string", pattern=r"[A-Za-z0-9_.@+-]{1,40}"),
        "folder": Param("string", pattern=r"/[^\x00\n\r]{0,1023}", default="/root"),
        "command": Param("string", pattern=r"[^\x00\n\r]*", max_length=1000, default=""),
    }, lambda p: f"Start the tmux session {p['name']} as root in {p['folder']}"
                 + (f", running: {p['command']}" if p["command"].strip() else ", with a shell") + ", and type in it from this device"),
    Op("tmux.kill", 2, "End a terminal session", tmux_kill, {"session": Param("string", choices=_tmux_names)},
       lambda p: f"End the tmux session {p['session']} and everything running in it"),
]}

# ops the agent's server_op may never use (spec §16.1)
DEVICE_ONLY = frozenset({"tmux.sessions", "terminal.watch", "terminal.control", "tmux.new", "tmux.kill"})
