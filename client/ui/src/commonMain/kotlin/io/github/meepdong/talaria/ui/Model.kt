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

    /** Paired: the chat list and the open conversation, with the connection state at hand. */
    data class Chat(val view: ChatView, val status: StatusView) : Screen
}

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
)

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
    fun retryMessage(key: String) {}
    fun stopReply(turnId: String) {}
    fun loadOlder() {}
    fun renameConversation(id: String, title: String) {}
    fun deleteConversation(id: String) {}
    fun dismissNotice() {}
    fun showStatus() {}
    fun showChats() {}
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
