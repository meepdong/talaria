package io.github.meepdong.talaria.rooms

import io.github.meepdong.talaria.chat.ChatApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

private class FakeApi : ChatApi {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    val answers = mutableMapOf<String, (JsonObject) -> JsonObject>()
    override val notifications = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    override val sessions = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override suspend fun request(method: String, params: JsonObject, timeoutMs: Long?): JsonObject {
        calls += method to params
        return (answers[method] ?: error("unexpected $method"))(params)
    }
    suspend fun push(method: String, params: String) =
        notifications.emit(json("""{"jsonrpc":"2.0","tnp":0,"method":"$method","params":$params}"""))
}

private const val ROOM = """{"id":"r-1","name":"Trip","members":[{"member_id":"scout","name":"Scout","handle":"scout","bot_id":"bot:scout"},
    {"member_id":"default","name":"Tally","handle":"hermes"}],"updated_at":100,"working":false,"needs_you":false}"""

@OptIn(ExperimentalCoroutinesApi::class)
class RoomsRepositoryTest {
    @Test
    fun listOpenUpdateSendApproveAndCreate() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val api = FakeApi()
            api.answers["rooms.list"] = { json("""{"available":true,"rooms":[$ROOM]}""") }
            api.answers["rooms.open"] = { json("""{"room":$ROOM,"has_more":false,"approvals":[],"messages":[
                {"seq":1,"at":10,"kind":"user","speaker":"You","text":"Plan Goa","thread_id":"th-1"}]}""") }
            api.answers["rooms.send"] = { json("""{"message":{"seq":3,"at":12,"kind":"user","speaker":"You","text":"@scout flights?","thread_id":"th-1"}}""") }
            api.answers["rooms.approve"] = { json("{}") }
            api.answers["rooms.create"] = { json("""{"room":{"id":"r-2","name":"New","members":[],"updated_at":200,"working":false,"needs_you":false}}""") }
            val repo = RoomsRepository(scope, api).also { it.start() }
            advanceUntilIdle()
            api.sessions.emit("s-1")
            advanceUntilIdle()
            assertTrue(repo.state.value.available)
            assertEquals(listOf("Trip"), repo.state.value.rooms.map { it.name })

            repo.open("r-1")
            advanceUntilIdle()
            assertEquals(listOf("Plan Goa"), repo.state.value.threads["r-1"]?.messages?.map { it.text })
            api.push("rooms.update", """{"room_id":"r-1","working":true,"needs_you":true,"messages":[
                {"seq":2,"at":11,"kind":"member","speaker":"Scout","text":"Two options, @user which?","member_id":"scout","thread_id":"th-1"}],
                "approvals":[{"approval_id":"a-1","member":"Scout","command":"curl x","choices":["once","deny"]}]}""")
            advanceUntilIdle()
            val thread = repo.state.value.threads["r-1"]!!
            assertEquals(listOf(1L, 2L), thread.messages.map { it.seq })
            assertTrue(thread.working && thread.needsYou)
            assertEquals("Scout: Two options, @user which?", repo.state.value.rooms.single().let { "${it.previewSpeaker}: ${it.previewText}" })

            repo.send("r-1", "@scout flights?", "th-1")
            advanceUntilIdle()
            assertEquals("""{"room_id":"r-1","text":"@scout flights?","thread_id":"th-1"}""", api.calls.last { it.first == "rooms.send" }.second.toString())
            assertEquals(listOf(1L, 2L, 3L), repo.state.value.threads["r-1"]!!.messages.map { it.seq })

            repo.approve("r-1", "a-1", "once")
            advanceUntilIdle()
            assertTrue(repo.state.value.threads["r-1"]!!.approvals.isEmpty())
            assertEquals("""{"room_id":"r-1","approval_id":"a-1","choice":"once"}""", api.calls.last { it.first == "rooms.approve" }.second.toString())

            var made: String? = null
            repo.create("New", listOf("bot:scout", "assistant")) { made = it }
            advanceUntilIdle()
            assertEquals("r-2", made)
            assertEquals("""{"name":"New","members":["bot:scout","assistant"]}""", api.calls.last { it.first == "rooms.create" }.second.toString())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aBridgeWithoutGroupChatsHasNone() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val api = FakeApi()  // no rooms.list answer: the bridge refuses
            val repo = RoomsRepository(scope, api).also { it.start() }
            api.sessions.emit("s-1")
            advanceUntilIdle()
            assertEquals(false, repo.state.value.available)
        } finally {
            scope.cancel()
        }
    }
}
