package io.github.meepdong.talaria.chat

import io.github.meepdong.talaria.session.ConnectionState
import io.github.meepdong.talaria.session.RpcException
import io.github.meepdong.talaria.session.TnpClient
import io.github.meepdong.talaria.session.TnpException
import io.github.meepdong.talaria.session.long
import io.github.meepdong.talaria.session.obj
import io.github.meepdong.talaria.session.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** What the chat needs from the bridge session; [TnpClient.asChatApi] is the real one. */
interface ChatApi {
    suspend fun request(method: String, params: JsonObject, timeoutMs: Long? = null): JsonObject

    /** chat.* notifications from the bridge. */
    val notifications: Flow<JsonObject>

    /** Emits the session id each time a new session is ready. */
    val sessions: Flow<String>
}

fun TnpClient.asChatApi(): ChatApi = object : ChatApi {
    override suspend fun request(method: String, params: JsonObject, timeoutMs: Long?) =
        this@asChatApi.request(method, params, timeoutMs)

    override val notifications: Flow<JsonObject> = this@asChatApi.notifications

    override val sessions: Flow<String> = state
        .map { s -> s.sessionId.takeIf { s.phase == ConnectionState.Phase.CONNECTED } }
        .distinctUntilChanged()
        .filterNotNull()
}

/**
 * The chat for one paired bridge (spec/README.md §9): the conversation list, the loaded
 * part of each conversation, and replies as they stream in. Every device gets every turn,
 * so a message sent from the laptop shows up on the phone too. After a reconnect it
 * catches up with `chat.turn.get` for replies that were streaming, and reloads the rest.
 */
