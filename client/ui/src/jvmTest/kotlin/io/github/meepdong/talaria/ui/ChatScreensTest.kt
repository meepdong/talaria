package io.github.meepdong.talaria.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.meepdong.talaria.chat.ChatMessage
import io.github.meepdong.talaria.chat.ChatState
import io.github.meepdong.talaria.chat.ConversationSummary
import io.github.meepdong.talaria.chat.ConversationThread
import io.github.meepdong.talaria.chat.MessageState
import io.github.meepdong.talaria.chat.Role
import io.github.meepdong.talaria.chat.ToolStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class ChatScreensTest {
    private class Recorder : TalariaActions {
        val calls = mutableListOf<String>()
        override fun pairWithLink(link: String, deviceName: String) {}
        override fun pairWithCode(code: String, address: String, deviceName: String) {}
        override fun cancelPairing() {}
        override fun testConnection() {}
        override fun reconnectNow() {}
        override fun forgetServer() {}
        override fun openConversation(id: String) { calls += "open $id" }
        override fun newConversation() { calls += "new" }
        override fun closeConversation() { calls += "close" }
        override fun sendMessage(text: String) { calls += "send $text" }
        override fun retryMessage(key: String) { calls += "retry $key" }
        override fun stopReply(turnId: String) { calls += "stop $turnId" }
        override fun showStatus() { calls += "status" }
    }

    private val status = StatusView(
        rows = emptyList(), lastConnected = "just now", deviceName = "Laptop", server = "wss://vps",
        keyProtection = "DPAPI", overall = Health.GOOD, summary = "Connected",
    )

    private fun state(streaming: Boolean = false, unsent: Boolean = false) = ChatState(
        conversations = listOf(
            ConversationSummary("c-1", "hermes", "Trip plans", 1, 1_700_000_000, Role.ASSISTANT, "Goa it is", if (streaming) "t-2" else null),
            ConversationSummary("c-2", "hermes", "Groceries", 1, 1_600_000_000, Role.USER, "milk"),
        ),
        listLoaded = true,
        openId = "c-1",
        threads = mapOf("c-1" to ConversationThread(
            messages = listOfNotNull(
                ChatMessage("h:1", Role.USER, "Plan a trip", 1_700_000_000_000),
                ChatMessage("h:2", Role.ASSISTANT, "**Goa** it is", 1_700_000_001_000, toolNames = listOf("web_search")),
                if (unsent) ChatMessage("local:m-1", Role.USER, "And hotels?", null, MessageState.NOT_SENT, error = "Not connected to the bridge") else null,
                if (streaming) ChatMessage("reply:t-2", Role.ASSISTANT, "Look", null, MessageState.STREAMING, turnId = "t-2",
                    tools = listOf(ToolStep("web_search", "started"))) else null,
            ),
            nextBefore = "50", loaded = true)),
    )

    private fun view(s: ChatState, open: Boolean = true, connected: Boolean = true) = chatView(s, open, connected, status, 1_700_000_100_000)

    @Test
    fun mapping() {
        val v = view(state(streaming = true))
        assertEquals(listOf("Trip plans", "Groceries"), v.conversations.map { it.title })
        assertEquals("You: milk", v.conversations[1].preview)
        assertEquals(true, v.conversations[0].running)
        assertEquals("t-2", v.runningTurnId)
        assertEquals(false, v.canSend)
        assertEquals(listOf(ToolChip("web_search", "completed")), v.messages[1].tools)
        assertEquals(true, v.hasOlder)
        assertNull(v.listMessage)
        assertEquals("No conversations yet. Start one with New chat.", view(ChatState(listLoaded = true)).listMessage)
        assertEquals("Chat is off on the bridge: none", view(ChatState(unavailable = "none")).listMessage)
    }

    @Test
    fun phoneListThenConversation() = runComposeUiTest {
        val actions = Recorder()
        var v by mutableStateOf(view(state(), open = false))
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.size(400.dp, 800.dp)) { ChatHome(v, actions) }
        }
        onNodeWithTag("conversation-c-2").performClick()
        onNodeWithTag("new-chat").performClick()
        onNodeWithTag("messages").assertDoesNotExist()

        v = view(state(unsent = true), open = true)
        onNodeWithTag("title").assertTextContains("Trip plans")
        onNodeWithText("Goa", substring = true).assertExists()
        onNodeWithTag("retry").performClick()
        onNodeWithTag("composer").performTextInput("Thanks")
        onNodeWithTag("send").performClick()
        onNodeWithTag("back").performClick()
        assertEquals(listOf("open c-2", "new", "retry local:m-1", "send Thanks", "close"), actions.calls)
    }

    @Test
    fun wideShowsBothPanesAndStop() = runComposeUiTest {
        val actions = Recorder()
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.size(1000.dp, 700.dp)) { ChatHome(view(state(streaming = true)), actions) }
        }
        onNodeWithTag("conversations").assertExists()
        onNodeWithTag("messages").assertExists()
        onNodeWithTag("back").assertDoesNotExist()
        onNodeWithText("🔧 web_search …").assertExists()
        onNodeWithTag("stop").performClick()
        onNodeWithTag("connection").performClick()
        assertEquals(listOf("stop t-2", "status"), actions.calls)
    }

    @Test
    fun offlineComposer() = runComposeUiTest {
        val actions = Recorder()
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.size(400.dp, 800.dp)) { ChatHome(view(state(), connected = false), actions) }
        }
        onNodeWithTag("composer-hint").assertTextContains("Not connected", substring = true)
        onNodeWithTag("composer").performTextInput("hi")
        onNodeWithTag("send").assertIsNotEnabled()
    }

    @Test
    fun markdown() {
        val blocks = parseMarkdown("# Plan\n\nDay **one**: beach\n- swim\n  - snorkel\n1. pack\n```\ncode()\n```\ntail")
        assertEquals(listOf(
            MdBlock.Heading(1, "Plan"),
            MdBlock.Paragraph("Day **one**: beach"),
            MdBlock.Bullet("•", "swim", 0),
            MdBlock.Bullet("•", "snorkel", 1),
            MdBlock.Bullet("1.", "pack", 0),
            MdBlock.Code("code()"),
            MdBlock.Paragraph("tail"),
        ), blocks)
        val inline = inlineMarkdown("a **b** `c` *d* 2*3*4 snake_case_name", androidx.compose.ui.graphics.Color.Gray)
        assertEquals("a b c d 2*3*4 snake_case_name", inline.text)
        assertEquals(3, inline.spanStyles.size)
    }
}
