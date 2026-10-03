package io.github.meepdong.talaria.security.desktop

import io.github.meepdong.talaria.security.KeyProtection
import io.github.meepdong.talaria.security.KeyStoreException
import io.github.meepdong.talaria.security.loadOrCreate
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopKeyStoreTest {
    @TempDir
    lateinit var dir: File

    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    private fun roundTrip(store: DesktopKeyStore) {
        assertNull(store.load())
        val key = store.loadOrCreate()
        assertEquals(key.publicKey, store.load()?.publicKey)
        store.delete()
        assertNull(store.load())
        assertNotEquals(key.publicKey, store.loadOrCreate().publicKey)
    }

    @Test
    fun plainFile() {
        val file = File(dir, "sub/device-key.json")
        roundTrip(DesktopKeyStore(PlainFileStore(file)))
        assertTrue(file.exists())
        if (!isWindows) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        }
        assertEquals(listOf("device-key.json"), file.parentFile.list()!!.toList(), "no temp files left behind")
    }

    @Test
    fun dpapiOnWindows() {
        assumeTrue(isWindows, "DPAPI exists only on Windows")
        val file = File(dir, "device-key.dpapi")
        val store = DesktopKeyStore(DpapiFileStore(file))
        roundTrip(store)
        val sealed = file.readBytes().decodeToString(throwOnInvalidSequence = false)
        assertFalse("private_pkcs8" in sealed, "the file must not contain the key in the clear")
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertFailsWith<KeyStoreException> { store.load() }
    }

    /** A pretend keyring that behaves like secret-tool. */
    private class FakeSecretTool(var available: Boolean = true) : CommandRunner {
        val items = mutableMapOf<List<String>, String>()
        val calls = mutableListOf<List<String>>()

        override fun run(command: List<String>, stdin: String?): CommandRunner.Result {
            calls += command
            assertEquals("secret-tool", command[0])
            if (!available) return CommandRunner.Result(1, "", "Cannot autolaunch D-Bus without X11 \$DISPLAY")
            return when (command[1]) {
                "lookup" -> items[command.drop(2)]?.let { CommandRunner.Result(0, it, "") }
                    ?: CommandRunner.Result(1, "", "")
                "store" -> {
                    assertTrue(command[2].startsWith("--label="))
                    items[command.drop(3)] = stdin!!
                    CommandRunner.Result(0, "", "")
                }
                "clear" -> {
                    items.remove(command.drop(2))
                    CommandRunner.Result(0, "", "")
                }
                else -> error("unexpected $command")
            }
        }
    }

    @Test
    fun keyringThroughSecretTool() {
        val tool = FakeSecretTool()
        val secretTool = SecretToolStore(tool)
        assertTrue(secretTool.isAvailable())
        roundTrip(DesktopKeyStore(secretTool))
        assertEquals(listOf("application", "talaria", "type", "device-key"), tool.items.keys.single())
    }

    @Test
    fun keyringErrorsAreReported() {
        val secretTool = SecretToolStore(FakeSecretTool(available = false))
        assertFalse(secretTool.isAvailable())
        assertFailsWith<KeyStoreException> { secretTool.read() }
        assertFalse(SecretToolStore { _, _ -> throw IOException("No such file") }.isAvailable())
    }

    @Test
    fun choosesTheStoreForEachComputer() {
        val keyring = SecretToolStore(FakeSecretTool())
        assertEquals(KeyProtection.OS_ENCRYPTED, DesktopKeyStore.forThisComputer(dir, "Windows 11", keyring).protection)
        assertEquals(KeyProtection.KEYRING, DesktopKeyStore.forThisComputer(dir, "Linux", keyring).protection)
        val noKeyring = SecretToolStore(FakeSecretTool(available = false))
        assertEquals(KeyProtection.FILE_ONLY, DesktopKeyStore.forThisComputer(dir, "Linux", noKeyring).protection)

        // A key already saved as a file stays there, even once a keyring appears, so the
        // device doesn't silently get a new identity and have to pair again.
        DesktopKeyStore.forThisComputer(dir, "Linux", noKeyring).create()
        assertEquals(KeyProtection.FILE_ONLY, DesktopKeyStore.forThisComputer(dir, "Linux", keyring).protection)
    }

    @Test
    fun dataDirectories() {
        assertEquals(File("C:\\Users\\m\\AppData\\Roaming", "Talaria"),
            DesktopKeyStore.defaultDataDir(mapOf("APPDATA" to "C:\\Users\\m\\AppData\\Roaming"), "Windows 11"))
        assertEquals(File("/home/m/.cfg/talaria"), DesktopKeyStore.defaultDataDir(mapOf("XDG_CONFIG_HOME" to "/home/m/.cfg"), "Linux"))
        assertIs<File>(DesktopKeyStore.defaultDataDir(emptyMap(), "Linux"))
    }
}
