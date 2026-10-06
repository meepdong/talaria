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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.meepdong.talaria.chat.Attachment
import io.github.meepdong.talaria.chat.ChatMessage
import io.github.meepdong.talaria.chat.ChatState
import io.github.meepdong.talaria.chat.ConversationSummary
import io.github.meepdong.talaria.chat.ConversationThread
import io.github.meepdong.talaria.chat.MessageState
import io.github.meepdong.talaria.chat.OutgoingFile
import io.github.meepdong.talaria.chat.PendingApproval
import io.github.meepdong.talaria.chat.Role
import io.github.meepdong.talaria.chat.ToolStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
        override fun openBot(id: String) { calls += "bot $id" }
        override fun openAttachment(root: String, path: String, name: String, mime: String) { calls += "file $root/$path $mime" }
        override fun newConversation() { calls += "new" }
        override fun closeConversation() { calls += "close" }
        override fun sendMessage(text: String) { calls += "send $text" }
        override fun retryMessage(key: String) { calls += "retry $key" }
        override fun stopReply(turnId: String) { calls += "stop $turnId" }
        override fun approve(turnId: String, choice: String) { calls += "approve $turnId $choice" }
        override fun showStatus() { calls += "status" }
        override fun attachFiles(photos: Boolean) { calls += "attach $photos" }
        override fun removeAttachment(index: Int) { calls += "remove $index" }
        override fun toggleDictation() { calls += "dictate" }
        override fun talk() { calls += "talk" }
        override fun dictationTaken(id: Long) { calls += "taken $id" }
        override fun speak(key: String, text: String) { calls += "speak $key" }
        override fun stopSpeaking() { calls += "stop speaking" }
        override fun setReadAloud(on: Boolean) { calls += "read aloud $on" }
        override fun loadTalkVoices() { calls += "voices" }
        override fun previewTalkVoice(id: String) { calls += "hear $id" }
        override fun setTalkVoice(id: String) { calls += "voice $id" }
        override fun pickModel(provider: String, model: String) { calls += "model $provider $model" }
        override fun openModelPicker(query: String) { calls += "picker $query" }
        override fun dismissAside(id: String) { calls += "dismiss $id" }
        override fun closeStatus() { calls += "close status" }
        override fun renameConversation(id: String, title: String) { calls += "rename $id $title" }
        override fun pinConversation(id: String, pinned: Boolean) { calls += "pin $id $pinned" }
        override fun deleteConversation(id: String) { calls += "delete $id" }
        override fun deleteMessages(keys: List<String>) { calls += "delete messages $keys" }
        override fun moveMessages(keys: List<String>, to: String?) { calls += "move $keys $to" }
        override fun shareText(text: String) { calls += "share $text" }
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
        assertEquals(true, v.canSend, "a message sent now waits for the reply")
        assertEquals(listOf(ToolChip("web_search", "completed")), v.messages[1].tools)
        assertEquals(true, v.hasOlder)
        assertNull(v.listMessage)
        assertEquals("No conversations yet. Start one with New chat.", view(ChatState(listLoaded = true)).listMessage)
        assertEquals("Chat is off on the bridge: none", view(ChatState(unavailable = "none")).listMessage)
    }

    private fun withBots(s: ChatState, openBot: Boolean) = s.copy(
        bots = listOf(io.github.meepdong.talaria.chat.Bot("bot:scout", "Scout", "scout", "Finds things"),
            io.github.meepdong.talaria.chat.Bot("bot:code-helper", "Code Helper", "code-helper")),
        conversations = s.conversations + ConversationSummary("c-bot", "bot:scout", "Scout", 1, 1_650_000_000, Role.ASSISTANT, "Found it"),
        openId = if (openBot) "c-bot" else s.openId,
    )

    @Test
    fun botsAndTheirChats() {
        val v = view(withBots(state(), openBot = true))
        assertEquals(listOf("scout", "code-helper"), v.bots.map { it.handle })
        assertEquals("CH", v.bots[1].initials)
        assertEquals("bot:scout", v.openBot?.id)
        assertEquals(listOf(false, false, true), v.conversations.map { it.bot })
        assertNull(v.model, "a bot's model is its own: no model chip")
        assertEquals(false, chatView(withBots(state(), openBot = true), true, true, status, 1_700_000_100_000, canAttach = true).canAttach)
        assertEquals(true, chatView(withBots(state(), openBot = false), true, true, status, 1_700_000_100_000, canAttach = true).canAttach)
        assertNull(view(withBots(state(), openBot = false)).openBot)
    }

    @Test
    fun mentions() {
        val bots = listOf(BotItem("bot:scout", "Scout", "scout"), BotItem("bot:code-helper", "Code Helper", "code-helper"))
        assertEquals("bot:scout" to "find a cafe", parseMention("@Scout find a cafe", bots)?.let { it.first.id to it.second })
        assertEquals("bot:code-helper" to "fix it", parseMention("@codehelper, fix it", bots)?.let { it.first.id to it.second })
        assertEquals("bot:scout" to "", parseMention("@scout", bots)?.let { it.first.id to it.second })
        assertNull(parseMention("@nobody hi", bots))
        assertNull(parseMention("email me@scout.com", bots))
        assertNull(parseMention("@ scout", bots))
        assertEquals(listOf("scout"), mentionSuggestions("@s", bots).map { it.handle })
        assertEquals(listOf("scout", "code-helper"), mentionSuggestions("@", bots).map { it.handle })
        assertTrue(mentionSuggestions("@scout hi", bots).isEmpty() && mentionSuggestions("hi", bots).isEmpty())
    }

    @Test
    fun botStripAndMentionsOnScreen() = runComposeUiTest {
        val actions = Recorder()
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.size(1200.dp, 800.dp)) {
                ChatHome(view(withBots(state(), openBot = true)), actions)
            }
        }
        onNodeWithTag("title").assertTextContains("🤖 Scout")
        onNodeWithTag("bot-code-helper").performClick()
        assertEquals("bot bot:code-helper", actions.calls.last())
        onNodeWithTag("composer").performTextInput("@sc")
        onNodeWithTag("mention-scout").performClick()
        onNodeWithTag("composer").assertTextContains("@scout ")
        onNodeWithTag("composer").performTextInput("find a cafe")
        onNodeWithTag("send").performClick()
        assertEquals("send @scout find a cafe", actions.calls.last())
    }

    @Test
    fun noBotsNoStrip() = runComposeUiTest {
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.size(1200.dp, 800.dp)) { ChatHome(view(state()), Recorder()) }
        }
        onNodeWithTag("bots").assertDoesNotExist()
        onNodeWithTag("composer").performTextInput("@sc")
        onNodeWithTag("mentions").assertDoesNotExist()
    }

    private fun jobState(reply: MessageState, approval: Boolean = false) = state().copy(threads = mapOf("c-1" to ConversationThread(
        messages = listOf(
            ChatMessage("t:1", Role.USER, "🎙 Read me the invoice", 1_700_000_000_000),
            ChatMessage("user:t-5", Role.USER, "Read the invoice and tell me the total.", 1_700_000_001_000, turnId = "t-5", worker = "Hermes"),
            ChatMessage("reply:t-5", Role.ASSISTANT, if (reply == MessageState.DONE) "Total 42,300 rupees, due 15 Oct." else "", null, reply,
                turnId = "t-5", worker = "Hermes", waitingForApproval = approval,
                approval = if (approval) PendingApproval(listOf("once", "deny"), "pdftotext invoice.pdf", null) else null),
            ChatMessage("t:2", Role.ASSISTANT, "It's forty-two thousand three hundred, due the fifteenth.", 1_700_000_002_000),
        ), loaded = true)))

    @Test
    fun aJobIsOneCardBetweenTheSpokenMessages() {
        val v = view(jobState(MessageState.DONE))
        assertEquals(listOf("t:1", "user:t-5", "t:2"), v.messages.map { it.key })
        val job = v.messages[1]
        assertEquals("Hermes", job.worker)
        assertEquals("Total 42,300 rupees, due 15 Oct.", job.report?.text)
        // a report whose order is on an older page still shows as a card
        val alone = foldJobs(listOf(MessageItem("r", false, "done", null, ItemState.DONE, worker = "Hermes")))
        assertEquals(listOf("r"), alone.map { it.key })
        assertNull(alone.single().report)
    }

    @Test
    fun theJobCardOpensAndShowsApprovalsWhileFolded() = runComposeUiTest {
        val actions = Recorder()
        var s by mutableStateOf(jobState(MessageState.STREAMING, approval = true))
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.size(1200.dp, 800.dp)) { ChatHome(view(s), actions) }
        }
        onNode(hasText("🔧 Hermes · needs you") and hasAnyAncestor(hasTestTag("job-user:t-5"))).assertExists()
        onNodeWithText("Allow once").performClick()
        assertEquals("approve t-5 once", actions.calls.last())
        s = jobState(MessageState.DONE)
        waitForIdle()
        onNode(hasText("🔧 Hermes · done") and hasAnyAncestor(hasTestTag("job-user:t-5"))).assertExists()
        onNodeWithTag("job-report", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("job-user:t-5").performClick()
        onNodeWithTag("job-brief", useUnmergedTree = true).assertTextContains("Read the invoice and tell me the total.")
        onNodeWithTag("job-report", useUnmergedTree = true).assertExists()
        onNodeWithText("It's forty-two thousand three hundred, due the fifteenth.").assertExists()
    }

    @Test
    fun aBotsRepliesCarryItsName() = runComposeUiTest {
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.size(1200.dp, 800.dp)) {
                ChatHome(view(withBots(state(), openBot = true).let { st ->
                    st.copy(threads = st.threads + ("c-bot" to ConversationThread(messages = listOf(
                        ChatMessage("h:9", Role.ASSISTANT, "Found three cafes", 1_700_000_000_000)), loaded = true)))
                }), Recorder())
            }
        }
        onNode(hasText("Scout") and hasAnyAncestor(hasTestTag("reply-h:9"))).assertExists()
    }

    @Test
    fun pinnedChatsComeFirst() {
        val s = state()
        val pinned = s.copy(conversations = s.conversations.map { if (it.id == "c-2") it.copy(pinned = true) else it })
        assertEquals(listOf("Groceries" to true, "Trip plans" to false), view(pinned).conversations.map { it.title to it.pinned })
    }

    @Test
    fun longPressAMessageOrAChat() = runComposeUiTest {
        val actions = Recorder()
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.size(1200.dp, 800.dp)) {
                ChatHome(view(state()).copy(canShare = true), actions)
            }
        }
        onNodeWithTag("hold-h:2").performTouchInput { longClick() }
        onNodeWithTag("copy").assertExists()
        onNodeWithTag("share").performClick()
        onNodeWithTag("hold-h:2").performTouchInput { longClick() }
        onNodeWithTag("select").performClick()
        onNodeWithTag("select-text").assertExists()
        onNodeWithText("Done").performClick()
        onNodeWithTag("hold-h:1").performTouchInput { longClick() }
        onNodeWithTag("delete-message").performClick()
        onNodeWithTag("confirm-delete-message").performClick()
        onNodeWithTag("hold-h:1").performTouchInput { longClick() }
        onNodeWithTag("move").performClick()
        onNodeWithTag("move-to-c-2").performClick()

        onNodeWithTag("conversation-c-2").performTouchInput { longClick() }
        onNodeWithTag("chat-pin").performClick()
        onNodeWithTag("conversation-c-2").performTouchInput { longClick() }
        onNodeWithTag("chat-rename").performClick()
        onNodeWithTag("rename-field").performTextReplacement("Food")
        onNodeWithText("Save").performClick()
        assertEquals(listOf("share **Goa** it is", "delete messages [h:1]", "move [h:1] c-2", "pin c-2 true", "rename c-2 Food"),
            actions.calls)
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
    fun aFileFromHermesOpens() = runComposeUiTest {
        val actions = Recorder()
        val file = io.github.meepdong.talaria.chat.Attachment(io.github.meepdong.talaria.chat.Attachment.Kind.FILE,
            "photos_signed.pdf", "application/pdf", 3_080_503, root = "workspace", path = "photos_signed.pdf")
        val s = state().let { st ->
            st.copy(threads = st.threads + ("c-1" to st.threads.getValue("c-1").let { t ->
                t.copy(messages = t.messages + ChatMessage("h:f-1", Role.ASSISTANT, "Signed PDF", 1_700_000_002_000, attachments = listOf(file)))
            }))
        }
        setContent { androidx.compose.foundation.layout.Box(Modifier.size(1200.dp, 800.dp)) { ChatHome(view(s), actions) } }
        onNodeWithText("📄 photos_signed.pdf", substring = true).assertExists()
        onNodeWithTag("open-file").performClick()
        assertEquals("file workspace/photos_signed.pdf application/pdf", actions.calls.last())
    }

    @Test
    fun bigTransfersSayHowFarTheyGot() = runComposeUiTest {
        val actions = Recorder()
        val file = io.github.meepdong.talaria.chat.Attachment(io.github.meepdong.talaria.chat.Attachment.Kind.FILE,
            "clip.mp4", "video/mp4", 1_500_000_000, root = "workspace", path = "clip.mp4")
        val s = state().let { st ->
            st.copy(threads = st.threads + ("c-1" to st.threads.getValue("c-1").let { t ->
                t.copy(messages = t.messages + listOf(
                    ChatMessage("h:f-2", Role.ASSISTANT, "Your video", 1_700_000_002_000, attachments = listOf(file)),
                    ChatMessage("local:m-9", Role.USER, "Here's another", null, MessageState.SENDING, clientMsgId = "m-9", progress = 0.4f)))
            }))
        }
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.size(1200.dp, 800.dp)) {
                ChatHome(view(s).copy(openingFile = "clip.mp4", openingProgress = 0.4f), actions)
            }
        }
        onNodeWithText("Uploading 40%").assertExists()
        onNodeWithText("Opening 40%").assertExists()
        onNodeWithTag("open-file").performClick()
        assertTrue(actions.calls.none { it.startsWith("file ") }, "no second download while one runs")
    }

    @Test
    fun approvalCard() = runComposeUiTest {
        val actions = Recorder()
        val asking = ChatMessage("reply:t-2", Role.ASSISTANT, "", null, MessageState.STREAMING, turnId = "t-2",
            waitingForApproval = true,
            approval = PendingApproval(listOf("once", "session", "always", "deny", "odd"), "rm -rf build", "recursive delete"))
        val s = state().let { st ->
            st.copy(threads = st.threads.mapValues { (_, t) -> t.copy(messages = t.messages + asking) })
        }
        val v = view(s)
        assertEquals(listOf("once", "session", "always", "deny"), v.messages.last().approval!!.choices.map { it.first },
            "choices the app doesn't know are left out")
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.size(400.dp, 800.dp)) { ChatHome(v, actions) }
        }
        onNodeWithTag("approval-command").assertTextContains("rm -rf build")
        onNodeWithText("recursive delete").assertExists()
        onNodeWithTag("approve-session").performClick()
        onNodeWithTag("approve-deny").performClick()
        assertEquals(listOf("approve t-2 session", "approve t-2 deny"), actions.calls)
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

    @Test
    fun attachments() = runComposeUiTest {
        val actions = Recorder()
        val sent = ChatMessage("h:9", Role.USER, "", 1_700_000_000_000, attachments = listOf(
            Attachment(Attachment.Kind.IMAGE, "Photo", "image/jpeg"),
            Attachment(Attachment.Kind.FILE, "report.pdf", "application/pdf", 12 * 1024)))
        val s = state().let { it.copy(threads = it.threads.mapValues { (_, t) -> t.copy(messages = t.messages + sent) }) }
        val pending = listOf(OutgoingFile("notes.txt", "text/plain", ByteArray(10)))
        val v = chatView(s, true, true, status, 1_700_000_100_000, pending, canAttach = true)
        assertEquals(listOf(AttachmentChip("Photo", true, null), AttachmentChip("report.pdf", false, "12 KB")), v.messages.last().attachments)
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.size(400.dp, 800.dp)) { ChatHome(v, actions) }
        }
        onNodeWithText("📎 report.pdf · 12 KB").assertExists()
        onNodeWithText("📎 notes.txt · 10 B").assertExists()
        onNodeWithTag("remove-0").performClick()
        onNodeWithTag("attach").performClick()
        onNodeWithTag("attach-file").performClick()
        onNodeWithTag("send").performClick() // a file alone can be sent without text
        assertEquals(listOf("remove 0", "attach false", "send "), actions.calls)
    }

    @Test
    fun theMicTalksAndALongPressDictates() = runComposeUiTest {
        val actions = Recorder()
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.size(400.dp, 800.dp)) {
                ChatHome(view(state()).copy(voice = VoiceView(canDictate = true)), actions)
            }
        }
        onNodeWithTag("dictate").performClick()
        onNodeWithTag("dictate").performTouchInput { longClick() }
        assertEquals(listOf("talk", "dictate"), actions.calls)
    }

    @Test
    fun theTalkVoiceIsPickedAfterHearingIt() = runComposeUiTest {
        val actions = Recorder()
        var v by mutableStateOf(view(state()).copy(voice = VoiceView(canDictate = true)))
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.size(400.dp, 800.dp)) { ChatHome(v, actions) }
        }
        onNodeWithTag("menu").performClick()
        onNodeWithTag("talk-voice").performClick()
        onNodeWithTag("voices-loading").assertExists()
        v = v.copy(voice = v.voice.copy(talkVoices = listOf("shimmer" to "Shimmer: bright and warm", "marin" to "Marin: natural, relaxed"),
            talkVoice = "shimmer"))
        waitForIdle()
        onNodeWithTag("hear-marin").performClick()
        onNodeWithTag("voice-marin").performClick()
        assertEquals(listOf("voices", "hear marin", "voice marin"), actions.calls)
    }

    @Test
    fun voice() = runComposeUiTest {
        val actions = Recorder()
        val base = view(state())
        var v by mutableStateOf(base.copy(voice = VoiceView(canDictate = true, canSpeak = true, listening = true, heard = "book a")))
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.size(400.dp, 800.dp)) { ChatHome(v, actions) }
        }
        onNodeWithTag("heard").assertTextContains("🎤 book a")
        onNodeWithTag("dictate").performClick()  // while dictating, a tap stops it
        onNodeWithTag("speak-h:2").performClick()
        onNodeWithTag("menu").performClick()
        onNodeWithTag("talk-wait").assertTextContains("Talk waits: Normal")
        onNodeWithTag("read-aloud").performClick()

        // what was heard goes into the composer to edit, and isn't sent yet
        v = v.copy(voice = v.voice.copy(listening = false, heard = "", dictation = Dictation(1, "book a table", send = false),
            speakingKey = "h:2"))
        waitForIdle()
        onNodeWithTag("composer").assertTextContains("book a table")
        onNodeWithTag("speak-h:2").assertTextContains("■")
        onNodeWithTag("stop-speaking").performClick()

        // with auto-send on, it's sent straight away
        v = v.copy(voice = v.voice.copy(dictation = Dictation(2, "for two", send = true)))
        waitForIdle()
        assertEquals(listOf("dictate", "speak h:2", "read aloud true", "taken 1", "stop speaking", "taken 2", "send book a table for two"),
            actions.calls)
    }

    @Test
    fun modelsAsidesStatusAndCommands() = runComposeUiTest {
        val actions = Recorder()
        val s = state(streaming = true).copy(
            models = io.github.meepdong.talaria.chat.ModelOptions(
                io.github.meepdong.talaria.chat.ModelChoice("openrouter", "anthropic/claude-sonnet-4"),
                listOf(io.github.meepdong.talaria.chat.ModelOptions.Provider("openrouter", "OpenRouter",
                    listOf("anthropic/claude-sonnet-4", "openai/gpt-5")))),
            asides = mapOf("c-1" to listOf(io.github.meepdong.talaria.chat.Aside("a-1", "what's Goa?", "A state in India"))),
            status = io.github.meepdong.talaria.chat.ConversationStatus("c-1", null, 4, 1, 50, 9, 0.0123, true, 1),
        )
        var v by mutableStateOf(view(s))
        assertEquals("claude-sonnet-4", v.model)
        assertEquals(listOf(true, false), v.modelGroups.single().models.map { it.selected })
        assertEquals(listOf("Messages" to "4", "Tool calls" to "1", "Tokens" to "50 in · 9 out", "Cost" to "$0.0123",
            "Reply running" to "Yes", "Waiting" to "1 queued"), v.status!!.lines)
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.size(1000.dp, 800.dp)) { ChatHome(v, actions) }
        }
        onNodeWithText("A state in India").assertExists()
        onNodeWithText("Close").performClick()
        v = v.copy(status = null)
        waitForIdle()
        onNodeWithTag("model").performClick()
        v = v.copy(modelPicker = "") // the controller opens the picker
        waitForIdle()
        onNodeWithTag("model-anthropic/claude-sonnet-4").assertExists()
        onNodeWithTag("model-search").performTextInput("gpt")
        onNodeWithTag("model-anthropic/claude-sonnet-4").assertDoesNotExist()
        onNodeWithTag("model-openai/gpt-5").performClick()
        v = v.copy(modelPicker = null)
        waitForIdle()
        onNodeWithTag("dismiss-aside").performClick()
        onNodeWithTag("composer").performTextInput("/st")
        onNodeWithTag("command-status").assertExists()
        onNodeWithTag("command-steer").performClick()
        onNodeWithTag("composer").assertTextContains("/steer ")
        onNodeWithTag("commands").assertDoesNotExist()
        onNodeWithTag("composer").performTextReplacement("/steer go on")
        onNodeWithTag("send").performClick()
        assertEquals(listOf("close status", "picker ", "model openrouter openai/gpt-5", "dismiss a-1", "send /steer go on"), actions.calls)
    }

    @Test
    fun modelFilter() {
        val groups = listOf(ModelGroup("anthropic", "Anthropic", listOf(
            ModelItem("anthropic", "claude-haiku-4.5", "claude-haiku-4.5", false),
            ModelItem("anthropic", "claude-sonnet-5.5", "claude-sonnet-5.5", true))))
        assertEquals(listOf("claude-haiku-4.5"), filterModels(groups, " HAIKU ").single().models.map { it.model })
        assertEquals(2, filterModels(groups, "anthropic").single().models.size, "the provider's name matches all its models")
        assertEquals(emptyList(), filterModels(groups, "gpt"))
        assertEquals(groups, filterModels(groups, ""))
    }

    @Test
    fun commandsParse() {
        assertEquals(Command.Aside("is it safe?"), Command.parse(" /btw is it safe? "))
        assertEquals(Command.Queue("then this"), Command.parse("/q then this"))
        assertEquals(Command.Model(""), Command.parse("/MODEL"))
        assertEquals(Command.Unknown("compress"), Command.parse("/compress"))
        assertNull(Command.parse("//not a command"))
        assertNull(Command.parse("a /btw in the middle"))
        assertEquals(listOf("steer", "status", "stop"), Command.suggestions("/st").map { it.name })
        assertEquals(emptyList(), Command.suggestions("/steer x"))
    }

    @Test
    fun aGrowingReplyDoesNotPullTheReaderBack() = runComposeUiTest {
        val actions = Recorder()
        fun reply(lines: Int) = state().let { st ->
            val t = st.threads.getValue("c-1")
            st.copy(threads = mapOf("c-1" to t.copy(messages = t.messages + ChatMessage("reply:t-3", Role.ASSISTANT,
                (1..lines).joinToString("\n") { "line $it" }, null, MessageState.STREAMING, turnId = "t-3"))))
        }
        var v by mutableStateOf(view(reply(80)))
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.size(400.dp, 800.dp)) { ChatHome(v, actions) }
        }
        // following: the newest line is in view, not the top of the reply
        v = view(reply(120))
        waitForIdle()
        onNodeWithText("Plan a trip").assertDoesNotExist()
        onNodeWithTag("messages").performTouchInput { swipeDown(startY = top + 50f, endY = bottom - 50f, durationMillis = 200) }
        onNodeWithTag("messages").performTouchInput { swipeDown(startY = top + 50f, endY = bottom - 50f, durationMillis = 200) }
        onNodeWithTag("messages").performTouchInput { swipeDown(startY = top + 50f, endY = bottom - 50f, durationMillis = 200) }
        onNodeWithTag("messages").performTouchInput { swipeDown(startY = top + 50f, endY = bottom - 50f, durationMillis = 200) }
        waitForIdle()
        onNodeWithText("Plan a trip").assertIsDisplayed()
        // the reply keeps growing while the user reads further up: they stay where they are
        v = view(reply(160))
        waitForIdle()
        v = view(reply(200))
        waitForIdle()
        onNodeWithText("Plan a trip").assertIsDisplayed()
    }

}
