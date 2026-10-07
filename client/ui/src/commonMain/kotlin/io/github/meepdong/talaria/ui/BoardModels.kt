package io.github.meepdong.talaria.ui

/** Hermes's Kanban board (spec §18.4), shown in the To-dos tab beside the owner's own list. */
data class BoardView(
    /** False when the bridge has no board (no doorway to Hermes's backend). */
    val available: Boolean = false,
    /** The To-dos tab shows the board instead of the list. */
    val showing: Boolean = false,
    /** Columns that have tasks, in the board's order. */
    val columns: List<BoardColumnView> = emptyList(),
    /** Who a task can go to: (id, name); the first is Hermes itself. */
    val people: List<Pair<String, String>> = emptyList(),
    val openTask: String? = null,
    val detail: BoardDetail? = null,
    val notice: String? = null,
    val taskCount: Int = 0,
)

data class BoardColumnView(val name: String, val label: String, val tasks: List<BoardTaskItem>)

data class BoardTaskItem(
    val id: String, val title: String, val status: String,
    /** Who has it ("Meeting Minder"), or null for nobody. */
    val who: String?,
    val summary: String?, val error: String?, val comments: Int,
    /** "3 h ago", when it was made or finished. */
    val age: String,
)

data class BoardDetail(
    val id: String, val body: String?, val result: String?,
    /** (author, text, "10 min ago") */
    val comments: List<Triple<String, String, String>>,
    /** Where it can move: (status, label). */
    val moves: List<Pair<String, String>>,
)

/** What Hermes spent (spec §18.5), on the Server page. */
data class UsageView(
    val days: Int = 7,
    val loading: Boolean = false,
    /** "$6.65 (estimate)" */
    val total: String = "",
    /** "874 calls · 77 chats · 4.6M tokens in, 351k out" */
    val detail: String = "",
    val byDay: List<UsageBar> = emptyList(),
    /** (model, "$2.23 · 508 calls") */
    val byModel: List<Pair<String, String>> = emptyList(),
)

/** One day: "Mon 5", "$1.06", and its share of the busiest day. */
data class UsageBar(val label: String, val cost: String, val fraction: Float)

val BOARD_LABELS = mapOf(
    "triage" to "Ideas", "todo" to "To do", "scheduled" to "Scheduled", "ready" to "Ready to start",
    "running" to "Working on it", "blocked" to "Blocked", "review" to "To check", "done" to "Done", "archived" to "Archive",
)

/** Bots' routines (spec §18.6), on the Schedule tab below Talaria's own automations. */
data class RoutinesView(
    val items: List<RoutineItem> = emptyList(),
    /** Bots a new routine can be for: (id, name). */
    val bots: List<Pair<String, String>> = emptyList(),
    val notice: String? = null,
)

data class RoutineItem(
    val id: String, val botId: String,
    /** "Meeting Minder", or "Hermes" for the owner's own assistant. */
    val who: String,
    val name: String, val schedule: String, val task: String, val on: Boolean,
    /** "Tomorrow 09:00" */
    val next: String?,
    /** "Ran 09:00", "Failed Mon 09:00: …" */
    val last: String?,
    val failed: Boolean,
    /** Its results go to the bot's chat. */
    val toChat: Boolean,
)

/** A helper agent working for a bot right now (spec §18.7). */
data class HelperItem(
    val id: String, val goal: String,
    /** "running · 3 tools · web_search" */
    val detail: String,
    val canSteer: Boolean,
)
