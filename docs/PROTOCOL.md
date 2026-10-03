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
| Network | A private network (Tailscale, Headscale, plain WireGuard or similar) is RECOMMENDED. The bridge SHOULD NOT be exposed to the public internet. |
| TLS certificate | Either publicly trusted (e.g. via `tailscale serve`) or **self-signed and pinned**: the pairing payload carries `tls_spki_sha256`, and clients MUST then accept only that certificate key |

TLS protects the channel. **Authentication does not rely on TLS**: both sides prove their identity with signatures (§3), so a misconfigured proxy cannot impersonate either side.

## 3. Identity, pairing and authentication

### 3.1 Keys

Exact byte formats (encodings, what `‖` means, signature and SAS derivation) and test vectors are in [spec/](../spec/README.md). Where this document and spec/ differ, spec/ wins.


- **Bridge identity:** an ECDSA P-256 key pair created on first start. `bridge_id` = base32(SHA-256(public key DER SPKI))[0:26].
- **Device identity:** an ECDSA P-256 key pair created on the device inside the OS keystore and marked non-exportable. `device_id` is derived the same way.
- P-256 is chosen because it is the one curve hardware-backed on every target: Android Keystore/StrongBox, Apple Secure Enclave, and Windows CNG/TPM.

### 3.2 Pairing (one time per device)

`talaria pair --name "OnePlus 10 Pro"` shows **three equivalent ways to pair**, so devices with and without cameras are covered:

```
█▀▀▀▀▀█ ▄▀▄ █▀▀▀▀▀█     Scan with the Talaria app
█ ███ █ ▀█▀ █ ███ █     or paste this link (expires in 5:00):
█ ▀▀▀ █ █▀█ █ ▀▀▀ █     talaria://pair#v0.meep-vps.ts.net.K7Q2….8fJ2…
▀▀▀▀▀▀▀ ▀ ▀ ▀▀▀▀▀▀▀     or enter code: HX49-2KQ7
```

| Method | For | Carries |
|---|---|---|
| **QR code** (rendered in the terminal) | Phones, tablets | The full pairing payload below |
| **Pairing link** `talaria://pair#…` | Devices without cameras (desktop, glasses), or pasting over a trusted channel | The same payload, base64url-encoded after `#` |
| **Short code** (8 characters) | Last resort, typed by hand | A lookup key. The device must also be given the bridge URL, and learns the bridge key from `hello` (the SAS check protects it). Rate-limited to 5 wrong attempts. |

Pairing payload:
```json
{"tnp": 0, "url": "wss://meep-vps.tailnet.ts.net/tnp",
 "bridge_id": "K7Q2…", "bridge_pk": "<base64 SPKI>",
 "pair_token": "<128-bit random, base64url>", "exp": 1790000000,
 "tls_spki_sha256": "<optional; present when the bridge uses a self-signed certificate>"}
```

- The token is **single-use** and expires in **5 minutes** (configurable, max 15).
- The secret sits **after `#`** (the URL fragment), which browsers, proxies and servers never transmit or log. A pairing link is still a secret: share it only over a channel you trust.
- Desktop clients register the `talaria://` scheme and also offer a "paste link" box.

Flow:
1. The device reads the payload, **pins `bridge_pk`**, generates its key pair in the OS keystore, and connects.
2. The device sends `pair.request` with `pair_token` (or `short_code`), its public key, name and platform, signed with its new key over the connection's `nonce_b` (spec/README.md §3).
3. The bridge verifies the token, then both sides derive a **short authentication string (SAS)**: 6 digits and 3 emoji computed from `SHA-256("tnp0-sas" ‖ bridge_pk ‖ device_pk ‖ pair_token)`.
4. **Confirmation:** the device shows the SAS, and the terminal asks:
   `Approve "OnePlus 10 Pro"?  Code 482 913  🦊🌙🎸  [y/N]`
   The operator approves only if the codes match. This stops someone who saw the QR code or link (on a screen share, in a screenshot) from pairing a device of their own.
5. The bridge consumes the token, stores the device public key, and replies `pair.accepted`. Without approval within 2 minutes it replies `pair.rejected`.

Prerequisite: the device must be able to reach the bridge over the private network (Tailscale, Headscale, WireGuard…). If it cannot, the client shows "Can't reach your server — is your private network (Tailscale/WireGuard) on?" rather than a generic error.

Management: `talaria devices list` and `talaria devices revoke <device_id>`. Revocation closes any open session immediately and the key is rejected from then on.

