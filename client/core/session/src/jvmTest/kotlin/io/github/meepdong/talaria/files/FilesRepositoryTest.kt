package io.github.meepdong.talaria.files

import io.github.meepdong.talaria.chat.ChatApi
import io.github.meepdong.talaria.chat.ServerFile
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
class FilesRepositoryTest {
    @Test
    fun browseSearchAndRead() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = FakeApi()
        api.answers["files.roots"] = {
            json("""{"agent_id":"hermes","roots":[{"id":"inbox","name":"Sent from Talaria"},{"id":"workspace","name":"Hermes workspace"}]}""")
        }
        api.answers["files.list"] = { p ->
            val path = p["path"]?.jsonPrimitive?.content ?: ""
            if (p["query"] != null) {
                json("""{"root":"inbox","path":"$path","truncated":true,"entries":[{"name":"t.txt","path":"c-1/t.txt","kind":"file","size":3,"mime":"text/plain","modified":5}]}""")
            } else if (path == "") {
                json("""{"root":"inbox","path":"","truncated":false,"entries":[{"name":"c-1","path":"c-1","kind":"folder","modified":9}]}""")
            } else {
                json("""{"root":"inbox","path":"c-1","truncated":false,"entries":[{"name":"Q3.pdf","path":"c-1/b-x-Q3.pdf","kind":"file","size":4,"mime":"application/pdf","modified":7}]}""")
            }
        }
        val content = ByteArray(700) { it.toByte() }
        api.answers["files.read"] = { p ->
            val offset = p["offset"]!!.jsonPrimitive.long.toInt()
            val end = minOf(content.size, offset + 512)
            val data = Base64.getEncoder().encodeToString(content.copyOfRange(offset, end))
            json("""{"size":${content.size},"mime":"application/pdf","offset":$offset,"data":"$data","eof":${end == content.size}}""")
        }
        val repo = FilesRepository(scope, api)
        repo.load()
        advanceUntilIdle()
        val s = repo.state.value
        assertEquals(listOf("inbox", "workspace"), s.roots.map { it.id })
        assertEquals("inbox", s.root)
        assertEquals(listOf("c-1"), s.entries.map { it.name })

        repo.open(s.entries.single())
        advanceUntilIdle()
        val pdf = repo.state.value.entries.single()
        assertEquals("c-1", repo.state.value.path)
        assertEquals(ServerFile("inbox", "c-1/b-x-Q3.pdf", "Q3.pdf", "application/pdf", 4), repo.serverFile(pdf))

        repo.search("t.")
        advanceUntilIdle()
        assertEquals("t.", repo.state.value.query)
        assertEquals(true, repo.state.value.truncated)
        repo.up()
        advanceUntilIdle()
        assertEquals(null, repo.state.value.query, "up leaves the search first")
        assertEquals("c-1", repo.state.value.path)
        repo.up()
        advanceUntilIdle()
        assertEquals("", repo.state.value.path)

        assertContentEquals(content, repo.read("inbox", "c-1/b-x-Q3.pdf"))
        assertEquals(2, api.calls.count { it.first == "files.read" })
        scope.cancel()
    }

    @Test
    fun aBridgeWithoutFoldersSaysSo() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = FakeApi()
        api.answers["files.roots"] = { throw RpcException(-32012, "This bridge shares no folders") }
        val repo = FilesRepository(scope, api)
        repo.load()
        advanceUntilIdle()
        assertFalse(repo.state.value.available)
        assertEquals("This bridge shares no folders", repo.state.value.error)
        scope.cancel()
    }
}
