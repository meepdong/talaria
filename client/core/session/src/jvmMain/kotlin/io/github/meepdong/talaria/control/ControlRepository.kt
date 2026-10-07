package io.github.meepdong.talaria.control

import io.github.meepdong.talaria.chat.ChatApi
import io.github.meepdong.talaria.session.long
import io.github.meepdong.talaria.session.obj
import io.github.meepdong.talaria.session.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * A task on Hermes's Kanban board (spec/README.md §18.4). [assignee] is a bot id, "assistant" (the owner's own
 * assistant) or null; [summary] is the worker's latest note, [error] its last failure.
 */
data class BoardTask(
    val id: String, val title: String, val status: String, val priority: Int = 0, val createdAt: Long = 0,
    val body: String? = null, val assignee: String? = null, val startedAt: Long? = null, val completedAt: Long? = null,
    val summary: String? = null, val result: String? = null, val error: String? = null, val comments: Int = 0,
)

data class BoardColumn(val name: String, val tasks: List<BoardTask>)

data class BoardComment(val author: String, val text: String, val at: Long)

/** What Hermes spent (§18.5): the whole period, by day (oldest first) and by model (costliest first). */
data class Usage(val costUsd: Double, val estimated: Boolean, val inputTokens: Long, val outputTokens: Long,
                 val sessions: Int, val calls: Int)
data class UsageReport(val days: Int, val total: Usage, val byDay: List<Pair<String, Usage>>, val byModel: List<Pair<String, Usage>>)

data class ControlState(
    /** False when the bridge has no board (no doorway, or an older bridge). */
    val boardAvailable: Boolean = false,
    val columns: List<BoardColumn> = emptyList(),
    val boardLoaded: Boolean = false,
    /** The task opened for its details, with its comments once loaded. */
    val openTask: String? = null,
    val comments: Map<String, List<BoardComment>> = emptyMap(),
    val usage: UsageReport? = null,
    val usageLoading: Boolean = false,
    val notice: String? = null,
    /** Last add's warning, e.g. nothing will pick the task up yet. */
    val warning: String? = null,
) {
    val tasks: List<BoardTask> get() = columns.flatMap { it.tasks }
}

/** Hermes's Kanban board and usage through the bridge (§18.4–18.5); the bridge pushes board.changed while watched. */
class ControlRepository(private val scope: CoroutineScope, private val api: ChatApi) {
    private val _state = MutableStateFlow(ControlState())
    val state: StateFlow<ControlState> = _state.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            launch { api.notifications.collect { runCatching { onNotification(it) } } }
            launch { api.sessions.collect { if (_state.value.boardLoaded) refreshBoard() } }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun refreshBoard() = launchCall("Couldn't load the board") {
        val r = api.request("board.get", JsonObject(emptyMap()))
        val available = (r["available"] as? JsonPrimitive)?.booleanOrNull ?: false
        _state.update { it.copy(boardAvailable = true, boardLoaded = available, columns = parseColumns(r["columns"] as? JsonArray)) }
    }

