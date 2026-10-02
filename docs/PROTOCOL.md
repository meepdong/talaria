# Talaria Node Protocol (TNP) — v0.1 draft

Status: **draft, unstable**. Everything here may change before v1.0. The key words MUST, SHOULD and MAY follow RFC 2119.

## 1. Goals and non-goals

**Goals**
- Any device can announce what it can do and nothing more.
- Mutual authentication without long-lived bearer tokens on the device.
- At-least-once delivery of device events, with deduplication.
- The device has the final say on every sensitive action.
- Simple to implement: JSON over WebSocket, JSON-RPC 2.0 framing.

**Non-goals (v0)**
- Device-to-device communication (everything goes through the bridge).
- Large media streaming (photos go through a separate upload; live audio/video is out of scope for v0).
- Multi-user bridges (one bridge per person in v0).

## 2. Transport

| Item | Value |
|---|---|
| Transport | WebSocket over TLS (`wss://`) |
| Subprotocol | `tnp.v0` (sent in `Sec-WebSocket-Protocol`) |
| Endpoint | `wss://<bridge-host>/tnp` |
| Framing | One JSON-RPC 2.0 message per text frame, UTF-8 |
| Max frame | 1 MiB. Larger payloads use `blob.upload` (§9). |
| Network | A private network (e.g. Tailscale) is RECOMMENDED. The bridge SHOULD NOT be exposed to the public internet. |

TLS protects the channel. **Authentication does not rely on TLS**: both sides prove their identity with signatures (§3), so a misconfigured proxy cannot impersonate either side.

## 3. Identity, pairing and authentication

### 3.1 Keys

- **Bridge identity:** an ECDSA P-256 key pair created on first start. `bridge_id` = base32(SHA-256(public key))[0:26].
- **Device identity:** an ECDSA P-256 key pair created on the device inside the OS keystore and marked non-exportable. `device_id` is derived the same way.
- P-256 is chosen because it is the one curve hardware-backed on every target: Android Keystore/StrongBox, Apple Secure Enclave, and Windows CNG/TPM.

### 3.2 Pairing (one time per device)

1. On the server: `talaria pair --name "OnePlus 10 Pro"` prints a QR code containing:
   ```json
   {"tnp": 0, "url": "wss://meep-vps.tailnet.ts.net/tnp",
    "bridge_id": "K7Q2…", "bridge_pk": "<base64 SPKI>",
    "pair_token": "<128-bit random, base64url>", "exp": 1790000000}
   ```
   The token is single-use and expires in 5 minutes.
2. The device scans the QR, **pins `bridge_pk`**, generates its key pair, and connects.
3. The device sends `pair.request` with `pair_token`, its public key, its name and platform.
4. The bridge verifies and consumes the token, stores the device public key, and replies `pair.accepted`.
5. Manual fallback: an 8-character code shown by the CLI, with a rate limit of 5 attempts per code.

Revocation: `talaria devices revoke <device_id>`. Any open session closes immediately and the key is rejected from then on.

### 3.3 Connection handshake (every connection)

```
Device                                Bridge
  │── WebSocket connect (tnp.v0) ──────▶│
  │◀── hello {bridge_id, nonce_b, ts, sig_b} ─│   sig_b = Sign_bridge("tnp0-hello" ‖ bridge_id ‖ nonce_b ‖ ts)
  │   verify sig_b with pinned bridge_pk  │
  │── auth {device_id, nonce_d, ts, sig_d, resume} ─▶│
  │                                       │   sig_d = Sign_device("tnp0-auth" ‖ bridge_id ‖ device_id ‖ nonce_b ‖ nonce_d ‖ ts)
  │◀── auth.ok {session_id, last_acked_seq, server_time} ─│
  │── capabilities.announce {...} ───────▶│
  │◀── ready ────────────────────────────│
```

- `hello` and `ready` are notifications. `auth` is a request, and `auth.ok` is its successful result.
- Timestamps MUST be within ±120 s, and nonces MUST NOT repeat within that window.
- On a signature failure the bridge closes with WebSocket code **4401**. On a revoked device it closes with **4403**.
- `resume` lets the device continue its event sequence (§6.2).

### 3.4 Heartbeat and reconnect

