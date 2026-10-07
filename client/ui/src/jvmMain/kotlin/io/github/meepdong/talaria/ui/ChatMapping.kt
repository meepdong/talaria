package io.github.meepdong.talaria.ui

import androidx.compose.ui.graphics.ImageBitmap
import io.github.meepdong.talaria.chat.Attachment
import io.github.meepdong.talaria.chat.ChatMessage
import io.github.meepdong.talaria.chat.ChatState
import io.github.meepdong.talaria.chat.ConversationStatus
import io.github.meepdong.talaria.chat.ConversationSummary
import io.github.meepdong.talaria.chat.ModelChoice
import io.github.meepdong.talaria.chat.MessageState
import io.github.meepdong.talaria.chat.OutgoingFile
import io.github.meepdong.talaria.chat.Role
import io.github.meepdong.talaria.chat.ServerFile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Optional

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val DAY = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault())

/** "14:05" today, "3 Oct" before that. */
fun shortTime(atMs: Long?, nowMs: Long): String? {
    if (atMs == null || atMs <= 0) return null
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(atMs)
    return if (at.atZone(zone).toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()) {
        CLOCK.format(at)
    } else {
        DAY.format(at)
    }
}

private fun MessageState.item(): ItemState = when (this) {
    MessageState.SENDING -> ItemState.SENDING
    MessageState.NOT_SENT -> ItemState.NOT_SENT
    MessageState.QUEUED -> ItemState.QUEUED
    MessageState.STREAMING -> ItemState.STREAMING
    MessageState.DONE -> ItemState.DONE
    MessageState.FAILED -> ItemState.FAILED
    MessageState.CANCELLED -> ItemState.CANCELLED
}

/** Decodes photo bytes for display, once per byte array. The platform supplies the decoder. */
class ImageCache(private val decode: (ByteArray) -> ImageBitmap?) {
    private val cache = java.util.Collections.synchronizedMap(java.util.WeakHashMap<ByteArray, Optional<ImageBitmap>>())

    operator fun get(bytes: ByteArray?): ImageBitmap? = bytes?.let { b ->
        cache.getOrPut(b) { Optional.ofNullable(runCatching { decode(b) }.getOrNull()) }.orElse(null)
    }
}

fun sizeLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

private fun Attachment.chip(images: ImageCache) =
    AttachmentChip(name, kind == Attachment.Kind.IMAGE, size?.let(::sizeLabel), images[preview], root, path, mime.takeIf { root != null })

private val APPROVAL_LABELS = mapOf(
    "once" to "Allow once", "session" to "Allow for this chat", "always" to "Always allow", "deny" to "Deny",
)

private fun ChatMessage.item(nowMs: Long, images: ImageCache) = MessageItem(
    key = key,
    fromUser = role == Role.USER,
    text = text,
    time = shortTime(atMs, nowMs),
    state = state.item(),
    turnId = turnId,
    tools = tools.map { ToolChip(it.name, it.state, it.preview) } +
        toolNames.map { ToolChip(it, "completed") },
    commentary = commentary,
    waitingForApproval = waitingForApproval,
    approval = approval?.takeIf { waitingForApproval }?.let { a ->
        ApprovalItem(a.command, a.description, a.choices.mapNotNull { c -> APPROVAL_LABELS[c]?.let { c to it } })
    },
    error = error,
    attachments = attachments.map { it.chip(images) },
    progress = progress,
    worker = worker,
)

