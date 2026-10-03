# TNP v0 spec: byte formats, schemas and test vectors

[`docs/PROTOCOL.md`](../docs/PROTOCOL.md) describes the protocol. This folder pins down the exact bytes, so that the Python bridge, the Kotlin clients and any third-party client compute the same ids, signatures and SAS codes. When the two disagree, this folder wins, and PROTOCOL.md gets fixed.

Working agreement (ROADMAP): every protocol change updates `spec/` first, with schema and test vectors, then code.

| Path | Contents |
|---|---|
| `schemas/` | JSON Schema (2020-12) for each message so far (M0 handshake, M1 status) |
| `vectors/` | Test vectors. `generate.py` rebuilds them. |
| `sas-emoji.json` | The 64-emoji SAS table |

## 1. Encodings

- **base64url** means RFC 4648 §5 without padding. Decoders MUST reject padding, characters outside the alphabet, and non-canonical encodings (where re-encoding gives a different string).
- **Public keys** on the wire are base64url of the DER `SubjectPublicKeyInfo` of an ECDSA P-256 key. Other curves MUST be rejected.
- **Ids:** `bridge_id` and `device_id` are RFC 4648 base32 (uppercase, no padding) of SHA-256 over the DER SPKI, truncated to 26 characters (130 bits). Vectors: `vectors/keys.json`.
- **Nonces** are 16 random bytes, base64url (22 characters).
- **Timestamps** are integer Unix seconds.

## 2. `frame()`: what ‖ means

PROTOCOL.md writes signed and hashed inputs as `a ‖ b ‖ c`. Concretely, that is `frame(a, b, c)`. Each field is a 4-byte big-endian length followed by its bytes. Every field is the **exact wire string** encoded as UTF-8, and integers use their decimal form (`"1790000000"`). Plain concatenation would be ambiguous (`"ab"‖"c"` = `"a"‖"bc"`). Vectors: `vectors/frame.json`.

## 3. Signatures

Signatures are ECDSA P-256 with SHA-256, DER-encoded, then base64url. Verifiers MUST accept any valid DER signature, whether high-S or low-S. Vectors: `vectors/signatures.json`.

| Message | Signer | Signed data |
|---|---|---|
| `hello.sig_b` | bridge | `frame("tnp0-hello", bridge_id, nonce_b, ts)` |
| `auth.sig_d` | device | `frame("tnp0-auth", bridge_id, device_id, nonce_b, nonce_d, ts)` |
| `pair.request.sig_d` | device | `frame("tnp0-pair", bridge_id, nonce_b, device_pk, pairing_secret, name, platform, ts)` |

Every connection starts with the bridge's `hello`, pairing included. `hello` also carries `bridge_pk`, and the device checks that `bridge_id` = id(`bridge_pk`) and that both match what it pinned. The `pair.request` signature is new compared with PROTOCOL.md. It proves the device holds the key it registers, and binds the request to this connection's `nonce_b`.

## 4. Pairing

**Link:** `talaria://pair#` followed by base64url of the UTF-8 JSON payload (PROTOCOL §3.2). Encoders write compact JSON with sorted keys. Decoders accept any key order and ignore unknown fields. Vectors: `vectors/pairing.json`.

**Short code:** 8 characters of Crockford base32 (`0-9 A-Z` without `I L O U`), 40 bits, displayed as `XXXX-XXXX`. Before use, devices normalize what was typed: uppercase it, drop `-` and spaces, map `O` to `0` and `I`/`L` to `1`. They then send the normalized form as `short_code` instead of `pair_token`. With a short code there is nothing to pin in advance, so the device trusts `bridge_pk` from `hello`. The SAS covers `bridge_pk`, so a substituted key shows mismatched codes. Every wrong code counts against all open codes. After 5 failures the open codes stop working (`rate_limited`), but their links still work.