- Either side sends `ping` every 30 s while idle. With no traffic for 90 s, the connection is considered dead.
- Reconnect backoff: 1 s, 2 s, 4 s … capped at 60 s, with ±20% jitter. Reset after 5 minutes connected.
- Android clients SHOULD keep the session in a foreground service (`foregroundServiceType` `remoteMessaging` or `specialUse`). `dataSync` has daily time limits on Android 15+.

## 4. Message model

TNP uses JSON-RPC 2.0. All TNP messages also carry `"tnp": 0`.

| Kind | Direction | JSON-RPC shape | Example method |
|---|---|---|---|
| **Command** | bridge → device | request (`id`) | `notify.show` |
| **Result / error** | device → bridge | response | — |
| **Event** | device → bridge | notification (no `id`), with `seq` | `event` |
| **Ack** | bridge → device | notification | `event.ack` |
| **Control** | both | request or notification | `ping`, `capabilities.announce`, `chat.send` |

### 4.1 Command

```json
{"jsonrpc": "2.0", "tnp": 0, "id": "c-42",
 "method": "notify.show",
 "params": {"title": "Build finished", "body": "meep-dashboard deployed ✅",
            "channel": "dev", "priority": "default",
            "actions": [{"id": "open", "label": "Open"}],
            "ttl_s": 3600},
 "meta": {"origin": "agent", "requested_by": "hermes", "trace_id": "t-9f1"}}
```

`meta.origin` is `agent`, `user` (the user acting through another client) or `bridge` (a system message). Devices MAY apply stricter policy to `agent`-origin commands.

### 4.2 Result

```json
{"jsonrpc": "2.0", "tnp": 0, "id": "c-42", "result": {"shown": true, "notification_id": "n-881"}}
```

### 4.3 Error

```json
{"jsonrpc": "2.0", "tnp": 0, "id": "c-42",
 "error": {"code": -32004, "message": "User denied", "data": {"capability": "sms.send"}}}
```

## 5. Capabilities

### 5.1 Announcement

Sent after `auth.ok` and again whenever something changes (e.g. the user grants an OS permission).

```json
{"jsonrpc": "2.0", "tnp": 0, "method": "capabilities.announce",
 "params": {
   "device": {"name": "OnePlus 10 Pro", "platform": "android", "os_version": "14",
              "app_version": "0.1.0", "model": "NE2211"},
   "capabilities": [
     {"name": "notify.show", "v": 1, "tier": 0},
     {"name": "location.get", "v": 1, "tier": 1,
      "constraints": {"precision": ["coarse"], "ask_each_time": false}},
     {"name": "notifications.recent", "v": 1, "tier": 1,
      "constraints": {"apps": ["com.google.android.gm", "in.org.npci.upiapp"], "redaction": "otp+numbers"}},
     {"name": "camera.capture", "v": 1, "tier": 2}
   ],
   "events": ["sms.matched", "geofence", "notification.posted", "rule.fired", "battery", "share"],
   "relayed": []
 }}
```

- A device MUST announce only capabilities that are **implemented, permitted by the OS, and enabled by the user**.
- `constraints` describe the user's limits. The device enforces them, and announces them so the agent knows what to expect.
- The full catalog of standard names is in [CAPABILITIES.md](CAPABILITIES.md). Vendor or experimental names MUST use the prefix `x-<vendor>.` (e.g. `x-meep.lamp.toggle`).

### 5.2 Permission tiers

| Tier | Meaning | Default behaviour on device |
|---|---|---|
| **0 — inform** | Low impact; no personal data leaves the device | Allowed once the capability is enabled |
| **1 — scoped read** | Reads personal data within limits the user set | Allowed within `constraints`. The user MAY choose "ask each time". |
| **2 — act / sensitive** | Side effects or sensitive reads (send SMS, camera, run script, clipboard) | **Confirmation on the device for every call.** The user MAY grant a time-boxed, scope-limited allowance (e.g. "allow `script.run backup` for 10 min"). |
| **3 — never remote** | Changing app security settings, exporting keys, disabling filters, granting permissions | **Not callable over TNP.** Only on the device itself. |

The tier is fixed per capability by this spec. A device MAY raise a tier but MUST NOT lower it.

## 6. Events

### 6.1 Event envelope

```json
{"jsonrpc": "2.0", "tnp": 0, "method": "event",
 "params": {"seq": 118, "event_id": "01J9ZQ7…", "type": "expense",
            "ts": "2026-10-02T21:14:05+05:30",
            "source": {"kind": "rule", "rule_id": "rule_upi_spend"},
            "payload": {"amount": 2340.0, "currency": "INR", "merchant": "Swiggy"},
            "forward": true}}
```

