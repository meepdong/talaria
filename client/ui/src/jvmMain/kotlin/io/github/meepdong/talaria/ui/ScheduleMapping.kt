package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.schedule.Automation
import io.github.meepdong.talaria.schedule.AutomationRan
import io.github.meepdong.talaria.schedule.CalendarEvent
import io.github.meepdong.talaria.schedule.ScheduleState
import io.github.meepdong.talaria.schedule.When
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_HM = DateTimeFormatter.ofPattern("EEE HH:mm")

/** "in 25 min", "in 3 h", "Tomorrow 08:00", "Mon 10:30" or "5 Oct 10:30". */
fun untilLabel(atMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val mins = (atMs - nowMs) / 60_000
    val at = Instant.ofEpochMilli(atMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when {
        mins < 1 -> "Now"
        mins < 60 -> "in $mins min"
        at.toLocalDate() == today -> if (mins < 180) "in ${mins / 60} h ${mins % 60} min".replace(" 0 min", "") else HM.format(at)
        at.toLocalDate() == today.plusDays(1) -> "Tomorrow ${HM.format(at)}"
        at.toLocalDate() < today.plusDays(7) -> DAY_HM.format(at)
        else -> "${at.dayOfMonth} ${at.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())} ${HM.format(at)}"
    }
}

/** An event's start and end, in [zone]; all-day events span the day. */
private fun span(e: CalendarEvent, zone: ZoneId): Pair<ZonedDateTime, ZonedDateTime>? = runCatching {
    if (e.allDay) {
        LocalDate.parse(e.start.take(10)).atStartOfDay(zone) to LocalDate.parse(e.end.take(10)).atStartOfDay(zone)
    } else {
        OffsetDateTime.parse(e.start).atZoneSameInstant(zone) to OffsetDateTime.parse(e.end).atZoneSameInstant(zone)
    }
}.getOrNull()

private fun resultLabel(to: String) = when (to) {
    "home" -> "Home"
    "chat" -> "A new chat"
    else -> "Log only"
}

private val DAY_CODES = mapOf(
    DayOfWeek.MONDAY to "mon", DayOfWeek.TUESDAY to "tue", DayOfWeek.WEDNESDAY to "wed", DayOfWeek.THURSDAY to "thu",
    DayOfWeek.FRIDAY to "fri", DayOfWeek.SATURDAY to "sat", DayOfWeek.SUNDAY to "sun",
)

/**
 * When an automation next does its work. A window automation is checked every 10 minutes,
 * so its next check says little: its window's next start does.
 */
fun nextWorkMs(a: Automation, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
    if (a.paused) return null
    val w = a.`when`
    if (w !is When.Arrives) return a.nextRunAt?.times(1000)
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    val from = runCatching { LocalTime.parse(w.from) }.getOrNull() ?: return null
    val until = runCatching { LocalTime.parse(w.until) }.getOrNull() ?: return null
    for (d in 0..7) {
        val day = now.toLocalDate().plusDays(d.toLong())
        if (DAY_CODES[day.dayOfWeek] !in w.days) continue
        val start = day.atTime(from).atZone(zone)
        if (d == 0 && now.toLocalTime() >= until) continue
        // inside today's window it's working now; done-for-today isn't known here
        return if (start.isBefore(now)) nowMs else start.toInstant().toEpochMilli()
    }
    return null
}

fun automationItem(a: Automation, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): AutomationItem {
    val last = a.lastRunAt?.let { Instant.ofEpochSecond(it).atZone(zone) }
    val lastTime = last?.let { if (it.toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()) HM.format(it) else DAY_HM.format(it) }
    val next = nextWorkMs(a, nowMs, zone)?.let { untilLabel(it, nowMs, zone) }
    return AutomationItem(
        id = a.id,
        name = a.name,
        schedule = a.scheduleText.ifBlank { if (a.`when` is When.Time) (a.`when` as When.Time).schedule else "" },
        next = if (a.state == "completed") null else next,
        paused = a.paused,
        last = lastTime?.let {
            when (a.lastStatus) {
                "error" -> "Failed $it" + (a.lastError?.let { e -> ": ${e.take(120)}" } ?: "")
                "nothing" -> "Checked $it, nothing yet"
                "blocked" -> "Blocked $it: it needs your approval"
                else -> "Ran $it"
            }
        },
        failed = a.lastStatus == "error" || a.lastStatus == "blocked",
        blocked = a.lastStatus == "blocked",
        byAgent = a.madeIn == "agent",
        resultTo = resultLabel(a.resultTo),
    )
}

fun scheduleView(state: ScheduleState?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): ScheduleView {
    val s = state ?: ScheduleState()
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    val agenda = s.events.mapNotNull { e ->
        val (start, end) = span(e, zone) ?: return@mapNotNull null
        AgendaItem(
            time = if (e.allDay) "All day" else HM.format(start),
            title = e.title,
            detail = listOfNotNull(if (e.allDay) null else "until ${HM.format(end)}", e.location).joinToString(" · ").ifEmpty { null },
            now = !e.allDay && !now.isBefore(start) && now.isBefore(end),
            past = !e.allDay && !now.isBefore(end),
        )
    }
    return ScheduleView(
        available = s.available,
        date = longDate(nowMs),
        agenda = agenda,
        calendarNote = s.calendarError ?: if (agenda.isEmpty() && s.loaded) "Nothing on the calendar today." else null,
        automations = s.automations.map { automationItem(it, nowMs, zone) },
        loaded = s.loaded,
        error = s.error,
        describing = s.describing,
        describeReply = s.describeReply,
    )
}

/** Home's Your day, Next up and Automations on. */
fun HomeView.withSchedule(state: ScheduleState?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): HomeView {
    val s = state ?: return this
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    val events = s.events.filter { !it.allDay }.mapNotNull { e ->
        val (start, end) = span(e, zone) ?: return@mapNotNull null
        if (!end.isAfter(now)) return@mapNotNull null
        NextItem(HM.format(start), e.title, if (start.isAfter(now)) untilLabel(start.toInstant().toEpochMilli(), nowMs, zone) else "Now") to
            start.toInstant().toEpochMilli()
    }
    val runs = s.automations.mapNotNull { a ->
        val at = nextWorkMs(a, nowMs, zone) ?: return@mapNotNull null
        if (at - nowMs > NEXT_UP_WINDOW_MS || a.`when` is When.AfterEvent) return@mapNotNull null
        NextItem(HM.format(Instant.ofEpochMilli(maxOf(at, nowMs)).atZone(zone)), a.name, untilLabel(at, nowMs, zone), automation = true) to at
    }
    return copy(
        day = s.today.map { r ->
            DayResult(r.name, r.run.text ?: r.run.error.orEmpty(), HM.format(Instant.ofEpochSecond(r.run.at).atZone(zone)),
                failed = r.run.status == "error", conversationId = r.run.conversationId, id = r.id,
                blocked = if (r.run.status == "blocked") r.run.blocked ?: "something" else null)
        },
        nextUp = (events + runs).sortedBy { it.second }.take(NEXT_UP).map { it.first },
        automationsOn = s.automations.filter { !it.paused && it.state != "completed" }.map { automationItem(it, nowMs, zone) },
        automationsAvailable = s.available,
    )
}

/** A notification's text for a run. */
fun AutomationRan.notificationText(): String = when (run.status) {
    "error" -> "It failed: ${run.error ?: "unknown error"}"
    "blocked" -> "Blocked: it needs your approval for ${run.blocked ?: "something"}. Open Home and tap Run in chat."
    else -> run.text.orEmpty()
}

const val NEXT_UP = 5
const val NEXT_UP_WINDOW_MS = 24 * 3_600_000L
