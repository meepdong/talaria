package io.github.meepdong.talaria.session

import io.github.meepdong.talaria.protocol.Tnp
import io.github.meepdong.talaria.security.DeviceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Timing knobs; the defaults follow PROTOCOL §3.4 and the M1 plan. */
data class ClientSettings(
    val pingIdleMs: Long = 30_000,
    val deadAfterMs: Long = 90_000,
    val requestTimeoutMs: Long = 15_000,
    val maxBackoffMs: Long = 60_000,
    /** Reset the backoff once a connection has lasted this long. */
    val stableAfterMs: Long = 5 * 60_000,
    val tickMs: Long = 1_000,
)

/** Outcome of the Test connection button. */
data class TestResult(val ok: Boolean, val latencyMs: Long?, val message: String)

/**
 * Keeps one session with the paired bridge open: connect, verify, authenticate, answer
 * pings, and reconnect after 1, 2, 4 … 60 s with ±20% jitter. Revocation, refused
 * credentials and a changed bridge identity stop it, because retrying can't fix them.
 */
class TnpClient(
    private val scope: CoroutineScope,
    private val transport: Transport,
    private val bridge: PairedBridge,
    private val key: DeviceKey,
    private val platform: String,
    val log: ConnectionLog = ConnectionLog(),
    private val settings: ClientSettings = ClientSettings(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var job: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var live: LiveSession? = null

    private val _notifications = MutableSharedFlow<JsonObject>(extraBufferCapacity = 1024)

    /** Notifications from the bridge other than `ping` and `status`, such as chat.delta. */
    val notifications: SharedFlow<JsonObject> = _notifications.asSharedFlow()

    /**
     * Call [method] on the bridge and return its result. Throws [RpcException] for an
     * error reply, and [TnpException] when there is no session or no answer in time.
     */
    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long? = null): JsonObject {
        val session = live ?: throw TnpException(Failure.CLOSED, "not connected")
        val reply = session.call(method, params, timeoutMs ?: settings.requestTimeoutMs)
        reply.obj("error")?.let { err ->
            throw RpcException(err.long("code")?.toInt() ?: 0, err.str("message") ?: "Request failed")
        }
        return reply.obj("result") ?: JsonObject(emptyMap())
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        live = null
        _state.update { it.copy(phase = ConnectionState.Phase.STOPPED, nextRetryAtMs = null) }
    }

    /** Skip the wait before the next attempt, or start again after a terminal failure. */
    fun reconnectNow() {
        if (job?.isActive == true) wake.trySend(Unit) else start()
    }

    /** Ping the bridge and fetch a fresh status report. */
    suspend fun test(): TestResult {
        val session = live ?: return TestResult(false, null, state.value.failure?.message ?: "Not connected")
        return try {
            val latency = session.ping()
            session.refreshStatus()
            TestResult(true, latency, "Bridge answered in $latency ms")
        } catch (e: TnpException) {
            TestResult(false, null, e.message ?: "Test failed")
        }
    }

    /** Next retry delay for the [attempt]th consecutive failure (1-based). */
    fun backoffMs(attempt: Int): Long {
        val base = minOf(settings.maxBackoffMs, 1_000L shl minOf(attempt - 1, 20))
        return (base * (0.8 + 0.4 * random.nextDouble())).toLong()
    }

    private suspend fun run() {
        var failures = 0
        while (true) {
            wake.tryReceive() // a reconnect request from before this attempt is already served
            _state.update { it.copy(phase = ConnectionState.Phase.CONNECTING, nextRetryAtMs = null, attempt = failures + 1) }
            var connectedAt: Long? = null
            val error: TnpException = try {
                connectOnce { connectedAt = it }
                TnpException(Failure.CLOSED)
            } catch (e: TnpException) {
                e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TnpException(Failure.UNREACHABLE, e.message)
            }
            live = null
            val detail = error.message?.removePrefix(error.failure.message)?.removePrefix(": ")?.ifBlank { null }
            log.add(nowMs(), if (connectedAt != null) "Disconnected" else "Connection failed",
                listOfNotNull(error.failure.message, detail).joinToString(": "))
            if (error.failure.terminal) {
                _state.update {
                    it.copy(phase = ConnectionState.Phase.FAILED, failure = error.failure, detail = detail,
                        sessionId = null, nextRetryAtMs = null)
                }
                return
            }
            val wasStable = connectedAt?.let { nowMs() - it >= settings.stableAfterMs } == true
            failures = if (wasStable) 1 else failures + 1
            val wait = backoffMs(failures)
            _state.update {
                it.copy(phase = ConnectionState.Phase.WAITING, failure = error.failure, detail = detail,
                    sessionId = null, nextRetryAtMs = nowMs() + wait, attempt = failures)
            }
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    private suspend fun connectOnce(onConnected: (Long) -> Unit) {
        val ws = transport.open(bridge.url, bridge.tlsSpkiSha256)
        try {
            val auth = ws.authenticate(bridge, key, platform, nowMs() / 1000)
            val at = nowMs()
            onConnected(at)
            log.add(at, "Connected", auth.sessionId)
            // Live before CONNECTED, so whoever reacts to the state can make requests.
            val session = LiveSession(ws).also { live = it }
            _state.update {
                it.copy(phase = ConnectionState.Phase.CONNECTED, failure = null, detail = null,
                    sessionId = auth.sessionId, lastConnectedAtMs = at, nextRetryAtMs = null)
            }
            session.serve()
        } finally {
            ws.close(Tnp.CLOSE_NORMAL, "client closing")
        }
    }

    /** One authenticated connection: the reader, our idle pings and pending requests. */
    private inner class LiveSession(private val ws: WsConnection) {
        private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        private val nextId = AtomicInteger()
        @Volatile private var lastRx = nowMs()

        suspend fun serve() = coroutineScope {
            val heartbeat = launch {
                var lastPing = 0L
                while (true) {
                    delay(settings.tickMs)
                    val idle = nowMs() - lastRx
                    if (idle > settings.deadAfterMs) {
                        ws.close(Tnp.CLOSE_TIMEOUT, "no traffic")
                        throw TnpException(Failure.TIMEOUT)
                    }
                    if (idle >= settings.pingIdleMs && nowMs() - lastPing >= settings.pingIdleMs) {
                        lastPing = nowMs()
                        launch { runCatching { ping() } }
                    }
                }
            }
            launch { runCatching { refreshStatus() } }
            try {
                while (true) {
                    val msg = ws.receiveMessage(Long.MAX_VALUE)
                    lastRx = nowMs()
                    handle(msg)
                }
            } finally {
                heartbeat.cancel()
                pending.values.forEach { it.cancel() }
            }
        }

        private suspend fun handle(msg: JsonObject) {
            val id = (msg["id"] as? JsonPrimitive)?.content
            when (msg.str("method")) {
                null -> if (id != null) pending.remove(id)?.complete(msg)
                "ping" -> if (msg["id"] != null) {
                    ws.send(Tnp.encode(Tnp.result(msg["id"]!!, buildJsonObject { put("ts", nowMs() / 1000) })))
                }
                "status" -> msg.obj("params")?.let { applyStatus(it) }
                else -> if (msg["id"] != null) {
                    ws.send(Tnp.encode(Tnp.error(msg["id"]!!, Tnp.METHOD_NOT_FOUND, "Not supported by this client")))
                } else {
                    _notifications.emit(msg)
                }
            }
        }

        suspend fun call(method: String, params: JsonObject? = null, timeoutMs: Long = settings.requestTimeoutMs): JsonObject {
            val id = "d-${nextId.incrementAndGet()}"
            val reply = CompletableDeferred<JsonObject>()
            pending[id] = reply
            ws.send(Tnp.encode(Tnp.request(id, method, params)))
            return try {
                withTimeout(timeoutMs) { reply.await() }
            } catch (e: TimeoutCancellationException) {
                throw TnpException(Failure.TIMEOUT, "no answer to $method")
            } finally {
                pending.remove(id)
            }
        }

        suspend fun ping(): Long {
            val start = nowMs()
            call("ping")
            val latency = nowMs() - start
            _state.update { it.copy(latencyMs = latency) }
            return latency
        }

        suspend fun refreshStatus() {
            val reply = call("status.get")
            val error = reply.obj("error")
            if (error != null) {
                // A bridge from before M1 doesn't know status.get yet.
                _state.update { it.copy(statusSupported = false, status = null) }
                return
            }
            reply.obj("result")?.let { applyStatus(it) }
        }

        private fun applyStatus(report: JsonObject) {
            _state.update { it.copy(status = parseStatus(report), statusSupported = true) }
        }
    }
}
