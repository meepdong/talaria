package io.github.meepdong.talaria.ui

/** The Schedule page: today's calendar and the agent's automations (spec/README.md §14). */
data class ScheduleView(
    /** False when the bridge has no automations, which shows why instead of the page. */
    val available: Boolean = true,
    val date: String = "",
    val agenda: List<AgendaItem> = emptyList(),
    /** Why the calendar is empty: not set up, or couldn't be read. */
    val calendarNote: String? = null,
    val automations: List<AutomationItem> = emptyList(),
    val loaded: Boolean = false,
    val error: String? = null,
    val describing: Boolean = false,
    /** The agent's answer to "Describe it". */
    val describeReply: String? = null,
)

data class AgendaItem(
    /** "10:00", or "All day". */
    val time: String,
    val title: String,
    val detail: String? = null,
    /** Happening now. */
    val now: Boolean = false,
    val past: Boolean = false,
)

data class AutomationItem(
    val id: String,
    val name: String,
    /** "Weekdays 10:30–13:00, when it arrives: …", or the agent's own description. */
    val schedule: String,
    /** "in 25 min", "Tomorrow 08:00", or null when paused or unknown. */
    val next: String? = null,
    val paused: Boolean = false,
    /** "Ran 11:42", "Checked 12:10, nothing yet", "Failed 09:00: …", "Blocked 09:00: …". */
    val last: String? = null,
    val failed: Boolean = false,
    /** Its last run was refused something that needs approval: Run in chat can answer it. */
    val blocked: Boolean = false,
    /** Made by asking the agent, in Talaria or elsewhere. */
    val byAgent: Boolean = false,
    /** "Home", "A new chat" or "Log only". */
    val resultTo: String = "",
)

/** What "Your day" shows: a result from today. [blocked] is what the agent wasn't allowed to do on its own. */
data class DayResult(
    val name: String, val text: String, val time: String, val failed: Boolean = false, val conversationId: String? = null,
    val id: String = "", val blocked: String? = null,
)

/** A line in "Next up": a calendar event or an automation about to run. */
data class NextItem(
    /** "10:00". */
    val time: String,
    val title: String,
    /** "in 25 min", or "Now". */
    val until: String,
    val automation: Boolean = false,
)

enum class WhenKind(val label: String) { TIME("At a time"), ARRIVES("When something arrives"), AFTER_EVENT("After a calendar event") }

/** The New automation form. */
data class AutomationDraft(
    val name: String = "",
    val kind: WhenKind = WhenKind.TIME,
    /** For TIME: a cron expression, "every 2h", or "weekdays at 9am". */
    val schedule: String = "",
    val watch: String = "",
    val from: String = "",
    val until: String = "",
    val days: Set<String> = WEEKDAYS,
    val fallback: String = "",
    val event: String = "",
    val delayMinutes: Int = 15,
    val task: String = "",
    /** home, chat or log. */
    val resultTo: String = "home",
) {
    val ready: Boolean
        get() = name.isNotBlank() && task.isNotBlank() && when (kind) {
            WhenKind.TIME -> schedule.isNotBlank()
            WhenKind.ARRIVES -> watch.isNotBlank() && HHMM.matches(from) && HHMM.matches(until) && until > from && days.isNotEmpty()
            WhenKind.AFTER_EVENT -> event.isNotBlank() && delayMinutes in 0..240 && days.isNotEmpty()
        }

    companion object {
        val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        val WEEKDAYS = setOf("mon", "tue", "wed", "thu", "fri")
        private val HHMM = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")

        /** The weekday summary of the morning catch-up, from the Gemini transcript that arrives by email. */
        val MORNING_SUMMARY = AutomationDraft(
            name = "Morning summary",
            kind = WhenKind.ARRIVES,
            watch = "an email from gemini-notes@google.com with the notes or transcript of today's company catch-up call",
            from = "10:30",
            until = "13:00",
            days = WEEKDAYS,
            fallback = "Summarise my day from my calendar only, and say the catch-up transcript hasn't arrived yet.",
            task = "Summarise today's catch-up from the transcript: the decisions, anything assigned to me, and what I " +
                "should know. Then list the rest of today's calendar with times. Keep it short enough to read on a phone.",
            resultTo = "home",
        )
    }
}
