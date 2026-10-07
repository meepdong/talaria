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
| `conversations.archive` | request | `{conversation_id, archived}` → `{conversation_id, archived}` |
| `voice.speech` | request | `{text, voice?}` → `{format, audio}` |
| `voice.ack` | request | `{text, conversation_id?}` → `{text}` |
| `talk.turn` | request | `{audio, format?, conversation_id?, early?}` → `{talk_id}` |
| `talk.say` | request | `{text?, conversation_id?, voice?}` → `{talk_id}` |
| `talk.commit`, `talk.cancel` | request | `{talk_id}` → `{}` |
| `talk.voices` | request | `{}` → `{voice, voices}` |
| `talk.voice` | request | `{voice}` → `{voice}` |
| `talk.end` | request | `{conversation_id}` → `{}` |
| `talk.audio`, `talk.text`, `talk.heard`, `talk.done` | notification | `{talk_id, …}` |
| `chat.hide` | request | `{conversation_id, message_ids}` → `{conversation_id, message_ids}` |
| `chat.hidden` | notification | `{conversation_id, message_ids}` |
| `chat.file`, `chat.talk` | notification | `{conversation_id, message}` |

**Sending.** `chat.send` without `conversation_id` starts a new conversation, titled with the start of the message. `agent_id` defaults to the first agent with chat configured. The result comes back before the turn's first `chat.delta`. A retry with the same `client_msg_id` within 10 minutes returns the original turn instead of sending twice. `attachments` lists photos and files uploaded first (§10); `text` may then be empty.

**Every device sees every turn.** `chat.started`, `chat.delta` and `chat.done` go to every session past `ready`, including turns sent from another device, so the phone and the laptop show the same conversation live. `chat.started` repeats the sender's `client_msg_id`, so the sending device can match it to the message it already shows, even before the `chat.send` result is handled.

**Deltas.** `seq` starts at 1 and grows by one per notification of a turn, `chat.done` included. `kind` is one of:
- `text`: `text` is the next piece of the answer.
- `tool_progress`: `tool` is `{name, state, preview?}`, with `state` one of `started`, `completed`, `failed`. `preview` is at most 500 characters.
- `commentary`: `text` is a progress note the agent wrote between tool calls. It is not part of the answer. The bridge writes one itself, "Tidying up this long chat…", when a turn in a big conversation (about 48k tokens read per model call last time) has shown nothing for 8 s: the agent is most likely compressing it first.
- `approval`: the agent is waiting for an approval before it runs something. `text` describes it, and `approval` is `{choices, command?, description?, request_id?}`: what it wants to run, why it was flagged, and the answers it accepts, from `once`, `session` (this conversation), `always` and `deny`.
- `approval_done`: the approval was answered from a device; `choice` says how. Every device drops its approval card.

**Done.** `status` is `completed`, `failed` (with `error`) or `cancelled`. `text` is the whole answer, which may differ from the joined `text` deltas, and clients replace the streamed text with it. `usage` holds `input_tokens`, `output_tokens`, `total_tokens`; `runtime` holds the `provider` and `model` that actually answered.

**Approvals.** `chat.approve` answers the approval a running turn waits for, with one of the `choices` from its `approval` delta. Only a device can answer: a conversation started from Talaria has no other place to approve it, and an unanswered approval times out in Hermes as a denial. It fails with `CONFLICT` when nothing is waiting (already answered, or the turn ended) and `INVALID_PARAMS` for a choice Hermes didn't offer. The bridge passes the answer to Hermes's `POST /v1/runs/{run_id}/approval`.

**Catching up.** A device that reconnects while it was showing a running turn calls `chat.turn.get`. The snapshot has the turn's `status`, `user_text`, `text` so far, `tools`, `commentary`, `waiting_for_approval` with the pending `approval`, and `seq`, the last `seq` it covers. The device replaces what it showed with the snapshot and ignores any delta with `seq` at or below it. The bridge keeps the last 50 turns; an older `turn_id` gets `NOT_FOUND`, and the device reloads `chat.history` instead. `conversations.list` names a conversation's running turn as `active_turn_id`.

**One turn at a time.** A conversation runs one turn at a time; a `chat.send` while one runs is queued (§11). `chat.cancel` stops a running turn; its result `status` is `stopping`, or the final status when the turn already ended, and the turn still ends with `chat.done`.

**Files from the agent.** When the agent calls `send_file` (§15), every device gets `chat.file`: an assistant `message` `{id, role, text, ts, attachments}` whose one attachment is `{kind, name, mime, size, root, path}`, and `text` is the caption. The device opens it with `files.read {root, path}` (§12). The bridge keeps these messages, so `chat.history` includes them at their time (ids `f-<n>`), and `chat.hide` hides them like any other.

**History.** `chat.history` returns the newest page first; `next_before` is an opaque cursor for the next older page, or `null`. Within a page, messages are oldest first. Each is `{id, role, text, ts, tools?, attachments?}`, where `role` is `user` or `assistant`, `ts` is Unix seconds or `null`, and `tools` lists the tools an assistant message called. Tool results are not included. Pages may hold fewer than `limit` messages.

**Hiding messages.** `chat.hide` hides up to 50 messages, by their `chat.history` `id`, from Talaria on every device: `chat.history` leaves them out from then on, and every device gets `chat.hidden` and drops them. The agent keeps them: they stay in its session and it still remembers them in that conversation. Hiding can't be undone from Talaria. Unknown ids are accepted and change nothing, so a device can hide a message another device hid already.

