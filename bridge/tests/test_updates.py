import base64
import re
import hashlib
import json
from pathlib import Path

import pytest
from conftest import Bridge, check

from talaria_bridge import __version__
from talaria_bridge.apppublish import Publisher, pick, releases, version_code
from talaria_bridge.protocol import keys
from talaria_bridge.protocol import messages as m
from talaria_bridge.registry import Registry
from talaria_bridge.server import BridgeServer, ServerSettings
from talaria_bridge.updates import AppUpdates

from test_chat import call, connected, recv

CERT = "ab" * 32


def place(folder: Path, channel: str, version: str, apk: bytes) -> dict:
    d = folder / channel
    d.mkdir(parents=True, exist_ok=True)
    name = f"talaria-android-{version}.apk"
    (d / name).write_bytes(apk)
    release = {"version": version, "version_code": version_code(version), "size": len(apk),
               "sha256": hashlib.sha256(apk).hexdigest(), "published_at": 1_790_000_000, "notes": "Fixes."}
    (d / "android.json").write_text(json.dumps({**release, "file": name}))
    return release


def test_version_codes():
    assert version_code("0.2.0-beta.3") == 20003
    assert version_code("0.2.0") == 20099
    assert version_code("1.0.0-beta.1") > version_code("0.99.99")
    assert version_code("0.2.1-beta.1") > version_code("0.2.0")
    for bad in ("0.2", "0.2.0-rc.1", "0.100.0", "0.2.0-beta.99", "0.2.0-beta.0"):
        with pytest.raises(ValueError):
            version_code(bad)



def test_the_bridge_has_the_apps_version():
    """PROCESS.md, Releases: client/gradle.properties is the version; the bridge says it in PEP 440 form."""
    props = (Path(__file__).parents[2] / "client" / "gradle.properties").read_text()
    version = re.search(r"^talaria\.version=(.+)$", props, re.M).group(1).strip()
    assert __version__ == re.sub(r"-beta\.(\d+)$", r"b\1", version)
    pyproject = (Path(__file__).parents[1] / "pyproject.toml").read_text()
    assert f'version = "{__version__}"' in pyproject

@pytest.fixture
async def updates_bridge(tmp_path: Path, settings: ServerSettings):
    registry = Registry(tmp_path / "bridge.db")
    updates = AppUpdates(tmp_path / "updates", "beta")
    server = BridgeServer(registry, keys.generate_key(), settings, updates=updates)
    async with server.serve() as ws_server:
        port = ws_server.sockets[0].getsockname()[1]
        yield Bridge(server, registry, f"ws://127.0.0.1:{port}/tnp"), updates
    registry.close()


async def test_devices_find_and_read_the_release(updates_bridge, tmp_path: Path, monkeypatch):
    bridge, updates = updates_bridge
    ws = await connected(bridge)
    check("app.latest", m.request("1", "app.latest", {"platform": "android"}))
    none = check("app.latest.result", await call(ws, "1", "app.latest", {"platform": "android"}))["result"]
    assert none == {"channel": "beta"}
    missing = await call(ws, "2", "app.read", {"platform": "android", "version_code": 20001})
    assert missing["error"]["code"] == m.NOT_FOUND

    apk = bytes(range(256)) * 5000  # 1.2 MiB: three chunks
    release = place(tmp_path / "updates", "beta", "0.2.0-beta.1", apk)
    place(tmp_path / "updates", "stable", "0.1.9", b"old")
    latest = check("app.latest.result", await call(ws, "3", "app.latest", {"platform": "android"}))["result"]
    assert latest == {"channel": "beta", "release": release}

    got, offset = b"", 0
    while True:
        params = {"platform": "android", "version_code": release["version_code"], "offset": offset}
        check("app.read", m.request("r", "app.read", params))
        chunk = check("app.read.result", await call(ws, f"r{offset}", "app.read", params))["result"]
        got += base64.b64decode(chunk["data"])
        offset += len(base64.b64decode(chunk["data"]))
        if chunk["eof"]:
            break
    assert got == apk and hashlib.sha256(got).hexdigest() == release["sha256"]

    stale = await call(ws, "4", "app.read", {"platform": "android", "version_code": 20000})
    assert stale["error"]["code"] == m.CONFLICT
    for bad in ({"platform": "ios", "version_code": 20001}, {"platform": "android", "version_code": "x"},
                {"platform": "android", "version_code": 20001, "offset": len(apk) + 1}):
        await ws.send(m.encode(m.request("b", "app.read", bad)))
        assert (await recv(ws))["error"]["code"] == m.INVALID_PARAMS

    # a new release reaches every device once
    seen: dict[str, int] = {}
    await updates.check(seen)
    newer = place(tmp_path / "updates", "beta", "0.2.0-beta.2", b"newer apk")
    await updates.check(seen)
    note = check("app.available", await recv(ws))
    assert note["params"] == {"platform": "android", "channel": "beta", "release": newer}
    await updates.check(seen)
    await ws.close()


def test_a_broken_release_file_is_ignored(tmp_path: Path):
    updates = AppUpdates(tmp_path, "stable")
    (tmp_path / "stable").mkdir()
    for text in ("not json", json.dumps({"version": "0.2.0", "file": "../x.apk", "version_code": 20099, "size": 1,
                                         "sha256": "0" * 64, "published_at": 1})):
        (tmp_path / "stable" / "android.json").write_text(text)
        assert updates.latest({"platform": "android"}) == {"channel": "stable"}
    with pytest.raises(ValueError):
        AppUpdates(tmp_path, "nightly")


REPO = "meepdong/talaria"