### 3.3 Connection handshake (every connection)

```
Device                                Bridge
  │── WebSocket connect (tnp.v0) ──────▶│
  │◀── hello {bridge_id, bridge_pk, nonce_b, ts, sig_b} ─│   sig_b = Sign_bridge("tnp0-hello" ‖ bridge_id ‖ nonce_b ‖ ts)
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

## 10. Status, agents, chat and groups

All chat goes **through the bridge**. Devices never hold agent API keys.

### 10.1 Status

`status.get` (device → bridge, request) returns a layered health report. The bridge also pushes `status` notifications when anything changes.

```json
{"bridge": {"version": "0.1.0", "uptime_s": 86400, "latency_ms": 45},
 "agents": [
   {"id": "meep",  "state": "ready",    "model": "anthropic/claude-sonnet-5.5"},
   {"id": "scout", "state": "degraded", "detail": "provider error: insufficient credit"}
 ],
 "device": {"session_id": "s-77", "last_acked_seq": 118}}
```

Agent `state` is one of `ready`, `degraded` (reachable but failing requests), `offline` or `unknown`. The client maps network failure, bridge failure and agent failure to distinct, actionable messages. `agents` is an empty list when the bridge has no agents configured. `latency_ms` is the round trip of the bridge's last heartbeat ping on this session, or `null` before one was answered. Byte-level details and schemas: spec/README.md §8.

### 10.2 Agents

The bridge keeps an **agent registry**. An agent is a Hermes profile or another OpenAI-compatible endpoint, configured on the server. The registry holds endpoints and keys; devices see only descriptions.

| Method | Purpose |
|---|---|
| `agents.list` | All agents: `id`, `name`, `avatar`, `role`, `model`, `modalities`, `state`, `cost_tier` |
| `agents.get` | One agent in full: the above plus `soul` (SOUL.md content), `tools`/`skills` summary, `memory_summary` (optional, owner-controlled), `spend` |
| `agents.soul.update` | Replace an agent's SOUL.md. **Disabled unless the bridge enables `allow_soul_edit`.** The request carries the expected current version (`soul_rev`) to prevent overwriting concurrent edits. The bridge keeps every previous version. |

Modalities:
```json
"modalities": {"in": ["text", "image", "pdf"], "out": ["text"]}
```
Clients warn before sending a modality the target agent does not accept.

### 10.3 Chat

| Method | Direction | Purpose |
|---|---|---|
| `chat.send` | device → bridge (request) | `{agent_id? \| group_id, conversation_id?, text, attachments?, reply_to?}` |
| `chat.started` | bridge → device (notification) | A turn started, on this device or another |
| `chat.delta` | bridge → device (notification) | Streaming output tagged with `kind`: `text`, `tool_progress`, `commentary`, `approval`, `worker` (see §10.5) |
| `chat.done` | bridge → device (notification) | Final message, `usage` (tokens), `runtime` (model actually used) |
| `chat.cancel` | device → bridge (request) | Stop an in-flight reply |
| `chat.turn.get` | device → bridge (request) | Snapshot of a recent turn, to catch up after a reconnect |
| `chat.history` | device → bridge (request) | Page through a conversation |
| `conversations.list` / `.rename` / `.delete` | device → bridge (request) | Conversations, with last message and any running turn |

- A Talaria conversation maps to a Hermes **session** on the API server's Sessions API (`/api/sessions/{id}/chat/stream`), so history lives on the server and survives app reinstalls. The Responses API's named conversations were the first plan, but Hermes keeps only the last 100 stored responses there.
- **The bridge holds the stream to the agent.** Hermes stops a run when its stream client disconnects, so the device never holds it: a reply finishes even when the phone drops off, and every connected device receives it. Byte-level details and schemas: spec/README.md §9.
- **Attachments** reference blobs (§9): `{"blob_id": "b-12", "mime": "image/jpeg", "name": "receipt.jpg"}`. Clients SHOULD downscale images (long edge ≤ 1568 px) and MUST strip location metadata (EXIF GPS) before upload unless the user opts out for that message.
- **Voice** is converted to text **on the device** before sending. Audio is only uploaded if the user explicitly attaches an audio file.
- **Assistant invocations** set `"origin": "assistant"` and MAY include `"context": {"screen_text": "…", "screenshot_blob": "b-31", "foreground_app": "com.example"}`. Context is included **only after the user confirms it for that request**. The bridge passes it to the agent marked as untrusted content.

### 10.4 Groups (agents only, one owner)

A group is a conversation with several of the owner's agents. **The bridge** routes messages and builds context; Hermes itself has no group concept.

| Method | Purpose |
|---|---|
| `groups.create` / `groups.update` / `groups.delete` | Name, avatar, description ("house rules"), members, settings |
| `groups.get` | Group info: members with roles, settings, spend, shared media |

Group settings:
```jsonc
{"routing": "conductor",          // conductor | mention_only | round_robin
 "conductor": "meep",
 "max_agent_replies_per_turn": 3, // hard stop for agent-to-agent ping-pong
 "agent_to_agent": "mention_only",
 "daily_budget": {"amount": 50, "currency": "INR"}}
