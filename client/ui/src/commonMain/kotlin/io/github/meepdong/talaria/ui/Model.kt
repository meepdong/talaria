package io.github.meepdong.talaria.ui

/** How a status row, or the tray icon, should be coloured. */
enum class Health { GOOD, WARN, BAD, UNKNOWN }

/** What the app shows. Plain data, so the screens don't depend on the session code. */
sealed interface Screen {
    /** Pair with a link, or with a short code plus the server's address. */
    data class Connect(
        val deviceName: String,
        val error: String? = null,
        val busy: Boolean = false,
    ) : Screen

    /** The SAS from PROTOCOL §3.2, shown while the operator approves in the terminal. */
    data class Confirm(
        val digits: String,
        val emoji: List<String>,
        val emojiNames: List<String>,
        val secondsLeft: Int,
    ) : Screen

    data class Status(val view: StatusView) : Screen

    /** The Server page, from the ☰ menu (PROTOCOL §10.8). [status] keeps the tray icon right. */
    data class Server(val view: ServerView, val status: StatusView) : Screen

    /** Terminals (spec §16.1): root's tmux sessions, from the ☰ menu. */
    data class Terminal(val view: TerminalView, val status: StatusView) : Screen

    /**
     * Paired: the main app, with its menu bar. [tab] picks the page; [menu] is the ☰ panel
     * (running work, balance, connection), shown while [menuOpen].
     */
    data class Chat(
        val view: ChatView,
        val status: StatusView,
        val tab: Tab = Tab.CHATS,
        val tabs: List<Tab> = listOf(Tab.HOME, Tab.CHATS),
        val home: HomeView = HomeView(),
        val menu: MenuView = MenuView(),
        val menuOpen: Boolean = false,
        val files: FilesView = FilesView(),
        val schedule: ScheduleView = ScheduleView(),
        val todos: TodosView = TodosView(),
    ) : Screen
}

/** The pages in the menu bar. */
enum class Tab(val label: String) { HOME("Home"), CHATS("Chats"), TODOS("To-dos"), FILES("Files"), SCHEDULE("Schedule") }

/** Home's movable tiles, in the default order. "Needs you" isn't one: it stays on top while something waits. */
enum class HomeTile(val label: String) { DAY("Your day"), NEXT("Next up"), AUTOMATIONS("Automations on"), TODOS("To do"), RECENT("Recent chats") }

/** The Home page: today at a glance. */
data class HomeView(
    /** "Monday 5 October". */
    val date: String = "",
    /** The latest chats, newest first. */
    val recent: List<ConversationItem> = emptyList(),
    /** The first few open to-dos, then the ones done today. */
    val todos: List<TodoItem> = emptyList(),
    /** Open to-dos not shown on Home; the To-dos page has them all. */
    val moreTodos: Int = 0,
    /** False when the bridge keeps no to-dos, which hides the card. */
    val todosAvailable: Boolean = true,
    /** Ticked off earlier and not shown. */
    val doneEarlier: Int = 0,
    /** Today's results from automations that report to Home, newest first. */
    val day: List<DayResult> = emptyList(),
    /** Server operations waiting for the owner (PROTOCOL §10.8), shown in "Needs you" with blocked runs. */
    val approvals: List<OpsApprovalItem> = emptyList(),
    /** The next events and automation runs. */
    val nextUp: List<NextItem> = emptyList(),
    val automationsOn: List<AutomationItem> = emptyList(),
    /** False when the bridge has no automations, which hides those cards. */
    val automationsAvailable: Boolean = true,
    /** The tiles below "Needs you", in this device's order. */
    val order: List<HomeTile> = HomeTile.entries,
    /** Rearranging: each tile shows ↑ and ↓, and Home a Done button. */
    val arranging: Boolean = false,
    /** A newer release to install, shown above everything else until it's installed. */
    val update: UpdateBanner? = null,
)

/** Home's "Update available" card (§17). [status] is the download's progress or what went wrong. */
data class UpdateBanner(val version: String, val notes: String? = null, val status: String? = null, val busy: Boolean = false)

