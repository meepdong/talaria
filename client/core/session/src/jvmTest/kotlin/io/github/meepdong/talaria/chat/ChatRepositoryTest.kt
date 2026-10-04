package io.github.meepdong.talaria.chat

import io.github.meepdong.talaria.session.RpcException
import io.github.meepdong.talaria.session.TnpException
import io.github.meepdong.talaria.session.Failure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

/** Answers requests from a table and lets the test push notifications. */
private class FakeApi : ChatApi {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    val answers = mutableMapOf<String, (JsonObject) -> JsonObject>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    override val notifications = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    override val sessions = MutableSharedFlow<String>(extraBufferCapacity = 4)

    override suspend fun request(method: String, params: JsonObject, timeoutMs: Long?): JsonObject {
        calls += method to params
        gates[method]?.await()
        return (answers[method] ?: error("unexpected $method"))(params)
    }

    suspend fun push(method: String, params: String) =
        notifications.emit(json("""{"jsonrpc":"2.0","tnp":0,"method":"$method","params":$params}"""))
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepositoryTest {
    /** runTest, plus a scope for the repository that advanceUntilIdle drives and the end cancels. */
    private fun chatTest(body: suspend TestScope.(CoroutineScope) -> Unit) = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            body(scope)
        } finally {
            scope.cancel()
        }
    }

    private fun repo(scope: CoroutineScope, api: FakeApi): ChatRepository {
        var n = 0
        return ChatRepository(scope, api, nowMs = { 1_000_000L }, newClientMsgId = { "m-${++n}" })
            .also { it.start() }
    }

    private val started = """{"conversation_id":"c-1","turn_id":"t-1","agent_id":"hermes","title":"Hi","user_text":"Hi","started_at":1000,"client_msg_id":"m-1"}"""

    private fun delta(seq: Int, body: String) = """{"conversation_id":"c-1","turn_id":"t-1","seq":$seq,$body}"""

    @Test
    fun serverFilesAreNamedNotUploaded() = chatTest { scope ->
        val api = FakeApi()
        api.answers["chat.send"] = { json("""{"conversation_id":"c-1","turn_id":"t-1","title":"Q3.pdf"}""") }
        val repo = repo(scope, api)
        advanceUntilIdle()
        repo.send("", serverFiles = listOf(ServerFile("inbox", "c-1/b-x-Q3.pdf", "Q3.pdf", "application/pdf", 4)))
        assertEquals(listOf("Q3.pdf"), repo.state.value.draft.single().attachments.map { it.name })
        advanceUntilIdle()
        val sent = api.calls.single { it.first == "chat.send" }.second
        assertEquals(Json.parseToJsonElement("""[{"root":"inbox","path":"c-1/b-x-Q3.pdf"}]"""), sent["files"])
        assertTrue(api.calls.none { it.first.startsWith("blob.") })
    }

    @Test
    fun newConversationStreamsIntoTheDraft() = chatTest { scope ->
        val api = FakeApi()
        api.answers["chat.send"] = { json("""{"conversation_id":"c-1","turn_id":"t-1","title":"Hi"}""") }
        val repo = repo(scope, api)
        advanceUntilIdle()
        val replies = mutableListOf<FinishedReply>()
        scope.launch { repo.replies.collect { replies += it } }

        repo.send("  Hi ")
        assertEquals(listOf("Hi"), repo.state.value.draft.map { it.text })
        assertEquals(MessageState.SENDING, repo.state.value.draft.single().state)
        advanceUntilIdle()
        assertEquals("c-1", repo.state.value.openId)
        assertTrue(repo.state.value.draft.isEmpty())

        api.push("chat.started", started)
        api.push("chat.delta", delta(1, """"kind":"text","text":"Hel""""))
        api.push("chat.delta", delta(2, """"kind":"tool_progress","tool":{"name":"web_search","state":"started"}"""))
        api.push("chat.delta", delta(2, """"kind":"text","text":"DUPLICATE""""))
        api.push("chat.delta", delta(3, """"kind":"tool_progress","tool":{"name":"web_search","state":"completed","preview":"ok"}"""))
        api.push("chat.delta", delta(4, """"kind":"text","text":"lo""""))
        advanceUntilIdle()
        val streaming = repo.state.value.openMessages
        assertEquals(listOf(Role.USER, Role.ASSISTANT), streaming.map { it.role })
        assertEquals("local:m-1", streaming[0].key) // the same row, now sent
        assertEquals(MessageState.DONE, streaming[0].state)
        assertEquals("Hello", streaming[1].text)
        assertEquals(MessageState.STREAMING, streaming[1].state)
        assertEquals(listOf(ToolStep("web_search", "completed", "ok")), streaming[1].tools)
        assertEquals("t-1", repo.state.value.conversations.single().activeTurnId)

        api.push("chat.done", """{"conversation_id":"c-1","turn_id":"t-1","seq":5,"status":"completed","text":"Hello!"}""")
        advanceUntilIdle()
        val done = repo.state.value.openMessages[1]
        assertEquals("Hello!", done.text)
        assertEquals(MessageState.DONE, done.state)
        val summary = repo.state.value.conversations.single()
        assertNull(summary.activeTurnId)
        assertEquals("Hello!", summary.lastText)
        assertEquals(listOf(FinishedReply("c-1", "Hi", "Hello!", MessageState.DONE, null, fromThisDevice = true)), replies)
    }

    @Test
    fun queuedMessagesWaitTheirTurn() = chatTest { scope ->
        val api = FakeApi()
        var sends = 0
        api.answers["chat.send"] = {
            sends++
            if (sends == 1) json("""{"conversation_id":"c-1","turn_id":"t-1","title":"Hi"}""")
            else json("""{"conversation_id":"c-1","turn_id":"t-$sends","title":"Hi","queued":true}""")
        }
        api.answers["chat.cancel"] = { json("""{"turn_id":"t-3","status":"cancelled"}""") }
        val repo = repo(scope, api)
        advanceUntilIdle()
        repo.send("Hi")
        advanceUntilIdle()
        api.push("chat.started", started)
        advanceUntilIdle()
        repo.send("And then?")
        repo.send("Never mind")
        advanceUntilIdle()
        api.push("chat.queued", """{"conversation_id":"c-1","turn_id":"t-2","user_text":"And then?","position":1,"client_msg_id":"m-2"}""")
        api.push("chat.queued", """{"conversation_id":"c-1","turn_id":"t-3","user_text":"Never mind","position":2,"client_msg_id":"m-3"}""")
        advanceUntilIdle()
        val waiting = repo.state.value.openMessages
        assertEquals(listOf("Hi", "", "And then?", "Never mind"), waiting.map { it.text })
        assertEquals(listOf(MessageState.QUEUED, MessageState.QUEUED), waiting.drop(2).map { it.state })
        assertEquals(listOf("t-2", "t-3"), repo.state.value.conversations.single().queuedTurnIds)

        // the second one is taken back, the first one starts when the reply ends
        repo.stop("t-3")
        api.push("chat.done", """{"conversation_id":"c-1","turn_id":"t-3","seq":1,"status":"cancelled","text":""}""")
        api.push("chat.done", """{"conversation_id":"c-1","turn_id":"t-1","seq":1,"status":"completed","text":"Hello"}""")
        api.push("chat.started", started.replace("t-1", "t-2").replace("m-1", "m-2").replace("\"Hi\",\"started", "\"And then?\",\"started"))
        advanceUntilIdle()
        val after = repo.state.value.openMessages
        assertEquals(listOf("local:m-1", "reply:t-1", "local:m-2", "local:m-3", "reply:t-2"), after.map { it.key })
        assertEquals(MessageState.DONE, after[2].state)
        assertEquals(MessageState.CANCELLED, after[3].state)
        assertEquals(MessageState.STREAMING, after[4].state)
        assertEquals(emptyList(), repo.state.value.conversations.single().queuedTurnIds)
    }

    @Test
    fun modelsAsidesStatusAndBalance() = chatTest { scope ->
        val api = FakeApi()
        api.answers["conversations.list"] = { json("""{"conversations":[]}""") }
        api.answers["agent.models"] = {
            json("""{"agent_id":"hermes","current":{"provider":"openrouter","model":"anthropic/claude-sonnet-4"},
                "providers":[{"id":"openrouter","name":"OpenRouter","models":["anthropic/claude-sonnet-4","x/y"]}]}""")
        }
        api.answers["account.balance"] = {
            json("""{"accounts":[{"provider":"openrouter","name":"OpenRouter","remaining":74.75,"currency":"USD","top_up_url":"https://openrouter.ai/settings/credits"}]}""")
        }
        api.answers["chat.send"] = { json("""{"conversation_id":"c-1","turn_id":"t-1","title":"Hi"}""") }
        api.answers["chat.aside"] = { json("""{"aside_id":"a-1"}""") }
        api.answers["chat.status"] = { json("""{"conversation_id":"c-1","queued":0,"messages":4,"input_tokens":50,"cost_usd":0.01}""") }
        val repo = repo(scope, api)
        advanceUntilIdle()
        api.sessions.emit("s-1")
        advanceUntilIdle()
        val s0 = repo.state.value
        assertEquals("claude-sonnet-4", s0.models!!.current!!.shortName)
        assertEquals(listOf("x/y"), s0.models!!.providers.single().models.drop(1))
        assertEquals(AccountBalance("OpenRouter", 74.75, "USD", "https://openrouter.ai/settings/credits", null), s0.balances.single())

        // a model picked before the first message goes with it
        repo.pickModel(ModelChoice("openrouter", "x/y"))
        repo.send("Hi")
        advanceUntilIdle()
        val sent = api.calls.last { it.first == "chat.send" }.second
        assertEquals("""{"provider":"openrouter","model":"x/y"}""", sent["model"].toString())
        assertEquals(ModelChoice("openrouter", "x/y"), repo.state.value.conversations.single().model)
        assertNull(repo.state.value.draftModel)

        repo.aside("what did I ask?")
        advanceUntilIdle()
        assertEquals(listOf(Aside("a-1", "what did I ask?")), repo.state.value.asides["c-1"])
        api.push("chat.aside.done", """{"conversation_id":"c-1","aside_id":"a-1","question":"what did I ask?","status":"completed","text":"Hi"}""")
        advanceUntilIdle()
        assertEquals("Hi", repo.state.value.asides.getValue("c-1").single().answer)

        repo.loadStatus()
        advanceUntilIdle()
        val status = repo.state.value.status!!
        assertEquals(listOf<Any?>(4L, 50L, null, 0.01, false), listOf(status.messages, status.inputTokens, status.outputTokens, status.costUsd, status.running))

        api.answers["chat.steer"] = { json("""{"turn_id":"t-1","accepted":true}""") }
        repo.steer("faster")
        advanceUntilIdle()
        assertEquals("""{"turn_id":"t-1","text":"faster"}""", api.calls.last().second.toString())
        api.push("chat.done", """{"conversation_id":"c-1","turn_id":"t-1","seq":1,"status":"completed","text":"Hello"}""")
        advanceUntilIdle()
        repo.steer("faster")
        advanceUntilIdle()
        assertEquals("Nothing is running to steer. Send it as a message instead.", repo.state.value.notice)
    }

    @Test
    fun startedBeforeTheSendResultDoesNotDuplicate() = chatTest { scope ->
        val api = FakeApi()
        api.gates["chat.send"] = CompletableDeferred()
        api.answers["chat.send"] = { json("""{"conversation_id":"c-1","turn_id":"t-1","title":"Hi"}""") }
        val repo = repo(scope, api)
        advanceUntilIdle()
        repo.send("Hi")
        advanceUntilIdle()
        api.push("chat.started", started)
        advanceUntilIdle()
        api.gates.getValue("chat.send").complete(Unit)
        advanceUntilIdle()
        val messages = repo.state.value.openMessages
        assertEquals(listOf("local:m-1", "reply:t-1"), messages.map { it.key })
        assertEquals("c-1", repo.state.value.openId)
    }

    @Test
    fun onlyRepliesToThisDeviceAreMarkedAsItsOwn() = chatTest { scope ->
        val api = FakeApi()
        api.gates["chat.send"] = CompletableDeferred()
        api.answers["chat.send"] = { json("""{"conversation_id":"c-1","turn_id":"t-1","title":"Hi"}""") }
        val repo = repo(scope, api)
        advanceUntilIdle()
        val replies = mutableListOf<FinishedReply>()
        scope.launch { repo.replies.collect { replies += it } }
        repo.send("Hi")
        advanceUntilIdle()
        // the turn starts before chat.send answers: still this device's question
        api.push("chat.started", started)
        advanceUntilIdle()
        api.push("chat.done", """{"conversation_id":"c-1","turn_id":"t-1","seq":1,"status":"completed","text":"Hello"}""")
        api.gates.getValue("chat.send").complete(Unit)
        advanceUntilIdle()
        // another device's question in the same conversation
        api.push("chat.started", started.replace("t-1", "t-2").replace("m-1", "m-other"))
        api.push("chat.done", """{"conversation_id":"c-1","turn_id":"t-2","seq":1,"status":"completed","text":"Yo"}""")
        advanceUntilIdle()
        assertEquals(listOf("Hello" to true, "Yo" to false), replies.map { it.text to it.fromThisDevice })
    }

    @Test
    fun aGapInDeltasCatchesUpFromTheSnapshot() = chatTest { scope ->
        val api = FakeApi()
        api.answers["chat.history"] = { json("""{"messages":[{"id":"1","role":"user","text":"Older","ts":900}],"next_before":null}""") }
        api.answers["conversations.list"] = {
            json("""{"conversations":[{"conversation_id":"c-1","agent_id":"hermes","title":"Hi","created_at":1,"updated_at":2}]}""")
        }
        api.answers["chat.turn.get"] = {
            json("""{"turn":{"conversation_id":"c-1","turn_id":"t-1","seq":3,"status":"running","user_text":"Hi","text":"Hel","tools":[],"commentary":["thinking"],"started_at":1000}}""")
        }
        val repo = repo(scope, api)
        advanceUntilIdle()
        api.sessions.emit("s-1")
        advanceUntilIdle()
        repo.open("c-1")
        advanceUntilIdle()
        api.push("chat.started", started.replace(""","client_msg_id":"m-1"""", ""))
        api.push("chat.delta", delta(3, """"kind":"text","text":"lo""""))  // 1 and 2 were missed
        advanceUntilIdle()
        assertEquals("chat.turn.get", api.calls.last().first)
        val reply = repo.state.value.openMessages.last()
        assertEquals("Hel", reply.text)
        assertEquals("thinking", reply.commentary)
        api.push("chat.delta", delta(4, """"kind":"text","text":"lo""""))
        advanceUntilIdle()
        assertEquals("Hello", repo.state.value.openMessages.last().text)
        assertEquals(listOf("Older", "Hi", "Hello"), repo.state.value.openMessages.map { it.text })
    }

    @Test
    fun aFailedSendCanBeRetriedWithTheSameId() = chatTest { scope ->
        val api = FakeApi()
        var fail = true
        api.answers["chat.send"] = {
            if (fail) throw TnpException(Failure.CLOSED, "not connected")
            json("""{"conversation_id":"c-1","turn_id":"t-1","title":"Hi"}""")
        }
        val repo = repo(scope, api)
        repo.send("Hi")
        advanceUntilIdle()
        val failed = repo.state.value.draft.single()
        assertEquals(MessageState.NOT_SENT, failed.state)
        assertEquals("Not connected to the bridge", failed.error)

        fail = false
        repo.retry(failed.key)
        advanceUntilIdle()
        val sends = api.calls.filter { it.first == "chat.send" }.map { it.second["client_msg_id"].toString() }
        assertEquals(listOf("\"m-1\"", "\"m-1\""), sends)
        assertEquals(MessageState.DONE, repo.state.value.openMessages.single().state)
    }

    @Test
    fun noChatAgentIsShownOnce() = chatTest { scope ->
        val api = FakeApi()
        api.answers["conversations.list"] = { throw RpcException(-32010, "No chat agent is configured on the bridge") }
        val repo = repo(scope, api)
        advanceUntilIdle()
        api.sessions.emit("s-1")
        advanceUntilIdle()
        assertEquals("No chat agent is configured on the bridge", repo.state.first { it.listLoaded }.unavailable)
    }

    @Test
    fun aTurnFromAnotherDeviceShowsUp() = chatTest { scope ->
        val api = FakeApi()
        api.answers["chat.history"] = { json("""{"messages":[],"next_before":null}""") }
        val repo = repo(scope, api)
        repo.open("c-1")
        advanceUntilIdle()
        api.push("chat.started", started.replace(""""m-1"""", """"m-other""""))
        api.push("chat.delta", delta(1, """"kind":"approval","text":"Waiting for approval in Hermes: rm""""))
        advanceUntilIdle()
        val (user, reply) = repo.state.value.openMessages
        assertEquals("Hi", user.text)
        assertTrue(reply.waitingForApproval)
        api.push("chat.done", """{"conversation_id":"c-1","turn_id":"t-1","seq":2,"status":"failed","text":"","error":"denied"}""")
        advanceUntilIdle()
        val failed = repo.state.value.openMessages.last()
        assertEquals(MessageState.FAILED, failed.state)
        assertEquals("denied", failed.error)
        assertEquals(false, failed.waitingForApproval)
    }

    @Test
    fun anApprovalIsAnsweredFromTheApp() = chatTest { scope ->
        val api = FakeApi()
        api.answers["chat.history"] = { json("""{"messages":[],"next_before":null}""") }
        api.answers["chat.approve"] = { json("""{"turn_id":"t-1","choice":"session"}""") }
        val repo = repo(scope, api)
        repo.open("c-1")
        advanceUntilIdle()
        api.push("chat.started", started)
        api.push("chat.delta", delta(1, """"kind":"approval","text":"Hermes asks to run: rm","approval":{"choices":["once","session","deny"],"command":"rm -rf build","description":"recursive delete","request_id":"r-1"}"""))
        advanceUntilIdle()
        val asked = repo.state.value.openMessages.last()
        assertTrue(asked.waitingForApproval)
        assertEquals(PendingApproval(listOf("once", "session", "deny"), "rm -rf build", "recursive delete"), asked.approval)

        repo.approve("t-1", "session")
        advanceUntilIdle()
        assertEquals("chat.approve" to """{"turn_id":"t-1","choice":"session"}""", api.calls.last().let { it.first to it.second.toString() })
        api.push("chat.delta", delta(2, """"kind":"approval_done","choice":"session""""))
        advanceUntilIdle()
        val answered = repo.state.value.openMessages.last()
        assertEquals(false, answered.waitingForApproval)
        assertNull(answered.approval)
        assertEquals("Allowed for this chat", answered.commentary)
    }

    @Test
    fun aTurnDuringAHistoryReloadIsNotLost() = chatTest { scope ->
        val api = FakeApi()
        val before = """{"id":"1","role":"user","text":"Hi","ts":1},{"id":"2","role":"assistant","text":"Hello","ts":2}"""
        val after = """$before,{"id":"3","role":"user","text":"Hi","ts":3},{"id":"4","role":"assistant","text":"Hello!","ts":4}"""
        var historyCalls = 0
        api.answers["chat.history"] = { json("""{"messages":[${if (++historyCalls <= 2) before else after}],"next_before":null}""") }
        api.answers["conversations.list"] = { json("""{"conversations":[]}""") }
        val repo = repo(scope, api)
        repo.open("c-1")
        advanceUntilIdle()

        // a reconnect reloads the thread, and a turn starts and ends while that page is on its way
        api.gates["chat.history"] = CompletableDeferred()
        api.sessions.emit("s-2")
        advanceUntilIdle()
        api.push("chat.started", started)
        api.push("chat.done", """{"conversation_id":"c-1","turn_id":"t-1","seq":1,"status":"completed","text":"Hello!"}""")
        advanceUntilIdle()
        api.gates.getValue("chat.history").complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("Hi", "Hello", "Hi", "Hello!"), repo.state.value.openMessages.map { it.text })
        assertEquals(3, historyCalls)
    }
}
