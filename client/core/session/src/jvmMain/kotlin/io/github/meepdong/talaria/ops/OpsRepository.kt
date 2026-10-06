package io.github.meepdong.talaria.ops

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
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One server operation the bridge offers (PROTOCOL §10.8). Tier 0 reads; 1 changes; 2 disrupts. */
data class OpInfo(val op: String, val tier: Int, val title: String, val params: Map<String, OpParam>)

/** A parameter: "string" with optional [choices], or "integer" between [min] and [max]. */
data class OpParam(val type: String, val choices: List<String>?, val min: Int?, val max: Int?)

/** What an operation did. [data] is structured detail for reads such as system.overview: maps, lists, strings, numbers. */
data class OpOutcome(
    val op: String, val ok: Boolean, val exitCode: Int?, val summary: String, val output: String,
    val data: Any?, val finishedAt: Long,
)

/** An operation waiting for the owner's approval. [paramsJson] is signed exactly as received. */
data class OpsApproval(
    val requestId: String, val op: String, val paramsJson: String, val tier: Int, val summary: String,
    val requestedBy: String, val expiresAt: Long,
)

/** An approved operation that finished, until dismissed. */
data class OpsResult(val requestId: String, val requestedBy: String, val approvedBy: String?, val outcome: OpOutcome)

data class OpsState(
    /** False when the bridge offers no server operations (talaria-ops isn't installed, or an older bridge). */
    val available: Boolean = true,
    val catalogue: List<OpInfo> = emptyList(),
    /** The latest result of each read, by op; service logs are kept as "service.logs". */
    val reads: Map<String, OpOutcome> = emptyMap(),
    /** Reads and requests on their way. */
    val busy: Set<String> = emptySet(),
    /** Oldest first. Kept until the bridge says ops.approval.done, or they expire. */
    val pending: List<OpsApproval> = emptyList(),
    /** Newest first, at most [MAX_RESULTS]. */
    val results: List<OpsResult> = emptyList(),
    /** Approvals this device is answering right now. */
    val answering: Set<String> = emptySet(),
    val error: String? = null,
)

/**
 * Server operations (PROTOCOL §10.8): read the server's state, ask for changes, and answer approvals with
 * this device's signature. talaria-ops checks the signature itself, so only a paired device can approve.
 */
