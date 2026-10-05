package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.protocol.Sas
import io.github.meepdong.talaria.security.DeviceKey
import io.github.meepdong.talaria.security.InMemoryKeyStore
import io.github.meepdong.talaria.session.Failure
import io.github.meepdong.talaria.session.NetworkStatus
import io.github.meepdong.talaria.session.PairedBridge
import io.github.meepdong.talaria.session.PairingStore
import io.github.meepdong.talaria.session.PairingTarget
import io.github.meepdong.talaria.session.TnpException
import io.github.meepdong.talaria.session.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keys = InMemoryKeyStore()
    private val store = MemoryPairingStore()
    private val unreachable = Transport { _, _ -> throw TnpException(Failure.UNREACHABLE, "no route") }
    private val approve = CompletableDeferred<Unit>()
    private var target: PairingTarget? = null

    private val pairer = Pairer { t, key, name, onSas ->
        target = t
        onSas(Sas("482913", listOf(0, 1, 2)))
        approve.await()
        PairedBridge("wss://vps.example", "b-1", "pk", key.deviceId, name)
    }

    private fun controller(pairer: Pairer = this.pairer) = TalariaController(
        scope, keys, store, "linux", "Laptop",
        transport = unreachable,
        pairer = pairer,
        networkCheck = { NetworkStatus(NetworkStatus.Kind.NONE, "No private network. Turn on Tailscale") },
    )

    @AfterEach
    fun tearDown() = scope.cancel()

    private suspend inline fun <reified T : Screen> TalariaController.await(crossinline ok: (T) -> Boolean = { true }): T =
        withTimeout(10_000) { screen.first { it is T && ok(it) } as T }

    @Test
    fun pairByCodeThenShowStatus() = runBlocking {
        val c = controller()
        c.start()
        c.pairWithCode("abcd-1234", "vps.example", "  My laptop ")
        val confirm = c.await<Screen.Confirm>()
        assertEquals("482 913", confirm.digits)
        assertEquals(3, confirm.emoji.size)
        assertTrue(confirm.secondsLeft in 115..120, "countdown starts at the bridge's 2 minutes")
        assertEquals(PairingTarget.Code("wss://vps.example", "ABCD1234"), target)

        approve.complete(Unit)
        val chat = c.await<Screen.Chat> { it.status.failure != null }
        val status = chat.status
        assertEquals("Not connected. ${status.failure}", chat.view.composerHint)
        assertEquals(false, chat.view.canSend)
        assertEquals("My laptop", status.deviceName)
        assertEquals("Unreachable", status.rows[1].value)
        assertEquals(Health.BAD, status.overall)
        assertNotNull(status.reconnectIn)
        assertEquals("My laptop", store.saved?.deviceName)
        assertTrue(c.log.entries.value.any { it.event == "Paired" })
        c.close()
    }

    @Test
    fun cancelGoesBackToConnect() = runBlocking {
        val c = controller()
        c.start()
        c.pairWithCode("ABCD1234", "vps.example", "Laptop")
        c.await<Screen.Confirm>()
        c.cancelPairing()
        val connect = c.await<Screen.Connect> { !it.busy }
        assertNull(connect.error)
        assertNull(store.saved)
    }

    @Test
    fun badInputIsExplained() = runBlocking {
        val c = controller()
        c.start()
        c.pairWithLink("hello", "Laptop")
        assertTrue(c.await<Screen.Connect> { it.error != null }.error!!.contains("pairing link"))
        c.pairWithCode("ABC", "vps.example", "Laptop")
        assertTrue(c.await<Screen.Connect> { it.error?.contains("8 characters") == true }.error!!.isNotEmpty())
        c.pairWithCode("ABCD1234", "http://vps", "Laptop")
        assertTrue(c.await<Screen.Connect> { it.error?.contains("address") == true }.error!!.isNotEmpty())
    }

    @Test
    fun pairingFailureIsShown() = runBlocking {
        val c = controller { _, _, _, _ -> throw TnpException(Failure.PAIR_REJECTED, "operator said no") }
        c.start()
        c.pairWithCode("ABCD1234", "vps.example", "Laptop")
        val connect = c.await<Screen.Connect> { it.error != null }
        assertEquals("Pairing was rejected on the bridge: operator said no", connect.error)
    }

    @Test
    fun savedPairingReconnectsAtStart() = runBlocking {
        val key = keys.create()
        store.saved = PairedBridge("wss://vps.example", "b-1", "pk", key.deviceId, "Laptop")
        val c = controller()
        c.start()
        val status = c.await<Screen.Chat>().status
        assertEquals("wss://vps.example", status.server)
        c.showStatus()
        assertTrue(c.await<Screen.Status>().view.canGoBack)
        c.showChats()
        c.await<Screen.Chat>()
        assertTrue(status.keyWarning, "the in-memory store reports a file-only key")
        c.close()
    }

    @Test
    fun savedPairingWithoutKeyAsksToPairAgain() = runBlocking {
        store.saved = PairedBridge("wss://vps.example", "b-1", "pk", "d-gone", "Laptop")
        val c = controller()
        c.start()
        assertTrue(c.await<Screen.Connect> { it.error != null }.error!!.contains("pair again"))
    }

    private class FakeInput : SpeechInput {
        var listener: SpeechInput.Listener? = null
        var stops = 0
        override fun start(listener: SpeechInput.Listener) { this.listener = listener }
        override fun stop() { stops++ }
    }

    private class FakeOutput : SpeechOutput {
        val said = mutableListOf<String>()
        var onDone: (() -> Unit)? = null
        override fun speak(text: String, onDone: () -> Unit) { said += text; this.onDone = onDone }
        override fun stop() { onDone?.invoke() }
    }

    @Test
    fun dictationAndReadingAloud() = runBlocking {
        val key = keys.create()
        store.saved = PairedBridge("wss://vps.example", "b-1", "pk", key.deviceId, "Laptop")
        val prefs = Prefs.Memory()
        val output = FakeOutput()
        val c = TalariaController(scope, keys, store, "linux", "Laptop", transport = unreachable, pairer = pairer,
            networkCheck = { NetworkStatus(NetworkStatus.Kind.NONE, "off") }, speechOutput = output, prefs = prefs)
        c.start()
        assertEquals(false, c.await<Screen.Chat>().view.voice.canDictate)
        val input = FakeInput()
        c.setSpeechInput(input)
        c.await<Screen.Chat> { it.view.voice.canDictate && it.view.voice.canSpeak }

        c.toggleDictation()
        input.listener!!.partial("book a")
        assertEquals("book a", c.await<Screen.Chat> { it.view.voice.heard == "book a" }.view.voice.heard)
        c.toggleDictation()
        assertEquals(1, input.stops)
        input.listener!!.done(" book a table ")
        val dictation = c.await<Screen.Chat> { it.view.voice.dictation != null }.view.voice
        assertEquals(false, dictation.listening)
        assertEquals("book a table", dictation.dictation!!.text)
        assertEquals(false, dictation.dictation!!.send)
        c.dictationTaken(dictation.dictation!!.id)
        c.await<Screen.Chat> { it.view.voice.dictation == null }

        c.speak("h:2", "**Goa** it is")
        assertEquals(listOf("Goa it is"), output.said)
        c.await<Screen.Chat> { it.view.voice.speakingKey == "h:2" }
        c.speak("h:2", "**Goa** it is") // a second tap stops it
        c.await<Screen.Chat> { it.view.voice.speakingKey == null }

        c.setReadAloud(true)
        c.setAutoSend(true)
        assertTrue(prefs.get(TalariaController.PREF_READ_ALOUD, false))
        assertTrue(c.await<Screen.Chat> { it.view.voice.autoSend }.view.voice.readAloud)
        c.close()
    }

    @Test
    fun homeTileOrderIsKeptOnThisDevice() = runBlocking {
        val key = keys.create()
        store.saved = PairedBridge("wss://vps.example", "b-1", "pk", key.deviceId, "Laptop")
        val prefs = Prefs.Memory()
        val c = TalariaController(scope, keys, store, "linux", "Laptop", transport = unreachable, pairer = pairer,
            networkCheck = { NetworkStatus(NetworkStatus.Kind.NONE, "off") }, prefs = prefs)
        c.start()
        assertEquals(HomeTile.entries, c.await<Screen.Chat>().home.order)
        c.startArrangingHome()
        c.await<Screen.Chat> { it.home.arranging }
        c.moveHomeTile(HomeTile.RECENT, up = true)
        c.moveHomeTile(HomeTile.RECENT, up = true)
        val moved = listOf(HomeTile.DAY, HomeTile.NEXT, HomeTile.RECENT, HomeTile.AUTOMATIONS, HomeTile.TODOS)
        c.await<Screen.Chat> { it.home.order == moved }
        c.selectTab(Tab.CHATS)
        assertTrue(c.await<Screen.Chat> { it.tab == Tab.CHATS }.home.arranging.not(), "leaving Home ends rearranging")
        c.close()

        val again = TalariaController(scope, keys, store, "linux", "Laptop", transport = unreachable, pairer = pairer,
            networkCheck = { NetworkStatus(NetworkStatus.Kind.NONE, "off") }, prefs = prefs)
        again.start()
        assertEquals(moved, again.await<Screen.Chat>().home.order, "the order survives a restart")
        again.close()
    }

    @Test
    fun anUpdateAsksForTheInstallPermissionFirst() = runBlocking {
        val key = keys.create()
        store.saved = PairedBridge("wss://vps.example", "b-1", "pk", key.deviceId, "Laptop")
        val c = controller()
        c.start()
        c.await<Screen.Chat>()
        var allowed = false
        val asked = mutableListOf<String>()
        c.setInstaller(object : AppUpdater {
            override fun canInstall() = allowed
            override fun askPermission() { asked += "settings" }
            override fun install(apk: ByteArray) { asked += "install" }
        })
        c.installUpdate()
        assertEquals(listOf("settings"), asked, "asked before anything is downloaded")
        c.resumeUpdate()
        assertEquals(listOf("settings"), asked, "still not allowed: nothing happens")
        allowed = true
        c.resumeUpdate()  // back from the settings screen
        c.resumeUpdate()  // a second resume doesn't start another
        assertEquals(listOf("settings"), asked, "nothing to download here (no release), so nothing to install")
        c.close()
    }

    @Test
    fun tabsMenuAndTalk() = runBlocking {
        val key = keys.create()
        store.saved = PairedBridge("wss://vps.example", "b-1", "pk", key.deviceId, "Laptop")
        val output = FakeOutput()
        val c = TalariaController(scope, keys, store, "linux", "Laptop", transport = unreachable, pairer = pairer,
            networkCheck = { NetworkStatus(NetworkStatus.Kind.NONE, "off") }, speechOutput = output)
        c.start()
        assertEquals(Tab.HOME, c.await<Screen.Chat>().tab, "paired apps open on Home")
        c.setMenuOpen(true)
        c.await<Screen.Chat> { it.menuOpen }
        c.selectTab(Tab.CHATS)
        val chats = c.await<Screen.Chat> { it.tab == Tab.CHATS }
        assertEquals(false, chats.menuOpen, "picking a page closes the menu")
        c.selectTab(Tab.HOME)
        c.await<Screen.Chat> { it.tab == Tab.HOME }

        // without a microphone, Talk opens a chat to type in
        c.talk()
        c.await<Screen.Chat> { it.tab == Tab.CHATS && it.view.conversationOpen && it.view.notice != null }
        c.selectTab(Tab.HOME)

        val input = FakeInput()
        c.setSpeechInput(input)
        c.await<Screen.Chat> { it.tab == Tab.HOME && it.view.voice.canDictate }
        c.talk()
        // page and voice are separate flows, so wait for both rather than the first frame that listens
        val talking = c.await<Screen.Chat> { it.view.voice.talk == TalkPhase.LISTENING && it.tab == Tab.CHATS }
        assertNull(talking.view.openId, "with no chat to go back to, Talk starts one")
        input.listener!!.done("what's on today")
        // sent straight away, then Talk waits for Hermes's reply to speak it
        val sent = c.await<Screen.Chat> { it.view.voice.talk == TalkPhase.THINKING }
        assertEquals("🎙 what's on today", sent.view.messages.last().text, "marked as spoken, for Hermes and in the chat")
        assertNull(sent.view.voice.dictation, "not left in the composer")

        // a tap while Hermes thinks interrupts and listens again; saying nothing ends Talk
        val first = input.listener
        c.talk()
        c.await<Screen.Chat> { it.view.voice.talk == TalkPhase.LISTENING }
        assertTrue(input.listener !== first)
        input.listener!!.done("")
        c.await<Screen.Chat> { it.view.voice.talk == null && !it.view.voice.listening }

        // End stops it from any state
        c.talk()
        c.await<Screen.Chat> { it.view.voice.talk == TalkPhase.LISTENING }
        c.endTalk()
        c.await<Screen.Chat> { it.view.voice.talk == null }
        assertTrue(input.stops > 0)
        assertTrue(output.said.isEmpty(), "nothing to say without a reply")
        c.close()
    }

    @Test
    fun aDeletedTodoWaitsOutItsUndo() = runBlocking {
        val key = keys.create()
        store.saved = PairedBridge("wss://vps.example", "b-1", "pk", key.deviceId, "Laptop")
        val c = controller()
        c.start()
        c.await<Screen.Chat>()
        c.deleteTodo("t-1")
        assertEquals("To-do deleted", c.await<Screen.Chat> { it.undo != null }.undo)
        c.undo()
        c.await<Screen.Chat> { it.undo == null }
        // a second swipe while Undo shows: the first stands, the second can be undone
        c.deleteTodo("t-1")
        c.deleteTodo("t-2")
        c.await<Screen.Chat> { it.undo == "To-do deleted" }
        c.close()
    }

    @Test
    fun askingAboutSomethingSaysWhatItIs() {
        val ran = io.github.meepdong.talaria.schedule.AutomationRan("00000000000a", "Flight watch", "home",
            io.github.meepdong.talaria.schedule.AutomationRun(1_791_192_600, "ok", text = "**No alert.**\nCheapest ₹4,200."))
        assertEquals("About my automation \"Flight watch\" (it ran 5 Oct, 09:30). It said:\n> **No alert.**\n> Cheapest ₹4,200.\n\n",
            TalariaController.aboutResult(ran, java.time.ZoneOffset.UTC))
        val blocked = ran.copy(run = ran.run.copy(status = "blocked", text = null, blocked = "recursive delete"))
        assertTrue(TalariaController.aboutResult(blocked, java.time.ZoneOffset.UTC).contains("it was blocked, needing my approval for recursive delete."))
        assertEquals("About the server action \"Restart Hermes\" (it failed, asked by Phone). Its output:\n```\nexit 1\n```\n\n",
            TalariaController.aboutServerResult(OpsResultItem("r-1", "Restart Hermes", false, "exit 1\n", "Phone")))
        assertEquals("From my to-do list: Renew passport\nDue: 2026-10-09\nIn my list: Errands\n\nPlease take care of this, or tell me what you need from me.",
            TalariaController.handOver("Renew passport", due = "2026-10-09", group = "Errands"))
    }

    @Test
    fun sharedFilesOpenANewChatReadyToSend() = runBlocking {
        val key = keys.create()
        store.saved = PairedBridge("wss://vps.example", "b-1", "pk", key.deviceId, "Laptop")
        val c = controller()
        c.start()
        c.await<Screen.Chat>()
        val pdf = io.github.meepdong.talaria.chat.OutgoingFile("statement.pdf", "application/pdf", ByteArray(3))
        c.receiveShare(List(130) { pdf }, "  What did I spend on food? ")
        val view = c.await<Screen.Chat> { it.view.conversationOpen && it.view.pending.size == 128 && it.view.voice.dictation != null }.view
        assertNull(view.openId, "a new chat")
        assertEquals(128, view.pending.size, "a message carries up to 128 files")
        assertEquals(false, view.canAttachMore)
        assertEquals("What did I spend on food?", view.voice.dictation?.text)
        assertEquals(false, view.voice.dictation?.send)
        c.close()
    }

    @Test
    fun forgetClearsThePairingAndKey() = runBlocking {
        val key: DeviceKey = keys.create()
        store.saved = PairedBridge("wss://vps.example", "b-1", "pk", key.deviceId, "Laptop")
        val c = controller()
        c.start()
        c.await<Screen.Chat>()
        c.forgetServer()
        assertIs<Screen.Connect>(c.await<Screen.Connect>())
        withTimeout(5_000) { while (store.saved != null || keys.load() != null) kotlinx.coroutines.delay(20) }
    }
}

private class MemoryPairingStore : PairingStore {
    @Volatile var saved: PairedBridge? = null
    override fun load() = saved
    override fun save(bridge: PairedBridge) {
        saved = bridge
    }
    override fun clear() {
        saved = null
    }
}
