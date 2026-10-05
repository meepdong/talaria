package io.github.meepdong.talaria.schedule

import io.github.meepdong.talaria.chat.ChatApi
import io.github.meepdong.talaria.session.RpcException
import io.github.meepdong.talaria.session.TnpException
import io.github.meepdong.talaria.session.long
import io.github.meepdong.talaria.session.obj
import io.github.meepdong.talaria.session.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** When an automation runs (spec/README.md §14). */
sealed interface When {
    /** A cron expression (`30 7 * * 1-5`), an interval (`every 2h`) or an ISO time for one run. */
    data class Time(val schedule: String) : When

    /** When [watch] arrives, checked between [from] and [until] (HH:MM) on [days]; [fallback] at [until] if not. */
    data class Arrives(val watch: String, val from: String, val until: String, val days: List<String>, val fallback: String? = null) : When

    data class AfterEvent(val event: String, val delayMinutes: Int, val days: List<String>) : When

    /** A job the agent made some other way. */
    data object Other : When
}

data class Automation(
    val id: String,
    val name: String,
    val `when`: When,
    val task: String,
    /** home, chat or log. */
    val resultTo: String,
    /** talaria or agent. */
    val madeIn: String,
    /** scheduled, paused, running, completed or error. */
    val state: String,
    val scheduleText: String,
    val nextRunAt: Long? = null,
    val lastRunAt: Long? = null,
    /** ok, error, nothing or blocked. */
    val lastStatus: String? = null,
    val lastError: String? = null,
) {
    val paused get() = state == "paused"
}

data class AutomationRun(
    val at: Long,
    /** ok, error, nothing or blocked. */
    val status: String,
    val text: String? = null,
    val error: String? = null,
    val conversationId: String? = null,
    /** What the agent wasn't allowed to do with nobody there to approve it, when [status] is blocked. */
    val blocked: String? = null,
)

/** A run that finished, as `automations.ran` says it. */
data class AutomationRan(val id: String, val name: String, val resultTo: String, val run: AutomationRun, val read: Boolean = false) {
    /** Home shows it, and the apps notify: results for Home, and runs that were blocked. */
    val forHome get() = resultTo == "home" || run.status == "blocked"
}

/** One calendar event. [start] and [end] are ISO 8601 times, or dates for all-day events. */
data class CalendarEvent(val title: String, val start: String, val end: String, val allDay: Boolean, val location: String? = null)

data class ScheduleState(
    val automations: List<Automation> = emptyList(),
    val loaded: Boolean = false,
    /** False when the bridge has no automations (an older bridge, or no agent with jobs). */
    val available: Boolean = true,
    val error: String? = null,
    /** Today's events, and why there are none when the calendar couldn't be read. */
    val events: List<CalendarEvent> = emptyList(),
    val calendarError: String? = null,
    /** Today's results for Home, newest first. */
    val today: List<AutomationRan> = emptyList(),
    /** [today] came from the bridge (home.get or home.changed), so a result missing from it was read or archived. */
    val homeLoaded: Boolean = false,
    /** What was archived off Home in the last month, newest first; null until asked for. */
    val archived: List<AutomationRan>? = null,
    /** Asking the agent to set one up from words. */
    val describing: Boolean = false,
    val describeReply: String? = null,
)

/** The agent's automations, today's calendar and Home's results, kept in step with the bridge. */
class ScheduleRepository(private val scope: CoroutineScope, private val api: ChatApi) {
    private val _state = MutableStateFlow(ScheduleState())
    val state: StateFlow<ScheduleState> = _state.asStateFlow()
    private val _ran = MutableSharedFlow<AutomationRan>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every run that finished with something to say, for notifications. */
    val ran: SharedFlow<AutomationRan> = _ran.asSharedFlow()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            launch {
                api.notifications.collect { msg ->
                    val p = msg.obj("params") ?: return@collect
                    when (msg.str("method")) {
                        "automations.changed" -> _state.update { it.copy(automations = automations(p), loaded = true) }
                        "automations.ran" -> ran(p)?.let { r ->
                            if (r.forHome) _state.update { s -> s.copy(today = listOf(r) + s.today.filterNot { it.id == r.id }) }
                            _ran.tryEmit(r)
                        }
                        "home.changed" -> _state.update { it.copy(today = homeResults(p), homeLoaded = true) }
                    }
                }
            }
            launch { api.sessions.collect { refresh() } }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** The list, today's calendar and Home's results. */
    fun refresh() {
        call("automations.list", JsonObject(emptyMap())) { r -> _state.update { it.copy(automations = automations(r), loaded = true, available = true) } }
        call("calendar.day", JsonObject(emptyMap())) { r ->
            val events = (r["events"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::event) }
            _state.update { it.copy(events = events, calendarError = r.str("error")) }
        }
        call("home.get", JsonObject(emptyMap())) { r -> _state.update { it.copy(today = homeResults(r), homeLoaded = true) } }
    }