**SAS:** `h = SHA-256(frame("tnp0-sas", bridge_pk, device_pk, pairing_secret))`, where `pairing_secret` is the `pair_token` string, or the normalized short code.
- digits = `uint32_be(h[0:4]) mod 1 000 000`, zero-padded to 6 and shown as `414 818`
- emoji = `sas-emoji.json[h[i] mod 64]` for i = 4, 5, 6

Vectors: `vectors/sas.json`.

**Flow:**
1. The bridge sends `hello`.
2. The device verifies it, then sends `pair.request`, a notification.
3. The bridge checks the request's shape, its `ts` (±120 s) and its signature. A failure here does **not** burn the token.
4. The bridge consumes the token. **Single use:** from this point the token is spent, whatever the operator decides.
5. Both sides show the SAS. The operator approves or rejects in the `talaria pair` terminal.
6. The bridge sends `pair.accepted {device_id, name}`, or `pair.rejected {reason}`, then closes with 1000.
7. The device reconnects and authenticates with `auth`.

Rejection reasons: `invalid_request`, `unknown_token`, `expired`, `already_used`, `revoked`, `bad_signature`, `rate_limited`, `sas_rejected`, `timeout`. The operator has 2 minutes to approve. A device that was revoked cannot pair again with the same key.

## 5. Handshake and session

The flow follows PROTOCOL §3.3. The bridge closes with **4401** when `auth` is malformed, the device is unknown, the signature is bad, `ts` is outside ±120 s, or `nonce_d` was already seen within the window. It closes with **4403** when the device is revoked, both at `auth` and within a second of `talaria devices revoke` during an open session.

After `auth.ok`, the device sends `capabilities.announce` and the bridge answers `ready`. `ping` is a request on either side, and its result is `{"ts": <sender clock>}`. The bridge sends `ping` after 30 s without receiving anything, and closes with **4408** after 90 s.

A WebSocket upgrade without the `tnp.v0` subprotocol is refused with HTTP 400. Any path other than `/tnp` gets HTTP 404.

## 6. Close codes

| Code | Meaning |
|---|---|
| 1000 | Normal close, including after pairing |
| 1002 | Protocol error: invalid frame or wrong first message |
| 4401 | Authentication failed |
| 4403 | Device revoked |
| 4408 | Timed out: no first message within 30 s, or no traffic for 90 s |

## 7. Transport security in development

`talaria serve --dev` serves plain `ws://` and refuses to bind anything but a loopback address. Clients MUST refuse `ws://` to any host that is not loopback. Real deployments use `wss://`: either a publicly trusted certificate (for example from `tailscale serve`), or a self-signed one whose key hash is pinned through `tls_spki_sha256` = base64url(SHA-256(certificate SPKI DER)).

`talaria serve --behind-proxy --url wss://host/tnp` is the setup for a TLS proxy on the same machine, such as `tailscale serve`. The bridge serves plain `ws://` on a loopback address only, and puts the `wss://` address in pairing links. It takes no certificate, so pairing links carry no `tls_spki_sha256`.

## 8. Status (M1)

After `ready`, a device may send `status.get` (request). The result is the layered report from PROTOCOL §10.1:

| Field | Meaning |
|---|---|
| `bridge.version` | Bridge version string |
| `bridge.uptime_s` | Seconds since the bridge started serving |
| `bridge.latency_ms` | Round trip of the bridge's last answered heartbeat `ping` on this session, or `null` until one was answered. Clients measure their own round trip with `ping` for display. |
| `agents[]` | `{id, name?, state, model?, detail?}`, `state` one of `ready`, `degraded`, `offline`, `unknown`. **Empty when no agents are configured.** |
| `device.session_id`, `device.last_acked_seq` | This session. `last_acked_seq` stays 0 until events arrive in M3. |

The bridge pushes the same report as a `status` notification to every session past `ready` whenever an agent's state changes. In M1 an agent's state comes from an HTTP health check (`agents.json` in the bridge home, checked every 30 s with a 5 s timeout): a 2xx answer is `ready`, unless its JSON body has a `status` other than ok/healthy/ready/up/pass, which is `degraded`. Any other HTTP status is `degraded`, and no answer is `offline`. Agents start as `unknown` until the first check completes.

