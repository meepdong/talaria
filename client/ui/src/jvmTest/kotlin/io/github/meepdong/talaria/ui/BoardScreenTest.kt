package io.github.meepdong.talaria.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.meepdong.talaria.chat.Bot
import io.github.meepdong.talaria.control.BoardColumn
import io.github.meepdong.talaria.control.BoardComment
import io.github.meepdong.talaria.control.BoardTask
import io.github.meepdong.talaria.control.ControlState
import io.github.meepdong.talaria.control.Usage
import io.github.meepdong.talaria.control.UsageReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BoardScreenTest {
    private class Recorder : TalariaActions {
        val calls = mutableListOf<String>()
        override fun pairWithLink(link: String, deviceName: String) {}
        override fun pairWithCode(code: String, address: String, deviceName: String) {}
        override fun cancelPairing() {}
        override fun testConnection() {}
        override fun reconnectNow() {}
        override fun forgetServer() {}
        override fun showBoard(show: Boolean) { calls += "show $show" }
        override fun boardAdd(title: String, assignee: String?) { calls += "add $title $assignee" }
        override fun boardOpen(taskId: String?) { calls += "open $taskId" }
        override fun boardMove(taskId: String, status: String) { calls += "move $taskId $status" }
        override fun boardGive(taskId: String, assignee: String) { calls += "give $taskId $assignee" }
        override fun boardComment(taskId: String, text: String) { calls += "comment $taskId $text" }
        override fun loadUsage(days: Int) { calls += "usage $days" }
    }

    private val bots = listOf(Bot("bot:meetingminder", "Meeting Minder", "meetingminder"), Bot("bot:research", "Research", "research"))
    private val now = 1_700_010_000_000L
    private val state = ControlState(
        boardAvailable = true, boardLoaded = true,
        columns = listOf(
            BoardColumn("todo", listOf(BoardTask("t_1", "Book the hall", "todo", createdAt = 1_700_000_000))),
            BoardColumn("ready", emptyList()),
            BoardColumn("running", listOf(BoardTask("t_2", "Summarise Monday's call", "running", createdAt = 1_700_000_000,
                startedAt = 1_700_009_000, assignee = "bot:meetingminder", summary = "Reading the notes", comments = 1))),
        ),
        openTask = "t_2",
        comments = mapOf("t_2" to listOf(BoardComment("owner", "Keep it short", 1_700_009_500))),
    )

    @Test
    fun mapping() {
        val v = boardView(state, showing = true, bots = bots, nowMs = now)
        assertEquals(listOf("To do", "Working on it"), v.columns.map { it.label }, "empty columns are left out")
        assertEquals("Meeting Minder", v.columns[1].tasks.single().who)
        assertEquals("16 min ago", v.columns[1].tasks.single().age)
        assertEquals(2, v.taskCount)
        val d = v.detail!!
        assertEquals(listOf(Triple("You", "Keep it short", "8 min ago")), d.comments)
        assertTrue("running" !in d.moves.map { it.first } && "archived" in d.moves.map { it.first })
        assertEquals(listOf("assistant", "bot:meetingminder", "bot:research"), v.people.map { it.first })
        assertEquals(BoardView(), boardView(ControlState(), true, bots, now), "no board on this bridge")
    }

    @Test
    fun usageMapping() {
        val u = usageView(UsageReport(7, Usage(6.653, true, 4_597_220, 351_425, 77, 874),
            listOf("2026-10-05" to Usage(2.0, true, 1, 1, 1, 1), "2026-10-06" to Usage(1.0, false, 1, 1, 1, 1)),
            listOf("qwen/qwen3.8-flash" to Usage(2.23, true, 1, 1, 47, 508))))
        assertEquals("$6.65 (estimate)", u.total)
        assertEquals("874 calls · 77 chats · 4.6M tokens in, 351k out", u.detail)
        assertEquals(listOf("Mon 5" to 1f, "Tue 6" to 0.5f), u.byDay.map { it.label to it.fraction })
        assertEquals("qwen/qwen3.8-flash" to "$2.23 · 508 calls", u.byModel.single())
    }

    @Test
    fun theBoardOnAPhone() = runComposeUiTest {
        val actions = Recorder()
        val board = boardView(state, showing = true, bots = bots, nowMs = now)
        setContent { Box(Modifier.size(380.dp, 1400.dp)) { TodosTab(TodosView(), board, actions) } }
        onNodeWithTag("show-board").assertTextContains("Bots' board · 2")
        onNodeWithText("Reading the notes").assertExists()
        onNodeWithText("Keep it short").assertExists()
        onNodeWithTag("task-move").performClick()
        onNodeWithTag("move-done").performClick()
        onNodeWithTag("task-give").performClick()
        onNodeWithTag("give-bot:research").performClick()
        onNodeWithTag("task-comment").performTextInput("Thanks")
        onNodeWithTag("task-comment-send").performClick()
        onNodeWithTag("task-title").performTextInput("Draft the vendor email")
        onNodeWithTag("task-for").performClick()
        onNodeWithTag("for-bot:research").performClick()
        onNodeWithTag("task-add").assertTextContains("Add and start").performClick()
        onNodeWithTag("task-t_1").performClick()
        onNodeWithTag("show-list").performClick()
        assertEquals(listOf("move t_2 done", "give t_2 bot:research", "comment t_2 Thanks",
            "add Draft the vendor email bot:research", "open t_1", "show false"), actions.calls)
    }

    @Test
    fun noBoardMeansJustTheList() = runComposeUiTest {
        setContent { Box(Modifier.size(380.dp, 800.dp)) { TodosTab(TodosView(), BoardView(), Recorder()) } }
        onNodeWithTag("show-board").assertDoesNotExist()
        onNodeWithTag("todos-page").assertExists()
    }
}
