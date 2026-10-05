package io.github.meepdong.talaria.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Plays WAV or MP3 audio; the platform's own player. [onDone] runs when it ends, fails or is stopped. */
interface AudioPlayer {
    fun play(audio: ByteArray, onDone: () -> Unit)
    fun stop()
}

/**
 * Talk's natural voice (Talk 2): text is turned into speech by the bridge in pieces of up to [maxPiece] characters,
 * cut at sentence ends, and played one after another while the next is fetched. A piece that can't be fetched is
 * said by [fallback], the device's own voice, so Talk never goes quiet.
 */
class CloudSpeech(
    private val scope: CoroutineScope,
    private val fetch: suspend (String) -> ByteArray?,
    private val player: AudioPlayer,
    private val fallback: SpeechOutput?,
    private val maxPiece: Int = io.github.meepdong.talaria.chat.VoiceApi.MAX_SPEECH,
) : SpeechOutput {
    private val lock = Any()
    private val fetched = LinkedHashMap<String, Deferred<ByteArray?>>()
    private var job: Job? = null
    private var onDone: (() -> Unit)? = null

    override fun prepare(text: String) {
        pieces(text).firstOrNull()?.let(::audio)
    }

    override fun speak(text: String, onDone: () -> Unit) {
        stop()
        synchronized(lock) { this.onDone = onDone }
        job = scope.launch {
            val pieces = pieces(text)
            pieces.forEachIndexed { i, piece ->
                val mp3 = audio(piece).await()
                pieces.getOrNull(i + 1)?.let(::audio)  // the next one is fetched while this one plays
                synchronized(lock) { fetched.remove(piece) }
                if (mp3 != null) play(mp3) else fallback?.let { say(it, piece) }
            }
            finish()
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        player.stop()
        fallback?.stop()
        finish()
    }

    private fun finish() {
        val done = synchronized(lock) { onDone.also { onDone = null } }
        done?.invoke()
    }

    private fun audio(piece: String): Deferred<ByteArray?> = synchronized(lock) {
        fetched.getOrPut(piece) { scope.async { runCatching { fetch(piece) }.getOrNull() } }.also {
            while (fetched.size > MAX_FETCHED) fetched.remove(fetched.keys.first())
        }
    }

    private suspend fun play(mp3: ByteArray) = suspendCancellableCoroutine { cont ->
        player.play(mp3) { if (cont.isActive) cont.resume(Unit) }
        cont.invokeOnCancellation { player.stop() }
    }

    private suspend fun say(voice: SpeechOutput, text: String) = suspendCancellableCoroutine { cont ->
        voice.speak(text) { if (cont.isActive) cont.resume(Unit) }
        cont.invokeOnCancellation { voice.stop() }
    }

    /** [text] in pieces of at most [maxPiece] characters, cut after sentences (or words, for a very long one). */
    fun pieces(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        SENTENCE.findAll(text.trim()).map { it.value.trim() }.filter { it.isNotEmpty() }.forEach { s ->
            var sentence = s
            while (sentence.length > maxPiece) {
                val cut = sentence.lastIndexOf(' ', maxPiece).takeIf { it > 0 } ?: maxPiece
                if (current.isNotEmpty()) { out += current.toString(); current.clear() }
                out += sentence.substring(0, cut).trim()
                sentence = sentence.substring(cut).trim()
            }
            if (current.isNotEmpty() && current.length + 1 + sentence.length > maxPiece) { out += current.toString(); current.clear() }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    companion object {
        private val SENTENCE = Regex("""[^.!?…\n]+[.!?…]*["')\]]*|\n""")
        private const val MAX_FETCHED = 8
    }
}
