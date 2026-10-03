package io.github.meepdong.talaria.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.meepdong.talaria.chat.ChatState
import io.github.meepdong.talaria.chat.ConversationSummary
import io.github.meepdong.talaria.chat.Role
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MainScreenTest {
    private class Recorder : TalariaActions {
        val calls = mutableListOf<String>()
        override fun pairWithLink(link: String, deviceName: String) {}
        override fun pairWithCode(code: String, address: String, deviceName: String) {}
        override fun cancelPairing() {}
        override fun testConnection() {}
        override fun reconnectNow() {}
        override fun forgetServer() {}
        override fun openConversation(id: String) { calls += "open $id" }
        override fun selectTab(tab: Tab) { calls += "tab $tab" }
        override fun setMenuOpen(open: Boolean) { calls += "menu $open" }
        override fun startChat() { calls += "chat" }
        override fun talk() { calls += "talk" }
        override fun showStatus() { calls += "status" }
    }

    private val status = StatusView(
        rows = listOf(StatusRow("Bridge", Health.GOOD, "Connected"), StatusRow("Hermes", Health.GOOD, "Ready")),
        lastConnected = "just now", deviceName = "Laptop", server = "wss://vps",
        keyProtection = "DPAPI", overall = Health.GOOD, summary = "Connected",
        balances = listOf(BalanceItem("OpenRouter", "$12.40", null, "https://openrouter.ai/settings/credits")),
    )

    private fun screen(tab: Tab = Tab.HOME, menuOpen: Boolean = false): Screen.Chat {
        val state = ChatState(
            conversations = listOf(
                ConversationSummary("c-1", "hermes", "Trip plans", 1, 1_700_000_000, Role.ASSISTANT, "Goa it is", "t-2"),
                ConversationSummary("c-2", "hermes", "Groceries", 1, 1_600_000_000, Role.USER, "milk"),
            ),
            listLoaded = true,
        )
        val view = chatView(state, false, true, status, 1_700_000_100_000)
        return Screen.Chat(view, status, tab = tab, tabs = TalariaController.TABS,
            home = homeView(view, 1_700_000_100_000), menu = menuView(view, status), menuOpen = menuOpen)
    }

    @Test
    fun homeAndMenuMapping() {
        val s = screen()
        assertEquals(listOf("Trip plans", "Groceries"), s.home.recent.map { it.title })
        assertEquals(listOf(RunningItem("Trip plans", "Hermes is replying", "c-1")), s.menu.running)
        assertEquals("$12.40", s.menu.balances.single().amount)
        assertEquals(2, s.menu.connection.size)
    }

    @Test
    fun homeOnAPhone() = runComposeUiTest {
        val actions = Recorder()
        setContent { TalariaTheme { Box(Modifier.size(400.dp, 800.dp)) { MainScreen(screen(), actions) } } }
        onNodeWithTag("home").assertExists()
        onNodeWithTag("bottom-tabs").assertExists()
        onNodeWithText("Trip plans").assertExists()
        onNodeWithTag("recent-c-2").performScrollTo().performClick()
        onNodeWithTag("start-chat").performClick()
        onNodeWithTag("talk").performClick()
        onNodeWithTag("tab-chats").performClick()
        onNodeWithTag("menu").performClick()
        assertEquals(listOf("open c-2", "chat", "talk", "tab CHATS", "menu true"), actions.calls)
    }

    @Test
    fun menuShowsRunningBalanceAndConnection() = runComposeUiTest {
        val actions = Recorder()
        setContent { TalariaTheme { Box(Modifier.size(1100.dp, 800.dp)) { MainScreen(screen(menuOpen = true), actions) } } }
        onNodeWithTag("side-bar").assertExists()
        onNodeWithTag("menu-panel").assertExists()
        onNodeWithTag("menu-balance").assertTextContains("$12.40")
        onNodeWithTag("menu-top-up").assertExists()
        onNodeWithTag("running-item").performClick()
        onNodeWithTag("connection-details").performClick()
        onNodeWithTag("close-menu").performClick()
        assertEquals(listOf("open c-1", "status", "menu false"), actions.calls)
    }

    @Test
    fun chatsTabKeepsTheChatScreens() = runComposeUiTest {
        val actions = Recorder()
        setContent { TalariaTheme { Box(Modifier.size(1100.dp, 800.dp)) { MainScreen(screen(tab = Tab.CHATS), actions) } } }
        onNodeWithTag("conversations").assertExists()
        onNodeWithTag("menu").performClick()
        assertEquals(listOf("menu true"), actions.calls)
    }
}
