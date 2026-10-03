package io.github.meepdong.talaria.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** What a pairing link or QR code carries (PROTOCOL §3.2, spec/README.md §3). */
data class PairingPayload(
    val url: String,
    val bridgeId: String,
    val bridgePk: String,
    val pairToken: String,
    val exp: Long,
    val tlsSpkiSha256: String? = null,
) {
    fun isExpired(nowSeconds: Long): Boolean = nowSeconds >= exp

    companion object {
        const val LINK_PREFIX = "talaria://pair#"

        fun fromLink(link: String): PairingPayload {
            val trimmed = link.trim()
            if (!trimmed.startsWith(LINK_PREFIX)) throw EncodingException("pairing links start with $LINK_PREFIX")
            val json = try {
                Json.parseToJsonElement(b64uDecode(trimmed.removePrefix(LINK_PREFIX)).decodeToString(throwOnInvalidSequence = true))
            } catch (e: SerializationException) {
                throw EncodingException("pairing link is corrupt: ${e.message}")
            } catch (e: CharacterCodingException) {
                throw EncodingException("pairing link is corrupt: ${e.message}")
            }
            return fromJson(json as? JsonObject ?: throw EncodingException("not a TNP v0 pairing payload"))
        }

        fun fromJson(data: JsonObject): PairingPayload {
            if ((data["tnp"] as? JsonPrimitive)?.let { !it.isString && it.content == "0" } != true) {
                throw EncodingException("not a TNP v0 pairing payload")
            }
            fun str(key: String): String {
                val v = data[key] ?: throw EncodingException("pairing payload is missing '$key'")
                return (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw EncodingException("pairing payload fields must be strings")
            }
            val exp = (data["exp"] ?: throw EncodingException("pairing payload is missing 'exp'"))
                .let { it as? JsonPrimitive }?.takeIf { !it.isString }?.longOrNull
                ?: throw EncodingException("exp must be an integer")
            val pin = data["tls_spki_sha256"]?.let {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                    ?: throw EncodingException("pairing payload fields must be strings")
            }
            return PairingPayload(str("url"), str("bridge_id"), str("bridge_pk"), str("pair_token"), exp, pin)
        }
    }
}

object ShortCode {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 8

    /** Accept what people type: any case, dashes or spaces, O for 0 and I/L for 1. */
    fun normalize(text: String): String {
        val cleaned = text.uppercase().replace("-", "").replace(" ", "")
            .replace('O', '0').replace('I', '1').replace('L', '1')
        if (cleaned.length != LENGTH || cleaned.any { it !in ALPHABET }) {
            throw EncodingException("short codes are 8 characters, like ABCD-1234")
        }
        return cleaned
    }

    fun format(code: String): String = "${code.take(4)}-${code.drop(4)}"
}

/**
 * spec/README.md §7: clients use wss:// everywhere and refuse plain ws:// to anything
 * but a loopback address.
 */
fun isAllowedBridgeUrl(url: String): Boolean {
    val match = Regex("^(wss?)://(\\[[^\\]]+\\]|[^/:?#]+)(:\\d+)?(/[^?#]*)?$").matchEntire(url) ?: return false
    val (scheme, host) = match.destructured
    if (scheme == "wss") return true
    val h = host.lowercase()
    return h == "localhost" || h == "[::1]" || Regex("^127(\\.\\d{1,3}){3}$").matches(h)
}
