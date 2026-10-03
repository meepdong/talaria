package io.github.meepdong.talaria.protocol

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

/** Order of the P-256 group; identifies the curve without relying on provider names. */
private val P256_ORDER = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)

private fun parse(text: String): ECPublicKey {
    val der = b64uDecode(text)
    val key = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    } catch (e: GeneralSecurityException) {
        throw EncodingException("invalid public key: ${e.message}")
    }
    if (key !is ECPublicKey || key.params.order != P256_ORDER || key.params.curve.field.fieldSize != 256) {
        throw EncodingException("public key must be ECDSA P-256")
    }
    if (!key.encoded.contentEquals(der)) throw EncodingException("non-canonical public key encoding")
    return key
}

internal actual fun p256CheckPublicKey(text: String): ByteArray = parse(text).encoded

internal actual fun p256Verify(publicKey: String, data: ByteArray, signature: String): Boolean = try {
    Signature.getInstance("SHA256withECDSA").run {
        initVerify(parse(publicKey))
        update(data)
        verify(b64uDecode(signature))
    }
} catch (e: EncodingException) {
    false
} catch (e: GeneralSecurityException) {
    false
}
