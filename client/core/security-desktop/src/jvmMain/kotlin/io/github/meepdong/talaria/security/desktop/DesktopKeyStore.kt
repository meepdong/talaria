package io.github.meepdong.talaria.security.desktop

import io.github.meepdong.talaria.security.DeviceKey
import io.github.meepdong.talaria.security.DeviceKeyStore
import io.github.meepdong.talaria.security.KeyProtection
import io.github.meepdong.talaria.security.SoftwareKey
import java.io.File

/** Somewhere to keep the exported key bytes, protected at rest. */
interface KeyBlobStore {
    val protection: KeyProtection
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun delete()
}

/** The desktop device key: a software P-256 key whose bytes live in a [KeyBlobStore]. */
class DesktopKeyStore(private val blobs: KeyBlobStore) : DeviceKeyStore {
    override val protection: KeyProtection get() = blobs.protection

    override fun load(): DeviceKey? = blobs.read()?.let { SoftwareKey.import(it) }

    override fun create(): DeviceKey = SoftwareKey.generate().also { blobs.write(it.export()) }

    override fun delete() = blobs.delete()

    companion object {
        /**
         * The right store for this computer:
         * - Windows: a file in %APPDATA%\Talaria encrypted with DPAPI.
         * - Linux: the Secret Service keyring through `secret-tool`. If there is no
         *   keyring, or a key was already saved as a file, a file only this user can read.
         */
        fun forThisComputer(
            dataDir: File = defaultDataDir(),
            os: String = System.getProperty("os.name"),
            secretTool: SecretToolStore = SecretToolStore(),
        ): DesktopKeyStore {
            val store = when {
                os.startsWith("Windows") -> DpapiFileStore(File(dataDir, "device-key.dpapi"))
                else -> {
                    val file = PlainFileStore(File(dataDir, "device-key.json"))
                    if (!file.exists() && secretTool.isAvailable()) secretTool else file
                }
            }
            return DesktopKeyStore(store)
        }

        /** %APPDATA%\Talaria on Windows, $XDG_CONFIG_HOME/talaria or ~/.config/talaria elsewhere. */
        fun defaultDataDir(env: Map<String, String> = System.getenv(), os: String = System.getProperty("os.name")): File =
            if (os.startsWith("Windows")) {
                File(env["APPDATA"] ?: File(System.getProperty("user.home"), "AppData/Roaming").path, "Talaria")
            } else {
                val config = env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }
                    ?: File(System.getProperty("user.home"), ".config").path
                File(config, "talaria")
            }
    }
}
