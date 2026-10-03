# Talaria bridge (M0)

The bridge runs next to your agent. M0 covers the device registry, secure pairing and the signed TNP handshake with heartbeats. The MCP tools, events and chat come in later milestones.

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

## Commands

| Command | What it does |
|---|---|
| `talaria serve --dev` | Local testing: `ws://127.0.0.1:8765/tnp` |
| `talaria serve --url wss://host/tnp --tls-cert C --tls-key K [--pin-cert]` | Real use. `--pin-cert` is for self-signed certificates: devices then pin the certificate key from the pairing link. |
| `talaria pair [--name N] [--ttl 300]` | Shows a QR code, a link and a short code, then asks you to confirm the SAS |
| `talaria devices list` | Paired devices, last seen time and status |
| `talaria devices revoke <id or prefix>` | Revoke a device |
