# Talaria bridge

The bridge runs next to your agent. M0 covers the device registry, secure pairing and the signed TNP handshake with heartbeats. M1 adds the connection status report (`status.get`), agent health checks and `--behind-proxy`. The MCP tools, events and chat come in later milestones.

Byte formats and test vectors live in [`../spec/`](../spec/README.md). The protocol itself is in [`../docs/PROTOCOL.md`](../docs/PROTOCOL.md).

## Install (Windows PowerShell; on macOS or Linux use `python3.12` and `.venv/bin/`)

From the repository root:

```
py -3.12 -m venv .venv
.venv\Scripts\python -m pip install -e "bridge[test]" -e "tools/tnp-cli[test]"
```

## Run the tests

```
.venv\Scripts\python -m pytest bridge tools/tnp-cli
```

## Try a pairing on one computer

Open three terminals at the repository root.

1. Start the bridge in local test mode (plain `ws://`, loopback only):
   ```
   .venv\Scripts\talaria serve --dev
   ```
2. Create a pairing link:
   ```
   .venv\Scripts\talaria pair --name "Test client"
   ```
3. Pair the test client with the link that step 2 printed:
   ```
   .venv\Scripts\tnp-cli pair "talaria://pair#…"
   ```
   Both terminals show a 6-digit code and 3 emoji. Type `y` in the `talaria pair` terminal only if they match.
4. Connect with the paired client:
   ```
   .venv\Scripts\tnp-cli connect --once
   .venv\Scripts\talaria devices list
   ```

To pair by short code instead, use `tnp-cli pair --code ABCD-1234 --url ws://127.0.0.1:8765/tnp`.

`talaria devices revoke <id>` revokes a device. Its open session closes within a second, and that key can't connect or pair again.

## Data

Everything is stored in `~/.talaria` (`%USERPROFILE%\.talaria` on Windows), or `$TALARIA_HOME` if set:

- `bridge_key.pem`: the bridge identity key. Keep it private. If you lose it, every device has to pair again.
- `bridge.db`: devices, pairing tokens and pending requests (SQLite).
- `agents.json` (optional): agents whose health the status report shows. The bridge reads it at start, so restart after editing it:
  ```json
  {"agents": [{"id": "hermes", "name": "Hermes", "health_url": "http://127.0.0.1:8642/health"}]}
  ```
  Each agent is checked every 30 s. A 2xx answer is `ready` (or `degraded` if its JSON `status` says otherwise), any other HTTP status is `degraded`, and no answer is `offline`. If the health URL doesn't answer but the agent has an `api_url` whose `/health` does (Hermes's dashboard is a separate process from its API server), the agent counts as `ready` with a note.

## Chat

Chat (M2) goes through the bridge to the Hermes API server, so devices never hold its key. Turn on the API server in Hermes (`API_SERVER_ENABLED=true` and an `API_SERVER_KEY` in `~/.hermes/.env`), put the key in a file only the bridge can read, and add `api_url` and `api_key_file` to the agent in `agents.json`:

```json
{"agents": [{"id": "hermes", "name": "Hermes", "health_url": "http://127.0.0.1:9119/api/status",
             "api_url": "http://127.0.0.1:8642", "api_key_file": "/etc/talaria/hermes-api.key"}]}
```

Each Talaria conversation is a Hermes session, so it also shows up in Hermes's own session list. The bridge keeps which session belongs to which conversation in `chat.db`, along with the shared to-do list (§13). Hermes sorts new to-dos into groups in short throwaway sessions that are deleted straight after, so they never stay in its session list. Protocol details: `spec/README.md` §9 and §13.

Photos go to Hermes inline with the message. Other files (PDFs, documents) need a folder Hermes can read: add `"inbox_dir": "/var/lib/talaria/inbox"` to the agent, owned by the bridge's user with Hermes's user in its group, and setgid so new folders keep that group (`chown talaria:hermes`, `chmod 2750`). Without `inbox_dir` the agent takes photos only. Uploads in progress wait in `blobs/` under the bridge's data folder. Protocol details: `spec/README.md` §10.

The apps can pick a chat's model, queue messages while a reply runs, steer a running reply, ask side questions and show a chat's status (`spec/README.md` §11). Nothing to configure: the bridge uses Hermes's own model list and session API.

