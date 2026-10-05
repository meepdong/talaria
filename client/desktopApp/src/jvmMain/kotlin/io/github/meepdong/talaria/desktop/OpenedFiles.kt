package io.github.meepdong.talaria.desktop

import io.github.meepdong.talaria.ui.FileOpener
import java.awt.Desktop
import java.io.File

/**
 * Opens a file fetched from the server in this computer's own viewer: a copy in
 * `opened/`, emptied when Talaria starts. Program files are refused, since opening
 * one would run it.
 */
class OpenedFiles(private val dir: File, private val windows: Boolean) : FileOpener {
    init {
        dir.deleteRecursively()
    }

    override fun target(name: String): File {
        val safe = safeName(name)
        require(!runs(safe)) { "Talaria doesn't open program files. Ask Hermes about it instead" }
        // a folder per open, so two files with the same name don't overwrite each other
        return File(File(dir, System.nanoTime().toString()).apply { mkdirs() }, safe)
    }

    override fun open(file: File, mime: String) {
        val target = file
        target.setReadOnly()
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(target)
        } else if (!windows) {
            ProcessBuilder("xdg-open", target.absolutePath).inheritIO().start()
        } else {
            error("This computer has no viewer Talaria can start")
        }
    }

    companion object {
        private val PROGRAMS = setOf(
            "exe", "bat", "cmd", "com", "msi", "msp", "ps1", "psm1", "vbs", "vbe", "js", "jse", "wsf", "wsh",
            "scr", "lnk", "hta", "cpl", "reg", "jar", "url", "pif", "appref-ms", "sh", "bash", "run",
            "desktop", "appimage", "py", "pl",
        )

        fun runs(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in PROGRAMS

        fun safeName(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f<>:\"|?*]"), "_")
                .trim(' ', '.').take(120).ifEmpty { "file" }
    }
}
