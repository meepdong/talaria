package io.github.meepdong.talaria.schedule

import io.github.meepdong.talaria.chat.ChatApi
import io.github.meepdong.talaria.session.RpcException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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

private const val MORNING = """{"id":"00000000000a","name":"Morning summary","when":{"kind":"arrives",
    "watch":"the transcript email","from":"10:30","until":"13:00","days":["mon","tue","wed","thu","fri"],
    "fallback":"calendar only"},"task":"Summarise.","result_to":"home","made_in":"talaria","state":"scheduled",
    "schedule_text":"Weekdays 10:30–13:00","next_run_at":2000}"""

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleRepositoryTest {
    @Test
    fun loadsListCalendarAndHome() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = FakeApi()
        api.answers["automations.list"] = { json("""{"automations":[$MORNING,
            {"id":"0000000000ff","name":"Weekly review","when":{"kind":"other"},"task":"Review","result_to":"log",
             "made_in":"agent","state":"paused","schedule_text":"Fridays at 17:00"}]}""") }
        api.answers["calendar.day"] = { json("""{"date":"2026-10-05","events":[
            {"title":"Company catch-up","start":"2026-10-05T10:00:00+05:30","end":"2026-10-05T10:30:00+05:30","all_day":false}]}""") }
        api.answers["home.get"] = { json("""{"date":"2026-10-05","results":[
            {"id":"00000000000a","name":"Morning summary","run":{"at":1500,"status":"ok","text":"Ship Friday."}}]}""") }
        val repo = ScheduleRepository(scope, api)
        repo.start()
        advanceUntilIdle()
        api.sessions.emit("s-1")
        advanceUntilIdle()
        val s = repo.state.value
        val morning = s.automations[0]
        assertEquals(When.Arrives("the transcript email", "10:30", "13:00", listOf("mon", "tue", "wed", "thu", "fri"), "calendar only"), morning.`when`)
        assertEquals(2000L, morning.nextRunAt)
        assertTrue(s.automations[1].paused)
        assertEquals(When.Other, s.automations[1].`when`)
        assertEquals("Company catch-up", s.events.single().title)
        assertEquals("Ship Friday.", s.today.single().run.text)

        val heard = mutableListOf<AutomationRan>()
        val collecting = scope.launch { repo.ran.collect { heard += it } }
        api.notifications.emit(json("""{"method":"automations.ran","params":{"id":"0000000000ff","name":"Weekly review",
            "result_to":"home","run":{"at":1600,"status":"ok","text":"Good week."}}}"""))
        advanceUntilIdle()
        assertEquals(listOf("Weekly review", "Morning summary"), repo.state.value.today.map { it.name })
        assertEquals("Good week.", heard.single().run.text)
        collecting.cancel()
        scope.cancel()
    }

    @Test
    fun aBlockedRunReachesHomeAndRunsInAChat() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = FakeApi()
        api.answers["automations.run_in_chat"] = { json("""{"conversation_id":"c-9","turn_id":"t-1","title":"Run my automation"}""") }
        val repo = ScheduleRepository(scope, api)
        repo.start()
        advanceUntilIdle()
        api.notifications.emit(json("""{"method":"automations.ran","params":{"id":"0000000000ff","name":"Tidy downloads",
            "result_to":"log","run":{"at":1600,"status":"blocked","blocked":"recursive delete"}}}"""))
        advanceUntilIdle()
        val blocked = repo.state.value.today.single()
        assertTrue(blocked.forHome, "a blocked run shows on Home whatever its result_to")
        assertEquals("recursive delete", blocked.run.blocked)

        var opened: String? = null
        repo.runInChat("0000000000ff") { opened = it }
        advanceUntilIdle()
        assertEquals("c-9", opened)
        assertEquals("automations.run_in_chat" to "0000000000ff", api.calls.last().let { it.first to it.second["id"]!!.jsonPrimitive.content })
        scope.cancel()
    }

    @Test
    fun addDescribePauseAndDelete() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = FakeApi()
        api.answers["automations.add"] = { json("""{"automation":$MORNING}""") }
        api.answers["automations.describe"] = { json("""{"reply":"Set up for 8:00 on weekdays.","automations":[$MORNING]}""") }
        api.answers["automations.update"] = { p ->
            json(MORNING.replace("\"scheduled\"", if (p["paused"]?.jsonPrimitive?.content == "true") "\"paused\"" else "\"scheduled\"")
                .let { """{"automation":$it}""" })
        }
        api.answers["automations.delete"] = { json("""{"id":"00000000000a","deleted":true}""") }
        val repo = ScheduleRepository(scope, api)
        var outcome: String? = "pending"
        repo.add("Morning summary", When.Arrives("the transcript email", "10:30", "13:00", listOf("fri", "mon"), " "),
            "Summarise.") { outcome = it }
        advanceUntilIdle()
        assertEquals(null, outcome)
        val sent = api.calls.last().second["when"]!!.jsonObject
        assertEquals(listOf("mon", "fri"), sent["days"]!!.jsonArray.map { it.jsonPrimitive.content }, "days in week order")
        assertFalse("fallback" in sent, "a blank fallback isn't sent")
        assertEquals(1, repo.state.value.automations.size)

        repo.describe("every weekday at 8, summarise my email")
        assertTrue(repo.state.value.describing)
        advanceUntilIdle()
        assertEquals("Set up for 8:00 on weekdays.", repo.state.value.describeReply)
        assertFalse(repo.state.value.describing)

        repo.setPaused("00000000000a", true)
        advanceUntilIdle()
        assertTrue(repo.state.value.automations.single().paused)
        repo.delete("00000000000a")
        advanceUntilIdle()
        assertTrue(repo.state.value.automations.isEmpty())

        api.answers["automations.add"] = { throw RpcException(-32602, "Invalid schedule") }
        repo.add("x", When.Time("bad"), "y") { outcome = it }
        advanceUntilIdle()
        assertEquals("Invalid schedule", outcome)
        scope.cancel()
    }

    @Test
    fun dismissTakesARunOffHomeHereAndOnOtherDevices() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = FakeApi()
        api.answers["automations.list"] = { json("""{"automations":[$MORNING]}""") }
        api.answers["calendar.day"] = { json("""{"date":"2026-10-05","events":[]}""") }
        api.answers["home.get"] = { json("""{"date":"2026-10-05","results":[
            {"id":"0000000000cc","name":"Tidy downloads","run":{"at":1600,"status":"blocked","blocked":"recursive delete"}},
            {"id":"00000000000a","name":"Morning summary","run":{"at":1500,"status":"ok","text":"Ship Friday."}}]}""") }
        api.answers["home.dismiss"] = { json("{}") }
        val repo = ScheduleRepository(scope, api)
        repo.start()
        advanceUntilIdle()
        api.sessions.emit("s-1")
        advanceUntilIdle()
        assertEquals(listOf("Tidy downloads", "Morning summary"), repo.state.value.today.map { it.name })

        repo.dismissHomeItem("0000000000cc", 1600)
        assertEquals(listOf("Morning summary"), repo.state.value.today.map { it.name }, "gone before the bridge answers")
        advanceUntilIdle()
        assertEquals("home.dismiss" to json("""{"id":"0000000000cc","at":1600}"""), api.calls.last())

        // another device dismissed the summary: the bridge tells everyone
        api.notifications.emit(json("""{"method":"home.changed","params":{"date":"2026-10-05","results":[]}}"""))
        advanceUntilIdle()
        assertEquals(emptyList(), repo.state.value.today)
        scope.cancel()
    }

    @Test
    fun readArchivedAndRestore() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = FakeApi()
        api.answers["automations.list"] = { json("""{"automations":[$MORNING]}""") }
        api.answers["calendar.day"] = { json("""{"date":"2026-10-05","events":[]}""") }
        api.answers["home.get"] = { json("""{"date":"2026-10-05","results":[
            {"id":"00000000000a","name":"Morning summary","run":{"at":1500,"status":"ok","text":"Ship Friday."},"read":true}]}""") }
        api.answers["home.read"] = { json("{}") }
        api.answers["home.restore"] = { json("{}") }
        api.answers["home.archived"] = { json("""{"results":[
            {"id":"0000000000cc","name":"Tidy downloads","run":{"at":900,"status":"ok","text":"Deleted 3 files."}}]}""") }
        val repo = ScheduleRepository(scope, api)
        repo.start()
        assertEquals(false, repo.state.value.homeLoaded, "nothing to clear notifications by until Home has loaded")
        advanceUntilIdle()
        api.sessions.emit("s-1")
        advanceUntilIdle()
        assertEquals(true, repo.state.value.homeLoaded)
        assertEquals(true, repo.state.value.today.single().read)

        repo.markHomeRead("00000000000a", 1500, false)
        assertEquals(false, repo.state.value.today.single().read, "unread at once")
        advanceUntilIdle()
        assertEquals("home.read" to json("""{"id":"00000000000a","at":1500,"read":false}"""), api.calls.last())

        repo.loadArchived()
        advanceUntilIdle()
        assertEquals(listOf("Deleted 3 files."), repo.state.value.archived?.map { it.run.text })
        repo.restoreHomeItem("0000000000cc", 900)
        assertEquals(emptyList(), repo.state.value.archived)
        advanceUntilIdle()
        assertEquals("home.restore" to json("""{"id":"0000000000cc","at":900}"""), api.calls.last())
        repo.closeArchived()
        assertEquals(null, repo.state.value.archived)
        scope.cancel()
    }
}