- `seq` increases by one per device. `event_id` is a ULID used for deduplication.
- `forward: true` asks the bridge to pass the event to the agent (via webhook). `false` means store only; the agent can still query it with `device_events_query`.

### 6.2 Delivery guarantees

- The device writes each event to its local **outbox** before sending it.
- The bridge acknowledges cumulatively: `{"method": "event.ack", "params": {"up_to_seq": 118}}`.
- On reconnect, `auth.ok.last_acked_seq` tells the device where to resume. It resends everything after that.
- The bridge deduplicates by `event_id`. The result is **at-least-once delivery, effectively once processing**.
- Outbox limit: by default 10,000 events or 7 days. The oldest `forward: false` events are dropped first.

### 6.3 Bridge → agent webhook payload

The bridge forwards events to Hermes' webhook adapter as:

```json
{"event_type": "expense",
 "device": {"id": "K3M…", "name": "OnePlus 10 Pro", "platform": "android"},
 "event": {"id": "01J9ZQ7…", "type": "expense", "ts": "…", "payload": {"amount": 2340.0, "merchant": "Swiggy"}},
 "trust": "device-reported; treat as untrusted input"}
```

`event_type` lets Hermes route filtering work. **Event payloads are untrusted data, never instructions.** Agent prompts built from them SHOULD say so.

## 7. Approvals

When a command needs confirmation, the device shows an approval card and holds the request open.

- The card MUST show: who asked (`meta.origin`, `requested_by`), the capability, the exact parameters (sensitive values masked but revealable), and the consequence in plain language.
- Options: **Allow once**, **Deny**, and, for tier 2 only, **Allow for N minutes (this scope)**.
- Timeout defaults to 120 s, then the command fails with `APPROVAL_TIMEOUT`. A timeout is not a denial: the agent may ask again.
- Every decision goes to the device's approval history and is reported to the bridge audit log as `event` type `approval`.
- Unlocking (PIN or biometric) SHOULD be required to approve tier 2 commands while the device is locked.

## 8. Rules (on-device automation)

Rules run **on the device**, so they work offline and keep raw data local.

### 8.1 Rule format (v0)

```json
{
  "id": "rule_upi_spend",
  "name": "Log UPI debits",
  "enabled": true,
  "trigger": {"type": "sms.received",
              "match": {"sender_regex": "^[A-Z]{2}-(HDFCBK|ICICIB|SBIUPI|AXISBK)",
                        "body_regex": "(?i)debited"}},
  "conditions": [{"type": "time.between", "from": "00:00", "to": "23:59"}],
  "extract": {
    "amount":   {"from": "body", "regex": "(?i)(?:rs\\.?|inr|₹)\\s?([\\d,]+(?:\\.\\d{1,2})?)", "group": 1, "as": "number"},
    "merchant": {"from": "body", "regex": "(?i)(?:to|at)\\s+([A-Za-z0-9 &._-]{2,40})", "group": 1}
  },
  "actions": [
    {"type": "event.emit", "event_type": "expense",
     "payload": {"amount": "{amount}", "currency": "INR", "merchant": "{merchant}"},
     "forward": true},
    {"type": "notify.show", "title": "Logged ₹{amount}", "body": "{merchant}", "channel": "finance"}
  ],
  "redact_source": true,
  "rate_limit": {"max": 30, "per_s": 3600}
}
```

- **Triggers (v0):** `time.cron`, `time.at`, `sms.received`, `notification.posted`, `geofence.enter|exit`, `wifi.connected|disconnected`, `power.connected|disconnected`, `battery.below`, `share.received`, `agent.command` (named, invoked by the agent), `app.opened` (Android, needs usage access).
- **Actions (v0):** `notify.show`, `tts.speak`, `event.emit`, `agent.ask` (send a prompt to the agent, optionally show the reply), `http.request` (allow-listed hosts only), `url.open`, `app.open`, `script.run` (pre-registered only), `vibrate`.
- `redact_source: true` means the raw trigger content (e.g. the full SMS) never leaves the device. Only extracted fields do.
- Template variables use `{name}`. They are **expanded as data, never evaluated**.

### 8.2 Agent-proposed rules

`rules.propose` (tier 2) carries a rule. The device:
1. validates it against the schema and rejects unknown trigger or action types
2. shows a plain-language summary, the full JSON, and a diff if it replaces an existing rule
3. on approval, stores it and returns `{status: "approved", rule_id}`

