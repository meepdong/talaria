# TNP v0 spec: byte formats, schemas and test vectors

[`docs/PROTOCOL.md`](../docs/PROTOCOL.md) describes the protocol. This folder pins down the exact bytes, so that the Python bridge, the Kotlin clients and any third-party client compute the same ids, signatures and SAS codes. When the two disagree, this folder wins, and PROTOCOL.md gets fixed.

Working agreement (ROADMAP): every protocol change updates `spec/` first, with schema and test vectors, then code.

| Path | Contents |
|---|---|
| `schemas/` | JSON Schema (2020-12) for each message so far (M0 handshake, M1 status, M2 chat, files, to-dos and automations) |
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
| `ops.approve.sig` | device | `frame("tnp0-ops-approve", request_id, device_id, op, params_json, choice)` (§16) |

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
| `chat.approve` | request | `{turn_id, choice}` → `{turn_id, choice}` |
| `chat.turn.get` | request | `{turn_id}` → `{turn: snapshot}` |
| `chat.history` | request | `{conversation_id, before?, limit?}` → `{messages, next_before}` |
| `conversations.list` | request | `{}` → `{conversations}` |
| `conversations.rename` | request | `{conversation_id, title}` → `{conversation_id, title}` |
| `conversations.delete` | request | `{conversation_id}` → `{conversation_id, deleted}` |
| `conversations.pin` | request | `{conversation_id, pinned}` → `{conversation_id, pinned}` |
| `chat.hide` | request | `{conversation_id, message_ids}` → `{conversation_id, message_ids}` |
| `chat.hidden` | notification | `{conversation_id, message_ids}` |

**Sending.** `chat.send` without `conversation_id` starts a new conversation, titled with the start of the message. `agent_id` defaults to the first agent with chat configured. The result comes back before the turn's first `chat.delta`. A retry with the same `client_msg_id` within 10 minutes returns the original turn instead of sending twice. `attachments` lists photos and files uploaded first (§10); `text` may then be empty.

**Every device sees every turn.** `chat.started`, `chat.delta` and `chat.done` go to every session past `ready`, including turns sent from another device, so the phone and the laptop show the same conversation live. `chat.started` repeats the sender's `client_msg_id`, so the sending device can match it to the message it already shows, even before the `chat.send` result is handled.

**Deltas.** `seq` starts at 1 and grows by one per notification of a turn, `chat.done` included. `kind` is one of:
- `text`: `text` is the next piece of the answer.
- `tool_progress`: `tool` is `{name, state, preview?}`, with `state` one of `started`, `completed`, `failed`. `preview` is at most 500 characters.
- `commentary`: `text` is a progress note the agent wrote between tool calls. It is not part of the answer.
- `approval`: the agent is waiting for an approval before it runs something. `text` describes it, and `approval` is `{choices, command?, description?, request_id?}`: what it wants to run, why it was flagged, and the answers it accepts, from `once`, `session` (this conversation), `always` and `deny`.
- `approval_done`: the approval was answered from a device; `choice` says how. Every device drops its approval card.

**Done.** `status` is `completed`, `failed` (with `error`) or `cancelled`. `text` is the whole answer, which may differ from the joined `text` deltas, and clients replace the streamed text with it. `usage` holds `input_tokens`, `output_tokens`, `total_tokens`; `runtime` holds the `provider` and `model` that actually answered.

**Approvals.** `chat.approve` answers the approval a running turn waits for, with one of the `choices` from its `approval` delta. Only a device can answer: a conversation started from Talaria has no other place to approve it, and an unanswered approval times out in Hermes as a denial. It fails with `CONFLICT` when nothing is waiting (already answered, or the turn ended) and `INVALID_PARAMS` for a choice Hermes didn't offer. The bridge passes the answer to Hermes's `POST /v1/runs/{run_id}/approval`.