/** The Terminals page (spec §16.1): the session list, or one session's screen. */
data class TerminalView(
    /** False when the bridge has no server operations. */
    val available: Boolean = true,
    val sessions: List<TerminalItem> = emptyList(),
    val loading: Boolean = false,
    /** The open session, or null on the list. */
    val open: String? = null,
    /** The approval this device has to give first. */
    val approval: TerminalApproval? = null,
    /** The screen: lines with SGR colour sequences, or null until the first one arrives. */
    val text: String? = null,
    val cols: Int = 80,
    /** (column, row) of the cursor, shown while typing. */
    val cursor: Pair<Int, Int>? = null,
    /** Typing allowed (a control grant). */
    val control: Boolean = false,
    /** "Watching · claude · 89×33". */
    val status: String = "",
    val closed: String? = null,
    val error: String? = null,
)

/** A row in the session list: "claude" with "Claude Code · /root · 2 attached" and "2 min ago". */
data class TerminalItem(val name: String, val detail: String, val active: String)

data class TerminalApproval(val requestId: String, val summary: String, val control: Boolean, val answering: Boolean = false)

/** A to-do on Home (spec/README.md §13). */
data class TodoItem(
    val id: String,
    val text: String,
    val done: Boolean,
    /** "Today", "Tomorrow", "Overdue · 2 Oct", "Fri 9 Oct", or null. */
    val due: String? = null,
    val overdue: Boolean = false,
    /** The conversation it was handed to Hermes in. */
    val conversationId: String? = null,
    /** That conversation's reply is running. */
    val withAgent: Boolean = false,
    val group: String? = null,
    val comments: List<CommentItem> = emptyList(),
)

/** A comment on a to-do. [time] is "14:05" today, else "3 Oct". */
data class CommentItem(val id: String, val text: String, val byAgent: Boolean, val time: String)

/** The To-dos page: every open to-do by group, then the done ones. */
data class TodosView(
    /** Groups in order, ungrouped last (name null). */
    val groups: List<TodoGroupView> = emptyList(),
    val done: List<TodoItem> = emptyList(),
    val openCount: Int = 0,
    /** Every group in use, for "Move to group". */
    val groupNames: List<String> = emptyList(),
    /** Hermes is sorting the list again. */
    val regrouping: Boolean = false,
    val error: String? = null,
    /** False when the bridge keeps no to-dos. */
    val available: Boolean = true,
)

/** One group on the To-dos page. A null [name] holds the to-dos Hermes hasn't sorted yet. */
data class TodoGroupView(val name: String?, val items: List<TodoItem>)

/** The ☰ panel. */
data class MenuView(
    /** Replies that are running right now, and anything else at work. */
    val running: List<RunningItem> = emptyList(),
    val balances: List<BalanceItem> = emptyList(),
    /** Network, Bridge and agents, as on the connection page. */
    val connection: List<StatusRow> = emptyList(),
    /** What new chats start on: "claude-sonnet-4", or null while models are loading. */
    val defaultModel: String? = null,
    /** True when it's Hermes's own default rather than one picked in Talaria. */
    val defaultIsAgents: Boolean = true,
    /** The models to pick from, selected by the default. */
    val defaultModelGroups: List<ModelGroup> = emptyList(),
    /** App version string (e.g., "0.1.0"). */
    val version: String = "",
    /** A newer release is ready to install from the bridge (§17). */
    val updateAvailable: Boolean = false,
    /** Its version: "0.2.0-beta.2". */
    val updateVersion: String = "",
    /** False where the app can't update itself (desktop, or an older bridge): no update controls. */
    val canUpdate: Boolean = false,
    /** "Checking…", "Downloading 40%", "Installing…", "Up to date", or why it failed. */
    val updateStatus: String? = null,
    /** Downloading or installing: Update can't be tapped again. */
    val updateBusy: Boolean = false,
    /** What's new in [updateVersion]. */
    val updateNotes: String? = null,
)

/** Something at work: a reply being written. [conversationId] opens it. */
data class RunningItem(val title: String, val detail: String, val conversationId: String? = null)

/** One line of the status card: Network, Bridge, or an agent. */
data class StatusRow(val label: String, val health: Health, val value: String, val detail: String? = null)

/** The Test connection button: running, or the last result. */
data class TestView(val running: Boolean, val ok: Boolean? = null, val message: String? = null)