class ChatRepository(
    private val scope: CoroutineScope,
    private val api: ChatApi,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newClientMsgId: () -> String = { "m-" + UUID.randomUUID() },
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _replies = MutableSharedFlow<FinishedReply>(extraBufferCapacity = 64)

    /** Every reply that finishes, from any device's message, for notifications. */
    val replies: SharedFlow<FinishedReply> = _replies.asSharedFlow()

    /** Last applied `seq` of each turn we follow. Guarded by [lock]. */
    private val lastSeq = HashMap<String, Int>()
    private val syncing = HashSet<String>()

    /** Bumped whenever a turn starts or ends in a conversation, so a history page fetched meanwhile is known to be stale. Guarded by [lock]. */
    private val changes = HashMap<String, Int>()
    private val lock = Mutex()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            launch { api.notifications.collect { msg -> runCatching { handle(msg) } } }
            launch { api.sessions.collect { onNewSession() } }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    // what the screens call

    fun open(conversationId: String?) {
        _state.update { it.copy(openId = conversationId) }
        if (conversationId == null) return
        val thread = _state.value.threads[conversationId]
        if (thread == null || (!thread.loaded && !thread.loading)) scope.launch { loadNewest(conversationId) }
    }

    /** Start a new conversation: the next message sent gets a new id from the bridge. */
    fun newConversation() {
        _state.update { s -> s.copy(openId = null, draft = s.draft.filter { it.state == MessageState.SENDING }) }
    }

    /** Send [text] to [conversationId], the open conversation by default (null starts a new one). */
    fun send(text: String, conversationId: String? = _state.value.openId) {
        val body = text.trim()
        if (body.isEmpty()) return
        val cmid = newClientMsgId()
        val conv = conversationId
        val msg = ChatMessage("local:$cmid", Role.USER, body, nowMs(), MessageState.SENDING, clientMsgId = cmid)
        _state.update { s -> s.withMessages(conv) { it + msg } }
        scope.launch { deliver(conv, body, cmid) }
    }

    /** Send a message that didn't reach the bridge again. The same client_msg_id means it can't arrive twice. */
    fun retry(key: String) {
        val s = _state.value
        val conv = s.openId
        val msg = s.openMessages.firstOrNull { it.key == key && it.state == MessageState.NOT_SENT } ?: return
        val cmid = msg.clientMsgId ?: return
        _state.update { st -> st.withMessages(conv) { list -> list.map { if (it.key == key) it.copy(state = MessageState.SENDING, error = null) else it } } }
        scope.launch { deliver(conv, msg.text, cmid) }
    }

    fun stop(turnId: String) {
        scope.launch { runCatching { api.request("chat.cancel", buildJsonObject { put("turn_id", turnId) }) } }
    }

    fun loadOlder(conversationId: String) {
        val thread = _state.value.threads[conversationId] ?: return
        if (thread.loading || thread.nextBefore == null) return
        scope.launch { loadPage(conversationId, thread.nextBefore) }
    }

    fun refresh() {
        scope.launch { refreshList() }
    }

    fun rename(conversationId: String, title: String) {
        scope.launch {
            try {
                val r = api.request("conversations.rename", buildJsonObject {
                    put("conversation_id", conversationId)
                    put("title", title.trim())
                })
                val newTitle = r.str("title") ?: title.trim()
                _state.update { s -> s.copy(conversations = s.conversations.map { if (it.id == conversationId) it.copy(title = newTitle) else it }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("Couldn't rename: ${e.message}")
            }
        }
    }

    fun delete(conversationId: String) {
        scope.launch {
            try {
                api.request("conversations.delete", buildJsonObject { put("conversation_id", conversationId) })
                _state.update { s ->
                    s.copy(conversations = s.conversations.filter { it.id != conversationId },
                        threads = s.threads - conversationId,
                        openId = s.openId.takeIf { it != conversationId })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("Couldn't delete: ${e.message}")
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private fun notice(text: String) = _state.update { it.copy(notice = text) }

    // requests

    private suspend fun deliver(conv: String?, text: String, cmid: String) {
        try {
            val r = api.request("chat.send", buildJsonObject {
                put("text", text)
                conv?.let { put("conversation_id", it) }
                put("client_msg_id", cmid)
            }, SEND_TIMEOUT_MS)
            lock.withLock { onSent(r, cmid) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            if (e.code == AGENT_UNAVAILABLE) _state.update { it.copy(unavailable = e.message) }
            markNotSent(conv, cmid, e.message ?: "The bridge refused the message")
        } catch (e: TnpException) {
            markNotSent(conv, cmid, "Not connected to the bridge")
        }
    }

    private fun markNotSent(conv: String?, cmid: String, error: String) = _state.update { s ->
        val update = { list: List<ChatMessage> ->
            list.map { if (it.clientMsgId == cmid && it.state == MessageState.SENDING) it.copy(state = MessageState.NOT_SENT, error = error) else it }
        }
        // the draft may have moved into a thread meanwhile
        s.withMessages(conv, f = update).let { st -> st.threads.keys.fold(st) { acc, id -> acc.withMessages(id, f = update) } }
    }

    private suspend fun refreshList() {
        try {
            val r = api.request("conversations.list", JsonObject(emptyMap()))
            val list = (r["conversations"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseSummary) }
            _state.update { it.copy(conversations = list.sortedByDescending { c -> c.updatedAt }, listLoaded = true, listError = null, unavailable = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            _state.update { it.copy(listError = e.message, listLoaded = true, unavailable = if (e.code == AGENT_UNAVAILABLE) e.message else it.unavailable) }
        } catch (e: TnpException) {
            _state.update { it.copy(listError = "Not connected to the bridge") }
        }
    }

    private suspend fun loadNewest(conv: String) {
        loadPage(conv, null)
        _state.value.conversations.firstOrNull { it.id == conv }?.activeTurnId?.let { sync(it) }
    }

    private suspend fun loadPage(conv: String, before: String?) {
        _state.update { s -> s.copy(threads = s.threads + (conv to (s.threads[conv] ?: ConversationThread()).copy(loading = true, error = null))) }
        try {
            var attempt = 0
            while (true) {
                val version = lock.withLock { changes[conv] }
                val r = api.request("chat.history", buildJsonObject {
                    put("conversation_id", conv)
                    put("limit", PAGE)
                    before?.let { put("before", it) }
                }, HISTORY_TIMEOUT_MS)
                val page = (r["messages"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseHistory) }
                val next = r.str("next_before")
                val applied = lock.withLock {
                    // a turn started or ended while the newest page was on its way: it may be missing, so ask again
                    if (before == null && changes[conv] != version && ++attempt < STALE_RETRIES) return@withLock false
                    _state.update { s ->
                        val old = s.threads[conv] ?: ConversationThread()
                        val messages = if (before == null) page + liveTail(old.messages, page) else page + old.messages
                        s.copy(threads = s.threads + (conv to old.copy(messages = messages, nextBefore = next, loaded = true, loading = false)))
                    }
                    true
                }
                if (applied) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val why = if (e is TnpException) "Not connected to the bridge" else e.message ?: "Couldn't load messages"
            _state.update { s -> s.copy(threads = s.threads + (conv to (s.threads[conv] ?: ConversationThread()).copy(loading = false, error = why))) }
        }
    }

    /**
     * What the newest history page doesn't have yet: unsent and streaming messages, and the
     * question of a reply still streaming unless history already ends with it.
     */
    private fun liveTail(old: List<ChatMessage>, page: List<ChatMessage>): List<ChatMessage> {
        val streaming = old.filter { it.state == MessageState.STREAMING }.mapNotNull { it.turnId }.toSet()
        val lastAsked = page.lastOrNull { it.role == Role.USER }?.text
        return old.filter {
            it.state in LOCAL_STATES || (it.role == Role.USER && it.turnId in streaming && it.text != lastAsked)
        }
    }

    private suspend fun onNewSession() {
        refreshList()
        val s = _state.value
        for ((conv, thread) in s.threads) {
            if (!thread.loaded) continue
            val streaming = thread.messages.filter { it.state == MessageState.STREAMING }.mapNotNull { it.turnId }
            if (streaming.isEmpty()) scope.launch { loadPage(conv, null) } else streaming.forEach { sync(it) }
        }
    }

    /** Catch up on one turn from its snapshot. */
    private fun sync(turnId: String) {
        scope.launch {
            lock.withLock { if (!syncing.add(turnId)) return@launch }
            try {
                val r = api.request("chat.turn.get", buildJsonObject { put("turn_id", turnId) })
                r.obj("turn")?.let { lock.withLock { applySnapshot(it) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RpcException) {
                // too old for the bridge to remember: history has it
                val conv = lock.withLock {
                    lastSeq.remove(turnId)
                    _state.value.threads.entries.firstOrNull { (_, t) -> t.messages.any { it.turnId == turnId } }?.key
                }
                if (conv != null) {
                    _state.update { s -> s.withMessages(conv) { list -> list.filterNot { it.turnId == turnId && it.state == MessageState.STREAMING } } }
                    loadPage(conv, null)
                }
            } catch (e: Exception) {
                // not connected; the next session catches up
            } finally {
                lock.withLock { syncing.remove(turnId) }
            }
        }
    }

    // notifications

    private suspend fun handle(msg: JsonObject) {
        val p = msg.obj("params") ?: return
        lock.withLock {
            when (msg.str("method")) {
                "chat.started" -> onStarted(p)
                "chat.delta" -> onDelta(p)
                "chat.done" -> onDone(p)
            }
        }
    }

    private fun changed(conv: String) {
        changes[conv] = (changes[conv] ?: 0) + 1
    }

    private fun onSent(r: JsonObject, cmid: String) {
        val conv = r.str("conversation_id") ?: return
        val turn = r.str("turn_id") ?: return
        val title = r.str("title") ?: "Conversation"
        lastSeq.putIfAbsent(turn, 0)
        changed(conv)
        _state.update { s0 ->
            var s = s0.claimDraft(cmid, conv)
            s = s.withMessages(conv) { list ->
                list.map { if (it.clientMsgId == cmid) it.copy(state = MessageState.DONE, turnId = turn, error = null) else it }
            }
            if (s.conversations.none { it.id == conv }) {
                val now = nowMs()
                s = s.upsert(ConversationSummary(conv, "", title, now, now, Role.USER, null, turn))
            }
            s
        }
    }

    private fun onStarted(p: JsonObject) {
        val conv = p.str("conversation_id") ?: return
        val turn = p.str("turn_id") ?: return
        val userText = p.str("user_text").orEmpty()
        val at = (p.long("started_at") ?: (nowMs() / 1000)) * 1000
        val cmid = p.str("client_msg_id")
        lastSeq.putIfAbsent(turn, 0)
        changed(conv)
        _state.update { s0 ->
            val existing = s0.conversations.firstOrNull { it.id == conv }
            var s = s0.upsert(
                existing?.copy(title = p.str("title") ?: existing.title, updatedAt = at / 1000, lastRole = Role.USER,
                    lastText = userText, activeTurnId = turn)
                    ?: ConversationSummary(conv, p.str("agent_id").orEmpty(), p.str("title") ?: "Conversation",
                        at / 1000, at / 1000, Role.USER, userText, turn)
            )
            if (cmid != null) s = s.claimDraft(cmid, conv)
            val thread = s.threads[conv]
            if (thread != null && thread.loaded) {
                s = s.withMessages(conv) { list -> withTurn(list, turn, userText, at, cmid) }
            }
            s
        }
    }

    /** Make sure [list] has this turn's user message and a streaming reply after it. */
    private fun withTurn(list: List<ChatMessage>, turn: String, userText: String, atMs: Long, cmid: String?): List<ChatMessage> {
        var out = list
        val mine = out.indexOfFirst { (cmid != null && it.clientMsgId == cmid) || (it.role == Role.USER && it.turnId == turn) }
        out = if (mine >= 0) {
            out.mapIndexed { i, m -> if (i == mine) m.copy(state = MessageState.DONE, turnId = turn, error = null) else m }
        } else {
            out + ChatMessage("user:$turn", Role.USER, userText, atMs, MessageState.DONE, turnId = turn, clientMsgId = cmid)
        }
        if (out.none { it.key == "reply:$turn" }) {
            out = out + ChatMessage("reply:$turn", Role.ASSISTANT, "", null, MessageState.STREAMING, turnId = turn)
        }
        return out
    }

    private fun onDelta(p: JsonObject) {
        val conv = p.str("conversation_id") ?: return
        val turn = p.str("turn_id") ?: return
        val seq = p.long("seq")?.toInt() ?: return
        val last = lastSeq[turn]
        if (last == null || seq > last + 1) {
            sync(turn) // we missed the start or a delta
            return
        }
        if (seq <= last) return
        lastSeq[turn] = seq
        _state.update { s ->
            s.withMessages(conv, onlyLoaded = true) { list ->
                list.map { m -> if (m.key == "reply:$turn") applyDelta(m, p) else m }
            }
        }
    }

    private fun applyDelta(m: ChatMessage, p: JsonObject): ChatMessage = when (p.str("kind")) {
        "text" -> m.copy(text = m.text + p.str("text").orEmpty(), waitingForApproval = false)
        "tool_progress" -> p.obj("tool")?.let { t ->
            val step = ToolStep(t.str("name") ?: "tool", t.str("state") ?: "started", t.str("preview"))
            val open = m.tools.indexOfLast { it.name == step.name && it.state == "started" }
            val tools = if (step.state != "started" && open >= 0) m.tools.mapIndexed { i, x -> if (i == open) step else x } else m.tools + step
            m.copy(tools = tools, waitingForApproval = false)
        } ?: m
        "commentary" -> m.copy(commentary = p.str("text"), waitingForApproval = false)
        "approval" -> m.copy(commentary = p.str("text"), waitingForApproval = true)
        else -> m
    }

    private fun onDone(p: JsonObject) {
        val conv = p.str("conversation_id") ?: return
        val turn = p.str("turn_id") ?: return
        lastSeq.remove(turn)
        finish(conv, turn, p.str("status"), p.str("text").orEmpty(), p.str("error"))
    }

    private fun finish(conv: String, turn: String, status: String?, text: String, error: String?) {
        val state = when (status) {
            "completed" -> MessageState.DONE
            "cancelled" -> MessageState.CANCELLED
            else -> MessageState.FAILED
        }
        changed(conv)
        _state.update { s0 ->
            var s = s0.copy(conversations = s0.conversations.map {
                if (it.id != conv) it
                else it.copy(activeTurnId = it.activeTurnId.takeIf { a -> a != turn },
                    lastRole = if (text.isNotBlank()) Role.ASSISTANT else it.lastRole,
                    lastText = text.ifBlank { null } ?: it.lastText,
                    updatedAt = nowMs() / 1000)
            }.sortedByDescending { it.updatedAt })
            s = s.withMessages(conv, onlyLoaded = true) { list ->
                val done = { m: ChatMessage ->
                    m.copy(text = text.ifEmpty { m.text }, state = state, error = error, waitingForApproval = false, commentary = null)
                }
                if (list.any { it.key == "reply:$turn" }) list.map { if (it.key == "reply:$turn") done(it) else it }
                else list + done(ChatMessage("reply:$turn", Role.ASSISTANT, "", null, state, turnId = turn))
            }
            s
        }
        val title = _state.value.conversations.firstOrNull { it.id == conv }?.title ?: "Talaria"
        _replies.tryEmit(FinishedReply(conv, title, text, state, error))
    }

    private fun applySnapshot(t: JsonObject) {
        val conv = t.str("conversation_id") ?: return
        val turn = t.str("turn_id") ?: return
        val status = t.str("status") ?: return
        val seq = t.long("seq")?.toInt() ?: 0
        val following = turn in lastSeq || _state.value.threads[conv]?.messages?.any { it.turnId == turn && it.state == MessageState.STREAMING } == true
        if (status == "running") {
            lastSeq[turn] = maxOf(seq, lastSeq[turn] ?: 0)
        } else {
            lastSeq.remove(turn)
        }
        val at = (t.long("started_at") ?: 0) * 1000
        val userText = t.str("user_text").orEmpty()
        val tools = (t["tools"] as? JsonArray).orEmpty().mapNotNull { e ->
            (e as? JsonObject)?.let { ToolStep(it.str("name") ?: "tool", it.str("state") ?: "started", it.str("preview")) }
        }
        val commentary = (t["commentary"] as? JsonArray)?.lastOrNull()?.let { (it as? JsonPrimitive)?.content }
        val waiting = (t["waiting_for_approval"] as? JsonPrimitive)?.content == "true"
        _state.update { s0 ->
            var s = s0.copy(conversations = s0.conversations.map {
                if (it.id == conv) it.copy(activeTurnId = if (status == "running") turn else null) else it
            })
            val thread = s.threads[conv]
            if (thread != null && thread.loaded) {
                s = s.withMessages(conv) { list ->
                    // history may already hold the user's message, without a turn id
                    val base = if (list.none { it.turnId == turn } && list.lastOrNull { it.role == Role.USER }?.text == userText) {
                        list.mapIndexed { i, m -> if (i == list.indexOfLast { it.role == Role.USER }) m.copy(turnId = turn) else m }
                    } else list
                    withTurn(base, turn, userText, at, null).map { m ->
                        if (m.key != "reply:$turn") m
                        else m.copy(text = t.str("text").orEmpty(), tools = tools,
                            commentary = if (status == "running") commentary else null,
                            waitingForApproval = waiting && status == "running",
                            state = if (status == "running") MessageState.STREAMING else m.state,
                            error = t.str("error"))
                    }
                }
            }
            s
        }
        if (status != "running" && following) finish(conv, turn, status, t.str("text").orEmpty(), t.str("error"))
    }

    // parsing

    private fun parseSummary(c: JsonObject): ConversationSummary? {
        val id = c.str("conversation_id") ?: return null
        val last = c.obj("last_message")
        return ConversationSummary(
            id = id, agentId = c.str("agent_id").orEmpty(), title = c.str("title") ?: "Conversation",
            createdAt = c.long("created_at") ?: 0, updatedAt = c.long("updated_at") ?: 0,
            lastRole = when (last?.str("role")) { "user" -> Role.USER; "assistant" -> Role.ASSISTANT; else -> null },
            lastText = last?.str("text"), activeTurnId = c.str("active_turn_id"),
        )
    }

    private fun parseHistory(m: JsonObject): ChatMessage? {
        val role = when (m.str("role")) { "user" -> Role.USER; "assistant" -> Role.ASSISTANT; else -> return null }
        return ChatMessage(
            key = "h:${m.str("id")}", role = role, text = m.str("text").orEmpty(),
            atMs = m.long("ts")?.times(1000),
            toolNames = (m["tools"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
        )
    }

    companion object {
        const val AGENT_UNAVAILABLE = -32010
        const val PAGE = 50
        const val SEND_TIMEOUT_MS = 30_000L
        const val HISTORY_TIMEOUT_MS = 30_000L
        private const val STALE_RETRIES = 3
        private val LOCAL_STATES = setOf(MessageState.SENDING, MessageState.NOT_SENT, MessageState.STREAMING)
    }
}

// state helpers

private fun ChatState.withMessages(conv: String?, onlyLoaded: Boolean = false, f: (List<ChatMessage>) -> List<ChatMessage>): ChatState {
    if (conv == null) return copy(draft = f(draft))
    val thread = threads[conv] ?: if (onlyLoaded) return this else ConversationThread()
    if (onlyLoaded && !thread.loaded) return this
    return copy(threads = threads + (conv to thread.copy(messages = f(thread.messages))))
}

/** The bridge named the draft conversation: move the draft into it and keep it open. */
private fun ChatState.claimDraft(cmid: String, conv: String): ChatState {
    if (draft.none { it.clientMsgId == cmid }) return this
    val thread = threads[conv] ?: ConversationThread(loaded = true)
    return copy(
        draft = emptyList(),
        threads = threads + (conv to thread.copy(messages = thread.messages + draft, loaded = true)),
        openId = openId ?: conv,
    )
}

private fun ChatState.upsert(c: ConversationSummary): ChatState =
    copy(conversations = (conversations.filter { it.id != c.id } + c).sortedByDescending { it.updatedAt })
