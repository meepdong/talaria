package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.chat.Bot
import io.github.meepdong.talaria.control.ControlState
import io.github.meepdong.talaria.control.UsageReport
import java.time.LocalDate
import java.util.Locale

/** The board as the To-dos tab shows it (§18.4): columns with tasks, names for bot ids. */
fun boardView(s: ControlState?, showing: Boolean, bots: List<Bot>, nowMs: Long): BoardView {
    if (s == null || !s.boardAvailable) return BoardView()
    val names = mapOf("assistant" to "Hermes") + bots.associate { it.id to it.name }
    fun name(id: String?) = id?.let { names[it] ?: it.removePrefix("bot:") }
    val columns = s.columns.filter { it.tasks.isNotEmpty() }.map { c ->
        BoardColumnView(c.name, BOARD_LABELS[c.name] ?: c.name, c.tasks.map { t ->
            BoardTaskItem(t.id, t.title, t.status, name(t.assignee), t.summary, t.error, t.comments,
                relativeTime((t.completedAt ?: t.startedAt ?: t.createdAt) * 1000, nowMs))
        })
    }
    val open = s.openTask?.let { id -> s.tasks.firstOrNull { it.id == id } }
    val detail = open?.let { t ->
        BoardDetail(t.id, t.body, t.result,
            s.comments[t.id].orEmpty().map { Triple(if (it.author == "owner") "You" else it.author, it.text, relativeTime(it.at * 1000, nowMs)) },
            (BOARD_LABELS.keys - "running" - t.status).map { it to (BOARD_LABELS[it] ?: it) })
    }
    return BoardView(
        available = true, showing = showing, columns = columns,
        people = listOf("assistant" to "Hermes") + bots.map { it.id to it.name },
        openTask = open?.id, detail = detail, notice = s.notice ?: s.warning,
        taskCount = s.tasks.count { it.status != "done" },
    )
}

/** What Hermes spent, for the Server page (§18.5). */
fun usageView(s: ControlState?): UsageView? {
    if (s == null || !s.boardAvailable) return null
    val u = s.usage ?: return UsageView(loading = s.usageLoading)
    return usageView(u, s.usageLoading)
}

fun usageView(u: UsageReport, loading: Boolean = false): UsageView {
    val most = u.byDay.maxOfOrNull { it.second.costUsd }?.takeIf { it > 0 } ?: 1.0
    return UsageView(
        days = u.days, loading = loading,
        total = money(u.total.costUsd) + if (u.total.estimated) " (estimate)" else "",
        detail = "${u.total.calls} calls · ${u.total.sessions} chats · ${tokens(u.total.inputTokens)} tokens in, ${tokens(u.total.outputTokens)} out",
        byDay = u.byDay.takeLast(if (u.days <= 7) 7 else 31).map { (day, use) ->
            UsageBar(dayLabel(day), money(use.costUsd), (use.costUsd / most).toFloat().coerceIn(0f, 1f))
        },
        byModel = u.byModel.take(6).map { (model, use) -> model to "${money(use.costUsd)} · ${use.calls} calls" },
    )
}

private fun money(usd: Double) = "$" + String.format(Locale.ROOT, "%.2f", usd)

private fun tokens(n: Long) = when {
    n >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> "${n / 1_000}k"
    else -> "$n"
}

private fun dayLabel(day: String): String = runCatching {
    val d = LocalDate.parse(day)
    d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH) + " " + d.dayOfMonth
}.getOrDefault(day)

/** Bots' routines for the Schedule tab (§18.6); null when the bridge has none. */
fun routinesView(s: ControlState?, bots: List<Bot>, nowMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): RoutinesView? {
    val routines = s?.routines ?: return null
    val names = mapOf("assistant" to "Hermes") + bots.associate { it.id to it.name }
    val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return RoutinesView(
        items = routines.map { r ->
            val last = r.lastRunAt?.let { java.time.Instant.ofEpochSecond(it).atZone(zone) }?.let { at ->
                val t = if (at.toLocalDate() == today) HM_FMT.format(at) else DAY_HM_FMT.format(at)
                if (r.lastStatus == "error") "Failed $t" + (r.lastError?.let { ": ${it.take(120)}" } ?: "") else "Ran $t"
            }
            RoutineItem(r.id, r.botId, names[r.botId] ?: r.botId.removePrefix("bot:"), r.name, r.schedule, r.task,
                on = r.enabled && r.state != "paused",
                next = r.nextRunAt?.takeIf { r.enabled && r.state != "completed" }?.let { untilLabel(it * 1000, nowMs, zone) },
                last = last, failed = r.lastStatus == "error", toChat = r.toChat)
        },
        bots = bots.map { it.id to it.name },
        notice = s.notice,
    )
}

/** A bot's helper agents for the open chat (§18.7). */
fun helperItems(s: ControlState?, conversationId: String?): List<HelperItem> =
    conversationId?.let { s?.helpers?.get(it) }.orEmpty().map { h ->
        HelperItem(h.id, h.goal.ifBlank { "Helping" },
            listOfNotNull(h.status.ifBlank { null }, if (h.tools > 0) "${h.tools} tools" else null, h.lastTool).joinToString(" · "),
            h.canSteer)
    }

private val HM_FMT = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
private val DAY_HM_FMT = java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)
