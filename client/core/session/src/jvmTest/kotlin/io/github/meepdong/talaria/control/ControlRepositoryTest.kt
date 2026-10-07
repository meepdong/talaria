package io.github.meepdong.talaria.control

import io.github.meepdong.talaria.chat.ChatApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
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
    override val notifications = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    override val sessions = MutableSharedFlow<String>(extraBufferCapacity = 4)

    override suspend fun request(method: String, params: JsonObject, timeoutMs: Long?): JsonObject {
        calls += method to params
        return (answers[method] ?: error("unexpected $method"))(params)
    }
}

private fun task(id: String, status: String, extra: String = "") =
    """{"id":"$id","title":"Task $id","status":"$status","priority":0,"created_at":1700000000,"comments":0$extra}"""

private fun board(vararg columns: Pair<String, String>) =
    """{"columns":[${columns.joinToString(",") { (name, tasks) -> """{"name":"$name","tasks":[$tasks]}""" }}]""" + ""","available":true}"""

@OptIn(ExperimentalCoroutinesApi::class)
class ControlRepositoryTest {
    private fun test(body: suspend TestScope.(CoroutineScope) -> Unit) = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            body(scope)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theBoardLoadsMovesAndHearsChanges() = test { scope ->
        val api = FakeApi()
        api.answers["board.get"] = { json(board("todo" to task("t_1", "todo"), "ready" to "")) }
        api.answers["board.update"] = { p -> json("""{"task":${task("t_1", p["status"].toString().trim('"'), ",\"assignee\":\"bot:research\"")}}""") }
        api.answers["board.task"] = { json("""{"task":${task("t_1", "ready")},"comments":[{"author":"owner","text":"Hurry","at":1700000100}]}""") }
        api.answers["board.comment"] = { json("{}") }
        val repo = ControlRepository(scope, api).also { it.start() }
        repo.probe()
        advanceUntilIdle()
        assertEquals(true, repo.state.value.boardAvailable)
        assertEquals(listOf("t_1"), repo.state.value.columns.first { it.name == "todo" }.tasks.map { it.id })

        repo.move("t_1", "ready")
        advanceUntilIdle()
        val s = repo.state.value
        assertEquals(emptyList(), s.columns.first { it.name == "todo" }.tasks)
        assertEquals("bot:research", s.columns.first { it.name == "ready" }.tasks.single().assignee)

        repo.comment("t_1", "Hurry")
        advanceUntilIdle()
        assertEquals(listOf("Hurry"), repo.state.value.comments["t_1"]!!.map { it.text })

        api.notifications.emit(json("""{"jsonrpc":"2.0","tnp":0,"method":"board.changed","params":{"columns":[{"name":"done","tasks":[${task("t_1", "done")}]}]}}"""))
        advanceUntilIdle()
        assertEquals("done", repo.state.value.tasks.single().status)
    }

    @Test
    fun noDoorwayNoBoardAndUsageParses() = test { scope ->
        val api = FakeApi()
        api.answers["usage.get"] = {
            json("""{"days":7,"total":{"cost_usd":0.8,"estimated":true,"input_tokens":150,"output_tokens":25,"sessions":3,"calls":10},
                "by_day":[{"day":"2026-10-05","cost_usd":0.5,"estimated":true,"input_tokens":100,"output_tokens":20,"sessions":2,"calls":7}],
                "by_model":[{"model":"qwen/qwen3.8-flash","cost_usd":0.7,"estimated":true,"input_tokens":145,"output_tokens":24,"sessions":2,"calls":9}]}""")
        }
        val repo = ControlRepository(scope, api).also { it.start() }
        repo.probe()  // board.get isn't answered: an older bridge
        repo.loadUsage(7)
        advanceUntilIdle()
        assertEquals(false, repo.state.value.boardAvailable)
        val u = repo.state.value.usage!!
        assertEquals(0.8, u.total.costUsd)
        assertEquals("qwen/qwen3.8-flash", u.byModel.single().first)
        assertNull(repo.state.value.notice)
    }

    @Test
    fun routinesAndHelpers() = test { scope ->
        val api = FakeApi()
        val routine = """{"id":"r1","bot_id":"bot:research","name":"Weekly digest","schedule":"every monday 8am","task":"Digest",
            "enabled":true,"state":"scheduled","next_run_at":1700100000,"to_chat":true}"""
        api.answers["routines.list"] = { json("""{"routines":[$routine],"available":true}""") }
        api.answers["routines.set"] = { p ->
            if (p["action"].toString() == "\"remove\"") json("{}") else json("""{"routine":${routine.replace("\"enabled\":true", "\"enabled\":false")}}""")
        }
        api.answers["helpers.stop"] = { json("""{"stopped":true}""") }
        val repo = ControlRepository(scope, api).also { it.start() }
        repo.loadRoutines()
        advanceUntilIdle()
        assertEquals(listOf("Weekly digest"), repo.state.value.routines!!.map { it.name })
        repo.setRoutine("bot:research", "r1", "pause")
        advanceUntilIdle()
        assertEquals(false, repo.state.value.routines!!.single().enabled)
        repo.setRoutine("bot:research", "r1", "remove")
        advanceUntilIdle()
        assertEquals(emptyList(), repo.state.value.routines)

        api.notifications.emit(json("""{"jsonrpc":"2.0","tnp":0,"method":"helpers.update","params":{"conversation_id":"c-1",
            "helpers":[{"id":"sa-1","goal":"Find cafes","status":"running","tools":2,"last_tool":"web_search","can_steer":true}]}}"""))
        advanceUntilIdle()
        assertEquals("Find cafes", repo.state.value.helpers["c-1"]!!.single().goal)
        repo.stopHelper("c-1", "sa-1")
        advanceUntilIdle()
        assertEquals(emptyList(), repo.state.value.helpers["c-1"])
        api.notifications.emit(json("""{"jsonrpc":"2.0","tnp":0,"method":"helpers.update","params":{"conversation_id":"c-1","helpers":[]}}"""))
        advanceUntilIdle()
        assertNull(repo.state.value.helpers["c-1"])
    }