def feed(*tags: str) -> str:
    """releases.atom with these tags, newest first, as GitHub writes it."""
    entries = "".join(f'<entry><link rel="alternate" type="text/html" href="https://github.com/{REPO}/releases/tag/{t}"/>'
                      f"<title>Talaria {t}</title></entry>" for t in tags)
    return f'<?xml version="1.0"?><feed><link href="https://github.com/{REPO}/releases"/>{entries}</feed>'


def url(version: str, name: str) -> str:
    return f"https://github.com/{REPO}/releases/download/v{version}/{name}"


def test_channels_pick_the_newest_fitting_release():
    found = releases(feed("v0.3.0-beta.1", "v0.2.0", "v0.2.0-beta.4", "nightly", "v0.2.0"), REPO)
    assert [r.version for r in found] == ["0.3.0-beta.1", "0.2.0", "0.2.0-beta.4"]
    assert found[0].assets["SHA256SUMS"] == url("0.3.0-beta.1", "SHA256SUMS")
    assert pick(found, "beta").version == "0.3.0-beta.1"
    assert pick(found, "stable").version == "0.2.0"
    assert pick([], "stable") is None


class FakeGitHub:
    def __init__(self, tags: list[str], files: dict[str, bytes]):
        self.tags, self.files, self.fetched = tags, files, []

    def get(self, u: str) -> bytes:
        self.fetched.append(u)
        assert "api.github.com" not in u, "no API: its quota runs out (issue 39)"
        if u.endswith("/releases.atom"):
            return feed(*self.tags).encode()
        if u not in self.files:
            import httpx
            raise httpx.HTTPStatusError("404", request=httpx.Request("GET", u), response=httpx.Response(404))
        return self.files[u]


def publish_files(version: str, apk: bytes, sums_apk: bytes | None = None, meta: dict | None = None) -> dict[str, bytes]:
    name = f"talaria-android-{version}-unsigned.apk"
    return {
        url(version, name): apk,
        url(version, "SHA256SUMS"): f"{hashlib.sha256(sums_apk or apk).hexdigest()}  {name}\n".encode(),
        url(version, "release.json"): json.dumps(
            meta or {"version": version, "version_code": version_code(version), "notes": "New: updates."}).encode(),
    }


def fake_tools(cert: str = CERT):
    ran = []

    def run(argv: list[str]) -> str:
        ran.append(argv)
        tool = Path(argv[0]).name
        if tool == "zipalign":
            Path(argv[-1]).write_bytes(Path(argv[-2]).read_bytes())
        elif argv[1] == "sign":
            Path(argv[argv.index("--out") + 1]).write_bytes(b"SIGNED:" + Path(argv[-1]).read_bytes())
        else:
            return f"Signer #1 certificate SHA-256 digest: {cert}\n"
        return ""
    return run, ran


def publisher(tmp_path: Path, gh: FakeGitHub, run) -> Publisher:
    return Publisher(tmp_path / "out", "meepdong/talaria", tmp_path / "k.p12", tmp_path / "pw", CERT.upper(),
                     tmp_path / "bt", group=None, get=gh.get, run=run)


def test_publisher_signs_and_places_each_channel(tmp_path: Path):
    gh = FakeGitHub(["v0.2.0-beta.1", "v0.1.0"],
                    {**publish_files("0.2.0-beta.1", b"beta apk"), **publish_files("0.1.0", b"stable apk")})
    run, ran = fake_tools()
    pub = publisher(tmp_path, gh, run)
    assert pub.publish() == ["beta 0.2.0-beta.1", "stable 0.1.0"]
    beta = json.loads((tmp_path / "out" / "beta" / "android.json").read_text())
    assert beta["file"] == "talaria-android-0.2.0-beta.1.apk" and beta["notes"] == "New: updates."
    signed = (tmp_path / "out" / "beta" / beta["file"]).read_bytes()
    assert signed == b"SIGNED:beta apk" and beta["sha256"] == hashlib.sha256(signed).hexdigest()
    assert beta["size"] == len(signed) and beta["version_code"] == 20001
    sign = next(a for a in ran if a[1:2] == ["sign"])
    assert sign[sign.index("--ks-pass") + 1] == f"file:{tmp_path / 'pw'}"
    assert AppUpdates(tmp_path / "out", "beta").latest({"platform": "android"})["release"]["version"] == "0.2.0-beta.1"

    # nothing new: nothing signed again
    ran.clear()
    assert pub.publish() == [] and ran == []

    # a stable release reaches both channels, and the old installer goes
    gh.tags.insert(0, "v0.3.0-beta.1")  # CI hasn't uploaded its files yet: skipped, no error
    assert pub.publish() == []
    gh.tags.insert(0, "v0.2.0")
    gh.files.update(publish_files("0.2.0", b"stable 0.2"))
    assert pub.publish() == ["beta 0.2.0", "stable 0.2.0"]
    assert sum(1 for a in ran if a[1:2] == ["sign"]) == 1, "signed once for both channels"
    assert [p.name for p in (tmp_path / "out" / "beta").glob("*.apk")] == ["talaria-android-0.2.0.apk"]


@pytest.mark.parametrize("problem", ["checksum", "meta", "cert"])
def test_publisher_refuses_what_it_cant_trust(tmp_path: Path, problem: str):
    files = publish_files("0.2.0-beta.1", b"beta apk",
                          sums_apk=b"other" if problem == "checksum" else None,
                          meta={"version": "0.2.0-beta.1", "version_code": 1} if problem == "meta" else None)
    run, _ = fake_tools(cert="cd" * 32 if problem == "cert" else CERT)
    pub = publisher(tmp_path, FakeGitHub(["v0.2.0-beta.1"], files), run)
    with pytest.raises(RuntimeError):
        pub.publish()
    assert not (tmp_path / "out" / "beta" / "android.json").exists()
