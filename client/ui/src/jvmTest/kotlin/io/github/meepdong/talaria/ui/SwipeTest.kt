package io.github.meepdong.talaria.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import kotlin.test.Test
import kotlin.test.assertEquals

/** UX1: the same swipes everywhere notifications show, each with a tap-only twin. */
@OptIn(ExperimentalTestApi::class)
class SwipeTest {
    private class Recorder : TalariaActions {
        val calls = mutableListOf<String>()
        override fun pairWithLink(link: String, deviceName: String) {}
        override fun pairWithCode(code: String, address: String, deviceName: String) {}
        override fun cancelPairing() {}
        override fun testConnection() {}
        override fun reconnectNow() {}
        override fun forgetServer() {}
        override fun dismissHomeItem(id: String, at: Long) { calls += "archive $id $at" }
        override fun markHomeRead(id: String, at: Long, read: Boolean) { calls += "read $id $at $read" }
        override fun markAllHomeRead() { calls += "all read" }
        override fun archiveReadHome() { calls += "archive read" }
        override fun showArchivedHome(open: Boolean) { calls += "archived $open" }
        override fun restoreHomeItem(id: String, at: Long) { calls += "restore $id $at" }
        override fun archiveConversation(id: String, archived: Boolean) { calls += "archive chat $id $archived" }
        override fun pinConversation(id: String, pinned: Boolean) { calls += "pin $id $pinned" }
        override fun setTodoDone(id: String, done: Boolean) { calls += "done $id $done" }
        override fun deleteTodo(id: String) { calls += "delete $id" }
        override fun opsDismiss(requestId: String) { calls += "clear $requestId" }
        override fun askAboutServerResult(requestId: String) { calls += "ask server $requestId" }
        override fun undo() { calls += "undo" }
        override fun newConversation() { calls += "new chat" }
        override fun setAutomationResultTo(id: String, resultTo: String) { calls += "result $id $resultTo" }
    }

    private val unread = DayResult("Flight watch", "No alert.", "19:40", id = "0000000000aa", at = 7)
    private val read = DayResult("Morning summary", "Ship Friday.", "07:00", id = "0000000000bb", at = 3, read = true)

    @Test
    fun yourDaySwipes() = runComposeUiTest {
        val actions = Recorder()
        setContent { TalariaTheme { Column { YourDayCard(HomeView(day = listOf(unread, read)), actions, androidx.compose.ui.Modifier) } } }
        onNodeWithTag("day-0000000000aa").performTouchInput { swipeLeft() }
        waitForIdle()
        onNodeWithTag("day-0000000000aa").performTouchInput { swipeRight() }
        waitForIdle()
        onNodeWithTag("day-0000000000bb").performTouchInput { swipeRight() }
        waitForIdle()
        assertEquals(listOf("archive 0000000000aa 7", "read 0000000000aa 7 true", "read 0000000000bb 3 false"), actions.calls)
        onNodeWithTag("unread-0000000000aa", useUnmergedTree = true).assertExists()
        onNodeWithTag("unread-0000000000bb", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun everySwipeHasATapTwin() = runComposeUiTest {
        val actions = Recorder()
        setContent { TalariaTheme { Column { YourDayCard(HomeView(day = listOf(unread, read)), actions, androidx.compose.ui.Modifier) } } }
        // screen readers: the swipes as actions
        val custom = onNodeWithTag("day-0000000000aa").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Archive", "Mark read"), custom.map { it.label })
        runOnIdle { custom.first { it.label == "Archive" }.action() }
        // the header's ⋮
        onNodeWithTag("day-menu").performClick()
        onNodeWithTag("day-all-read").performClick()
        onNodeWithTag("day-menu").performClick()
        onNodeWithTag("day-archive-read").performClick()
        onNodeWithTag("day-menu").performClick()
        onNodeWithTag("day-archived").performClick()
        assertEquals(listOf("archive 0000000000aa 7", "all read", "archive read", "archived true"), actions.calls)
    }

    @Test
    fun archivedResultsCanBeRestored() = runComposeUiTest {
        val actions = Recorder()
        val home = HomeView(archived = listOf(read.copy(time = "3 Oct 07:00")))
        setContent { TalariaTheme { Column { YourDayCard(home, actions, androidx.compose.ui.Modifier) } } }
        onNodeWithTag("archived-results").assertExists()
        onNodeWithText("3 Oct 07:00").assertExists()
        onNodeWithTag("restore-0000000000bb").performClick()
        assertEquals(listOf("restore 0000000000bb 3"), actions.calls)
    }

    @Test
    fun chatsArchiveLeftAndPinRight() = runComposeUiTest {
        val actions = Recorder()
        val c = ConversationItem("c-1", "Trip", "You: book it", "10:00", running = false)
        setContent { TalariaTheme { Column {
            ConversationRow(c, selected = false, actions)
            ConversationRow(c.copy(id = "c-2"), selected = false, actions, archived = true)
        } } }
        onNodeWithTag("swipe-chat-c-1").performTouchInput { swipeLeft() }
        waitForIdle()
        onNodeWithTag("swipe-chat-c-1").performTouchInput { swipeRight() }
        waitForIdle()
        onNodeWithTag("swipe-chat-c-2").performTouchInput { swipeRight() }
        waitForIdle()
        assertEquals(listOf("archive chat c-1 true", "pin c-1 true", "archive chat c-2 false"), actions.calls)
    }

    @Test
    fun todosDoneRightDeleteLeft() = runComposeUiTest {
        val actions = Recorder()
        val todos = TodosView(groups = listOf(TodoGroupView(null, listOf(TodoItem("t-1", "Call the bank", done = false)))), openCount = 1)
        setContent { TalariaTheme { TodosScreen(todos, actions) } }
        onNodeWithTag("swipe-todo-t-1").performTouchInput { swipeRight() }
        waitForIdle()
        onNodeWithTag("swipe-todo-t-1").performTouchInput { swipeLeft() }
        waitForIdle()
        assertEquals(listOf("done t-1 true", "delete t-1"), actions.calls)
    }

    @Test
    fun serverResultsClearLeftAndAskHermes() = runComposeUiTest {
        val actions = Recorder()
        setContent { TalariaTheme { Column { OpsResultCard(OpsResultItem("r-1", "Restart Hermes", true, "ok", "this device"), actions) } } }
        onNodeWithTag("ops-ask").performClick()
        onNodeWithTag("ops-result").performTouchInput { swipeLeft() }
        waitForIdle()
        assertEquals(listOf("ask server r-1", "clear r-1"), actions.calls)
    }

}
