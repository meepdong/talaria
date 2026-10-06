package io.github.meepdong.talaria.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import io.github.meepdong.talaria.ui.PcmPlayer
import io.github.meepdong.talaria.ui.VoiceRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Talk 3's ears: the microphone at 16 kHz until the owner stops talking (0.8 s of quiet after speech), as WAV. A
 * quarter of a second before the first word is kept so it isn't clipped; 7 s with nothing said gives null (Talk
 * ends); 20 s is the most one turn records.
 */
class AndroidRecorder(
    private val context: Context,
    private val askPermission: (onResult: (Boolean) -> Unit) -> Unit,
) : VoiceRecorder {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var state = IDLE

    override fun start(listener: VoiceRecorder.Listener) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            begin(listener)
        } else {
            main.post {
                askPermission { granted ->
                    if (granted) begin(listener) else listener.failed("Talaria needs the microphone to talk")
                }
            }
        }
    }

    override fun stop() {
        if (state == RUNNING) state = STOPPED
    }

    override fun cancel() {
        if (state == RUNNING) state = CANCELLED
    }

    private fun begin(listener: VoiceRecorder.Listener) {
        state = RUNNING
        Thread({ record(listener) }, "talaria-talk-mic").start()
    }

    private fun record(listener: VoiceRecorder.Listener) {
        val frame = RATE / 50  // 20 ms
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT), RATE))
        } catch (e: SecurityException) {
            main.post { listener.failed("Talaria needs the microphone to talk") }
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            main.post { listener.failed("The microphone is busy") }
            return
        }
        val buf = ShortArray(frame)
        val pre = ArrayDeque<ShortArray>()
        val out = ByteArrayOutputStream()
        var floor = 0.0
        var speaking = false
        var quietMs = 0
        var ms = 0
        rec.startRecording()
        try {
            while (state == RUNNING) {
                val n = rec.read(buf, 0, frame)
                if (n <= 0) continue
                val chunk = buf.copyOf(n)
                var sum = 0.0
                for (s in chunk) sum += s.toDouble() * s
                val rms = sqrt(sum / n)
                ms += 20
                if (ms <= 300) floor = if (floor == 0.0) rms else floor * 0.8 + rms * 0.2  // the room, before they speak
                val loud = rms > max(MIN_SPEECH, floor * 2.5)
                if (!speaking) {
                    pre.addLast(chunk)
                    if (pre.size > PRE_FRAMES) pre.removeFirst()
                    if (loud && ms > 100) {
                        speaking = true
                        pre.forEach { out.write(pcm(it)) }
                        pre.clear()
                        main.post { listener.speaking() }
                    } else if (ms >= NOTHING_MS) break
                } else {
                    out.write(pcm(chunk))
                    quietMs = if (loud) 0 else quietMs + 20
                    if (quietMs >= END_QUIET_MS || ms >= MAX_MS) break
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        val how = state
        state = IDLE
        if (how == CANCELLED) return
        val wav = if (speaking && out.size() > 0) wav(out.toByteArray()) else null
        main.post { listener.done(wav) }
    }

    private fun pcm(samples: ShortArray): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1)
            .putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }

    private companion object {
        const val RATE = 16_000
        const val IDLE = 0
        const val RUNNING = 1
        const val STOPPED = 2
        const val CANCELLED = 3
        const val MIN_SPEECH = 600.0
        const val PRE_FRAMES = 12  // 240 ms
        const val END_QUIET_MS = 800
        const val NOTHING_MS = 7_000
        const val MAX_MS = 20_000
    }
}

/** Talk 3's voice: 24 kHz 16-bit mono PCM played as it streams in, on the assistant audio stream. */
class AndroidPcmPlayer : PcmPlayer {
    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<Any>()
    private val lock = Any()
    private var track: AudioTrack? = null
    @Volatile private var frames = 0L

    private class Finish(val onDone: () -> Unit)

    override fun write(pcm: ByteArray) {
        ensure()
        queue.put(pcm)
    }

    override fun finish(onDone: () -> Unit) {
        ensure()
        queue.put(Finish(onDone))
    }

    override fun stop() {
        synchronized(lock) {
            queue.clear()
            track?.let {
                runCatching {
                    it.pause()
                    it.flush()
                    it.play()
                }
            }
            frames = 0
        }
    }

    private fun ensure() = synchronized(lock) {
        if (track != null) return@synchronized
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(AudioTrack.getMinBufferSize(24_000, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT), 24_000))
            .build()
        t.play()
        track = t
        Thread(::run, "talaria-talk-voice").apply { isDaemon = true }.start()
    }

    private fun run() {
        while (true) {
            val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val t = synchronized(lock) { track } ?: continue
            when (item) {
                is ByteArray -> {
                    t.write(item, 0, item.size)
                    frames += item.size / 2
                }
                is Finish -> {
                    // wait for what was written to be heard, then say so
                    val target = frames
                    val until = System.currentTimeMillis() + 30_000
                    while ((t.playbackHeadPosition.toLong() and 0xffffffffL) < target &&
                        System.currentTimeMillis() < until) Thread.sleep(20)
                    main.post(item.onDone)
                }
            }
        }
    }
}
