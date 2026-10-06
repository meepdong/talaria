# AGENTS.md: working on Talaria

For any coding agent. Read this file first; it is enough to find your way. Open other files only when you need them,
and `grep -n` before reading a big one. If you are on the owner's server, read `/root/AGENTS.md` instead (it adds the
server, deployment and release steps).

## What it is

Talaria is a private companion app for a self-hosted AI agent ([Hermes](https://github.com/NousResearch/hermes-agent)).
Three parts, one version number:

- **bridge/** (Python 3.12+): runs next to the agent. Serves the **TNP** protocol (encrypted WebSocket, device
  pairing with signed keys), drives the agent's local API, keeps Talaria's own data (conversations, to-dos, files,
  Talk messages), offers the agent tools over MCP, and runs `talaria-ops` (privileged server operations behind
  per-request device approvals).
- **client/** (Kotlin Multiplatform, Compose): the Android app and the desktop app share `core/` and `ui/`.
- **spec/**: the protocol. `spec/README.md` (sections §1–§17) and one JSON Schema per message in `spec/schemas/`;
  `spec/vectors/` are byte-exact test vectors both sides run.

## Where to change what

```
spec/README.md, spec/schemas/      protocol: §9 chat + Talk, §10 attachments, §11 models, §12 files, §13 to-dos,
                                   §14 automations/Home, §15 agent tools (MCP), §16 server ops, §17 app updates
bridge/src/talaria_bridge/
  server.py        WebSocket server, sessions, routing     cli.py      `talaria` CLI and wiring
  chat.py          chat turns, history, conversations      hermes.py   the agent's API client (SSE)
  talk.py          Talk: voice talker, Whisper transcripts, early turns, voices
  voice.py         speech and quick lines (older Talk path), allowed-models filter
  todos.py, todo_groups.py, automations.py, files.py, blobs.py, terminals.py, updates.py, apppublish.py
  agent_tools.py   MCP tools for the agent                 server_ops.py   ops approvals over TNP
  ops/             the talaria-ops daemon and its operation catalogue
  install.py       `talaria setup` / `talaria doctor`      agents.py   agents.json format
bridge/tests/      pytest, one file per area; conftest.py has schema checks (`check(name, msg)`)
client/core/protocol   TNP encoding and crypto           client/core/session   repositories per area: chat/
client/core/security*  device key storage                  (ChatRepository, VoiceApi), ops/, schedule/, todos/,
                                                           files/, terminal/, updates/; TnpClient, Handshake
client/ui/src/commonMain   Compose screens (ChatScreen, HomeScreen, ScheduleScreen, TodosScreen, FilesScreen,
                           ServerScreen, TerminalScreen), Model.kt (TalariaActions), *Models.kt (view state)
client/ui/src/jvmMain      TalariaController (app logic), TalkerSession (Talk turn-taking), *Mapping.kt
client/androidApp          MainActivity, ConnectionService (foreground), TalkAudio (mic + player), Assistant,
                           notifiers, AppInstaller
client/desktopApp          desktop entry point
tools/tnp-cli              a command-line TNP client (tests and debugging)
docs/                      ARCHITECTURE, PROTOCOL (background), SECURITY, ROADMAP, UI
```

## Build and test

```bash
# bridge (from the repo root)
python3 -m venv .venv && .venv/bin/pip install -e "bridge[test]" -e "tools/tnp-cli[test]"
.venv/bin/python -m pytest bridge tools/tnp-cli -q

# client (JDK 21; the integration tests start a real bridge, so point them at that venv's Python)
cd client && TALARIA_PYTHON=$PWD/../.venv/bin/python ./gradlew :core:protocol:jvmTest :core:session:jvmTest :ui:jvmTest --console=plain
./gradlew :desktopApp:compileKotlinJvm --console=plain        # desktop compiles
./gradlew :androidApp:assembleDebug --console=plain           # needs the Android SDK
```
CI (`.github/workflows/tests.yml`, named "tests") runs the bridge on Linux and Windows and `./gradlew check` plus the
desktop and Android builds. A tag `v*` runs `release.yml`.

## Conventions

- **Spec first.** A new or changed message: `spec/README.md` + a schema in `spec/schemas/`, then the bridge (with a
  test that validates against the schema), then the client.
- **Tests with every change**, written to fail first. UI tests use `testTag`s; keep them stable.
- **Read like the surrounding code:** same naming, comment density and plain-English comments (they explain why).
- **One version** for everything: `client/gradle.properties` `talaria.version`; `ui/.../Version.kt`,
  `bridge/pyproject.toml` and `bridge/src/talaria_bridge/__init__.py` (PEP 440 form, e.g. `0.2.0b18`) must match.
  Releases: `X.Y.Z-beta.N` (versionCode `MAJOR*1000000 + MINOR*10000 + PATCH*100 + N`), stable `X.Y.Z`.
- **CHANGELOG.md**: add user-visible changes under `## [Unreleased]` (Added / Changed / Fixed / Security), in plain words.
- **Never commit** secrets, server names, IPs or personal data: this repository is public.
- Commits: what and why in the message; a trailer naming the agent.

## Traps

- Don't run Python scripts with `bridge/src/talaria_bridge/` as the working directory: its `operator.py` shadows the
  standard library module.
- `strftime("%-d")` fails on Windows (CI runs Windows): use `f"{d.day}"`.
- Talk tests (`bridge/tests/test_talk.py`) use a fake OpenRouter that answers transcription, filler and streamed
  turns separately: a new kind of call needs a branch there.
- The bridge can't read the agent's files; it learns everything through the agent's HTTP API.
