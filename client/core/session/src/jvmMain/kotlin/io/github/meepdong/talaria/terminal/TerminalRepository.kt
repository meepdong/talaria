package io.github.meepdong.talaria.terminal

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One of root's tmux sessions (spec/README.md §16.1); [activity] in Unix seconds. */
data class TmuxSession(
    val name: String, val command: String, val path: String, val cols: Int, val rows: Int, val attached: Int, val activity: Long,
)

/** A session's screen: lines joined by \n with SGR colour sequences. */
data class TermScreen(
    val session: String, val cols: Int, val rows: Int, val cursorX: Int, val cursorY: Int, val command: String,
    val control: Boolean, val text: String,
    /** A full-screen program (Claude Code, vim) draws in the alternate screen, which has no scrollback: page it instead. */
    val alternate: Boolean = false,
)

/** A key for [TerminalRepository.keys]: literal text, or a named key such as Enter or C-c. */
sealed interface TermKey {
    data class Text(val text: String) : TermKey
    data class Key(val name: String) : TermKey
}

data class TerminalState(
    val sessions: List<TmuxSession> = emptyList(),
    val loading: Boolean = false,
    /** The session being opened or shown, or null on the list. */
    val session: String? = null,
    /** Asked for control (typing), not just watching. */
    val control: Boolean = false,
    /** The approval this device has to give before the terminal opens. */
    val requestId: String? = null,
    val grant: String? = null,
    val screen: TermScreen? = null,
    /** Why the terminal closed (the session ended, the grant expired). */
    val closed: String? = null,
    val error: String? = null,
    /** Scrollback above the screen, oldest first, once asked for ([TerminalRepository.history]). */
    val history: String? = null,
    val loadingHistory: Boolean = false,
    /** tmux.kill requests waiting for an approval: request id → session. */
    val ending: Map<String, String> = emptyMap(),
)

/**
 * Terminals (§16.1): root's tmux sessions, opened with an approval this device signs. The grant comes back in the
 * ops.result this device approved; the bridge then streams the screen and takes keys for it.
 */
