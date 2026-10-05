package io.github.meepdong.talaria.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.meepdong.talaria.ops.OpsApproval
import io.github.meepdong.talaria.ops.OpsState
import io.github.meepdong.talaria.terminal.TermScreen
import io.github.meepdong.talaria.terminal.TerminalState
import io.github.meepdong.talaria.terminal.TmuxSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class TerminalScreenTest {
    private class Recorder : TalariaActions {
        val calls = mutableListOf<String>()
        override fun pairWithLink(link: String, deviceName: String) {}
        override fun pairWithCode(code: String, address: String, deviceName: String) {}
        override fun cancelPairing() {}
        override fun testConnection() {}
        override fun reconnectNow() {}
        override fun forgetServer() {}
        override fun showChats() { calls += "chats" }
        override fun openTerminal(session: String) { calls += "open $session" }
        override fun closeTerminal() { calls += "close" }
        override fun opsApprove(requestId: String, choice: String) { calls += "approve $requestId $choice" }
        override fun terminalTakeControl() { calls += "control" }
        override fun terminalKey(key: String) { calls += "key $key" }
        override fun terminalText(text: String, enter: Boolean) { calls += "text $text $enter" }
    }

    private val now = 1_790_000_600_000L
    private val claude = TmuxSession("claude", "claude", "/root", 89, 33, 2, 1_790_000_480)

    @Test
    fun colours() {
        val fg = Color.White
        val bg = Color.Black
        val s = ansiText("\u001b[1;31mError\u001b[0m ok\n\u001b[38;5;208mo\u001b[38;2;1;2;3mx", fg, bg)
        assertEquals("Error ok\nox", s.text)
        val err = s.spanStyles.first { s.text.substring(it.start, it.end) == "Error" }.item
        assertEquals(FontWeight.Bold, err.fontWeight)
        assertEquals(Color(0xFFE06C75), err.color)
        assertEquals(fg, s.spanStyles.first { s.text.substring(it.start, it.end) == " ok" }.item.color)
        assertEquals(xterm256(208), s.spanStyles.first { s.text.substring(it.start, it.end) == "o" }.item.color)
        assertEquals(Color(1, 2, 3), s.spanStyles.first { s.text.substring(it.start, it.end) == "x" }.item.color)

        val cursor = ansiText("ab\ncd", fg, bg, cursor = 1 to 1)
        assertEquals("ab\ncd", cursor.text)
        assertEquals(fg, cursor.spanStyles.first { cursor.text.substring(it.start, it.end) == "d" }.item.background)
        val past = ansiText("ab", fg, bg, cursor = 4 to 0)
        assertEquals("ab   ", past.text, "the cursor past the text is padded to")
    }

    @Test
    fun mapping() {
        assertEquals(TerminalItem("claude", "Claude Code · /root · open on 2 screens", "2 min ago"), terminalItem(claude, now))
        val pending = OpsState(pending = listOf(OpsApproval("op-1", "terminal.watch", """{"session":"claude"}""", 1,
            "Watch the tmux session claude on this device for up to 30 minutes", "device:ME", 0)))
        val asking = terminalView(TerminalState(session = "claude", requestId = "op-1"), pending, now)
        assertEquals("op-1", asking.approval?.requestId)
        assertNull(asking.text)
        val screen = TermScreen("claude", 89, 33, 2, 1, "claude", true, "hi")
        val typing = terminalView(TerminalState(session = "claude", grant = "tg-1", screen = screen), OpsState(), now)
        assertEquals("Typing · claude · 89×33", typing.status)
        assertEquals(2 to 1, typing.cursor)
        assertNull(terminalView(TerminalState(session = "claude", grant = "tg-1", screen = screen.copy(control = false)),
            OpsState(), now).cursor, "no cursor while only watching")
    }

    @Test
    fun listApproveWatchAndType() = runComposeUiTest {
        val actions = Recorder()
        var view by mutableStateOf(TerminalView(sessions = listOf(terminalItem(claude, now))))
        setContent { TalariaTheme { Box(Modifier.size(420.dp, 900.dp)) { TerminalScreen(view, actions) } } }
        onNodeWithTag("terminal-row-claude").performClick()
        onNodeWithTag("terminal-back").performClick()
        assertEquals(listOf("open claude", "chats"), actions.calls)

        view = TerminalView(open = "claude", approval = TerminalApproval("op-1", "Watch the tmux session claude", control = false))
        onNodeWithTag("terminal-allow").performClick()
        assertEquals("approve op-1 once", actions.calls.last())

        view = TerminalView(open = "claude", text = "\u001b[1mClaude Code\u001b[0m\n> Proceed?", cols = 89, status = "Watching · claude · 89×33")
        onNodeWithText("Claude Code", substring = true).assertExists()
        onNodeWithTag("terminal-key-⏎").assertDoesNotExist()
        onNodeWithTag("terminal-take-control").performClick()
        assertEquals("control", actions.calls.last())

        view = view.copy(approval = TerminalApproval("op-2", "Type in the tmux session claude", control = true))
        onNodeWithText("Typing here acts as root on the server.").assertExists()
        onNodeWithTag("terminal-deny").performClick()
        assertEquals("approve op-2 deny", actions.calls.last())

        view = view.copy(approval = null, control = true, cursor = 2 to 1)
        onNodeWithTag("terminal-key-1").performScrollTo().performClick()
        onNodeWithTag("terminal-key-Ctrl-C").performScrollTo().performClick()
        onNodeWithTag("terminal-input").performTextInput("yes please")
        onNodeWithTag("terminal-send").performClick()
        assertEquals(listOf("text 1 false", "key C-c", "text yes please true"), actions.calls.takeLast(3))
        onNodeWithTag("terminal-back").performClick()
        assertEquals("close", actions.calls.last())

        view = view.copy(control = false, closed = "the session claude has ended")
        onNodeWithTag("terminal-closed").assertExists()
        onNodeWithTag("terminal-reopen").performClick()
        assertEquals("open claude", actions.calls.last())
    }
}
