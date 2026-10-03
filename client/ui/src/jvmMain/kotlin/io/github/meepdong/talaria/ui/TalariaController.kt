package io.github.meepdong.talaria.ui

import androidx.compose.ui.graphics.ImageBitmap
import io.github.meepdong.talaria.chat.ChatRepository
import io.github.meepdong.talaria.chat.ChatState
import io.github.meepdong.talaria.chat.FinishedReply
import io.github.meepdong.talaria.chat.MessageState
import io.github.meepdong.talaria.chat.ModelChoice
import io.github.meepdong.talaria.chat.OutgoingFile
import io.github.meepdong.talaria.chat.asChatApi
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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
    /** Decodes photo bytes for thumbnails; each platform has its own image codecs. */
    imageDecoder: (ByteArray) -> ImageBitmap? = { null },
    /** Reads replies aloud; null where the platform has no voice. */
    private val speechOutput: SpeechOutput? = null,
    private val prefs: Prefs = Prefs.Memory(),
) : TalariaActions {
    private sealed interface Mode {
        data class Connect(val name: String, val error: String? = null, val busy: Boolean = false) : Mode
        data class Confirm(val sas: Sas, val deadlineMs: Long) : Mode
        data class Connected(val bridge: PairedBridge, val client: TnpClient, val chat: ChatRepository) : Mode
    }

    /** Which page shows while paired. */
    private data class Page(val status: Boolean = false, val conversationOpen: Boolean = false)

    private val mode = MutableStateFlow<Mode>(Mode.Connect(defaultDeviceName))
    private val test = MutableStateFlow<TestView?>(null)
    private val network = MutableStateFlow<NetworkStatus?>(null)
    private val tick = MutableStateFlow(nowMs())
    private val page = MutableStateFlow(Page())
    private val images = ImageCache(imageDecoder)

    /** Photos and files picked for the next message. */
    private val pending = MutableStateFlow<List<OutgoingFile>>(emptyList())

    /**
     * Opens the platform's file picker, which hands its choice to [addAttachments]. Null while
     * the app can't pick files, which hides the 📎 button.
     */
    private val picker = MutableStateFlow<((photos: Boolean) -> Unit)?>(null)

    /** Speech-to-text, set while the app can listen (on Android, while it's on screen). */
    private val speechInput = MutableStateFlow<SpeechInput?>(null)
    private val voice = MutableStateFlow(
        VoiceView(readAloud = prefs.get(PREF_READ_ALOUD, false), autoSend = prefs.get(PREF_AUTO_SEND, false)),
    )
    private var dictations = 0L
    private var pairJob: Job? = null
    private var started = false

    private data class Live(val mode: Mode, val state: ConnectionState?, val chat: ChatState?)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val live = mode.flatMapLatest { m ->
        if (m is Mode.Connected) combine(m.client.state, m.chat.state) { st, c -> Live(m, st, c) } else flowOf(Live(m, null, null))
    }

    private data class Extras(
        val entries: List<ConnectionLog.Entry>, val net: NetworkStatus?, val test: TestView?, val page: Page,
        val pending: List<OutgoingFile>, val canAttach: Boolean, val voice: VoiceView,
    )

    private val extras = combine(
        combine(log.entries, network, test, page) { e, n, t, p -> Quad(e, n, t, p) }, pending, picker, voice, speechInput,
    ) { q, files, pick, v, input ->
        Extras(q.a, q.b, q.c, q.d, files, pick != null, v.copy(canDictate = input != null, canSpeak = speechOutput != null))
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    val screen: StateFlow<Screen> = combine(live, extras, tick) { l, x, now ->
        render(l, x, now)
    }.stateIn(scope, SharingStarted.Eagerly, Screen.Connect(defaultDeviceName))

    /** Every reply that finishes, for notifications. The apps decide whether one is needed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val replies: Flow<FinishedReply> = mode.flatMapLatest { m -> if (m is Mode.Connected) m.chat.replies else emptyFlow() }

    private val chat: ChatRepository? get() = (mode.value as? Mode.Connected)?.chat

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
            replies.collect { r ->
                if (r.fromThisDevice && r.state == MessageState.DONE && voice.value.readAloud) say(READ_ALOUD_KEY, r.text)
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
        speechInput.value?.stop()
        speechOutput?.stop()
        (mode.value as? Mode.Connected)?.let {
            it.chat.stop()
            it.client.stop()
        }
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
        current.chat.stop()
        current.client.stop()
        page.value = Page()
        test.value = null
        mode.value = Mode.Connect(current.bridge.deviceName)
        log.add(nowMs(), "Forgot the server", current.bridge.url)
        scope.launch(io) {
            runCatching { pairingStore.clear() }
            // A fresh key for the next pairing: the old one may be revoked or refused.
            runCatching { keyStore.delete() }
        }
    }

    // chat

    override fun openConversation(id: String) {
        chat?.open(id)
        page.value = Page(conversationOpen = true)
    }

    override fun newConversation() {
        chat?.newConversation()
        page.value = Page(conversationOpen = true)
    }

    override fun closeConversation() {
        page.value = Page()
        chat?.refresh()
    }

    override fun sendMessage(text: String) {
        val c = chat ?: return
        val command = Command.parse(text)
        if (command == null) {
            c.send(text, pending.value)
            pending.value = emptyList()
            return
        }
        when (command) {
            is Command.Model -> pickModelByName(c, command.query)
            Command.Retry -> c.retryLast()
            is Command.Queue -> if (command.text.isBlank() && pending.value.isEmpty()) {
                c.notice("Type the message after /queue")
            } else {
                c.send(command.text, pending.value)
                pending.value = emptyList()
            }
            is Command.Steer -> if (command.text.isBlank()) c.notice("Type the note after /steer") else c.steer(command.text)
            is Command.Aside -> if (command.text.isBlank()) c.notice("Type the question after /btw") else c.aside(command.text)
            Command.Status -> c.loadStatus()
            Command.Stop -> c.state.value.let { s ->
                (s.openSummary?.activeTurnId ?: s.openMessages.lastOrNull { it.state == MessageState.STREAMING }?.turnId)
                    ?.let(c::stop) ?: c.notice("Nothing is running")
            }
            Command.New -> newConversation()
            is Command.Unknown -> c.notice("Talaria doesn't know /${command.name}. Type / to see the commands it has.")
        }
    }

    /** /model name: pick it when the name matches one model, otherwise say what matched. */
    private fun pickModelByName(c: ChatRepository, query: String) {
        val options = c.state.value.models
        if (options == null) {
            c.loadModels()
            c.notice("The model list isn't loaded yet; try again in a moment")
            return
        }
        if (query.isBlank()) {
            c.notice("Pick a model from the chip at the top of the chat, or type /model and part of its name")
            return
        }
        val all = options.providers.flatMap { p -> p.models.map { p.id to it } }
        val q = query.trim().lowercase()
        val exact = all.filter { (_, m) -> m.lowercase() == q || m.substringAfterLast('/').lowercase() == q }
        val matches = exact.ifEmpty { all.filter { (_, m) -> q in m.lowercase() } }
        when {
            matches.size == 1 -> pickModel(matches[0].first, matches[0].second)
            matches.isEmpty() -> c.notice("No model matches \"$query\"")
            else -> c.notice("\"$query\" matches ${matches.size} models: " +
                matches.take(5).joinToString { it.second.substringAfterLast('/') } + if (matches.size > 5) "…" else "")
        }
    }

    override fun pickModel(provider: String, model: String) {
        chat?.pickModel(ModelChoice(provider, model))
    }

    override fun dismissAside(id: String) {
        val c = chat ?: return
        c.state.value.openId?.let { c.dismissAside(it, id) }
    }

    override fun closeStatus() {
        chat?.closeStatus()
    }

    override fun attachFiles(photos: Boolean) {
        picker.value?.invoke(photos)
    }

    override fun removeAttachment(index: Int) {
        pending.update { files -> files.filterIndexed { i, _ -> i != index } }
    }

    /** The platform's file picker: set while the app can show it, null otherwise. */
    fun setFilePicker(pick: ((photos: Boolean) -> Unit)?) {
        picker.value = pick
    }

    /**
     * Files the user picked, already prepared by the platform (photos downscaled, location
     * removed). [problem] says why some couldn't be added.
     */
    fun addAttachments(files: List<OutgoingFile>, problem: String? = null) {
        val (fit, tooBig) = files.partition { it.bytes.size <= OutgoingFile.MAX_SIZE }
        val room = OutgoingFile.MAX_PER_MESSAGE - pending.value.size
        (problem ?: tooBig.firstOrNull()?.let { "${it.name} is over 20 MB, too large to send" }
            ?: if (fit.size > room) "A message can carry ${OutgoingFile.MAX_PER_MESSAGE} files; the rest were left out" else null)
            ?.let { chat?.notice(it) }
        pending.update { (it + fit).take(OutgoingFile.MAX_PER_MESSAGE) }
    }

    /**
     * Files or text shared from another app (Android's share sheet): a new chat with them
     * ready to send, so the user can add a question first.
     */
    fun receiveShare(files: List<OutgoingFile>, text: String?, problem: String? = null) {
        chat?.newConversation()
        page.value = Page(conversationOpen = true)
        pending.value = emptyList()
        addAttachments(files, problem)
        val words = text?.trim().orEmpty()
        if (words.isNotEmpty()) {
            voice.update { it.copy(dictation = Dictation(++dictations, words.take(32000), send = false)) }
        }
    }

    /** The platform's speech-to-text: set while the app can listen, null otherwise. */
    fun setSpeechInput(input: SpeechInput?) {
        if (input == null) {
            speechInput.value?.stop()
            voice.update { it.copy(listening = false, heard = "") }
        }
        speechInput.value = input
    }

    override fun toggleDictation() {
        val input = speechInput.value ?: return
        if (voice.value.listening) {
            input.stop()
            return
        }
        stopSpeaking()
        voice.update { it.copy(listening = true, heard = "") }
        input.start(object : SpeechInput.Listener {
            override fun partial(text: String) {
                voice.update { if (it.listening) it.copy(heard = text) else it }
            }

            override fun done(text: String) {
                val heard = text.trim()
                voice.update { v ->
                    v.copy(listening = false, heard = "",
                        dictation = if (heard.isEmpty()) v.dictation else Dictation(++dictations, heard, v.autoSend))
                }
            }

            override fun failed(message: String) {
                voice.update { it.copy(listening = false, heard = "") }
                chat?.notice(message)
            }
        })
    }

    override fun dictationTaken(id: Long) {
        voice.update { if (it.dictation?.id == id) it.copy(dictation = null) else it }
    }

    override fun speak(key: String, text: String) {
        if (voice.value.speakingKey == key) stopSpeaking() else say(key, text)
    }

    private fun say(key: String, text: String) {
        val out = speechOutput ?: return
        val words = speakable(text)
        if (words.isEmpty()) return
        voice.update { it.copy(speakingKey = key) }
        out.speak(words) { voice.update { if (it.speakingKey == key) it.copy(speakingKey = null) else it } }
    }

    override fun stopSpeaking() {
        speechOutput?.stop()
        voice.update { it.copy(speakingKey = null) }
    }

    override fun setReadAloud(on: Boolean) {
        prefs.set(PREF_READ_ALOUD, on)
        voice.update { it.copy(readAloud = on) }
        if (!on && voice.value.speakingKey == READ_ALOUD_KEY) stopSpeaking()
    }

    override fun setAutoSend(on: Boolean) {
        prefs.set(PREF_AUTO_SEND, on)
        voice.update { it.copy(autoSend = on) }
    }

    override fun retryMessage(key: String) {
        chat?.retry(key)
    }

    override fun stopReply(turnId: String) {
        chat?.stop(turnId)
    }

    override fun loadOlder() {
        val c = chat ?: return
        c.state.value.openId?.let { c.loadOlder(it) }
    }

    override fun renameConversation(id: String, title: String) {
        if (title.isNotBlank()) chat?.rename(id, title)
    }

    override fun deleteConversation(id: String) {
        chat?.delete(id)
        if (chat?.state?.value?.openId == id) page.value = Page()
    }

    override fun dismissNotice() {
        chat?.dismissNotice()
    }

    override fun showStatus() {
        chat?.loadBalance()
        page.value = page.value.copy(status = true)
    }

    override fun showChats() {
        page.value = page.value.copy(status = false)
    }

    /** A reply typed into a notification, sent without opening the app. */
    fun replyFromNotification(conversationId: String, text: String) {
        chat?.send(text, conversationId = conversationId)
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
        val chat = ChatRepository(scope, client.asChatApi())
        test.value = null
        page.value = Page()
        chat.start()
        mode.value = Mode.Connected(bridge, client, chat)
        client.start()
    }

    private fun render(l: Live, x: Extras, now: Long): Screen = when (val m = l.mode) {
        is Mode.Connect -> Screen.Connect(m.name, m.error, m.busy)
        is Mode.Confirm -> Screen.Confirm(
            digits = "${m.sas.digits.take(3)} ${m.sas.digits.drop(3)}",
            emoji = m.sas.emojiIndices.map { SAS_EMOJI[it].first },
            emojiNames = m.sas.emojiIndices.map { SAS_EMOJI[it].second },
            secondsLeft = ((m.deadlineMs - now).coerceAtLeast(0) / 1000).toInt(),
        )
        is Mode.Connected -> {
            val state = l.state ?: ConnectionState()
            val status = statusView(state, m.bridge, x.net, keyStore.protection, x.entries, x.test, now)
            if (x.page.status) {
                Screen.Status(status.copy(canGoBack = true, balances = balanceItems(l.chat?.balances.orEmpty())))
            } else {
                Screen.Chat(chatView(l.chat ?: ChatState(), x.page.conversationOpen,
                    state.phase == ConnectionState.Phase.CONNECTED, status, now, x.pending, x.canAttach, images)
                    .copy(voice = x.voice), status.copy(balances = balanceItems(l.chat?.balances.orEmpty())))
            }
        }
    }

    companion object {
        /** The countdown on Confirm code: the bridge gives its operator 120 s to approve. */
        const val APPROVAL_WINDOW_MS = 120_000L

        /** How long to wait for the decision, a little past the bridge's own limit. */
        const val DECISION_TIMEOUT_MS = 150_000L

        const val PREF_READ_ALOUD = "voice.read_aloud"
        const val PREF_AUTO_SEND = "voice.auto_send"

        /** [VoiceView.speakingKey] while a reply is read aloud on its own. */
        const val READ_ALOUD_KEY = "read-aloud"

        /** What people type for a short-code pairing: a host, host:port, or a wss:// URL. */
        fun bridgeUrl(address: String): String? {
            val t = address.trim().trimEnd('/')
            if (t.isEmpty()) return null
            val url = if ("://" in t) t else "wss://$t"
            return url.takeIf { isAllowedBridgeUrl(it) }
        }
    }
}
