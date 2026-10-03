package io.github.meepdong.talaria.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import io.github.meepdong.talaria.protocol.b64uEncode
import io.github.meepdong.talaria.security.DeviceKey
import io.github.meepdong.talaria.security.DeviceKeyStore
import io.github.meepdong.talaria.security.KeyProtection
import io.github.meepdong.talaria.security.KeyStoreException
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The device key in the Android Keystore: a non-exportable P-256 key, in StrongBox when
 * the phone has one and otherwise in the TEE. The private key never leaves the hardware.
 */
class AndroidDeviceKeyStore(private val alias: String = "talaria-device-key") : DeviceKeyStore {
    override val protection: KeyProtection get() = KeyProtection.HARDWARE

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun load(): DeviceKey? = guard {
        val entry = keyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry ?: return@guard null
        KeystoreKey(entry.privateKey, b64uEncode(entry.certificate.publicKey.encoded))
    }

    override fun create(): DeviceKey = guard {
        delete()
        try {
            generate(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
        load() ?: throw KeyStoreException("The new device key could not be read back")
    }

    override fun delete() = guard {
        val ks = keyStore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
            initialize(spec)
            generateKeyPair()
        }
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: GeneralSecurityException) {
        throw KeyStoreException("The Android Keystore failed: ${e.message}", e)
    } catch (e: java.io.IOException) {
        throw KeyStoreException("The Android Keystore failed: ${e.message}", e)
    }

    private class KeystoreKey(private val private: PrivateKey, override val publicKey: String) : DeviceKey {
        // SHA256withECDSA returns a DER signature, which is what TNP carries.
        override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
            initSign(private)
            update(data)
            sign()
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