```

- **Routing.** `conductor`: the conductor decides who answers (default). `mention_only`: only @mentioned agents answer. `round_robin`: everyone answers (costly, off by default).
- **Context.** Each agent receives the recent group transcript with speaker labels ("Scout said: …") plus the new message. Each agent's own memory stays separate.
- **Limits.** The bridge enforces `max_agent_replies_per_turn` and `daily_budget`. Hitting a limit posts a system message and stops further agent replies until the user writes again.

### 10.5 Workflow events

When an agent delegates work to sub-agents (Hermes `delegate_task` or Kanban), the bridge relays progress from Hermes' run event stream as `chat.delta` with `kind: "worker"`:

```json
{"kind": "worker", "worker_id": "w-3", "parent": "meep",
 "status": "running", "goal": "Find cheapest Goa flights 14–17 Nov",
 "model": "anthropic/claude-haiku-4.5", "cost": {"amount": 0.42, "currency": "INR"}}
```

`status` is one of `queued`, `running`, `done`, `failed` or `cancelled`. Clients render these as a live workflow view. A per-conversation budget, when set, is enforced by the bridge and reported with `BUDGET_EXCEEDED`.

### 10.6 Voice replies to other messaging apps

Voice replies ("Reply to Asha on WhatsApp: I'll be ten minutes late") are **handled almost entirely on the device**. Speech-to-text, contact matching, confirmation and delivery all happen locally, and contacts never leave the device.

The only protocol involvement is the optional **Polish mode**:

| Method | Direction | Purpose |
|---|---|---|
| `compose.polish` | device → bridge (request) | `{agent_id, text, recipient_first_name?, app?, tone?}` → `{text}`. Rewrites dictated intent ("tell her I'll be late") into a first-person message in the owner's style. |

- Only the dictated text and, optionally, the recipient's **first name** are sent. No phone numbers, chat history or contact details.
- **Exact mode** (the dictated words as spoken) needs no protocol call and no model cost.
- Sent messages are logged **on the device only** ("what I sent by voice"). Nothing about them is reported to the bridge unless the user enables it.

### 10.7 Schedules

A schedule lives in **exactly one place**: on the device (a local rule with a time trigger, §8) or on the server (a Hermes cron job). The client shows both in one list.

| Method | Direction | Purpose |
|---|---|---|
| `schedules.list` | device → bridge (request) | Server schedules (Hermes cron jobs, read via the bridge). The client merges them with its local time rules. |
| `schedules.create` / `schedules.update` | device → bridge (request) | Create or edit a **server** schedule: `{name, when, prompt, agent_id, model?, deliver_to: ["device:<id>", …], budget_per_run?}` |
| `schedules.pause` / `schedules.resume` / `schedules.delete` | device → bridge (request) | Lifecycle for server schedules |
| `schedules.run_now` | device → bridge (request) | Trigger a server schedule once, for testing |

Server schedule entries include `runs_on: "server"`, `next_run_at`, `last_run` (`status`, `cost`) and the pinned `model`. Local schedules are never sent to the bridge; they appear in the list with `runs_on: "device"`.

A server job that needs phone data at run time calls the normal device tools (`device_location`, `device_events_query`, …). If the device is unreachable, the bridge answers from its **last known** event data and labels it stale (e.g. `"as_of": "2026-10-03T07:41:00+05:30"`).

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
| -32010 | `AGENT_UNAVAILABLE` | The target agent is offline or failing |
| -32011 | `BUDGET_EXCEEDED` | A group, conversation or workflow budget was reached |
| -32012 | `MODALITY_UNSUPPORTED` | The agent does not accept this attachment type |
| -32013 | `CONFLICT` | Stale revision (e.g. `soul_rev` does not match), or a reply is still running in that conversation |
| -32014 | `NOT_FOUND` | Unknown conversation, turn or other object |

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
