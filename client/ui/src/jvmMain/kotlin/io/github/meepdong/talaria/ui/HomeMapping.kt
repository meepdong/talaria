package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.todos.TodosState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LONG_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM").withZone(ZoneId.systemDefault())

/** "Monday 5 October". */
fun longDate(nowMs: Long): String = LONG_DAY.format(Instant.ofEpochMilli(nowMs))

private val SHORT_DAY = DateTimeFormatter.ofPattern("EEE d MMM")
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM")

/** Home: the date, the to-dos and the latest chats. */
fun homeView(chat: ChatView, nowMs: Long, todos: TodosState? = null, zone: ZoneId = ZoneId.systemDefault()): HomeView {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val running = chat.conversations.filter { it.running }.map { it.id }.toSet()
    val all = todos?.todos.orEmpty()
    val doneToday = { doneAt: Long? -> doneAt != null && Instant.ofEpochSecond(doneAt).atZone(zone).toLocalDate() == today }
    val shown = all.filter { !it.done || doneToday(it.doneAt) }
    return HomeView(
        date = longDate(nowMs),
        recent = chat.conversations.take(RECENT_CHATS),
        todos = shown.map { t ->
            val due = t.due?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            TodoItem(
                id = t.id,
                text = t.text,
                done = t.done,
                due = due?.let { dueLabel(it, today, t.done) },
                overdue = !t.done && due != null && due < today,
                conversationId = t.conversationId,
                withAgent = t.conversationId != null && t.conversationId in running,
            )
        },
        todosAvailable = todos?.available ?: true,
        doneEarlier = all.count { it.done } - shown.count { it.done },
    )
}

/** "Today", "Tomorrow", "Overdue · 2 Oct", or "Fri 9 Oct". */
fun dueLabel(due: LocalDate, today: LocalDate, done: Boolean = false): String = when {
    due == today -> "Today"
    due == today.plusDays(1) -> "Tomorrow"
    due < today && !done -> "Overdue · ${DAY_MONTH.format(due)}"
    else -> SHORT_DAY.format(due)
}

/** The ☰ panel: replies at work, provider balances and the connection rows. */
fun menuView(chat: ChatView, status: StatusView): MenuView = MenuView(
    running = chat.conversations.filter { it.running }.map { RunningItem(it.title, "Hermes is replying", it.id) },
    balances = status.balances,
    connection = status.rows,
)

const val RECENT_CHATS = 5