**Catching up.** A device that reconnects while it was showing a running turn calls `chat.turn.get`. The snapshot has the turn's `status`, `user_text`, `text` so far, `tools`, `commentary`, `waiting_for_approval` with the pending `approval`, and `seq`, the last `seq` it covers. The device replaces what it showed with the snapshot and ignores any delta with `seq` at or below it. The bridge keeps the last 50 turns; an older `turn_id` gets `NOT_FOUND`, and the device reloads `chat.history` instead. `conversations.list` names a conversation's running turn as `active_turn_id`.

**One turn at a time.** A conversation runs one turn at a time; a `chat.send` while one runs is queued (§11). `chat.cancel` stops a running turn; its result `status` is `stopping`, or the final status when the turn already ended, and the turn still ends with `chat.done`.

**History.** `chat.history` returns the newest page first; `next_before` is an opaque cursor for the next older page, or `null`. Within a page, messages are oldest first. Each is `{id, role, text, ts, tools?, attachments?}`, where `role` is `user` or `assistant`, `ts` is Unix seconds or `null`, and `tools` lists the tools an assistant message called. Tool results are not included. Pages may hold fewer than `limit` messages.

**Hiding messages.** `chat.hide` hides up to 50 messages, by their `chat.history` `id`, from Talaria on every device: `chat.history` leaves them out from then on, and every device gets `chat.hidden` and drops them. The agent keeps them: they stay in its session and it still remembers them in that conversation. Hiding can't be undone from Talaria. Unknown ids are accepted and change nothing, so a device can hide a message another device hid already.

**Pinning.** `conversations.pin` pins a conversation, or unpins it with `pinned: false`; `conversations.list` marks pinned ones `pinned: true`, and devices list them first. The bridge also pins the agent's session where the agent supports it.

**Errors.** `AGENT_UNAVAILABLE` (-32010) when no chat agent is configured or the agent cannot be reached; `CONFLICT` (-32013) when the queue is full (§11); `NOT_FOUND` (-32014) for an unknown conversation or turn; `INVALID_PARAMS` (-32602) for malformed params.

Schemas: `chat.send`, `chat.send.result`, `chat.started`, `chat.delta`, `chat.done`, `chat.cancel`, `chat.cancel.result`, `chat.approve`, `chat.approve.result`, `chat.turn.get`, `chat.turn.get.result`, `chat.history`, `chat.history.result`, `conversations.list`, `conversations.list.result`, `conversations.rename`, `conversations.delete`, `conversations.pin`, `conversations.result`, `chat.hide`, `chat.hide.result`, `chat.hidden`.

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
| `agent.models` | request | `{agent_id?}` → `{agent_id, current, default?, providers}` |
| `agent.set_default_model` | request | `{agent_id?, model}` → `{agent_id, default?}` |
| `agent.default_model` | notification | `{agent_id, default?}` |
| `conversations.set_model` | request | `{conversation_id, model}` → `{conversation_id, model}` |
| `chat.queued` | notification | `{conversation_id, turn_id, user_text, position, client_msg_id?, attachments?}` |
| `chat.steer` | request | `{turn_id, text}` → `{turn_id, accepted}` |
| `chat.aside` | request | `{conversation_id, text}` → `{aside_id}` |
| `chat.aside.done` | notification | `{conversation_id, aside_id, question, status, text, error?}` |
| `chat.status` | request | `{conversation_id}` → `{conversation_id, model?, messages?, tool_calls?, input_tokens?, output_tokens?, cost_usd?, active_turn_id?, queued}` |
| `account.balance` | request | `{}` → `{accounts}` |

**Models.** A model is `{provider, model}`, both strings as Hermes names them (for example `{"provider": "anthropic", "model": "claude-sonnet-4"}`). `agent.models` lists the providers the agent has credentials for, each `{id, name, models}` with `models` a list of model names, and `current`, the agent's default. `conversations.set_model` pins a conversation to a model from then on, and `chat.send` with `model` does the same before its turn, which is how a new conversation starts on a chosen model. `conversations.list` gives each conversation's pinned `model`, if any. A model the agent can't route fails with `INVALID_PARAMS`.

