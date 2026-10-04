"""talaria-ops: the root service that runs server operations for the bridge (spec/README.md §16).

It trusts nobody's word for an approval: tier 1-2 operations run only with a paired, unrevoked device's
signature over frame("tnp0-ops-approve", request_id, device_id, op, params_json, choice), checked here
against the bridge's device registry. A compromised bridge or agent can ask for an operation, not approve it.
"""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import grp
import json
import logging
import os
import pwd
import secrets
import socket
import sqlite3
import struct
import sys
import time
from dataclasses import dataclass
from pathlib import Path

from ..protocol import keys
from ..protocol import messages as m
from .audit import AuditLog
from .catalogue import OPS, OUTPUT_LIMIT, Context, OpError, Outcome, Run, Runner, check_params, params_json

log = logging.getLogger("talaria.ops")

APPROVAL_TTL_S = 120
MAX_REQUEST = 64 * 1024


@dataclass
class Prepared:
    op: str
    params: dict
    params_json: str
    tier: int
    summary: str
    requested_by: str
    expires_at: int


async def run_command(argv: list[str], *, user: str | None = None, env: dict | None = None,
                      cwd: str | None = None, timeout: int = 60) -> Run:
    """Run argv without a shell, as [user] if given (root only), with stdout and stderr together."""
    if user is not None:
        argv = ["runuser", "-u", user, "--", *argv]
    full_env = {"PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C.UTF-8"}
    if user is not None:
        full_env["HOME"] = pwd.getpwnam(user).pw_dir
    full_env.update(env or {})
    proc = await asyncio.create_subprocess_exec(*argv, stdout=asyncio.subprocess.PIPE,
                                                stderr=asyncio.subprocess.STDOUT, stdin=asyncio.subprocess.DEVNULL,
                                                cwd=cwd, env=full_env, start_new_session=True)
    try:
        out, _ = await asyncio.wait_for(proc.communicate(), timeout)
    except asyncio.TimeoutError:
        with contextlib.suppress(ProcessLookupError):
            os.killpg(proc.pid, 9)
        out = b""
        with contextlib.suppress(Exception):
            out = (await asyncio.wait_for(proc.communicate(), 5))[0]
        return Run(None, out.decode("utf-8", "replace")[-OUTPUT_LIMIT:])
    return Run(proc.returncode, out.decode("utf-8", "replace")[-OUTPUT_LIMIT:])


