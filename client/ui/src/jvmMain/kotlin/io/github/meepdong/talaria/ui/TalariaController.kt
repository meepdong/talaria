package io.github.meepdong.talaria.ui

import androidx.compose.ui.graphics.ImageBitmap
import io.github.meepdong.talaria.chat.ChatRepository
import io.github.meepdong.talaria.chat.ChatState
import io.github.meepdong.talaria.chat.FinishedReply
import io.github.meepdong.talaria.chat.MessageState
import io.github.meepdong.talaria.chat.ModelChoice
import io.github.meepdong.talaria.chat.OutgoingFile
import io.github.meepdong.talaria.chat.Role
import io.github.meepdong.talaria.chat.ServerFile
import io.github.meepdong.talaria.chat.VoiceApi
import io.github.meepdong.talaria.ops.OpsRepository
import io.github.meepdong.talaria.ops.OpsState
import io.github.meepdong.talaria.files.FilesRepository
import io.github.meepdong.talaria.files.FilesState
import io.github.meepdong.talaria.schedule.AutomationRan
import io.github.meepdong.talaria.schedule.ScheduleRepository
import io.github.meepdong.talaria.schedule.ScheduleState
import io.github.meepdong.talaria.schedule.When
import io.github.meepdong.talaria.updates.UpdateRepository
import io.github.meepdong.talaria.updates.UpdateState
import io.github.meepdong.talaria.terminal.TermKey
import io.github.meepdong.talaria.terminal.TerminalRepository
import io.github.meepdong.talaria.terminal.TerminalState
import io.github.meepdong.talaria.todos.TodosRepository
import io.github.meepdong.talaria.todos.TodosState
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
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
        data class Connected(
            val bridge: PairedBridge, val client: TnpClient, val chat: ChatRepository, val files: FilesRepository,
            val todos: TodosRepository, val schedule: ScheduleRepository, val ops: OpsRepository,
            val updates: UpdateRepository, val terminals: TerminalRepository,
            val voice: VoiceApi = VoiceApi(client.asChatApi()),
        ) : Mode
    }

    /** Which page shows while paired. */
    private data class Page(
        val status: Boolean = false,
        /** The Server page, from the ☰ menu. */
        val server: Boolean = false,
        /** The Terminals page, from the ☰ menu (§16.1). */
        val terminal: Boolean = false,
        val conversationOpen: Boolean = false,
        val tab: Tab = Tab.HOME,
        val menuOpen: Boolean = false,
        val modelQuery: String? = null,
        /** Home's tiles are being rearranged (long-press on a tile's title). */
        val arrangingHome: Boolean = false,
        /** From [swipes]: what the Undo bar says, and to-dos waiting out their Undo before they're deleted. */
        val undo: String? = null,
        val hiddenTodos: Set<String> = emptySet(),
    )

    /** The last swipe that can be taken back (UX1). Kept apart from [page], which opening a chat replaces. */
    private data class Swipes(val label: String? = null, val hiddenTodos: Set<String> = emptySet())
    private val swipes = MutableStateFlow(Swipes())
    private var undoAction: (() -> Unit)? = null
    private var undoCommit: (() -> Unit)? = null
    private var undoJob: Job? = null

    private val mode = MutableStateFlow<Mode>(Mode.Connect(defaultDeviceName))
    private val test = MutableStateFlow<TestView?>(null)
    private val network = MutableStateFlow<NetworkStatus?>(null)
    private val tick = MutableStateFlow(nowMs())
    private val page = MutableStateFlow(Page())
    /** Home's tile order on this device; outside [Page], which opening a chat resets. */
    private val homeOrder = MutableStateFlow(homeOrder(prefs.getString(PREF_HOME_ORDER, "")))
    private val images = ImageCache(imageDecoder)

    /** Photos and files picked for the next message. */
    private val pending = MutableStateFlow<List<OutgoingFile>>(emptyList())

    /** Files already on the server (Files page, §12) attached to the next message. */
    private val pendingServer = MutableStateFlow<List<ServerFile>>(emptyList())

    /** A server file being fetched to open, and why the last one couldn't be. */
    private data class FileTask(val opening: String? = null, val notice: String? = null, val progress: Float? = null)
    private val fileTask = MutableStateFlow(FileTask())

    /** Opens a fetched file with the device's own app; set by the platform. */
    private var fileOpener: FileOpener? = null
    @Volatile private var installer: AppUpdater? = null
    /** The owner's fingerprint or screen lock for terminal approvals; null where the device has none to ask (desktop). */
    @Volatile private var authenticator: Authenticator? = null
    private val terminalLock = TerminalLock(nowMs)
    private var textSharer: ((String) -> Unit)? = null

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

    /** Talk is on (Talk 2): listening, waiting for Hermes, or speaking its reply, then listening again. */
    @Volatile private var talkOn = false
    private var talkLoop: TalkLoop? = null
    private var talkFollow: Job? = null

    /** Talk's natural voice (Talk 2): speech from the bridge, played here; null where the app can't play audio. */
    @Volatile private var talkVoice: SpeechOutput? = null

    /** The platform's audio player, for Talk's natural voice; without one Talk uses the device's own voice. */
    fun setAudioPlayer(player: AudioPlayer?) {
        talkVoice = player?.let {
            // no fallback to the device's own voice: the owner would rather miss a piece than hear it robotic
            CloudSpeech(scope, fetch = { text -> (mode.value as? Mode.Connected)?.voice?.speech(text) }, player = it,
                fallback = null)
        }
    }
    private var pairJob: Job? = null
    private var started = false

    private data class Live(
        val mode: Mode, val state: ConnectionState?, val chat: ChatState?, val files: FilesState? = null,
        val todos: TodosState? = null, val schedule: ScheduleState? = null, val ops: OpsState? = null,
        val updates: UpdateState? = null, val terminal: TerminalState? = null,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val live = mode.flatMapLatest { m ->
        if (m is Mode.Connected) {
            combine(
                combine(m.client.state, m.chat.state, m.files.state) { st, c, f -> Triple(st, c, f) },
                m.todos.state, m.schedule.state,
                combine(m.ops.state, m.updates.state, m.terminals.state) { o, u, tm -> Triple(o, u, tm) },
            ) { (st, c, f), t, sc, (o, u, tm) -> Live(m, st, c, f, t, sc, o, u, tm) }
        } else {
            flowOf(Live(m, null, null))
        }
    }

    private data class Extras(
        val entries: List<ConnectionLog.Entry>, val net: NetworkStatus?, val test: TestView?, val page: Page,
        val pending: List<OutgoingFile>, val canAttach: Boolean, val voice: VoiceView,
        val serverPending: List<ServerFile> = emptyList(), val fileTask: FileTask = FileTask(), val canShare: Boolean = false,
        val homeOrder: List<HomeTile> = HomeTile.entries,
    )

    private val extras = combine(
        combine(log.entries, network, test, combine(page, swipes) { p, w -> p.copy(undo = w.label, hiddenTodos = w.hiddenTodos) }) { e, n, t, p -> Quad(e, n, t, p) },
        combine(pending, pendingServer, fileTask, homeOrder) { a, b, c, d -> Quad(a, b, c, d) }, picker, voice, speechInput,
    ) { q, files, pick, v, input ->
        Extras(q.a, q.b, q.c, q.d, files.a, pick != null, v.copy(canDictate = input != null, canSpeak = speechOutput != null),
            files.b, files.c, canShare = textSharer != null, homeOrder = files.d)
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    val screen: StateFlow<Screen> = combine(live, extras, tick) { l, x, now ->
        render(l, x, now)
    }.stateIn(scope, SharingStarted.Eagerly, Screen.Connect(defaultDeviceName))

    /** Every reply that finishes, for notifications. The apps decide whether one is needed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val replies: Flow<FinishedReply> = mode.flatMapLatest { m -> if (m is Mode.Connected) m.chat.replies else emptyFlow() }

    private val chat: ChatRepository? get() = (mode.value as? Mode.Connected)?.chat
    private val files: FilesRepository? get() = (mode.value as? Mode.Connected)?.files
    private val todos: TodosRepository? get() = (mode.value as? Mode.Connected)?.todos
    private val schedule: ScheduleRepository? get() = (mode.value as? Mode.Connected)?.schedule
    private val ops: OpsRepository? get() = (mode.value as? Mode.Connected)?.ops
    private val updates: UpdateRepository? get() = (mode.value as? Mode.Connected)?.updates
    private val terminals: TerminalRepository? get() = (mode.value as? Mode.Connected)?.terminals

    /** Server operations waiting for approval, for a notification each (PROTOCOL §10.8). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val opsPending: Flow<List<OpsApprovalItem>> = mode.flatMapLatest { m ->
        if (m is Mode.Connected) m.ops.state.map { opsApprovals(it, m.bridge.deviceId) }.distinctUntilChanged()
        else flowOf(emptyList())
    }

    /** Automation runs that report to Home or were blocked, for a notification; a run for a chat refreshes the list. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val automationResults: Flow<AutomationRan> = mode.flatMapLatest { m ->
        if (m is Mode.Connected) {
            m.schedule.ran.onEach { if (it.run.conversationId != null) m.chat.refresh() }.filter { it.forHome }
        } else {
            emptyFlow()
        }
    }

    /**
     * Home's results that still want attention, as "id@at", once Home has loaded: a notification for anything else
     * (read or archived on any device) is cleared.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val homeUnread: Flow<Set<String>> = mode.flatMapLatest { m ->
        if (m is Mode.Connected) {
            m.schedule.state.filter { it.homeLoaded }.map { s -> s.today.filter { !it.read }.map { "${it.id}@${it.run.at}" }.toSet() }
                .distinctUntilChanged()
        } else {
            emptyFlow()
        }
    }

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
            // Chat with Hermes and Talk come back to the chat last open here, even after a restart
            mode.flatMapLatest { m -> if (m is Mode.Connected) m.chat.state.map { it.openId } else emptyFlow() }
                .filterNotNull().distinctUntilChanged().collect { prefs.setString(PREF_LAST_CHAT, it) }
        }
        scope.launch {
            replies.collect { r ->
                // Talk speaks its own replies as they stream; Read aloud is for typed messages
                if (r.fromThisDevice && r.state == MessageState.DONE && voice.value.readAloud && !talkOn) {
                    say(READ_ALOUD_KEY, r.text)
                }
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
            it.todos.stop()
            it.schedule.stop()
            it.ops.stop()
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
        current.todos.stop()
        current.schedule.stop()
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
        page.value = Page(conversationOpen = true, tab = Tab.CHATS)
    }

    override fun newConversation() {
        chat?.newConversation()
        page.value = Page(conversationOpen = true, tab = Tab.CHATS)
    }

    override fun closeConversation() {
        page.value = Page(tab = Tab.CHATS)
        chat?.refresh()
    }

    override fun selectTab(tab: Tab) {
        page.update { it.copy(tab = tab, status = false, menuOpen = false, arrangingHome = false) }
        if (tab == Tab.HOME || tab == Tab.CHATS) chat?.refresh()
        if (tab == Tab.TODOS) todos?.refresh()
        if (tab == Tab.FILES) files?.load()
        if (tab == Tab.HOME || tab == Tab.SCHEDULE) schedule?.refresh()
    }

    override fun setMenuOpen(open: Boolean) {
        if (open) chat?.loadBalance()
        if (open) chat?.loadModels()  // the default model picker
        page.update { it.copy(menuOpen = open) }
    }

    override fun startChat() {
        resumeChat()
    }

    /** The chat last open on this device, if it's still there; else a new one. */
    private fun resumeChat(maxAgeS: Long? = null) {
        val c = chat ?: return newConversation()
        val s = c.state.value
        val id = s.openId ?: prefs.getString(PREF_LAST_CHAT, "").takeIf { it.isNotEmpty() }
        val last = s.conversations.firstOrNull { it.id == id }
        val recent = maxAgeS == null || (last != null && nowMs() / 1000 - last.updatedAt <= maxAgeS)
        if (last != null && recent) openConversation(last.id) else newConversation()
    }

    // swipes and Undo (UX1)

    /** Show Undo for a few seconds: [undo] takes the swipe back; [commit] runs once it's too late to (if anything waits). */
    private fun offerUndo(label: String, undo: () -> Unit, commit: () -> Unit = {}) {
        undoJob?.cancel()
        finishUndo()  // the swipe before stands
        undoAction = undo
        undoCommit = commit
        swipes.update { it.copy(label = label) }
        undoJob = scope.launch {
            delay(UNDO_MS)
            finishUndo()
        }
    }

    private fun finishUndo() {
        val commit = undoCommit
        undoAction = null
        undoCommit = null
        swipes.update { it.copy(label = null) }
        commit?.invoke()
    }

    override fun undo() {
        undoJob?.cancel()
        val action = undoAction
        undoAction = null
        undoCommit = null
        swipes.update { it.copy(label = null) }
        action?.invoke()
    }

    override fun markHomeRead(id: String, at: Long, read: Boolean) {
        schedule?.markHomeRead(id, at, read)
    }

    override fun markAllHomeRead() {
        val s = schedule ?: return
        s.state.value.today.filter { !it.read }.forEach { s.markHomeRead(it.id, it.run.at, true) }
    }

    override fun archiveReadHome() {
        val s = schedule ?: return
        val read = s.state.value.today.filter { it.read }
        if (read.isEmpty()) return
        read.forEach { s.dismissHomeItem(it.id, it.run.at) }
        offerUndo(if (read.size == 1) "Archived 1 result" else "Archived ${read.size} results",
            undo = { read.forEach { s.restoreHomeItem(it.id, it.run.at) } })
    }

    override fun showArchivedHome(open: Boolean) {
        if (open) schedule?.loadArchived() else schedule?.closeArchived()
    }

    override fun restoreHomeItem(id: String, at: Long) {
        schedule?.restoreHomeItem(id, at)
    }

    override fun askAboutResult(id: String, at: Long) {
        val s = schedule ?: return
        val st = s.state.value
        val r = (st.today + st.archived.orEmpty()).firstOrNull { it.id == id && it.run.at == at } ?: return
        if (!r.read) s.markHomeRead(id, at, true)
        s.closeArchived()
        val own = r.run.conversationId?.takeIf { c -> chat?.state?.value?.conversations?.any { it.id == c } == true }
        if (own != null) openConversation(own) else newConversation()
        voice.update { it.copy(dictation = Dictation(++dictations, aboutResult(r, java.time.ZoneId.systemDefault()), send = false)) }
    }

    override fun askAboutServerResult(requestId: String) {
        val m = mode.value as? Mode.Connected ?: return
        val r = opsResults(m.ops.state.value, m.bridge.deviceId).firstOrNull { it.requestId == requestId } ?: return
        page.update { it.copy(server = false, terminal = false) }
        newConversation()
        voice.update { it.copy(dictation = Dictation(++dictations, aboutServerResult(r), send = false)) }
    }

    override fun archiveConversation(id: String, archived: Boolean) {
        val c = chat ?: return
        c.archive(id, archived)
        if (archived) offerUndo("Chat archived", undo = { c.archive(id, false) })
    }

    /**
     * The Talk button (Talk 2): a spoken conversation in the chat last open here. Hermes's reply is spoken as it
     * streams, then the mic opens again; saying nothing ends it. While listening, a tap sends what was heard;
     * while Hermes thinks or speaks, a tap interrupts (stops the reply) and listens.
     */
    override fun talk() {
        if (speechInput.value == null) {
            resumeChat()
            chat?.notice("This device can't take dictation, so type your message")
            return
        }
        when (voice.value.talk) {
            null -> {
                // on the Chats page, Talk is in the chat on screen, a fresh one too (it has no id until its first
                // message); from Home and elsewhere, in the chat last open here (compression keeps it small)
                if (!talksInChatOnScreen(page.value.tab)) resumeChat()
                talkOn = true
                listenForTalk()
            }
            TalkPhase.LISTENING, TalkPhase.APPROVING -> speechInput.value?.stop()
            else -> interruptTalk()
        }
    }

    override fun endTalk() {
        talkOn = false
        talkFollow?.cancel()
        talkLoop?.stop()
        talkLoop = null
        if (voice.value.listening) speechInput.value?.stop()
        voice.update { it.copy(talk = null, listening = false, heard = "") }
    }

    private fun interruptTalk() {
        talkFollow?.cancel()
        talkLoop?.stop()
        talkLoop = null
        chat?.state?.value?.openSummary?.activeTurnId?.let { chat?.stop(it) }
        listenForTalk()
    }

    private fun listenForTalk() {
        val input = speechInput.value ?: return endTalk()
        stopSpeaking()
        voice.update { it.copy(listening = true, heard = "", talk = TalkPhase.LISTENING) }
        input.start(object : SpeechInput.Listener {
            override fun partial(text: String) {
                voice.update { if (it.listening) it.copy(heard = text) else it }
            }

            override fun done(text: String) {
                val heard = text.trim()
                voice.update { it.copy(listening = false, heard = "") }
                if (!talkOn) return
                if (heard.isEmpty()) endTalk() else talkSend(heard)
            }

            override fun failed(message: String) {
                endTalk()
                chat?.notice(message)
            }
        })
    }

    /** Send what was said, then speak the reply to it as it streams and listen again once it's said. */
    private fun talkSend(text: String) {
        val c = chat ?: return endTalk()
        val conversationId = c.state.value.openId
        // 🎙 tells Hermes it's spoken (SOUL.md: say what you're doing, answer briefly), and shows it in the chat
        val cmid = c.send("$SPOKEN $text") ?: return endTalk()
        val out = talkVoice ?: speechOutput ?: return endTalk()  // nothing to speak with: one message, as dictation
        voice.update { it.copy(talk = TalkPhase.THINKING) }
        val loop = TalkLoop(out, onSpeak = { voice.update { if (it.talk != null) it.copy(talk = TalkPhase.SPEAKING) else it } }) {
            scope.launch { if (talkOn) listenForTalk() }
        }
        talkLoop = loop
        talkFollow?.cancel()
        talkFollow = scope.launch {
            // a quick line from a fast model while Hermes starts, and "still on it" while its tools run
            launch {
                // the quick line only when Hermes is slow to start: not said if its answer has begun by then
                val sentAt = nowMs()
                val line = (mode.value as? Mode.Connected)?.voice?.ack(text, conversationId) ?: return@launch
                delay((QUICK_LINE_AFTER_MS - (nowMs() - sentAt)).coerceAtLeast(0))
                loop.opening(line)
            }
            launch {
                while (true) {
                    delay(NUDGE_TICK_MS)
                    loop.nudge(nowMs())
                }
            }
            var askedApproval = false
            c.state.map { talkReply(it, cmid) }
                .filterNotNull().distinctUntilChanged().collect { m ->
                    // an approval: Talk says what it's for in its own voice and takes a yes or no (or a tap on screen),
                    // waits meanwhile, and carries on after
                    loop.hold(m.waitingForApproval)
                    if (m.waitingForApproval && !askedApproval) {
                        askedApproval = true
                        val turn = m.turnId
                        loop.note(approvalLine(m.approval?.description ?: m.approval?.command)) {
                            if (turn != null) scope.launch { listenForApproval(turn, loop) }
                        }
                    }
                    if (!m.waitingForApproval && approvingTurn != null) {
                        approvingTurn = null  // answered on screen: what's heard now doesn't count
                        speechInput.value?.stop()
                        voice.update { if (it.talk == TalkPhase.APPROVING) it.copy(talk = TalkPhase.THINKING) else it }
                    }
                    val over = m.state == MessageState.DONE || m.state == MessageState.FAILED || m.state == MessageState.CANCELLED
                    val text = if (m.state == MessageState.FAILED && m.text.isBlank()) "Sorry, that didn't go through." else m.text
                    loop.update(text, m.tools, over)
                }
        }
    }

    /** The turn whose approval Talk is listening for, or null. */
    @Volatile private var approvingTurn: String? = null

    /** Listen for a yes (allow once) or a no (deny). Never "always": that stays a choice made on screen. */
    private fun listenForApproval(turnId: String, loop: TalkLoop) {
        val input = speechInput.value ?: return
        if (!talkOn) return
        approvingTurn = turnId
        voice.update { it.copy(listening = true, heard = "", talk = TalkPhase.APPROVING) }
        input.start(object : SpeechInput.Listener {
            override fun partial(text: String) {
                voice.update { if (it.listening) it.copy(heard = text) else it }
            }

            override fun done(text: String) {
                voice.update { it.copy(listening = false, heard = "", talk = if (it.talk != null) TalkPhase.THINKING else null) }
                if (approvingTurn != turnId) return
                approvingTurn = null
                when (approvalAnswer(text)) {
                    "once" -> {
                        chat?.approve(turnId, "once")
                        loop.note("Okay, going ahead.")
                    }
                    "deny" -> {
                        chat?.approve(turnId, "deny")
                        loop.note("Okay, I won't.")
                    }
                    else -> loop.note("I didn't catch a yes or no. It's on screen.")
                }
            }

            override fun failed(message: String) {
                approvingTurn = null
                voice.update { it.copy(listening = false, heard = "", talk = if (it.talk != null) TalkPhase.THINKING else null) }
            }
        })
    }

    override fun sendMessage(text: String) {
        val c = chat ?: return
        val command = Command.parse(text)
        if (command == null) {
            c.send(text, pending.value, serverFiles = pendingServer.value)
            pending.value = emptyList()
            pendingServer.value = emptyList()
            return
        }
        when (command) {
            is Command.Model -> pickModelByName(c, command.query)
            Command.Retry -> c.retryLast()
            is Command.Queue -> if (command.text.isBlank() && pending.value.isEmpty() && pendingServer.value.isEmpty()) {
                c.notice("Type the message after /queue")
            } else {
                c.send(command.text, pending.value, serverFiles = pendingServer.value)
                pending.value = emptyList()
                pendingServer.value = emptyList()
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
            openModelPicker()
            return
        }
        val all = options.providers.flatMap { p -> p.models.map { p.id to it } }
        val q = query.trim().lowercase()
        val exact = all.filter { (_, m) -> m.lowercase() == q || m.substringAfterLast('/').lowercase() == q }
        val matches = exact.ifEmpty { all.filter { (_, m) -> q in m.lowercase() } }
        when {
            matches.size == 1 -> pickModel(matches[0].first, matches[0].second)
            matches.isEmpty() -> c.notice("No model matches \"$query\"")
            else -> openModelPicker(query.trim())
        }
    }

    override fun pickModel(provider: String, model: String) {
        page.update { it.copy(modelQuery = null) }
        chat?.pickModel(ModelChoice(provider, model))
    }

    override fun openModelPicker(query: String) {
        chat?.let { if (it.state.value.models == null) it.loadModels() }
        page.update { it.copy(modelQuery = query) }
    }

    override fun closeModelPicker() {
        page.update { it.copy(modelQuery = null) }
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
        val local = pending.value.size
        if (index < local) {
            pending.update { files -> files.filterIndexed { i, _ -> i != index } }
        } else {
            pendingServer.update { files -> files.filterIndexed { i, _ -> i != index - local } }
        }
    }

    // Files (§12)

    /** How the platform opens a fetched server file (a temp copy and the system's viewer). */
    fun setFileOpener(open: FileOpener?) {
        fileOpener = open
    }

    override fun openRoot(id: String) {
        fileTask.value = FileTask()
        files?.list(id)
    }

    override fun filesUp() {
        fileTask.value = FileTask()
        files?.up()
    }

    override fun searchFiles(query: String) {
        val f = files ?: return
        fileTask.value = FileTask()
        if (query.isBlank()) f.state.value.root?.let { f.list(it, f.state.value.path) } else f.search(query)
    }

    override fun openFile(path: String) {
        val f = files ?: return
        val entry = f.state.value.entries.firstOrNull { it.path == path } ?: return
        if (entry.folder) {
            fileTask.value = FileTask()
            f.open(entry)
            return
        }
        val root = f.state.value.root ?: return
        download(root, path, entry.name, entry.mime ?: "application/octet-stream")
    }

    override fun openAttachment(root: String, path: String, name: String, mime: String) {
        download(root, path, name, mime)
    }

    /** Fetch a file to a file of its own, a chunk at a time (up to 2 GB), then open it; [FileTask] shows how far it got. */
    private fun download(root: String, path: String, name: String, mime: String) {
        val f = files ?: return
        val opener = fileOpener ?: run {
            fileTask.value = FileTask(notice = "This device can't open files from here yet")
            chat?.notice("This device can't open files yet")
            return
        }
        if (fileTask.value.opening != null) return
        fileTask.value = FileTask(opening = path, progress = 0f)
        scope.launch(io) {
            val notice = try {
                val target = opener.target(name)
                try {
                    target.outputStream().buffered().use { out ->
                        f.readTo(root, path, out) { p -> fileTask.update { it.copy(progress = p) } }
                    }
                } catch (e: Exception) {
                    target.delete()
                    throw e
                }
                opener.open(target, mime)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't open $name: ${e.message ?: e::class.simpleName}"
            }
            fileTask.value = FileTask(notice = notice)
            notice?.let { chat?.notice(it) }
        }
    }

    override fun askAboutFile(path: String) {
        val f = files ?: return
        val file = f.state.value.entries.firstOrNull { it.path == path }?.let(f::serverFile) ?: return
        newConversation()
        pending.value = emptyList()
        pendingServer.value = listOf(file)
    }

    // To-dos (§13)

    override fun addTodo(text: String) {
        todos?.add(text)
    }

    override fun setTodoDone(id: String, done: Boolean) {
        todos?.setDone(id, done)
    }

    override fun deleteTodo(id: String) {
        val t = todos ?: return
        // gone from the list now, deleted once Undo has passed
        swipes.update { it.copy(hiddenTodos = it.hiddenTodos + id) }
        offerUndo("To-do deleted",
            undo = { swipes.update { it.copy(hiddenTodos = it.hiddenTodos - id) } },
            commit = {
                t.delete(id)
                swipes.update { it.copy(hiddenTodos = it.hiddenTodos - id) }
            })
    }

    override fun editTodo(id: String, text: String) {
        todos?.edit(id, text = text)
    }

    override fun setTodoDue(id: String, due: String?) {
        val date = due?.let { dueFromChoice(it, java.time.LocalDate.now()) }
        if (date == null) todos?.edit(id, clearDue = true) else todos?.edit(id, due = date)
    }

    override fun setTodoGroup(id: String, group: String?) {
        todos?.setGroup(id, group)
    }

    override fun commentOnTodo(id: String, text: String) {
        todos?.comment(id, text)
    }

    override fun deleteTodoComment(id: String, commentId: String) {
        todos?.uncomment(id, commentId)
    }

    override fun regroupTodos() {
        todos?.regroup()
    }

    override fun setDefaultModel(provider: String?, model: String?) {
        chat?.setDefaultModel(if (provider != null && model != null) ModelChoice(provider, model) else null)
    }

    override fun handTodoToAgent(id: String) {
        val c = chat ?: return
        val todo = todos?.state?.value?.todos?.firstOrNull { it.id == id } ?: return
        newConversation()
        c.send(handOver(todo.text, todo.comments.map { (if (it.byAgent) "Hermes" else "Me") + ": " + it.text }, todo.due, todo.group),
            conversationId = null, todoId = todo.id)
    }

    // Automations (§14)

    override fun addAutomation(draft: AutomationDraft) {
        val s = schedule ?: return
        if (!draft.ready) return
        val w = when (draft.kind) {
            WhenKind.TIME -> When.Time(draft.schedule)
            WhenKind.ARRIVES -> When.Arrives(draft.watch, draft.from, draft.until, draft.days.toList(), draft.fallback.ifBlank { null })
            WhenKind.AFTER_EVENT -> When.AfterEvent(draft.event, draft.delayMinutes, draft.days.toList())
        }
        s.add(draft.name, w, draft.task, draft.resultTo)
    }

    override fun describeAutomation(text: String) {
        schedule?.describe(text)
    }

    override fun clearDescribeReply() {
        schedule?.clearReply()
    }

    override fun setAutomationPaused(id: String, paused: Boolean) {
        schedule?.setPaused(id, paused)
    }

    override fun setAutomationResultTo(id: String, resultTo: String) {
        schedule?.setResultTo(id, resultTo)
    }

    override fun runAutomation(id: String) {
        schedule?.runNow(id)
    }

    override fun runAutomationInChat(id: String) {
        val s = schedule ?: return
        s.runInChat(id) { conversation ->
            chat?.refresh()
            openConversation(conversation)
        }
    }

    override fun deleteAutomation(id: String) {
        schedule?.delete(id)
    }

    override fun dismissHomeItem(id: String, at: Long) {
        val s = schedule ?: return
        s.dismissHomeItem(id, at)
        offerUndo("Archived", undo = { s.restoreHomeItem(id, at) })
    }

    override fun startArrangingHome() {
        page.update { it.copy(arrangingHome = true) }
    }

    override fun moveHomeTile(tile: HomeTile, up: Boolean) {
        val order = homeOrder.updateAndGet { it.moved(tile, up) }
        prefs.setString(PREF_HOME_ORDER, order.joinToString(",") { it.name })
    }

    override fun doneArrangingHome() {
        page.update { it.copy(arrangingHome = false) }
    }

    override fun showTerminals() {
        page.update { it.copy(terminal = true, server = false, status = false, menuOpen = false) }
        terminals?.refresh()
    }

    override fun refreshTerminals() {
        terminals?.refresh()
    }

    override fun openTerminal(session: String) {
        terminals?.open(session)
    }

    override fun newTerminal(name: String, folder: String, command: String) {
        terminals?.create(name.trim(), folder.trim().ifEmpty { "/root" }, command.trim())
    }

    override fun endTerminal(session: String) {
        terminals?.end(session)
    }

    override fun terminalHistory() {
        terminals?.history()
    }

    override fun unlockTerminal() {
        val auth = authenticator ?: return
        auth.authenticate("Unlock the terminal") { ok -> if (ok) terminalLock.unlock() }
    }

    /** How this platform asks for the owner's fingerprint or screen lock; null where it can't (desktop). */
    fun setAuthenticator(auth: Authenticator?) {
        authenticator = auth
    }

    /** The app came to the front, or left it (the 5-minute grace for terminals counts from leaving). */
    fun setForeground(visible: Boolean) {
        terminalLock.foreground(visible)
    }

    override fun closeTerminal() {
        terminals?.close()
        terminals?.refresh()
    }

    override fun terminalTakeControl() {
        val t = terminals ?: return
        t.state.value.session?.let { t.open(it, control = true) }
    }

    override fun terminalKey(key: String) {
        terminals?.keys(listOf(TermKey.Key(key)))
    }

    override fun terminalText(text: String, enter: Boolean) {
        terminals?.keys(listOfNotNull(TermKey.Text(text), TermKey.Key("Enter").takeIf { enter }))
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
        val (fit, tooBig) = files.partition { it.size <= OutgoingFile.MAX_SIZE }
        val room = OutgoingFile.MAX_PER_MESSAGE - pending.value.size
        (problem ?: tooBig.firstOrNull()?.let { "${it.name} is over 2 GB, too large to send" }
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
        page.value = Page(conversationOpen = true, tab = Tab.CHATS)
        pending.value = emptyList()
        pendingServer.value = emptyList()
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
        val out = talkVoice ?: speechOutput ?: return  // the natural voice, as in Talk, where the app can play it
        val words = speakable(text)
        if (words.isEmpty()) return
        voice.update { it.copy(speakingKey = key) }
        out.speak(words) { voice.update { if (it.speakingKey == key) it.copy(speakingKey = null) else it } }
    }

    override fun stopSpeaking() {
        talkVoice?.stop()
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

    override fun approve(turnId: String, choice: String) {
        chat?.approve(turnId, choice)
    }

    override fun opsApprove(requestId: String, choice: String) {
        val op = ops?.state?.value?.pending?.firstOrNull { it.requestId == requestId }?.op
        val auth = authenticator
        if (choice == "once" && op in GUARDED && auth != null && !terminalLock.unlocked) {
            // a root terminal from this phone: its owner's fingerprint or screen lock first (owner, 2026-10-05)
            auth.authenticate("Approve: open a root terminal") { ok ->
                if (ok) {
                    terminalLock.unlock()
                    approveOps(requestId, choice)
                }
            }
            return
        }
        approveOps(requestId, choice)
    }

    private fun approveOps(requestId: String, choice: String) {
        ops?.approve(requestId, choice)
    }

    override fun opsDismiss(requestId: String) {
        ops?.dismissResult(requestId)
    }

    override fun showServer() {
        page.update { it.copy(server = true, terminal = false, status = false, menuOpen = false) }
        ops?.refresh()
    }

    override fun refreshServer() {
        ops?.refresh()
    }

    override fun serverRun(op: String, params: Map<String, String>) {
        val o = ops ?: return
        val types = o.state.value.catalogue.firstOrNull { it.op == op }?.params.orEmpty()
        o.run(op, params.mapValues { (k, v) -> if (types[k]?.type == "integer") v.toIntOrNull() ?: v else v })
    }

    override fun dismissServerError() {
        ops?.dismissError()
    }

    override fun loadOlder() {
        val c = chat ?: return
        c.state.value.openId?.let { c.loadOlder(it) }
    }

    override fun renameConversation(id: String, title: String) {
        if (title.isNotBlank()) chat?.rename(id, title)
    }

    override fun pinConversation(id: String, pinned: Boolean) {
        chat?.pin(id, pinned)
    }

    override fun deleteMessages(keys: List<String>) {
        val c = chat ?: return
        c.hide(c.state.value.openId ?: return, keys)
    }

    override fun moveMessages(keys: List<String>, to: String?) {
        val c = chat ?: return
        val s = c.state.value
        val from = s.openId ?: return
        val moving = s.openMessages.filter { it.key in keys && it.text.isNotBlank() }
        if (moving.isEmpty()) return
        val quote = moving.joinToString("\n\n") { m ->
            (if (m.role == Role.USER) "I wrote" else "Hermes wrote") + " in \"${s.openSummary?.title ?: "another chat"}\":\n" +
                m.text.trim().lines().joinToString("\n") { "> $it" }
        }
        c.hide(from, keys)
        if (to == null) newConversation() else openConversation(to)
        voice.update { it.copy(dictation = Dictation(++dictations, quote.take(32000), send = false)) }
    }

    /** The platform's share sheet for text: set while the app can show it, null otherwise. */
    fun setTextSharer(share: ((String) -> Unit)?) {
        textSharer = share
    }

    override fun shareText(text: String) {
        textSharer?.invoke(text)
    }

    override fun deleteConversation(id: String) {
        chat?.delete(id)
        if (chat?.state?.value?.openId == id) page.value = Page(tab = Tab.CHATS)
    }

    override fun dismissNotice() {
        chat?.dismissNotice()
    }

    override fun showStatus() {
        chat?.loadBalance()
        page.update { it.copy(status = true, menuOpen = false) }
    }

    override fun showChats() {
        page.value = page.value.copy(status = false, server = false, terminal = false)
    }

    override fun checkForUpdates() {
        updates?.check()
    }

    override fun installUpdate() {
        val repo = updates ?: return
        val platform = installer ?: return
        val now = repo.state.value
        if (now.installing || now.progress != null) return  // one update at a time; the button says so
        if (!platform.canInstall()) {
            // ask first, so nothing is downloaded twice; resumeUpdate carries on when the user is back
            repo.awaitPermission(true)
            platform.askPermission()
            return
        }
        repo.awaitPermission(false)
        repo.installStarted()  // the button says "Installing…" from the tap, through the download
        scope.launch(io) {
            val apk = repo.download() ?: return@launch repo.installTimedOut()  // the download failed; its error stays
            runCatching { platform.install(apk) }.onFailure { repo.installFailed(it.message ?: it::class.simpleName.orEmpty()) }
            delay(INSTALL_WAIT_MS)
            repo.installTimedOut()  // still here and no answer: let Update be tapped again
        }
    }

    /** Back in the app: carry on with an update that was waiting for the permission to install apps. */
    fun resumeUpdate() {
        val repo = updates ?: return
        if (repo.state.value.awaitingPermission && installer?.canInstall() == true) installUpdate()
    }

    /** How this platform installs updates (Android); null where the app can't update itself. */
    fun setInstaller(updater: AppUpdater?) {
        installer = updater
    }

    /** The system installer refused the update ([message] says why), or the user cancelled it (null). */
    fun updateFailed(message: String?) {
        updates?.installFailed(message)
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
        val todos = TodosRepository(scope, client.asChatApi()).also { it.start() }
        val schedule = ScheduleRepository(scope, client.asChatApi()).also { it.start() }
        val ops = OpsRepository(scope, client.asChatApi()).also { it.start() }
        val updates = UpdateRepository(scope, client.asChatApi(), versionCodeOf(TALARIA_VERSION) ?: 0).also { it.start() }
        val terminals = TerminalRepository(scope, client.asChatApi(), bridge.deviceId).also { it.start() }
        mode.value = Mode.Connected(bridge, client, chat, FilesRepository(scope, client.asChatApi()), todos, schedule, ops, updates, terminals)
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
            val opsState = l.ops ?: OpsState()
            if (x.page.status) {
                Screen.Status(status.copy(canGoBack = true, balances = balanceItems(l.chat?.balances.orEmpty())))
            } else if (x.page.terminal) {
                Screen.Terminal(terminalView(l.terminal ?: TerminalState(), opsState, now,
                    locked = authenticator != null && !terminalLock.unlocked), status)
            } else if (x.page.server) {
                Screen.Server(serverView(opsState, m.bridge.deviceId), status)
            } else {
                val withBalance = status.copy(balances = balanceItems(l.chat?.balances.orEmpty()))
                val todosShown = l.todos?.let { t -> t.copy(todos = t.todos.filterNot { it.id in x.page.hiddenTodos }) }
                val view = chatView(l.chat ?: ChatState(), x.page.conversationOpen,
                    state.phase == ConnectionState.Phase.CONNECTED, status, now, x.pending, x.canAttach, images,
                    x.serverPending, opsApprovals(opsState, m.bridge.deviceId), opsResults(opsState, m.bridge.deviceId)).copy(voice = x.voice, modelPicker = x.page.modelQuery, canShare = x.canShare,
                        openingFile = x.fileTask.opening, openingProgress = x.fileTask.progress)
                Screen.Chat(
                    view, withBalance,
                    undo = x.page.undo,
                    tab = x.page.tab,
                    tabs = TABS,
                    home = homeView(view, now, todosShown).copy(order = x.homeOrder, arranging = x.page.arrangingHome,
                        update = l.updates?.takeIf { installer != null }?.let(::updateBanner)).withSchedule(l.schedule, now),
                    menu = menuView(view, withBalance, l.chat?.models, VERSION).withUpdate(l.updates, installer != null),
                    menuOpen = x.page.menuOpen,
                    files = filesView(l.files, now, x.fileTask.opening, x.fileTask.notice, x.fileTask.progress),
                    schedule = scheduleView(l.schedule, now),
                    todos = todosView(todosShown, view, now),
                )
            }
        }
    }

    companion object {
        /** The countdown on Confirm code: the bridge gives its operator 120 s to approve. */
        const val APPROVAL_WINDOW_MS = 120_000L

        /** How long Update stays busy after the installer has it, if Android never answers. */
        const val INSTALL_WAIT_MS = 120_000L

        /** This build's version (Version.kt). */
        const val VERSION = TALARIA_VERSION

        /** How long to wait for the decision, a little past the bridge's own limit. */
        const val DECISION_TIMEOUT_MS = 150_000L

        const val PREF_READ_ALOUD = "voice.read_aloud"
        const val PREF_AUTO_SEND = "voice.auto_send"
        /** Home's tile order on this device, as "DAY,NEXT,…" (see [homeOrder]). */
        const val PREF_HOME_ORDER = "home.order"
        /** The chat last open on this device, for Chat with Hermes and Talk. */
        const val PREF_LAST_CHAT = "chat.last"

        /**
         * The reply to the message sent with [cmid]: the assistant message of the same turn (the turn's id lands
         * on the sent message once the bridge has it). Null until it starts, or if history replaced both.
         */
        fun talkReply(s: ChatState, cmid: String) = s.openMessages.firstOrNull { it.clientMsgId == cmid }?.turnId
            ?.let { turn -> s.openMessages.lastOrNull { it.role == Role.ASSISTANT && it.turnId == turn } }

        /** Marks a message as spoken in Talk, for Hermes (SOUL.md) and in the chat. */
        const val SPOKEN = "🎙"

        /** Talk started on [tab] stays in the chat on screen (new or not) rather than going back to the last one. */
        fun talksInChatOnScreen(tab: Tab) = tab == Tab.CHATS

        /** What Talk says when Hermes needs an approval: what it's for, then how to answer. */
        fun approvalLine(what: String?): String {
            val thing = what?.let { speakable(it).replace('\n', ' ').trim() }?.takeIf { it.isNotEmpty() }
                ?.let { if (it.length > 140) it.take(140).substringBeforeLast(' ') + "…" else it }
            return (if (thing != null) "I need your okay for this: $thing. " else "I need your okay for this. ") +
                "Say yes to allow it once, or no."
        }

        private val YES = Regex("""\b(yes|yeah|yep|yup|sure|ok|okay|approve|approved|allow|go ahead|do it|go for it)\b""")
        private val NO = Regex("""\b(no|nope|nah|deny|don't|do not|stop|cancel|never)\b""")

        /** "once" for a clear yes, "deny" for a clear no, null when it's neither or both. */
        fun approvalAnswer(heard: String): String? {
            val t = heard.lowercase()
            val yes = YES.containsMatchIn(t)
            val no = NO.containsMatchIn(t)
            return when {
                yes && !no -> "once"
                no && !yes -> "deny"
                else -> null
            }
        }

        /** How long Talk waits for Hermes's own words before saying the quick line instead. */
        const val QUICK_LINE_AFTER_MS = 1_200L
        private const val NUDGE_TICK_MS = 1_000L

        /** How long Undo shows after a swipe. */
        const val UNDO_MS = 5_000L

        private val WHEN = java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm")

        /** What "Ask Hermes about this" puts in the composer for a result from Home: what ran, when, and what it said. */
        fun aboutResult(r: io.github.meepdong.talaria.schedule.AutomationRan, zone: java.time.ZoneId) = buildString {
            val at = WHEN.format(java.time.Instant.ofEpochSecond(r.run.at).atZone(zone))
            append("About my automation \"").append(r.name).append("\" (it ran ").append(at).append(")")
            when (r.run.status) {
                "blocked" -> append(": it was blocked, needing my approval for ").append(r.run.blocked ?: "something").append(".")
                "error" -> append(": it failed: ").append(r.run.error ?: "no reason given").append(".")
                else -> append(". It said:\n").append(quote(r.run.text.orEmpty()))
            }
            append("\n\n")
        }

        /** What "Ask Hermes about this" puts in the composer for a finished server operation. */
        fun aboutServerResult(r: OpsResultItem) = buildString {
            append("About the server action \"").append(r.summary).append("\" (")
            append(if (r.ok) "it worked" else "it failed").append(", asked by ").append(r.from).append(")")
            if (r.output.isNotBlank()) append(". Its output:\n```\n").append(r.output.trim().take(MAX_QUOTE)).append("\n```")
            append("\n\n")
        }

        private fun quote(text: String) = text.trim().take(MAX_QUOTE).lines().joinToString("\n") { "> $it" }

        private const val MAX_QUOTE = 8_000

        /** [VoiceView.speakingKey] while a reply is read aloud on its own. */
        const val READ_ALOUD_KEY = "read-aloud"

        /** What a to-do handed to the agent says. */
        fun handOver(todo: String, comments: List<String> = emptyList(), due: String? = null, group: String? = null) = buildString {
            append("From my to-do list: ").append(todo)
            if (due != null) append("\nDue: ").append(due)
            if (group != null) append("\nIn my list: ").append(group)
            if (comments.isNotEmpty()) append("\n\nComments on it:\n").append(comments.joinToString("\n") { "- $it" })
            append("\n\nPlease take care of this, or tell me what you need from me.")
        }

        /** The pages in the menu bar. */
        val TABS = listOf(Tab.HOME, Tab.CHATS, Tab.TODOS, Tab.FILES, Tab.SCHEDULE)

        /** What people type for a short-code pairing: a host, host:port, or a wss:// URL. */
        fun bridgeUrl(address: String): String? {
            val t = address.trim().trimEnd('/')
            if (t.isEmpty()) return null
            val url = if ("://" in t) t else "wss://$t"
            return url.takeIf { isAllowedBridgeUrl(it) }
        }
    }
}

/** Approvals that open a root terminal or change sessions: these need the fingerprint or screen lock (§16.1). */
private val GUARDED = setOf("terminal.watch", "terminal.control", "tmux.new", "tmux.kill")
