# M0 — Bridge core and secure pairing

Working plan and status for the M0 milestone in
[ROADMAP.md](ROADMAP.md#m0--bridge-core-and-secure-pairing).
The protocol is described in [PROTOCOL.md](PROTOCOL.md); the exact bytes are pinned in
[../spec/README.md](../spec/README.md).

**Status: complete.** 50 tests pass, and the exit criterion was walked through with the real
`talaria` and `tnp-cli` commands on 2026-10-03.

## What M0 is for

M0 makes one thing trustworthy: a device and a bridge that have never met end up sharing a key,
and both sides can prove nothing was swapped in the middle. Everything after this — status,
chat, notifications — assumes that key is sound, so M0 is the only milestone where the
cryptography and the operator's confirmation step are the whole deliverable.

M0 ships no app. The only client is `tnp-cli`, which exists so the bridge can be exercised and
so the Android client in M1 has a reference to match.

## Deliverables

### `spec/` — the bytes, fixed first

| Item | Status | Where |
|---|---|---|
| JSON Schema (2020-12) per M0 message | done | [`spec/schemas/`](../spec/schemas/) — 11 schemas: `hello`, `pair.request`, `pair.accepted`, `pair.rejected`, `auth`, `auth.ok`, `capabilities.announce`, `ready`, `ping`, `ping.result`, `error` |
| Test vectors: ids, `frame()`, signatures, SAS, pairing links | done | [`spec/vectors/`](../spec/vectors/), rebuilt by [`generate.py`](../spec/vectors/generate.py) |
| 64-emoji SAS table | done | [`spec/sas-emoji.json`](../spec/sas-emoji.json) |
| Byte-format document | done | [`spec/README.md`](../spec/README.md) |

The schemas are not decoration: every message the test harness sends or receives is validated
against them through `check()` in [`conftest.py`](../bridge/tests/conftest.py), so a schema that
drifts from the code fails the suite.

### `bridge/` — the server

| Item | Status | Where |
|---|---|---|
| Protocol core: strict base64url, `frame()`, P-256 keys and ids, signing | done | [`protocol/encoding.py`](../bridge/src/talaria_bridge/protocol/encoding.py), [`protocol/keys.py`](../bridge/src/talaria_bridge/protocol/keys.py) |
| Message builders and signed-data layouts | done | [`protocol/messages.py`](../bridge/src/talaria_bridge/protocol/messages.py) |
| Pairing links and short codes | done | [`protocol/pairing.py`](../bridge/src/talaria_bridge/protocol/pairing.py) |
| SAS derivation (6 digits + 3 emoji) | done | [`protocol/sas.py`](../bridge/src/talaria_bridge/protocol/sas.py) |
| Device registry (SQLite): devices, pairing tokens, pending requests | done | [`registry.py`](../bridge/src/talaria_bridge/registry.py) |
| TNP endpoint: `hello`, pairing, signed `auth`, `capabilities.announce` / `ready`, heartbeats | done | [`server.py`](../bridge/src/talaria_bridge/server.py) |
| Operator side: create a token, wait, show the SAS, record the decision | done | [`operator.py`](../bridge/src/talaria_bridge/operator.py) |
| `talaria serve` (`--dev`, TLS, `--pin-cert`) | done | [`cli.py`](../bridge/src/talaria_bridge/cli.py) |
| `talaria pair`: QR in the terminal, `talaria://` link, short code, SAS prompt | done | [`cli.py`](../bridge/src/talaria_bridge/cli.py) |
| `talaria devices list` / `revoke` | done | [`cli.py`](../bridge/src/talaria_bridge/cli.py) |

Two details worth remembering, because they are easy to undo by accident:

- **A pairing token is single use from the moment it is claimed**, not from the moment the
  operator approves. `claim_pairing()` burns it inside a `BEGIN IMMEDIATE` transaction, so a
  rejected or timed-out pairing cannot be retried with the same link. A request that fails its
  *shape* or *signature* check is rejected before the claim and does not burn it.
- **A wrong short code counts against every code still open**, not just the one guessed. Five
  failures close the short-code path with `rate_limited` while the links keep working.

### `tools/tnp-cli/` — the reference client

| Item | Status | Where |
|---|---|---|
| Pair by pasted link, or by `--code` with `--url` | done | [`client.py`](../tools/tnp-cli/src/tnp_cli/client.py), [`cli.py`](../tools/tnp-cli/src/tnp_cli/cli.py) |
| `hello` verification: id matches key, signature, clock, pinned key | done | `_verify_hello()` in [`client.py`](../tools/tnp-cli/src/tnp_cli/client.py) |
| TLS pinning for self-signed bridges; plain `ws://` only to loopback | done | `_ssl_context()`, `_check_pin()` in [`client.py`](../tools/tnp-cli/src/tnp_cli/client.py) |
| `connect` (authenticate, announce, ping, stay up), `whoami` | done | [`cli.py`](../tools/tnp-cli/src/tnp_cli/cli.py) |

## Exit criteria

> Pair the CLI client by link; tests pass for bad signatures, replays, expired or reused tokens,
> revoked devices, and rejected SAS.

| Criterion | Covered by |
|---|---|
| Pair the CLI client by link | `test_pair_by_link_then_connect`, plus the by-hand walkthrough below |
| Bad signatures | `test_bad_signature_does_not_burn_the_token` (pairing), `test_bad_signature` (session) |
| Replays | `test_replayed_auth_on_a_new_connection` (stale `nonce_b`), `test_repeated_device_nonce` (reused `nonce_d`) |
| Expired or reused tokens | `test_expired_token`, `test_reused_token`, `test_rejected_token_cannot_be_retried`, `test_approval_timeout` |
| Revoked devices | `test_revoked_device_cannot_pair_again`, `test_revoked_device`, `test_revoking_closes_an_open_session`, `test_revoked_client_is_disconnected` |
| Rejected SAS | `test_rejected_sas`, `test_operator_rejects` |

Beyond the stated criteria the suite also covers the published vectors, strict base64url,
non-P-256 keys, stale timestamps on both messages, short-code rate limiting, malformed pairing
requests, the subprotocol and path requirements, heartbeat timeout and recovery, and TLS pinning
including a deliberately wrong pin.

```
.venv\Scripts\python -m pytest bridge tools/tnp-cli     # 50 passed
```

| Suite | Tests |
|---|---|
| [`bridge/tests/test_vectors.py`](../bridge/tests/test_vectors.py) | 12 |
| [`bridge/tests/test_pairing.py`](../bridge/tests/test_pairing.py) | 15 |
| [`bridge/tests/test_handshake.py`](../bridge/tests/test_handshake.py) | 14 |
| [`tools/tnp-cli/tests/test_end_to_end.py`](../tools/tnp-cli/tests/test_end_to_end.py) | 9 |

## Walked through by hand

The automated end-to-end test drives `pair()` and `confirm_request()` as library calls. That
leaves the actual commands — argument parsing, terminal output, two processes meeting through
SQLite — unproven, so M0 was also closed by running them. On 2026-10-03, against a throwaway
`TALARIA_HOME`:

1. `talaria serve --dev` — binds loopback, prints its bridge id.
2. `talaria pair --name "Test client"` — prints the link and the short code, then waits.
3. `tnp-cli pair "talaria://pair#…"` — both terminals printed the same `768 028 🦄📚🌲`
   (unicorn, books, tree); approving at the prompt paired the device.
4. `tnp-cli connect --once` — session opened, ping answered.
5. `talaria devices list` — one active device.
6. `talaria devices revoke OAUG5A` — accepted an id prefix.
7. `tnp-cli connect --once` — refused with "This device was revoked on the bridge", exit 1.

Worth repeating after any change to the CLI surface or the pairing flow, since nothing in the
test suite exercises the commands themselves.

## Fixed while closing M0

**The SAS emoji were lost whenever output was piped or logged.** Both CLIs called
`sys.stdout.reconfigure(errors="replace")` without setting an encoding. On Windows that leaves
stdout on the locale code page (cp1252 here), so `🦄📚🌲` reached the terminal as `???` — on
*both* sides, which reads as a match and quietly reduces the SAS to its six digits. Both CLIs
now reconfigure to UTF-8. On a real console this changes nothing, since Python already uses
UTF-8 there; it only fixes redirected output.

The emoji names in brackets, which both sides already printed, are what kept this from being
worse than it was.

## Not in M0, by design

- `resume`, sequence numbers and the outbox — the field is accepted and ignored; M7.
- MCP tools, events, chat proxy — M2 and later.
- Any Android code — M1 starts the KMP skeleton.
- `status.get` / `status` — M1, with the connection status screen that consumes it.

## Closed after M0, before M−1

Three repository-level gaps, none of which blocked M0 but all of which blocked M−1 posting the
repo publicly.

- **`LICENSE` added** — the canonical Apache-2.0 text at the repository root, with a `NOTICE`
  carrying the copyright line and the "not affiliated with Nous Research" disclaimer. The README
  no longer says "planned".
- **CI added** — [`.github/workflows/tests.yml`](../.github/workflows/tests.yml) runs
  `pytest bridge tools/tnp-cli` on every push and pull request, on Ubuntu (3.12 and 3.13) and on
  Windows (3.12). Windows is in the matrix on purpose: the SAS emoji bug
  described above was a console encoding bug that only exists there.

  There is deliberately no "regenerate the vectors and diff" job. ECDSA signatures are
  randomized, so [`generate.py`](../spec/vectors/generate.py) produces a different
  `signatures.json` every run; [`test_vectors.py`](../bridge/tests/test_vectors.py) verifies the
  *published* vectors against the code, which is the check that actually matters.

- **A `.venv/` was being tracked.** Back when this project lived in a subdirectory of a larger
  repository, the `.gitignore` covering `.venv/` sat in that subdirectory, so a virtual environment
  created one level up was committed: 861 of the 913 tracked files were pip's vendored wheels. The
  `.gitignore` now sits at the repository root and covers virtual environments anywhere in the
  tree, along with `bridge.db` and `bridge_key.pem` in case `TALARIA_HOME` is ever pointed inside
  the repo.