data class StatusView(
    val rows: List<StatusRow>,
    /** Why the connection is down, naming the layer and the fix. Null while connected. */
    val failure: String? = null,
    /** True when retrying can't help (revoked, refused, identity changed). */
    val mustPairAgain: Boolean = false,
    val lastConnected: String,
    /** "Retrying in 12 s" while waiting to reconnect. */
    val reconnectIn: String? = null,
    val deviceName: String,
    val server: String,
    val keyProtection: String,
    val keyWarning: Boolean = false,
    val test: TestView? = null,
    /** Newest first. */
    val log: List<String> = emptyList(),
    /** Overall state, for the tray icon and its tooltip. */
    val overall: Health,
    val summary: String,
    /** Shows "← Chats" when the status page was opened from the chat. */
    val canGoBack: Boolean = false,
    /** Provider credit, such as OpenRouter's, with a link to add more. */
    val balances: List<BalanceItem> = emptyList(),
)

data class BalanceItem(val name: String, val amount: String?, val error: String?, val topUpUrl: String)

/** What the screens can ask for. */
interface TalariaActions {
    fun pairWithLink(link: String, deviceName: String)
    fun pairWithCode(code: String, address: String, deviceName: String)
    fun cancelPairing()
    fun testConnection()
    fun reconnectNow()

    /** Forget the paired server and go back to Connect. */
    fun forgetServer()

    // chat (M2); defaults, so screens that don't chat needn't care

    fun openConversation(id: String) {}
    fun newConversation() {}

    /** Back from a conversation to the list, on a phone. */
    fun closeConversation() {}
    fun sendMessage(text: String) {}
    /** Pick photos ([photos] true) or any files to send with the next message. */
    fun attachFiles(photos: Boolean) {}
    fun removeAttachment(index: Int) {}

    /** 🎤: start dictating, or stop and keep what was heard. */
    fun toggleDictation() {}
    /** The composer has taken dictation [id]. */
    fun dictationTaken(id: Long) {}
    fun speak(key: String, text: String) {}
    fun stopSpeaking() {}
    /** Read replies to messages sent from this device aloud. */
    fun setReadAloud(on: Boolean) {}
    /** Send dictated text as soon as it's heard, rather than leaving it to edit. */
    fun setAutoSend(on: Boolean) {}

    fun pickModel(provider: String, model: String) {}

    /** The model new chats start on, on every device; a chat can still change it. Null: Hermes's own default. */
    fun setDefaultModel(provider: String?, model: String?) {}

    /** Open the model picker, filtered by [query]. */
    fun openModelPicker(query: String = "") {}
    fun closeModelPicker() {}
    fun dismissAside(id: String) {}
    fun closeStatus() {}
    fun retryMessage(key: String) {}
    fun stopReply(turnId: String) {}
    /** Answer the approval a running reply waits for: once, session, always or deny. */
    fun approve(turnId: String, choice: String) {}
    
    /** Answer a server operation's approval: once or deny, signed by this device (PROTOCOL §10.8). */
    fun opsApprove(requestId: String, choice: String) {}

    /** Dismiss a finished server operation's card. */
    fun opsDismiss(requestId: String) {}

    // Terminals (spec §16.1)

    fun showTerminals() {}
    fun refreshTerminals() {}
    /** Ask to watch a tmux session: an approval on this device opens it. */
    fun openTerminal(session: String) {}
    /** Back to the list; ends the grant. */
    fun closeTerminal() {}
    /** Ask to type in the open session (tier 2: typing acts as root). */
    fun terminalTakeControl() {}
    /** A named key: Enter, Escape, Tab, Up, Down, Left, Right, C-c, … */
    fun terminalKey(key: String) {}
    /** Literal text, then Enter when [enter]. */
    fun terminalText(text: String, enter: Boolean) {}
    fun loadOlder() {}
    fun renameConversation(id: String, title: String) {}
    fun deleteConversation(id: String) {}
    fun pinConversation(id: String, pinned: Boolean) {}

    /** Delete messages of the open chat from Talaria on every device; Hermes keeps them (spec/README.md §9). */
    fun deleteMessages(keys: List<String>) {}

