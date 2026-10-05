package io.github.meepdong.talaria.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.meepdong.talaria.chat.ChatState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HomeTilesTest {
    private class Recorder : TalariaActions {
        val calls = mutableListOf<String>()
        override fun pairWithLink(link: String, deviceName: String) {}
        override fun pairWithCode(code: String, address: String, deviceName: String) {}
        override fun cancelPairing() {}
        override fun testConnection() {}
        override fun reconnectNow() {}
        override fun forgetServer() {}
        override fun startArrangingHome() { calls += "arrange" }
        override fun moveHomeTile(tile: HomeTile, up: Boolean) { calls += "move $tile ${if (up) "up" else "down"}" }
        override fun doneArrangingHome() { calls += "done" }
    }

    private val now = 1_791_028_800_000L
    private val status = StatusView(rows = emptyList(), lastConnected = "", deviceName = "Laptop", server = "", keyProtection = "",
        overall = Health.GOOD, summary = "Connected")

    private fun screen(home: HomeView) = Screen.Chat(chatView(ChatState(), false, true, status, now), status, home = home)

    private fun SemanticsNodeInteraction.top() = fetchSemanticsNode().boundsInRoot.top

    @Test
    fun savedOrder() {
        assertEquals(HomeTile.entries, homeOrder(""))
        assertEquals(listOf(HomeTile.RECENT, HomeTile.DAY, HomeTile.NEXT, HomeTile.AUTOMATIONS, HomeTile.TODOS),
            homeOrder("RECENT,WEATHER,DAY,DAY"), "unknown and repeated names are skipped; missing tiles follow")
        val order = HomeTile.entries
        assertEquals(listOf(HomeTile.NEXT, HomeTile.DAY, HomeTile.AUTOMATIONS, HomeTile.TODOS, HomeTile.RECENT),
            order.moved(HomeTile.NEXT, up = true))
        assertEquals(order, order.moved(HomeTile.DAY, up = true), "the first can't go up")
        assertEquals(order, order.moved(HomeTile.RECENT, up = false), "the last can't go down")
    }

    @Test
    fun longPressArrangesAndTilesFollowTheOrder() = runComposeUiTest {
        val actions = Recorder()
        var home by mutableStateOf(HomeView(
            order = listOf(HomeTile.RECENT, HomeTile.TODOS, HomeTile.DAY, HomeTile.NEXT, HomeTile.AUTOMATIONS)))
        setContent { TalariaTheme { Box(Modifier.size(600.dp, 2400.dp)) { HomeScreen(screen(home), actions) } } }
        assertTrue(onNodeWithTag("recent-chats").top() < onNodeWithTag("todos").top())
        assertTrue(onNodeWithTag("todos").top() < onNodeWithTag("your-day").top())
        assertTrue(onNodeWithTag("next-up").top() < onNodeWithTag("automations-on").top())
        onNodeWithTag("arrange-done").assertDoesNotExist()
        onNodeWithTag("move-up-DAY").assertDoesNotExist()

        onNodeWithText("Your day").performTouchInput { longClick() }
        assertEquals(listOf("arrange"), actions.calls)

        home = home.copy(arranging = true)
        onNodeWithTag("move-up-RECENT").assertIsNotEnabled()
        onNodeWithTag("move-down-AUTOMATIONS").assertIsNotEnabled()
        onNodeWithTag("move-up-DAY").assertIsEnabled().performClick()
        onNodeWithTag("move-down-RECENT").performClick()
        onNodeWithTag("arrange-done").performClick()
        assertEquals(listOf("arrange", "move DAY up", "move RECENT down", "done"), actions.calls)
    }

    @Test
    fun needsYouStaysOnTop() = runComposeUiTest {
        val home = HomeView(order = listOf(HomeTile.RECENT, HomeTile.DAY, HomeTile.NEXT, HomeTile.AUTOMATIONS, HomeTile.TODOS),
            day = listOf(DayResult("Tidy downloads", "", "09:30", id = "0000000000cc", blocked = "recursive delete", at = 1)))
        setContent { TalariaTheme { Box(Modifier.size(1200.dp, 2400.dp)) { HomeScreen(screen(home), Recorder()) } } }
        assertTrue(onNodeWithTag("needs-you").top() < onNodeWithTag("recent-chats").top())
        // wide: Next up and Automations on still share a row while they're neighbours
        assertEquals(onNodeWithTag("next-up").top(), onNodeWithTag("automations-on").top())
    }
}
