package io.github.meepdong.talaria.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoginItemTest {
    private val tmp: File = Files.createTempDirectory("talaria-login").toFile().apply { deleteOnExit() }

    @Test
    fun linuxAutostartEntry() {
        val item = LinuxAutostart(File(tmp, "autostart"), listOf("/opt/Talaria app/bin/Talaria", "--minimized"))
        assertNull(item.unavailableReason)
        assertFalse(item.isEnabled())
        item.setEnabled(true)
        assertTrue(item.isEnabled())
        val text = item.file.readText()
        assertTrue(text.startsWith("[Desktop Entry]\n"), text)
        assertTrue("Exec=\"/opt/Talaria app/bin/Talaria\" --minimized\n" in text, text)
        item.setEnabled(false)
        assertFalse(item.isEnabled())
        assertFalse(item.file.exists())
    }

    @Test
    fun linuxUsesXdgConfigHome() {
        val item = LoginItem.forThisComputer("Linux", mapOf("XDG_CONFIG_HOME" to tmp.path), "/opt/talaria/bin/Talaria")
        assertIs<LinuxAutostart>(item)
        assertEquals(File(tmp, "autostart/talaria.desktop"), item.file)
    }

    @Test
    fun windowsRunKey() {
        val key = FakeRunKey()
        val item = LoginItem.forThisComputer("Windows 11", emptyMap(), "C:\\Program Files\\Talaria\\Talaria.exe")
        assertIs<WindowsRunKey>(item)
        val fake = WindowsRunKey(listOf("C:\\Program Files\\Talaria\\Talaria.exe", "--minimized"), key)
        assertFalse(fake.isEnabled())
        fake.setEnabled(true)
        assertEquals("\"C:\\Program Files\\Talaria\\Talaria.exe\" --minimized", key.values["Talaria"])
        assertTrue(fake.isEnabled())
        fake.setEnabled(false)
        assertTrue(key.values.isEmpty())
    }

    @Test
    fun notAvailableWhenRunFromGradle() {
        val item = LoginItem.forThisComputer("Linux", mapOf("XDG_CONFIG_HOME" to tmp.path), appPath = null)
        assertNotNull(item.unavailableReason)
        assertFailsWith<IllegalStateException> { item.setEnabled(true) }
        item.setEnabled(false) // turning it off always works
        val win = WindowsRunKey(null, FakeRunKey())
        assertNotNull(win.unavailableReason)
    }

    @Test
    fun oneAppAtATime() {
        val file = File(tmp, "app.lock")
        val first = assertNotNull(AppLock.acquire(file))
        assertNull(AppLock.acquire(file))
        first.release()
        assertNotNull(AppLock.acquire(file)).release()
    }

    @Test
    fun deviceName() {
        assertEquals("MEEP-LAPTOP", defaultDeviceName(mapOf("COMPUTERNAME" to "MEEP-LAPTOP")))
        assertEquals("x".repeat(64), defaultDeviceName(mapOf("HOSTNAME" to "x".repeat(80))))
    }
}

private class FakeRunKey : RunKey {
    val values = mutableMapOf<String, String>()
    override fun read(name: String) = values[name]
    override fun write(name: String, value: String) {
        values[name] = value
    }
    override fun delete(name: String) {
        values.remove(name)
    }
}
