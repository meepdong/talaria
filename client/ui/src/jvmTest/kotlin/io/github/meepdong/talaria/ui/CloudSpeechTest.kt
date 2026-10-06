package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.chat.ToolStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Talk 2's natural voice: speech fetched in pieces, played in order, the next fetched while one plays. */
class CloudSpeechTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private class Player : AudioPlayer {
        val played = mutableListOf<String>()
        @Volatile var onDone: (() -> Unit)? = null
        override fun play(audio: ByteArray, onDone: () -> Unit) { synchronized(played) { played += String(audio) }; this.onDone = onDone }
        override fun stop() { onDone?.also { onDone = null }?.invoke() }
        fun finish() = onDone?.also { onDone = null }?.invoke()
    }

    private class Builtin : SpeechOutput {
        val said = mutableListOf<String>()
        override fun speak(text: String, onDone: () -> Unit) { said += text; onDone() }
        override fun stop() {}
    }

    private suspend fun until(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(5) }

    @Test
    fun pieces() {
        val speech = CloudSpeech(scope, { null }, Player(), null, maxPiece = 40)
        assertEquals(listOf("One. Two.", "A third sentence that is rather long.", "Four!"),
            speech.pieces("One. Two. A third sentence that is rather long. Four!"))
        assertEquals(listOf("word word word word word word word word", "word word"),
            speech.pieces("word word word word word word word word word word"), "a sentence too long is cut between words")
    }

    @Test
    fun playsPiecesInOrderAndFetchesAhead() = runBlocking {
        val fetched = mutableListOf<String>()
        val player = Player()
        val speech = CloudSpeech(scope, { t -> synchronized(fetched) { fetched += t }; t.toByteArray() }, player, null, maxPiece = 12)
        val done = CompletableDeferred<Unit>()
        speech.speak("First one. Second one. Third.") { done.complete(Unit) }
        until { player.played.size == 1 }
        until { synchronized(fetched) { fetched.size } >= 2 }
        assertEquals(listOf("First one.", "Second one."), synchronized(fetched) { fetched.take(2) }, "the next is fetched while one plays")
        player.finish()
        until { player.played.size == 2 }
        player.finish()
        until { player.played.size == 3 }
        player.finish()
        withTimeout(5_000) { done.await() }
        assertEquals(listOf("First one.", "Second one.", "Third."), player.played)
    }

    @Test
    fun aPieceThatCantBeFetchedIsSaidByTheDevice() = runBlocking {
        val builtin = Builtin()
        val speech = CloudSpeech(scope, { null }, Player(), builtin)
        val done = CompletableDeferred<Unit>()
        speech.speak("Hello there.") { done.complete(Unit) }
        withTimeout(5_000) { done.await() }
        assertEquals(listOf("Hello there."), builtin.said)
    }

    @Test
    fun stopEndsItOnce() = runBlocking {
        val player = Player()
        val speech = CloudSpeech(scope, { it.toByteArray() }, player, null)
        var finished = 0
        speech.speak("Long answer.") { finished++ }
        until { player.played.isNotEmpty() }
        speech.stop()
        speech.stop()
        assertEquals(1, finished)
    }

    @Test
    fun theQuickLineGoesFirstAndQuietGetsANudge() {
        val said = mutableListOf<String>()
        val prepared = mutableListOf<String>()
        var onDone: (() -> Unit)? = null
        val out = object : SpeechOutput {
            override fun speak(text: String, onDone: () -> Unit) { said += text; onDone.also { onDone -> }; onDoneSet(onDone) }
            override fun stop() {}
            override fun prepare(text: String) { prepared += text }
            fun onDoneSet(d: () -> Unit) { onDone = d }
        }
        val loop = TalkLoop(out) {}
        loop.opening("Sure, checking your calendar.")
        assertEquals(listOf("Sure, checking your calendar."), said)
        loop.update("You have two meetings. The first", listOf(ToolStep("calendar", "done")), over = false)
        assertEquals(listOf("You have two meetings."), prepared, "frozen and fetched while the quick line plays")
        onDone!!.invoke()
        assertEquals("You have two meetings.", said[1])
        loop.opening("Late line.")
        assertEquals(2, said.size, "a quick line that comes after the answer started isn't said")
        onDone!!.invoke()
        loop.nudge(1_000)
        loop.nudge(5_000)
        assertEquals(2, said.size, "not yet")
        loop.nudge(1_000 + TalkLoop.QUIET_MS)
        assertEquals("Still working on it.", said.last())
    }

    @Test
    fun anApprovalIsSaidInTheSameVoiceAndTalkCarriesOn() {
        val said = mutableListOf<String>()
        var onDone: (() -> Unit)? = null
        val out = object : SpeechOutput {
            override fun speak(text: String, onDone: () -> Unit) { said += text; setDone(onDone) }
            override fun stop() {}
            fun setDone(d: () -> Unit) { onDone = d }
        }
        var finished = false
        val loop = TalkLoop(out) { finished = true }
        loop.hold(true)
        var listening = false
        loop.note(TalariaController.approvalLine("Run **curl wttr.in**")) { listening = true }
        assertEquals(listOf("I need your okay for this: Run curl wttr.in. Say yes to allow it once, or no."), said)
        assertTrue(!listening, "listens only once the question has been said")
        onDone!!.invoke()
        assertTrue(listening)
        loop.nudge(1)
        loop.nudge(1 + TalkLoop.QUIET_MS * 3)
        assertEquals(1, said.size, "no 'still working' while waiting for the owner")
        loop.hold(false)
        loop.update("The hottest part is around two.", emptyList(), over = true)
        assertEquals("The hottest part is around two.", said.last(), "after the approval, the answer, in the same voice")
        onDone!!.invoke()
        assertTrue(finished, "then it listens again")
    }

    @Test
    fun yesAllowsOnceAndNoDenies() {
        for (y in listOf("yes", "Yeah go ahead", "okay do it", "sure")) assertEquals("once", TalariaController.approvalAnswer(y), y)
        for (n in listOf("no", "nope", "don't do that", "stop")) assertEquals("deny", TalariaController.approvalAnswer(n), n)
        for (u in listOf("", "what is it", "yes no", "no, yes")) assertEquals(null, TalariaController.approvalAnswer(u), u)
        assertEquals("I need your okay for this. Say yes to allow it once, or no.", TalariaController.approvalLine(null))
    }

    @Test
    fun theYourTurnToneIsAShortWav() {
        val wav = LISTEN_CUE
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        val seconds = (wav.size - 44) / 2.0 / 24_000
        assertTrue(seconds in 0.2..0.3, "about a quarter of a second, was $seconds")
    }

    @Test
    fun anApprovalAnsweredOnScreenStopsTheQuestionAtOnce() {
        val said = mutableListOf<String>()
        var stops = 0
        var onDone: (() -> Unit)? = null
        val out = object : SpeechOutput {
            override fun speak(text: String, onDone: () -> Unit) { said += text; setDone(onDone) }
            override fun stop() { stops++; onDone?.also { onDone = null }?.invoke() }
            fun setDone(d: () -> Unit) { onDone = d }
        }
        val loop = TalkLoop(out) {}
        loop.hold(true)
        var listened = false
        loop.note(TalariaController.approvalLine("Run a long command that takes a while to describe")) { listened = true }
        assertEquals(1, said.size, "the question is being said")
        loop.hold(false)
        loop.cutNote()  // Allow tapped
        assertEquals(1, stops, "cut off at once")
        assertTrue(!listened, "no yes-or-no listening after an on-screen answer")
        loop.update("It's thirty-two degrees at two.", emptyList(), over = true)
        assertEquals("It's thirty-two degrees at two.", said.last(), "straight on to the answer")
        loop.cutNote()
        assertEquals(1, stops, "nothing more to cut")
    }
}
