package io.github.meepdong.talaria.rooms

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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One member of a group chat (spec/README.md §18.2); [botId] is absent for the owner's own assistant. */
data class RoomMember(val memberId: String, val name: String, val handle: String, val botId: String? = null)

/** A group chat as the list shows it. */
data class RoomSummary(
    val id: String, val name: String, val members: List<RoomMember>, val updatedAt: Long,
    val working: Boolean = false, val needsYou: Boolean = false,
    val previewSpeaker: String? = null, val previewText: String? = null,
)

enum class RoomMessageKind { USER, MEMBER, NOTE }

data class RoomMessage(
    val seq: Long, val atMs: Long, val kind: RoomMessageKind, val speaker: String, val text: String,
    val threadId: String? = null, val memberId: String? = null,
)

/** An approval a member waits for: answer it with "once" or "deny". */
data class RoomApproval(val id: String, val member: String, val command: String?, val description: String?)

/** The loaded part of one room. */
data class RoomThread(
    val messages: List<RoomMessage> = emptyList(),
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val working: Boolean = false,
    val approvals: List<RoomApproval> = emptyList(),
    val needsYou: Boolean = false,
    /** Members' turns Hermes couldn't finish for sure: rooms.retry runs them again. */
    val stuck: Int = 0,
)

data class RoomsState(
    /** False when the bridge has no group chats (no doorway, or an older bridge). */
    val available: Boolean = false,
    val rooms: List<RoomSummary> = emptyList(),
    val threads: Map<String, RoomThread> = emptyMap(),
    val notice: String? = null,
)

/**
 * Hermes's group chats through the bridge (spec/README.md §18.2): the list, a room's messages, writing, stopping,
 * approvals and new rooms. The bridge watches the rooms and pushes rooms.changed / rooms.update.
 */
