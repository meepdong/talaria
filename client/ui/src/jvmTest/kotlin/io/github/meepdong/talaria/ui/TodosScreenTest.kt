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
import io.github.meepdong.talaria.chat.ModelChoice
import io.github.meepdong.talaria.chat.ModelOptions
import io.github.meepdong.talaria.todos.Todo
import io.github.meepdong.talaria.todos.TodoComment
import io.github.meepdong.talaria.todos.TodosState
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TodosScreenTest {
    private class Recorder : TalariaActions {
        val calls = mutableListOf<String>()
        override fun pairWithLink(link: String, deviceName: String) {}
        override fun pairWithCode(code: String, address: String, deviceName: String) {}
        override fun cancelPairing() {}
        override fun testConnection() {}
        override fun reconnectNow() {}
        override fun forgetServer() {}
        override fun addTodo(text: String) { calls += "add $text" }
        override fun setTodoDone(id: String, done: Boolean) { calls += "done $id $done" }
        override fun handTodoToAgent(id: String) { calls += "hand $id" }
        override fun setTodoDue(id: String, due: String?) { calls += "due $id $due" }
        override fun setTodoGroup(id: String, group: String?) { calls += "group $id $group" }
        override fun commentOnTodo(id: String, text: String) { calls += "comment $id $text" }
        override fun deleteTodoComment(id: String, commentId: String) { calls += "uncomment $id $commentId" }
        override fun regroupTodos() { calls += "regroup" }
        override fun setDefaultModel(provider: String?, model: String?) { calls += "default $provider $model" }
    }

    // Saturday 3 October 2026, 12:00 UTC
    private val now = 1_791_028_800_000L
    private val todaySec = now / 1000
    private val status = StatusView(rows = emptyList(), lastConnected = "", deviceName = "Laptop", server = "", keyProtection = "",
        overall = Health.GOOD, summary = "Connected")

    private fun view(): TodosView {
        val chat = chatView(ChatState(), false, true, status, now)
        val todos = TodosState(todos = listOf(
            Todo("td-1", "Book flights", false, 100, group = "Travel",
                comments = listOf(TodoComment("tc-1", "Window seat", false, todaySec - 60), TodoComment("tc-2", "Found one", true, 10))),
            Todo("td-2", "Buy milk", false, 200),
            Todo("td-3", "Pay rent", false, 300, group = "Admin", due = "2026-10-04"),
            Todo("td-4", "Old thing", true, 10, doneAt = todaySec - 60, group = "Home"),
        ), loaded = true)
        return todosView(todos, chat, now, ZoneOffset.UTC)
    }

    @Test
    fun mapping() {
        val v = view()
        assertEquals(listOf("Admin", "Travel", null), v.groups.map { it.name }, "groups A to Z, unsorted last")
        assertEquals(3, v.openCount)
        assertEquals(listOf("td-4"), v.done.map { it.id })
        assertEquals(listOf("Admin", "Home", "Travel"), v.groupNames)
        val flights = v.groups[1].items.single()
        assertEquals(listOf("11:59", "1 Jan"), flights.comments.map { it.time })
        assertEquals(listOf(false, true), flights.comments.map { it.byAgent })
        assertEquals("Tomorrow", v.groups[0].items.single().due)

        val today = LocalDate.of(2026, 10, 3)
        assertEquals("2026-10-03", dueFromChoice("today", today))
        assertEquals("2026-10-10", dueFromChoice("next week", today))
        assertNull(dueFromChoice("whenever", today))

        val many = TodosState(todos = (1..8).map { Todo("td-$it", "Thing $it", false, it.toLong()) }, loaded = true)
        val home = homeView(chatView(ChatState(), false, true, status, now), now, many, ZoneOffset.UTC)
        assertEquals(HOME_TODOS, home.todos.size)
        assertEquals(3, home.moreTodos)
    }

    @Test
    fun commentGroupAndSort() = runComposeUiTest {
        val actions = Recorder()
        setContent { TalariaTheme { Box(Modifier.size(900.dp, 1400.dp)) { TodosScreen(view(), actions) } } }
        onNodeWithTag("todo-group-Travel").assertExists()
        onNodeWithTag("todo-group-none").assertExists()
        onNodeWithText("Not sorted yet").assertExists()

        onNodeWithTag("todos-input").performTextInput("Renew passport")
        onNodeWithTag("todos-add").performClick()
        onNodeWithTag("todos-regroup").performClick()

        onNodeWithTag("todo-row-td-1").performScrollTo().performClick()
        onNodeWithTag("todo-details-td-1").assertExists()
        onNodeWithText("Window seat").assertExists()
        onNodeWithTag("todo-uncomment-tc-2").performScrollTo().performClick()
        onNodeWithTag("todo-comment-input-td-1").performScrollTo().performTextInput("Aisle is fine too")
        onNodeWithTag("todo-comment-add-td-1").performScrollTo().performClick()
        onNodeWithTag("todo-ask-td-1").performScrollTo().performClick()
        onNodeWithTag("todo-due-td-1").performScrollTo().performClick()
        onNodeWithTag("todo-due-td-1-tomorrow").performClick()
        onNodeWithTag("todo-group-td-1").performScrollTo().performClick()
        onNodeWithTag("todo-group-td-1-admin").performClick()
        onNodeWithTag("todo-check-td-1").performScrollTo().performClick()

        onNodeWithTag("todos-show-done").performScrollTo().performClick()
        onNodeWithTag("todo-row-td-4").performScrollTo().assertExists()
        assertEquals(
            listOf("add Renew passport", "regroup", "uncomment td-1 tc-2", "comment td-1 Aisle is fine too", "hand td-1",
                "due td-1 tomorrow", "group td-1 Admin", "done td-1 true"),
            actions.calls,
        )
    }

    @Test
    fun defaultModelForNewChats() = runComposeUiTest {
        val actions = Recorder()
        val models = ModelOptions(ModelChoice("openrouter", "a/m-1"),
            listOf(ModelOptions.Provider("openrouter", "OpenRouter", listOf("a/m-1", "b/m-2"))), default = ModelChoice("openrouter", "b/m-2"))
        val chat = chatView(ChatState(models = models), false, true, status, now)
        val menu = menuView(chat, status, models)
        assertEquals("m-2", menu.defaultModel)
        assertFalse(menu.defaultIsAgents)
        assertTrue(menu.defaultModelGroups.single().models.single { it.selected }.model == "b/m-2")
        assertEquals("m-2", chat.model, "a new chat shows the default")
        assertTrue(menuView(chat, status, models.copy(default = null)).defaultIsAgents)

        setContent { TalariaTheme { Box(Modifier.size(400.dp, 800.dp)) { DefaultModelPicker(menu, actions) } } }
        onNodeWithTag("default-model").performClick()
        onNodeWithTag("default-model-a/m-1").performClick()
        onNodeWithTag("default-model").performClick()
        onNodeWithTag("default-model-agent").performClick()
        assertEquals(listOf("default openrouter a/m-1", "default null null"), actions.calls)
    }
}
