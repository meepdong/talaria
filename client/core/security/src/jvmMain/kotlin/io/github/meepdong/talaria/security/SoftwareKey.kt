package io.github.meepdong.talaria.security

import io.github.meepdong.talaria.protocol.P256
import io.github.meepdong.talaria.protocol.b64uDecode
import io.github.meepdong.talaria.protocol.b64uEncode
import io.github.meepdong.talaria.protocol.frame
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/**
 * A P-256 key held in memory, for the desktop key stores (which protect its bytes at
 * rest) and for tests. On Android the key stays in the Keystore instead.
 */
class SoftwareKey private constructor(private val private: PrivateKey, override val publicKey: String) : DeviceKey {
    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(private)
        update(data)
        sign()
    }

    /** The bytes a key store protects: a small versioned JSON document. */
    fun export(): ByteArray =
        """{"v":1,"private_pkcs8":"${b64uEncode(private.encoded)}","public_key":"$publicKey"}""".encodeToByteArray()

    companion object {
        fun generate(): SoftwareKey {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
            return SoftwareKey(pair.private, b64uEncode(pair.public.encoded))
        }

        private val FIELD = Regex("\"(\\w+)\":(\"[^\"]*\"|\\d+)")

        /** Read [export] output back, checking that the private and public halves belong together. */
        fun import(bytes: ByteArray): SoftwareKey {
            val fields = FIELD.findAll(bytes.decodeToString()).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
            if (fields["v"] != "1") throw KeyStoreException("The stored device key has an unknown format")
            val key = try {
                val private = KeyFactory.getInstance("EC")
                    .generatePrivate(PKCS8EncodedKeySpec(b64uDecode(fields["private_pkcs8"].orEmpty())))
                if (private !is ECPrivateKey) throw KeyStoreException("The stored device key is not an EC key")
                SoftwareKey(private, fields["public_key"].orEmpty())
            } catch (e: GeneralSecurityException) {
                throw KeyStoreException("The stored device key is damaged", e)
            } catch (e: IllegalArgumentException) {
                throw KeyStoreException("The stored device key is damaged", e)
            }
            val probe = frame("talaria-key-check", key.publicKey)
            if (!P256.verify(key.publicKey, probe, b64uEncode(key.sign(probe)))) {
                throw KeyStoreException("The stored device key is damaged: its two halves don't match")
            }
            return key
        }
    }
}

/** Keeps the key in memory only. For tests and previews. */
class InMemoryKeyStore : DeviceKeyStore {
    private var key: SoftwareKey? = null
    override val protection get() = KeyProtection.FILE_ONLY
    override fun load(): DeviceKey? = key
    override fun create(): DeviceKey = SoftwareKey.generate().also { key = it }
    override fun delete() {
        key = null
    }
}
