package io.github.meepdong.talaria.ops

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
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

private class FakeApi : ChatApi {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    val answers = mutableMapOf<String, (JsonObject) -> JsonObject>()
    val signed = mutableListOf<List<String>>()
    override val notifications = MutableSharedFlow<JsonObject>()
    override val sessions = MutableSharedFlow<String>()

    override suspend fun request(method: String, params: JsonObject, timeoutMs: Long?): JsonObject {
        calls += method to params
        return (answers[method] ?: error("unexpected $method"))(params)
    }

    override fun signOpsApproval(requestId: String, op: String, paramsJson: String, choice: String): String {
        signed += listOf(requestId, op, paramsJson, choice)
        return "sig-$choice"
    }
}

private const val REQUEST = """{"jsonrpc":"2.0","tnp":0,"method":"ops.approval.request","params":{
    "request_id":"op-0123456789abcdef","op":"service.restart","params_json":"{\"service\":\"docker\"}","tier":1,
    "summary":"Restart docker","requested_by":"agent:hermes","expires_at":2000}}"""

@OptIn(ExperimentalCoroutinesApi::class)
class OpsRepositoryTest {
    @Test
    fun readsCatalogueAndReads() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = FakeApi()
        api.answers["ops.catalogue"] = { json("""{"ops":[{"op":"service.logs","tier":0,"title":"Service logs","params":{
            "service":{"type":"string","enum":["docker","ssh"]},"lines":{"type":"integer","minimum":1,"maximum":500,"default":100}}}]}""") }
        api.answers["ops.run"] = { p -> json("""{"status":"done","result":{"op":"${p["op"]!!.jsonPrimitive.content}","ok":true,
            "exit_code":0,"summary":"fine","output":"out","finished_at":5}}""") }
        val repo = OpsRepository(scope, api) { 1000 }
        repo.start()
        advanceUntilIdle()
        api.sessions.emit("s-1")
        advanceUntilIdle()
        val lines = repo.state.value.catalogue.single().params.getValue("lines")
        assertEquals(1, lines.min)
        assertEquals(500, lines.max)
        assertEquals(listOf("docker", "ssh"), repo.state.value.catalogue.single().params.getValue("service").choices)

        repo.refresh()
        advanceUntilIdle()
        assertEquals(setOf("system.overview", "services.list", "docker.ps", "bridge.version"), repo.state.value.reads.keys)
        assertTrue(repo.state.value.busy.isEmpty())
        scope.cancel()
    }

    @Test
    fun hermesSkillsAreListedWhenTheServerOffersThem() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = FakeApi()
        api.answers["ops.catalogue"] = { json("""{"ops":[{"op":"hermes.skills","tier":0,"title":"Hermes's skills","params":{}}]}""") }
        api.answers["ops.run"] = { json("""{"status":"done","result":{"op":"hermes.skills","ok":true,"exit_code":0,
            "summary":"1 of 2 skills on for Talaria","output":"","finished_at":5,
            "data":[{"name":"himalaya","description":"Email","category":"email","enabled":true}]}}""") }
        val repo = OpsRepository(scope, api) { 1000 }
        repo.start()
        advanceUntilIdle()
        api.sessions.emit("s-1")
        advanceUntilIdle()
        assertEquals("1 of 2 skills on for Talaria", repo.state.value.reads.getValue("hermes.skills").summary)
        repo.refresh()
        advanceUntilIdle()
        assertTrue("hermes.skills" in repo.state.value.reads)
        scope.cancel()
    }

    @Test
    fun approvalsAreKeptSignedAndSettled() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = FakeApi()
        api.answers["ops.approve"] = { p -> p }
        val repo = OpsRepository(scope, api) { 1000 }
        repo.start()
        advanceUntilIdle()
        api.notifications.emit(json(REQUEST))
        api.notifications.emit(json(REQUEST))  // re-sent on reconnect: not a second card
        advanceUntilIdle()
        val pending = repo.state.value.pending.single()
        assertEquals("Restart docker", pending.summary)

        repo.approve(pending.requestId, "once")
        advanceUntilIdle()
        assertEquals(listOf("op-0123456789abcdef", "service.restart", """{"service":"docker"}""", "once"), api.signed.single())
        val sent = api.calls.single { it.first == "ops.approve" }.second
        assertEquals("sig-once", sent["sig"]!!.jsonPrimitive.content)

        api.notifications.emit(json("""{"method":"ops.approval.done","params":{"request_id":"op-0123456789abcdef","choice":"once"}}"""))
        api.notifications.emit(json("""{"method":"ops.result","params":{"request_id":"op-0123456789abcdef",
            "requested_by":"agent:hermes","approved_by":"D","result":{"op":"service.restart","ok":true,"exit_code":0,
            "summary":"restarting docker","output":""}}}"""))
        api.answers["ops.catalogue"] = { json("""{"ops":[]}""") }
        api.answers["ops.run"] = { json("""{"status":"done"}""") }
        advanceUntilIdle()
        assertTrue(repo.state.value.pending.isEmpty())
        val result = repo.state.value.results.single()
        assertEquals("restarting docker", result.outcome.summary)
        repo.dismissResult(result.requestId)
        assertTrue(repo.state.value.results.isEmpty())
        scope.cancel()
    }

    @Test
    fun expiredRequestsDropAndAnOldBridgeHasNoOps() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = FakeApi()
        api.answers["ops.catalogue"] = { throw RpcException(-32601, "Method not found") }
        val repo = OpsRepository(scope, api) { 3000 }
        repo.start()
        advanceUntilIdle()
        api.notifications.emit(json(REQUEST))  // expires_at 2000, now 3000
        api.notifications.emit(json(REQUEST.replace("op-0123456789abcdef", "op-fedcba9876543210").replace("2000", "4000")))
        advanceUntilIdle()
        assertEquals(listOf("op-fedcba9876543210"), repo.state.value.pending.map { it.requestId })
        api.sessions.emit("s-1")
        advanceUntilIdle()
        assertFalse(repo.state.value.available)
        scope.cancel()
    }
}
