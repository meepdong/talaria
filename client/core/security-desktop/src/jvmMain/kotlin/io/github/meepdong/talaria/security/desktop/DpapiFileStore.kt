package io.github.meepdong.talaria.security.desktop

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.Win32Exception
import io.github.meepdong.talaria.security.KeyProtection
import io.github.meepdong.talaria.security.KeyStoreException
import java.io.File

/**
 * Windows: the key file is encrypted with DPAPI, so only this Windows user on this
 * computer can decrypt it. Copying the file elsewhere is useless.
 */
class DpapiFileStore(private val file: File) : KeyBlobStore {
    override val protection get() = KeyProtection.OS_ENCRYPTED

    override fun read(): ByteArray? = readFileOrNull(file)?.let {
        try {
            Crypt32Util.cryptUnprotectData(it, ENTROPY, CRYPTPROTECT_UI_FORBIDDEN, null)
        } catch (e: Win32Exception) {
            throw KeyStoreException("Windows couldn't decrypt the device key. Was it copied from another account?", e)
        }
    }

    override fun write(bytes: ByteArray) {
        val sealed = try {
            Crypt32Util.cryptProtectData(bytes, ENTROPY, CRYPTPROTECT_UI_FORBIDDEN, "Talaria device key", null)
        } catch (e: Win32Exception) {
            throw KeyStoreException("Windows couldn't encrypt the device key: ${e.message}", e)
        }
        writePrivateFile(file, sealed)
    }

    override fun delete() {
        file.delete()
    }

    private companion object {
        /** Ties the ciphertext to Talaria: other programs calling DPAPI as this user can't read it without it. */
        val ENTROPY = "talaria-device-key-v1".encodeToByteArray()
        const val CRYPTPROTECT_UI_FORBIDDEN = 0x1
    }
}
