"""talaria-publish-app: sign the newest app release for each channel and place it for the bridge (spec §17).

Runs as root from a timer. It reads the repository's GitHub Releases (CI publishes an unsigned installer, its
checksum and release.json for each `vX.Y.Z[-beta.N]` tag) from the releases feed and the release download URLs,
not GitHub's API, whose 60 requests an hour per address other tools on the server use up too (issue 39), checks the checksum, signs the installer with the release
key, which only root can read, checks the signature is that key's, and writes `<out>/<channel>/android.json` and the
signed installer. The bridge only reads them. A channel never goes back to an older version.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

import httpx

from .updates import CHANNELS, MAX_NOTES

log = logging.getLogger("talaria.publish")

MAX_APK = 200 * 1024 * 1024
TAG = re.compile(r"^v(?P<version>(?P<major>[0-9]+)\.(?P<minor>[0-9]+)\.(?P<patch>[0-9]+)(?:-beta\.(?P<beta>[0-9]+))?)$")

Get = Callable[[str], bytes]
Run = Callable[[list[str]], str]


def version_code(version: str) -> int:
    """MAJOR*1000000 + MINOR*10000 + PATCH*100 + N for beta.N, + 99 for a stable release (PROCESS.md, Releases)."""
    t = TAG.match("v" + version)
    if t is None:
        raise ValueError(f"not a release version: {version!r}")
    beta = t["beta"]
    if beta is not None and not 1 <= int(beta) <= 98:
        raise ValueError("beta numbers run from 1 to 98")
    if int(t["minor"]) > 99 or int(t["patch"]) > 99:
        raise ValueError("minor and patch run up to 99")
    return int(t["major"]) * 1_000_000 + int(t["minor"]) * 10_000 + int(t["patch"]) * 100 + (int(beta) if beta else 99)


@dataclass
class Release:
    version: str
    version_code: int
    prerelease: bool
    assets: dict[str, str]  # name -> download URL

    @property
    def apk_name(self) -> str:
        return f"talaria-android-{self.version}-unsigned.apk"


FEED_TAG = re.compile(r"/releases/tag/(v[0-9A-Za-z.+-]+)\"")


def releases(feed: str, repo: str) -> list[Release]:
    """The releases feed (releases.atom) → releases with a valid tag, and where CI put their three assets.
    Drafts aren't in the feed; a beta is a `-beta.N` tag."""
    out, seen = [], set()
    for tag in FEED_TAG.findall(feed):
        t = TAG.match(tag)
        if t is None or tag in seen:
            continue
        seen.add(tag)
        try:
            code = version_code(t["version"])
        except ValueError:
            continue
        base = f"https://github.com/{repo}/releases/download/{tag}/"
        rel = Release(t["version"], code, t["beta"] is not None, {})
        rel.assets = {name: base + name for name in (rel.apk_name, "release.json", "SHA256SUMS")}
        out.append(rel)
    return out


def pick(found: list[Release], channel: str) -> Release | None:
    """The newest release for a channel: betas and stable releases for `beta`, stable releases only for `stable`."""
    fit = [r for r in found if channel == "beta" or not r.prerelease]
    return max(fit, key=lambda r: r.version_code, default=None)


class NotYet(Exception):
    """A release whose files CI hasn't uploaded yet: the next run tries again."""


