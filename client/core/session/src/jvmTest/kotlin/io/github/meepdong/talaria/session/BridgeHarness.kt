package io.github.meepdong.talaria.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit

/** The real Python bridge in a child process, driven through bridge_harness.py. */
class BridgeHarness private constructor(private val process: Process) : AutoCloseable {
    private val out = process.inputStream.bufferedReader()
    private val input = process.outputStream.bufferedWriter()

    val url: String = read().getValue("url").jsonPrimitive.content

    private fun read(): JsonObject {
        val line = out.readLine() ?: error("bridge harness exited: " + process.errorStream.bufferedReader().readText())
        return Json.parseToJsonElement(line).jsonObject
    }

    private fun command(json: String): JsonObject {
        input.write(json + "\n")
        input.flush()
        return read()
    }

    /** A fresh pairing: (link, short code). Requests are approved automatically. */
    fun newPairing(ttl: Int = 300): Pair<String, String> = command("""{"cmd":"pair","ttl":$ttl}""")
        .let { it.getValue("link").jsonPrimitive.content to it.getValue("short_code").jsonPrimitive.content }

    fun revoke(deviceId: String) = command("""{"cmd":"revoke","device_id":"$deviceId"}""")

    fun setAgent(state: String) = command("""{"cmd":"agent","state":"$state"}""")

    override fun close() {
        input.close()
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        /**
         * Starts the harness, or skips the test when Python with the bridge isn't available.
         * CI sets TALARIA_PYTHON, and then a missing bridge fails instead of skipping.
         * [oldBridge] answers status.get with METHOD_NOT_FOUND, like an M0 bridge.
         */
        fun start(oldBridge: Boolean = false): BridgeHarness {
            val required = System.getenv("TALARIA_PYTHON")
            val python = required ?: listOf("python3", "python").firstOrNull { works(it) }
            if (required == null) assumeTrue(python != null && works(python), "Python with talaria_bridge not found")
            val script = File.createTempFile("bridge_harness", ".py").apply {
                deleteOnExit()
                writeText(BridgeHarness::class.java.getResource("/bridge_harness.py")!!.readText())
            }
            val pb = ProcessBuilder(listOfNotNull(python, script.path, "--old".takeIf { oldBridge }))
            System.getenv("TALARIA_BRIDGE_SRC")?.let { pb.environment()["PYTHONPATH"] = it }
            return BridgeHarness(pb.start())
        }

        private fun works(python: String): Boolean = runCatching {
            val pb = ProcessBuilder(python, "-c", "import talaria_bridge, websockets, cryptography, httpx")
            System.getenv("TALARIA_BRIDGE_SRC")?.let { pb.environment()["PYTHONPATH"] = it }
            val p = pb.redirectErrorStream(true).start()
            p.inputStream.readAllBytes()
            p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
    }
}
