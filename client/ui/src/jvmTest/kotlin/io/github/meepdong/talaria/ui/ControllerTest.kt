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