The apps can browse the files on the server, read-only (`spec/README.md` §12): the inbox, plus any folder you share in the agent's `files` list. For Hermes's workspace:

```json
"files": [{"id": "workspace", "name": "Hermes workspace", "path": "/home/hermes/projects", "agent_path": "/workspace/projects"}]
```

`agent_path` is where Hermes sees the folder (its Docker sandbox mounts `/home/hermes/projects` at `/workspace/projects`), so "Ask Hermes about it" points it at the right file. The bridge's user needs read access, for example `setfacl -m u:talaria:x /home/hermes` and `setfacl -R -m u:talaria:rX,d:u:talaria:rX /home/hermes/projects`. Hidden files and links leading out of the folder are never shown. If the bridge runs sandboxed with `ProtectHome=yes`, it can't see `/home` at all and the workspace shows "No such file or folder": install [`deploy/talaria-bridge-workspace.conf`](deploy/talaria-bridge-workspace.conf) as a drop-in (`/etc/systemd/system/talaria-bridge.service.d/40-workspace.conf`), which leaves `/home` empty except the workspace, bound in read-only.

To show your OpenRouter balance in the apps, create a **management key** in OpenRouter (Settings → Management keys), save it in a file only the bridge can read (`install -m 600 -o talaria /dev/null /etc/talaria/openrouter.key`, then paste the key into it), and add `"openrouter_key_file": "/etc/talaria/openrouter.key"` to the agent. A management key can also create and delete API keys, so it never leaves the bridge: the apps only get the number.

## Automations, calendar and Home

The apps list Hermes's scheduled jobs as automations and can add, change, pause, run and delete them (`spec/README.md` §14). Nothing to configure: the bridge uses Hermes's jobs API and checks it every minute for changes and finished runs. Jobs made in any Hermes chat show up too. A run whose result goes to Home appears on every device's Home and as a notification; one that goes to a chat becomes a conversation.

"When something arrives" and "after a calendar event" are jobs that check every 10 minutes in their window, answer `[SILENT]` until there's something to do, and leave a marker under `~/.talaria/automations/` in Hermes's workspace so they run once a day at most.

To show the calendar, give the bridge a command that prints a day's events. For Hermes's Google Workspace skill, install [`deploy/calendar-day`](deploy/calendar-day) as its header says (root-owned, run as `hermes` through one sudoers line), check its two paths, and add to the agent:

```json
"calendar_command": ["/usr/bin/sudo", "-n", "-u", "hermes", "/usr/local/lib/talaria/calendar-day"]
```

Test it with `sudo -u talaria /usr/bin/sudo -n -u hermes /usr/local/lib/talaria/calendar-day $(date +%F)`. The bridge never holds the Google token.

## Tools for the agent

The bridge can give Hermes tools to keep the to-do list (`spec/README.md` §15): list, add, reword, group, comment on and tick off. To-dos it adds in any chat or automation show up on every device. The tools are an MCP server on `http://127.0.0.1:8767/mcp`, reachable only from the server itself, and each agent needs its own token. Make one in a file only the bridge can read and add it to the agent:

```sh
install -m 600 -o talaria /dev/null /etc/talaria/hermes-tools.key
openssl rand -hex 32 > /etc/talaria/hermes-tools.key
```

```json
"tools_key_file": "/etc/talaria/hermes-tools.key"
```

Then give Hermes the same token in `~/.hermes/.env` as `TALARIA_TOOLS_KEY=…` and add the server to `~/.hermes/config.yaml`:

```yaml
mcp_servers:
  talaria:
    url: "http://127.0.0.1:8767/mcp"
    headers:
      Authorization: "Bearer ${TALARIA_TOOLS_KEY}"
```

Restart the bridge, then Hermes (or `/reload-mcp` in a Hermes chat). `--agent-tools-port` changes the port. There is no delete tool, so a prompt that tricks the agent can't wipe the list; deleting stays in the apps.

## Server operations (talaria-ops)

`talaria-ops` lets the owner, and the agent with the owner's approval, manage the server from the apps: overview, services, logs, Docker, Tailscale, restarts, bridge update, disk cleanup, package upgrades and reboot (spec README §16, PROTOCOL §10.8). It runs as **root**, so install it from a **root-owned copy**, never from the bridge's checkout. That way the bridge user can't change what root runs:

