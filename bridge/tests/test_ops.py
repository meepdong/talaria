import asyncio
import json
import os
import sqlite3
import sys
from pathlib import Path

import pytest

# talaria-ops is a Linux service (Unix sockets, SO_PEERCRED, runuser); nothing to test on Windows.
if sys.platform == "win32":
    pytest.skip("talaria-ops is Linux-only", allow_module_level=True)

from talaria_bridge.ops.audit import AuditLog
from talaria_bridge.ops.catalogue import OPS, Run
from talaria_bridge.ops.client import OpsClient, OpsRefused, OpsUnavailable
from talaria_bridge.ops.daemon import OpsDaemon, run_command
from talaria_bridge.protocol import keys
from talaria_bridge.protocol import messages as m
from talaria_bridge.registry import Registry

PHONE = keys.generate_key()
PHONE_ID = keys.key_id(PHONE)


class FakeRunner:
    """Records every argv and answers from [answers] (first matching prefix wins)."""

    def __init__(self, answers: dict[tuple, Run] | None = None):
        self.calls: list[tuple[list[str], dict]] = []
        self.answers = answers or {}

    async def __call__(self, argv, **kw) -> Run:
        self.calls.append((list(argv), kw))
        for prefix, run in self.answers.items():
            if tuple(argv[:len(prefix)]) == prefix:
                return run
        return Run(0, "")

    def argvs(self) -> list[list[str]]:
        return [a for a, _ in self.calls]


@pytest.fixture
def registry_db(tmp_path: Path) -> Path:
    path = tmp_path / "bridge.db"
    Registry(path).db.close()
    db = sqlite3.connect(path)
    db.execute("INSERT INTO devices (device_id, public_key, name, platform, paired_at) VALUES (?, ?, ?, ?, ?)",
               (PHONE_ID, keys.public_key_b64u(PHONE), "Phone", "android", 1))
    db.commit()
    db.close()
    return path


@pytest.fixture
def clock():
    class Clock:
        t = 1_790_000_000

        def __call__(self) -> int:
            return self.t
    return Clock()


@pytest.fixture
def runner() -> FakeRunner:
    return FakeRunner({("docker", "ps", "--format", "{{.Names}}"): Run(0, "hermes-abc\nweb\n")})


@pytest.fixture
def daemon(tmp_path, registry_db, runner, clock) -> OpsDaemon:
    return OpsDaemon(registry_db, AuditLog(tmp_path / "audit" / "audit.jsonl"), runner, clock)


def sign(prepared: dict, choice: str = "once", key=PHONE, device_id: str = PHONE_ID) -> str:
    return keys.sign(key, m.ops_approve_signed_data(prepared["request_id"], device_id, prepared["op"],
                                                    prepared["params_json"], choice))


async def test_catalogue_lists_every_op_with_live_choices(daemon: OpsDaemon):
    ops = {o["op"]: o for o in (await daemon.handle({"cmd": "catalogue"}))["ops"]}
    assert set(ops) == set(OPS)
    assert ops["system.overview"]["tier"] == 0 and ops["apt.upgrade"]["tier"] == 1 and ops["system.reboot"]["tier"] == 2
    assert ops["docker.restart"]["params"]["container"]["enum"] == ["hermes-abc", "web"]
    assert ops["service.logs"]["params"]["lines"] == {"type": "integer", "minimum": 1, "maximum": 500, "default": 100}


async def test_tier_0_runs_at_once_and_is_audited(daemon: OpsDaemon, runner: FakeRunner):
    answer = await daemon.handle({"cmd": "run", "op": "service.logs", "params": {"service": "docker", "lines": 5},
                                  "requested_by": "device:X"})
    assert answer["result"]["ok"] and answer["result"]["op"] == "service.logs"
    assert ["journalctl", "-u", "docker.service", "-n", "5", "--no-pager", "-o", "short-iso"] in runner.argvs()
    entry = daemon.audit.tail(1)[0]
    assert entry["op"] == "service.logs" and entry["outcome"] == "ok" and entry["requested_by"] == "device:X"