class OpsDaemon:
    def __init__(self, registry_db: Path, audit: AuditLog, runner: Runner = run_command,
                 clock=lambda: int(time.time()), **context):
        self.registry_db = registry_db
        self.audit = audit
        self.clock = clock
        self.ctx = Context(run=runner, audit_tail=audit.tail, **context)
        self.prepared: dict[str, Prepared] = {}

    # Requests

    async def handle(self, req: object) -> dict:
        try:
            if not isinstance(req, dict) or not isinstance(req.get("cmd"), str):
                raise OpError("bad request")
            cmd = req["cmd"]
            if cmd == "catalogue":
                return {"ops": await self.catalogue()}
            if cmd == "run":
                return {"result": await self.run(req.get("op"), req.get("params"), self._by(req))}
            if cmd == "prepare":
                return await self.prepare(req.get("op"), req.get("params"), self._by(req))
            if cmd == "execute":
                return {"result": await self.execute(req.get("request_id"), req.get("device_id"),
                                                     req.get("choice"), req.get("sig"))}
            raise OpError(f"unknown command {cmd!r}")
        except OpError as exc:
            return {"error": str(exc)}

    async def catalogue(self) -> list[dict]:
        out = []
        for op in OPS.values():
            params = {}
            for name, spec in op.params.items():
                params[name] = spec.describe(await spec.choices(self.ctx) if spec.choices else None)
            out.append({"op": op.name, "tier": op.tier, "title": op.title, "params": params})
        return out

    async def run(self, name: object, params: object, by: str) -> dict:
        op = self._op(name)
        if op.tier != 0:
            raise OpError(f"{op.name} needs approval: use prepare")
        checked = await check_params(op, params, self.ctx)
        return await self._execute(op.name, checked, by, approved_by=None)

    async def prepare(self, name: object, params: object, by: str) -> dict:
        op = self._op(name)
        if op.tier == 0:
            raise OpError(f"{op.name} needs no approval: use run")
        checked = await check_params(op, params, self.ctx)
        self._expire()
        request_id = "op-" + secrets.token_hex(8)
        p = Prepared(op.name, checked, params_json(checked), op.tier, op.describe_summary(checked), by,
                     self.clock() + APPROVAL_TTL_S)
        self.prepared[request_id] = p
        self.audit.append({"ts": self.clock(), "request_id": request_id, "op": op.name, "params": checked,
                           "tier": op.tier, "requested_by": by, "outcome": "asked"})
        return {"request_id": request_id, "op": p.op, "params_json": p.params_json, "tier": p.tier,
                "summary": p.summary, "expires_at": p.expires_at}

    async def execute(self, request_id: object, device_id: object, choice: object, sig: object) -> dict | None:
        if not all(isinstance(x, str) for x in (request_id, device_id, choice, sig)):
            raise OpError("request_id, device_id, choice and sig are required")
        if choice not in ("once", "deny"):
            raise OpError("choice must be once or deny")
        self._expire()
        p = self.prepared.get(request_id)
        if p is None:
            raise OpError("no such request, or it expired or was already answered")
        public_key = self._device_key(device_id)
        data = m.ops_approve_signed_data(request_id, device_id, p.op, p.params_json, choice)
        if not keys.verify(public_key, data, sig):
            self.audit.append({"ts": self.clock(), "request_id": request_id, "op": p.op, "device_id": device_id,
                               "outcome": "bad signature"})
            raise OpError("signature does not verify")
        del self.prepared[request_id]  # single use, from here on
        if choice == "deny":
            self.audit.append({"ts": self.clock(), "request_id": request_id, "op": p.op, "params": p.params,
                               "tier": p.tier, "requested_by": p.requested_by, "approved_by": device_id,
                               "outcome": "denied"})
            return None
        return await self._execute(p.op, p.params, p.requested_by, approved_by=device_id, request_id=request_id)

    # Internals

    async def _execute(self, name: str, params: dict, by: str, approved_by: str | None,
                       request_id: str | None = None) -> dict:
        op = OPS[name]
        started = self.clock()
        try:
            outcome = await op.do(self.ctx, params)
        except Exception as exc:  # an op's bug must not take the daemon down
            log.exception("%s failed", name)
            outcome = Outcome(False, f"{name} failed: {exc}", "", None)
        result = outcome.as_dict(name, started, self.clock())
        self.audit.append({"ts": started, "request_id": request_id, "op": name, "params": params, "tier": op.tier,
                           "requested_by": by, "approved_by": approved_by,
                           "outcome": "ok" if outcome.ok else "failed", "exit_code": outcome.exit_code,
                           "summary": result["summary"], "duration_s": result["finished_at"] - started})
        return result

    def _op(self, name: object):
        if not isinstance(name, str) or name not in OPS:
            raise OpError(f"unknown operation {name!r}")
        return OPS[name]

    @staticmethod
    def _by(req: dict) -> str:
        by = req.get("requested_by")
        return by[:128] if isinstance(by, str) and by else "unknown"

    def _expire(self) -> None:
        now = self.clock()
        for rid in [r for r, p in self.prepared.items() if p.expires_at <= now]:
            p = self.prepared.pop(rid)
            self.audit.append({"ts": now, "request_id": rid, "op": p.op, "requested_by": p.requested_by,
                               "outcome": "expired"})

    def _device_key(self, device_id: str):
        try:
            db = sqlite3.connect(f"file:{self.registry_db}?mode=ro", uri=True)
            try:
                row = db.execute("SELECT public_key, revoked_at FROM devices WHERE device_id = ?",
                                 (device_id,)).fetchone()
            finally:
                db.close()
        except sqlite3.Error as exc:
            raise OpError(f"cannot read the device registry: {exc}") from exc
        if row is None:
            raise OpError("unknown device")
        if row[1] is not None:
            raise OpError("device is revoked")
        return keys.load_public_key(row[0])

    # Socket

    async def serve(self, path: Path, allowed_uids: set[int], group_gid: int | None = None):
        """Listen on the Unix socket [path]; only processes running as one of [allowed_uids] are answered."""
        path.parent.mkdir(parents=True, exist_ok=True)
        with contextlib.suppress(FileNotFoundError):
            path.unlink()

        async def client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
            sock = writer.get_extra_info("socket")
            uid = struct.unpack("3i", sock.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED,
                                                      struct.calcsize("3i")))[1]
            try:
                if uid not in allowed_uids:
                    log.warning("refused a connection from uid %s", uid)
                    return
                while line := await reader.readline():
                    try:
                        req = json.loads(line)
                    except ValueError:
                        answer = {"error": "bad request"}
                    else:
                        answer = await self.handle(req)
                    writer.write(json.dumps(answer, ensure_ascii=False).encode() + b"\n")
                    await writer.drain()
            except (ConnectionError, asyncio.LimitOverrunError, ValueError):
                pass
            finally:
                writer.close()

        server = await asyncio.start_unix_server(client, path=str(path), limit=MAX_REQUEST)
        if group_gid is not None:
            os.chown(path.parent, 0, group_gid)
            os.chmod(path.parent, 0o750)
            os.chown(path, 0, group_gid)
        os.chmod(path, 0o660)
        return server


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="talaria-ops", description="Run server operations for the Talaria bridge.")
    ap.add_argument("--socket", type=Path, default=Path("/run/talaria-ops/ops.sock"))
    ap.add_argument("--registry", type=Path, default=Path("/var/lib/talaria/bridge.db"))
    ap.add_argument("--audit", type=Path, default=Path("/var/log/talaria-ops/audit.jsonl"))
    ap.add_argument("--allow-user", default="talaria", help="the user the bridge runs as")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")
    if os.geteuid() != 0:
        print("talaria-ops must run as root", file=sys.stderr)
        return 2
    user = pwd.getpwnam(args.allow_user)
    daemon = OpsDaemon(args.registry, AuditLog(args.audit))

    async def go() -> None:
        server = await daemon.serve(args.socket, {0, user.pw_uid}, grp.getgrgid(user.pw_gid).gr_gid)
        print(f"talaria-ops: {len(OPS)} operations on {args.socket} for {args.allow_user}", flush=True)
        async with server:
            await server.serve_forever()

    asyncio.run(go())
    return 0


if __name__ == "__main__":
    sys.exit(main())
