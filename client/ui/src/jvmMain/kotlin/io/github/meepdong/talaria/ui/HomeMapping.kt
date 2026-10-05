package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.chat.ModelChoice
import io.github.meepdong.talaria.chat.ModelOptions
import io.github.meepdong.talaria.todos.Todo
import io.github.meepdong.talaria.todos.TodosState
import io.github.meepdong.talaria.updates.UpdateState
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
        approvals = chat.opsApprovals,
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
fun menuView(chat: ChatView, status: StatusView, models: ModelOptions? = null, version: String = "0.1.0"): MenuView {
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
        version = version,
    )
}

const val RECENT_CHATS = 5

/** Open to-dos on Home; the To-dos page has the rest. */
const val HOME_TODOS = 5

/** A saved tile order ("DAY,NEXT,…"): unknown names are skipped, and tiles it doesn't name follow in the default order. */
fun homeOrder(saved: String): List<HomeTile> {
    val named = saved.split(",").mapNotNull { n -> HomeTile.entries.firstOrNull { it.name == n.trim() } }.distinct()
    return named + HomeTile.entries.filterNot { it in named }
}

/** [tile] one place up or down; at either end nothing changes. */
fun List<HomeTile>.moved(tile: HomeTile, up: Boolean): List<HomeTile> {
    val i = indexOf(tile)
    val j = if (up) i - 1 else i + 1
    if (i < 0 || j !in indices) return this
    return toMutableList().apply { add(j, removeAt(i)) }
}

/** The ☰ menu's About section with the bridge's update state (§17); [canInstall] is false where the app can't update itself. */
fun MenuView.withUpdate(u: UpdateState?, canInstall: Boolean): MenuView {
    if (u == null || !canInstall || !u.supported) return this
    return copy(
        canUpdate = true,
        updateAvailable = u.available,
        updateVersion = u.release?.version.orEmpty(),
        updateNotes = u.release?.notes?.takeIf { u.available },
        updateStatus = updateStatus(u) ?: if (u.upToDate) "Up to date" else null,
        updateBusy = u.progress != null || u.installing,
    )
}

/** Home's card while a newer release waits. */
fun updateBanner(u: UpdateState): UpdateBanner? {
    val release = u.release?.takeIf { u.available && u.supported } ?: return null
    return UpdateBanner(release.version, release.notes, updateStatus(u), busy = u.progress != null || u.installing)
}

private fun updateStatus(u: UpdateState): String? = when (val progress = u.progress) {
    null -> when {
        u.installing -> "Installing…"
        u.awaitingPermission -> "Allow Talaria to install apps, then come back: the update carries on by itself"
        u.error != null -> u.error
        u.checking -> "Checking…"
        else -> null
    }
    else -> "Downloading ${(progress * 100).toInt()}%"
}