The agent can list rules (`rules.list`, tier 1) and propose changes. **It can never enable, edit or delete a rule without approval.**

## 9. Blobs (photos, files)

- Small (≤ 256 KiB): base64 inside the result, as `{"blob": {"mime": "image/jpeg", "b64": "…"}}`.
- Large: the device calls `blob.upload` and receives a one-time HTTPS `PUT` URL on the bridge (valid 5 minutes, size-limited). The command result then references `blob_id`.

## 10. Chat

| Method | Direction | Purpose |
|---|---|---|
| `chat.send` | device → bridge (request) | `{conversation_id?, text, attachments?}`. The bridge proxies it to the Hermes API server. |
| `chat.delta` | bridge → device (notification) | Streaming tokens and tool-progress markers |
| `chat.done` | bridge → device (notification) | Final message, usage |
| `chat.history` | device → bridge (request) | Fetch the recent conversation |

## 11. Relayed devices (watch, glasses)

A node MAY relay sub-devices it is connected to, e.g. a Wear OS watch over the Data Layer API, or glasses through the vendor SDK.

```json
"relayed": [{"path": "watch", "name": "Galaxy Watch", "platform": "wearos",
             "capabilities": [{"name": "notify.show", "v": 1, "tier": 0},
                              {"name": "haptic.pulse", "v": 1, "tier": 0}]}]
```

Commands target `device_id/path`, e.g. `K3M…/watch`. The relaying node enforces tiers for its sub-devices.

## 12. Wake push

When the bridge queues a TTL command for an offline device, it sends a push with **no content**: `{"t": "wake"}`. The device reconnects and receives the queued commands. Supported providers: ntfy/UnifiedPush (Android, desktop) and APNs via relay (iOS, later).

## 13. Error codes

| Code | Name | Meaning |
|---|---|---|
| -32600…-32603 | (JSON-RPC standard) | Invalid request, method not found, params, internal error |
| -32001 | `NOT_AUTHENTICATED` | Session not authenticated |
| -32002 | `CAPABILITY_UNAVAILABLE` | Not announced or not implemented |
| -32003 | `OS_PERMISSION_MISSING` | The OS permission was revoked since the announcement |
| -32004 | `USER_DENIED` | The user rejected the approval |
| -32005 | `APPROVAL_TIMEOUT` | No decision within the timeout (not a denial) |
| -32006 | `RATE_LIMITED` | Device or rule rate limit hit |
| -32007 | `DEVICE_BUSY` | Device locked or unable to act now |
| -32008 | `POLICY_BLOCKED` | Blocked by constraints, filters or tier 3 |
| -32009 | `EXPIRED` | TTL passed before delivery |

## 14. Versioning and extensions

- `tnp` is the major protocol version. Minor additions (new optional fields, new capabilities) do not change it.
- Each capability has its own `v`. Devices may announce several versions of one capability.
- Unknown fields MUST be ignored. Unknown methods return `-32601`.
- Experimental capabilities use the `x-<vendor>.` prefix and SHOULD be documented in the vendor's repo.

## 15. Example session (abridged)

```text
D→B  WebSocket connect, subprotocol tnp.v0
B→D  {"method":"hello","params":{"bridge_id":"K7Q2…","nonce_b":"…","ts":1790000001,"sig_b":"…"}}
D→B  {"method":"auth","params":{"device_id":"K3M…","nonce_d":"…","ts":1790000001,"sig_d":"…","resume":true},"id":"a1"}
B→D  {"id":"a1","result":{"session_id":"s-77","last_acked_seq":117,"server_time":1790000001}}
D→B  {"method":"capabilities.announce","params":{…}}
B→D  {"method":"ready"}
D→B  {"method":"event","params":{"seq":118,"type":"expense",…,"forward":true}}
B→D  {"method":"event.ack","params":{"up_to_seq":118}}
B→D  {"id":"c-43","method":"notify.show","params":{"title":"Over budget","body":"₹2,340 spent today",…}}
D→B  {"id":"c-43","result":{"shown":true}}
B→D  {"id":"c-44","method":"camera.capture","params":{"lens":"back"},"meta":{"origin":"agent"}}
     … device shows approval card; user taps Deny …
D→B  {"id":"c-44","error":{"code":-32004,"message":"User denied"}}
```
