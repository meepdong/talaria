package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.chat.ModelChoice
import io.github.meepdong.talaria.chat.ModelOptions
import io.github.meepdong.talaria.todos.Todo
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

/** Home: the date, the first few to-dos and the latest chats. */
fun homeView(chat: ChatView, nowMs: Long, todos: TodosState? = null, zone: ZoneId = ZoneId.systemDefault()): HomeView {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val running = chat.conversations.filter { it.running }.map { it.id }.toSet()
    val all = todos?.todos.orEmpty()
    val doneToday = { doneAt: Long? -> doneAt != null && Instant.ofEpochSecond(doneAt).atZone(zone).toLocalDate() == today }
    val open = all.filter { !it.done }
    val shown = open.take(HOME_TODOS) + all.filter { it.done && doneToday(it.doneAt) }
    return HomeView(
        date = longDate(nowMs),
        recent = chat.conversations.take(RECENT_CHATS),
        todos = shown.map { it.item(today, running, zone) },
        moreTodos = open.size - open.take(HOME_TODOS).size,
        todosAvailable = todos?.available ?: true,
        doneEarlier = all.count { it.done } - shown.count { it.done },
    )
}

/** The To-dos page: open ones by group (groups A to Z, unsorted last), then the done ones. */
fun todosView(todos: TodosState?, chat: ChatView, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): TodosView {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val running = chat.conversations.filter { it.running }.map { it.id }.toSet()
    val all = todos?.todos.orEmpty()
    val open = all.filter { !it.done }
    val groups = open.groupBy { it.group }.entries
        .sortedWith(compareBy<Map.Entry<String?, List<Todo>>> { it.key == null }.thenBy { it.key?.lowercase() })
        .map { (name, items) -> TodoGroupView(name, items.map { it.item(today, running, zone) }) }
    return TodosView(
        groups = groups,
        done = all.filter { it.done }.map { it.item(today, running, zone) },
        openCount = open.size,
        groupNames = all.mapNotNull { it.group }.distinct().sortedBy { it.lowercase() },
        regrouping = todos?.regrouping == true,
        error = todos?.error,
        available = todos?.available ?: true,
    )
}

private fun Todo.item(today: LocalDate, running: Set<String>, zone: ZoneId): TodoItem {
    val dueDate = due?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return TodoItem(
        id = id,
        text = text,
        done = done,
        due = dueDate?.let { dueLabel(it, today, done) },
        overdue = !done && dueDate != null && dueDate < today,
        conversationId = conversationId,
        withAgent = conversationId != null && conversationId in running,
        group = group,
        comments = comments.map { c ->
            val at = Instant.ofEpochSecond(c.at).atZone(zone)
            CommentItem(c.id, c.text, c.byAgent, if (at.toLocalDate() == today) CLOCK.format(at) else DAY_MONTH.format(at))
        },
    )
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/** A to-do's due date from a quick choice: "today", "tomorrow" or "next week" (a week from today). */
fun dueFromChoice(choice: String, today: LocalDate): String? = when (choice) {
    "today" -> today
    "tomorrow" -> today.plusDays(1)
    "next week" -> today.plusDays(7)
    else -> null
}?.toString()

/** "Today", "Tomorrow", "Overdue · 2 Oct", or "Fri 9 Oct". */
fun dueLabel(due: LocalDate, today: LocalDate, done: Boolean = false): String = when {
    due == today -> "Today"
    due == today.plusDays(1) -> "Tomorrow"
    due < today && !done -> "Overdue · ${DAY_MONTH.format(due)}"
    else -> SHORT_DAY.format(due)
}

/** The ☰ panel: replies at work, the default model, provider balances and the connection rows. */
fun menuView(chat: ChatView, status: StatusView, models: ModelOptions? = null): MenuView {
    val default = models?.forNewChats
    return MenuView(
        running = chat.conversations.filter { it.running }.map { RunningItem(it.title, "Hermes is replying", it.id) },
        balances = status.balances,
        connection = status.rows,
        defaultModel = default?.shortName,
        defaultIsAgents = models?.default == null,
        defaultModelGroups = models?.providers.orEmpty().map { p ->
            ModelGroup(p.id, p.name, p.models.map { m -> ModelItem(p.id, m, m.substringAfterLast('/'), default == ModelChoice(p.id, m)) })
        },
    )
}

const val RECENT_CHATS = 5

/** Open to-dos on Home; the To-dos page has the rest. */
const val HOME_TODOS = 5