    /** `home.get`'s result or `home.changed`'s params: Home's runs for today. */
    private fun homeResults(r: JsonObject): List<AutomationRan> =
        (r["results"] as? JsonArray).orEmpty().mapNotNull { e ->
            (e as? JsonObject)?.let { o -> ran(buildJsonObject {
                o["id"]?.let { put("id", it) }
                o["name"]?.let { put("name", it) }
                put("result_to", "home")
                o["run"]?.let { put("run", it) }
                o["read"]?.let { put("read", it) }
            }) }
        }

    fun add(name: String, `when`: When, task: String, resultTo: String = "home", done: (String?) -> Unit = {}) {
        call("automations.add", buildJsonObject {
            put("name", name.trim())
            put("when", json(`when`))
            put("task", task.trim())
            put("result_to", resultTo)
        }, onError = done) { r ->
            r.obj("automation")?.let(::automation)?.let(::upsert)
            done(null)
        }
    }

    /** Ask the agent to set one up from words; its answer lands in [ScheduleState.describeReply]. */
    fun describe(text: String) {
        if (text.isBlank() || _state.value.describing) return
        _state.update { it.copy(describing = true, describeReply = null) }
        call("automations.describe", buildJsonObject { put("text", text.trim()) }, timeoutMs = DESCRIBE_TIMEOUT_MS,
            onError = { e -> _state.update { it.copy(describing = false, describeReply = e) } }) { r ->
            _state.update { it.copy(describing = false, describeReply = r.str("reply").orEmpty(), automations = automations(r)) }
        }
    }

    fun clearReply() = _state.update { it.copy(describeReply = null) }

    fun setPaused(id: String, paused: Boolean) {
        _state.update { s -> s.copy(automations = s.automations.map { if (it.id == id) it.copy(state = if (paused) "paused" else "scheduled") else it }) }
        call("automations.update", buildJsonObject {
            put("id", id)
            put("paused", paused)
        }) { r -> r.obj("automation")?.let(::automation)?.let(::upsert) }
    }

    fun setResultTo(id: String, resultTo: String) {
        call("automations.update", buildJsonObject {
            put("id", id)
            put("result_to", resultTo)
        }) { r -> r.obj("automation")?.let(::automation)?.let(::upsert) }
    }

    fun runNow(id: String) {
        call("automations.run", buildJsonObject { put("id", id) }) { r -> r.obj("automation")?.let(::automation)?.let(::upsert) }
    }

    /**
     * Run the automation's task now in a new conversation, where approvals can be answered;
     * [opened] gets the conversation's id.
     */
    fun runInChat(id: String, opened: (String) -> Unit) {
        call("automations.run_in_chat", buildJsonObject { put("id", id) }) { r -> r.str("conversation_id")?.let(opened) }
    }

    fun delete(id: String) {
        _state.update { s -> s.copy(automations = s.automations.filterNot { it.id == id }) }
        call("automations.delete", buildJsonObject { put("id", id) }) {}
    }

    /** Take one run off Home on every device (`home.dismiss`); this device drops it at once. */
    fun dismissHomeItem(id: String, at: Long) {
        _state.update { s -> s.copy(today = s.today.filterNot { it.id == id && it.run.at == at }) }
        call("home.dismiss", buildJsonObject {
            put("id", id)
            put("at", at)
        }) {}
    }

    /** Read, or unread again, on every device. */
    fun markHomeRead(id: String, at: Long, read: Boolean) {
        _state.update { s -> s.copy(today = s.today.map { if (it.id == id && it.run.at == at) it.copy(read = read) else it }) }
        call("home.read", buildJsonObject {
            put("id", id)
            put("at", at)
            put("read", read)
        }) {}
    }

    /** Put an archived run back: Home shows it again while it's today's latest. */
    fun restoreHomeItem(id: String, at: Long) {
        _state.update { s -> s.copy(archived = s.archived?.filterNot { it.id == id && it.run.at == at }) }
        call("home.restore", buildJsonObject {
            put("id", id)
            put("at", at)
        }) {}
    }