@pytest.mark.parametrize("req, error", [
    ({"cmd": "run", "op": "rm.rf"}, "unknown operation"),
    ({"cmd": "run", "op": "service.restart", "params": {"service": "docker"}}, "needs approval"),
    ({"cmd": "prepare", "op": "system.overview"}, "needs no approval"),
    ({"cmd": "run", "op": "service.logs", "params": {"service": "sshd; id"}}, "must be one of"),
    ({"cmd": "run", "op": "service.logs", "params": {"service": "docker", "lines": 10_000}}, "between"),
    ({"cmd": "run", "op": "service.logs", "params": {"service": "docker", "lines": "5"}}, "integer"),
    ({"cmd": "run", "op": "service.logs", "params": {"service": "docker", "cwd": "/"}}, "no parameter"),
    ({"cmd": "prepare", "op": "service.restart", "params": {"service": "ssh"}}, "must be one of"),
    ({"cmd": "prepare", "op": "docker.restart", "params": {"container": "--help"}}, "must be one of"),
    ({"cmd": "prepare", "op": "service.restart"}, "needs 'service'"),
    ({"cmd": "nope"}, "unknown command"),
    ("not an object", "bad request"),
])
async def test_bad_requests_are_refused_before_anything_runs(daemon: OpsDaemon, runner: FakeRunner, req, error):
    before = len(runner.calls)
    answer = await daemon.handle(req)
    assert error in answer["error"]
    # only the live container list may have been read
    assert all(a[:2] == ["docker", "ps"] for a in runner.argvs()[before:])


async def test_a_signed_approval_runs_the_op_once(daemon: OpsDaemon, runner: FakeRunner):
    prepared = await daemon.handle({"cmd": "prepare", "op": "service.restart", "params": {"service": "docker"},
                                    "requested_by": "agent:hermes"})
    assert prepared["params_json"] == '{"service":"docker"}' and prepared["tier"] == 1
    assert prepared["summary"] == "Restart docker" and prepared["request_id"].startswith("op-")
    assert not any(a[:2] == ["systemctl", "restart"] for a in runner.argvs())  # nothing ran yet

    answer = await daemon.handle({"cmd": "execute", "request_id": prepared["request_id"], "device_id": PHONE_ID,
                                  "choice": "once", "sig": sign(prepared)})
    assert answer["result"]["ok"]
    assert ["systemctl", "restart", "--no-block", "docker.service"] in runner.argvs()
    entry = daemon.audit.tail(1)[0]
    assert entry["approved_by"] == PHONE_ID and entry["requested_by"] == "agent:hermes" and entry["outcome"] == "ok"

    again = await daemon.handle({"cmd": "execute", "request_id": prepared["request_id"], "device_id": PHONE_ID,
                                 "choice": "once", "sig": sign(prepared)})
    assert "no such request" in again["error"]


async def test_deny_is_signed_too_and_runs_nothing(daemon: OpsDaemon, runner: FakeRunner):
    prepared = await daemon.prepare("system.reboot", {}, "device:X")
    assert prepared["tier"] == 2
    answer = await daemon.handle({"cmd": "execute", "request_id": prepared["request_id"], "device_id": PHONE_ID,
                                  "choice": "deny", "sig": sign(prepared, "deny")})
    assert answer == {"result": None}
    assert not any("reboot" in " ".join(a) for a in runner.argvs())
    assert daemon.audit.tail(1)[0]["outcome"] == "denied"


async def test_only_a_paired_device_can_approve(daemon: OpsDaemon, runner: FakeRunner, registry_db: Path):
    prepared = await daemon.prepare("apt.upgrade", {}, "agent:hermes")
    rid = prepared["request_id"]
    stranger = keys.generate_key()

    async def execute(**kw):
        req = {"cmd": "execute", "request_id": rid, "device_id": PHONE_ID, "choice": "once", "sig": sign(prepared)}
        return await daemon.handle({**req, **kw})

    assert "signature does not verify" in (await execute(sig=sign(prepared, key=stranger)))["error"]
    assert "signature does not verify" in (await execute(sig=sign(prepared, "deny")))["error"]  # choice is signed
    assert "unknown device" in (await execute(device_id=keys.key_id(stranger),
                                              sig=sign(prepared, key=stranger, device_id=keys.key_id(stranger))))["error"]
    db = sqlite3.connect(registry_db)
    db.execute("UPDATE devices SET revoked_at = 2")
    db.commit()
    db.close()
    assert "revoked" in (await execute())["error"]
    assert not any(a[:1] == ["apt-get"] for a in runner.argvs())


async def test_a_tampered_param_fails_the_signature(daemon: OpsDaemon):
    prepared = await daemon.prepare("service.restart", {"service": "docker"}, "agent:hermes")
    forged = {**prepared, "params_json": '{"service":"talaria-bridge"}'}
    answer = await daemon.handle({"cmd": "execute", "request_id": prepared["request_id"], "device_id": PHONE_ID,
                                  "choice": "once", "sig": sign(forged)})
    assert "signature does not verify" in answer["error"]


