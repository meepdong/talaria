# Security & privacy (draft v0.1)

Talaria gives an AI agent access to personal devices. That is only acceptable if the defaults are safe and the device always has the last word.

## 1. Assets

- Personal data on devices: messages, notifications, location, photos, files
- The ability to act: send SMS, run scripts, open apps
- Credentials: device private keys, the bridge key, the Hermes API key, the MCP token, webhook secrets

## 2. Threat model

| # | Threat | Example | Controls |
|---|---|---|---|
| T1 | **Prompt-injected agent** | A web page tells the agent "text this number" or "take a photo" | Permission tiers enforced **on the device**; tier 2 requires per-call confirmation; scripts only by pre-registered name; rate limits; `meta.origin` shown on every approval card; audit log |
| T2 | **Compromised bridge or server** | An attacker gets root on the VPS | The device still enforces tiers and filters, and the bridge cannot approve on the user's behalf. Devices pin the bridge key. Revoke all devices and re-pair after an incident. |
| T3 | **Network attacker** | Wi-Fi interception | Private network (Tailscale/WireGuard), TLS, and **mutual signature authentication** independent of TLS (PROTOCOL §3.3) |
| T4 | **Lost or stolen device** | A phone left in a cab | Hardware-backed non-exportable keys; app lock for approvals; `talaria devices revoke` |
| T5 | **Data leakage to the model provider** | Bank SMS text sent to a cloud LLM | Allow-lists per source; on-device redaction; `redact_source` on rules so only extracted fields leave; coarse location by default |
| T6 | **Malicious or careless rule** | The agent proposes a rule that forwards every notification | `rules.propose` always needs approval with a plain-language summary and diff; rules cannot target tier 3; rule-level rate limits |
| T7 | **Event injection into the agent** | A crafted notification text says "ignore previous instructions" | The bridge labels payloads as untrusted; Hermes webhook payloads are treated as data; prompts quote them as data |
| T8 | **Replay / impersonation** | Reusing a captured auth message | Nonces from both sides, timestamps within ±120 s, signatures bound to `bridge_id` and both nonces |
| T9 | **Supply chain** | A malicious dependency in the app | Minimal dependencies, lockfiles, reproducible builds as a goal, signed releases with published checksums |
| T10 | **Leaked pairing QR code or link** | The QR code is visible on a screen share; the link is pasted in the wrong chat | Single-use token, 5-minute expiry, secret kept in the URL fragment, and **SAS confirmation on the terminal**: nothing pairs without the operator approving matching codes |
| T11 | **Runaway cost** | Agents ping-pong in a group; a workflow spawns many workers | Per-turn agent reply cap, group and workflow budgets enforced by the bridge, lower `max_concurrent_children`, live spend shown in the app |
| T12 | **Soul tampering** | Someone with a stolen, unlocked phone rewrites an agent's personality | Soul editing off by default (`allow_soul_edit`), revision checks, full version history on the bridge, app lock required for edits |
| T13 | **Metadata leaks in attachments** | A photo reveals your home location | GPS/EXIF stripped on the device by default; per-message opt-out only |

## 3. Safe defaults

- Every tier 1 and tier 2 capability is **off** until the user enables it.
- Tier 2 means **confirm every call** unless the user grants a time-boxed allowance for a specific scope.
- Tier 3 cannot be invoked remotely at all.
- `location.get` defaults to **coarse**.
- Notification and SMS forwarding are **allow-list only**. There is no "forward everything" option in the UI.
- **OTP redaction is always on:** any number of 4–8 digits near words like *OTP, code, verification, passcode* (and common Hindi equivalents) is replaced with `[redacted]` before leaving the device. It cannot be turned off remotely.
- The bridge listens on localhost plus the private-network interface only. Startup warns loudly if it is bound to a public address.
- Pairing always requires **terminal approval with matching codes**.
- Agent API keys live only in the bridge configuration. Devices never receive them.
- Groups start with `max_agent_replies_per_turn: 3`, `agent_to_agent: mention_only` and a daily budget.
- Images are downscaled and stripped of location metadata before upload.

## 4. Privacy

- **No telemetry, no analytics, no Talaria cloud.**
- The event store on the bridge has a retention limit (default 30 days). Raw content excluded by `redact_source` is never stored anywhere off-device.
- The device keeps a local **"what left this device"** log the user can browse and clear.
- Export and delete: `talaria export` and `talaria purge --device <id>` on the bridge; "delete all data" on the device.

## 5. Approval UX requirements

An approval card MUST show:
1. **Who asked:** agent, user, or bridge, plus the requester name
2. **What:** the capability, in plain words ("Send an SMS to +91 98…")
3. **Exact parameters**, with sensitive values masked and a reveal option
4. **Consequence** ("This sends a real message and may cost money")
5. **Options:** Allow once · Deny · (tier 2) Allow for N minutes for this scope

Tier 2 approvals on a locked device require unlocking (biometric or PIN).

## 6. Secrets handling

| Secret | Location |
|---|---|
| Device private key | OS keystore, non-exportable |
| Bridge private key | `~/.config/talaria/bridge.key`, mode 0600 |
| Hermes API keys (one per agent/profile) | Bridge environment only; **never sent to devices** |
| MCP token, webhook secret | Bridge and Hermes `.env` files, mode 0600 |

## 7. Reporting vulnerabilities

Before the first release, add a `SECURITY.md` at the repo root with a private contact (GitHub private vulnerability reporting) and a 90-day disclosure window.