    /** Quote messages in another chat's composer ([to], or a new chat when null) and delete them here. */
    fun moveMessages(keys: List<String>, to: String?) {}

    /** Hand text to another app (the platform's share sheet). */
    fun shareText(text: String) {}
    fun dismissNotice() {}
    fun showStatus() {}

    /** The Server page, from the ☰ menu. [showChats] goes back. */
    fun showServer() {}
    fun refreshServer() {}

    /** Run a server operation; parameters as text, integers converted by their type in the catalogue. */
    fun serverRun(op: String, params: Map<String, String> = emptyMap()) {}
    fun dismissServerError() {}
    fun showChats() {}

    // the menu bar and Home

    fun selectTab(tab: Tab) {}
    fun setMenuOpen(open: Boolean) {}

    /** Ask the bridge for a newer release (§17). */
    fun checkForUpdates() {}
    /** Download the newer release, check it and hand it to the system installer. */
    fun installUpdate() {}

    /** The Chat button: a new chat, ready to type. */
    fun startChat() {}

    /** The mic button: say something to Hermes and hear the answer, in a new chat. */
    fun talk() {}

    // Home's tiles, ordered per device

    /** A long-press on a tile's title. */
    fun startArrangingHome() {}
    fun moveHomeTile(tile: HomeTile, up: Boolean) {}
    fun doneArrangingHome() {}

    // Files (§12)

    fun openRoot(id: String) {}
    /** A folder opens in the list; a file is fetched and opened with the device's own app. */
    fun openFile(path: String) {}
    fun filesUp() {}
    /** Search by name under the folder showing; blank goes back to the folder. */
    fun searchFiles(query: String) {}
    /** A new chat with this server file attached, ready for a question. */
    fun askAboutFile(path: String) {}

    // To-dos (§13)

    fun addTodo(text: String) {}
    fun setTodoDone(id: String, done: Boolean) {}
    fun deleteTodo(id: String) {}
    fun editTodo(id: String, text: String) {}
    /** [due] is "today", "tomorrow", "next week", or null to clear it. */
    fun setTodoDue(id: String, due: String?) {}
    /** Null takes it out of its group. */
    fun setTodoGroup(id: String, group: String?) {}
    fun commentOnTodo(id: String, text: String) {}
    fun deleteTodoComment(id: String, commentId: String) {}
    /** Ask Hermes to sort every open to-do into groups again. */
    fun regroupTodos() {}
    /** A new chat asking Hermes to do it; the to-do remembers the chat. */
    fun handTodoToAgent(id: String) {}

    // Automations (§14)

    fun addAutomation(draft: AutomationDraft) {}
    /** Ask Hermes to set one up from words. */
    fun describeAutomation(text: String) {}
    fun clearDescribeReply() {}
    fun setAutomationPaused(id: String, paused: Boolean) {}

    /** Where an automation's results go: "home" (Home and a notification), "chat" or "log" (§14). */
    fun setAutomationResultTo(id: String, resultTo: String) {}
    fun runAutomation(id: String) {}

    /** Run its task now in a new chat, where an approval it needs can be answered. */
    fun runAutomationInChat(id: String) {}
    fun deleteAutomation(id: String) {}

    /** Dismiss a blocked automation run from Home's "Needs you" card. */
    fun dismissHomeItem(id: String, at: Long) {}
}

/** The tray icon's colour. */
val Screen.overall: Health
    get() = when (this) {
        is Screen.Status -> view.overall
        is Screen.Server -> status.overall
        is Screen.Terminal -> status.overall
        is Screen.Chat -> status.overall
        else -> Health.UNKNOWN
    }

/** The tray icon's tooltip. */
val Screen.summary: String
    get() = when (this) {
        is Screen.Connect -> "Not paired"
        is Screen.Confirm -> "Pairing"
        is Screen.Status -> view.summary
        is Screen.Server -> status.summary
        is Screen.Terminal -> status.summary
        is Screen.Chat -> status.summary
    }

/** Paired with a server, whichever page is showing. */
val Screen.paired: Boolean
    get() = this is Screen.Status || this is Screen.Server || this is Screen.Terminal || this is Screen.Chat