    @Test
    fun theBotEditorSavesOnlyWhatChangedAndAsksAboutExpensiveModels() = test { scope ->
        val api = FakeApi()
        api.answers["bots.describe"] = {
            json("""{"bot":{"bot_id":"bot:scout","name":"Scout","about":"Finds things","personality":"Be kind.","model":"qwen/qwen3.8-flash",
                "skills":[{"name":"research","enabled":true},{"name":"spotify","enabled":false}],
                "toolsets":[{"name":"web","label":"Web","about":"Search","enabled":true}],"connectors":[]}}""")
        }
        var confirmNext = true
        api.answers["bots.update"] = { p ->
            if (confirmNext && "model" in p && "confirm" !in p) json("""{"applied":{},"confirm":"pricey/big is expensive"}""")
            else json("""{"applied":{"model":true}}""")
        }
        api.answers["bots.create"] = { json("""{"bot_id":"bot:tripplanner"}""") }
        val repo = ControlRepository(scope, api).also { it.start() }
        repo.editBot("bot:scout")
        advanceUntilIdle()
        val was = repo.state.value.editing!!.settings!!
        assertEquals(listOf("research"), was.skills.filter { it.enabled }.map { it.name })

        val now = was.copy(about = "Finds places to eat", skills = was.skills.map { it.copy(enabled = true) })
        var saved: String? = null
        repo.saveBot(was, now) { saved = it }
        advanceUntilIdle()
        val sent = api.calls.last { it.first == "bots.update" }.second
        assertEquals(setOf("bot_id", "about", "skills"), sent.keys)
        assertEquals("""["research","spotify"]""", sent["skills"].toString())
        assertEquals("bot:scout", saved)

        repo.saveBot(was, was.copy(model = "pricey/big"))
        advanceUntilIdle()
        assertEquals("pricey/big is expensive", repo.state.value.editing!!.confirm)
        repo.saveBot(was, was.copy(model = "pricey/big"), confirm = true)
        advanceUntilIdle()
        assertEquals("true", api.calls.last { it.first == "bots.update" }.second["confirm"].toString())
        assertNull(repo.state.value.editing!!.confirm)

        repo.editBot(null)
        repo.saveBot(null, BotSettings("", "Trip Planner", "Plans trips", "", null, emptyList(), emptyList(), emptyList())) { saved = it }
        advanceUntilIdle()
        assertEquals("bot:tripplanner", saved)
        assertEquals("""{"name":"Trip Planner","about":"Plans trips"}""", api.calls.last { it.first == "bots.create" }.second.toString())
    }

    @Test
    fun historyIsListedReadAndCarriedOn() = test { scope ->
        val api = FakeApi()
        api.answers["history.list"] = { p ->
            if ("offset" in p) json("""{"sessions":[{"session_id":"cron_1","title":"Morning","source":"cron","started_at":1,"messages":4}],"has_more":false}""")
            else json("""{"sessions":[{"session_id":"tg_1","title":"Flights","source":"telegram","started_at":1700000000,"last_active":1700000500,"messages":6,"snippet":"cheap >>>flights<<<"}],"has_more":true}""")
        }
        api.answers["history.read"] = { json("""{"messages":[{"role":"user","text":"Find flights","at":1700000000},{"role":"assistant","text":"Two","at":1700000002}],"has_more":false}""") }
        api.answers["history.continue"] = { json("""{"conversation_id":"c-9","title":"Flights (continued)"}""") }
        val repo = ControlRepository(scope, api).also { it.start() }
        repo.loadPast("flights", null)
        advanceUntilIdle()
        assertEquals("flights", api.calls.last().second["query"].toString().trim('"'))
        assertEquals(listOf("tg_1"), repo.state.value.past.sessions.map { it.id })
        repo.loadPast(more = true)
        advanceUntilIdle()
        assertEquals(listOf("tg_1", "cron_1"), repo.state.value.past.sessions.map { it.id })
        repo.readPast(repo.state.value.past.sessions.first())
        advanceUntilIdle()
        assertEquals(listOf("user", "assistant"), repo.state.value.past.messages.map { it.role })
        var opened: String? = null
        repo.continuePast("tg_1") { opened = it }
        advanceUntilIdle()
        assertEquals("c-9", opened)
        repo.readPast(null)
        assertNull(repo.state.value.past.open)
    }
}