class TerminalRepository(
    private val scope: CoroutineScope,
    private val api: ChatApi,
    /** This device: only grants it approved can be used from it. */
    private val deviceId: String,
) {
    private val _state = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = _state.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            launch { api.notifications.collect(::onNotification) }
            // after a reconnect the bridge has forgotten what this device watched: ask again with the same grant
            launch { api.sessions.collect { _state.value.grant?.let(::watch) } }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        call("ops.run", buildJsonObject {
            put("op", "tmux.sessions")
            put("params", JsonObject(emptyMap()))
        }) { r ->
            val list = (r.obj("result")?.obj("data")?.get("sessions") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::session) }
            _state.update { it.copy(sessions = list, loading = false) }
        }
    }

    /** Ask to open [session] for typing (one approval, spec §16.1); once this device approves it, the screen follows. */
    fun open(session: String, control: Boolean = true) {
        // a watch going on stays on screen until the new grant arrives
        _state.update { it.copy(session = session, control = control, requestId = null, closed = null, error = null,
            screen = if (it.session == session) it.screen else null, history = if (it.session == session) it.history else null) }
        call("ops.run", buildJsonObject {
            put("op", if (control) "terminal.control" else "terminal.watch")
            put("params", buildJsonObject { put("session", session) })
        }) { r -> r.str("request_id")?.let { id -> _state.update { it.copy(requestId = id) } } }
    }

    /** A new root tmux session: [folder] and [command] can be anything (empty command: a shell); one approval opens it. */
    fun create(name: String, folder: String, command: String) {
        _state.update { it.copy(session = name, control = true, requestId = null, closed = null, error = null, screen = null, history = null) }
        call("ops.run", buildJsonObject {
            put("op", "tmux.new")
            put("params", buildJsonObject {
                put("name", name)
                put("folder", folder)
                put("command", command)
            })
        }) { r -> r.str("request_id")?.let { id -> _state.update { it.copy(requestId = id) } } }
    }

    /** End a session (and everything running in it), after an approval. */
    fun end(session: String) {
        call("ops.run", buildJsonObject {
            put("op", "tmux.kill")
            put("params", buildJsonObject { put("session", session) })
        }) { r -> r.str("request_id")?.let { id -> _state.update { it.copy(ending = it.ending + (id to session)) } } }
    }

    /** The scrollback above the screen (up to [lines]); a full-screen program has none. */
    fun history(lines: Int = 3000) {
        val grant = _state.value.grant ?: return
        _state.update { it.copy(loadingHistory = true) }
        call("term.history", buildJsonObject {
            put("grant", grant)
            put("lines", lines)
        }) { r -> _state.update { it.copy(history = r.str("text").orEmpty(), loadingHistory = false) } }
    }

    fun keys(keys: List<TermKey>) {
        val grant = _state.value.grant?.takeIf { _state.value.screen?.control == true } ?: return
        call("term.keys", buildJsonObject {
            put("grant", grant)
            put("keys", buildJsonArray {
                keys.forEach { k ->
                    add(buildJsonObject { if (k is TermKey.Text) put("text", k.text) else put("key", (k as TermKey.Key).name) })
                }
            })
        }) {}
    }

    /** Back to the list: stop watching and end the grant. */
    fun close() {
        _state.value.grant?.let(::stopGrant)
        _state.update { it.copy(session = null, control = false, requestId = null, grant = null, screen = null, closed = null,
            history = null, loadingHistory = false) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun watch(grant: String) {
        call("term.watch", buildJsonObject { put("grant", grant) }) {}
    }

    private fun stopGrant(grant: String) {
        call("term.stop", buildJsonObject { put("grant", grant) }) {}
    }

    private fun onNotification(msg: JsonObject) {
        val p = msg.obj("params") ?: return
        when (msg.str("method")) {
            "ops.result" -> {
                val s = _state.value
                val result = p.obj("result") ?: return
                s.ending[p.str("request_id")]?.let {
                    _state.update { st -> st.copy(ending = st.ending - p.str("request_id")!!) }
                    refresh()
                    return
                }
                if (p.str("request_id") != s.requestId || p.str("approved_by") != deviceId) return
                val ok = (result["ok"] as? JsonPrimitive)?.booleanOrNull == true
                val grant = result.obj("data")?.str("grant")
                if (!ok || grant == null) {
                    _state.update { it.copy(requestId = null, error = result.str("summary") ?: "The terminal didn't open") }
                    return
                }
                val old = s.grant
                _state.update { it.copy(requestId = null, grant = grant) }
                watch(grant)
                if (old != null && old != grant) stopGrant(old)
            }
            "ops.approval.done" -> if (p.str("choice") != "once" && p.str("request_id") in _state.value.ending) {
                _state.update { it.copy(ending = it.ending - p.str("request_id")!!) }
            } else if (p.str("request_id") == _state.value.requestId && p.str("choice") != "once") {
                // denied, or nobody answered in time
                _state.update { it.copy(requestId = null, session = if (it.grant == null) null else it.session, control = false) }
            }
            "term.screen" -> {
                if (p.str("grant") != _state.value.grant) return
                screen(p)?.let { sc -> _state.update { it.copy(screen = sc, closed = null) } }
            }
            "term.closed" -> {
                if (p.str("grant") != _state.value.grant) return
                _state.update { it.copy(grant = null, closed = p.str("reason") ?: "The terminal closed") }
            }
        }
    }

    private fun call(method: String, params: JsonObject, onResult: (JsonObject) -> Unit) {
        scope.launch {
            val error = try {
                onResult(api.request(method, params))
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: RpcException) {
                if (method == "term.watch" || method == "term.keys") _state.update { it.copy(grant = null) }
                e.message ?: "The bridge refused it"
            } catch (e: TnpException) {
                "Not connected to the bridge"
            }
            if (error != null) _state.update { it.copy(loading = false, loadingHistory = false, error = error) }
        }
    }

    private fun int(o: JsonObject, key: String): Int? = o.long(key)?.toInt()

    private fun session(o: JsonObject): TmuxSession? = TmuxSession(
        name = o.str("name") ?: return null, command = o.str("command") ?: "", path = o.str("path") ?: "",
        cols = int(o, "cols") ?: 0, rows = int(o, "rows") ?: 0, attached = int(o, "attached") ?: 0,
        activity = o.long("activity") ?: 0,
    )

    private fun screen(o: JsonObject): TermScreen? = TermScreen(
        session = o.str("session") ?: return null, cols = int(o, "cols") ?: return null, rows = int(o, "rows") ?: return null,
        cursorX = int(o, "cursor_x") ?: 0, cursorY = int(o, "cursor_y") ?: 0, command = o.str("command") ?: "",
        control = (o["control"] as? JsonPrimitive)?.booleanOrNull == true, text = o.str("text") ?: "",
        alternate = (o["alternate"] as? JsonPrimitive)?.booleanOrNull == true,
    )
}
