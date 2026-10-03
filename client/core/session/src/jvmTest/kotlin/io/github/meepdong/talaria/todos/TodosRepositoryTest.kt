package io.github.meepdong.talaria.todos

import io.github.meepdong.talaria.chat.ChatApi
import io.github.meepdong.talaria.session.RpcException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

private class FakeApi : ChatApi {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    val answers = mutableMapOf<String, (JsonObject) -> JsonObject>()
    override val notifications = MutableSharedFlow<JsonObject>()
    override val sessions = MutableSharedFlow<String>()

    override suspend fun request(method: String, params: JsonObject, timeoutMs: Long?): JsonObject {
        calls += method to params
        return (answers[method] ?: error("unexpected $method"))(params)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TodosRepositoryTest {
    @Test
    fun listAddTickAndHearChanges() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = FakeApi()
        api.answers["todos.list"] = {
            json("""{"todos":[{"id":"td-1","text":"Book flights","done":false,"created_at":10,"due":"2026-10-05"}]}""")
        }
        api.answers["todos.add"] = { p ->
            json("""{"todo":{"id":"td-2","text":"${p["text"]!!.jsonPrimitive.content}","done":false,"created_at":20}}""")
        }
        api.answers["todos.update"] = { json("""{"todo":{"id":"td-1","text":"Book flights","done":true,"created_at":10,"done_at":30}}""") }
        val todos = TodosRepository(scope, api)
        todos.start()
        advanceUntilIdle()
        api.sessions.emit("s-1")
        advanceUntilIdle()
        assertEquals(listOf("td-1"), todos.state.value.todos.map { it.id })
        assertEquals("2026-10-05", todos.state.value.todos[0].due)

        todos.add("  Call the bank ")
        advanceUntilIdle()
        assertEquals("Call the bank", api.calls.last().second["text"]!!.jsonPrimitive.content)
        assertEquals(listOf("td-1", "td-2"), todos.state.value.todos.map { it.id })

        todos.setDone("td-1", true)
        advanceUntilIdle()
        assertEquals(listOf("td-2", "td-1"), todos.state.value.todos.map { it.id }, "done ones go last")
        assertEquals(30L, todos.state.value.todos[1].doneAt)

        api.notifications.emit(json("""{"jsonrpc":"2.0","tnp":0,"method":"todos.changed","params":{"todos":[
            {"id":"td-2","text":"Call the bank","done":false,"created_at":20,"conversation_id":"c-9"}]}}"""))
        advanceUntilIdle()
        assertEquals(listOf("c-9"), todos.state.value.todos.map { it.conversationId })

        api.answers["todos.update"] = { json("""{"todo":{"id":"td-2","text":"Call the bank","done":false,"created_at":20}}""") }
        todos.edit("td-2", clearDue = true)
        advanceUntilIdle()
        assertEquals(JsonNull, api.calls.last().second["due"])
        scope.cancel()
    }

    @Test
    fun anOlderBridgeHasNoTodos() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = FakeApi()
        api.answers["todos.list"] = { throw RpcException(-32601, "Method not found: todos.list") }
        val todos = TodosRepository(scope, api)
        todos.refresh()
        advanceUntilIdle()
        assertFalse(todos.state.value.available)
        assertTrue(todos.state.value.todos.isEmpty())
        scope.cancel()
    }
}