**Default model.** Talaria can keep its own default model for new chats, apart from the agent's (`current`), which its other apps share. `agent.set_default_model` sets it for every device (`model: null` goes back to the agent's), and every device gets `agent.default_model`; `agent.models` reports it as `default`. A new conversation whose first `chat.send` has no `model` is pinned to the default, so it shows in that conversation's `model` and can still be changed there. Conversations that already exist keep the model they have.

**Queue.** `chat.send` to a conversation whose turn is running is accepted and queued: the result has `queued: true`, and every device gets `chat.queued` with the turn's place in the queue, starting at 1. The bridge starts queued turns in order as each one ends, each with its usual `chat.started`. `chat.cancel` on a queued turn removes it, and the turn ends with `chat.done` with `status: cancelled`, without a `chat.started`. A conversation holds at most 5 queued turns; one more fails with `CONFLICT`. The queue lives on the bridge, so it keeps going when the device drops off, but not across a bridge restart: queued turns are then lost. `conversations.list` names a conversation's queued turns as `queued_turn_ids`. Until it starts, a queued turn's `chat.turn.get` snapshot has `status: queued`.

**Steer.** `chat.steer` hands a note to a running turn without stopping it; the agent reads it after its next tool call. `accepted` is `false` when the turn was already finishing; a turn that isn't running fails with `CONFLICT`.

