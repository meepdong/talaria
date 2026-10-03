package io.github.meepdong.talaria.security.desktop

import io.github.meepdong.talaria.protocol.b64uDecode
import io.github.meepdong.talaria.protocol.b64uEncode
import io.github.meepdong.talaria.security.KeyProtection
import io.github.meepdong.talaria.security.KeyStoreException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Runs a command with optional stdin. Replaceable in tests. */
fun interface CommandRunner {
    data class Result(val exit: Int, val stdout: String, val stderr: String)

    fun run(command: List<String>, stdin: String?): Result

    companion object {
        val SYSTEM = CommandRunner { command, stdin ->
            val process = ProcessBuilder(command).start()
            process.outputStream.use { out -> stdin?.let { out.write(it.encodeToByteArray()) } }
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IOException("${command.first()} did not finish")
            }
            Result(process.exitValue(), stdout, stderr)
        }
    }
}

/**
 * Linux: the key lives in the Secret Service keyring (GNOME Keyring, KWallet), through
 * the `secret-tool` command from libsecret-tools. No native library is loaded.
 */
class SecretToolStore(private val runner: CommandRunner = CommandRunner.SYSTEM) : KeyBlobStore {
    override val protection get() = KeyProtection.KEYRING

    private fun secretTool(vararg args: String, stdin: String? = null): CommandRunner.Result = try {
        runner.run(listOf("secret-tool", *args), stdin)
    } catch (e: IOException) {
        throw KeyStoreException("Couldn't run secret-tool: ${e.message}", e)
    }

    /**
     * True when `secret-tool` is installed and a keyring answers. A lookup for an item
     * that doesn't exist exits 1 with nothing on stderr; a missing keyring prints an error.
     */
    fun isAvailable(): Boolean = try {
        val r = runner.run(listOf("secret-tool", "lookup", *PROBE), null)
        r.exit == 0 || (r.exit == 1 && r.stderr.isBlank())
    } catch (e: IOException) {
        false
    }

    override fun read(): ByteArray? {
        val r = secretTool("lookup", *ATTRIBUTES)
        return when {
            r.exit == 0 && r.stdout.isNotBlank() -> try {
                b64uDecode(r.stdout.trim())
            } catch (e: IllegalArgumentException) {
                throw KeyStoreException("The device key in the keyring is damaged", e)
            }
            r.exit == 1 && r.stderr.isBlank() -> null
            else -> throw KeyStoreException("The keyring didn't answer: ${r.stderr.trim()}")
        }
    }

    override fun write(bytes: ByteArray) {
        val r = secretTool("store", "--label=Talaria device key", *ATTRIBUTES, stdin = b64uEncode(bytes))
        if (r.exit != 0) throw KeyStoreException("Couldn't save the device key in the keyring: ${r.stderr.trim()}")
    }

    override fun delete() {
        secretTool("clear", *ATTRIBUTES)
    }

    private companion object {
        val ATTRIBUTES = arrayOf("application", "talaria", "type", "device-key")
        val PROBE = arrayOf("application", "talaria", "type", "probe")
    }
}
