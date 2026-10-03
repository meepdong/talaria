package io.github.meepdong.talaria.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

/** Start at login: off by default, and only the person turns it on. */
interface LoginItem {
    /** Null when it can be turned on; otherwise why not, to show next to the switch. */
    val unavailableReason: String?
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)

    companion object {
        const val NAME = "Talaria"

        /** Passed by the login entry, so the app starts in the tray without a window. */
        const val MINIMIZED_FLAG = "--minimized"

        /**
         * The right kind for this computer. [appPath] is the packaged launcher, which
         * jpackage (createDistributable) reports in `jpackage.app-path`; when running
         * from Gradle there is none, so there's nothing stable to start at login.
         */
        fun forThisComputer(
            os: String = System.getProperty("os.name"),
            env: Map<String, String> = System.getenv(),
            appPath: String? = System.getProperty("jpackage.app-path"),
        ): LoginItem {
            val command = appPath?.takeIf { it.isNotBlank() }?.let { listOf(it, MINIMIZED_FLAG) }
            return if (os.startsWith("Windows")) {
                WindowsRunKey(command, JnaRunKey)
            } else {
                val config = env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }
                    ?: File(System.getProperty("user.home"), ".config").path
                LinuxAutostart(File(config, "autostart"), command)
            }
        }

        internal const val NOT_PACKAGED =
            "Available in the packaged app (gradlew :desktopApp:createDistributable), not when run from Gradle"
    }
}

/** The values under HKCU\Software\Microsoft\Windows\CurrentVersion\Run. */
interface RunKey {
    fun read(name: String): String?
    fun write(name: String, value: String)
    fun delete(name: String)
}

object JnaRunKey : RunKey {
    private const val PATH = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private val HKCU = WinReg.HKEY_CURRENT_USER

    override fun read(name: String): String? =
        if (Advapi32Util.registryValueExists(HKCU, PATH, name)) Advapi32Util.registryGetStringValue(HKCU, PATH, name) else null

    override fun write(name: String, value: String) = Advapi32Util.registrySetStringValue(HKCU, PATH, name, value)

    override fun delete(name: String) {
        if (Advapi32Util.registryValueExists(HKCU, PATH, name)) Advapi32Util.registryDeleteValue(HKCU, PATH, name)
    }
}

/** Windows: a value in the current user's Run key, so no admin rights are needed. */
class WindowsRunKey(private val command: List<String>?, private val key: RunKey) : LoginItem {
    override val unavailableReason: String? get() = if (command == null) LoginItem.NOT_PACKAGED else null

    override fun isEnabled(): Boolean = runCatching { key.read(LoginItem.NAME) }.getOrNull() != null

    override fun setEnabled(enabled: Boolean) {
        if (!enabled) return key.delete(LoginItem.NAME)
        val cmd = checkNotNull(command) { LoginItem.NOT_PACKAGED }
        key.write(LoginItem.NAME, commandLine(cmd))
    }

    companion object {
        /** `"C:\…\Talaria.exe" --minimized`: the path quoted, since it usually has spaces. */
        fun commandLine(cmd: List<String>): String =
            cmd.joinToString(" ") { if (it.any(Char::isWhitespace)) "\"$it\"" else it }
    }
}

/** Linux: a desktop entry in ~/.config/autostart (the XDG autostart spec). */
class LinuxAutostart(private val dir: File, private val command: List<String>?) : LoginItem {
    val file: File get() = File(dir, "talaria.desktop")

    override val unavailableReason: String? get() = if (command == null) LoginItem.NOT_PACKAGED else null

    override fun isEnabled(): Boolean = file.exists()

    override fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            file.delete()
            return
        }
        val cmd = checkNotNull(command) { LoginItem.NOT_PACKAGED }
        dir.mkdirs()
        file.writeText(desktopEntry(cmd))
    }

    companion object {
        fun desktopEntry(cmd: List<String>): String = """
            [Desktop Entry]
            Type=Application
            Name=Talaria
            Comment=Keeps Talaria connected to your server
            Exec=${cmd.joinToString(" ") { quote(it) }}
            Terminal=false
            X-GNOME-Autostart-enabled=true
        """.trimIndent() + "\n"

        /** Exec quoting from the desktop entry spec: double quotes, with " ` $ \ escaped. */
        private fun quote(arg: String): String {
            if (arg.all { it.isLetterOrDigit() || it in "-_./=:" }) return arg
            val escaped = arg.replace("\\", "\\\\\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$")
            return "\"$escaped\""
        }
    }
}
