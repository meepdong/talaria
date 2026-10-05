package io.github.meepdong.talaria.ui

import java.io.File

/** How a platform opens a file fetched from the server: the controller writes it to [target] as it downloads, then [open]s it. */
interface FileOpener {
    /** A new file named [name] to download into (a folder of its own, so two files with one name don't clash). */
    fun target(name: String): File

    /** Open [file] in the device's own app (which can also share it). */
    fun open(file: File, mime: String)
}
