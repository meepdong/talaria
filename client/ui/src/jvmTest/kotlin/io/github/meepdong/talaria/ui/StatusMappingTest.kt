package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.security.KeyProtection
import io.github.meepdong.talaria.session.AgentStatus
import io.github.meepdong.talaria.session.ConnectionLog
import io.github.meepdong.talaria.session.ConnectionState
import io.github.meepdong.talaria.session.ConnectionState.Phase
import io.github.meepdong.talaria.session.Failure
import io.github.meepdong.talaria.session.NetworkStatus
import io.github.meepdong.talaria.session.PairedBridge
import io.github.meepdong.talaria.session.StatusReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusMappingTest {
    private val now = 1_800_000_000_000L
    private val bridge = PairedBridge("wss://vps.tailnet.ts.net", "b-1", "pk", "d-1", "Laptop")
    private val tailscale = NetworkStatus(NetworkStatus.Kind.TAILSCALE, "Tailscale ok (100.101.102.103)")
    private val noVpn = NetworkStatus(NetworkStatus.Kind.NONE, "No private network. Turn on Tailscale")

    private fun view(
        state: ConnectionState,
        network: NetworkStatus? = tailscale,
        protection: KeyProtection = KeyProtection.OS_ENCRYPTED,
        log: List<ConnectionLog.Entry> = emptyList(),
    ) = statusView(state, bridge, network, protection, log, null, now)

    @Test
    fun connectedWithAgents() {
        val v = view(ConnectionState(
            phase = Phase.CONNECTED, lastConnectedAtMs = now - 5_000, latencyMs = 45,
            status = StatusReport("0.2.0", 100, listOf(
                AgentStatus("meep", "Meep", "ready", null, null),
                AgentStatus("scout", "Scout", "degraded", null, "Provider error: out of credit"),
            )),
        ))
        assertEquals(listOf("Network", "Bridge", "Meep", "Scout"), v.rows.map { it.label })
        assertEquals(StatusRow("Network", Health.GOOD, "Tailscale ok (100.101.102.103)"), v.rows[0])
        assertEquals(StatusRow("Bridge", Health.GOOD, "Connected · 45 ms"), v.rows[1])
        assertEquals(Health.WARN, v.rows[3].health)
        assertEquals("Provider error: out of credit", v.rows[3].detail)
        assertNull(v.failure)
        assertEquals("just now", v.lastConnected)
        // A degraded agent makes the tray amber, not green.
        assertEquals(Health.WARN, v.overall)
        assertEquals("Connected", v.summary)
    }

    @Test
    fun unreachableNamesTheNetwork() {
        val v = view(
            ConnectionState(phase = Phase.WAITING, failure = Failure.UNREACHABLE, detail = "timeout",
                nextRetryAtMs = now + 7_400, attempt = 3, lastConnectedAtMs = now - 600_000),
            network = noVpn,
        )
        assertEquals(Health.BAD, v.rows[0].health)
        assertEquals("Unreachable", v.rows[1].value)
        assertEquals("Server unreachable. Is your private network (Tailscale/WireGuard) on?: timeout", v.failure)
        assertEquals("Retrying in 8 s", v.reconnectIn)
        assertFalse(v.mustPairAgain)
        assertEquals("10 min ago", v.lastConnected)
        assertEquals(Health.BAD, v.overall)
        assertEquals(StatusRow("Agents", Health.UNKNOWN, "Unknown while disconnected"), v.rows[2])
    }

    @Test
    fun revokedMeansPairAgain() {
        val v = view(ConnectionState(phase = Phase.FAILED, failure = Failure.REVOKED))
        assertTrue(v.mustPairAgain)
        assertEquals("This device was revoked. Pair again", v.failure)
        assertNull(v.reconnectIn)
        assertEquals("Never", v.lastConnected)
    }

    @Test
    fun noVpnIsOnlyAWarningWhileConnected() {
        val v = view(ConnectionState(phase = Phase.CONNECTED), network = noVpn)
        assertEquals(Health.WARN, v.rows[0].health)
    }

    @Test
    fun bridgeFromBeforeM1() {
        val v = view(ConnectionState(phase = Phase.CONNECTED, statusSupported = false))
        val agents = v.rows.drop(2).single()
        assertEquals("Not reported", agents.value)
        assertEquals(Health.UNKNOWN, agents.health)
        assertEquals(Health.GOOD, v.overall)
    }

    @Test
    fun weakKeyIsFlagged() {
        val v = view(ConnectionState(phase = Phase.CONNECTED), protection = KeyProtection.FILE_ONLY)
        assertTrue(v.keyWarning)
        assertFalse(view(ConnectionState(phase = Phase.CONNECTED)).keyWarning)
    }

    @Test
    fun logIsNewestFirst() {
        val v = view(ConnectionState(), log = listOf(
            ConnectionLog.Entry(now - 2_000, "Connected", "s-1"),
            ConnectionLog.Entry(now - 1_000, "Disconnected", "reason"),
        ))
        assertTrue(v.log[0].contains("Disconnected  (reason)"))
        assertTrue(v.log[1].contains("Connected  (s-1)"))
    }

    @Test
    fun relativeTimes() {
        assertEquals("Never", relativeTime(null, now))
        assertEquals("just now", relativeTime(now - 59_000, now))
        assertEquals("1 min ago", relativeTime(now - 60_000, now))
        assertEquals("3 h ago", relativeTime(now - 3 * 3_600_000, now))
    }

    @Test
    fun bridgeAddresses() {
        assertEquals("wss://vps.tailnet.ts.net", TalariaController.bridgeUrl(" vps.tailnet.ts.net/ "))
        assertEquals("wss://100.64.1.2:8765", TalariaController.bridgeUrl("100.64.1.2:8765"))
        assertEquals("ws://127.0.0.1:8765", TalariaController.bridgeUrl("ws://127.0.0.1:8765"))
        assertNull(TalariaController.bridgeUrl("ws://vps.tailnet.ts.net"))
        assertNull(TalariaController.bridgeUrl(""))
        assertNull(TalariaController.bridgeUrl("https://vps"))
    }
}
