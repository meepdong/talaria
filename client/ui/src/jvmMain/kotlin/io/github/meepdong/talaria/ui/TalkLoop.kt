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
    /** The next thing to say, frozen while something else is said so its audio can be fetched meanwhile. */
    private var next: String? = null
    private var saidAnything = false
    private var replyStarted = false
    private var lastSoundMs = 0L
    private var nudges = 0
    /** Waiting for the owner (an approval on screen): no "still working" lines meanwhile. */
    private var held = false

    /** The reply so far: its text, its tool steps, and whether it has ended. */
    fun update(text: String, tools: List<ToolStep>, over: Boolean) {
        val nextUp = synchronized(lock) {
            if (stopped || finished) return
            // a tool starting while nothing is waiting to be said: a short cue, once per kind of tool
            tools.drop(toolsSeen).forEach { step ->
                val cue = cueFor(step.name)
                if (step.state == "started" && waiting.isEmpty() && !speaking && cued.add(cue)) waiting.append(cue).append(' ')
            }
            toolsSeen = tools.size
            val (ready, upTo) = completeSentences(text, consumed, over)
            consumed = upTo
            if (ready.isNotBlank()) {
                waiting.append(ready).append(' ')
                replyStarted = true
            }
            replyOver = over
            takeNext()
        }
        nextUp?.let(::say) ?: run {
            freezeNext()
            maybeFinish()
        }
    }

    /**
     * The quick line said while Hermes starts working ("Sure, checking your calendar."), from a fast model: said
     * first, unless Hermes has already started to answer or something has been said.
     */
    fun opening(line: String) {
        val nextUp = synchronized(lock) {
            if (stopped || finished || saidAnything || replyStarted || speaking) return
            waiting.insert(0, "$line ")
            takeNext()
        }
        nextUp?.let(::say)
    }

    /**
     * Called every second or so while the reply runs: after [QUIET_MS] with nothing said (tools are working), a
     * short "still on it" line, at most [MAX_NUDGES] times.
     */
    fun nudge(nowMs: Long) {
        val nextUp = synchronized(lock) {
            if (held) lastSoundMs = 0L
            if (held || stopped || finished || replyOver || speaking || waiting.isNotEmpty() || nudges >= MAX_NUDGES) return
            if (lastSoundMs == 0L) lastSoundMs = nowMs
            if (nowMs - lastSoundMs < QUIET_MS) return
            waiting.append(NUDGES[nudges % NUDGES.size]).append(' ')
            nudges++
            takeNext()
        }
        nextUp?.let(::say)
    }

    /**
     * Something Talk says itself, in the same voice: after what's waiting, before what comes next. [then] runs
     * once it has been said (such as listening for a yes or no).
     */
    fun note(line: String, then: (() -> Unit)? = null) {
        val nextUp = synchronized(lock) {
            if (stopped || finished) return
            waiting.append(line).append(' ')
            afterSaid = then
            takeNext()
        }
        nextUp?.let(::say)
    }

    private var afterSaid: (() -> Unit)? = null

    /** Hold while the owner answers something on screen (no nudges), and carry on after. */
    fun hold(on: Boolean) = synchronized(lock) { held = on }

    /** While something is said, the next batch is frozen so its audio can be fetched now. */
    private fun freezeNext() {
        val upcoming = synchronized(lock) {
            if (!speaking || next != null || waiting.isEmpty() || stopped) return
            val words = speakable(waiting.toString())
            waiting.clear()
            next = words.takeIf { it.isNotBlank() }
            next
        }
        upcoming?.let(out::prepare)
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
        if (speaking) return null
        next?.let {
            next = null
            speaking = true
            return it
        }
        if (waiting.isEmpty()) return null
        val words = speakable(waiting.toString())
        waiting.clear()
        if (words.isBlank()) return null
        speaking = true
        return words
    }

    private fun say(words: String) {
        synchronized(lock) { saidAnything = true }
        onSpeak()
        out.speak(words) {
            val (following, then) = synchronized(lock) {
                speaking = false
                lastSoundMs = 0L  // the quiet is counted from the next nudge() on
                if (stopped) return@speak
                // a note's follow-up runs once nothing more is queued behind it
                val then = afterSaid.takeIf { next == null && waiting.isEmpty() }?.also { afterSaid = null }
                takeNext() to then
            }
            then?.invoke()
            following?.let(::say) ?: maybeFinish()
        }
        freezeNext()
    }

    private fun maybeFinish() {
        val done = synchronized(lock) {
            if (stopped || finished || speaking || waiting.isNotEmpty() || next != null || !replyOver) false
            else true.also { finished = true }
        }
        if (done) onFinished()
    }

    companion object {
        /** How long Talk stays quiet while Hermes works before saying it's still on it. */
        const val QUIET_MS = 8_000L
        const val MAX_NUDGES = 3
        private val NUDGES = listOf("Still working on it.", "Almost there.", "This one is taking a little longer.")

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
