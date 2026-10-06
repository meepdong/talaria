package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.chat.VoiceApi.TalkEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Talk 3's turn-taking, with a fake microphone, speaker and talker. */
@OptIn(ExperimentalCoroutinesApi::class)
class TalkerSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = scope.cancel()

    private class Mic : VoiceRecorder {
        var listener: VoiceRecorder.Listener? = null
        var starts = 0
        var cancels = 0
        var stops = 0
        var quietMs = 0
        override fun start(listener: VoiceRecorder.Listener, endQuietMs: Int) { this.listener = listener; starts++; quietMs = endQuietMs }
        override fun stop() { stops++ }
        override fun cancel() { cancels++; listener = null }
    }

    private class Speaker : PcmPlayer {
        val played = mutableListOf<String>()
        var finishing: (() -> Unit)? = null
        var stops = 0
        override fun write(pcm: ByteArray) { played += String(pcm) }
        override fun finish(onDone: () -> Unit) { finishing = onDone }
        override fun stop() { stops++; finishing = null }
        fun drained() = finishing?.also { finishing = null }?.invoke()
    }

    private class Talker : TalkerApi {
        override val events = MutableSharedFlow<TalkEvent>(extraBufferCapacity = 64)
        val turns = mutableListOf<Pair<String, String?>>()
        val ended = mutableListOf<String>()
        var next = 1
        override suspend fun turn(wav: ByteArray, conversationId: String?) = "t${next++}".also { turns += String(wav) to conversationId }
        override suspend fun say(text: String, conversationId: String?) = "s${next++}"
        override suspend fun end(conversationId: String) { ended += conversationId }
    }

    private fun session(mic: Mic, speaker: Speaker, talker: Talker, phases: MutableList<Pair<TalkPhase, String>>,
                        opened: MutableList<String>, ends: MutableList<String?>, cues: IntArray) =
        TalkerSession(scope, talker, mic, speaker, cue = { cues[0]++ },
            onPhase = { p, t -> phases += p to t }, onConversation = { opened += it }, onEnd = { ends += it })

    @Test
    fun aTurnThenItsYourTurn() = runBlocking {
        val mic = Mic(); val speaker = Speaker(); val talker = Talker()
        val phases = mutableListOf<Pair<TalkPhase, String>>(); val opened = mutableListOf<String>(); val ends = mutableListOf<String?>()
        val cues = IntArray(1)
        val s = session(mic, speaker, talker, phases, opened, ends, cues)
        s.start(null)
        assertEquals(1, mic.starts)
        mic.listener!!.done("hello".toByteArray())
        assertEquals(listOf<Pair<String, String?>>("hello" to null), talker.turns, "what was said goes to the talker, as audio")
        talker.events.emit(TalkEvent.Text("t1", null, "Added it."))
        talker.events.emit(TalkEvent.Audio("t1", null, 0, "pcm1".toByteArray()))
        talker.events.emit(TalkEvent.Done("t1", "c-7", "Added it.", unprompted = false, error = null))
        assertEquals(listOf("pcm1"), speaker.played)
        assertEquals(listOf("c-7"), opened, "its first brief started a chat: open it")
        assertEquals(1, mic.starts, "not while it's still being heard")
        speaker.drained()
        assertEquals(1, cues[0], "the your-turn tone")
        assertEquals(2, mic.starts, "then it listens again")
        assertTrue(phases.any { it == TalkPhase.SPEAKING to "Added it." })

        // the agent's reply, said on its own while they're quiet: it takes over the mic
        talker.events.emit(TalkEvent.Audio("u9", "c-7", 0, "result".toByteArray()))
        assertEquals(1, mic.cancels)
        assertEquals(listOf("pcm1", "result"), speaker.played)
        talker.events.emit(TalkEvent.Done("u9", "c-7", "It's in your calendar.", unprompted = true, error = null))
        speaker.drained()
        assertEquals(3, mic.starts)

        // a chat that isn't this one isn't ours
        talker.events.emit(TalkEvent.Audio("x1", "c-other", 0, "nope".toByteArray()))
        assertEquals(2, speaker.played.size)

        // quiet: Talk ends, and the bridge stops speaking replies in the chat
        mic.listener!!.done(null)
        assertEquals(listOf<String?>(null), ends)
        assertEquals(listOf("c-7"), talker.ended)
    }

    @Test
    fun theirWordsShowAndOpenTheChatTheWaitIsTheirsAndTheLevelMoves() = runBlocking {
        val mic = Mic(); val speaker = Speaker(); val talker = Talker()
        val phases = mutableListOf<Pair<TalkPhase, String>>(); val opened = mutableListOf<String>()
        val levels = mutableListOf<Float>()
        var wait = TalkWait.PATIENT
        val s = TalkerSession(scope, talker, mic, speaker, cue = {}, onPhase = { p, t -> phases += p to t },
            onConversation = { opened += it }, onEnd = {}, endQuietMs = { wait.quietMs }, onLevel = { levels += it })
        s.start(null)
        assertEquals(1500, mic.quietMs, "Patient waits longer before it answers")
        mic.listener!!.level(0.4f)
        mic.listener!!.done("hello".toByteArray())
        assertEquals(listOf(0.4f, 0f), levels, "the level moves while listening, and drops when they're done")
        talker.events.emit(TalkEvent.Heard("t1", "c-9", "Add milk to my list"))
        assertEquals(TalkPhase.THINKING to "“Add milk to my list”", phases.last(), "their words show at once")
        assertEquals(listOf("c-9"), opened, "the chat they're kept in opens")
        assertEquals("c-9", s.conversationId)
        talker.events.emit(TalkEvent.Audio("t1", "c-9", 0, "pcm".toByteArray()))
        talker.events.emit(TalkEvent.Done("t1", "c-9", "Added milk.", unprompted = false, error = null))
        assertEquals(listOf("c-9"), opened, "opened once")
        wait = TalkWait.QUICK
        speaker.drained()
        assertEquals(500, mic.quietMs, "a change applies to the next turn")
        s.end()
    }

    @Test
    fun aTapCutsTheTalkerOff() = runBlocking {
        val mic = Mic(); val speaker = Speaker(); val talker = Talker()
        val s = session(mic, speaker, talker, mutableListOf(), mutableListOf(), mutableListOf(), IntArray(1))
        s.start("c-1")
        s.tap()
        assertEquals(1, mic.stops, "while listening, a tap ends their turn")
        mic.listener!!.done("hi".toByteArray())
        talker.events.emit(TalkEvent.Audio("t1", "c-1", 0, "long answer".toByteArray()))
        s.tap()
        assertEquals(1, speaker.stops)
        assertEquals(2, mic.starts, "listening again at once")
        talker.events.emit(TalkEvent.Audio("t1", "c-1", 1, "rest".toByteArray()))
        assertEquals(listOf("long answer"), speaker.played, "the rest of what was cut off isn't played")
    }

    @Test
    fun anErrorEndsTalkWithWhy() = runBlocking {
        val mic = Mic(); val speaker = Speaker(); val talker = Talker()
        val ends = mutableListOf<String?>()
        val s = session(mic, speaker, talker, mutableListOf(), mutableListOf(), ends, IntArray(1))
        s.start("c-1")
        mic.listener!!.done("hi".toByteArray())
        talker.events.emit(TalkEvent.Done("t1", "c-1", "", unprompted = false, error = "The voice model refused (404)"))
        assertEquals(listOf<String?>("The voice model refused (404)"), ends)
    }
}
