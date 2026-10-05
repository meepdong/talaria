package io.github.meepdong.talaria.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import io.github.meepdong.talaria.ui.SpeechInput
import io.github.meepdong.talaria.ui.SpeechOutput

/**
 * Dictation with Android's speech recogniser, on the device where it can. Runs on the main
 * thread. [askPermission] asks for the microphone and reports whether it was granted.
 */
class AndroidDictation(
    private val context: Context,
    private val askPermission: (onResult: (Boolean) -> Unit) -> Unit,
) : SpeechInput {
    private var recognizer: SpeechRecognizer? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** SpeechRecognizer lives on the main thread; Talk opens the mic again from the speech engine's thread. */
    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block() else main.post(block)
    }

    override fun start(listener: SpeechInput.Listener) = onMain {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            begin(listener)
        } else {
            askPermission { granted ->
                if (granted) begin(listener) else listener.failed("Talaria needs the microphone to take dictation")
            }
        }
    }

    override fun stop() = onMain {
        recognizer?.stopListening()
    }

    private fun begin(listener: SpeechInput.Listener) {
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        var finished = false
        fun finish(report: () -> Unit) {
            if (finished) return
            finished = true
            report()
            r.destroy()
            if (recognizer === r) recognizer = null
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partial: Bundle) {
                first(partial)?.let(listener::partial)
            }

            override fun onResults(results: Bundle) = finish { listener.done(first(results).orEmpty()) }

            override fun onError(error: Int) = finish {
                when (error) {
                    // heard nothing: not worth an error
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listener.done("")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> listener.failed("Talaria needs the microphone to take dictation")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        listener.failed("Dictation needs a network connection, or an offline speech pack for your language")
                    else -> listener.failed("Dictation failed (speech error $error)")
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        })
    }

    private fun first(results: Bundle): String? =
        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    companion object {
        fun isAvailable(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)
    }
}

/** Reads text aloud with the phone's text-to-speech voice. */
class AndroidVoice(context: Context) : SpeechOutput {
    @Volatile private var ready = false
    private val lock = Any()
    private var current: Pair<String, () -> Unit>? = null
    private var count = 0
    private val tts = TextToSpeech(context.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) = finished(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finished(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finished(utteranceId)
        })
    }

    override fun speak(text: String, onDone: () -> Unit) {
        stop()
        if (!ready) {
            onDone()
            return
        }
        val pieces = chunks(text, TextToSpeech.getMaxSpeechInputLength() - 100)
        val last = synchronized(lock) { "talaria-${++count}" }
        synchronized(lock) { current = last to onDone }
        pieces.forEachIndexed { i, piece ->
            val id = if (i == pieces.lastIndex) last else "$last-$i"
            tts.speak(piece, TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    override fun stop() {
        val done = synchronized(lock) { current.also { current = null } }
        tts.stop()
        done?.second?.invoke()
    }

    private fun finished(utteranceId: String) {
        val done = synchronized(lock) { current?.takeIf { it.first == utteranceId }?.also { current = null } }
        done?.second?.invoke()
    }

    /** Text-to-speech takes a few thousand characters at a time; split at line or sentence ends. */
    private fun chunks(text: String, max: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > max) {
            val cut = maxOf(rest.lastIndexOf('\n', max), rest.lastIndexOf(". ", max) + 1, rest.lastIndexOf(' ', max))
                .takeIf { it > 0 } ?: max
            out += rest.substring(0, cut)
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotBlank()) out += rest
        return out
    }
}

/**
 * Plays Talk's natural voice (WAV or MP3 from the bridge) through the assistant audio stream. One clip at a time; [stop] ends it
 * and still calls its onDone, as [io.github.meepdong.talaria.ui.AudioPlayer] asks.
 */
class AndroidAudio(context: Context) : io.github.meepdong.talaria.ui.AudioPlayer {
    private val dir = java.io.File(context.cacheDir, "talk").apply { mkdirs() }
    private val lock = Any()
    private var player: android.media.MediaPlayer? = null
    private var done: (() -> Unit)? = null
    private var count = 0

    override fun play(audio: ByteArray, onDone: () -> Unit) {
        stop()
        val kind = if (audio.size > 4 && String(audio, 0, 4, Charsets.US_ASCII) == "RIFF") "wav" else "mp3"
        val file = java.io.File(dir, "clip-${synchronized(lock) { ++count % 4 }}.$kind")
        val p = android.media.MediaPlayer()
        synchronized(lock) {
            player = p
            done = onDone
        }
        try {
            file.writeBytes(audio)
            p.setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(file.path)
            p.setOnCompletionListener { finished(p) }
            p.setOnErrorListener { _, _, _ -> finished(p); true }
            p.prepare()
            p.start()
        } catch (e: Exception) {
            finished(p)
        }
    }

    override fun stop() {
        val p = synchronized(lock) { player }
        if (p != null) {
            runCatching { p.stop() }
            finished(p)
        }
    }

    private fun finished(p: android.media.MediaPlayer) {
        val callback = synchronized(lock) {
            if (player !== p) return
            player = null
            done.also { done = null }
        }
        runCatching { p.release() }
        callback?.invoke()
    }
}
