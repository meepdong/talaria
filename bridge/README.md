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
  Each agent is checked every 30 s. A 2xx answer is `ready` (or `degraded` if its JSON `status` says otherwise), any other HTTP status is `degraded`, and no answer is `offline`.

## Chat

Chat (M2) goes through the bridge to the Hermes API server, so devices never hold its key. Turn on the API server in Hermes (`API_SERVER_ENABLED=true` and an `API_SERVER_KEY` in `~/.hermes/.env`), put the key in a file only the bridge can read, and add `api_url` and `api_key_file` to the agent in `agents.json`:

```json
{"agents": [{"id": "hermes", "name": "Hermes", "health_url": "http://127.0.0.1:9119/api/status",
             "api_url": "http://127.0.0.1:8642", "api_key_file": "/etc/talaria/hermes-api.key"}]}
```

Each Talaria conversation is a Hermes session, so it also shows up in Hermes's own session list. The bridge keeps which session belongs to which conversation in `chat.db`. Protocol details: `spec/README.md` §9.

Photos go to Hermes inline with the message. Other files (PDFs, documents) need a folder Hermes can read: add `"inbox_dir": "/var/lib/talaria/inbox"` to the agent, owned by the bridge's user with Hermes's user in its group, and setgid so new folders keep that group (`chown talaria:hermes`, `chmod 2750`). Without `inbox_dir` the agent takes photos only. Uploads in progress wait in `blobs/` under the bridge's data folder. Protocol details: `spec/README.md` §10.

## Commands

| Command | What it does |
|---|---|
| `talaria serve --dev` | Local testing: `ws://127.0.0.1:8765/tnp` |
| `talaria serve --behind-proxy --url wss://host:8443/tnp` | Behind a TLS proxy on the same machine, such as `tailscale serve --https=8443 http://127.0.0.1:8765`. Plain `ws://` on loopback only; devices are given the `wss://` address. |
| `talaria serve --url wss://host/tnp --tls-cert C --tls-key K [--pin-cert]` | Real use. `--pin-cert` is for self-signed certificates: devices then pin the certificate key from the pairing link. |
| `talaria pair [--name N] [--ttl 300]` | Shows a QR code, a link and a short code, then asks you to confirm the SAS |
| `talaria devices list` | Paired devices, last seen time and status |
| `talaria devices revoke <id or prefix>` | Revoke a device |
