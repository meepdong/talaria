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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** What the chat needs from the bridge session; [TnpClient.asChatApi] is the real one. */
interface ChatApi {
    suspend fun request(method: String, params: JsonObject, timeoutMs: Long? = null): JsonObject

    /** chat.* notifications from the bridge. */
    val notifications: Flow<JsonObject>

    /** Emits the session id each time a new session is ready. */
    val sessions: Flow<String>

    /** This device's signature for an ops.approve answer (PROTOCOL §10.8), or null when it can't sign. */
    fun signOpsApproval(requestId: String, op: String, paramsJson: String, choice: String): String? = null
}

fun TnpClient.asChatApi(): ChatApi = object : ChatApi {
    override suspend fun request(method: String, params: JsonObject, timeoutMs: Long?) =
        this@asChatApi.request(method, params, timeoutMs)

    override val notifications: Flow<JsonObject> = this@asChatApi.notifications

    override val sessions: Flow<String> = state
        .map { s -> s.sessionId.takeIf { s.phase == ConnectionState.Phase.CONNECTED } }
        .distinctUntilChanged()
        .filterNotNull()

    override fun signOpsApproval(requestId: String, op: String, paramsJson: String, choice: String): String =
        this@asChatApi.signOpsApproval(requestId, op, paramsJson, choice)
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
    /** Where files are read for uploading (a test passes its own). */
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _replies = MutableSharedFlow<FinishedReply>(extraBufferCapacity = 64)

    /** Every reply that finishes, from any device's message, for notifications. */
    val replies: SharedFlow<FinishedReply> = _replies.asSharedFlow()

    /** Last applied `seq` of each turn we follow. Guarded by [lock]. */
    private val lastSeq = HashMap<String, Int>()
    private val syncing = HashSet<String>()

    /** Turns asked from this device. Guarded by [lock]. */
    private val mine = HashSet<String>()

    /** Bumped whenever a turn starts or ends in a conversation, so a history page fetched meanwhile is known to be stale. Guarded by [lock]. */
    private val changes = HashMap<String, Int>()
    private val lock = Mutex()

    /** Files of messages not yet taken by the bridge, by client_msg_id, so a retry can upload them again. */
    private val outgoing = java.util.concurrent.ConcurrentHashMap<String, List<OutgoingFile>>()

    /** Server files (§12) named by messages not yet taken by the bridge, by client_msg_id. */
    private val outgoingServer = java.util.concurrent.ConcurrentHashMap<String, List<ServerFile>>()
    /** The to-do each message hands to the agent (§13), by client_msg_id. */
    private val outgoingTodo = java.util.concurrent.ConcurrentHashMap<String, String>()
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

    /**
     * Send [text] and [files] to [conversationId], the open conversation by default (null starts
     * a new one). Files are uploaded first (spec/README.md §10). Returns the message's client id (its
     * [ChatMessage.clientMsgId], which then gets the turn's id), or null when there was nothing to send.
     */
    fun send(
        text: String, files: List<OutgoingFile> = emptyList(), conversationId: String? = _state.value.openId,
        serverFiles: List<ServerFile> = emptyList(), todoId: String? = null,
    ): String? {
        val body = text.trim()
        if (body.isEmpty() && files.isEmpty() && serverFiles.isEmpty()) return null
        val cmid = newClientMsgId()
        val conv = conversationId
        val msg = ChatMessage("local:$cmid", Role.USER, body, nowMs(), MessageState.SENDING, clientMsgId = cmid,
            attachments = files.map { it.toAttachment() } + serverFiles.map { it.toAttachment() })
        if (files.isNotEmpty()) outgoing[cmid] = files
        if (serverFiles.isNotEmpty()) outgoingServer[cmid] = serverFiles
        todoId?.let { outgoingTodo[cmid] = it }
        _state.update { s -> s.withMessages(conv) { it + msg } }
        scope.launch { deliver(conv, body, cmid) }
        return cmid
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

    /** Take a chat off the list, or put it back. Nothing is deleted: Hermes keeps the session. */
    fun archive(conversationId: String, archived: Boolean) {
        fun set(on: Boolean) = _state.update { s -> s.copy(conversations = s.conversations.map { if (it.id == conversationId) it.copy(archived = on) else it }) }
        set(archived)
        scope.launch {
            try {
                api.request("conversations.archive", buildJsonObject {
                    put("conversation_id", conversationId)
                    put("archived", archived)
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                set(!archived)
                notice("Couldn't ${if (archived) "archive" else "unarchive"}: ${e.message}")
            }
        }
    }

    fun pin(conversationId: String, pinned: Boolean) {
        fun set(on: Boolean) = _state.update { s -> s.copy(conversations = s.conversations.map { if (it.id == conversationId) it.copy(pinned = on) else it }) }
        set(pinned)
        scope.launch {
            try {
                api.request("conversations.pin", buildJsonObject {
                    put("conversation_id", conversationId)
                    put("pinned", pinned)
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                set(!pinned)
                notice("Couldn't ${if (pinned) "pin" else "unpin"}: ${e.message}")
            }
        }
    }

    /**
     * Delete messages from Talaria on every device (spec/README.md §9 chat.hide). Hermes keeps
     * them. A message shown live, before history named it, is found in the newest page first.
     */
    fun hide(conversationId: String, keys: Collection<String>) {
        val chosen = _state.value.threads[conversationId]?.messages.orEmpty().filter { it.key in keys }
        if (chosen.isEmpty()) return
        _state.update { s -> s.withMessages(conversationId) { list -> list.filterNot { it.key in keys } } }
        val known = chosen.filter { it.key.startsWith(HISTORY_KEY) }.map { it.key.removePrefix(HISTORY_KEY) }
        val live = chosen.filter { !it.key.startsWith(HISTORY_KEY) && it.state !in LOCAL_STATES }
        scope.launch {
            try {
                var ids = known
                if (live.isNotEmpty()) {
                    val r = api.request("chat.history", buildJsonObject {
                        put("conversation_id", conversationId)
                        put("limit", PAGE)
                    }, HISTORY_TIMEOUT_MS)
                    val page = (r["messages"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseHistory) }
                    ids = ids + live.mapNotNull { l ->
                        page.lastOrNull { it.role == l.role && it.text.trim() == l.text.trim() }?.key?.removePrefix(HISTORY_KEY)
                    }
                }
                ids.distinct().chunked(MAX_HIDE).forEach { chunk ->
                    api.request("chat.hide", buildJsonObject {
                        put("conversation_id", conversationId)
                        putJsonArray("message_ids") { chunk.forEach { add(JsonPrimitive(it)) } }
                    })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("Couldn't delete: ${e.message}")
                loadPage(conversationId, null)
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // models and commands (spec/README.md §11)

    fun loadModels() {
        scope.launch {
            try {
                val r = api.request("agent.models", JsonObject(emptyMap()))
                val providers = (r["providers"] as? JsonArray).orEmpty().mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    val id = o.str("id") ?: return@mapNotNull null
                    ModelOptions.Provider(id, o.str("name") ?: id,
                        (o["models"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content })
                }
                _state.update { it.copy(models = ModelOptions(parseModel(r.obj("current")), providers, parseModel(r.obj("default")))) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // no picker until the next session: the bridge or Hermes may be older
            }
        }
    }

    /** Pin the open conversation to [choice], or start the next new one on it. */
    fun pickModel(choice: ModelChoice) {
        val conv = _state.value.openId
        if (conv == null) {
            _state.update { it.copy(draftModel = choice) }
            return
        }
        scope.launch {
            try {
                api.request("conversations.set_model", buildJsonObject {
                    put("conversation_id", conv)
                    put("model", choice.json())
                })
                _state.update { s -> s.copy(conversations = s.conversations.map { if (it.id == conv) it.copy(model = choice) else it }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("Couldn't switch the model: ${e.message}")
            }
        }
    }

    /** Start every device's new chats on [choice], or on the agent's own model when null (§11). */
    fun setDefaultModel(choice: ModelChoice?) {
        _state.update { s -> s.copy(models = s.models?.copy(default = choice)) }
        scope.launch {
            try {
                api.request("agent.set_default_model", buildJsonObject {
                    if (choice == null) put("model", JsonNull) else put("model", choice.json())
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("Couldn't set the default model: ${e.message}")
                loadModels()
            }
        }
    }

    /** Answer the approval [turnId] waits for: once, session, always or deny (§9). */
    fun approve(turnId: String, choice: String) {
        scope.launch {
            try {
                api.request("chat.approve", buildJsonObject {
                    put("turn_id", turnId)
                    put("choice", choice)
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("Couldn't answer the approval: ${e.message}")
            }
        }
    }

    /** A note for the open conversation's running reply (Hermes's /steer). */
    fun steer(text: String) {
        val turn = _state.value.openSummary?.activeTurnId
            ?: _state.value.openMessages.lastOrNull { it.state == MessageState.STREAMING }?.turnId
        if (turn == null) {
            notice("Nothing is running to steer. Send it as a message instead.")
            return
        }
        scope.launch {
            try {
                val r = api.request("chat.steer", buildJsonObject {
                    put("turn_id", turn)
                    put("text", text.trim())
                })
                if ((r["accepted"] as? JsonPrimitive)?.content != "true") notice("Too late to steer: the reply was finishing")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("Couldn't steer: ${e.message}")
            }
        }
    }

    /** A side question about the open conversation (Hermes's /btw), answered beside it. */
    fun aside(text: String) {
        val conv = _state.value.openId
        if (conv == null) {
            notice("Side questions are about a conversation. Open one first.")
            return
        }
        scope.launch {
            try {
                val r = api.request("chat.aside", buildJsonObject {
                    put("conversation_id", conv)
                    put("text", text.trim())
                })
                val id = r.str("aside_id") ?: return@launch
                lock.withLock {
                    _state.update { s ->
                        val list = s.asides[conv].orEmpty()
                        if (list.any { it.id == id }) s else s.copy(asides = s.asides + (conv to list + Aside(id, text.trim())))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("Couldn't ask: ${e.message}")
            }
        }
    }

    fun dismissAside(conversationId: String, id: String) = _state.update { s ->
        s.copy(asides = s.asides + (conversationId to s.asides[conversationId].orEmpty().filter { it.id != id }))
    }

    /** Ask the open conversation's last question again (Hermes's /retry). */
    fun retryLast() {
        val last = _state.value.openMessages.lastOrNull { it.role == Role.USER && it.text.isNotBlank() }
        if (last == null) notice("Nothing to retry yet") else send(last.text)
    }

    /** What Hermes reports about the open conversation (Hermes's /status). */
    fun loadStatus() {
        val conv = _state.value.openId
        if (conv == null) {
            notice("Start the conversation first: there's nothing to report yet")
            return
        }
        scope.launch {
            try {
                val r = api.request("chat.status", buildJsonObject { put("conversation_id", conv) })
                _state.update {
                    it.copy(status = ConversationStatus(
                        conv, parseModel(r.obj("model")), r.long("messages"), r.long("tool_calls"), r.long("input_tokens"),
                        r.long("output_tokens"), (r["cost_usd"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                        r.str("active_turn_id") != null, r.long("queued")?.toInt() ?: 0))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("Couldn't get the status: ${e.message}")
            }
        }
    }

    fun closeStatus() = _state.update { it.copy(status = null) }

    /** Credit left on the agent's provider accounts, such as OpenRouter. */
    fun loadBalance() {
        scope.launch {
            try {
                val r = api.request("account.balance", JsonObject(emptyMap()))
                val list = (r["accounts"] as? JsonArray).orEmpty().mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    val url = o.str("top_up_url")?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
                    AccountBalance(o.str("name") ?: "Account", (o["remaining"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                        o.str("currency"), url, o.str("error"))
                }
                _state.update { it.copy(balances = list) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // an older bridge: no balance to show
            }
        }
    }

    fun notice(text: String) = _state.update { it.copy(notice = text) }

    // requests

    private suspend fun deliver(conv: String?, text: String, cmid: String) {
        try {
            // uploaded again on a retry: the bridge drops a blob once it is sent or an hour old
            val files = outgoing[cmid].orEmpty()
            val total = files.sumOf { it.size }.coerceAtLeast(1)
            var before = 0L
            val blobs = files.map { f ->
                upload(f) { sent -> setProgress(conv, cmid, (before + sent).toFloat() / total) }.also { before += f.size }
            }
            if (files.isNotEmpty()) setProgress(conv, cmid, null)
            val r = api.request("chat.send", buildJsonObject {
                put("text", text)
                conv?.let { put("conversation_id", it) }
                if (conv == null) _state.value.draftModel?.let { put("model", it.json()) }
                put("client_msg_id", cmid)
                if (blobs.isNotEmpty()) put("attachments", JsonArray(blobs.map { id -> buildJsonObject { put("blob_id", id) } }))
                outgoingServer[cmid]?.let { found ->
                    put("files", JsonArray(found.map { f -> buildJsonObject { put("root", f.root); put("path", f.path) } }))
                }
                outgoingTodo[cmid]?.let { put("todo_id", it) }
            }, SEND_TIMEOUT_MS)
            outgoing.remove(cmid)
            outgoingServer.remove(cmid)
            outgoingTodo.remove(cmid)
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

    /**
     * Upload one file in chunks, read from its source as it goes (twice: the digest first, then the chunks), so
     * its size doesn't matter for memory; [sent] hears the bytes sent so far. Returns its blob_id.
     */
    private suspend fun upload(file: OutgoingFile, sent: (Long) -> Unit = {}): String {
        val sha = withContext(io) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.open().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        val begin = api.request("blob.begin", buildJsonObject {
            put("name", file.name)
            put("mime", file.mime)
            put("size", file.size)
            put("sha256", sha)
        })
        val id = begin.str("blob_id") ?: throw RpcException(0, "The bridge didn't start the upload")
        val chunk = (begin.long("chunk_bytes")?.toInt() ?: CHUNK_BYTES).coerceIn(1, CHUNK_BYTES)
        file.open().use { input ->
            var offset = 0L
            while (offset < file.size) {
                val piece = withContext(io) { input.readNBytes(minOf(chunk.toLong(), file.size - offset).toInt()) }
                if (piece.isEmpty()) throw RpcException(0, "${file.name} got shorter while it was sent")
                api.request("blob.put", buildJsonObject {
                    put("blob_id", id)
                    put("offset", offset)
                    put("data", Base64.getEncoder().encodeToString(piece))
                }, SEND_TIMEOUT_MS)
                offset += piece.size
                sent(offset)
            }
        }
        api.request("blob.commit", buildJsonObject { put("blob_id", id) }, SEND_TIMEOUT_MS)
        return id
    }

    private fun setProgress(conv: String?, cmid: String, progress: Float?) = _state.update { s ->
        val update = { list: List<ChatMessage> -> list.map { if (it.clientMsgId == cmid) it.copy(progress = progress) else it } }
        s.withMessages(conv, f = update).let { st -> st.threads.keys.fold(st) { acc, id -> acc.withMessages(id, f = update) } }
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
                        val messages = if (before == null) {
                            val tail = liveTail(old.messages, page)
                            withPreviews(withoutPending(page, tail), old.messages) + tail
                        } else page + old.messages
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
    /**
     * The page without its newest user messages that this device still shows as its own, still sending: Hermes
     * records a message as its turn starts, so history reloaded after a reconnect can hold one that the send's
     * answer hasn't reached yet, and it would show twice (issue 29). The device's copy stays; the next page settles it.
     */
    private fun withoutPending(page: List<ChatMessage>, tail: List<ChatMessage>): List<ChatMessage> {
        val pending = tail.filter { it.role == Role.USER && (it.state == MessageState.SENDING || it.state == MessageState.QUEUED) }
            .map { it.text }.toMutableList()
        var trimmed = page
        while (true) {
            val last = trimmed.lastOrNull() ?: break
            if (last.role != Role.USER || !pending.remove(last.text)) break
            trimmed = trimmed.dropLast(1)
        }
        return trimmed
    }

    private fun liveTail(old: List<ChatMessage>, page: List<ChatMessage>): List<ChatMessage> {
        val streaming = old.filter { it.state == MessageState.STREAMING }.mapNotNull { it.turnId }.toSet()
        val lastAsked = page.lastOrNull { it.role == Role.USER }?.text
        return old.filter {
            it.state in LOCAL_STATES || (it.role == Role.USER && it.turnId in streaming && it.text != lastAsked)
        }
    }

    /** History has no photo bytes: keep the ones this device sent, matched by text and photo count. */
    private fun withPreviews(page: List<ChatMessage>, old: List<ChatMessage>): List<ChatMessage> {
        val donors = old.filter { m -> m.role == Role.USER && m.attachments.any { it.preview != null } }.toMutableList()
        if (donors.isEmpty()) return page
        return page.map { m ->
            val images = m.attachments.count { it.kind == Attachment.Kind.IMAGE }
            if (m.role != Role.USER || images == 0) return@map m
            val donor = donors.firstOrNull { d -> d.text == m.text && d.attachments.count { it.kind == Attachment.Kind.IMAGE } == images }
                ?: return@map m
            donors.remove(donor)
            val previews = donor.attachments.filter { it.kind == Attachment.Kind.IMAGE }.iterator()
            m.copy(attachments = m.attachments.map { a -> if (a.kind == Attachment.Kind.IMAGE && previews.hasNext()) a.copy(preview = previews.next().preview, name = a.name) else a })
        }
    }

    private suspend fun onNewSession() {
        refreshList()
        loadModels()
        loadBalance()
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
                "chat.queued" -> onQueued(p)
                "chat.aside.done" -> onAside(p)
                "chat.hidden" -> onHidden(p)
                "chat.file" -> onFile(p)
                "agent.default_model" -> _state.update { s -> s.copy(models = s.models?.copy(default = parseModel(p.obj("default")))) }
            }
        }
    }

    /** A file the agent sent (§9): its message goes at the end of that conversation, and the list moves. */
    private fun onFile(p: JsonObject) {
        val conv = p.str("conversation_id") ?: return
        val message = p.obj("message")?.let(::parseHistory) ?: return
        _state.update { s -> s.withMessages(conv, onlyLoaded = true) { list -> list.filterNot { it.key == message.key } + message } }
        scope.launch { refreshList() }
    }

    private fun onHidden(p: JsonObject) {
        val conv = p.str("conversation_id") ?: return
        val keys = (p["message_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.let { id -> HISTORY_KEY + id } }.toSet()
        _state.update { s -> s.withMessages(conv, onlyLoaded = true) { list -> list.filterNot { it.key in keys } } }
        // a message this device shows live has no history id yet: the newest page settles it
        if (_state.value.threads[conv]?.messages.orEmpty().any { !it.key.startsWith(HISTORY_KEY) && it.state == MessageState.DONE }) {
            scope.launch { loadPage(conv, null) }
        }
    }

    private fun changed(conv: String) {
        changes[conv] = (changes[conv] ?: 0) + 1
    }

    private fun onSent(r: JsonObject, cmid: String) {
        val conv = r.str("conversation_id") ?: return
        val turn = r.str("turn_id") ?: return
        val title = r.str("title") ?: "Conversation"
        val queued = (r["queued"] as? JsonPrimitive)?.content == "true"
        if (!queued) lastSeq.putIfAbsent(turn, 0)
        changed(conv)
        mine += turn
        _state.update { s0 ->
            val draftModel = s0.draftModel ?: s0.models?.default
            var s = s0.claimDraft(cmid, conv)
            s = s.withMessages(conv) { list ->
                list.map {
                    if (it.clientMsgId != cmid) it
                    // chat.started may have come first and already marked it sent
                    else if (queued && it.state != MessageState.SENDING) it.copy(turnId = turn)
                    else it.copy(state = if (queued) MessageState.QUEUED else MessageState.DONE, turnId = turn, error = null)
                }
            }
            if (s.conversations.none { it.id == conv }) {
                val now = nowMs()
                s = s.upsert(ConversationSummary(conv, "", title, now, now, Role.USER, null, turn, model = draftModel))
                s = s.copy(draftModel = null)
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
        val attachments = parseAttachments(p)
        lastSeq.putIfAbsent(turn, 0)
        changed(conv)
        if (cmid != null && _state.value.let { s -> (s.draft + s.threads.values.flatMap { it.messages }).any { it.key == "local:$cmid" } }) {
            mine += turn
        }
        _state.update { s0 ->
            val existing = s0.conversations.firstOrNull { it.id == conv }
            var s = s0.upsert(
                existing?.copy(title = p.str("title") ?: existing.title, updatedAt = at / 1000, lastRole = Role.USER,
                    lastText = userText, activeTurnId = turn, queuedTurnIds = existing.queuedTurnIds - turn)
                    ?: ConversationSummary(conv, p.str("agent_id").orEmpty(), p.str("title") ?: "Conversation",
                        at / 1000, at / 1000, Role.USER, userText, turn)
            )
            if (cmid != null) s = s.claimDraft(cmid, conv)
            val thread = s.threads[conv]
            if (thread != null && thread.loaded) {
                s = s.withMessages(conv) { list -> withTurn(list, turn, userText, at, cmid, attachments) }
            }
            s
        }
    }

    private fun onQueued(p: JsonObject) {
        val conv = p.str("conversation_id") ?: return
        val turn = p.str("turn_id") ?: return
        val cmid = p.str("client_msg_id")
        val userText = p.str("user_text").orEmpty()
        val attachments = parseAttachments(p)
        if (cmid != null && _state.value.let { s -> (s.draft + s.threads.values.flatMap { it.messages }).any { it.key == "local:$cmid" } }) {
            mine += turn
        }
        _state.update { s0 ->
            var s = s0.copy(conversations = s0.conversations.map {
                if (it.id == conv && turn !in it.queuedTurnIds) it.copy(queuedTurnIds = it.queuedTurnIds + turn) else it
            })
            if (cmid != null) s = s.claimDraft(cmid, conv)
            s.withMessages(conv, onlyLoaded = true) { list ->
                val at = list.indexOfFirst { (cmid != null && it.clientMsgId == cmid) || (it.role == Role.USER && it.turnId == turn) }
                if (at >= 0) {
                    list.mapIndexed { i, m -> if (i == at && m.state != MessageState.DONE) m.copy(state = MessageState.QUEUED, turnId = turn) else m }
                } else {
                    list + ChatMessage("user:$turn", Role.USER, userText, nowMs(), MessageState.QUEUED, turnId = turn,
                        clientMsgId = cmid, attachments = attachments)
                }
            }
        }
    }

    private fun onAside(p: JsonObject) {
        val conv = p.str("conversation_id") ?: return
        val id = p.str("aside_id") ?: return
        val answer = p.str("text").orEmpty()
        val error = p.str("error").takeIf { p.str("status") != "completed" }
        _state.update { s ->
            val list = s.asides[conv].orEmpty()
            val done = Aside(id, p.str("question").orEmpty(), answer, error)
            val updated = if (list.any { it.id == id }) list.map { if (it.id == id) done.copy(question = it.question) else it } else list + done
            s.copy(asides = s.asides + (conv to updated.takeLast(MAX_ASIDES)))
        }
    }

    /** Make sure [list] has this turn's user message and a streaming reply after it. */
    private fun withTurn(
        list: List<ChatMessage>, turn: String, userText: String, atMs: Long, cmid: String?,
        attachments: List<Attachment> = emptyList(),
    ): List<ChatMessage> {
        var out = list
        val mine = out.indexOfFirst { (cmid != null && it.clientMsgId == cmid) || (it.role == Role.USER && it.turnId == turn) }
        out = if (mine >= 0) {
            out.mapIndexed { i, m -> if (i == mine) m.copy(state = MessageState.DONE, turnId = turn, error = null) else m }
        } else {
            out + ChatMessage("user:$turn", Role.USER, userText, atMs, MessageState.DONE, turnId = turn, clientMsgId = cmid,
                attachments = attachments)
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
        "text" -> m.copy(text = m.text + p.str("text").orEmpty(), waitingForApproval = false, approval = null)
        "tool_progress" -> p.obj("tool")?.let { t ->
            val step = ToolStep(t.str("name") ?: "tool", t.str("state") ?: "started", t.str("preview"))
            val open = m.tools.indexOfLast { it.name == step.name && it.state == "started" }
            val tools = if (step.state != "started" && open >= 0) m.tools.mapIndexed { i, x -> if (i == open) step else x } else m.tools + step
            m.copy(tools = tools, waitingForApproval = false, approval = null)
        } ?: m
        "commentary" -> m.copy(commentary = p.str("text"), waitingForApproval = false, approval = null)
        "approval" -> m.copy(commentary = p.str("text"), waitingForApproval = true, approval = parseApproval(p.obj("approval")))
        "approval_done" -> m.copy(commentary = APPROVAL_ANSWERS[p.str("choice")], waitingForApproval = false, approval = null)
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
        val waiting = _state.value.threads[conv]?.messages.orEmpty()
            .any { it.turnId == turn && it.state == MessageState.QUEUED }
        if (waiting || _state.value.conversations.any { it.id == conv && turn in it.queuedTurnIds }) {
            // a queued message removed before it started: no reply to show
            _state.update { s ->
                s.copy(conversations = s.conversations.map { if (it.id == conv) it.copy(queuedTurnIds = it.queuedTurnIds - turn) else it })
                    .withMessages(conv, onlyLoaded = true) { list ->
                        list.map { if (it.turnId == turn && it.role == Role.USER) it.copy(state = MessageState.CANCELLED) else it }
                    }
            }
            mine.remove(turn)
            return
        }
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
                    m.copy(text = text.ifEmpty { m.text }, state = state, error = error, waitingForApproval = false, approval = null, commentary = null)
                }
                if (list.any { it.key == "reply:$turn" }) list.map { if (it.key == "reply:$turn") done(it) else it }
                else list + done(ChatMessage("reply:$turn", Role.ASSISTANT, "", null, state, turnId = turn))
            }
            s
        }
        val title = _state.value.conversations.firstOrNull { it.id == conv }?.title ?: "Talaria"
        _replies.tryEmit(FinishedReply(conv, title, text, state, error, fromThisDevice = mine.remove(turn)))
    }

    private fun applySnapshot(t: JsonObject) {
        val conv = t.str("conversation_id") ?: return
        val turn = t.str("turn_id") ?: return
        val status = t.str("status") ?: return
        val seq = t.long("seq")?.toInt() ?: 0
        if (status == "queued") return // nothing to show until it starts
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
                    withTurn(base, turn, userText, at, null, parseAttachments(t)).map { m ->
                        if (m.key != "reply:$turn") m
                        else m.copy(text = t.str("text").orEmpty(), tools = tools,
                            commentary = if (status == "running") commentary else null,
                            waitingForApproval = waiting && status == "running",
                            approval = if (waiting && status == "running") parseApproval(t.obj("approval")) else null,
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
            model = parseModel(c.obj("model")),
            queuedTurnIds = (c["queued_turn_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            pinned = (c["pinned"] as? JsonPrimitive)?.content == "true",
            archived = (c["archived"] as? JsonPrimitive)?.content == "true",
        )
    }

    private fun parseModel(o: JsonObject?): ModelChoice? {
        val provider = o?.str("provider") ?: return null
        return ModelChoice(provider, o.str("model") ?: return null)
    }

    private fun parseHistory(m: JsonObject): ChatMessage? {
        val role = when (m.str("role")) { "user" -> Role.USER; "assistant" -> Role.ASSISTANT; else -> return null }
        return ChatMessage(
            key = HISTORY_KEY + m.str("id"), role = role, text = m.str("text").orEmpty(),
            atMs = m.long("ts")?.times(1000),
            toolNames = (m["tools"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            attachments = parseAttachments(m),
        )
    }

    private fun parseAttachments(o: JsonObject): List<Attachment> = (o["attachments"] as? JsonArray).orEmpty().mapNotNull { e ->
        val a = e as? JsonObject ?: return@mapNotNull null
        val kind = if (a.str("kind") == "image") Attachment.Kind.IMAGE else Attachment.Kind.FILE
        Attachment(kind, a.str("name") ?: "file", a.str("mime") ?: "application/octet-stream", a.long("size"),
            root = a.str("root"), path = a.str("path"))
    }

    companion object {
        const val AGENT_UNAVAILABLE = -32010
        const val PAGE = 50
        const val SEND_TIMEOUT_MS = 30_000L
        const val HISTORY_TIMEOUT_MS = 30_000L
        const val CHUNK_BYTES = 512 * 1024
        private const val STALE_RETRIES = 3
        private const val MAX_ASIDES = 5
        private const val MAX_HIDE = 50
        private const val HISTORY_KEY = "h:"
        private val LOCAL_STATES = setOf(MessageState.SENDING, MessageState.NOT_SENT, MessageState.QUEUED, MessageState.STREAMING)
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

private fun ModelChoice.json() = buildJsonObject {
    put("provider", provider)
    put("model", model)
}

private val APPROVAL_ANSWERS = mapOf(
    "once" to "Allowed once", "session" to "Allowed for this chat", "always" to "Always allowed", "deny" to "Denied",
)

private fun parseApproval(o: JsonObject?): PendingApproval? {
    o ?: return null
    val choices = (o["choices"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
    return PendingApproval(choices.ifEmpty { listOf("once", "deny") }, o.str("command"), o.str("description"))
}
