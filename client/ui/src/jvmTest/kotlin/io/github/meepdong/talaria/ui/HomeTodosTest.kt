package io.github.meepdong.talaria.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.meepdong.talaria.chat.ChatState
import io.github.meepdong.talaria.chat.ConversationSummary
import io.github.meepdong.talaria.chat.Role
import io.github.meepdong.talaria.todos.Todo
import io.github.meepdong.talaria.todos.TodosState
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HomeTodosTest {
    private class Recorder : TalariaActions {
        val calls = mutableListOf<String>()
        override fun pairWithLink(link: String, deviceName: String) {}
        override fun pairWithCode(code: String, address: String, deviceName: String) {}
        override fun cancelPairing() {}
        override fun testConnection() {}
        override fun reconnectNow() {}
        override fun forgetServer() {}
        override fun openConversation(id: String) { calls += "open $id" }
        override fun addTodo(text: String) { calls += "add $text" }
        override fun setTodoDone(id: String, done: Boolean) { calls += "done $id $done" }
        override fun deleteTodo(id: String) { calls += "delete $id" }
        override fun handTodoToAgent(id: String) { calls += "hand $id" }
    }

    // Saturday 3 October 2026, 12:00 UTC
    private val now = 1_791_028_800_000L
    private val todaySec = now / 1000
    private val status = StatusView(rows = emptyList(), lastConnected = "", deviceName = "Laptop", server = "", keyProtection = "",
        overall = Health.GOOD, summary = "Connected")

    private fun home(): HomeView {
        val chat = chatView(ChatState(conversations = listOf(
            ConversationSummary("c-1", "hermes", "From my to-do list", 1, todaySec, Role.ASSISTANT, "On it", "t-1"),
        ), listLoaded = true), false, true, status, now)
        val todos = TodosState(todos = listOf(
            Todo("td-1", "Book flights", false, 100, due = "2026-10-01"),
            Todo("td-2", "Summarise Q3", false, 200, conversationId = "c-1"),
            Todo("td-3", "Call the bank", false, 300, due = "2026-10-04", conversationId = "c-0"),
            Todo("td-4", "Pay rent", true, 50, doneAt = todaySec - 60),
            Todo("td-5", "Old thing", true, 10, doneAt = todaySec - 3 * 86_400),
        ), loaded = true)
        return homeView(chat, now, todos, ZoneOffset.UTC)
    }

    @Test
    fun mapping() {
        val h = home()
        assertEquals(listOf("td-1", "td-2", "td-3", "td-4"), h.todos.map { it.id }, "done today stays; older done is counted")
        assertEquals(1, h.doneEarlier)
        val (flights, q3, bank) = h.todos
        assertEquals("Overdue · 1 Oct", flights.due)
        assertTrue(flights.overdue)
        assertTrue(q3.withAgent, "its chat is replying")
        assertEquals("Tomorrow", bank.due)
        assertFalse(bank.withAgent)
        val today = LocalDate.of(2026, 10, 3)
        assertEquals("Today", dueLabel(today, today))
        assertEquals("Fri 9 Oct", dueLabel(LocalDate.of(2026, 10, 9), today))
        assertTrue(homeView(chatView(ChatState(), false, true, status, now), now, TodosState(available = false)).todosAvailable.not())
    }

    @Test
    fun addTickHandAndOpen() = runComposeUiTest {
        val actions = Recorder()
        val screen = Screen.Chat(chatView(ChatState(), false, true, status, now), status, home = home())
        setContent { TalariaTheme { Box(Modifier.size(900.dp, 1200.dp)) { HomeScreen(screen, actions) } } }
        onNodeWithTag("todo-input").performScrollTo().performTextInput("Renew passport")
        onNodeWithTag("todo-add").performScrollTo().performClick()
        onNodeWithTag("todo-done-td-1").performScrollTo().performClick()
        onNodeWithTag("todo-hand-td-1").performScrollTo().performClick()
        onNodeWithTag("todo-open-td-2").performScrollTo().performClick()
        onNodeWithTag("todo-open-td-3").performScrollTo().performClick()
        onNodeWithTag("todo-delete-td-4").performScrollTo().performClick()
        onNodeWithText("1 done earlier").assertExists()
        onNodeWithTag("todo-hand-td-4").assertDoesNotExist()
        assertEquals(
            listOf("add Renew passport", "done td-1 true", "hand td-1", "open c-1", "open c-0", "delete td-4"),
            actions.calls,
        )
    }
}
