package io.github.meepdong.talaria.todos

import io.github.meepdong.talaria.chat.ChatApi
import io.github.meepdong.talaria.session.RpcException
import io.github.meepdong.talaria.session.TnpException
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One to-do (spec/README.md §13). Times are Unix seconds; [due] is `YYYY-MM-DD`. */
data class Todo(
    val id: String,
    val text: String,
    val done: Boolean,
    val createdAt: Long,
    val doneAt: Long? = null,
    val due: String? = null,
    /** The conversation it was handed to the agent in. */
    val conversationId: String? = null,
)

data class TodosState(
    /** Open first, oldest first, then recently done. */
    val todos: List<Todo> = emptyList(),
    val loaded: Boolean = false,
    val error: String? = null,
    /** False when the bridge keeps no to-dos (an older bridge). */
    val available: Boolean = true,
)

/** The to-do list kept on the bridge, the same on every device. */
class TodosRepository(private val scope: CoroutineScope, private val api: ChatApi) {
    private val _state = MutableStateFlow(TodosState())
    val state: StateFlow<TodosState> = _state.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            launch {
                api.notifications.collect { msg ->
                    if (msg.str("method") == "todos.changed") msg.obj("params")?.let { p -> _state.update { it.copy(todos = list(p), loaded = true) } }
                }
            }
            launch { api.sessions.collect { refresh() } }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun refresh() {
        call("todos.list", JsonObject(emptyMap())) { r -> _state.update { it.copy(todos = list(r), loaded = true, available = true) } }
    }

    fun add(text: String, due: String? = null) {
        val t = text.trim()
        if (t.isEmpty()) return
        call("todos.add", buildJsonObject {
            put("text", t.take(MAX_TEXT))
            due?.let { put("due", it) }
        }) { r -> r.obj("todo")?.let(::todo)?.let(::upsert) }
    }

    fun setDone(id: String, done: Boolean) {
        // ticked at once; the bridge's answer (or todos.changed) settles it
        _state.update { s -> s.copy(todos = s.todos.map { if (it.id == id) it.copy(done = done) else it }) }
        call("todos.update", buildJsonObject {
            put("id", id)
            put("done", done)
        }) { r -> r.obj("todo")?.let(::todo)?.let(::upsert) }
    }

    fun edit(id: String, text: String? = null, due: String? = null, clearDue: Boolean = false) {
        call("todos.update", buildJsonObject {
            put("id", id)
            text?.trim()?.takeIf { it.isNotEmpty() }?.let { put("text", it.take(MAX_TEXT)) }
            if (clearDue) put("due", JsonNull) else due?.let { put("due", it) }
        }) { r -> r.obj("todo")?.let(::todo)?.let(::upsert) }
    }

    fun delete(id: String) {
        _state.update { s -> s.copy(todos = s.todos.filterNot { it.id == id }) }
        call("todos.delete", buildJsonObject { put("id", id) }) {}
    }

    private fun upsert(t: Todo) {
        _state.update { s ->
            val todos = if (s.todos.any { it.id == t.id }) s.todos.map { if (it.id == t.id) t else it } else s.todos + t
            s.copy(todos = todos.sortedWith(ORDER), error = null)
        }
    }

    private fun call(method: String, params: JsonObject, onResult: (JsonObject) -> Unit) {
        scope.launch {
            try {
                onResult(api.request(method, params))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RpcException) {
                if (e.code == METHOD_NOT_FOUND) _state.update { it.copy(available = false) }
                else _state.update { it.copy(error = e.message) }
                if (method != "todos.list") refresh()
            } catch (e: TnpException) {
                _state.update { it.copy(error = "Not connected to the bridge") }
            }
        }
    }

    private fun list(o: JsonObject): List<Todo> =
        (o["todos"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::todo) }

    private fun todo(o: JsonObject): Todo? = Todo(
        id = o.str("id") ?: return null,
        text = o.str("text") ?: return null,
        done = (o["done"] as? JsonPrimitive)?.booleanOrNull == true,
        createdAt = o.long("created_at") ?: 0,
        doneAt = o.long("done_at"),
        due = o.str("due"),
        conversationId = o.str("conversation_id"),
    )

    companion object {
        const val MAX_TEXT = 500
        private const val METHOD_NOT_FOUND = -32601

        /** Open first, oldest first; then done, newest first. */
        val ORDER: Comparator<Todo> = compareBy<Todo> { it.done }
            .thenBy { if (it.done) -(it.doneAt ?: 0) else it.createdAt }
    }
}