class RoomsRepository(private val scope: CoroutineScope, private val api: ChatApi) {
    private val _state = MutableStateFlow(RoomsState())
    val state: StateFlow<RoomsState> = _state.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            launch { api.notifications.collect { runCatching { onNotification(it) } } }
            launch { api.sessions.collect { refresh(); _state.value.threads.keys.forEach(::open) } }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun refresh() {
        scope.launch {
            try {
                val r = api.request("rooms.list", JsonObject(emptyMap()))
                _state.update { it.copy(available = true, rooms = parseRooms(r["rooms"] as? JsonArray)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(available = false) }  // no doorway: no group chats
            }
        }
    }

    /** Load a room's newest messages (and have the bridge watch it). */
    fun open(roomId: String) {
        _state.update { s -> s.withThread(roomId) { it.copy(loading = true) } }
        scope.launch {
            try {
                val r = api.request("rooms.open", buildJsonObject { put("room_id", roomId) })
                val messages = parseMessages(r["messages"] as? JsonArray)
                _state.update { s ->
                    val room = r.obj("room")?.let(::parseRoom)
                    s.copy(rooms = room?.let { new -> s.rooms.map { if (it.id == new.id) new else it } } ?: s.rooms)
                        .withThread(roomId) {
                            RoomThread(messages = merge(it.messages, messages), hasMore = (r["has_more"] as? JsonPrimitive)?.booleanOrNull == true,
                                working = room?.working ?: it.working, approvals = parseApprovals(r["approvals"] as? JsonArray),
                                needsYou = room?.needsYou ?: it.needsYou, stuck = (r["stuck"] as? JsonPrimitive)?.intOrNull ?: 0)
                        }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(roomId, "Couldn't open the group chat: ${e.message}")
            }
        }
    }

    fun send(roomId: String, text: String, threadId: String? = null) {
        if (text.isBlank()) return
        scope.launch {
            try {
                val r = api.request("rooms.send", buildJsonObject {
                    put("room_id", roomId)
                    put("text", text.trim())
                    threadId?.let { put("thread_id", it) }
                })
                r.obj("message")?.let(::parseMessage)?.let { msg ->
                    _state.update { s -> s.withThread(roomId) { it.copy(messages = merge(it.messages, listOf(msg)), working = true) } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("Couldn't send: ${e.message}")
            }
        }
    }

    fun stopRoom(roomId: String) = simple("rooms.stop", roomId) { put("room_id", roomId) }

    fun renameRoom(roomId: String, name: String) {
        if (name.isBlank()) return
        simple("rooms.rename", roomId) {
            put("room_id", roomId)
            put("name", name.trim())
        }
    }

    /** Run the room's stuck turns again. */
    fun retryRoom(roomId: String) {
        _state.update { s -> s.withThread(roomId) { it.copy(stuck = 0, working = true) } }
        simple("rooms.retry", roomId) { put("room_id", roomId) }
    }

    /** End the room for good, everywhere; [then] runs once it's gone. */
    fun disbandRoom(roomId: String, then: () -> Unit) {
        scope.launch {
            try {
                api.request("rooms.disband", buildJsonObject { put("room_id", roomId) })
                _state.update { s -> s.copy(rooms = s.rooms.filter { it.id != roomId }, threads = s.threads - roomId) }
                then()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("Couldn't end the group chat: ${e.message}")
            }
        }
    }

    fun approve(roomId: String, approvalId: String, choice: String) {
        _state.update { s -> s.withThread(roomId) { it.copy(approvals = it.approvals.filterNot { a -> a.id == approvalId }) } }
        simple("rooms.approve", roomId) {
            put("room_id", roomId)
            put("approval_id", approvalId)
            put("choice", choice)
        }
    }

    /** A new group chat with 2–6 [members] (bot ids, or "assistant"); [then] gets its id. */
    fun create(name: String, members: List<String>, then: (String) -> Unit = {}) {
        scope.launch {
            try {
                val r = api.request("rooms.create", buildJsonObject {
                    put("name", name.trim())
                    put("members", JsonArray(members.map { JsonPrimitive(it) }))
                })
                val room = r.obj("room")?.let(::parseRoom) ?: return@launch
                _state.update { s -> s.copy(rooms = listOf(room) + s.rooms.filterNot { it.id == room.id }) }
                then(room.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("Couldn't make the group chat: ${e.message}")
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private fun simple(method: String, roomId: String, params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        scope.launch {
            try {
                api.request(method, buildJsonObject(params))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice(if (e is RpcException || e is TnpException) e.message ?: "It didn't work" else "It didn't work")
                open(roomId)
            }
        }
    }

    private fun notice(text: String) = _state.update { it.copy(notice = text) }

    private fun fail(roomId: String, text: String) =
        _state.update { s -> s.copy(notice = text).withThread(roomId) { it.copy(loading = false) } }

    private fun onNotification(msg: JsonObject) {
        val p = msg.obj("params") ?: return
        when (msg.str("method")) {
            "rooms.changed" -> _state.update { it.copy(available = true, rooms = parseRooms(p["rooms"] as? JsonArray)) }
            "rooms.update" -> {
                val id = p.str("room_id") ?: return
                val messages = parseMessages(p["messages"] as? JsonArray)
                val working = (p["working"] as? JsonPrimitive)?.booleanOrNull == true
                val needsYou = (p["needs_you"] as? JsonPrimitive)?.booleanOrNull == true
                _state.update { s ->
                    s.copy(rooms = s.rooms.map { r ->
                        if (r.id != id) r else {
                            val last = messages.lastOrNull { it.kind != RoomMessageKind.NOTE }
                            r.copy(working = working, needsYou = needsYou, previewSpeaker = last?.speaker ?: r.previewSpeaker,
                                previewText = last?.text ?: r.previewText, updatedAt = maxOf(r.updatedAt, (last?.atMs ?: 0) / 1000))
                        }
                    }.sortedByDescending { it.updatedAt }).let { st ->
                        if (id !in st.threads) st else st.withThread(id) {
                            it.copy(messages = merge(it.messages, messages), working = working,
                                approvals = parseApprovals(p["approvals"] as? JsonArray), needsYou = needsYou,
                                stuck = (p["stuck"] as? JsonPrimitive)?.intOrNull ?: it.stuck)
                        }
                    }
                }
            }
        }
    }

    private fun RoomsState.withThread(id: String, f: (RoomThread) -> RoomThread): RoomsState =
        copy(threads = threads + (id to f(threads[id] ?: RoomThread())))

    private fun merge(old: List<RoomMessage>, new: List<RoomMessage>): List<RoomMessage> =
        (old + new).associateBy { it.seq }.values.sortedBy { it.seq }

    private fun parseRooms(a: JsonArray?): List<RoomSummary> = a.orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseRoom) }

    private fun parseRoom(o: JsonObject): RoomSummary? {
        val id = o.str("id") ?: return null
        val preview = o.obj("preview")
        return RoomSummary(
            id = id, name = o.str("name") ?: "Group chat",
            members = (o["members"] as? JsonArray).orEmpty().mapNotNull { e ->
                val m = e as? JsonObject ?: return@mapNotNull null
                RoomMember(m.str("member_id") ?: return@mapNotNull null, m.str("name") ?: "Member", m.str("handle") ?: "", m.str("bot_id"))
            },
            updatedAt = o.long("updated_at") ?: 0,
            working = (o["working"] as? JsonPrimitive)?.booleanOrNull == true,
            needsYou = (o["needs_you"] as? JsonPrimitive)?.booleanOrNull == true,
            previewSpeaker = preview?.str("speaker"), previewText = preview?.str("text"),
        )
    }

    private fun parseMessages(a: JsonArray?): List<RoomMessage> = a.orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseMessage) }

    private fun parseMessage(o: JsonObject): RoomMessage? = RoomMessage(
        seq = o.long("seq") ?: return null, atMs = (o.long("at") ?: 0) * 1000,
        kind = when (o.str("kind")) { "user" -> RoomMessageKind.USER; "member" -> RoomMessageKind.MEMBER; else -> RoomMessageKind.NOTE },
        speaker = o.str("speaker") ?: "", text = o.str("text").orEmpty(), threadId = o.str("thread_id"), memberId = o.str("member_id"),
    )

    private fun parseApprovals(a: JsonArray?): List<RoomApproval> = a.orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        RoomApproval(o.str("approval_id") ?: return@mapNotNull null, o.str("member") ?: "A member", o.str("command"), o.str("description"))
    }
}
