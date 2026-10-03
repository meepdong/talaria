package io.github.meepdong.talaria.desktop

import io.github.meepdong.talaria.ui.SpeechInput
import io.github.meepdong.talaria.ui.SpeechOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * Offline speech-to-text with Vosk. The small English model (about 40 MB) downloads into
 * [folder] the first time someone dictates.
 */
class VoskInput(private val folder: File, private val scope: CoroutineScope) : SpeechInput {
    @Volatile private var job: Job? = null
    @Volatile private var stopRequested = false
    private var model: Model? = null

    override fun start(listener: SpeechInput.Listener) {
        if (job?.isActive == true) return
        stopRequested = false
        job = scope.launch(Dispatchers.IO) {
            try {
                listen(loadModel(listener), listener)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listener.failed("Dictation failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    override fun stop() {
        stopRequested = true
    }

    private fun listen(model: Model, listener: SpeechInput.Listener) {
        val line = AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, FORMAT)) as TargetDataLine
        val heard = mutableListOf<String>()
        Recognizer(model, FORMAT.sampleRate).use { rec ->
            line.open(FORMAT)
            line.start()
            try {
                val buf = ByteArray(4096)
                val started = System.currentTimeMillis()
                var lastSpeech = started
                var partial = ""
                while (!stopRequested && System.currentTimeMillis() - started < MAX_LISTEN_MS) {
                    val n = line.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (rec.acceptWaveForm(buf, n)) {
                        field(rec.result, "text")?.takeIf { it.isNotBlank() }?.let { heard += it }
                        partial = ""
                    } else {
                        val now = field(rec.partialResult, "partial").orEmpty()
                        if (now != partial) {
                            partial = now
                            if (now.isNotBlank()) lastSpeech = System.currentTimeMillis()
                        }
                    }
                    listener.partial((heard + partial).filter { it.isNotBlank() }.joinToString(" "))
                    // hands-free: stop once someone has spoken and then gone quiet
                    if (heard.isNotEmpty() && partial.isBlank() && System.currentTimeMillis() - lastSpeech > QUIET_STOP_MS) break
                }
            } finally {
                line.stop()
                line.close()
            }
            field(rec.finalResult, "text")?.takeIf { it.isNotBlank() }?.let { heard += it }
        }
        listener.done(heard.joinToString(" "))
    }

    @Synchronized
    private fun loadModel(listener: SpeechInput.Listener): Model {
        model?.let { return it }
        val dir = File(folder, MODEL_NAME)
        if (!File(dir, "conf/model.conf").isFile) {
            listener.partial("Downloading the speech model (about 40 MB, only once)…")
            download(dir)
        }
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        return Model(dir.path).also { model = it }
    }

    private fun download(dir: File) {
        folder.mkdirs()
        val tmp = Files.createTempDirectory(folder.toPath(), "model-").toFile()
        try {
            URI(MODEL_URL).toURL().openStream().use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    val root = tmp.canonicalFile
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val out = File(root, entry.name).canonicalFile
                        require(out.toPath().startsWith(root.toPath())) { "Unexpected path in the model download" }
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
            val unpacked = File(tmp, MODEL_NAME)
            require(File(unpacked, "conf/model.conf").isFile) { "The model download is incomplete" }
            Files.move(unpacked.toPath(), dir.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.deleteRecursively()
        }
    }

    companion object {
        const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
        private val FORMAT = AudioFormat(16_000f, 16, 1, true, false)
        private const val MAX_LISTEN_MS = 60_000L
        private const val QUIET_STOP_MS = 2_000L
        private val FIELD = Regex(""""(\w+)"\s*:\s*"((?:[^"\\]|\\.)*)"""")

        /** Null when this computer has no microphone Java can open. */
        fun forThisComputer(folder: File, scope: CoroutineScope): VoskInput? =
            if (runCatching { AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, FORMAT)) }.getOrDefault(false)) {
                VoskInput(folder, scope)
            } else {
                null
            }

        /** One string field of Vosk's small JSON results, such as {"partial" : "hello"}. */
        fun field(json: String, name: String): String? =
            FIELD.findAll(json).firstOrNull { it.groupValues[1] == name }?.groupValues?.get(2)
                ?.replace("\\\"", "\"")?.replace("\\\\", "\\")
    }
}

/** Reads text aloud: Windows' own voices, or espeak-ng / spd-say on Linux. */
class DesktopVoice private constructor(private val command: List<String>, private val viaStdin: Boolean, private val stopCommand: List<String>?) : SpeechOutput {
    @Volatile private var process: Process? = null

    override fun speak(text: String, onDone: () -> Unit) {
        stop()
        val p = try {
            // no shell, and the text goes in as data, so nothing in a reply can run as a command
            ProcessBuilder(if (viaStdin) command else command + listOf("--", text))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: Exception) {
            onDone()
            return
        }
        process = p
        Thread {
            runCatching {
                if (viaStdin) p.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) } else p.outputStream.close()
                p.waitFor()
            }
            if (process === p) process = null
            onDone()
        }.apply { isDaemon = true }.start()
    }

    override fun stop() {
        val p = process ?: return
        process = null
        p.descendants().forEach { it.destroy() }
        p.destroy()
        stopCommand?.let { runCatching { ProcessBuilder(it).start() } }
    }

    companion object {
        private const val WINDOWS_SPEAK = "[Console]::InputEncoding = [Text.Encoding]::UTF8; " +
            "Add-Type -AssemblyName System.Speech; " +
            "(New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak([Console]::In.ReadToEnd())"

        /** Null on a Linux desktop with neither espeak-ng nor spd-say installed. */
        fun forThisComputer(windows: Boolean): DesktopVoice? = when {
            windows -> DesktopVoice(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", WINDOWS_SPEAK), true, null)
            onPath("espeak-ng") -> DesktopVoice(listOf("espeak-ng", "--stdin"), true, null)
            onPath("spd-say") -> DesktopVoice(listOf("spd-say", "-w"), false, listOf("spd-say", "-S"))
            else -> null
        }

        private fun onPath(name: String): Boolean =
            System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, name).canExecute() }
    }
}
