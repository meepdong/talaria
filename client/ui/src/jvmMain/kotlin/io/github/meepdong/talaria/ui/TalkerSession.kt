package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.chat.VoiceApi.TalkEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Records what the owner says, until they stop talking. The platform's own (Android: the microphone). */
interface VoiceRecorder {
    /** Records; [Listener.done] gets WAV once they've finished, or null if nothing was said. */
    fun start(listener: Listener)
    /** Stop now and deliver what was said so far. */
    fun stop()
    /** Stop now and drop it. */
    fun cancel()

    interface Listener {
        /** They started speaking. */
        fun speaking() {}
        fun done(wav: ByteArray?)
        fun failed(message: String)
    }
}

/** Plays 24 kHz 16-bit mono PCM as it streams in. */
interface PcmPlayer {
    fun write(pcm: ByteArray)
    /** [onDone] once everything written so far has been played. */
    fun finish(onDone: () -> Unit)
    /** Stop now, dropping what's left (no onDone). */
    fun stop()
}

/** The bridge's talker, as Talk 3 uses it (VoiceApi). */
interface TalkerApi {
    val events: Flow<TalkEvent>
    suspend fun turn(wav: ByteArray, conversationId: String?): String?
    suspend fun say(text: String, conversationId: String?): String?
    suspend fun end(conversationId: String)
}

/**
 * Talk 3 (spec §9): the owner speaks, the bridge's voice model hears the audio itself and answers out loud, doing
 * quick things itself and briefing the agent for the rest; when the agent replies, the talker says that too. This
 * keeps the turn-taking: record until they stop, play the answer as it streams, a tone, listen again. Silence ends
 * it; a tap ends their turn early or cuts the talker off.
 */
class TalkerSession(
    private val scope: CoroutineScope,
    private val api: TalkerApi,
    private val recorder: VoiceRecorder,
    private val player: PcmPlayer,
    /** The "your turn" tone. */
    private val cue: suspend () -> Unit,
    /** What to show: the phase and the words heard or said. */
    private val onPhase: (TalkPhase, String) -> Unit,
    /** The talker started a chat (its first brief to the agent): open it. */
    private val onConversation: (String) -> Unit,
    /** Talk is over; with why, if it failed. */
    private val onEnd: (String?) -> Unit,
) {
    @Volatile var conversationId: String? = null
        private set
    private val lock = Any()
    private val mine = mutableSetOf<String>()  // talks this session asked for, until done
    private val playing = mutableSetOf<String>()  // talks whose audio is being played, until done
    private val ignored = mutableSetOf<String>()  // cut off by a tap
    private val said = mutableMapOf<String, StringBuilder>()
    private val afterSaid = mutableMapOf<String, () -> Unit>()
    private var listening = false
    private var held = false
    private var ended = false
    private var events: Job? = null

    fun start(conversationId: String?) {
        this.conversationId = conversationId
        events = scope.launch { api.events.collect(::on) }
        listen()
    }

    /** The Talk bar: while listening, that's the end of their turn; otherwise, cut the talker off and listen. */
    fun tap() {
        val wasListening = synchronized(lock) { listening }
        if (wasListening) {
            recorder.stop()
            return
        }
        synchronized(lock) {
            ignored += mine + playing
            mine.clear()
            playing.clear()
            afterSaid.clear()
        }
        player.stop()
        listen()
    }

    fun end(error: String? = null) {
        val conv = synchronized(lock) {
            if (ended) return
            ended = true
            conversationId
        }
        events?.cancel()
        recorder.cancel()
        player.stop()
        conv?.let { scope.launch { api.end(it) } }
        onEnd(error)
    }

    /**
     * Say a line in the talker's voice (an approval's question); [then] runs once it has been said. While [hold]
     * is on, Talk doesn't listen again by itself (the controller takes the answer).
     */
    fun say(line: String, then: (() -> Unit)? = null) {
        scope.launch {
            val id = api.say(line, conversationId) ?: return@launch then?.invoke() ?: Unit
            synchronized(lock) {
                mine += id
                then?.let { afterSaid[id] = it }
            }
        }
    }

    fun hold(on: Boolean) {
        synchronized(lock) { held = on }
        if (on) {
            recorder.cancel()
            synchronized(lock) { listening = false }
        }
    }

    /** Stop saying whatever is being said now (an approval answered on screen), and drop what was to follow. */
    fun cut() {
        synchronized(lock) {
            ignored += mine + playing
            mine.clear()
            playing.clear()
            afterSaid.clear()
        }
        player.stop()
    }

    /** Listen again after something the controller handled (an approval). */
    fun resume() {
        hold(false)
        val idle = synchronized(lock) { mine.isEmpty() && playing.isEmpty() && !listening && !ended }
        if (idle) scope.launch {
            cue()
            listen()
        }
    }

    private fun listen() {
        synchronized(lock) {
            if (ended || held) return
            listening = true
        }
        onPhase(TalkPhase.LISTENING, "")
        recorder.start(object : VoiceRecorder.Listener {
            override fun done(wav: ByteArray?) {
                val busy = synchronized(lock) {
                    listening = false
                    mine.isNotEmpty() || playing.isNotEmpty()
                }
                if (ended) return
                when {
                    wav != null -> send(wav)
                    !busy -> end()  // quiet: Talk is over
                }
            }

            override fun failed(message: String) = end(message)
        })
    }

    private fun send(wav: ByteArray) {
        onPhase(TalkPhase.THINKING, "")
        scope.launch {
            val id = api.turn(wav, conversationId)
            if (id == null) {
                end("The voice assistant isn't available right now")
                return@launch
            }
            synchronized(lock) { mine += id }
        }
    }

    private fun on(e: TalkEvent) {
        val ours = synchronized(lock) {
            e.talkId !in ignored && (e.talkId in mine || e.talkId in playing ||
                (conversationId != null && e.conversationId == conversationId))
        }
        if (!ours || ended) return
        when (e) {
            is TalkEvent.Audio -> {
                val interrupting = synchronized(lock) {
                    playing += e.talkId
                    listening.also { listening = false }
                }
                if (interrupting) recorder.cancel()  // the agent's reply came while they were quiet
                player.write(e.pcm)
                onPhase(TalkPhase.SPEAKING, synchronized(lock) { said[e.talkId]?.toString().orEmpty() })
            }
            is TalkEvent.Text -> {
                val text = synchronized(lock) { said.getOrPut(e.talkId) { StringBuilder() }.append(e.text).toString() }
                onPhase(TalkPhase.SPEAKING, text)
            }
            is TalkEvent.Done -> {
                val started = e.conversationId
                if (conversationId == null && started != null) {
                    conversationId = started
                    onConversation(started)
                }
                val (idle, then) = synchronized(lock) {
                    mine -= e.talkId
                    playing -= e.talkId
                    said -= e.talkId
                    (mine.isEmpty() && playing.isEmpty()) to afterSaid.remove(e.talkId)
                }
                if (e.error != null && e.text.isEmpty()) {
                    end(e.error)
                    return
                }
                player.finish {
                    then?.invoke()
                    if (idle && !synchronized(lock) { held || listening || ended }) scope.launch {
                        cue()  // their turn
                        listen()
                    }
                }
            }
        }
    }
}
