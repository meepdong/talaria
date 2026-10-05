package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.chat.ToolStep

/**
 * Talk's voice for one reply (Talk 2): speaks it sentence by sentence while it streams in rather than after it ends,
 * says in a few words what Hermes is doing when it starts a tool, and calls [onFinished] once everything is said
 * and the reply is over. Sentences that arrive while it speaks go out together next, so there are few gaps.
 * Speech callbacks come from the platform's own thread, hence the lock.
 */
class TalkLoop(private val out: SpeechOutput, private val onSpeak: () -> Unit = {}, private val onFinished: () -> Unit) {
    private val lock = Any()
    private var consumed = 0
    private val waiting = StringBuilder()
    private var speaking = false
    private var replyOver = false
    private var finished = false
    private var stopped = false
    private val cued = mutableSetOf<String>()
    private var toolsSeen = 0

    /** The reply so far: its text, its tool steps, and whether it has ended. */
    fun update(text: String, tools: List<ToolStep>, over: Boolean) {
        val next = synchronized(lock) {
            if (stopped || finished) return
            // a tool starting while nothing is waiting to be said: a short cue, once per kind of tool
            tools.drop(toolsSeen).forEach { step ->
                val cue = cueFor(step.name)
                if (step.state == "started" && waiting.isEmpty() && !speaking && cued.add(cue)) waiting.append(cue).append(' ')
            }
            toolsSeen = tools.size
            val (ready, upTo) = completeSentences(text, consumed, over)
            consumed = upTo
            if (ready.isNotBlank()) waiting.append(ready).append(' ')
            replyOver = over
            takeNext()
        }
        next?.let(::say) ?: maybeFinish()
    }

    /** Stop talking now (the owner interrupted, or Talk ended); [onFinished] isn't called. */
    fun stop() {
        synchronized(lock) {
            stopped = true
            waiting.clear()
        }
        out.stop()
    }

    /** What to say next, marking it spoken; null when nothing waits or something is still being said. */
    private fun takeNext(): String? {
        if (speaking || waiting.isEmpty()) return null
        val words = speakable(waiting.toString())
        waiting.clear()
        if (words.isBlank()) return null
        speaking = true
        return words
    }

    private fun say(words: String) {
        onSpeak()
        out.speak(words) {
            val next = synchronized(lock) {
                speaking = false
                if (stopped) return@speak
                takeNext()
            }
            next?.let(::say) ?: maybeFinish()
        }
    }

    private fun maybeFinish() {
        val done = synchronized(lock) {
            if (stopped || finished || speaking || waiting.isNotEmpty() || !replyOver) false else true.also { finished = true }
        }
        if (done) onFinished()
    }

    companion object {
        /** What Talk says when Hermes starts a tool, by what the tool's name suggests. */
        fun cueFor(tool: String): String {
            val t = tool.lowercase()
            return when {
                "calendar" in t || "event" in t -> "Checking your calendar."
                "mail" in t || "inbox" in t -> "Looking at your email."
                "todo" in t || "to_do" in t -> "Checking your to-dos."
                "send_file" in t -> "Sending you the file."
                "search" in t -> "Searching the web."
                "extract" in t || "browse" in t || "browser" in t || "fetch" in t || "web" in t -> "Reading the page."
                "file" in t || "read" in t || "write" in t || "pdf" in t -> "Working on the files."
                "terminal" in t || "shell" in t || "exec" in t || "run" in t || "code" in t || "python" in t -> "Running that now."
                "memory" in t || "skill" in t -> "Thinking it over."
                else -> "One moment."
            }
        }

        private val END = Regex("""[.!?…](?=["')\]]*(\s|$))|\n""")

        /**
         * The finished sentences in [text] after [from], and where they end. A sentence ends at . ! ? … before a
         * space, or at a line break; nothing inside an unclosed code block counts until it closes. When [over],
         * the rest counts too.
         */
        fun completeSentences(text: String, from: Int, over: Boolean): Pair<String, Int> {
            if (from >= text.length) return "" to from
            if (over) return text.substring(from) to text.length
            var end = -1
            END.findAll(text, from).forEach { m ->
                val upTo = m.range.last + 1
                // the end of what's been written isn't the end of a sentence until more follows
                if (upTo < text.length && text.substring(0, upTo).split("```").size % 2 == 1) end = upTo
            }
            return if (end < 0) "" to from else text.substring(from, end) to end
        }
    }
}
