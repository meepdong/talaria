"""JSON-RPC 2.0 framing with the TNP marker, plus the M0 signed-data builders."""

from __future__ import annotations

import json
from typing import Any

from .encoding import frame

SUBPROTOCOL = "tnp.v0"
MAX_FRAME = 1024 * 1024
TS_WINDOW_S = 120

HELLO_LABEL = "tnp0-hello"
AUTH_LABEL = "tnp0-auth"
PAIR_LABEL = "tnp0-pair"
OPS_APPROVE_LABEL = "tnp0-ops-approve"

# WebSocket close codes (PROTOCOL §3.3, spec/README.md §6)
CLOSE_BAD_SIGNATURE = 4401
CLOSE_REVOKED = 4403
CLOSE_TIMEOUT = 4408
CLOSE_PROTOCOL = 1002

# JSON-RPC error codes (PROTOCOL §13)
INVALID_REQUEST = -32600
METHOD_NOT_FOUND = -32601
INVALID_PARAMS = -32602
INTERNAL_ERROR = -32603
NOT_AUTHENTICATED = -32001
AGENT_UNAVAILABLE = -32010
MODALITY_UNSUPPORTED = -32012
CONFLICT = -32013
NOT_FOUND = -32014
BACKEND_ERROR = -32015  # Hermes's backend refused a call (§18)

PAIR_REJECT_REASONS = (
    "invalid_request",
    "unknown_token",
    "expired",
    "already_used",
    "revoked",
    "bad_signature",
    "rate_limited",
    "sas_rejected",
    "timeout",
)


class ProtocolError(ValueError):
    """A frame that is not valid TNP."""


def _base(**fields: Any) -> dict:
    return {"jsonrpc": "2.0", "tnp": 0, **fields}


def notification(method: str, params: dict | None = None) -> dict:
    msg = _base(method=method)
    if params is not None:
        msg["params"] = params
    return msg


def request(id: str, method: str, params: dict | None = None) -> dict:
    msg = _base(id=id, method=method)
    if params is not None:
        msg["params"] = params
    return msg


def result(id: str | int | None, value: dict) -> dict:
    return _base(id=id, result=value)


def error(id: str | int | None, code: int, message: str, data: Any = None) -> dict:
    err: dict[str, Any] = {"code": code, "message": message}
    if data is not None:
        err["data"] = data
    return _base(id=id, error=err)


def encode(msg: dict) -> str:
    return json.dumps(msg, ensure_ascii=False, separators=(",", ":"))


def decode(text: str | bytes) -> dict:
    if isinstance(text, bytes):
        raise ProtocolError("TNP uses text frames only")
    try:
        msg = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ProtocolError(f"invalid JSON: {exc}") from None
    if not isinstance(msg, dict) or msg.get("jsonrpc") != "2.0" or msg.get("tnp") != 0:
        raise ProtocolError("not a TNP v0 JSON-RPC message")
    if "params" in msg and not isinstance(msg["params"], dict):
        raise ProtocolError("params must be an object")
    return msg


def hello_signed_data(bridge_id: str, nonce_b: str, ts: int) -> bytes:
    return frame(HELLO_LABEL, bridge_id, nonce_b, str(ts))


def auth_signed_data(bridge_id: str, device_id: str, nonce_b: str, nonce_d: str, ts: int) -> bytes:
    return frame(AUTH_LABEL, bridge_id, device_id, nonce_b, nonce_d, str(ts))


def pair_signed_data(
    bridge_id: str, nonce_b: str, device_pk: str, pairing_secret: str,
    name: str, platform: str, ts: int,
) -> bytes:
    """Proof that the pairing device holds the private key for device_pk."""
    return frame(PAIR_LABEL, bridge_id, nonce_b, device_pk, pairing_secret, name, platform, str(ts))


def ops_approve_signed_data(request_id: str, device_id: str, op: str, params_json: str, choice: str) -> bytes:
    """A device's answer to a server operation (§10.8). [params_json] is the exact string the bridge sent."""
    return frame(OPS_APPROVE_LABEL, request_id, device_id, op, params_json, choice)