    /** Probe once: is there a board? (An older bridge or no doorway: no.) */
    fun probe() = scope.launch {
        try {
            api.request("board.get", JsonObject(emptyMap())).let { r ->
                _state.update { it.copy(boardAvailable = true, columns = parseColumns(r["columns"] as? JsonArray),
                    boardLoaded = (r["available"] as? JsonPrimitive)?.booleanOrNull ?: false) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(boardAvailable = false) }
        }
    }

    fun add(title: String, body: String?, assignee: String?) {
        if (title.isBlank()) return
        launchCall("Couldn't add the task") {
            val r = api.request("board.add", buildJsonObject {
                put("title", title.trim())
                body?.takeIf { it.isNotBlank() }?.let { put("body", it.trim()) }
                assignee?.let { put("assignee", it) }
            })
            r.obj("task")?.let(::parseTask)?.let(::upsert)
            _state.update { it.copy(warning = r.str("warning")) }
        }
    }

    fun move(taskId: String, status: String) = update(taskId, "Couldn't move the task") { put("status", status) }

    /** [assignee] a bot id, "assistant", or "" for nobody. */
    fun give(taskId: String, assignee: String) = update(taskId, "Couldn't give the task") { put("assignee", assignee) }

    fun comment(taskId: String, text: String) {
        if (text.isBlank()) return
        launchCall("Couldn't comment") {
            api.request("board.comment", buildJsonObject {
                put("task_id", taskId)
                put("text", text.trim())
            })
            loadTask(taskId)
        }
    }

    /** Show a task's details (null closes them); its comments load. */
    fun open(taskId: String?) {
        _state.update { it.copy(openTask = taskId) }
        if (taskId != null) launchCall("Couldn't open the task") { loadTask(taskId) }
    }

    fun loadUsage(days: Int) {
        _state.update { it.copy(usageLoading = true) }
        scope.launch {
            try {
                val r = api.request("usage.get", buildJsonObject { put("days", days) })
                _state.update { it.copy(usage = parseUsage(r), usageLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(usageLoading = false, notice = "Couldn't load usage: ${e.message}") }
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null, warning = null) }

    private suspend fun loadTask(taskId: String) {
        val r = api.request("board.task", buildJsonObject { put("task_id", taskId) })
        r.obj("task")?.let(::parseTask)?.let(::upsert)
        val comments = (r["comments"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            BoardComment(o.str("author").orEmpty(), o.str("text").orEmpty(), o.long("at") ?: 0)
        }
        _state.update { it.copy(comments = it.comments + (taskId to comments)) }
    }

    private fun update(taskId: String, failure: String, change: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        launchCall(failure) {
            val r = api.request("board.update", buildJsonObject {
                put("task_id", taskId)
                change()
            })
            r.obj("task")?.let(::parseTask)?.let(::upsert)
        }

    /** A task as it is now, in its column (archived: off the board). */
    private fun upsert(t: BoardTask) = _state.update { s ->
        val names = s.columns.map { it.name }.ifEmpty { COLUMNS }
        s.copy(columns = names.map { name ->
            val others = s.columns.firstOrNull { it.name == name }?.tasks.orEmpty().filter { it.id != t.id }
            BoardColumn(name, if (name == t.status) listOf(t) + others else others)
        })
    }

    private fun launchCall(failure: String, body: suspend () -> Unit) = scope.launch {
        try {
            body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(notice = "$failure: ${e.message}") }
        }
    }

    private fun onNotification(msg: JsonObject) {
        if (msg.str("method") != "board.changed") return
        val p = msg.obj("params") ?: return
        _state.update { it.copy(boardAvailable = true, boardLoaded = true, columns = parseColumns(p["columns"] as? JsonArray)) }
    }

    companion object {
        val COLUMNS = listOf("triage", "todo", "scheduled", "ready", "running", "blocked", "review", "done")

        fun parseColumns(a: JsonArray?): List<BoardColumn> = a.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            BoardColumn(o.str("name") ?: return@mapNotNull null, (o["tasks"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseTask) })
        }

        fun parseTask(o: JsonObject): BoardTask? = BoardTask(
            id = o.str("id") ?: return null, title = o.str("title").orEmpty(), status = o.str("status") ?: "todo",
            priority = (o["priority"] as? JsonPrimitive)?.intOrNull ?: 0, createdAt = o.long("created_at") ?: 0,
            body = o.str("body"), assignee = o.str("assignee"), startedAt = o.long("started_at"), completedAt = o.long("completed_at"),
            summary = o.str("summary"), result = o.str("result"), error = o.str("error"),
            comments = (o["comments"] as? JsonPrimitive)?.intOrNull ?: 0,
        )

        private fun usage(o: JsonObject) = Usage(
            costUsd = (o["cost_usd"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            estimated = (o["estimated"] as? JsonPrimitive)?.booleanOrNull ?: false,
            inputTokens = o.long("input_tokens") ?: 0, outputTokens = o.long("output_tokens") ?: 0,
            sessions = (o["sessions"] as? JsonPrimitive)?.intOrNull ?: 0, calls = (o["calls"] as? JsonPrimitive)?.intOrNull ?: 0,
        )

        fun parseUsage(r: JsonObject): UsageReport = UsageReport(
            days = (r["days"] as? JsonPrimitive)?.intOrNull ?: 0,
            total = r.obj("total")?.let(::usage) ?: Usage(0.0, false, 0, 0, 0, 0),
            byDay = (r["by_day"] as? JsonArray).orEmpty().mapNotNull { e -> (e as? JsonObject)?.let { o -> (o.str("day") ?: return@mapNotNull null) to usage(o) } },
            byModel = (r["by_model"] as? JsonArray).orEmpty().mapNotNull { e -> (e as? JsonObject)?.let { o -> (o.str("model") ?: return@mapNotNull null) to usage(o) } },
        )
    }
}
