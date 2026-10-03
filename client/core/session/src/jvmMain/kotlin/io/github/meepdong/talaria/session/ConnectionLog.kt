package io.github.meepdong.talaria.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Connects, drops and their reasons, so the week-long exit test can be judged. */
class ConnectionLog(private val sink: ((Entry) -> Unit)? = null, private val keep: Int = 500) {
    data class Entry(val atMs: Long, val event: String, val detail: String? = null) {
        fun line(): String = "${FORMAT.format(Instant.ofEpochMilli(atMs))}  $event${detail?.let { "  ($it)" } ?: ""}"
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun add(atMs: Long, event: String, detail: String? = null) {
        val entry = Entry(atMs, event, detail)
        _entries.update { (it + entry).takeLast(keep) }
        runCatching { sink?.invoke(entry) }
    }

    companion object {
        private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

        /** Appends each entry to [file]; when it passes [maxBytes] the old half is dropped to [file].1. */
        fun fileSink(file: File, maxBytes: Long = 1_000_000): (Entry) -> Unit = { entry ->
            file.parentFile?.mkdirs()
            if (file.length() > maxBytes) {
                val old = File(file.path + ".1")
                old.delete()
                file.renameTo(old)
            }
            file.appendText(entry.line() + System.lineSeparator())
        }
    }
}
