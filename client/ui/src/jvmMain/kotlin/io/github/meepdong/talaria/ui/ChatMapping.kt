package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.chat.ChatMessage
import io.github.meepdong.talaria.chat.ChatState
import io.github.meepdong.talaria.chat.MessageState
import io.github.meepdong.talaria.chat.Role
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    MessageState.STREAMING -> ItemState.STREAMING
    MessageState.DONE -> ItemState.DONE
    MessageState.FAILED -> ItemState.FAILED
    MessageState.CANCELLED -> ItemState.CANCELLED
}

private fun ChatMessage.item(nowMs: Long) = MessageItem(
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
    error = error,
)

/** Turn the chat state into the chat screens (UI.md §3–4). */
fun chatView(state: ChatState, conversationOpen: Boolean, connected: Boolean, status: StatusView, nowMs: Long): ChatView {
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
    return ChatView(
        conversations = state.conversations.map { c ->
            ConversationItem(
                id = c.id,
                title = c.title,
                preview = c.lastText?.let { (if (c.lastRole == Role.USER) "You: " else "") + it.replace('\n', ' ') }.orEmpty(),
                time = shortTime(c.updatedAt * 1000, nowMs).orEmpty(),
                running = c.activeTurnId != null,
            )
        },
        listMessage = listMessage,
        openId = state.openId,
        conversationOpen = conversationOpen,
        title = state.openSummary?.title ?: "New chat",
        messages = state.openMessages.map { it.item(nowMs) },
        hasOlder = thread?.nextBefore != null,
        loading = thread?.loading == true,
        historyError = thread?.error,
        runningTurnId = running,
        canSend = connected && state.unavailable == null && running == null,
        composerHint = hint,
        notice = state.notice,
        connection = status.overall,
        connectionSummary = status.summary,
    )
}