Schemas: `status.get`, `status.result`, `status`.

## 9. Chat (M2)

After `ready`, a device can chat with an agent through the bridge (PROTOCOL §10.3). Devices never see agent credentials. In M2 the bridge talks to Hermes through its API server's Sessions API: one Talaria conversation is one Hermes session, so history lives in Hermes and survives app reinstalls. The bridge, not the device, holds the stream to Hermes, so a reply keeps going when the device drops off and the device catches up when it reconnects.

| Method | Direction | Params → result |
|---|---|---|
| `chat.send` | request | `{text, conversation_id?, agent_id?, client_msg_id?, attachments?, model?}` → `{conversation_id, turn_id, title, queued?}` |
| `chat.started` | notification | `{conversation_id, turn_id, agent_id, title, user_text, started_at, client_msg_id?, attachments?}` |
| `chat.delta` | notification | `{conversation_id, turn_id, seq, kind, text?, tool?}` |
| `chat.done` | notification | `{conversation_id, turn_id, seq, status, text, error?, usage?, runtime?}` |
| `chat.cancel` | request | `{turn_id}` → `{turn_id, status}` |
| `chat.turn.get` | request | `{turn_id}` → `{turn: snapshot}` |
| `chat.history` | request | `{conversation_id, before?, limit?}` → `{messages, next_before}` |
| `conversations.list` | request | `{}` → `{conversations}` |
| `conversations.rename` | request | `{conversation_id, title}` → `{conversation_id, title}` |
| `conversations.delete` | request | `{conversation_id}` → `{conversation_id, deleted}` |

**Sending.** `chat.send` without `conversation_id` starts a new conversation, titled with the start of the message. `agent_id` defaults to the first agent with chat configured. The result comes back before the turn's first `chat.delta`. A retry with the same `client_msg_id` within 10 minutes returns the original turn instead of sending twice. `attachments` lists photos and files uploaded first (§10); `text` may then be empty.

**Every device sees every turn.** `chat.started`, `chat.delta` and `chat.done` go to every session past `ready`, including turns sent from another device, so the phone and the laptop show the same conversation live. `chat.started` repeats the sender's `client_msg_id`, so the sending device can match it to the message it already shows, even before the `chat.send` result is handled.

**Deltas.** `seq` starts at 1 and grows by one per notification of a turn, `chat.done` included. `kind` is one of:
- `text`: `text` is the next piece of the answer.
- `tool_progress`: `tool` is `{name, state, preview?}`, with `state` one of `started`, `completed`, `failed`. `preview` is at most 500 characters.
- `commentary`: `text` is a progress note the agent wrote between tool calls. It is not part of the answer.
- `approval`: the agent is waiting for an approval; `text` describes it. Approving from the app comes later, so clients show "Waiting for approval in Hermes".

**Done.** `status` is `completed`, `failed` (with `error`) or `cancelled`. `text` is the whole answer, which may differ from the joined `text` deltas, and clients replace the streamed text with it. `usage` holds `input_tokens`, `output_tokens`, `total_tokens`; `runtime` holds the `provider` and `model` that actually answered.

**Catching up.** A device that reconnects while it was showing a running turn calls `chat.turn.get`. The snapshot has the turn's `status`, `user_text`, `text` so far, `tools`, `commentary`, `waiting_for_approval`, and `seq`, the last `seq` it covers. The device replaces what it showed with the snapshot and ignores any delta with `seq` at or below it. The bridge keeps the last 50 turns; an older `turn_id` gets `NOT_FOUND`, and the device reloads `chat.history` instead. `conversations.list` names a conversation's running turn as `active_turn_id`.

**One turn at a time.** A conversation runs one turn at a time; a `chat.send` while one runs is queued (§11). `chat.cancel` stops a running turn; its result `status` is `stopping`, or the final status when the turn already ended, and the turn still ends with `chat.done`.

