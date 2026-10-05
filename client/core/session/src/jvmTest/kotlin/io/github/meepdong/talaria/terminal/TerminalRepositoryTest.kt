package io.github.meepdong.talaria.terminal

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

private class FakeApi : ChatApi {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    val answers = mutableMapOf<String, (JsonObject) -> JsonObject>()
    override val notifications = MutableSharedFlow<JsonObject>()
    override val sessions = MutableSharedFlow<String>()

    override suspend fun request(method: String, params: JsonObject, timeoutMs: Long?): JsonObject {
        calls += method to params
        return (answers[method] ?: { json("{}") })(params)
    }

    fun last(method: String) = calls.last { it.first == method }.second
}

private const val ME = "PHONEDEVICEID"
private val G1 = "tg-" + "1".repeat(32)
private val G2 = "tg-" + "2".repeat(32)

private fun result(requestId: String, by: String, op: String, grant: String) = json("""{"method":"ops.result","params":{
    "request_id":"$requestId","requested_by":"device:$by","approved_by":"$by","result":{"op":"$op","ok":true,"exit_code":0,
    "summary":"Watching","output":"","started_at":1,"finished_at":1,"data":{"grant":"$grant","session":"claude",
    "control":${op == "terminal.control"},"expires_at":2}}}}""")

private fun screen(grant: String, text: String, control: Boolean) = json("""{"method":"term.screen","params":{"grant":"$grant",
    "session":"claude","cols":89,"rows":33,"cursor_x":2,"cursor_y":1,"command":"claude","control":$control,"text":"$text"}}""")

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalRepositoryTest {
    @Test
    fun opensWithThisDevicesApprovalThenWatchesAndTypes() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = FakeApi()
        var asked = 0
        api.answers["ops.run"] = { p ->
            if (p["op"].toString().contains("tmux.sessions")) json("""{"status":"done","result":{"op":"tmux.sessions","ok":true,
                "exit_code":0,"summary":"1 tmux session","output":"","started_at":1,"finished_at":1,"data":{"sessions":[
                {"name":"claude","command":"claude","path":"/root","cols":89,"rows":33,"attached":2,"activity":100}]}}}""")
            else json("""{"status":"pending","request_id":"op-${++asked}"}""")
        }
        val repo = TerminalRepository(scope, api, ME)
        repo.start()
        advanceUntilIdle()
        repo.refresh()
        advanceUntilIdle()
        assertEquals(listOf(TmuxSession("claude", "claude", "/root", 89, 33, 2, 100)), repo.state.value.sessions)

        repo.open("claude")
        advanceUntilIdle()
        assertEquals("op-1", repo.state.value.requestId)
        assertEquals("""{"op":"terminal.watch","params":{"session":"claude"}}""", api.last("ops.run").toString())

        api.notifications.emit(result("op-1", "LAPTOPID", "terminal.watch", G1))  // another device approved: not ours
        advanceUntilIdle()
        assertNull(repo.state.value.grant)
        api.notifications.emit(result("op-1", ME, "terminal.watch", G1))
        advanceUntilIdle()
        assertEquals(G1, repo.state.value.grant)
        assertEquals("""{"grant":"$G1"}""", api.last("term.watch").toString())

        api.notifications.emit(screen(G2, "not mine", false))
        api.notifications.emit(screen(G1, "Claude Code", false))
        advanceUntilIdle()
        assertEquals("Claude Code", repo.state.value.screen?.text)
        repo.keys(listOf(TermKey.Key("Enter")))
        advanceUntilIdle()
        assertEquals(0, api.calls.count { it.first == "term.keys" }, "a watch grant doesn't type")

        repo.open("claude", control = true)  // take control: the watch goes on until the new grant comes
        advanceUntilIdle()
        assertEquals("op-2", repo.state.value.requestId)
        assertEquals(G1, repo.state.value.grant)
        api.notifications.emit(result("op-2", ME, "terminal.control", G2))
        advanceUntilIdle()
        assertEquals(G2, repo.state.value.grant)
        assertEquals("""{"grant":"$G1"}""", api.last("term.stop").toString(), "the watch grant ends")
        api.notifications.emit(screen(G2, "Claude Code", true))
        advanceUntilIdle()
        repo.keys(listOf(TermKey.Text("1"), TermKey.Key("Enter")))
        advanceUntilIdle()
        assertEquals("""{"grant":"$G2","keys":[{"text":"1"},{"key":"Enter"}]}""", api.last("term.keys").toString())

        api.sessions.emit("s-2")  // reconnected: watch again with the same grant
        advanceUntilIdle()
        assertEquals("""{"grant":"$G2"}""", api.last("term.watch").toString())

        api.notifications.emit(json("""{"method":"term.closed","params":{"grant":"$G2","reason":"the session claude has ended"}}"""))
        advanceUntilIdle()
        assertNull(repo.state.value.grant)
        assertEquals("the session claude has ended", repo.state.value.closed)

        repo.close()
        advanceUntilIdle()
        assertNull(repo.state.value.session)
        scope.cancel()
    }

    @Test
    fun aDeniedOrExpiredApprovalGoesBackToTheList() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = FakeApi()
        api.answers["ops.run"] = { json("""{"status":"pending","request_id":"op-9"}""") }
        val repo = TerminalRepository(scope, api, ME)
        repo.start()
        advanceUntilIdle()
        repo.open("claude")
        advanceUntilIdle()
        api.notifications.emit(json("""{"method":"ops.approval.done","params":{"request_id":"op-9","choice":"expired"}}"""))
        advanceUntilIdle()
        assertNull(repo.state.value.requestId)
        assertNull(repo.state.value.session)

        api.answers["term.watch"] = { throw RpcException(-32013, "no such terminal grant, or it ended") }
        repo.open("claude")
        advanceUntilIdle()
        api.notifications.emit(result("op-9", ME, "terminal.watch", G1))
        advanceUntilIdle()
        assertNull(repo.state.value.grant, "a grant that already ended isn't kept")
        assertEquals("no such terminal grant, or it ended", repo.state.value.error)
        scope.cancel()
    }
}
