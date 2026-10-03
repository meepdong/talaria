package io.github.meepdong.talaria.protocol

/** SHA-256 of [data]. */
expect fun sha256(data: ByteArray): ByteArray

internal expect fun p256CheckPublicKey(text: String): ByteArray

internal expect fun p256Verify(publicKey: String, data: ByteArray, signature: String): Boolean

/** ECDSA P-256 public keys in their wire form: base64url of the DER SubjectPublicKeyInfo. */
object P256 {
    /**
     * Parse a wire public key, accepting only canonical P-256 keys.
     * Returns the SPKI DER bytes, or throws [EncodingException].
     */
    fun checkPublicKey(text: String): ByteArray = p256CheckPublicKey(text)

    /** Verify an ECDSA P-256 / SHA-256 DER signature, given as base64url. Never throws. */
    fun verify(publicKey: String, data: ByteArray, signature: String): Boolean = p256Verify(publicKey, data, signature)
}

/**
 * Something that signs with a device key: ECDSA P-256 / SHA-256, DER signature.
 * The key itself stays in the platform key store (core/security).
 */
fun interface Signer {
    fun sign(data: ByteArray): ByteArray
}

/** bridge_id / device_id: base32(SHA-256(SPKI DER)), uppercase, unpadded, first 26 characters. */
fun keyId(publicKey: String): String = base32(sha256(P256.checkPublicKey(publicKey))).take(26)

/** Sign [data] and return the signature as base64url, the form every TNP message carries. */
fun Signer.signB64u(data: ByteArray): String = b64uEncode(sign(data))
