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
    )

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
    private data class FileTask(val opening: String? = null, val notice: String? = null)
    private val fileTask = MutableStateFlow(FileTask())

    /** Opens fetched file bytes with the device's own app; set by the platform. */
    private var fileOpener: ((name: String, mime: String, bytes: ByteArray) -> Unit)? = null
    @Volatile private var installer: AppUpdater? = null
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

    /** The mic button is listening: send what's heard, and read the answer aloud. */
    @Volatile private var talking = false

    /** Read the next reply to this device aloud, after talking. */
    @Volatile private var speakNextReply = false
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
        combine(log.entries, network, test, page) { e, n, t, p -> Quad(e, n, t, p) },
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
                if (r.fromThisDevice && r.state == MessageState.DONE && (voice.value.readAloud || speakNextReply)) {
                    speakNextReply = false
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
        newConversation()
    }

    override fun talk() {
        if (speechInput.value == null) {
            newConversation()
            chat?.notice("This device can't take dictation, so type your message")
            return
        }
        if (voice.value.listening) {
            toggleDictation()
            return
        }
        newConversation()
        talking = true
        toggleDictation()
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
    fun setFileOpener(open: ((name: String, mime: String, bytes: ByteArray) -> Unit)?) {
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
        val open = fileOpener ?: run {
            fileTask.value = FileTask(notice = "This device can't open files from here yet")
            return
        }
        if (fileTask.value.opening != null) return
        fileTask.value = FileTask(opening = path)
        scope.launch(io) {
            val notice = try {
                val bytes = f.read(root, path)
                open(entry.name, entry.mime ?: "application/octet-stream", bytes)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't open ${entry.name}: ${e.message ?: e::class.simpleName}"
            }
            fileTask.value = FileTask(notice = notice)
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
        todos?.delete(id)
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
        c.send(handOver(todo.text, todo.comments.map { (if (it.byAgent) "Hermes" else "Me") + ": " + it.text }),
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
        schedule?.dismissHomeItem(id, at)
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
                val talked = talking
                talking = false
                if (talked && heard.isNotEmpty()) speakNextReply = true
                voice.update { v ->
                    v.copy(listening = false, heard = "",
                        dictation = if (heard.isEmpty()) v.dictation else Dictation(++dictations, heard, v.autoSend || talked))
                }
            }

            override fun failed(message: String) {
                talking = false
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

    override fun approve(turnId: String, choice: String) {
        chat?.approve(turnId, choice)
    }

    override fun opsApprove(requestId: String, choice: String) {
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
                Screen.Terminal(terminalView(l.terminal ?: TerminalState(), opsState, now), status)
            } else if (x.page.server) {
                Screen.Server(serverView(opsState, m.bridge.deviceId), status)
            } else {
                val withBalance = status.copy(balances = balanceItems(l.chat?.balances.orEmpty()))
                val view = chatView(l.chat ?: ChatState(), x.page.conversationOpen,
                    state.phase == ConnectionState.Phase.CONNECTED, status, now, x.pending, x.canAttach, images,
                    x.serverPending, opsApprovals(opsState, m.bridge.deviceId), opsResults(opsState, m.bridge.deviceId)).copy(voice = x.voice, modelPicker = x.page.modelQuery, canShare = x.canShare)
                Screen.Chat(
                    view, withBalance,
                    tab = x.page.tab,
                    tabs = TABS,
                    home = homeView(view, now, l.todos).copy(order = x.homeOrder, arranging = x.page.arrangingHome,
                        update = l.updates?.takeIf { installer != null }?.let(::updateBanner)).withSchedule(l.schedule, now),
                    menu = menuView(view, withBalance, l.chat?.models, VERSION).withUpdate(l.updates, installer != null),
                    menuOpen = x.page.menuOpen,
                    files = filesView(l.files, now, x.fileTask.opening, x.fileTask.notice),
                    schedule = scheduleView(l.schedule, now),
                    todos = todosView(l.todos, view, now),
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

        /** [VoiceView.speakingKey] while a reply is read aloud on its own. */
        const val READ_ALOUD_KEY = "read-aloud"

        /** What a to-do handed to the agent says. */
        fun handOver(todo: String, comments: List<String> = emptyList()) = buildString {
            append("From my to-do list: ").append(todo)
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