**Pinning.** `conversations.pin` pins a conversation, or unpins it with `pinned: false`; `conversations.list` marks pinned ones `pinned: true`, and devices list them first. The bridge also pins the agent's session where the agent supports it.

**Archiving.** `conversations.archive` takes a conversation off the list (devices show it under Archived), or puts it back with `archived: false`; `conversations.list` marks archived ones `archived: true`. Nothing is deleted, on the bridge or in the agent. A new message sent in an archived conversation puts it back. Devices archive with a swipe left, with Undo; deleting stays in the long-press menu.

**Talk's voice.** When an agent has a `voice_key_file` (an OpenRouter key), devices can ask for natural speech and a quick first line. `voice.speech` turns up to 150 characters into audio, base64: `wav` (24 kHz 16-bit mono, from Gemini 3.8 Flash-Lite TTS, the default, voice Despina) or `mp3` (from Qwen-Audio TTS Flash, with `voice_engine: "qwen"`); `voice_name` in agents.json picks another of the engine's voices. `voice.ack` returns one short line acknowledging what was said (from a small fast model, never answering it), using the conversation's last answer as context. Without a voice key both are `-32601` and devices use their own voice. Messages spoken in Talk start with 🎙, which tells the agent the reply will be read aloud.

**Talk 3: the talker.** With a voice key, the bridge also runs a voice model that hears audio (GPT Audio Mini through OpenRouter). `talk.turn` sends what the owner said as WAV or MP3 (at most 900,000 base64 characters); the talker hears it directly, uses its tools (to-dos: add, tick off by name, list; the day's agenda) and answers out loud, streaming `talk.audio` (24 kHz 16-bit mono PCM, base64, in `seq` order) and `talk.text` (its words), then `talk.done` with all it said. For anything else it sends the agent a self-contained brief in the conversation, as a message starting "🎙 From Talk:", and says so; `talk.done` carries the conversation it worked in (a new one if it started one). While a conversation has been talked in during the last 15 minutes, the agent's finished replies in it are said by the talker on its own (`talk.done` with `unprompted: true`; `talk.audio` and `talk.text` carry the `conversation_id` so devices recognise them); `talk.end` stops that. **Talk is kept in the chat.** Alongside the talker, the bridge has Whisper Large v3 Turbo write down what the owner said (about 1–2 s; prompted with the agent's name and the names in open to-dos), or the talker's own model if Whisper fails or `talk_transcriber` in agents.json is `"talker"`. Every `talk.turn` happens in a conversation: the one given, or a new one the bridge starts, titled with the owner's first words. The words go to devices as `talk.heard {talk_id, text, conversation_id}` as soon as they're known, and both sides of the exchange are kept in the conversation: every device gets `chat.talk {conversation_id, message}`, with a user `message` `{id, role, text, ts}` whose text starts with 🎙, then the talker's answer as an assistant message. `chat.history` includes them at their time (ids `t-<n>`), and `chat.hide` hides them like any other. The agent doesn't see them; the talker's brief tells it what it needs. Approval questions aren't kept; the talker's retelling of a worker's report is (see "Tally leads Talk"). `talk.say` has the talker say a line as it is (an approval's question); with `voice`, in that voice, and without `text` a sample line, so the owner can hear a voice before picking it. **Answering early.** A device may send `talk.turn` with `early: true` at the owner's first short pause (0.35 s), before its own end-of-turn wait: the talker starts at once, but holds what it says, does (tools, briefs) and keeps until `talk.commit {talk_id}` (the wait passed in silence), and `talk.cancel` (they went on talking) drops it with nothing said or done; an early turn neither committed nor cancelled is dropped after 30 s. When the talker goes quiet to use a tool, devices hear a short filler in its voice ("Mm-hm, one sec."). `talk.voices` lists the talker's voices `[{id, label}]` and the one in use; `talk.voice` picks one for every device, kept across restarts (agents.json `talk_voice` is only the first default). The talker never approves anything. Without a talker these are `-32601`, and devices fall back to speech-to-text, the agent and `voice.speech`.

**Tally leads Talk (workers).** The talker has the assistant's own name and manner (agents.json `talk_name`, e.g. "Tally", and `talk_persona`; by default the agent's name) and leads: it does the talking, and the agent and Hermes's bots (§18.1) are its **workers**. A job for a worker goes as a **work order**: a turn in the worker's conversation (the Talk conversation for the agent, the bot's own chat for a bot) whose message to the worker starts "🎙 Work order from <talk name>" and asks for a factual report to the talker; `chat.started`, the turn snapshot and `chat.history` mark such turns with `worker` (the worker's name) on the order and on the report, and `user_text` / the order's history `text` is the brief alone. Devices show a work order and its report as one collapsed card ("Tally asked Hermes…"), not as messages of their own: in a Talk conversation the messages are the owner's words and the talker's, and the talker's retelling of a report is kept as its message. The talker can give several workers work at once, pass them files the owner sent in the conversation (by name; the bridge adds their paths, which every worker can read), check on running work, add a note to it (as `chat.steer`) and stop it (as `chat.cancel`); a worker's report is said by the talker when it comes, whichever conversation it came in.

**Hermes compatibility.** Hermes updates itself, so the bridge checks that every call it makes to the agent's API (sessions, the reply stream, stop/steer/approval, models, jobs) still answers with the fields it reads: when the agent's version changes and at least once a day, against a scratch session it deletes afterwards, with one tiny test message (a fraction of a cent). Calls that would change something real (a run, a job) are made with an id that doesn't exist and must answer "not found"; jobs are never created. When something changed, Home gets the bridge's own card (§14) and `talaria doctor --hermes` names each change. No new messages: devices see an ordinary Home result.

**Errors.** `AGENT_UNAVAILABLE` (-32010) when no chat agent is configured or the agent cannot be reached; `CONFLICT` (-32013) when the queue is full (§11); `NOT_FOUND` (-32014) for an unknown conversation or turn; `INVALID_PARAMS` (-32602) for malformed params.

Schemas: `chat.send`, `chat.send.result`, `chat.started`, `chat.delta`, `chat.done`, `chat.cancel`, `chat.cancel.result`, `chat.approve`, `chat.approve.result`, `chat.turn.get`, `chat.turn.get.result`, `chat.history`, `chat.history.result`, `conversations.list`, `conversations.list.result`, `conversations.rename`, `conversations.delete`, `conversations.pin`, `conversations.archive`, `conversations.result`, `chat.hide`, `chat.hide.result`, `voice.speech`, `voice.speech.result`, `voice.ack`, `voice.ack.result`, `talk.turn`, `talk.turn.result`, `talk.say`, `talk.end`, `talk.end.result`, `talk.voices`, `talk.voices.result`, `talk.voice`, `talk.voice.result`, `talk.commit`, `talk.commit.result`, `talk.cancel`, `talk.cancel.result`, `talk.audio`, `talk.text`, `talk.heard`, `talk.done`, `chat.talk`, `chat.hidden`.

## 10. Attachments (M2)

Photos and files travel over the session in chunks, so no second port or URL is needed. This replaces the HTTPS `PUT` upload sketched in PROTOCOL §9.

| Method | Direction | Params → result |
|---|---|---|
| `blob.begin` | request | `{name, mime, size, sha256}` → `{blob_id, chunk_bytes}` |
| `blob.put` | request | `{blob_id, offset, data}` → `{blob_id, received}` |
| `blob.commit` | request | `{blob_id}` → `{blob_id, kind, name, mime, size}` |

**Uploading.** `size` is at most 2 GiB and `sha256` is the lowercase hex digest of the whole file. The device sends chunks in order: `offset` is the number of bytes sent so far and `data` is base64 (standard alphabet, with padding) of at most `chunk_bytes` bytes (512 KiB). A chunk at the wrong offset fails with `CONFLICT`. `blob.commit` checks the size and digest; a mismatch fails with `INVALID_PARAMS` and drops the blob. An upload that fails part way is started again with a new `blob.begin`.

**Sending.** `chat.send` takes `attachments: [{blob_id}]`, up to 128. Each committed blob can be sent once, by any device past `ready`; the bridge drops blobs not sent within an hour. `kind` is `image` for `image/jpeg`, `image/png`, `image/webp` and `image/gif` up to 5 MiB, and `file` for anything else. When the agent has an inbox, **every** attachment is saved there as a file with an `Attached file:` line, photos included, so the agent's tools can use them (a signature to put on a PDF). Photos are also sent inline when a message has at most 10 of them, together at most 7 MiB, so the agent sees them. Without an inbox only photos can be sent, inline, and a message whose photos don't fit is refused with `INVALID_PARAMS`. History lists each attachment once, by name. A file may be up to 2 GiB. Uploads not yet sent may hold up to 8 GiB per bridge, and `blob.begin` answers `CONFLICT` when the upload would leave the server with less than 10 GiB free.

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

**Reading.** `files.read` returns a file in chunks: `data` is base64 (standard alphabet, with padding) of at most 512 KiB starting at `offset` (default 0), `size` is the whole file's size and `eof` says whether this chunk ends it. A device reads the next chunk from `offset + decoded length`. Files over 2 GiB fail with `INVALID_PARAMS`.

**Asking about a file.** `chat.send` takes `files: [{root, path}]` (together with `attachments`, at most 128 per message). The bridge adds an `Attached file:` line for each, with the path where the agent sees it, as for a file sent from the device (§10), and `chat.started`, snapshots and history list it in `attachments` with `kind: file`. The file stays where it is.

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
| `home.read` | request | `{id, at, read?}` → `{}` |
| `home.restore` | request | `{id, at}` → `{}` |
| `home.archived` | request | `{}` → `{results}` |
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

**Home.** `home.get` returns today's `results`: the latest run today of each automation with `result_to: home`, and of any other automation whose latest run today is `blocked`, each `{id, name, run}`, newest first, so a device that was off sees the morning summary, or that it was blocked, when it opens. Results can also be the bridge's own: `id` `hermes-check` ("Hermes compatibility check", §9) is a run with `status: error` saying what a Hermes update changed; it comes with `automations.ran` (`result_to: home`) once per new problem and is dismissed by the bridge when a later check passes.

`home.dismiss` archives one run: it takes the run (`id` of the automation, `at` of the run) off Home for good, and every device gets `home.changed` with what `home.get` now returns. Only that run goes: a later run of the same job that belongs on Home shows again. When `automations.run_in_chat` is used on a job whose latest run was `blocked`, and the chat turn ends `completed`, the bridge dismisses that blocked run the same way. Dismissing an unknown or already dismissed run is not an error.

`home.read` marks one run read (`read` defaults to true; `false` marks it unread again), and `home.get` and `home.changed` mark read runs `read: true`; every device gets `home.changed`, so a result opened on the phone stops looking new on the laptop, and devices take down its notification. `home.archived` returns the runs archived in the last 30 days that the run log still has, newest first, at most 100, each `{id, name, run}`; `home.restore` takes one back off that list (Home shows it again while it is that automation's latest run today), and every device gets `home.changed`. Read and archived marks are kept for 30 days. `automations.run_in_chat` tells the agent how the automation's last run went (what it was blocked from doing, why it failed, or what it said), so it knows why it is being run again.

**Errors.** `AGENT_UNAVAILABLE` when the agent's jobs can't be reached; `NOT_FOUND` for an unknown automation; `INVALID_PARAMS` for a bad schedule, window or day; `CONFLICT` when changing the `when` of a `kind: other` job.

Schemas: `automations.list`, `automations.list.result`, `automations.add`, `automations.describe`, `automations.describe.result`, `automations.update`, `automations.run`, `automations.result`, `automations.run_in_chat` (result: `chat.send.result`), `automations.delete`, `automations.delete.result`, `automations.runs`, `automations.runs.result`, `automations.ran`, `automations.changed`, `calendar.day`, `calendar.day.result`, `home.get`, `home.get.result`, `home.dismiss`, `home.dismiss.result`, `home.read`, `home.read.result`, `home.restore`, `home.restore.result`, `home.archived`, `home.archived.result`, `home.changed`.

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
| `send_file` | `{path, caption?}` | Sends a file the agent made into the conversation it is replying in (§9 `chat.file`): `path` as the agent sees it, inside a folder the bridge shares (its workspace or the inbox, §12), at most 2 GiB, no hidden files; `caption` up to 1000 characters. With no reply running, it goes to the agent's most recently active conversation of the last 30 minutes; otherwise it is refused. Returns `{conversation_id, name, size}`. Its description tells the agent to use it whenever a result is a file, instead of saying it can't send one. |
| `server_op` | `{op, params?}` | Runs a server operation from the catalogue (§16). Tier 0 returns its result. Tier 1–2 waits up to 120 s for the owner to approve on a device, then returns the result, or says it was denied or expired. Offered only when `talaria-ops` is reachable. |

A tool's result is one `text` content item holding JSON. A bad argument, an unknown id or a full list is a tool result with `isError: true` and a sentence saying why, so the agent can correct itself. After any change every device gets `todos.changed` (§13). The agent can't delete to-dos or comments: it ticks to-dos off instead, so nothing the agent reads (an email, a web page) can make it wipe the list.


**Bots as helpers.** When the bridge has Hermes's bots (§18.1), the agent also gets `list_bots` (each bot's name,
profile and what it's for), `ask_bot {bot, message, files?, wait_seconds?}` and `bot_job {job}`. `ask_bot` sends a
work order (§9 "Tally leads Talk", from "Hermes, the owner's main agent", asking the bot to do it itself rather than
hand it on) into the bot's own conversation, where devices show it as a job card, and waits up to `wait_seconds`
(default 240, at most 280) for the report: `{job, bot, status, report?, error?}`; a job still running returns its
`job` id, and `bot_job` gives its state or report later. `files` are paths the bot can read too (the inbox,
`/workspace/projects`). At most three such jobs run at once (bots have these tools too, so chains stay short).
## 16. Server operations

PROTOCOL §10.8 describes them. `talaria-ops` (root, `bridge/src/talaria_bridge/ops/`) owns the catalogue and checks every approval itself. It listens on the Unix socket `/run/talaria-ops/ops.sock` and accepts only the bridge's user. Requests are newline-delimited JSON:

| Request | Answer |
|---|---|
| `{"cmd": "catalogue"}` | `{"ops": [...]}` |
| `{"cmd": "run", "op", "params", "requested_by"}` (tier 0 only) | `{"result": {...}}` |
| `{"cmd": "prepare", "op", "params", "requested_by"}` (tier 1–2) | `{"request_id", "op", "params_json", "tier", "summary", "expires_at"}` |
| `{"cmd": "execute", "request_id", "device_id", "choice", "sig"}` | `{"result": {...}}`, or `{"result": null}` for `deny` |

Any failure is `{"error": "<sentence>"}`. `params_json` is `json.dumps(params, sort_keys=True, separators=(",", ":"))`. A prepared request lives 120 s and is used at most once. Every run, of any tier, is appended to `/var/log/talaria-ops/audit.jsonl`.

**Hermes's skills.** `hermes.skills` (tier 0) lists the skills installed for the agent, `data` `[{name, description, category, enabled}]`, where `enabled` means it is loaded for Talaria (it isn't in Hermes's `skills.platform_disabled.api_server`). `hermes.skill.set {skill, enabled: "on"|"off"}` (tier 1) changes that with `hermes config set` and restarts Hermes, which stops a reply in progress. `hermes.skills.set {changes}` (tier 1) does several at once with one restart: `changes` is `name=on|off` pairs separated by commas (at most 100), every name one of the listed skills. Devices show the skills as switches on the Server page; switching only marks a change, and an Apply button sends them all as one `hermes.skills.set`, so one approval and one restart cover them. While that approval waits or the operation runs, the switches are locked and say so.

### 16.1 Terminals

The owner can follow and answer the agent sessions they run in **root's tmux** (Claude Code, opencode) from a device.
This is not a terminal emulator: talaria-ops reads the session's rendered screen (`tmux capture-pane -p -e`, with
colour) and types with `tmux send-keys`. Sessions are never resized, so other attached clients are left as they are.
**Terminals are for devices only: the agent's `server_op` refuses every `tmux.*` and `terminal.*` operation.**

Operations in the catalogue:

- `tmux.sessions` (tier 0): `data.sessions` is a list of `{name, command, path, cols, rows, attached, activity}`
  for the active pane of each session (`activity` in Unix seconds). It shows no screen content.
- `terminal.watch {session}` (tier 1) and `terminal.control {session}` (tier 2): once approved, talaria-ops creates a
  **grant** for the approving device and that session, and the result's `data` is
  `{grant, session, control, expires_at}`. A grant is `tg-` and 32 hex digits; it ends 30 minutes after it was last
  used, after 12 hours in any case, when the device is revoked, or with `term.close`. Only `control` grants may type.
- `tmux.new {name, folder?, command?}` (tier 2): starts a root tmux session `name` (letters, digits, `_ . @ + -`, at
  most 40) in `folder` (any existing absolute path, default `/root`) running `command` (any one-line command, at most
  1000 characters; empty: a shell), and answers like `terminal.control`: a control grant for the approving device.
- `tmux.kill {session}` (tier 2): ends a session and every grant to it.

Apps ask for the device owner's fingerprint or screen lock before signing a terminal approval, unless they did in the
last 5 minutes while the app stayed open (owner, 2026-10-05).

talaria-ops also answers, for the bridge only:

| Request | Answer |
|---|---|
| `{"cmd": "term.screen", "grant", "device_id"}` | `{"screen": {session, cols, rows, cursor_x, cursor_y, command, control, text}}` |
| `{"cmd": "term.keys", "grant", "device_id", "keys"}` | `{"ok": true}` |
| `{"cmd": "term.close", "grant", "device_id"}` | `{"ok": true}` |
| `{"cmd": "term.history", "grant", "device_id", "lines"}` | `{"history": {session, text}}` |

A grant only answers for the device it was given to, and every `term.keys` is audited with what was typed.

Devices use them through the bridge:

| Method | Direction | Params → result |
|---|---|---|
| `term.watch` | request | `{grant}` → `{grant, session, control}` |
| `term.keys` | request | `{grant, keys}` → `{}` |
| `term.stop` | request | `{grant}` → `{}` |
| `term.history` | request | `{grant, lines}` → `{session, text}` |
| `term.screen` | notification | `{grant, session, cols, rows, cursor_x, cursor_y, command?, control, alternate?, text}` |
| `term.closed` | notification | `{grant, reason}` |

After `term.watch` the bridge sends the screen at once and then whenever it changes (it looks about 3 times a second,
10 times a second for 3 seconds after keys), only to that device's session, until `term.stop`, the session ends, or the device disconnects.
`text` is the screen's lines joined by `\n`, with SGR colour sequences (`ESC [ … m`) and nothing else. `keys` is a
list of `{text}` (typed literally, at most 2000 characters) and `{key}`: a tmux key name, i.e. `Enter`, `Escape`, `Tab`,
`BSpace`, `Space`, `Up`, `Down`, `Left`, `Right`, `Home`, `End`, `PPage`, `NPage`, `DC`, `IC`, or a letter, digit or one of
`@ [ ] \ ^ _ /`, each optionally after `C-`, `M-` or `C-M-` (so `C-b` reaches tmux itself), or `BTab`, or `F1`–`F12`.
`term.history` returns up to `lines` (at most 3000) lines of scrollback above the screen, oldest first, same format.
`alternate` is true while a full-screen program (Claude Code, vim, htop) draws in the terminal's alternate screen,
which has no scrollback: apps scroll such a program with its own keys (`PPage`/`NPage`) instead. A grant that ended answers `CONFLICT`, and a watching device gets `term.closed`.

Schemas: `term.watch`, `term.watch.result`, `term.keys`, `term.keys.result`, `term.stop`, `term.stop.result`, `term.history`, `term.history.result`,
`term.screen`, `term.closed`.

## 17. App updates

The apps update themselves from the bridge they're paired with, so no device needs a computer, a store or a public
download page. Each bridge has one **channel**: `beta` (the owner's devices, for testing) or `stable` (everyone else).
Versions, tags and file names follow the release convention in the deployment notes (SemVer, `X.Y.Z-beta.N`).

| Method | Direction | Params → result |
|---|---|---|
| `app.latest` | request | `{platform}` → `{channel, release?}` |
| `app.read` | request | `{platform, version_code, offset?}` → `{size, offset, data, eof}` |
| `app.available` | notification | `{platform, channel, release}` |

**A release** is `{version, version_code, size, sha256, published_at, notes?}`: `version` as `0.2.0` or `0.2.0-beta.1`,
`version_code` the Android version code (`MAJOR*1000000 + MINOR*10000 + PATCH*100 + N` for `beta.N`, `+ 99` for a stable
release), `size` and `sha256` (lowercase hex) of the signed installer, `published_at` in Unix seconds. `platform` is
`android` for now.

**Where releases come from.** A tag `vX.Y.Z[-beta.N]` makes CI build an unsigned installer and publish it with its
checksum as a GitHub Release (a pre-release for betas). On the server, a root-owned publisher fetches the newest release
for each channel, checks the checksum, signs it with the **release key**, which never leaves the server, and places it
where the bridge reads it. The bridge never holds the key. A beta release reaches the `beta` channel; a stable release
reaches both.

**Updating.** After `ready`, a device asks `app.latest` and compares `version_code` with its own. When a new release is
placed, every device gets `app.available`. To update, the device reads the installer with `app.read` in chunks of at most
512 KiB (`data` is base64, the next chunk starts at `offset` + decoded length), checks `size` and `sha256`, and hands it
to the system installer. Android itself refuses an installer that isn't signed with the same key as the installed app.

**Errors.** `NOT_FOUND` when the channel has no release for the platform; `CONFLICT` from `app.read` when `version_code`
is no longer the newest (the device asks `app.latest` again); `INVALID_PARAMS` for a bad platform or offset.

Schemas: `app.latest`, `app.latest.result`, `app.read`, `app.read.result`, `app.available`.

## 18. Hermes backend (the doorway)

Hermes has a second backend, `hermes serve`, which its desktop app uses: JSON-RPC over a WebSocket, with bots (Hermes
profiles), group chats (rooms), jobs, skills, helper agents and settings. The bridge connects to it **on the same
machine only** (agents.json: `serve_url`, e.g. `ws://127.0.0.1:9119/api/ws`, and `serve_key_file`, the token the
bridge and Hermes each keep in their own file) as one more client, and passes an **allowlisted** set of its calls
through to devices. Devices never reach Hermes themselves. Everything else (chat, Talk, Home) keeps using Hermes's
main API (§9–§14).

| Method | Kind | Params → result |
|---|---|---|
| `hermes.capabilities` | request | `{}` → `{connected, version?, methods, server_requests, open_requests}` |
| `hermes.call` | request | `{method, params?}` → `{result}` |
| `hermes.respond` | request | `{request_id, result}` → `{}` |
| `hermes.changed` | notification | same as `hermes.capabilities`'s result |
| `hermes.event` | notification | `{type, session_id?, payload?}` |
| `hermes.request` | notification | `{request_id, method, params}` |
| `hermes.request.done` | notification | `{request_id, reason}` |

**What a device may call.** `methods` lists the backend's methods a device may use **and** that this Hermes has: the
bridge checks each allowlisted method when it connects, with a call Hermes refuses before running anything (an
unknown parameter). A device shows a feature only when its methods are listed, so a Hermes update that drops one
hides that feature instead of breaking it (and §9 "Hermes compatibility" names it). `connected` is false while the
backend is down; the bridge reconnects by itself and sends `hermes.changed` whenever `connected` or `methods` change.

The allowlist covers reading (bots, rooms, chats, jobs, skills, helper agents, models) and talking (starting and
resuming a chat with a bot, sending, stopping, steering, rooms' send/stop/retry/approve). It never includes running
commands or shell, slash commands, settings or `.env` changes, the vault, passwords or secrets, connectors, billing,
or linking machines (`groups.peer.*`, `bot_relay.*`); settings changes come later through signed approvals as in §16.
Some calls are allowed with limits, and the bridge refuses (`INVALID_PARAMS`) params outside them: no parameter
starting with `_`; `prompt.submit` without any rewind (`truncate_*`, `confirm_*`); `cron.manage` only `action: list`;
`skills.manage` only `list` or `inspect`; a `model` only from the models the bridge's OpenRouter key may use
(§11). `model.options`'s result is filtered the same way.

**Calls.** `hermes.call` returns Hermes's `result` unchanged (except `model.options`). Errors: `METHOD_NOT_FOUND`
for a method not in `methods`; `AGENT_UNAVAILABLE` while not connected; `INVALID_PARAMS` as above; `BACKEND_ERROR`
(-32015) when Hermes answered with an error, with `data: {code, message}` from Hermes; `CONFLICT` when the result
would not fit in one frame (ask for less, e.g. a smaller `limit`).

**Events.** Every event Hermes streams on the bridge's connection (replies to chats a device started or resumed,
tool progress, room activity, `sessions.changed`, …) goes to every device as `hermes.event`, unchanged, except
`gateway.ready` and events too big for one frame. After `connected` turns true again, a device resumes the chats it
was showing (`session.resume`).

**Hermes's questions.** When Hermes asks its client something, the bridge passes on `approval` (`{choice}`: `once`,
`session`, `always` or `deny`) and `clarify` (`{answers}` keyed by question id, or `{}` to cancel) as
`hermes.request` to every device, and `hermes.respond` answers it from any one of them. Everything else Hermes may
ask (`sudo`, `secret`, `vault.*`, reading a terminal or window, …) the bridge declines at once, so the agent doesn't
wait. `hermes.request.done` tells every device that a question is closed (`answered`, `withdrawn` by Hermes,
or `disconnected`); `open_requests` lists the open ones for a device that connects later. `hermes.respond` for a
closed or unknown question is `NOT_FOUND`; a `result` of the wrong shape is `INVALID_PARAMS`.

Schemas: `hermes.capabilities`, `hermes.capabilities.result`, `hermes.call`, `hermes.call.result`, `hermes.respond`,
`hermes.respond.result`, `hermes.changed`, `hermes.event`, `hermes.request`, `hermes.request.done`.

### 18.1 Bots

A bot is a Hermes profile (Bot Mode in Hermes Desktop) with its own role, model, memory and skills. Each bot has
one permanent chat in Hermes, the session titled exactly `Bot Chat` in its profile, which Hermes Desktop opens too.
The bridge shows it as an ordinary conversation (§9) whose `agent_id` is `bot:<profile>`: chat, history, stop,
notes (`chat.steer`), approvals (`chat.approve`) and Talk work as they do for Hermes. Hermes's default profile is
Hermes itself, so it isn't listed as a bot.

| Method | Kind | Params → result |
|---|---|---|
| `bots.list` | request | `{}` → `{bots, available}` |
| `bots.open` | request | `{bot_id}` → `{conversation_id, title}` |
| `bots.avatar` | request | `{bot_id}` → `{found, mime?, data?}` |
| `bots.changed` | notification | `{bots}` |

A bot is `{id, name, profile, has_avatar, description?, role?, model?}`, listed by name; `available` is false
while the doorway is down. `bots.changed` goes to every device when the roster changes (the bridge looks every
minute and whenever the doorway connects). `bots.open` returns the bot's conversation, made the first time (and
Hermes's `Bot Chat` with it, if the bot never had one); it is the same conversation every time, on every device.
`chat.send` with `agent_id` a bot's id and no `conversation_id` writes into that conversation. `bots.avatar`
returns the bot's picture as base64 (`found: false` when it has none).

What the bridge never does to a bot's chat in Hermes: rename it (renaming changes only Talaria's title), delete it
(`conversations.delete` only takes it off Talaria's list; `bots.open` brings it back with its history from
Hermes), or pin a model to it (`conversations.set_model` is `INVALID_PARAMS`: a bot's model is its profile's).
Attachments to bots aren't supported yet (`MODALITY_UNSUPPORTED`). A `clarify` question in a bot's chat isn't
asked on devices: Hermes goes on without the answer.

Errors: `NOT_FOUND` for an unknown bot; `AGENT_UNAVAILABLE` when the doorway is down or Hermes can't open the
bot's chat; `METHOD_NOT_FOUND` when the bridge has no doorway.

Schemas: `bots.list`, `bots.list.result`, `bots.open`, `bots.open.result`, `bots.avatar`, `bots.avatar.result`,
`bots.changed`.

### 18.2 Group chats (rooms)

A group chat is a Hermes **room**: two to six bots (and the owner's own assistant, the default profile) in one
conversation. Hermes runs it on its backend, durably: a message from the owner starts up to three rounds of member
turns; each member speaks when it has something to add and passes otherwise; @mentions address members, `@all`
everyone; members ask the owner with `@user`. Rooms keep running with no device connected. The bridge watches them
(every couple of seconds for a room that is busy or open on a device, every half minute for the rest) and tells
devices what changed.

| Method | Kind | Params → result |
|---|---|---|
| `rooms.list` | request | `{}` → `{rooms, available}` |
| `rooms.open` | request | `{room_id, before?}` → `{room, messages, has_more, approvals}` |
| `rooms.send` | request | `{room_id, text, thread_id?}` → `{message}` |
| `rooms.stop` | request | `{room_id}` → `{stopped}` |
| `rooms.approve` | request | `{room_id, approval_id, choice}` → `{}` |
| `rooms.create` | request | `{name, members}` → `{room}` |
| `rooms.changed` | notification | `{rooms}` |
| `rooms.update` | notification | `{room_id, messages, working, approvals, needs_you}` |

A room is `{id, name, members, updated_at, working, needs_you, preview?}`; a member `{member_id, name, handle,
bot_id?}` (`bot_id` as in §18.1, absent for the owner's own assistant); `preview` `{speaker, text}` is the latest
message. A **message** is `{seq, at, kind, speaker, text, thread_id?, member_id?}` with `kind` `user` (the owner),
`member` (a member's reply) or `note` (a member couldn't answer, the room was stopped or renamed, a member wasn't
available), in `seq` order. `rooms.open` returns the newest messages (at most 60; `before` a `seq` for older ones)
and marks the room as watched by that device for 10 minutes; `rooms.update` carries messages a device hasn't had
yet, after `seq`s it has, with the room's state. `working` is true while members are taking turns.
`needs_you` is true while an approval waits or a member's latest message addresses `@user` after the owner's last
message. An **approval** is `{approval_id, member, command?, description?, choices}`; `choices` are `once` and
`deny`; `rooms.approve` answers it. `rooms.send` starts a new topic unless `thread_id` names one to reply in.
`rooms.stop` cancels the room's queued and running turns and holds its members until the owner addresses them
again. `rooms.create` takes a `name` and 2–6 `members` (bot ids from `bots.list`, or `"assistant"` for the owner's
own assistant) and returns the new room; disbanding a room is done in Hermes Desktop.

Errors: `NOT_FOUND` for an unknown room, member or approval (or one no longer pending); `INVALID_PARAMS` for a bad
name, member list or text; `AGENT_UNAVAILABLE` when the doorway is down; `METHOD_NOT_FOUND` without a doorway.

Schemas: `rooms.list`, `rooms.list.result`, `rooms.open`, `rooms.open.result`, `rooms.send`, `rooms.send.result`,
`rooms.stop`, `rooms.stop.result`, `rooms.approve`, `rooms.approve.result`, `rooms.create`, `rooms.create.result`,
`rooms.changed`, `rooms.update`.

### 18.3 Hermes's own commands

Hermes has its own `/` commands (`/help`, `/usage`, `/compress`, `/yolo`, `/kanban list`, skill commands like
`/weekly-review`, …). A device runs them in a conversation of the doorway's agent or a bot (§18.1): the bridge runs
them in that chat's Hermes session through the doorway. Talaria's own commands (§11) are handled by the device and
never reach this method.

| Method | Kind | Params → result |
|---|---|---|
| `commands.list` | request | `{conversation_id}` → `{commands, available}` |
| `commands.run` | request | `{conversation_id, text}` → `{status, command, output?, turn?, text?, request_id?}` |

A command is `{name, about, category, approve}`: `name` without the slash, `about` one line, `category` Hermes's
grouping (`Session`, `Info`, `Tools & Skills`, …, or `Skills`), and `approve` true when running it asks the owner
first. Commands that only make sense in a terminal or on the laptop (keys, logins, screens, clipboard, exit…) are
left out and refused. `available` is false while the doorway is down.

`commands.run` takes the whole line, `/name arg…`. `status` says what happened:

- `done`: it ran; `output` is Hermes's answer as plain text (monospace, may be long).
- `sent`: it became a message to the agent (a skill, `/plan`, …); `turn` is the turn started, as for `chat.send`
  (§9), whose user text is the command line.
- `prefill`: `text` should go into the composer for the owner to edit and send.
- `pending`: it changes Hermes's settings or state, so it waits for approval. The bridge sends
  `ops.approval.request` (§16) with `op` `hermes.command`, a `request_id` `hc-<16 hex>`, `params_json`
  `{"command": "/name arg", "conversation_id": "…"}` and tier 1; a device answers with `ops.approve`, signed the
  same way. The bridge checks the signature itself (not talaria-ops), then sends `ops.approval.done` and, once the
  command ran, `ops.result` with `op` `hermes.command` and the output. A pending command expires after 120 s.

Errors: `NOT_FOUND` for an unknown conversation or a command Hermes doesn't have; `INVALID_PARAMS` for a command
that can't run from a phone (the message says so); `AGENT_UNAVAILABLE` when the doorway is down or Hermes fails;
`METHOD_NOT_FOUND` without a doorway, or for a conversation of an agent the doorway doesn't serve.

Schemas: `commands.list`, `commands.list.result`, `commands.run`, `commands.run.result`.

### 18.4 The board (Kanban)

Hermes keeps a Kanban board of tasks; a task given to a bot (its `assignee`) and made `ready` is picked up and worked
by that bot on its own, which writes a summary when it's done. Hermes Desktop shows the same board. The bridge reads
and changes it through the doorway (the board's own addresses on `hermes serve`).

| Method | Kind | Params → result |
|---|---|---|
| `board.get` | request | `{}` → `{columns, available}` |
| `board.task` | request | `{task_id}` → `{task, comments}` |
| `board.add` | request | `{title, body?, assignee?, triage?}` → `{task, warning?}` |
| `board.update` | request | `{task_id, status?, assignee?, title?, body?, priority?}` → `{task}` |
| `board.comment` | request | `{task_id, text}` → `{}` |
| `board.changed` | notification | `{columns}` |

`columns` lists, in order, `triage`, `todo`, `scheduled`, `ready`, `running`, `blocked`, `review` and `done`, each
`{name, tasks}`. A **task** is `{id, title, status, priority, created_at, body?, assignee?, started_at?,
completed_at?, summary?, result?, error?, comments}`: `assignee` is a bot id (§18.1) or `"assistant"` for the owner's
own assistant, `summary` the worker's latest note, `error` its last failure, `comments` how many there are (on
`board.task`, `comments` is the list `{author, text, at}` instead). `board.update` moves a task (any status but
`running`, which only a worker sets; `archived` takes it off the board), gives it to someone (`assignee`, or `""` for
nobody), or edits it; Hermes refuses a move it doesn't allow (e.g. to `ready` while a task it depends on isn't done)
with `CONFLICT` and says why. `board.add` makes a task in `ready` when it has an `assignee` (so it starts), `todo`
otherwise, or `triage` with `triage: true`; `warning` says when nothing will pick it up yet. `board.get` marks the
board as watched by that device for 10 minutes: while it is, the bridge checks it every few seconds and sends
`board.changed` when it changed.

Errors: `NOT_FOUND` for an unknown task; `INVALID_PARAMS` for a bad field; `CONFLICT` as above;
`AGENT_UNAVAILABLE` when the doorway is down; `METHOD_NOT_FOUND` without a doorway.

### 18.5 Usage

| Method | Kind | Params → result |
|---|---|---|
| `usage.get` | request | `{days}` → `{days, total, by_day, by_model}` |

What Hermes spent over the last `days` (1–365), from its own records (every surface: chats, Talk's jobs, bots,
automations, Hermes Desktop). `total` and each row of `by_day` (`{day, …}`, oldest first, `day` as `YYYY-MM-DD`) and
`by_model` (`{model, …}`, costliest first) carry `cost_usd`, `estimated` (true when the cost is Hermes's estimate
rather than what the provider charged), `input_tokens`, `output_tokens`, `sessions` and `calls`.

Errors: `INVALID_PARAMS` for bad `days`; `AGENT_UNAVAILABLE` when the doorway is down; `METHOD_NOT_FOUND` without a
doorway.

Schemas: `board.get`, `board.get.result`, `board.task`, `board.task.result`, `board.add`, `board.add.result`,
`board.update`, `board.update.result`, `board.comment`, `board.comment.result`, `board.changed`, `usage.get`,
`usage.get.result`.