async def test_requests_expire_after_two_minutes(daemon: OpsDaemon, clock, runner: FakeRunner):
    prepared = await daemon.prepare("disk.cleanup", {}, "agent:hermes")
    clock.t += 121
    answer = await daemon.handle({"cmd": "execute", "request_id": prepared["request_id"], "device_id": PHONE_ID,
                                  "choice": "once", "sig": sign(prepared)})
    assert "expired" in answer["error"]
    assert daemon.audit.tail(1)[0]["outcome"] == "expired"
    assert not any(a[:1] == ["journalctl"] for a in runner.argvs())


async def test_bridge_update_rolls_back_when_tests_fail(tmp_path, registry_db, clock):
    heads = iter(["aaaaaaa1", "bbbbbbb2"])

    class Runner(FakeRunner):
        async def __call__(self, argv, **kw):
            await super().__call__(argv, **kw)
            if argv[-2:] == ["rev-parse", "HEAD"]:
                return Run(0, next(heads) + "\n")
            if "pytest" in argv:
                return Run(1, "1 failed")
            return Run(0, "")

    runner = Runner()
    d = OpsDaemon(registry_db, AuditLog(tmp_path / "a.jsonl"), runner, clock)
    prepared = await d.prepare("bridge.update", {}, "device:X")
    result = await d.execute(prepared["request_id"], PHONE_ID, "once", sign(prepared))
    assert not result["ok"] and "rolled back to aaaaaaa" in result["summary"]
    assert ["git", "-C", "/opt/talaria", "reset", "-q", "--hard", "aaaaaaa1"] in runner.argvs()
    assert not any(a[:2] == ["systemctl", "restart"] for a in runner.argvs())
    assert all(kw.get("user") == "talaria" for a, kw in runner.calls if a[0] == "git")


async def test_hermes_is_restarted_as_its_user_unit(daemon: OpsDaemon, runner: FakeRunner):
    prepared = await daemon.prepare("service.restart", {"service": "hermes-gateway"}, "device:X")
    await daemon.execute(prepared["request_id"], PHONE_ID, "once", sign(prepared))
    assert ["systemctl", "--user", "-M", "hermes@", "restart", "--no-block", "hermes-gateway.service"] in runner.argvs()


async def test_hermes_logs_come_from_its_log_file(tmp_path, registry_db, clock):
    log = tmp_path / "agent.log"
    log.write_text("".join(f"line {i}\n" for i in range(10)))
    d = OpsDaemon(registry_db, AuditLog(tmp_path / "a.jsonl"), FakeRunner(), clock, hermes_log=log)
    result = await d.run("service.logs", {"service": "hermes-gateway", "lines": 3}, "device:X")
    assert result["output"] == "line 7\nline 8\nline 9"


async def test_socket_round_trip_and_peer_check(daemon: OpsDaemon, tmp_path: Path):
    path = tmp_path / "run" / "ops.sock"
    server = await daemon.serve(path, {os.getuid()})
    async with server:
        client = OpsClient(path)
        assert len(await client.catalogue()) == len(OPS)
        assert (await client.run("docker.ps", {}, "device:X"))["op"] == "docker.ps"
        with pytest.raises(OpsRefused, match="unknown operation"):
            await client.run("nope", {}, "device:X")
        assert oct(path.stat().st_mode & 0o777) == "0o660"
    server.close()
    await server.wait_closed()

    strict = await daemon.serve(path, {os.getuid() + 12345})  # nobody we are
    async with strict:
        with pytest.raises(OpsUnavailable):
            await OpsClient(path).catalogue()
    strict.close()
    await strict.wait_closed()
    with pytest.raises(OpsUnavailable):
        await OpsClient(tmp_path / "missing.sock").catalogue()


async def test_real_runner_has_no_shell_and_times_out():
    run = await run_command(["/bin/echo", "a;b", "$(id)"])
    assert run.exit_code == 0 and run.output == "a;b $(id)\n"
    slow = await run_command(["/bin/sleep", "5"], timeout=0.2)
    assert slow.exit_code is None


