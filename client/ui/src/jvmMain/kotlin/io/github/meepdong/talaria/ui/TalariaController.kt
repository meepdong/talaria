package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.protocol.PairingPayload
import io.github.meepdong.talaria.protocol.Sas
import io.github.meepdong.talaria.protocol.SAS_EMOJI
import io.github.meepdong.talaria.protocol.ShortCode
import io.github.meepdong.talaria.protocol.isAllowedBridgeUrl
import io.github.meepdong.talaria.security.DeviceKey
import io.github.meepdong.talaria.security.DeviceKeyStore
import io.github.meepdong.talaria.security.loadOrCreate
import io.github.meepdong.talaria.session.ConnectionLog
import io.github.meepdong.talaria.session.ConnectionState
import io.github.meepdong.talaria.session.KtorTransport
import io.github.meepdong.talaria.session.NetworkCheck
import io.github.meepdong.talaria.session.NetworkStatus
import io.github.meepdong.talaria.session.PairedBridge
import io.github.meepdong.talaria.session.PairingStore
import io.github.meepdong.talaria.session.PairingTarget
import io.github.meepdong.talaria.session.TnpClient
import io.github.meepdong.talaria.session.TnpException
import io.github.meepdong.talaria.session.Transport
import io.github.meepdong.talaria.session.pair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs [pair] for the controller; tests swap in a fake. */
fun interface Pairer {
    suspend fun pair(target: PairingTarget, key: DeviceKey, name: String, onSas: (Sas) -> Unit): PairedBridge
}

/**
 * The app's state machine, shared by the desktop and Android apps: pair, keep the
 * session open, and turn everything into a [Screen].
 */
