# TNP v0 spec: byte formats, schemas and test vectors

[`docs/PROTOCOL.md`](../docs/PROTOCOL.md) describes the protocol. This folder pins down the exact bytes, so that the Python bridge, the Kotlin clients and any third-party client compute the same ids, signatures and SAS codes. When the two disagree, this folder wins, and PROTOCOL.md gets fixed.

Working agreement (ROADMAP): every protocol change updates `spec/` first, with schema and test vectors, then code.

| Path | Contents |
|---|---|
| `schemas/` | JSON Schema (2020-12) for each M0 message |
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
