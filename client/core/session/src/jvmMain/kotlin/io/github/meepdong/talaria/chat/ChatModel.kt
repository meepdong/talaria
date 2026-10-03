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

/** A photo or file on a user message (spec/README.md §10). */
data class Attachment(
    val kind: Kind,
    val name: String,
    val mime: String,
    val size: Long? = null,
    /** The photo itself, on the device that sent it; the bridge doesn't send image bytes back. */
    val preview: ByteArray? = null,
) {
    enum class Kind { IMAGE, FILE }
}

/** A photo or file picked to send, already downscaled and stripped of location if it is a photo. */
class OutgoingFile(val name: String, val mime: String, val bytes: ByteArray) {
    val kind: Attachment.Kind
        get() = if (mime in INLINE_IMAGE_MIMES && bytes.size <= MAX_INLINE_IMAGE) Attachment.Kind.IMAGE else Attachment.Kind.FILE

    fun toAttachment() = Attachment(kind, name, mime, bytes.size.toLong(), bytes.takeIf { kind == Attachment.Kind.IMAGE })

    companion object {
        /** What the bridge passes to the agent as a photo; anything else goes to its inbox as a file. */
        val INLINE_IMAGE_MIMES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
        const val MAX_INLINE_IMAGE = 5 * 1024 * 1024
        const val MAX_SIZE = 20 * 1024 * 1024
        const val MAX_PER_MESSAGE = 10
    }
}

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
    val attachments: List<Attachment> = emptyList(),
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