class TalariaController(
    private val scope: CoroutineScope,
    private val keyStore: DeviceKeyStore,
    private val pairingStore: PairingStore,
    private val platform: String,
    private val defaultDeviceName: String,
    val log: ConnectionLog = ConnectionLog(),
    private val transport: Transport = KtorTransport(),
    private val pairer: Pairer = Pairer { target, key, name, onSas ->
        pair(transport, target, key, name, platform, onSas, decisionTimeoutMs = DECISION_TIMEOUT_MS)
    },
    private val newClient: (PairedBridge, DeviceKey) -> TnpClient = { bridge, key ->
        TnpClient(scope, transport, bridge, key, platform, log)
    },
    private val networkCheck: () -> NetworkStatus = { NetworkCheck.check() },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val tickMs: Long = 1_000,
    private val networkEveryMs: Long = 5_000,
) : TalariaActions {
    private sealed interface Mode {
        data class Connect(val name: String, val error: String? = null, val busy: Boolean = false) : Mode
        data class Confirm(val sas: Sas, val deadlineMs: Long) : Mode
        data class Connected(val bridge: PairedBridge, val client: TnpClient) : Mode
    }

    private val mode = MutableStateFlow<Mode>(Mode.Connect(defaultDeviceName))
    private val test = MutableStateFlow<TestView?>(null)
    private val network = MutableStateFlow<NetworkStatus?>(null)
    private val tick = MutableStateFlow(nowMs())
    private var pairJob: Job? = null
    private var started = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val modeAndState = mode.flatMapLatest { m ->
        if (m is Mode.Connected) m.client.state.map { m to it } else flowOf(m to null)
    }

    val screen: StateFlow<Screen> = combine(modeAndState, log.entries, network, test, tick) { (m, state), entries, net, t, now ->
        render(m, state, entries, net, t, now)
    }.stateIn(scope, SharingStarted.Eagerly, Screen.Connect(defaultDeviceName))

    /** Load the saved pairing and connect, and start the clock and the network check. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                tick.value = nowMs()
                delay(tickMs)
            }
        }
        scope.launch(io) {
            while (true) {
                network.value = runCatching { networkCheck() }.getOrNull()
                delay(networkEveryMs)
            }
        }
        scope.launch {
            val saved = withContext(io) { runCatching { pairingStore.load() }.getOrNull() } ?: return@launch
            val key = try {
                withContext(io) { keyStore.load() }
            } catch (e: Exception) {
                mode.value = Mode.Connect(saved.deviceName, "Couldn't read this device's key: ${e.message}")
                return@launch
            }
            if (key == null || key.deviceId != saved.deviceId) {
                mode.value = Mode.Connect(saved.deviceName, "This computer's device key is missing, so pair again")
                return@launch
            }
            connect(saved, key)
        }
    }

    /** Stop the session, for example when the app quits. */
    fun close() {
        pairJob?.cancel()
        (mode.value as? Mode.Connected)?.client?.stop()
    }

    override fun pairWithLink(link: String, deviceName: String) {
        val payload = try {
            PairingPayload.fromLink(link)
        } catch (e: IllegalArgumentException) {
            showError(deviceName, "That isn't a Talaria pairing link. Copy the whole talaria://pair#… line from the terminal")
            return
        }
        startPairing(PairingTarget.Link(payload), deviceName)
    }

    override fun pairWithCode(code: String, address: String, deviceName: String) {
        val normalized = try {
            ShortCode.normalize(code)
        } catch (e: IllegalArgumentException) {
            showError(deviceName, "Short codes are 8 characters, like ABCD-1234")
            return
        }
        val url = bridgeUrl(address) ?: run {
            showError(deviceName, "Enter the server's address, like my-server.tailnet.ts.net or wss://my-server:8765")
            return
        }
        startPairing(PairingTarget.Code(url, normalized), deviceName)
    }

    override fun cancelPairing() {
        pairJob?.cancel()
        pairJob = null
        mode.value = Mode.Connect(currentName())
    }

    override fun testConnection() {
        val client = (mode.value as? Mode.Connected)?.client ?: return
        if (test.value?.running == true) return
        test.value = TestView(running = true)
        scope.launch {
            val result = client.test()
            test.value = TestView(running = false, ok = result.ok, message = result.message)
        }
    }

    override fun reconnectNow() {
        (mode.value as? Mode.Connected)?.client?.reconnectNow()
    }

    override fun forgetServer() {
        val current = mode.value as? Mode.Connected ?: return
        current.client.stop()
        test.value = null
        mode.value = Mode.Connect(current.bridge.deviceName)
        log.add(nowMs(), "Forgot the server", current.bridge.url)
        scope.launch(io) {
            runCatching { pairingStore.clear() }
            // A fresh key for the next pairing: the old one may be revoked or refused.
            runCatching { keyStore.delete() }
        }
    }

    private fun currentName(): String = when (val m = mode.value) {
        is Mode.Connect -> m.name
        is Mode.Connected -> m.bridge.deviceName
        is Mode.Confirm -> defaultDeviceName
    }

    private fun showError(name: String, error: String) {
        mode.value = Mode.Connect(name.ifBlank { defaultDeviceName }, error)
    }

    private fun startPairing(target: PairingTarget, rawName: String) {
        if (pairJob?.isActive == true) return
        val name = rawName.trim().take(64).ifEmpty { defaultDeviceName }
        mode.value = Mode.Connect(name, busy = true)
        pairJob = scope.launch {
            try {
                val key = withContext(io) { keyStore.loadOrCreate() }
                val paired = pairer.pair(target, key, name) { sas ->
                    mode.value = Mode.Confirm(sas, nowMs() + APPROVAL_WINDOW_MS)
                }
                withContext(io) { pairingStore.save(paired) }
                log.add(nowMs(), "Paired", "${paired.deviceName} with ${paired.url}")
                connect(paired, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TnpException) {
                mode.value = Mode.Connect(name, e.message)
            } catch (e: Exception) {
                mode.value = Mode.Connect(name, "Pairing failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    private fun connect(bridge: PairedBridge, key: DeviceKey) {
        val client = newClient(bridge, key)
        test.value = null
        mode.value = Mode.Connected(bridge, client)
        client.start()
    }

    private fun render(
        m: Mode,
        state: ConnectionState?,
        entries: List<ConnectionLog.Entry>,
        net: NetworkStatus?,
        t: TestView?,
        now: Long,
    ): Screen = when (m) {
        is Mode.Connect -> Screen.Connect(m.name, m.error, m.busy)
        is Mode.Confirm -> Screen.Confirm(
            digits = "${m.sas.digits.take(3)} ${m.sas.digits.drop(3)}",
            emoji = m.sas.emojiIndices.map { SAS_EMOJI[it].first },
            emojiNames = m.sas.emojiIndices.map { SAS_EMOJI[it].second },
            secondsLeft = ((m.deadlineMs - now).coerceAtLeast(0) / 1000).toInt(),
        )
        is Mode.Connected -> Screen.Status(
            statusView(state ?: ConnectionState(), m.bridge, net, keyStore.protection, entries, t, now)
        )
    }

    companion object {
        /** The countdown on Confirm code: the bridge gives its operator 120 s to approve. */
        const val APPROVAL_WINDOW_MS = 120_000L

        /** How long to wait for the decision, a little past the bridge's own limit. */
        const val DECISION_TIMEOUT_MS = 150_000L

        /** What people type for a short-code pairing: a host, host:port, or a wss:// URL. */
        fun bridgeUrl(address: String): String? {
            val t = address.trim().trimEnd('/')
            if (t.isEmpty()) return null
            val url = if ("://" in t) t else "wss://$t"
            return url.takeIf { isAllowedBridgeUrl(it) }
        }
    }
}
