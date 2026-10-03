package io.github.meepdong.talaria.chat

/** A conversation as the chat list shows it (spec/README.md §9, `conversations.list`). */
data class ConversationSummary(
    val id: String,
    val agentId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRole: Role? = null,
    val lastText: String? = null,
    val activeTurnId: String? = null,
)

enum class Role { USER, ASSISTANT }

enum class MessageState {
    /** A user message on its way to the bridge. */
    SENDING,

    /** A user message the bridge didn't take; it can be retried. */
    NOT_SENT,

    /** An assistant reply that is still streaming. */
    STREAMING,
    DONE,
    FAILED,
    CANCELLED,
}

/** One tool call in a running or finished reply. */
data class ToolStep(val name: String, val state: String, val preview: String? = null)

data class ChatMessage(
    /** Stable across updates, for list keys. */
    val key: String,
    val role: Role,
    val text: String,
    val atMs: Long?,
    val state: MessageState = MessageState.DONE,
    val turnId: String? = null,
    val clientMsgId: String? = null,
    /** Live tool steps of this reply. */
    val tools: List<ToolStep> = emptyList(),
    /** Tool names from history, where only the names are known. */
    val toolNames: List<String> = emptyList(),
    /** The agent's latest progress note while it works. */
    val commentary: String? = null,
    val waitingForApproval: Boolean = false,
    val error: String? = null,
)

/** The loaded part of one conversation. */
data class ConversationThread(
    val messages: List<ChatMessage> = emptyList(),
    /** Cursor for the next older page; null when the oldest message is loaded. */
    val nextBefore: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

data class ChatState(
    val conversations: List<ConversationSummary> = emptyList(),
    val listLoaded: Boolean = false,
    val listError: String? = null,
    /** The open conversation, or null for a new one that has no id yet. */
    val openId: String? = null,
    val threads: Map<String, ConversationThread> = emptyMap(),
    /** Messages of the new conversation, until the bridge names it. */
    val draft: List<ChatMessage> = emptyList(),
    /** Set when the bridge has no chat agent, so the app can say so instead of failing each send. */
    val unavailable: String? = null,
    /** A one-off problem to show, such as a failed rename. */
    val notice: String? = null,
) {
    val openMessages: List<ChatMessage>
        get() = openId?.let { threads[it]?.messages } ?: draft

    val openThread: ConversationThread?
        get() = openId?.let { threads[it] }

    val openSummary: ConversationSummary?
        get() = openId?.let { id -> conversations.firstOrNull { it.id == id } }
}

/** A reply that finished, for notifications. */
data class FinishedReply(
    val conversationId: String,
    val title: String,
    val text: String,
    val state: MessageState,
    val error: String?,
)