```bash
sudo python3 -m venv /opt/talaria-ops/venv
sudo /opt/talaria-ops/venv/bin/pip install /path/to/talaria/bridge        # not -e
sudo install -m 644 deploy/talaria-ops.service /etc/systemd/system/
sudo install -d /etc/systemd/system/talaria-bridge.service.d
sudo install -m 644 deploy/talaria-bridge-ops.conf /etc/systemd/system/talaria-bridge.service.d/20-ops.conf
sudo systemctl daemon-reload && sudo systemctl enable --now talaria-ops && sudo systemctl restart talaria-bridge
```

The bridge offers `ops.*` to devices and `server_op` to the agent once it can reach `/run/talaria-ops/ops.sock` (`--ops-socket` changes it). To upgrade talaria-ops, repeat the `pip install` and `systemctl restart talaria-ops`. The `bridge.update` operation updates only the bridge. Every operation is logged to `/var/log/talaria-ops/audit.jsonl`.

## App updates

The apps update themselves from the bridge (spec README §17). A tag `vX.Y.Z[-beta.N]` makes CI publish an unsigned
Android build as a GitHub Release. On the server, `talaria-publish-app` (root, every 10 minutes) checks its checksum,
signs it with the **release key** and places it in `<data>/updates/<channel>/`, where the bridge reads it. The key never
leaves the server and never enters the repository. The bridge offers the `stable` channel unless
`TALARIA_UPDATE_CHANNEL=beta` (or `--update-channel beta`) is set.

One-time setup, as root (`build-tools` 35 or later; take the current file name from Google's repository listing):

```bash
install -d -m 700 /etc/talaria/release
openssl rand -base64 32 | tr -d '\n' > /etc/talaria/release/password && chmod 600 /etc/talaria/release/password
keytool -genkeypair -keystore /etc/talaria/release/release.p12 -storetype PKCS12 -alias talaria -keyalg RSA \
  -keysize 4096 -validity 10950 -dname "CN=Talaria release" \
  -storepass:file /etc/talaria/release/password -keypass:file /etc/talaria/release/password
keytool -list -v -keystore /etc/talaria/release/release.p12 -storepass:file /etc/talaria/release/password \
  | sed -n 's/.*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f' > /etc/talaria/release/cert.sha256
# apksigner and zipalign, without the rest of the SDK
curl -fsSLo /tmp/bt.zip https://dl.google.com/android/repository/build-tools_<version>_linux.zip
unzip -q /tmp/bt.zip -d /opt && mv /opt/android-* /opt/android-build-tools
sudo /opt/talaria-ops/venv/bin/pip install /path/to/talaria/bridge        # gives talaria-publish-app
install -m 644 deploy/talaria-publish-app.service deploy/talaria-publish-app.timer /etc/systemd/system/
install -m 644 deploy/talaria-bridge-updates.conf /etc/systemd/system/talaria-bridge.service.d/30-updates.conf  # owner's bridge only
systemctl daemon-reload && systemctl enable --now talaria-publish-app.timer && systemctl restart talaria-bridge
```

**Back up `/etc/talaria/release/` somewhere safe and offline.** Without that key, no installed app accepts another
update: every device would have to uninstall and pair again. Releasing: PROCESS.md "Releases" in the deployment notes.

## Commands

| Command | What it does |
|---|---|
| `talaria serve --dev` | Local testing: `ws://127.0.0.1:8765/tnp` |
| `talaria serve --behind-proxy --url wss://host:8443/tnp` | Behind a TLS proxy on the same machine, such as `tailscale serve --https=8443 http://127.0.0.1:8765`. Plain `ws://` on loopback only; devices are given the `wss://` address. |
| `talaria serve --url wss://host/tnp --tls-cert C --tls-key K [--pin-cert]` | Real use. `--pin-cert` is for self-signed certificates: devices then pin the certificate key from the pairing link. |
| `talaria pair [--name N] [--ttl 300]` | Shows a QR code, a link and a short code, then asks you to confirm the SAS |
| `talaria devices list` | Paired devices, last seen time and status |
| `talaria devices revoke <id or prefix>` | Revoke a device |
