package io.github.meepdong.talaria.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A frame that is not valid TNP. */
class ProtocolException(message: String) : IllegalArgumentException(message)

/** JSON-RPC 2.0 framing with the TNP marker, plus the signed-data builders (PROTOCOL §3). */
object Tnp {
    const val SUBPROTOCOL = "tnp.v0"
    const val MAX_FRAME = 1024 * 1024
    const val TS_WINDOW_S = 120

    const val HELLO_LABEL = "tnp0-hello"
    const val AUTH_LABEL = "tnp0-auth"
    const val PAIR_LABEL = "tnp0-pair"

    // WebSocket close codes (spec/README.md §6)
    const val CLOSE_NORMAL = 1000
    const val CLOSE_PROTOCOL = 1002
    const val CLOSE_BAD_SIGNATURE = 4401
    const val CLOSE_REVOKED = 4403
    const val CLOSE_TIMEOUT = 4408

    // JSON-RPC error codes (PROTOCOL §13)
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val NOT_AUTHENTICATED = -32001

    private fun base(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("tnp", 0)
        build()
    }

    fun notification(method: String, params: JsonObject? = null): JsonObject = base {
        put("method", method)
        if (params != null) put("params", params)
    }

    fun request(id: String, method: String, params: JsonObject? = null): JsonObject = base {
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }

    fun result(id: JsonElement, value: JsonObject): JsonObject = base {
        put("id", id)
        put("result", value)
    }

    fun encode(msg: JsonObject): String = msg.toString()

    /** Parse one text frame, checking the JSON-RPC and TNP markers. */
    fun decode(text: String): JsonObject {
        val msg = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw ProtocolException("invalid JSON: ${e.message}")
        }
        if (msg !is JsonObject ||
            (msg["jsonrpc"] as? JsonPrimitive)?.let { it.isString && it.content == "2.0" } != true ||
            (msg["tnp"] as? JsonPrimitive)?.let { !it.isString && it.content == "0" } != true
        ) {
            throw ProtocolException("not a TNP v0 JSON-RPC message")
        }
        if ("params" in msg && msg["params"] !is JsonObject) throw ProtocolException("params must be an object")
        return msg
    }

    fun helloSignedData(bridgeId: String, nonceB: String, ts: Long): ByteArray =
        frame(HELLO_LABEL, bridgeId, nonceB, ts.toString())

    fun authSignedData(bridgeId: String, deviceId: String, nonceB: String, nonceD: String, ts: Long): ByteArray =
        frame(AUTH_LABEL, bridgeId, deviceId, nonceB, nonceD, ts.toString())

    /** Proof that the pairing device holds the private key for [devicePk]. */
    fun pairSignedData(
        bridgeId: String, nonceB: String, devicePk: String, pairingSecret: String,
        name: String, platform: String, ts: Long,
    ): ByteArray = frame(PAIR_LABEL, bridgeId, nonceB, devicePk, pairingSecret, name, platform, ts.toString())
}