class OpsRepository(
    private val scope: CoroutineScope,
    private val api: ChatApi,
    private val nowS: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val _state = MutableStateFlow(OpsState())
    val state: StateFlow<OpsState> = _state.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            launch { api.notifications.collect(::onNotification) }
            launch { api.sessions.collect { loadCatalogue() } }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** The catalogue, then the reads the Server page shows. */
    fun refresh() {
        loadCatalogue()
        for (op in listOf("system.overview", "services.list", "docker.ps", "bridge.version")) run(op)
        if (_state.value.catalogue.any { it.op == SKILLS }) run(SKILLS)
    }

    fun loadCatalogue() {
        call("ops.catalogue", JsonObject(emptyMap()), "catalogue") { r ->
            val s = _state.updateAndGet { s -> s.copy(available = true, catalogue = (r["ops"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::opInfo) }) }
            if (s.catalogue.any { it.op == SKILLS } && SKILLS !in s.reads && SKILLS !in s.busy) run(SKILLS)
        }
    }

    /** Run [op]: a read answers into [OpsState.reads]; a change becomes an approval card on every device. */
    fun run(op: String, params: Map<String, Any> = emptyMap()) {
        val body = buildJsonObject {
            put("op", op)
            put("params", buildJsonObject {
                params.forEach { (k, v) -> if (v is Number) put(k, v) else put(k, v.toString()) }
            })
        }
        call("ops.run", body, op) { r ->
            if (r.str("status") == "done") r.obj("result")?.let(::outcome)?.let { o -> _state.update { it.copy(reads = it.reads + (op to o)) } }
        }
    }

    /** Answer an approval: [choice] is "once" or "deny", signed with this device's key. */
    fun approve(requestId: String, choice: String) {
        val a = _state.value.pending.firstOrNull { it.requestId == requestId } ?: return
        val sig = api.signOpsApproval(a.requestId, a.op, a.paramsJson, choice)
        if (sig == null) {
            _state.update { it.copy(error = "This device can't sign approvals") }
            return
        }
        _state.update { it.copy(answering = it.answering + requestId) }
        scope.launch {
            try {
                api.request("ops.approve", buildJsonObject {
                    put("request_id", requestId)
                    put("choice", choice)
                    put("sig", sig)
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Couldn't answer: ${e.message}") }
            } finally {
                _state.update { it.copy(answering = it.answering - requestId) }
            }
        }
    }

    fun dismissResult(requestId: String) {
        _state.update { s -> s.copy(results = s.results.filterNot { it.requestId == requestId }) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun onNotification(msg: JsonObject) {
        val p = msg.obj("params") ?: return
        when (msg.str("method")) {
            "ops.approval.request" -> {
                val a = OpsApproval(
                    requestId = p.str("request_id") ?: return, op = p.str("op") ?: return,
                    paramsJson = p.str("params_json") ?: return, tier = (p["tier"] as? JsonPrimitive)?.intOrNull ?: 1,
                    summary = p.str("summary") ?: "", requestedBy = p.str("requested_by") ?: "", expiresAt = p.long("expires_at") ?: 0,
                )
                val now = nowS()
                // the bridge re-sends pending requests on every reconnect: replace, don't duplicate
                _state.update { s -> s.copy(pending = s.pending.filter { it.requestId != a.requestId && (it.expiresAt == 0L || it.expiresAt > now) } + a) }
            }
            "ops.approval.done" -> {
                val id = p.str("request_id") ?: return
                _state.update { s -> s.copy(pending = s.pending.filterNot { it.requestId == id }) }
            }
            "ops.result" -> {
                val id = p.str("request_id") ?: return
                val o = p.obj("result")?.let(::outcome) ?: return
                val r = OpsResult(id, p.str("requested_by") ?: "", p.str("approved_by"), o)
                _state.update { s -> s.copy(results = (listOf(r) + s.results.filterNot { it.requestId == id }).take(MAX_RESULTS)) }
                if (o.op in REFRESH_AFTER) refresh()
            }
        }
    }

    private fun call(method: String, params: JsonObject, busyKey: String, onResult: (JsonObject) -> Unit) {
        _state.update { it.copy(busy = it.busy + busyKey) }
        scope.launch {
            try {
                onResult(api.request(method, params, timeoutMs = 130_000))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RpcException) {
                if (e.code == METHOD_NOT_FOUND) _state.update { it.copy(available = false) }
                else _state.update { it.copy(error = e.message) }
            } catch (e: TnpException) {
                _state.update { it.copy(error = "Not connected to the bridge") }
            } finally {
                _state.update { it.copy(busy = it.busy - busyKey) }
            }
        }
    }

    private fun opInfo(o: JsonObject): OpInfo? = OpInfo(
        op = o.str("op") ?: return null,
        tier = (o["tier"] as? JsonPrimitive)?.intOrNull ?: return null,
        title = o.str("title") ?: "",
        params = o.obj("params").orEmpty().mapNotNull { (name, v) ->
            val p = v as? JsonObject ?: return@mapNotNull null
            name to OpParam(
                type = p.str("type") ?: "string",
                choices = (p["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content },
                min = (p["minimum"] as? JsonPrimitive)?.intOrNull, max = (p["maximum"] as? JsonPrimitive)?.intOrNull,
            )
        }.toMap(),
    )

    private fun outcome(o: JsonObject): OpOutcome? = OpOutcome(
        op = o.str("op") ?: return null,
        ok = (o["ok"] as? JsonPrimitive)?.booleanOrNull ?: false,
        exitCode = (o["exit_code"] as? JsonPrimitive)?.intOrNull,
        summary = o.str("summary") ?: "",
        output = o.str("output") ?: "",
        data = o["data"]?.let(::plain),
        finishedAt = o.long("finished_at") ?: nowS(),
    )

    /** JSON as plain Kotlin values, so the UI needn't know about JSON. */
    private fun plain(e: JsonElement): Any? = when (e) {
        is JsonObject -> e.mapValues { plain(it.value) }
        is JsonArray -> e.map(::plain)
        is JsonPrimitive -> when {
            e is kotlinx.serialization.json.JsonNull -> null
            e.isString -> e.content
            else -> e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull
        }
    }

    companion object {
        const val MAX_RESULTS = 20
        const val METHOD_NOT_FOUND = -32601

        /** After these finish, the Server page's reads are out of date. */
        val REFRESH_AFTER = setOf("service.restart", "docker.restart", "bridge.update", "disk.cleanup", "apt.upgrade",
            "hermes.skill.set")
        /** Hermes's skills for Talaria, listed when the server offers it (§16). */
        const val SKILLS = "hermes.skills"
    }
}