async def test_hermes_skills_are_listed_and_switched_for_talaria(tmp_path, registry_db, clock):
    for cat, name, desc in (("email", "himalaya", "Read and send email"), ("media", "gif-search", '"Find GIFs"')):
        d = tmp_path / "skills" / cat / name
        d.mkdir(parents=True)
        (d / "SKILL.md").write_text(f"---\nname: {name}\ndescription: {desc}\nversion: 1\n---\n# {name}\n")
    hermes = "/home/hermes/.local/bin/hermes"
    runner = FakeRunner({(hermes, "config", "get"): Run(0, "  - gif-search\n  - airtable\n")})
    d = OpsDaemon(registry_db, AuditLog(tmp_path / "a.jsonl"), runner, clock, hermes_home=tmp_path)
    listed = await d.run("hermes.skills", {}, "device:X")
    assert listed["summary"] == "1 of 2 skills on for Talaria"
    assert listed["data"] == [{"name": "himalaya", "description": "Read and send email", "category": "email", "enabled": True},
                              {"name": "gif-search", "description": "Find GIFs", "category": "media", "enabled": False}]
    assert runner.calls[0][1]["user"] == "hermes"

    ops = {o["op"]: o for o in (await d.handle({"cmd": "catalogue"}))["ops"]}
    assert ops["hermes.skill.set"]["tier"] == 1 and ops["hermes.skill.set"]["params"]["skill"]["enum"] == ["himalaya", "gif-search"]
    prepared = await d.prepare("hermes.skill.set", {"skill": "gif-search", "enabled": "on"}, "device:X")
    assert prepared["summary"].startswith("Turn the skill gif-search on for Talaria")
    result = (await d.handle({"cmd": "execute", "request_id": prepared["request_id"], "device_id": PHONE_ID,
                              "choice": "once", "sig": sign(prepared)}))["result"]
    assert result["ok"] and "Hermes is restarting" in result["summary"]
    assert [hermes, "config", "set", "skills.platform_disabled.api_server", '["airtable"]'] in runner.argvs()
    assert ["systemctl", "--user", "-M", "hermes@", "restart", "--no-block", "hermes-gateway.service"] in runner.argvs()

    with pytest.raises(Exception):
        await d.prepare("hermes.skill.set", {"skill": "not-installed", "enabled": "on"}, "device:X")


async def test_several_skills_change_with_one_approval_and_one_restart(tmp_path, registry_db, clock):
    """#52: the Server page sends all its switch changes as one hermes.skills.set."""
    for cat, name in (("email", "himalaya"), ("media", "gif-search"), ("note-taking", "obsidian")):
        d = tmp_path / "skills" / cat / name
        d.mkdir(parents=True)
        (d / "SKILL.md").write_text(f"---\nname: {name}\ndescription: {name}\n---\n")
    hermes = "/home/hermes/.local/bin/hermes"
    runner = FakeRunner({(hermes, "config", "get"): Run(0, "  - gif-search\n  - airtable\n")})
    d = OpsDaemon(registry_db, AuditLog(tmp_path / "a.jsonl"), runner, clock, hermes_home=tmp_path)
    ops = {o["op"]: o for o in (await d.handle({"cmd": "catalogue"}))["ops"]}
    assert ops["hermes.skills.set"]["tier"] == 1
    prepared = await d.prepare("hermes.skills.set", {"changes": "gif-search=on,obsidian=off,himalaya=on"}, "device:X")
    assert prepared["summary"] == ("For Talaria, turn on gif-search, himalaya; turn off obsidian "
                                   "(Hermes restarts once; a reply in progress stops)")
    result = (await d.handle({"cmd": "execute", "request_id": prepared["request_id"], "device_id": PHONE_ID,
                              "choice": "once", "sig": sign(prepared)}))["result"]
    assert result["ok"] and "3 skill changes (2 on, 1 off) for Talaria; Hermes is restarting once" in result["summary"]
    sets = [a for a in runner.argvs() if a[:3] == [hermes, "config", "set"]]
    assert sets == [[hermes, "config", "set", "skills.platform_disabled.api_server", '["airtable", "obsidian"]']]
    restarts = [a for a in runner.argvs() if "restart" in a]
    assert restarts == [["systemctl", "--user", "-M", "hermes@", "restart", "--no-block", "hermes-gateway.service"]]

    for bad in ("gif-search", "gif-search=maybe", "a=on;rm -rf /=off", "", "x=on," * 101):
        with pytest.raises(Exception):
            await d.prepare("hermes.skills.set", {"changes": bad}, "device:X")
    unknown = await d.prepare("hermes.skills.set", {"changes": "not-installed=on"}, "device:X")
    out = (await d.handle({"cmd": "execute", "request_id": unknown["request_id"], "device_id": PHONE_ID,
                           "choice": "once", "sig": sign(unknown)}))["result"]
    assert not out["ok"] and out["summary"] == "no skill called not-installed"
