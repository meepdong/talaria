package io.github.meepdong.talaria.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        override fun terminalKey(key: String) { calls += "key $key" }
        override fun terminalText(text: String, enter: Boolean) { calls += "text $text $enter" }
        override fun newTerminal(name: String, folder: String, command: String) { calls += "new $name $folder [$command]" }
        override fun endTerminal(session: String) { calls += "end $session" }
        override fun terminalHistory() { calls += "history" }
        override fun unlockTerminal() { calls += "unlock" }
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
        assertEquals(xterm256(208), s.spanStyles.first { s.text.substring(it.start, it.end) == "o" }.item.color)
        assertEquals(Color(1, 2, 3), s.spanStyles.first { s.text.substring(it.start, it.end) == "x" }.item.color)
        val past = ansiText("ab", fg, bg, cursor = 4 to 0)
        assertEquals("ab   ", past.text, "the cursor past the text is padded to")
    }

    @Test
    fun typedTextBecomesTerminalKeys() {
        assertEquals(listOf("ls -la" to false, "Enter" to true), typedKeys("ls -la\n", ctrl = false, alt = false))
        assertEquals(listOf("C-c" to true), typedKeys("c", ctrl = true, alt = false))
        assertEquals(listOf("C-M-x" to true, "yz" to false), typedKeys("Xyz", ctrl = true, alt = true))
        assertEquals(listOf("é" to false), typedKeys("é", ctrl = true, alt = false), "Ctrl only joins letters and digits")
    }

    @Test
    fun theLockOpensAfterFiveMinutesAway() {
        var t = 0L
        val lock = TerminalLock { t }
        assertFalse(lock.unlocked)
        lock.unlock()
        assertTrue(lock.unlocked)
        lock.foreground(false)
        t += TerminalLock.GRACE_MS - 1000
        lock.foreground(true)
        assertTrue(lock.unlocked, "back within 5 minutes: still unlocked")
        lock.foreground(false)
        t += TerminalLock.GRACE_MS
        assertFalse(lock.unlocked, "away 5 minutes: locked, even before coming back")
        lock.foreground(true)
        assertFalse(lock.unlocked)
        lock.unlock()
        t += 60 * 60 * 1000L
        assertTrue(lock.unlocked, "while the app stays open it stays unlocked")
    }

    @Test
    fun mapping() {
        assertEquals(TerminalItem("claude", "Claude Code · /root · open on 2 screens", "2 min ago"), terminalItem(claude, now))
        val pending = OpsState(pending = listOf(OpsApproval("op-1", "terminal.control", """{"session":"claude"}""", 2,
            "Type in the tmux session claude", "device:ME", 0)))
        assertEquals("op-1", terminalView(TerminalState(session = "claude", requestId = "op-1"), pending, now).approval?.requestId)
        val screen = TermScreen("claude", 89, 33, 2, 1, "claude", true, "hi", alternate = true)
        val typing = terminalView(TerminalState(session = "claude", grant = "tg-1", screen = screen), OpsState(), now, locked = true)
        assertEquals("Typing · claude · 89×33", typing.status)
        assertTrue(typing.alternate && typing.locked)
        assertFalse(terminalView(TerminalState(session = "claude", screen = screen), OpsState(), now, locked = true).locked,
            "nothing open, nothing to lock")
        assertNull(terminalView(TerminalState(session = "claude", grant = "tg-1", screen = screen.copy(control = false)),
            OpsState(), now).cursor, "no cursor while only watching")
    }

    @Test
    fun listNewSessionAndEnding() = runComposeUiTest {
        val actions = Recorder()
        setContent { TalariaTheme { Box(Modifier.size(420.dp, 900.dp)) {
            TerminalScreen(TerminalView(sessions = listOf(terminalItem(claude, now))), actions)
        } } }
        onNodeWithTag("terminal-row-claude").performClick()
        assertEquals("open claude", actions.calls.last())

        onNodeWithTag("terminal-row-claude").performTouchInput { longClick() }
        onNodeWithTag("terminal-end-confirm").performClick()
        assertEquals("end claude", actions.calls.last())

        onNodeWithTag("terminal-new").performClick()
        onNodeWithTag("new-name").performTextClearance()
        onNodeWithTag("new-name").performTextInput("claude")  // taken: Create stays off
        onNodeWithTag("new-create").performClick()
        assertEquals("end claude", actions.calls.last())
        onNodeWithTag("new-name").performTextClearance()
        onNodeWithTag("new-name").performTextInput("build")
        onNodeWithTag("new-folder").performTextClearance()
        onNodeWithTag("new-folder").performTextInput("/srv/my app")  // any folder
        onNodeWithTag("command-preset-Claude Code").performClick()
        onNodeWithTag("new-create").performClick()
        assertEquals("new build /srv/my app [claude]", actions.calls.last())
    }

    @Test
    fun fullScreenTypingKeysAndLock() = runComposeUiTest {
        val actions = Recorder()
        var view by mutableStateOf(TerminalView(open = "claude", approval = TerminalApproval("op-1", "Type in the tmux session claude", control = true)))
        setContent { TalariaTheme { Box(Modifier.size(420.dp, 900.dp)) { TerminalScreen(view, actions) } } }
        onNodeWithTag("terminal-allow").performClick()
        assertEquals("approve op-1 once", actions.calls.last())

        view = TerminalView(open = "claude", text = "\u001b[1mClaude Code\u001b[0m\n> Proceed?", cols = 89, control = true,
            cursor = 2 to 1, status = "Typing · claude · 89×33")
        onNodeWithTag("terminal-full").assertExists()
        onNodeWithText("Claude Code", substring = true).assertExists()
        onNodeWithTag("terminal-input").performTextInput("ls")
        onNodeWithTag("terminal-key-Ctrl").performClick()
        onNodeWithTag("terminal-input").performTextInput("c")
        onNodeWithTag("terminal-key-Esc").performClick()
        onNodeWithTag("terminal-key-Alt").performClick()
        onNodeWithTag("terminal-key-↑").performScrollTo().performClick()
        assertEquals(listOf("text ls false", "key C-c", "key Escape", "key M-Up"), actions.calls.takeLast(4))
        onNodeWithTag("terminal-key-more").performScrollTo().performClick()
        onNodeWithTag("terminal-key-F5").performClick()
        assertEquals("key F5", actions.calls.last())

        view = view.copy(locked = true)
        onNodeWithTag("terminal-lock").assertExists()
        onNodeWithTag("terminal-key-Esc").assertDoesNotExist()
        onNodeWithTag("terminal-unlock").performClick()
        assertEquals("unlock", actions.calls.last())

        view = view.copy(locked = false, control = true, closed = "the session claude has ended")
        onNodeWithTag("terminal-reopen").performClick()
        assertEquals("open claude", actions.calls.last())
        onNodeWithTag("terminal-close").performClick()
        assertEquals("close", actions.calls.last())
    }
}