**History.** `chat.history` returns the newest page first; `next_before` is an opaque cursor for the next older page, or `null`. Within a page, messages are oldest first. Each is `{id, role, text, ts, tools?, attachments?}`, where `role` is `user` or `assistant`, `ts` is Unix seconds or `null`, and `tools` lists the tools an assistant message called. Tool results are not included. Pages may hold fewer than `limit` messages.

**Errors.** `AGENT_UNAVAILABLE` (-32010) when no chat agent is configured or the agent cannot be reached; `CONFLICT` (-32013) when the queue is full (§11); `NOT_FOUND` (-32014) for an unknown conversation or turn; `INVALID_PARAMS` (-32602) for malformed params.

Schemas: `chat.send`, `chat.send.result`, `chat.started`, `chat.delta`, `chat.done`, `chat.cancel`, `chat.cancel.result`, `chat.turn.get`, `chat.turn.get.result`, `chat.history`, `chat.history.result`, `conversations.list`, `conversations.list.result`, `conversations.rename`, `conversations.delete`, `conversations.result`.

## 10. Attachments (M2)

Photos and files travel over the session in chunks, so no second port or URL is needed. This replaces the HTTPS `PUT` upload sketched in PROTOCOL §9.

| Method | Direction | Params → result |
|---|---|---|
| `blob.begin` | request | `{name, mime, size, sha256}` → `{blob_id, chunk_bytes}` |
| `blob.put` | request | `{blob_id, offset, data}` → `{blob_id, received}` |
| `blob.commit` | request | `{blob_id}` → `{blob_id, kind, name, mime, size}` |

**Uploading.** `size` is at most 20 MiB and `sha256` is the lowercase hex digest of the whole file. The device sends chunks in order: `offset` is the number of bytes sent so far and `data` is base64 (standard alphabet, with padding) of at most `chunk_bytes` bytes (512 KiB). A chunk at the wrong offset fails with `CONFLICT`. `blob.commit` checks the size and digest; a mismatch fails with `INVALID_PARAMS` and drops the blob. An upload that fails part way is started again with a new `blob.begin`.

**Sending.** `chat.send` takes `attachments: [{blob_id}]`, up to 10. Each committed blob can be sent once, by any device past `ready`; the bridge drops blobs not sent within an hour. `kind` is `image` for `image/jpeg`, `image/png`, `image/webp` and `image/gif` up to 5 MiB, and `file` for anything else.

**What the agent gets.** Images go to the agent inline with the message, at most 7 MiB of them per message. Hermes's API takes no other files, so the bridge saves a `file` to an inbox folder the agent can read and adds a line to the message: `Attached file: <path> (<mime>, <size> bytes)`. An agent with no inbox configured refuses files with `MODALITY_UNSUPPORTED`.

**What devices see.** `chat.started`, the `chat.turn.get` snapshot and user messages in `chat.history` carry `attachments: [{kind, name, mime, size?}]`. `user_text` and history `text` leave out the `Attached file:` lines. Devices show their own copy of a photo they sent; the bridge does not send image bytes back, so other devices and history show a placeholder.

Clients SHOULD downscale photos to 1568 px on the long edge and MUST strip location metadata before upload (PROTOCOL §10.3).

Schemas: `blob.begin`, `blob.begin.result`, `blob.put`, `blob.put.result`, `blob.commit`, `blob.commit.result`; `chat.send`, `chat.started`, `chat.turn.get.result` and `chat.history.result` gain `attachments`.

## 11. Models and chat commands (M2)

What Hermes offers as slash commands in its own chat apps (`/model`, `/queue`, `/steer`, `/btw`, `/status`), as protocol methods. Its API server doesn't run slash commands, so the bridge maps each one to the API, and devices show them as buttons and as a `/` menu in the composer. Hermes's `/retry` is the device sending the last question again, so it needs nothing here.

