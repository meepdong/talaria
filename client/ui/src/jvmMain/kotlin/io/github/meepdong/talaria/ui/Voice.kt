package io.github.meepdong.talaria.ui

/** Speech-to-text on this platform: Android's recogniser, or Vosk on the desktop. */
interface SpeechInput {
    /** Start listening. Calls back with what it has heard so far, then once with the end result. */
    fun start(listener: Listener)

    /** Stop listening and deliver what was heard. */
    fun stop()

    interface Listener {
        fun partial(text: String)
        fun done(text: String)
        fun failed(message: String)
    }
}

/** Reads text aloud with the platform's voices. */
interface SpeechOutput {
    /** Stops anything already speaking first. [onDone] runs when it finishes or is stopped. */
    fun speak(text: String, onDone: () -> Unit)
    fun stop()
}

/** Small on/off settings that outlive the app, such as "read replies aloud". */
interface Prefs {
    fun get(key: String, default: Boolean): Boolean
    fun set(key: String, value: Boolean)

    class Memory : Prefs {
        private val values = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        override fun get(key: String, default: Boolean) = values[key] ?: default
        override fun set(key: String, value: Boolean) { values[key] = value }
    }

    /** A properties file, for the desktop. */
    class FileBacked(private val file: java.io.File) : Prefs {
        private val props = java.util.Properties().apply {
            runCatching { file.inputStream().use { load(it) } }
        }

        @Synchronized override fun get(key: String, default: Boolean) = props.getProperty(key)?.toBoolean() ?: default

        @Synchronized override fun set(key: String, value: Boolean) {
            props.setProperty(key, value.toString())
            runCatching {
                file.parentFile?.mkdirs()
                file.outputStream().use { props.store(it, "Talaria settings") }
            }
        }
    }
}

private val CODE_BLOCK = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)
private val LINK = Regex("""!?\[([^\]]*)]\([^)]*\)""")
private val URL = Regex("""https?://\S+""")
private val MARKS = Regex("""(^|\n)\s{0,3}(#{1,6}\s+|>\s?|[-*+]\s+|\d+[.)]\s+)""")
private val EMPHASIS = Regex("""[*_~`]+""")
private val TABLE_RULE = Regex("""(?m)^\s*\|?\s*:?-{3,}.*$""")

/** What to say for a Markdown reply: no code blocks, links or formatting marks, table cells as phrases. */
fun speakable(markdown: String, maxChars: Int = 6000): String {
    var t = CODE_BLOCK.replace(markdown, " (code) ")
    t = LINK.replace(t) { it.groupValues[1] }
    t = URL.replace(t, "a link")
    t = TABLE_RULE.replace(t, "")
    t = MARKS.replace(t) { it.groupValues[1] }
    t = EMPHASIS.replace(t, "")
    t = t.replace('|', ',')
    t = t.lines().map { it.trim().trim(',').trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    return if (t.length <= maxChars) t else t.take(maxChars).substringBeforeLast(' ') + "… The rest is on screen."
}
