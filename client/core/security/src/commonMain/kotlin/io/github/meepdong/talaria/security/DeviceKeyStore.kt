package io.github.meepdong.talaria.security

import io.github.meepdong.talaria.protocol.Signer
import io.github.meepdong.talaria.protocol.keyId

/** This device's identity: an ECDSA P-256 key that signs `pair.request` and `auth`. */
interface DeviceKey : Signer {
    /** Wire form: base64url of the DER SubjectPublicKeyInfo. */
    val publicKey: String

    /** The device_id the bridge knows this device by. */
    val deviceId: String get() = keyId(publicKey)
}

/** How well the private key is protected, so the UI can be honest about it. */
enum class KeyProtection(val description: String) {
    /** Android Keystore: the key never leaves secure hardware. */
    HARDWARE("Stored in this device's secure hardware"),

    /** Windows DPAPI: encrypted with your Windows login. */
    OS_ENCRYPTED("Encrypted with your Windows login"),

    /** Linux Secret Service (GNOME Keyring, KWallet). */
    KEYRING("Stored in your system keyring"),

    /** No keyring available: a file only your user account can read. */
    FILE_ONLY("No system keyring found, so the key is in a file only your account can read"),
    ;

    /** True when the UI should show a warning. */
    val weak: Boolean get() = this == FILE_ONLY
}

/** Where the device key lives. One per platform; the device has exactly one key. */
interface DeviceKeyStore {
    val protection: KeyProtection

    /** The stored key, or null if this device has never paired. */
    fun load(): DeviceKey?

    /** Make a new key, replacing any existing one. */
    fun create(): DeviceKey

    /** Forget the key, for example after this device was revoked. */
    fun delete()
}

fun DeviceKeyStore.loadOrCreate(): DeviceKey = load() ?: create()

/** The key store could not read or write the key. The message is safe to show. */
class KeyStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)
