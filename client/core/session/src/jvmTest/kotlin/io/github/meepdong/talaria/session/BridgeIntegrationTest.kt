package io.github.meepdong.talaria.session

import io.github.meepdong.talaria.protocol.PairingPayload
import io.github.meepdong.talaria.protocol.Sas
import io.github.meepdong.talaria.protocol.ShortCode
import io.github.meepdong.talaria.security.InMemoryKeyStore
import io.github.meepdong.talaria.security.loadOrCreate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Kotlin session against the real Python bridge: the M1 interop check. */
class BridgeIntegrationTest {
    private lateinit var bridge: BridgeHarness
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transport = KtorTransport()
    private val key = InMemoryKeyStore().loadOrCreate()

    @BeforeEach
    fun setUp() {
        bridge = BridgeHarness.start()
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        if (::bridge.isInitialized) bridge.close()
    }

    private fun pairByLink(): PairedBridge = runBlocking {
        val (link, _) = bridge.newPairing()
        var sas: Sas? = null
        val paired = pair(transport, PairingTarget.Link(PairingPayload.fromLink(link)), key, "Kotlin test", "desktop",
            onSas = { sas = it })
        assertEquals(6, assertNotNull(sas).digits.length)
        paired
    }

    private fun client(paired: PairedBridge, settings: ClientSettings = ClientSettings()) =
        TnpClient(scope, transport, paired, key, "desktop", settings = settings)

    private suspend fun TnpClient.awaitPhase(phase: ConnectionState.Phase) =
        withTimeout(20_000) { state.first { it.phase == phase } }

    @Test
    fun pairConnectAndReadStatus() = runBlocking {
        val paired = pairByLink()
        assertEquals(key.deviceId, paired.deviceId)
        assertEquals(bridge.url, paired.url)

        val client = client(paired)
        client.start()
        val connected = client.awaitPhase(ConnectionState.Phase.CONNECTED)
        assertTrue(connected.sessionId!!.startsWith("s-"))
        val status = withTimeout(10_000) { client.state.first { it.status != null }.status!! }
        assertEquals(listOf("meep"), status.agents.map { it.id })

        val test = client.test()
        assertTrue(test.ok, test.message)
        assertNotNull(test.latencyMs)

        bridge.setAgent("offline")
        withTimeout(10_000) { client.state.first { it.status?.agents?.single()?.state == "offline" } }
        assertTrue(client.log.entries.value.any { it.event == "Connected" })
        client.stop()
    }

    @Test
    fun bridgeWithoutStatusSupport() = runBlocking {
        bridge.close()
        bridge = BridgeHarness.start(oldBridge = true)
        val client = client(pairByLink())
        client.start()
        client.awaitPhase(ConnectionState.Phase.CONNECTED)
        withTimeout(10_000) { client.state.first { !it.statusSupported } }
        assertTrue(client.test().ok)
        client.stop()
    }

    @Test
    fun pairByShortCode() = runBlocking {
        val (_, code) = bridge.newPairing()
        val paired = pair(transport, PairingTarget.Code(bridge.url, ShortCode.normalize(code)), key, "Kotlin test",
            "desktop", onSas = {})
        assertEquals(key.deviceId, paired.deviceId)
    }

    @Test
    fun revokedDeviceStops() = runBlocking {
        val paired = pairByLink()
        val client = client(paired)
        client.start()
        client.awaitPhase(ConnectionState.Phase.CONNECTED)
        bridge.revoke(paired.deviceId)
        val failed = client.awaitPhase(ConnectionState.Phase.FAILED)
        assertEquals(Failure.REVOKED, failed.failure)
    }

    @Test
    fun changedBridgeIdentityStops() = runBlocking {
        val paired = pairByLink()
        val other = InMemoryKeyStore().loadOrCreate().publicKey
        val client = client(paired.copy(bridgePk = other))
        client.start()
        assertEquals(Failure.IDENTITY_CHANGED, client.awaitPhase(ConnectionState.Phase.FAILED).failure)
    }

    @Test
    fun expiredLinkIsRefusedBeforeConnecting() = runBlocking {
        val (link, _) = bridge.newPairing()
        val payload = PairingPayload.fromLink(link)
        val e = assertFailsWith<TnpException> {
            pair(transport, PairingTarget.Link(payload), key, "x", "desktop", onSas = {}, nowS = { payload.exp + 1 })
        }
        assertEquals(Failure.LINK_EXPIRED, e.failure)
    }

    @Test
    fun unreachableBridgeRetries() = runBlocking {
        val paired = pairByLink()
        bridge.close()
        val client = client(paired)
        client.start()
        val waiting = client.awaitPhase(ConnectionState.Phase.WAITING)
        assertEquals(Failure.UNREACHABLE, waiting.failure)
        assertNotNull(waiting.nextRetryAtMs)
        client.stop()
    }
}
