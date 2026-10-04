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
    ) : Screen
}

/** The pages in the menu bar. */
enum class Tab(val label: String) { HOME("Home"), CHATS("Chats"), FILES("Files"), SCHEDULE("Schedule") }

/** The Home page: today at a glance. */
data class HomeView(
    /** "Monday 5 October". */
    val date: String = "",
    /** The latest chats, newest first. */
    val recent: List<ConversationItem> = emptyList(),
    /** Open to-dos, then the ones done today. */
    val todos: List<TodoItem> = emptyList(),
    /** False when the bridge keeps no to-dos, which hides the card. */
    val todosAvailable: Boolean = true,
    /** Ticked off earlier and not shown. */
    val doneEarlier: Int = 0,
    /** Today's results from automations that report to Home, newest first. */
    val day: List<DayResult> = emptyList(),
    /** The next events and automation runs. */
    val nextUp: List<NextItem> = emptyList(),
    val automationsOn: List<AutomationItem> = emptyList(),
    /** False when the bridge has no automations, which hides those cards. */
    val automationsAvailable: Boolean = true,
)

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
)

/** The ☰ panel. */
data class MenuView(
    /** Replies that are running right now, and anything else at work. */
    val running: List<RunningItem> = emptyList(),
    val balances: List<BalanceItem> = emptyList(),
    /** Network, Bridge and agents, as on the connection page. */
    val connection: List<StatusRow> = emptyList(),
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
    /** Open the model picker, filtered by [query]. */
    fun openModelPicker(query: String = "") {}
    fun closeModelPicker() {}
    fun dismissAside(id: String) {}
    fun closeStatus() {}
    fun retryMessage(key: String) {}
    fun stopReply(turnId: String) {}
    /** Answer the approval a running reply waits for: once, session, always or deny. */
    fun approve(turnId: String, choice: String) {}
    fun loadOlder() {}
    fun renameConversation(id: String, title: String) {}
    fun deleteConversation(id: String) {}
    fun dismissNotice() {}
    fun showStatus() {}
    fun showChats() {}

    // the menu bar and Home

    fun selectTab(tab: Tab) {}
    fun setMenuOpen(open: Boolean) {}

    /** The Chat button: a new chat, ready to type. */
    fun startChat() {}

    /** The mic button: say something to Hermes and hear the answer, in a new chat. */
    fun talk() {}

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
    /** A new chat asking Hermes to do it; the to-do remembers the chat. */
    fun handTodoToAgent(id: String) {}

    // Automations (§14)

    fun addAutomation(draft: AutomationDraft) {}
    /** Ask Hermes to set one up from words. */
    fun describeAutomation(text: String) {}
    fun clearDescribeReply() {}
    fun setAutomationPaused(id: String, paused: Boolean) {}
    fun runAutomation(id: String) {}

    /** Run its task now in a new chat, where an approval it needs can be answered. */
    fun runAutomationInChat(id: String) {}
    fun deleteAutomation(id: String) {}
}

/** The tray icon's colour. */
val Screen.overall: Health
    get() = when (this) {
        is Screen.Status -> view.overall
        is Screen.Chat -> status.overall
        else -> Health.UNKNOWN
    }

/** The tray icon's tooltip. */
val Screen.summary: String
    get() = when (this) {
        is Screen.Connect -> "Not paired"
        is Screen.Confirm -> "Pairing"
        is Screen.Status -> view.summary
        is Screen.Chat -> status.summary
    }

/** Paired with a server, whichever page is showing. */
val Screen.paired: Boolean
    get() = this is Screen.Status || this is Screen.Chat
