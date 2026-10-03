@file:OptIn(ExperimentalEncodingApi::class)

package io.github.meepdong.talaria.protocol

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** A value on the wire is not in its canonical encoding (spec/README.md §1). */
class EncodingException(message: String) : IllegalArgumentException(message)

private val b64u = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/** base64url without padding. */
fun b64uEncode(data: ByteArray): String = b64u.encode(data)

/** Strict base64url decode: no padding, no stray characters, canonical form only. */
fun b64uDecode(text: String): ByteArray {
    if ('=' in text) throw EncodingException("expected unpadded base64url text")
    val data = try {
        b64u.decode(text)
    } catch (e: IllegalArgumentException) {
        throw EncodingException("invalid base64url: ${e.message}")
    }
    if (b64uEncode(data) != text) throw EncodingException("non-canonical base64url")
    return data
}

/**
 * Unambiguous concatenation, written ‖ in PROTOCOL.md: each field is a 4-byte
 * big-endian length followed by its UTF-8 bytes.
 */
fun frame(vararg fields: String): ByteArray {
    val parts = fields.map { it.encodeToByteArray() }
    val out = ByteArray(parts.sumOf { 4 + it.size })
    var i = 0
    for (part in parts) {
        val n = part.size
        out[i++] = (n ushr 24).toByte()
        out[i++] = (n ushr 16).toByte()
        out[i++] = (n ushr 8).toByte()
        out[i++] = n.toByte()
        part.copyInto(out, i)
        i += n
    }
    return out
}

private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

/** RFC 4648 base32, uppercase, unpadded. */
internal fun base32(data: ByteArray): String {
    val sb = StringBuilder((data.size * 8 + 4) / 5)
    var buffer = 0
    var bits = 0
    for (b in data) {
        buffer = (buffer shl 8) or (b.toInt() and 0xff)
        bits += 8
        while (bits >= 5) {
            sb.append(BASE32[(buffer shr (bits - 5)) and 31])
            bits -= 5
        }
    }
    if (bits > 0) sb.append(BASE32[(buffer shl (5 - bits)) and 31])
    return sb.toString()
}
