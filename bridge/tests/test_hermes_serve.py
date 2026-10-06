"""The bridge as a client of `hermes serve` (hermes_serve.py): a fake that speaks its wire format (newline-delimited
JSON-RPC over a WebSocket, gateway.ready first, requests of its own) and its Kanban REST address."""

import asyncio
import json
from http import HTTPStatus

import pytest
import websockets

from talaria_bridge.cli import main as cli_main
from talaria_bridge.hermes_serve import HermesServe, ServeError, ServeUnavailable, probe

TOKEN = "t" * 43

ANSWERS = {
    "gateway.capabilities": {"per_session_exclusive_submit": True},
    "client.capabilities": {"server_requests": ["approval", "clarify", "sudo"], "declines_not_shown": True},
    "profiles.list": {"profiles": [{"name": "default", "model": "qwen/qwen3.8-flash", "skill_count": 66,
                                    "is_default": True}], "bot_mode_protocol": True},
    "groups.list": {"rooms": [{"id": "r1", "name": "Trip planning"}], "next_offset": None},
    "session.list": {"sessions": [{"id": "s1", "title": "Hello", "source": "api_server"}]},
    "cron.manage": {"success": True, "count": 1, "jobs": [{"job_id": "784e2ad17a81", "name": "Morning summary"}]},
    "skills.manage": {"skills": {"devops": ["sdlc-review"], "email": ["himalaya", "email-inbox-triage"]}},
    "delegation.status": {"active": [], "max_concurrent_children": 10},
}


class FakeServe:
    def __init__(self, *, ready: bool = True, ask: str | None = None):
        self.ready = ready
        self.ask = ask  # a server→client request to send before answering the first call
        self.answered: list[dict] = []  # the client's answers to our requests
        self.calls: list[str] = []
        self.kanban_token: str | None = None

    async def process_request(self, connection, request):
        if request.path.startswith("/api/plugins/kanban/board"):
            self.kanban_token = request.headers.get("X-Hermes-Session-Token")
            if self.kanban_token != TOKEN:
                return connection.respond(HTTPStatus.UNAUTHORIZED, "no")
            board = {"columns": [{"name": "todo", "tasks": [{"id": 1}, {"id": 2}]}, {"name": "done", "tasks": []}]}
            return connection.respond(HTTPStatus.OK, json.dumps(board))
        if f"token={TOKEN}" not in request.path:
            return connection.respond(HTTPStatus.FORBIDDEN, "bad token")
        return None

    async def handler(self, ws):
        if self.ready:
            await ws.send(json.dumps({"jsonrpc": "2.0", "method": "event",
                                      "params": {"type": "gateway.ready", "payload": {"skin": {}}}}) + "\n")
        async for raw in ws:
            for line in raw.splitlines():
                msg = json.loads(line)
                if "method" not in msg:
                    self.answered.append(msg)
                    continue
                self.calls.append(msg["method"])
                if self.ask:
                    await ws.send(json.dumps({"jsonrpc": "2.0", "id": "srq-1", "method": self.ask,
                                              "params": {"command": "rm -rf /"}}) + "\n")
                    self.ask = None
                if msg["method"] in ANSWERS:
                    reply = {"jsonrpc": "2.0", "id": msg["id"], "result": ANSWERS[msg["method"]]}
                else:
                    reply = {"jsonrpc": "2.0", "id": msg["id"],
                             "error": {"code": -32601, "message": f"unknown method: {msg['method']}"}}
                # an event and the answer in one frame, as newline-delimited JSON
                event = {"jsonrpc": "2.0", "method": "event", "params": {"type": "sessions.changed", "payload": {}}}
                await ws.send(json.dumps(event) + "\n" + json.dumps(reply) + "\n")


@pytest.fixture
async def serve():
    fake = FakeServe()
    server = await websockets.serve(fake.handler, "127.0.0.1", 0, process_request=fake.process_request)
    port = server.sockets[0].getsockname()[1]
    fake.url = f"ws://127.0.0.1:{port}/api/ws"
    yield fake
    server.close()
    await server.wait_closed()


async def test_calls_answers_errors_and_events(serve):
    events = []
    async with HermesServe(serve.url, TOKEN, on_event=events.append) as hs:
        assert hs.ready == {"skin": {}}
        assert await hs.call("gateway.capabilities") == {"per_session_exclusive_submit": True}
        with pytest.raises(ServeError) as err:
            await hs.call("cli.exec", {"command": "ls"})
        assert err.value.code == -32601
    assert [e["type"] for e in events][:2] == ["gateway.ready", "sessions.changed"]


async def test_the_servers_own_questions_are_declined(serve):
    serve.ask = "approval"
    async with HermesServe(serve.url, TOKEN) as hs:
        await hs.call("gateway.capabilities")
        for _ in range(50):
            if serve.answered:
                break
            await asyncio.sleep(0.01)
    assert serve.answered == [{"jsonrpc": "2.0", "id": "srq-1",
                               "error": {"code": -32601, "message": "Talaria doesn't answer this yet"}}]


async def test_a_wrong_token_or_no_server_is_unavailable(serve):
    with pytest.raises(ServeUnavailable):
        async with HermesServe(serve.url, "wrong"):
            pass
    with pytest.raises(ServeUnavailable):
        async with HermesServe("ws://127.0.0.1:9/api/ws", TOKEN):
            pass


async def test_probe_lists_bots_rooms_jobs_skills_and_the_board(serve):
    lines = {what: (ok, said) for what, ok, said in await probe(serve.url, TOKEN)}
    assert all(ok for ok, _ in lines.values()), lines
    assert lines["bots"][1] == "default (qwen/qwen3.8-flash, 66 skills)"
    assert lines["group chats"][1] == "Trip planning"
    assert lines["jobs"][1] == "Morning summary"
    assert lines["skills"][1] == "3 in 2 groups"
    assert lines["Kanban board"][1] == "2 columns, 2 tasks"
    assert serve.kanban_token == TOKEN
    assert "cli.exec" not in serve.calls  # the probe only reads


async def test_probe_command(serve, tmp_path, capsys):
    key = tmp_path / "serve.key"
    key.write_text(TOKEN + "\n")
    assert await asyncio.to_thread(cli_main, ["hermes-serve", "probe", "--url", serve.url, "--key-file", str(key)]) == 0
    said = capsys.readouterr().out
    assert "✓ bots: default" in said and "✓ Kanban board" in said
