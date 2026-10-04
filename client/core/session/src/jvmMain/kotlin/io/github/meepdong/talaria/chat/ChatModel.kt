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
    /** The model this conversation is pinned to, if any (spec/README.md §11). */
    val model: ModelChoice? = null,
    val queuedTurnIds: List<String> = emptyList(),
    /** Listed first (spec/README.md §9). */
    val pinned: Boolean = false,
)

/** A model as Hermes names it, e.g. openrouter / anthropic/claude-sonnet-4. */
data class ModelChoice(val provider: String, val model: String) {
    /** The part people recognise: "claude-sonnet-4" rather than "anthropic/claude-sonnet-4". */
    val shortName: String get() = model.substringAfterLast('/')
}

/** What `agent.models` offers: the agent's own model ([current]), Talaria's default for new chats, and the rest. */
data class ModelOptions(val current: ModelChoice?, val providers: List<Provider>, val default: ModelChoice? = null) {
    /** What a new chat starts on. */
    val forNewChats: ModelChoice? get() = default ?: current

    data class Provider(val id: String, val name: String, val models: List<String>)
}

/** A side question (Hermes's /btw) and its answer, once it comes. Not saved in history. */
data class Aside(val id: String, val question: String, val answer: String? = null, val error: String? = null)

/** What `chat.status` reports about a conversation. */
data class ConversationStatus(
    val conversationId: String,
    val model: ModelChoice?,
    val messages: Long?,
    val toolCalls: Long?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val costUsd: Double?,
    val running: Boolean,
    val queued: Int,
)

/** Credit left on a provider account (`account.balance`). */
data class AccountBalance(val name: String, val remaining: Double?, val currency: String?, val topUpUrl: String, val error: String?)

enum class Role { USER, ASSISTANT }

enum class MessageState {
    /** A user message on its way to the bridge. */
    SENDING,

    /** A user message the bridge didn't take; it can be retried. */
    NOT_SENT,

    /** A user message waiting for the running reply to end (spec/README.md §11). */
    QUEUED,

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

/** A file already on the server (spec/README.md §12), sent to the agent by where it is. */
data class ServerFile(val root: String, val path: String, val name: String, val mime: String, val size: Long?) {
    fun toAttachment() = Attachment(Attachment.Kind.FILE, name, mime, size)
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
    /** What the agent asks to run while [waitingForApproval], and the answers it takes. */
    val approval: PendingApproval? = null,
    val error: String? = null,
    val attachments: List<Attachment> = emptyList(),
)

/** An approval a running reply waits for (§9): answer it with one of [choices] through [ChatRepository.approve]. */
data class PendingApproval(val choices: List<String>, val command: String?, val description: String?)

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
    /** Side questions by conversation, newest last. */
    val asides: Map<String, List<Aside>> = emptyMap(),
    val models: ModelOptions? = null,
    /** The model picked for the next new conversation. */
    val draftModel: ModelChoice? = null,
    /** Shown by /status until dismissed. */
    val status: ConversationStatus? = null,
    val balances: List<AccountBalance> = emptyList(),
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
    /** The question was sent from this device, so this device may read the answer aloud. */
    val fromThisDevice: Boolean = false,
)

/** A VPS command approval request from the bridge. */
data class VpsApprovalRequest(
    val approvalId: String,
    val command: String,
    val args: List<String>,
    val cwd: String,
    val timeout: Int,
    val agentId: String,
    /** Unix seconds after which the bridge stops waiting; 0 when the bridge didn't say (§10.8). */
    val expiresAt: Long = 0,
)

/** A VPS command result (completed/failed). */
data class VpsCommandResult(
    val approvalId: String,
    val choice: String,
    val exitCode: Int,
    val output: List<String>,
    val command: String? = null,
    val args: List<String>? = null,
    val cwd: String? = null,
)
