package io.github.meepdong.talaria.session

import io.github.meepdong.talaria.protocol.P256
import io.github.meepdong.talaria.protocol.PairingPayload
import io.github.meepdong.talaria.protocol.ProtocolException
import io.github.meepdong.talaria.protocol.Sas
import io.github.meepdong.talaria.protocol.Tnp
import io.github.meepdong.talaria.protocol.b64uEncode
import io.github.meepdong.talaria.protocol.keyId
import io.github.meepdong.talaria.protocol.signB64u
import io.github.meepdong.talaria.security.DeviceKey
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.SecureRandom
import kotlin.math.abs

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

internal fun randomB64u(bytes: Int = 16): String = b64uEncode(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

internal fun closedFailure(info: CloseInfo?): TnpException = when (info?.code) {
    Tnp.CLOSE_REVOKED -> TnpException(Failure.REVOKED)
    Tnp.CLOSE_BAD_SIGNATURE -> TnpException(Failure.AUTH_REFUSED, info.reason.ifBlank { null })
    Tnp.CLOSE_TIMEOUT -> TnpException(Failure.TIMEOUT, info.reason.ifBlank { null })
    else -> TnpException(Failure.CLOSED, info?.let { "${it.code ?: "no close code"} ${it.reason}".trim() })
}

/** Receive and decode one frame, failing on close or after [timeoutMs]. */
internal suspend fun WsConnection.receiveMessage(timeoutMs: Long): JsonObject {
    var closed = false
    val text = withTimeoutOrNull(timeoutMs) { receive().also { closed = it == null } }
        ?: if (closed) throw closedFailure(closeInfo) else throw TnpException(Failure.TIMEOUT)
    return try {
        Tnp.decode(text)
    } catch (e: ProtocolException) {
        throw TnpException(Failure.PROTOCOL, e.message)
    }
}

internal data class Hello(val bridgeId: String, val bridgePk: String, val nonceB: String)

/**
 * Check that the bridge proves the expected identity (PROTOCOL §3.3). With nothing
 * pinned (short-code pairing) the key is accepted here and protected by the SAS.
 */
internal suspend fun WsConnection.verifyHello(expectId: String?, expectPk: String?, nowS: Long): Hello {
    val hello = receiveMessage(15_000)
    val p = hello.obj("params")
    val bridgeId = p?.str("bridge_id")
    val bridgePk = p?.str("bridge_pk")
    val nonceB = p?.str("nonce_b")
    val ts = p?.long("ts")
    val sig = p?.str("sig_b")
    if (hello.str("method") != "hello" || bridgeId == null || bridgePk == null || nonceB == null || ts == null || sig == null) {
        throw TnpException(Failure.PROTOCOL, "the bridge did not start with a valid hello")
    }
    val actualId = runCatching { keyId(bridgePk) }.getOrNull()
        ?: throw TnpException(Failure.PROTOCOL, "the bridge sent an invalid key")
    if (actualId != bridgeId) throw TnpException(Failure.PROTOCOL, "the bridge's id does not match its key")
    if ((expectPk != null && bridgePk != expectPk) || (expectId != null && bridgeId != expectId)) {
        throw TnpException(Failure.IDENTITY_CHANGED)
    }
    if (!P256.verify(bridgePk, Tnp.helloSignedData(bridgeId, nonceB, ts), sig)) {
        throw TnpException(Failure.PROTOCOL, "the bridge's hello signature is invalid")
    }
    if (abs(nowS - ts) > Tnp.TS_WINDOW_S) throw TnpException(Failure.CLOCK)
    return Hello(bridgeId, bridgePk, nonceB)
}

/** How to reach a bridge for pairing: a link, or an address plus the short code. */
sealed interface PairingTarget {
    data class Link(val payload: PairingPayload) : PairingTarget
    data class Code(val url: String, val normalizedCode: String) : PairingTarget
}

/**
 * Pair this device (PROTOCOL §3.2). [onSas] is called with the code to show while the
 * operator compares it in the terminal; the call then waits for their decision.
 */
suspend fun pair(
    transport: Transport,
    target: PairingTarget,
    key: DeviceKey,
    name: String,
    platform: String,
    onSas: (Sas) -> Unit,
    nowS: () -> Long = { System.currentTimeMillis() / 1000 },
    decisionTimeoutMs: Long = 150_000,
): PairedBridge {
    val url: String
    val secret: String
    val pin: String?
    var expectId: String? = null
    var expectPk: String? = null
    when (target) {
        is PairingTarget.Link -> {
            if (target.payload.isExpired(nowS())) throw TnpException(Failure.LINK_EXPIRED)
            url = target.payload.url
            secret = target.payload.pairToken
            pin = target.payload.tlsSpkiSha256
            expectId = target.payload.bridgeId
            expectPk = target.payload.bridgePk
        }
        is PairingTarget.Code -> {
            url = target.url
            secret = target.normalizedCode
            pin = null
        }
    }

    val ws = transport.open(url, pin)
    val hello: Hello
    val reply: JsonObject
    try {
        hello = ws.verifyHello(expectId, expectPk, nowS())
        onSas(Sas.derive(hello.bridgePk, key.publicKey, secret))
        val ts = nowS()
        val signed = Tnp.pairSignedData(hello.bridgeId, hello.nonceB, key.publicKey, secret, name, platform, ts)
        ws.send(Tnp.encode(Tnp.notification("pair.request", buildJsonObject {
            put("device_pk", key.publicKey)
            put("name", name)
            put("platform", platform)
            put("ts", ts)
            put("sig_d", key.signB64u(signed))
            put(if (target is PairingTarget.Link) "pair_token" else "short_code", secret)
        })))
        reply = ws.receiveMessage(decisionTimeoutMs)
    } finally {
        ws.close(Tnp.CLOSE_NORMAL, "pairing done")
    }

    when (reply.str("method")) {
        "pair.accepted" -> {}
        "pair.rejected" -> throw TnpException(Failure.PAIR_REJECTED, reply.obj("params")?.str("reason"))
        else -> throw TnpException(Failure.PROTOCOL, "unexpected reply to pair.request")
    }
    val accepted = reply.obj("params")
    val deviceId = accepted?.str("device_id")
    if (deviceId != key.deviceId) throw TnpException(Failure.PROTOCOL, "the bridge registered a different device id")
    return PairedBridge(url, hello.bridgeId, hello.bridgePk, deviceId, accepted.str("name") ?: name, pin)
}

/** Result of the signed handshake: the session id the bridge assigned. */
internal data class Authenticated(val sessionId: String)

/** `auth`, then `capabilities.announce` until `ready` (PROTOCOL §3.3). */
internal suspend fun WsConnection.authenticate(
    bridge: PairedBridge, key: DeviceKey, platform: String, nowS: Long,
): Authenticated {
    val hello = verifyHello(bridge.bridgeId, bridge.bridgePk, nowS)
    val nonceD = randomB64u()
    val sig = key.signB64u(Tnp.authSignedData(bridge.bridgeId, bridge.deviceId, hello.nonceB, nonceD, nowS))
    send(Tnp.encode(Tnp.request("a1", "auth", buildJsonObject {
        put("device_id", bridge.deviceId)
        put("nonce_d", nonceD)
        put("ts", nowS)
        put("sig_d", sig)
        put("resume", false)
    })))
    val ok = receiveMessage(15_000)
    val sessionId = ok.obj("result")?.str("session_id")
    if (ok.str("id") != "a1" || sessionId == null) throw TnpException(Failure.PROTOCOL, "authentication failed")
    send(Tnp.encode(Tnp.notification("capabilities.announce", buildJsonObject {
        put("device", buildJsonObject {
            put("name", bridge.deviceName)
            put("platform", platform)
        })
        put("capabilities", buildJsonArray {})
        put("events", buildJsonArray {})
        put("relayed", buildJsonArray {})
    })))
    val ready = receiveMessage(15_000)
    if (ready.str("method") != "ready") throw TnpException(Failure.PROTOCOL, "the bridge did not send ready")
    return Authenticated(sessionId)
}

internal fun parseStatus(report: JsonObject): StatusReport? {
    val bridge = report.obj("bridge") ?: return null
    val agents = (report["agents"] as? JsonArray)?.mapNotNull { el ->
        val a = el as? JsonObject ?: return@mapNotNull null
        val id = a.str("id") ?: return@mapNotNull null
        AgentStatus(id, a.str("name") ?: id, a.str("state") ?: "unknown", a.str("model"), a.str("detail"))
    } ?: emptyList()
    return StatusReport(bridge.str("version") ?: "?", bridge.long("uptime_s") ?: 0, agents)
}
