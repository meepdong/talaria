package io.github.meepdong.talaria.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.meepdong.talaria.schedule.Automation
import io.github.meepdong.talaria.schedule.AutomationRan
import io.github.meepdong.talaria.schedule.AutomationRun
import io.github.meepdong.talaria.schedule.CalendarEvent
import io.github.meepdong.talaria.schedule.ScheduleState
import io.github.meepdong.talaria.schedule.When
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ScheduleScreenTest {
    private class Recorder : TalariaActions {
        val calls = mutableListOf<String>()
        val drafts = mutableListOf<AutomationDraft>()
        override fun pairWithLink(link: String, deviceName: String) {}
        override fun pairWithCode(code: String, address: String, deviceName: String) {}
        override fun cancelPairing() {}
        override fun testConnection() {}
        override fun reconnectNow() {}
        override fun forgetServer() {}
        override fun addAutomation(draft: AutomationDraft) { drafts += draft }
        override fun describeAutomation(text: String) { calls += "describe $text" }
        override fun setAutomationPaused(id: String, paused: Boolean) { calls += "paused $id $paused" }
        override fun setAutomationResultTo(id: String, resultTo: String) { calls += "result $id $resultTo" }
        override fun runAutomation(id: String) { calls += "run $id" }
        override fun runAutomationInChat(id: String) { calls += "chat $id" }
        override fun deleteAutomation(id: String) { calls += "delete $id" }
    }

    private val utc = ZoneOffset.UTC

    // Monday 5 October 2026, 09:35 UTC
    private val now = ZonedDateTime.of(2026, 10, 5, 9, 35, 0, 0, utc).toInstant().toEpochMilli()
    private fun at(h: Int, m: Int, day: Int = 5) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, utc).toEpochSecond()

    private val morning = Automation("00000000000a", "Morning summary",
        When.Arrives("the transcript email", "10:30", "13:00", listOf("mon", "tue", "wed", "thu", "fri"), "calendar only"),
        "Summarise.", "home", "talaria", "scheduled", "Weekdays 10:30–13:00, when it arrives: the transcript email",
        nextRunAt = at(10, 30), lastRunAt = at(12, 10, day = 2), lastStatus = "nothing")
    private val digest = Automation("00000000000b", "Email digest", When.Time("0 18 * * *"), "Digest.", "chat", "talaria",
        "scheduled", "0 18 * * *", nextRunAt = at(18, 0))
    private val review = Automation("0000000000ff", "Weekly review", When.Other, "Review", "log", "agent", "paused",
        "Fridays at 17:00", lastRunAt = at(17, 0, day = 2), lastStatus = "error", lastError = "No access")
    private val state = ScheduleState(
        automations = listOf(morning, digest, review),
        loaded = true,
        events = listOf(
            CalendarEvent("Stand-up", "2026-10-05T09:00:00Z", "2026-10-05T09:15:00Z", false),
            CalendarEvent("Company catch-up", "2026-10-05T15:00:00+05:30", "2026-10-05T15:30:00+05:30", false, "Meet"),
            CalendarEvent("Holiday", "2026-10-05", "2026-10-06", true),
        ),
        today = listOf(AutomationRan("00000000000a", "Morning summary", "home", AutomationRun(at(9, 20), "ok", "Ship Friday."))),
    )

    @Test
    fun labels() {
        assertEquals("in 25 min", untilLabel(now + 25 * 60_000, now, utc))
        assertEquals("in 1 h 25 min", untilLabel(now + 85 * 60_000, now, utc))
        assertEquals("18:00", untilLabel(at(18, 0) * 1000, now, utc))
        assertEquals("Tomorrow 08:00", untilLabel(at(8, 0, day = 6) * 1000, now, utc))
        assertEquals("Now", untilLabel(now - 1000, now, utc))
        assertEquals(at(10, 30) * 1000, nextWorkMs(morning, now, utc), "a window automation's next work is its window")
        val inWindow = ZonedDateTime.of(2026, 10, 5, 11, 0, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals(inWindow, nextWorkMs(morning, inWindow, utc))
        val friEvening = ZonedDateTime.of(2026, 10, 9, 14, 0, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals(at(10, 30, day = 12) * 1000, nextWorkMs(morning, friEvening, utc), "after Friday's window, Monday's")
        assertNull(nextWorkMs(review, now, utc), "paused")
    }

    @Test
    fun mapping() {
        val v = scheduleView(state, now, utc)
        assertEquals(listOf("09:00", "09:30", "All day"), v.agenda.map { it.time }.let { listOf(it[0], it[1], it[2]) })
        assertTrue(v.agenda[0].past)
        assertEquals("until 10:00 · Meet", v.agenda[1].detail)
        val (m, d, r) = v.automations
        assertEquals("in 55 min", m.next)
        assertEquals("Checked Fri 12:10, nothing yet", m.last)
        assertEquals("A new chat", d.resultTo)
        assertEquals("chat", d.resultKey)
        assertTrue(r.paused && r.failed && r.byAgent)
        assertEquals("Failed Fri 17:00: No access", r.last)

        val home = HomeView().withSchedule(state, now, utc)
        assertEquals(listOf("Ship Friday."), home.day.map { it.text })
        assertEquals(listOf("Company catch-up", "Morning summary", "Email digest"), home.nextUp.map { it.title })
        assertEquals(listOf("Now", "in 55 min", "18:00"), home.nextUp.map { it.until }, "the catch-up has started")
        assertEquals(listOf("Morning summary", "Email digest"), home.automationsOn.map { it.name })
        assertFalse(HomeView().withSchedule(ScheduleState(available = false), now, utc).automationsAvailable)
    }

    @Test
    fun aBlockedRunOffersRunInChat() = runComposeUiTest {
        val tidy = Automation("0000000000cc", "Tidy downloads", When.Time("*/10 * * * *"), "Tidy.", "log", "talaria",
            "scheduled", "*/10 * * * *", lastRunAt = at(9, 30), lastStatus = "blocked")
        val blocked = state.copy(automations = listOf(tidy), today = listOf(
            AutomationRan(tidy.id, tidy.name, "log", AutomationRun(at(9, 30), "blocked", blocked = "recursive delete"))))
        val item = scheduleView(blocked, now, utc).automations.single()
        assertTrue(item.blocked && item.failed)
        assertEquals("Blocked 09:30: it needs your approval", item.last)
        val day = HomeView().withSchedule(blocked, now, utc).day.single()
        assertEquals("recursive delete", day.blocked)
        assertTrue(blocked.today.single().notificationText().contains("recursive delete"))

        val actions = Recorder()
        setContent {
            TalariaTheme {
                Box(Modifier.size(1200.dp, 2400.dp)) {
                    androidx.compose.foundation.layout.Column {
                        DayCards(HomeView().withSchedule(blocked, now, utc), actions, wide = true)
                        ScheduleScreen(scheduleView(blocked, now, utc), actions)
                    }
                }
            }
        }
        onNodeWithTag("blocked-0000000000cc").assertTextContains("recursive delete", substring = true)
        // a blocked run waits in Needs you, not in Your day
        onNode(hasTestTag("blocked-0000000000cc") and hasAnyAncestor(hasTestTag("needs-you"))).assertExists()
        onNode(hasText("Nothing yet today", substring = true) and hasAnyAncestor(hasTestTag("your-day"))).assertExists()
        onNodeWithTag("run-in-chat-0000000000cc").performClick()
        onNodeWithTag("automation-chat-0000000000cc").performScrollTo().performClick()
        assertEquals(listOf("chat 0000000000cc", "chat 0000000000cc"), actions.calls)
    }

    @Test
    fun draftReadiness() {
        assertTrue(AutomationDraft.MORNING_SUMMARY.ready)
        assertFalse(AutomationDraft.MORNING_SUMMARY.copy(until = "10:00").ready)
        assertFalse(AutomationDraft(name = "x", task = "y").ready, "a time needs a schedule")
        assertTrue(AutomationDraft(name = "x", task = "y", schedule = "0 9 * * *").ready)
    }

    @Test
    fun templateDescribeAndActions() = runComposeUiTest {
        val actions = Recorder()
        setContent { TalariaTheme { Box(Modifier.size(1200.dp, 2400.dp)) { ScheduleScreen(scheduleView(state, now, utc), actions) } } }
        onNodeWithText("Company catch-up").assertExists()
        onNodeWithTag("automation-run-00000000000a").performScrollTo().performClick()
        onNodeWithTag("automation-on-00000000000b").performScrollTo().performClick()
        // a job Hermes made reports to Home from now on; picking the current choice again does nothing
        onNodeWithTag("automation-result-home-0000000000ff").performScrollTo().performClick()
        onNodeWithTag("automation-result-chat-00000000000b").performScrollTo().performClick()
        onNodeWithTag("automation-delete-0000000000ff").performScrollTo().performClick()
        onNodeWithTag("describe-input").performScrollTo().performTextInput("every weekday at 8, summarise my email")
        onNodeWithTag("describe").performScrollTo().performClick()

        onNodeWithTag("add-automation").performScrollTo().assertIsNotEnabled()
        onNodeWithTag("template-morning").performScrollTo().performClick()
        onNodeWithTag("draft-from").performScrollTo().assertTextContains("10:30")
        onNodeWithTag("add-automation").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(AutomationDraft.MORNING_SUMMARY), actions.drafts)
        assertEquals(listOf("run 00000000000a", "paused 00000000000b true", "result 0000000000ff home", "delete 0000000000ff",
            "describe every weekday at 8, summarise my email"), actions.calls)
    }

    @Test
    fun anOlderBridge() = runComposeUiTest {
        setContent { TalariaTheme { ScheduleScreen(ScheduleView(available = false), Recorder()) } }
        onNodeWithTag("schedule-unavailable").assertExists()
    }

    @Test
    fun needsYouHoldsServerApprovalsEvenWithoutAutomations() = runComposeUiTest {
        val actions = Recorder()
        val home = HomeView(automationsAvailable = false,
            approvals = listOf(OpsApprovalItem("op-1", "Restart docker", "service: docker", "Hermes", 1)))
        setContent { TalariaTheme { androidx.compose.foundation.layout.Column { DayCards(home, actions, wide = false) } } }
        onNode(hasTestTag("ops-approval") and hasAnyAncestor(hasTestTag("needs-you"))).assertExists()
        onNodeWithTag("your-day").assertDoesNotExist()
    }

    @Test
    fun noNeedsYouWhenNothingWaits() = runComposeUiTest {
        setContent { TalariaTheme { androidx.compose.foundation.layout.Column { DayCards(HomeView(), Recorder(), wide = false) } } }
        onNodeWithTag("needs-you").assertDoesNotExist()
        onNodeWithTag("your-day").assertExists()
    }

}