| Method | Direction | Params → result |
|---|---|---|
| `agent.models` | request | `{agent_id?}` → `{agent_id, current, providers}` |
| `conversations.set_model` | request | `{conversation_id, model}` → `{conversation_id, model}` |
| `chat.queued` | notification | `{conversation_id, turn_id, user_text, position, client_msg_id?, attachments?}` |
| `chat.steer` | request | `{turn_id, text}` → `{turn_id, accepted}` |
| `chat.aside` | request | `{conversation_id, text}` → `{aside_id}` |
| `chat.aside.done` | notification | `{conversation_id, aside_id, question, status, text, error?}` |
| `chat.status` | request | `{conversation_id}` → `{conversation_id, model?, messages?, tool_calls?, input_tokens?, output_tokens?, cost_usd?, active_turn_id?, queued}` |
| `account.balance` | request | `{}` → `{accounts}` |

**Models.** A model is `{provider, model}`, both strings as Hermes names them (for example `{"provider": "anthropic", "model": "claude-sonnet-4"}`). `agent.models` lists the providers the agent has credentials for, each `{id, name, models}` with `models` a list of model names, and `current`, the agent's default. `conversations.set_model` pins a conversation to a model from then on, and `chat.send` with `model` does the same before its turn, which is how a new conversation starts on a chosen model. `conversations.list` gives each conversation's pinned `model`, if any. A model the agent can't route fails with `INVALID_PARAMS`.

**Queue.** `chat.send` to a conversation whose turn is running is accepted and queued: the result has `queued: true`, and every device gets `chat.queued` with the turn's place in the queue, starting at 1. The bridge starts queued turns in order as each one ends, each with its usual `chat.started`. `chat.cancel` on a queued turn removes it, and the turn ends with `chat.done` with `status: cancelled`, without a `chat.started`. A conversation holds at most 5 queued turns; one more fails with `CONFLICT`. The queue lives on the bridge, so it keeps going when the device drops off, but not across a bridge restart: queued turns are then lost. `conversations.list` names a conversation's queued turns as `queued_turn_ids`. Until it starts, a queued turn's `chat.turn.get` snapshot has `status: queued`.

**Steer.** `chat.steer` hands a note to a running turn without stopping it; the agent reads it after its next tool call. `accepted` is `false` when the turn was already finishing; a turn that isn't running fails with `CONFLICT`.

**Aside.** `chat.aside` asks a side question about a conversation (Hermes's `/btw`) without adding to it or waiting for its running turn. The bridge asks the agent in a separate, throwaway request that carries the conversation's recent messages; the answer comes to every device as `chat.aside.done`, with `status` `completed` or `failed`. Asides are not saved: they are not in `chat.history`, and a device that was offline misses them.

**Status.** `chat.status` reports on a conversation, from what Hermes keeps about its session: the pinned `model`, how many `messages` and `tool_calls` it has, the `input_tokens` and `output_tokens` it has used, the estimated `cost_usd`, the running turn and how many turns are `queued`. Fields Hermes doesn't report are left out.

**Balance.** `account.balance` lists the provider accounts the bridge can check, each `{provider, name, remaining, currency, top_up_url}`: today only OpenRouter, when the operator has given the bridge an OpenRouter management key. That key never leaves the bridge. `remaining` is the credit left (purchased minus used) as a number in `currency` (`USD`), and `top_up_url` is the provider's page for adding credit. The list is empty when nothing is configured; a provider that can't be reached is listed with `error` instead of `remaining`.

Schemas: `agent.models`, `agent.models.result`, `conversations.set_model`, `conversations.set_model.result`, `chat.queued`, `chat.steer`, `chat.steer.result`, `chat.aside`, `chat.aside.result`, `chat.aside.done`, `chat.status`, `chat.status.result`, `account.balance`, `account.balance.result`; `chat.send`, `chat.send.result` and `conversations.list.result` gain `model`, `queued` and `queued_turn_ids`.