    /** What was archived off Home, into [ScheduleState.archived]. */
    fun loadArchived() {
        call("home.archived", JsonObject(emptyMap())) { r -> _state.update { it.copy(archived = homeResults(r)) } }
    }

    fun closeArchived() = _state.update { it.copy(archived = null) }

    private fun upsert(a: Automation) = _state.update { s ->
        val list = if (s.automations.any { it.id == a.id }) s.automations.map { if (it.id == a.id) a else it } else s.automations + a
        s.copy(automations = list, error = null)
    }

    private fun call(
        method: String, params: JsonObject, timeoutMs: Long? = null, onError: (String) -> Unit = {},
        onResult: (JsonObject) -> Unit,
    ) {
        scope.launch {
            val error = try {
                onResult(api.request(method, params, timeoutMs))
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: RpcException) {
                if (e.code == METHOD_NOT_FOUND) _state.update { it.copy(available = false) }
                e.message ?: "The bridge refused it"
            } catch (e: TnpException) {
                "Not connected to the bridge"
            }
            if (error != null) {
                if (method.startsWith("automations.")) _state.update { it.copy(error = error) }
                onError(error)
            }
        }
    }

    companion object {
        const val DESCRIBE_TIMEOUT_MS = 180_000L
        private const val METHOD_NOT_FOUND = -32601
        val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

        fun json(w: When): JsonObject = buildJsonObject {
            when (w) {
                is When.Time -> {
                    put("kind", "time")
                    put("schedule", w.schedule.trim())
                }
                is When.Arrives -> {
                    put("kind", "arrives")
                    put("watch", w.watch.trim())
                    put("from", w.from)
                    put("until", w.until)
                    putJsonArray("days") { DAYS.filter { it in w.days }.forEach { add(JsonPrimitive(it)) } }
                    w.fallback?.trim()?.takeIf { it.isNotEmpty() }?.let { put("fallback", it) }
                }
                is When.AfterEvent -> {
                    put("kind", "after_event")
                    put("event", w.event.trim())
                    put("delay_minutes", w.delayMinutes)
                    putJsonArray("days") { DAYS.filter { it in w.days }.forEach { add(JsonPrimitive(it)) } }
                }
                When.Other -> put("kind", "other")
            }
        }

        private fun strings(o: JsonObject, key: String): List<String> =
            (o[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

        fun parseWhen(o: JsonObject?): When = when (o?.str("kind")) {
            "time" -> When.Time(o.str("schedule").orEmpty())
            "arrives" -> When.Arrives(o.str("watch").orEmpty(), o.str("from").orEmpty(), o.str("until").orEmpty(),
                strings(o, "days"), o.str("fallback"))
            "after_event" -> When.AfterEvent(o.str("event").orEmpty(), (o["delay_minutes"] as? JsonPrimitive)?.intOrNull ?: 0,
                strings(o, "days"))
            else -> When.Other
        }

        fun automation(o: JsonObject): Automation? = Automation(
            id = o.str("id") ?: return null,
            name = o.str("name") ?: return null,
            `when` = parseWhen(o.obj("when")),
            task = o.str("task").orEmpty(),
            resultTo = o.str("result_to") ?: "log",
            madeIn = o.str("made_in") ?: "agent",
            state = o.str("state") ?: "scheduled",
            scheduleText = o.str("schedule_text").orEmpty(),
            nextRunAt = o.long("next_run_at"),
            lastRunAt = o.long("last_run_at"),
            lastStatus = o.str("last_status"),
            lastError = o.str("last_error"),
        )

        private fun automations(o: JsonObject): List<Automation> =
            (o["automations"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::automation) }

        fun run(o: JsonObject): AutomationRun? = AutomationRun(
            at = o.long("at") ?: return null,
            status = o.str("status") ?: return null,
            text = o.str("text"),
            error = o.str("error"),
            conversationId = o.str("conversation_id"),
            blocked = o.str("blocked"),
        )

        fun ran(o: JsonObject): AutomationRan? = AutomationRan(
            id = o.str("id") ?: return null,
            name = o.str("name") ?: return null,
            resultTo = o.str("result_to") ?: "log",
            run = o.obj("run")?.let(::run) ?: return null,
            read = (o["read"] as? JsonPrimitive)?.contentOrNull == "true",
        )

        fun event(o: JsonObject): CalendarEvent? = CalendarEvent(
            title = o.str("title") ?: return null,
            start = o.str("start") ?: return null,
            end = o.str("end") ?: o.str("start")!!,
            allDay = (o["all_day"] as? JsonPrimitive)?.contentOrNull == "true",
            location = o.str("location"),
        )
    }
}
