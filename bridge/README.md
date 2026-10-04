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

Each Talaria conversation is a Hermes session, so it also shows up in Hermes's own session list. The bridge keeps which session belongs to which conversation in `chat.db`, along with the shared to-do list (§13). Protocol details: `spec/README.md` §9 and §13.

Photos go to Hermes inline with the message. Other files (PDFs, documents) need a folder Hermes can read: add `"inbox_dir": "/var/lib/talaria/inbox"` to the agent, owned by the bridge's user with Hermes's user in its group, and setgid so new folders keep that group (`chown talaria:hermes`, `chmod 2750`). Without `inbox_dir` the agent takes photos only. Uploads in progress wait in `blobs/` under the bridge's data folder. Protocol details: `spec/README.md` §10.

The apps can pick a chat's model, queue messages while a reply runs, steer a running reply, ask side questions and show a chat's status (`spec/README.md` §11). Nothing to configure: the bridge uses Hermes's own model list and session API.

The apps can browse the files on the server, read-only (`spec/README.md` §12): the inbox, plus any folder you share in the agent's `files` list. For Hermes's workspace:

```json
"files": [{"id": "workspace", "name": "Hermes workspace", "path": "/home/hermes/projects", "agent_path": "/workspace/projects"}]
```

`agent_path` is where Hermes sees the folder (its Docker sandbox mounts `/home/hermes/projects` at `/workspace/projects`), so "Ask Hermes about it" points it at the right file. The bridge's user needs read access, for example `setfacl -m u:talaria:x /home/hermes` and `setfacl -R -m u:talaria:rX,d:u:talaria:rX /home/hermes/projects`. Hidden files and links leading out of the folder are never shown.

To show your OpenRouter balance in the apps, create a **management key** in OpenRouter (Settings → Management keys), save it in a file only the bridge can read (`install -m 600 -o talaria /dev/null /etc/talaria/openrouter.key`, then paste the key into it), and add `"openrouter_key_file": "/etc/talaria/openrouter.key"` to the agent. A management key can also create and delete API keys, so it never leaves the bridge: the apps only get the number.

## Automations, calendar and Home

The apps list Hermes's scheduled jobs as automations and can add, change, pause, run and delete them (`spec/README.md` §14). Nothing to configure: the bridge uses Hermes's jobs API and checks it every minute for changes and finished runs. Jobs made in any Hermes chat show up too. A run whose result goes to Home appears on every device's Home and as a notification; one that goes to a chat becomes a conversation.

"When something arrives" and "after a calendar event" are jobs that check every 10 minutes in their window, answer `[SILENT]` until there's something to do, and leave a marker under `~/.talaria/automations/` in Hermes's workspace so they run once a day at most.

To show the calendar, give the bridge a command that prints a day's events. For Hermes's Google Workspace skill, install [`deploy/calendar-day`](deploy/calendar-day) as its header says (root-owned, run as `hermes` through one sudoers line), check its two paths, and add to the agent:

```json
"calendar_command": ["/usr/bin/sudo", "-n", "-u", "hermes", "/usr/local/lib/talaria/calendar-day"]
```

Test it with `sudo -u talaria /usr/bin/sudo -n -u hermes /usr/local/lib/talaria/calendar-day $(date +%F)`. The bridge never holds the Google token.

## Commands

| Command | What it does |
|---|---|
| `talaria serve --dev` | Local testing: `ws://127.0.0.1:8765/tnp` |
| `talaria serve --behind-proxy --url wss://host:8443/tnp` | Behind a TLS proxy on the same machine, such as `tailscale serve --https=8443 http://127.0.0.1:8765`. Plain `ws://` on loopback only; devices are given the `wss://` address. |
| `talaria serve --url wss://host/tnp --tls-cert C --tls-key K [--pin-cert]` | Real use. `--pin-cert` is for self-signed certificates: devices then pin the certificate key from the pairing link. |
| `talaria pair [--name N] [--ttl 300]` | Shows a QR code, a link and a short code, then asks you to confirm the SAS |
| `talaria devices list` | Paired devices, last seen time and status |
| `talaria devices revoke <id or prefix>` | Revoke a device |