class Publisher:
    def __init__(self, out: Path, repo: str, keystore: Path, password_file: Path, cert_sha256: str, build_tools: Path,
                 group: str | None = "talaria", get: Get | None = None, run: Run | None = None):
        self.out, self.repo, self.keystore, self.password_file = out, repo, keystore, password_file
        self.cert_sha256 = cert_sha256.lower().replace(":", "")
        self.build_tools, self.group = build_tools, group
        self.get = get or _get
        self.run = run or _run

    def current(self, channel: str) -> int:
        try:
            return int(json.loads((self.out / channel / "android.json").read_text())["version_code"])
        except (OSError, ValueError, KeyError, TypeError):
            return 0

    def publish(self) -> list[str]:
        """One pass over the channels; returns what was placed, as "channel version"."""
        found = releases(self.get(f"https://github.com/{self.repo}/releases.atom").decode("utf-8", "replace"), self.repo)
        signed: dict[str, tuple[Path, dict]] = {}
        placed = []
        with tempfile.TemporaryDirectory(prefix="talaria-publish-") as tmp:
            for channel in CHANNELS:
                # newest first; one whose files CI hasn't uploaded yet waits for the next run, an older ready one goes now
                fitting = sorted((r for r in found if (channel == "beta" or not r.prerelease)
                                  and r.version_code > self.current(channel)), key=lambda r: -r.version_code)
                for rel in fitting:
                    if rel.version not in signed:
                        try:
                            signed[rel.version] = self._sign(rel, Path(tmp))
                        except NotYet:
                            log.info("%s: its files aren't published yet", rel.version)
                            continue
                    self._place(channel, rel, *signed[rel.version])
                    placed.append(f"{channel} {rel.version}")
                    break
        return placed

    def _sign(self, rel: Release, tmp: Path) -> tuple[Path, dict]:
        try:
            apk = self.get(rel.assets[rel.apk_name])
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code == 404:
                raise NotYet(rel.version) from exc
            raise
        if len(apk) > MAX_APK:
            raise RuntimeError(f"{rel.apk_name} is over {MAX_APK // 2**20} MiB")
        sums = {}
        for line in self.get(rel.assets["SHA256SUMS"]).decode().splitlines():
            parts = line.split()
            if len(parts) == 2:
                sums[parts[1].lstrip("*")] = parts[0].lower()  # sha256sum's "<hash>  <name>" (or " *<name>")
        if sums.get(rel.apk_name) != hashlib.sha256(apk).hexdigest():
            raise RuntimeError(f"{rel.apk_name}: the checksum doesn't match SHA256SUMS")
        meta = json.loads(self.get(rel.assets["release.json"]))
        if meta.get("version") != rel.version or meta.get("version_code") != rel.version_code:
            raise RuntimeError(f"release.json doesn't describe {rel.version} ({rel.version_code})")
        unsigned = tmp / rel.apk_name
        unsigned.write_bytes(apk)
        aligned = tmp / f"{rel.version}-aligned.apk"
        tool = lambda name: str(self.build_tools / name)  # noqa: E731
        self.run([tool("zipalign"), "-f", "-p", "4", str(unsigned), str(aligned)])
        out = tmp / f"talaria-android-{rel.version}.apk"
        self.run([tool("apksigner"), "sign", "--ks", str(self.keystore), "--ks-type", "PKCS12",
                  "--ks-pass", f"file:{self.password_file}", "--v4-signing-enabled", "false",
                  "--out", str(out), str(aligned)])
        printed = self.run([tool("apksigner"), "verify", "--print-certs", str(out)])
        digests = re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", printed)
        if digests != [self.cert_sha256]:
            raise RuntimeError(f"{out.name} isn't signed with the release key alone")
        data = out.read_bytes()
        notes = meta.get("notes") if isinstance(meta.get("notes"), str) else ""
        return out, {"version": rel.version, "version_code": rel.version_code, "size": len(data),
                     "sha256": hashlib.sha256(data).hexdigest(), "notes": notes[:MAX_NOTES]}

    def _place(self, channel: str, rel: Release, apk: Path, release: dict) -> None:
        folder = self.out / channel
        folder.mkdir(parents=True, exist_ok=True)
        gid = -1
        if self.group:
            import grp  # Unix only, like the publisher itself; here so the tests (group=None) run anywhere

            gid = grp.getgrnam(self.group).gr_gid
        for path, mode in ((self.out, 0o750), (folder, 0o750)):
            os.chmod(path, mode)
            if gid >= 0:
                os.chown(path, 0, gid)
        name = apk.name
        part = folder / f".{name}.part"
        shutil.copyfile(apk, part)
        os.chmod(part, 0o640)
        if gid >= 0:
            os.chown(part, 0, gid)
        os.replace(part, folder / name)
        meta = folder / ".android.json.part"
        meta.write_text(json.dumps({**release, "file": name, "published_at": int(time.time())}, indent=2) + "\n")
        os.chmod(meta, 0o640)
        if gid >= 0:
            os.chown(meta, 0, gid)
        os.replace(meta, folder / "android.json")  # the bridge sees the new release only once the installer is there
        for old in folder.glob("talaria-android-*.apk"):
            if old.name != name:
                old.unlink()
        log.info("placed %s on %s", rel.version, channel)


def _get(url: str) -> bytes:
    r = httpx.get(url, follow_redirects=True, timeout=300, headers={"User-Agent": "talaria-publish-app"})
    r.raise_for_status()
    return r.content


def _run(argv: list[str]) -> str:
    done = subprocess.run(argv, capture_output=True, text=True, timeout=300)
    if done.returncode != 0:
        raise RuntimeError(f"{Path(argv[0]).name} failed: {(done.stderr or done.stdout).strip()[-500:]}")
    return done.stdout


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="talaria-publish-app", description=__doc__.splitlines()[0])
    p.add_argument("--repo", default="meepdong/talaria")
    p.add_argument("--out", type=Path, default=Path("/var/lib/talaria/updates"))
    p.add_argument("--keystore", type=Path, default=Path("/etc/talaria/release/release.p12"))
    p.add_argument("--password-file", type=Path, default=Path("/etc/talaria/release/password"))
    p.add_argument("--cert-sha256-file", type=Path, default=Path("/etc/talaria/release/cert.sha256"))
    p.add_argument("--build-tools", type=Path, default=Path("/opt/android-build-tools"))
    p.add_argument("--group", default="talaria", help="group that may read what's placed (the bridge's)")
    args = p.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    publisher = Publisher(args.out, args.repo, args.keystore, args.password_file,
                          args.cert_sha256_file.read_text().strip(), args.build_tools, args.group)
    try:
        placed = publisher.publish()
    except Exception as exc:  # one line in the journal; the timer tries again
        print(f"talaria-publish-app: {exc}", file=sys.stderr)
        return 1
    print("placed: " + ", ".join(placed) if placed else "nothing new")
    return 0


if __name__ == "__main__":
    sys.exit(main())
