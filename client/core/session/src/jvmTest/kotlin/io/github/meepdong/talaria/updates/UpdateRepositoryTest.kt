package io.github.meepdong.talaria.updates

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
import kotlinx.serialization.json.long
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

private fun release(version: String, code: Long, apk: ByteArray) =
    """{"version":"$version","version_code":$code,"size":${apk.size},"sha256":"${UpdateRepository.sha256(apk)}",
        "published_at":1790000000,"notes":"Fixes."}"""

/** Serves [apk] in chunks of [chunk] bytes, as app.read does. */
private fun reader(apk: ByteArray, chunk: Int = 3): (JsonObject) -> JsonObject = { p ->
    val offset = p["offset"]!!.jsonPrimitive.long.toInt()
    val part = apk.copyOfRange(offset, minOf(apk.size, offset + chunk))
    json("""{"size":${apk.size},"offset":$offset,"data":"${Base64.getEncoder().encodeToString(part)}",
        "eof":${offset + part.size >= apk.size}}""")
}

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateRepositoryTest {
    private val apk = "a signed installer".toByteArray()

    @Test
    fun offersOnlyANewerReleaseAndDownloadsIt() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = FakeApi()
        api.answers["app.latest"] = { json("""{"channel":"beta","release":${release("0.2.0-beta.1", 20001, apk)}}""") }
        val repo = UpdateRepository(scope, api, currentCode = 20001)
        repo.start()
        advanceUntilIdle()
        api.sessions.emit("s-1")
        advanceUntilIdle()
        assertEquals("beta", repo.state.value.channel)
        assertFalse(repo.state.value.available, "the same version isn't an update")
        assertNull(repo.download())

        api.notifications.emit(json("""{"method":"app.available","params":{"platform":"android","channel":"beta",
            "release":${release("0.2.0-beta.2", 20002, apk)}}}"""))
        advanceUntilIdle()
        assertTrue(repo.state.value.available)
        assertEquals("0.2.0-beta.2", repo.state.value.release?.version)

        api.answers["app.read"] = reader(apk)
        assertContentEquals(apk, repo.download())
        assertEquals(listOf(0L, 3L, 6L, 9L, 12L, 15L), api.calls.filter { it.first == "app.read" }
            .map { it.second["offset"]!!.jsonPrimitive.long })
        assertTrue(api.calls.filter { it.first == "app.read" }.all { it.second["version_code"]!!.jsonPrimitive.long == 20002L })
        assertNull(repo.state.value.progress)
        assertNull(repo.state.value.error)
        scope.cancel()
    }

    @Test
    fun aBadDownloadIsNeverHandedOn() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = FakeApi()
        api.answers["app.latest"] = { json("""{"channel":"stable","release":${release("0.2.0", 20099, apk)}}""") }
        val repo = UpdateRepository(scope, api, currentCode = 20001)
        repo.check()
        advanceUntilIdle()
        assertTrue(repo.state.value.available)

        api.answers["app.read"] = reader("tampered installer!".toByteArray().copyOf(apk.size))
        assertNull(repo.download())
        assertEquals("Couldn't update: The download didn't match its checksum", repo.state.value.error)

        // a newer release replaced it while downloading: ask again
        api.answers["app.read"] = { throw RpcException(UpdateRepository.CONFLICT, "A newer release replaced that one") }
        assertNull(repo.download())
        advanceUntilIdle()
        assertEquals(2, api.calls.count { it.first == "app.latest" })
        scope.cancel()
    }

    @Test
    fun aManualCheckSaysUpToDateAndAnOlderBridgeHidesUpdates() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = FakeApi()
        api.answers["app.latest"] = { json("""{"channel":"stable"}""") }
        val repo = UpdateRepository(scope, api, currentCode = 20001)
        repo.check()
        advanceUntilIdle()
        assertTrue(repo.state.value.upToDate)
        assertFalse(repo.state.value.available)

        api.answers["app.latest"] = { throw RpcException(UpdateRepository.METHOD_NOT_FOUND, "Method not found: app.latest") }
        repo.check()
        advanceUntilIdle()
        assertFalse(repo.state.value.supported)
        assertNull(repo.state.value.error)
        scope.cancel()
    }
}