/** Turn the chat state into the chat screens (UI.md §3–4). */
fun chatView(
    state: ChatState, conversationOpen: Boolean, connected: Boolean, status: StatusView, nowMs: Long,
    pending: List<OutgoingFile> = emptyList(), canAttach: Boolean = false, images: ImageCache = NO_IMAGES,
    serverPending: List<ServerFile> = emptyList(),
    opsApprovals: List<OpsApprovalItem> = emptyList(),
    opsResults: List<OpsResultItem> = emptyList(),
): ChatView {
    val thread = state.openThread
    val running = state.openMessages.lastOrNull { it.state == MessageState.STREAMING }?.turnId
        ?: state.openSummary?.activeTurnId
    val listMessage = when {
        state.unavailable != null -> "Chat is off on the bridge: ${state.unavailable}"
        state.conversations.isNotEmpty() -> null
        !state.listLoaded -> if (connected) "Loading…" else "Not connected"
        state.listError != null -> state.listError
        else -> "No conversations yet. Start one with New chat."
    }
    val hint = when {
        state.unavailable != null -> "The bridge has no chat agent set up"
        !connected -> "Not connected. ${status.failure ?: status.summary}"
        else -> null
    }
    val bots = state.bots.map { BotItem(it.id, it.name, it.profile, it.description) }
    val openBot = state.openSummary?.agentId?.let { agent -> bots.firstOrNull { it.id == agent } }
        ?: state.openSummary?.agentId?.takeIf { it.startsWith("bot:") }?.let { BotItem(it, state.openSummary?.title ?: it, it.removePrefix("bot:")) }
    return ChatView(
        bots = bots,
        openBot = openBot,
        // pinned chats first, each part newest first as the list comes
        conversations = state.conversations.filter { !it.archived }.sortedByDescending { it.pinned }.map { it.item(nowMs) },
        archived = state.conversations.filter { it.archived }.sortedByDescending { it.updatedAt }.map { it.item(nowMs) },
        listMessage = listMessage,
        openId = state.openId,
        conversationOpen = conversationOpen,
        title = state.openSummary?.title ?: "New chat",
        messages = foldJobs(state.openMessages.map { it.item(nowMs, images) }),
        hasOlder = thread?.nextBefore != null,
        loading = thread?.loading == true,
        historyError = thread?.error,
        runningTurnId = running,
        // while a reply runs, a new message waits for it on the bridge (§11)
        canSend = connected && state.unavailable == null,
        composerHint = hint,
        notice = state.notice,
        connection = status.overall,
        connectionSummary = status.summary,
        pending = pending.map { it.toAttachment().chip(images) } + serverPending.map { it.toAttachment().chip(images) },
        canAttach = canAttach && openBot == null,  // bots take text only, for now (§18.1)
        canAttachMore = pending.size + serverPending.size < OutgoingFile.MAX_PER_MESSAGE,
        model = state.effectiveModel?.shortName?.takeIf { openBot == null },  // a bot's model is its own (§18.1)
        modelGroups = state.models?.providers.orEmpty().map { p ->
            ModelGroup(p.id, p.name, p.models.map { m ->
                ModelItem(p.id, m, m.substringAfterLast('/'), state.effectiveModel == ModelChoice(p.id, m))
            })
        },
        asides = state.openId?.let { state.asides[it] }.orEmpty().map { AsideItem(it.id, it.question, it.answer, it.error, it.command) },
        hermesCommands = state.openId?.let { state.commands[it] }.orEmpty().map {
            Command.Help(it.name, "/${it.name}", it.about + if (it.approve) " · asks you first" else "")
        },
        status = state.status?.takeIf { it.conversationId == state.openId }?.let { statusLines(it, state.openSummary?.title) },
        opsApprovals = opsApprovals,
        opsResults = opsResults,
    )
}

/** The model the open conversation uses: its own, or for a new chat the one picked, else what new chats start on. */
private val ChatState.effectiveModel: ModelChoice?
    get() = openSummary?.model ?: if (openId == null) draftModel ?: models?.forNewChats else models?.current

private fun statusLines(s: ConversationStatus, title: String?): ConversationStatusView {
    val lines = buildList {
        s.model?.let { add("Model" to "${it.shortName} (${it.provider})") }
        s.messages?.let { add("Messages" to "$it") }
        s.toolCalls?.let { add("Tool calls" to "$it") }
        if (s.inputTokens != null || s.outputTokens != null) {
            add("Tokens" to "${s.inputTokens ?: 0} in · ${s.outputTokens ?: 0} out")
        }
        s.costUsd?.let { add("Cost" to "$" + "%.4f".format(it)) }
        add("Reply running" to if (s.running) "Yes" else "No")
        if (s.queued > 0) add("Waiting" to "${s.queued} queued")
    }
    return ConversationStatusView(title ?: "This chat", lines)
}

fun balanceItems(list: List<io.github.meepdong.talaria.chat.AccountBalance>): List<BalanceItem> = list.map { b ->
    val amount = b.remaining?.let { (if (b.currency == "USD" || b.currency == null) "$" else "${b.currency} ") + "%.2f".format(it) }
    BalanceItem(b.name, amount, b.error, b.topUpUrl)
}

private val NO_IMAGES = ImageCache { null }

private fun ConversationSummary.item(nowMs: Long) = ConversationItem(
    id = id,
    title = title,
    preview = lastText?.let { (if (lastRole == Role.USER) "You: " else "") + it.replace('\n', ' ') }.orEmpty(),
    time = shortTime(updatedAt * 1000, nowMs).orEmpty(),
    running = activeTurnId != null,
    pinned = pinned,
    bot = agentId.startsWith("bot:"),
)
