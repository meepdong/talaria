package io.github.meepdong.talaria.ui

/** One row of the chat list (UI.md §3). */
data class ConversationItem(
    val id: String,
    val title: String,
    val preview: String,
    val time: String,
    val running: Boolean,
)

enum class ItemState { SENDING, NOT_SENT, STREAMING, DONE, FAILED, CANCELLED }

/** A tool call in a reply: "🔧 web_search…", then ✓ or ✗. */
data class ToolChip(val name: String, val state: String, val preview: String? = null)

data class MessageItem(
    val key: String,
    val fromUser: Boolean,
    val text: String,
    val time: String?,
    val state: ItemState,
    val turnId: String? = null,
    val tools: List<ToolChip> = emptyList(),
    val commentary: String? = null,
    val waitingForApproval: Boolean = false,
    val error: String? = null,
)

data class ChatView(
    val conversations: List<ConversationItem>,
    /** Shown instead of the list when it's empty or failed. */
    val listMessage: String?,
    /** The open conversation, or null for a new one. */
    val openId: String?,
    /** On a phone: the conversation is showing rather than the list. */
    val conversationOpen: Boolean,
    val title: String,
    val messages: List<MessageItem>,
    val hasOlder: Boolean,
    val loading: Boolean,
    val historyError: String?,
    /** The reply that is still running, for the Stop button. */
    val runningTurnId: String?,
    val canSend: Boolean,
    /** Why sending is off, or a warning to show above the composer. */
    val composerHint: String?,
    val notice: String?,
    val connection: Health,
    val connectionSummary: String,
)
