package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.chat.ToolStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Talk 2: a reply is spoken as it streams, with cues for tools, and Talk listens again once it's all said. */
class TalkLoopTest {
    /** Speaks when told, and finishes when the test says the phone is done speaking. */
    private class Voice : SpeechOutput {
        val said = mutableListOf<String>()
        var onDone: (() -> Unit)? = null
        override fun speak(text: String, onDone: () -> Unit) { said += text; this.onDone = onDone }
        override fun stop() { onDone?.also { onDone = null }?.invoke() }
        fun finish() = onDone?.also { onDone = null }?.invoke()
    }

    @Test
    fun talkFollowsTheReplyToWhatWasSaid() {
        fun msg(key: String, role: io.github.meepdong.talaria.chat.Role, text: String, turn: String?, cmid: String? = null) =
            io.github.meepdong.talaria.chat.ChatMessage(key, role, text, 1, turnId = turn, clientMsgId = cmid)
        val user = io.github.meepdong.talaria.chat.Role.USER
        val hermes = io.github.meepdong.talaria.chat.Role.ASSISTANT
        val older = listOf(msg("1", user, "Hi", "t-1"), msg("2", hermes, "Hello", "t-1"))
        fun state(list: List<io.github.meepdong.talaria.chat.ChatMessage>) = io.github.meepdong.talaria.chat.ChatState(
            openId = "c-1", threads = mapOf("c-1" to io.github.meepdong.talaria.chat.ConversationThread(messages = list)))
        assertEquals(null, TalariaController.talkReply(state(older + msg("local:x", user, "What's on?", null, "x")), "x"),
            "nothing while the bridge hasn't taken it: not the older reply")
        val started = older + msg("local:x", user, "What's on?", "t-2", "x") + msg("reply:t-2", hermes, "Two meetings", "t-2")
        assertEquals("Two meetings", TalariaController.talkReply(state(started), "x")?.text)
    }

    @Test
    fun sentences() {
        assertEquals("" to 0, TalkLoop.completeSentences("Sure, let me", 0, over = false), "nothing until a sentence ends")
        assertEquals("Sure. " to 5, TalkLoop.completeSentences("Sure. Let me", 0, over = false).let { it.first + " " to it.second })
        assertEquals("" to 0, TalkLoop.completeSentences("Done.", 0, over = false), "the last full stop may be 3.5 in the making")
        assertEquals("Done." to 5, TalkLoop.completeSentences("Done.", 0, over = true))
        assertEquals(" Next one!" to 15, TalkLoop.completeSentences("Done. Next one! And", 5, over = false))
        val code = "Run this:\n```\nls -la. rm x. echo"
        assertEquals("Run this:\n" to 10, TalkLoop.completeSentences(code, 0, over = false), "nothing inside an open code block")
        assertEquals("Checking your calendar.", TalkLoop.cueFor("google_calendar_list"))
        assertEquals("Searching the web.", TalkLoop.cueFor("web_search"))
        assertEquals("One moment.", TalkLoop.cueFor("mystery"))
    }

    @Test
    fun speaksAsItStreamsAndListensAfter() {
        val voice = Voice()
        var finished = 0
        val loop = TalkLoop(voice) { finished++ }
        loop.update("Sure", emptyList(), over = false)
        assertEquals(emptyList(), voice.said)
        loop.update("Sure. Let me look", emptyList(), over = false)
        assertEquals(listOf("Sure."), voice.said, "the first sentence goes out at once")
        loop.update("Sure. Let me look.", listOf(ToolStep("google_calendar_list", "started")), over = false)
        loop.update("Sure. Let me look. You have two meetings. The first is at 10.", listOf(ToolStep("google_calendar_list", "done")), over = false)
        assertEquals(listOf("Sure."), voice.said, "one thing at a time")
        voice.finish()
        assertEquals("Let me look. You have two meetings.", voice.said[1], "what came in meanwhile goes together")
        loop.update("Sure. Let me look. You have two meetings. The first is at 10.", emptyList(), over = true)
        voice.finish()
        assertEquals("The first is at 10.", voice.said[2])
        assertEquals(0, finished, "not until it's said")
        voice.finish()
        assertEquals(1, finished)
        loop.update("Sure. Let me look. You have two meetings. The first is at 10.", emptyList(), over = true)
        assertEquals(1, finished, "once")
    }

    @Test
    fun aToolGetsACueWhileNothingElseIsSaid() {
        val voice = Voice()
        val loop = TalkLoop(voice) {}
        loop.update("", listOf(ToolStep("web_search", "started")), over = false)
        assertEquals(listOf("Searching the web."), voice.said)
        voice.finish()
        loop.update("", listOf(ToolStep("web_search", "done"), ToolStep("web_search", "started")), over = false)
        assertEquals(1, voice.said.size, "the same cue isn't said twice in a reply")
        loop.update("", listOf(ToolStep("web_search", "done"), ToolStep("web_search", "done"), ToolStep("send_file", "started")), over = false)
        assertEquals("Sending you the file.", voice.said.last())
    }

    @Test
    fun stoppingEndsItWithoutListeningAgain() {
        val voice = Voice()
        var finished = false
        val loop = TalkLoop(voice) { finished = true }
        loop.update("One. Two. Three", emptyList(), over = false)
        loop.stop()
        loop.update("One. Two. Three.", emptyList(), over = true)
        voice.finish()
        assertEquals(listOf("One. Two."), voice.said)
        assertTrue(!finished)
    }
}