**Aside.** `chat.aside` asks a side question about a conversation (Hermes's `/btw`) without adding to it or waiting for its running turn. The bridge asks the agent in a separate, throwaway request that carries the conversation's recent messages; the answer comes to every device as `chat.aside.done`, with `status` `completed` or `failed`. Asides are not saved: they are not in `chat.history`, and a device that was offline misses them.

**Status.** `chat.status` reports on a conversation, from what Hermes keeps about its session: the pinned `model`, how many `messages` and `tool_calls` it has, the `input_tokens` and `output_tokens` it has used, the estimated `cost_usd`, the running turn and how many turns are `queued`. Fields Hermes doesn't report are left out.

**Balance.** `account.balance` lists the provider accounts the bridge can check, each `{provider, name, remaining, currency, top_up_url}`: today only OpenRouter, when the operator has given the bridge an OpenRouter management key. That key never leaves the bridge. `remaining` is the credit left (purchased minus used) as a number in `currency` (`USD`), and `top_up_url` is the provider's page for adding credit. The list is empty when nothing is configured; a provider that can't be reached is listed with `error` instead of `remaining`.

Schemas: `agent.models`, `agent.models.result`, `agent.set_default_model`, `agent.set_default_model.result`, `agent.default_model`, `conversations.set_model`, `conversations.set_model.result`, `chat.queued`, `chat.steer`, `chat.steer.result`, `chat.aside`, `chat.aside.result`, `chat.aside.done`, `chat.status`, `chat.status.result`, `account.balance`, `account.balance.result`; `chat.send`, `chat.send.result` and `conversations.list.result` gain `model`, `queued` and `queued_turn_ids`.

## 12. Files on the server (M2)

Devices can browse, open and reuse the files that live next to the agent: the inbox where the bridge saves files sent from Talaria (§10), and any folder the operator shares, such as the agent's workspace. Everything is read-only; the bridge never writes, renames or deletes through these methods.

| Method | Direction | Params → result |
|---|---|---|
| `files.roots` | request | `{agent_id?}` → `{agent_id, roots}` |
| `files.list` | request | `{root, path?, query?, agent_id?}` → `{root, path, entries, truncated}` |
| `files.read` | request | `{root, path, offset?, agent_id?}` → `{size, mime, offset, data, eof}` |

**Roots.** A root is `{id, name}`, a folder the operator listed for the agent (`files` in `agents.json`), plus the inbox as `{id: "inbox", name: "Sent from Talaria"}` when the agent has one. A root the bridge can't read is listed with `error`.

**Listing.** `path` is a folder inside the root, written with `/` and relative to it; it defaults to the root itself. Without `query`, `entries` lists that folder; with `query`, it lists files anywhere under `path` whose name contains `query`, ignoring case. Each entry is `{name, path, kind, size?, mime?, modified}`: `kind` is `file` or `folder`, `path` is relative to the root, `modified` is Unix seconds, and folders have no `size` or `mime`. Folders come first, then newest first. At most 500 entries come back; `truncated` says whether more were left out. Names starting with `.` are never listed, and neither is anything that leads outside the root (a `..` segment, a symbolic link pointing out).

**Reading.** `files.read` returns a file in chunks: `data` is base64 (standard alphabet, with padding) of at most 512 KiB starting at `offset` (default 0), `size` is the whole file's size and `eof` says whether this chunk ends it. A device reads the next chunk from `offset + decoded length`. Files over 20 MiB fail with `INVALID_PARAMS`.

**Asking about a file.** `chat.send` takes `files: [{root, path}]` (together with `attachments`, at most 10 per message). The bridge adds an `Attached file:` line for each, with the path where the agent sees it, as for a file sent from the device (§10), and `chat.started`, snapshots and history list it in `attachments` with `kind: file`. The file stays where it is.

**Errors.** `NOT_FOUND` for an unknown root or a missing path; `INVALID_PARAMS` for a path outside the root, a folder passed to `files.read`, or a file that is too large.

Schemas: `files.roots`, `files.roots.result`, `files.list`, `files.list.result`, `files.read`, `files.read.result`; `chat.send` gains `files`.

## 13. To-dos (M2)

A to-do list kept on the bridge, so every device shows the same one. The agent works on it through its tools (§15) and sees a to-do in a chat when one is handed to it.

| Method | Direction | Params → result |
|---|---|---|
| `todos.list` | request | `{}` → `{todos}` |
| `todos.add` | request | `{text, due?, group?}` → `{todo}` |
| `todos.update` | request | `{id, text?, done?, due?, group?}` → `{todo}` |
| `todos.delete` | request | `{id}` → `{id, deleted}` |
| `todos.comment` | request | `{id, text}` → `{todo}` |
| `todos.uncomment` | request | `{id, comment_id}` → `{todo}` |
| `todos.regroup` | request | `{}` → `{todos}` |
| `todos.changed` | notification | `{todos}` |

A to-do is `{id, text, done, created_at, done_at?, due?, conversation_id?, group?, comments?}`. `text` is 1 to 500 characters; `due` is a date, `YYYY-MM-DD`, or `null` to clear it; times are Unix seconds. `todos.list` returns open to-dos first, oldest first, then the 50 most recently done. After any change every device gets `todos.changed` with the whole list. At most 500 open to-dos; one more fails with `CONFLICT`.

**Comments.** `comments` lists notes on a to-do, oldest first, each `{id, text, by, at}`: `text` is 1 to 2000 characters, `by` is `you` (a device) or `agent` (§15), `at` is when. `todos.comment` adds one and `todos.uncomment` removes one; at most 50 per to-do, one more fails with `CONFLICT`. `comments` is left out when there are none.

**Groups.** `group` is a short name such as "Home" or "Work", 1 to 40 characters; `null` in `todos.update` clears it. When open to-dos without a group appear, the bridge waits a few seconds for more, then asks the agent to put each one in one of the groups already used or a new one, and every device gets `todos.changed`. It asks in a throwaway request, like an aside (§11), so no chat gains anything; if the agent can't be reached, the to-dos stay ungrouped until the next try. Grouping never moves a to-do that already has a group, so a group set by hand or by the agent stays. `todos.regroup` asks the agent to sort every open to-do again, groups included, and answers with the new list once it's done; it fails with `AGENT_UNAVAILABLE` if the agent can't be reached.

**Handing a to-do to the agent** is an ordinary `chat.send` with `todo_id`: the bridge records the new turn's conversation in the to-do's `conversation_id`, so devices can show "With Hermes" while that conversation's turn runs and open it from the to-do. Devices include the to-do's comments in the message.

Schemas: `todos.list`, `todos.list.result`, `todos.add`, `todos.update`, `todos.result`, `todos.delete`, `todos.delete.result`, `todos.comment`, `todos.uncomment`, `todos.regroup`, `todos.regroup.result`, `todos.changed`; `chat.send` gains `todo_id`.

## 14. Automations, calendar and Home (M2)

An automation is work the agent does on its own: **when**, **what to do** and **where the result goes**. For Hermes, every automation is one of its scheduled (cron) jobs, so automations keep running when no device is connected, and jobs made anywhere else (in a Hermes chat, from Telegram, by asking in Talaria) are listed too.

| Method | Direction | Params → result |
|---|---|---|
| `automations.list` | request | `{agent_id?}` → `{automations}` |
| `automations.add` | request | `{name, when, task, result_to?, agent_id?}` → `{automation}` |
| `automations.describe` | request | `{text, agent_id?}` → `{reply, automations}` |
| `automations.update` | request | `{id, name?, when?, task?, result_to?, paused?}` → `{automation}` |
| `automations.run` | request | `{id}` → `{automation}` |
| `automations.run_in_chat` | request | `{id}` → `{conversation_id, turn_id, title}` |
| `automations.delete` | request | `{id}` → `{id, deleted}` |
| `automations.runs` | request | `{id, limit?}` → `{runs}` |
| `automations.ran` | notification | `{id, name, run}` |
| `automations.changed` | notification | `{automations}` |
| `calendar.day` | request | `{date?}` → `{date, events}` or `{date, events: [], error}` |
| `home.get` | request | `{}` → `{date, results}` |
| `home.dismiss` | request | `{id, at}` → `{}` |
| `home.changed` | notification | `{date, results}` |

**An automation** is `{id, name, when, task, result_to, made_in, state, next_run_at?, last_run_at?, last_status?, last_error?, schedule_text}`:
- `when` is one of
  - `{kind: "time", schedule}`: `schedule` is a 5-field cron expression (`30 7 * * 1-5`), an interval (`every 2h`) or an ISO 8601 time for a single run.
  - `{kind: "arrives", watch, from, until, days, fallback?}`: when something matching `watch` arrives (an email or file, described in words, such as `an email from gemini-notes@google.com with "Transcript" in the subject`), checked every 10 minutes between `from` and `until` (`HH:MM`, the agent's time zone) on `days` (`mon`…`sun`), and run once per day at most. `fallback`, if set, is what to do instead at `until` when nothing arrived that day.
  - `{kind: "after_event", event, delay_minutes, days}`: `delay_minutes` (0 to 240) after a calendar event whose title contains `event` ends.
  - `{kind: "other"}`: a job the agent made some other way, described by `schedule_text`; it can be run, paused and deleted, but `when` can't be changed from Talaria.
- `task` is what the agent should do, in words, up to 4000 characters.
- `result_to` is `home` (Home and a notification on every device), `chat` (a new conversation per run) or `log` (only `automations.runs`). Jobs made outside Talaria count as `log` unless changed.
- `made_in` is `talaria` or `agent`; `state` is `scheduled`, `paused`, `running`, `completed` (a single run that has happened) or `error`; times are Unix seconds; `last_status` is `ok`, `error`, `nothing` (an `arrives` or `after_event` check that found nothing to do) or `blocked` (see **Blocked runs**).

**How the bridge does it for Hermes.** `time` is a Hermes job with that schedule. `arrives` and `after_event` are a Hermes job every 10 minutes in the window whose prompt tells the agent to check first and to answer `[SILENT]` when there is nothing to do yet or it already ran that day; the bridge keeps the structured `when` alongside the job id. All jobs Talaria makes deliver locally; the bridge reads each run's result and passes it on.

**Asking in words.** `automations.describe` sends `text` (such as "every weekday at 8, summarise my unread email") to the agent, which creates the job with its own scheduling tool and answers in `reply` (what it set up, or a question). `automations` is the list afterwards. Jobs the agent makes this way, or in any chat, show up with `made_in: agent` and `when.kind: other`.

**Runs.** A run is `{at, status, text?, error?, blocked?, conversation_id?}`. When a run of a job finishes with something to say, every device gets `automations.ran`; a device shows a notification when `result_to` is `home`, or when `status` is `blocked`. `automations.runs` returns the newest first, at most `limit` (default 10, at most 50). `automations.changed` goes to every device when the list changes (added, edited, paused, run, deleted, or a change the bridge noticed in the agent's jobs).

**Blocked runs.** A scheduled run has nobody to answer an approval (§9), so the agent refuses anything that needs one. For Hermes, `approvals.cron_mode` (default `deny`) blocks a dangerous command in a cron job unless it was approved permanently. When the bridge finds such a refusal in a run's session, the run's `status` is `blocked` and `blocked` says what was refused (Hermes's description, such as `recursive delete`), up to 500 characters; `text` is kept when the agent answered anyway. Only the first of consecutive blocked runs of a job sends `automations.ran`, so a window job blocked every 10 minutes notifies once.

`automations.run_in_chat` runs the automation's task now in a new conversation, as if the owner had sent it from this device (it returns what `chat.send` returns, and the turn streams as in §9). There the approval card appears, and answering `always` approves the command for the agent's future runs too, scheduled ones included. It works for any automation, not only blocked ones. Nothing about the job changes: the bridge never sets `approvals.cron_mode` or approves anything on the owner's behalf.

**Calendar.** `calendar.day` lists the events of `date` (`YYYY-MM-DD`, default today in the bridge's time zone), each `{title, start, end, all_day, location?}`, with `start` and `end` as ISO 8601 times (dates for all-day events), sorted by start. The bridge reads the calendar the agent can read, so devices never hold calendar credentials. Without a calendar set up, `events` is empty and `error` says why.

**Home.** `home.get` returns today's `results`: the latest run today of each automation with `result_to: home`, and of any other automation whose latest run today is `blocked`, each `{id, name, run}`, newest first, so a device that was off sees the morning summary, or that it was blocked, when it opens.

`home.dismiss` takes one run (`id` of the automation, `at` of the run) off Home for good, and every device gets `home.changed` with what `home.get` now returns. Only that run goes: a later run of the same job that belongs on Home shows again. When `automations.run_in_chat` is used on a job whose latest run was `blocked`, and the chat turn ends `completed`, the bridge dismisses that blocked run the same way. Dismissing an unknown or already dismissed run is not an error.

**Errors.** `AGENT_UNAVAILABLE` when the agent's jobs can't be reached; `NOT_FOUND` for an unknown automation; `INVALID_PARAMS` for a bad schedule, window or day; `CONFLICT` when changing the `when` of a `kind: other` job.

Schemas: `automations.list`, `automations.list.result`, `automations.add`, `automations.describe`, `automations.describe.result`, `automations.update`, `automations.run`, `automations.result`, `automations.run_in_chat` (result: `chat.send.result`), `automations.delete`, `automations.delete.result`, `automations.runs`, `automations.runs.result`, `automations.ran`, `automations.changed`, `calendar.day`, `calendar.day.result`, `home.get`, `home.get.result`, `home.dismiss`, `home.dismiss.result`, `home.changed`.

## 15. Tools for the agent (M2)

The bridge offers the agent a few tools over the Model Context Protocol (MCP), so the agent can work on what Talaria keeps, such as the to-do list (§13), from any chat, automation or other app it is used in. This is between the bridge and the agent only; devices see the results through the usual notifications.

**Endpoint.** MCP's Streamable HTTP transport at `http://127.0.0.1:<port>/mcp` (port 8767 by default), on loopback only. The bridge answers each `POST` with one JSON-RPC response as `application/json` (or `202` for a notification) and offers no event stream, so `GET` gets `405`. It speaks protocol versions `2025-06-18`, `2025-03-26` and `2024-11-05`, and supports `initialize`, `ping`, `tools/list` and `tools/call`.

**Who may call.** Each agent in `agents.json` with a `tools_key_file` gets the endpoint; the file holds a random token of at least 32 characters, readable only by the bridge, and the agent sends it as `Authorization: Bearer <token>`. A request without a known token gets `401`; a request with an `Origin` header that isn't a loopback address gets `403`, so a web page can't reach the endpoint through the browser.

**Tools.**

| Tool | Arguments | What it does |
|---|---|---|
| `todo_list` | `{include_done?}` | The open to-dos, oldest first, each `{id, text, due?, group?, comments?}` with comments as `{text, by}`; with `include_done: true` also the recently done ones. |
| `todo_add` | `{text, due?, group?}` | Adds a to-do (`text` 1 to 500 characters, `due` as `YYYY-MM-DD`, `group` 1 to 40 characters) and returns it. |
| `todo_update` | `{id, text?, due?, done?, group?}` | Changes a to-do, ticks it off (`done: true`) or opens it again, and returns it. `due: null` and `group: null` clear them. |
| `todo_comment` | `{id, text}` | Adds a comment to a to-do, marked as the agent's, and returns the to-do. |
| `automation_report_to` | `{id, to}` | Sets where one of the agent's scheduled jobs reports in Talaria: `home` (Home and a notification on every device), `chat` (a new conversation per run) or `log` (§14), as `automations.update` does. Its description tells the agent to create a job delivering locally and call this, instead of asking the owner where to send results. |
| `server_op` | `{op, params?}` | Runs a server operation from the catalogue (§16). Tier 0 returns its result. Tier 1–2 waits up to 120 s for the owner to approve on a device, then returns the result, or says it was denied or expired. Offered only when `talaria-ops` is reachable. |

A tool's result is one `text` content item holding JSON. A bad argument, an unknown id or a full list is a tool result with `isError: true` and a sentence saying why, so the agent can correct itself. After any change every device gets `todos.changed` (§13). The agent can't delete to-dos or comments: it ticks to-dos off instead, so nothing the agent reads (an email, a web page) can make it wipe the list.

## 16. Server operations

PROTOCOL §10.8 describes them. `talaria-ops` (root, `bridge/src/talaria_bridge/ops/`) owns the catalogue and checks every approval itself. It listens on the Unix socket `/run/talaria-ops/ops.sock` and accepts only the bridge's user. Requests are newline-delimited JSON:

| Request | Answer |
|---|---|
| `{"cmd": "catalogue"}` | `{"ops": [...]}` |
| `{"cmd": "run", "op", "params", "requested_by"}` (tier 0 only) | `{"result": {...}}` |
| `{"cmd": "prepare", "op", "params", "requested_by"}` (tier 1–2) | `{"request_id", "op", "params_json", "tier", "summary", "expires_at"}` |
| `{"cmd": "execute", "request_id", "device_id", "choice", "sig"}` | `{"result": {...}}`, or `{"result": null}` for `deny` |

Any failure is `{"error": "<sentence>"}`. `params_json` is `json.dumps(params, sort_keys=True, separators=(",", ":"))`. A prepared request lives 120 s and is used at most once. Every run, of any tier, is appended to `/var/log/talaria-ops/audit.jsonl`.
