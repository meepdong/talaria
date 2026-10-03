package io.github.meepdong.talaria.session

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoresTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun pairingStoreRoundTrip() {
        val store = FilePairingStore(File(dir, "sub/bridge.json"))
        assertNull(store.load())
        val bridge = PairedBridge("wss://srv.ts.net:8443/tnp", "B", "PK", "D", "Laptop", "PIN")
        store.save(bridge)
        assertEquals(bridge, store.load())
        store.save(bridge.copy(tlsSpkiSha256 = null))
        assertEquals(bridge.copy(tlsSpkiSha256 = null), store.load())
        store.clear()
        assertNull(store.load())
        File(dir, "sub/bridge.json").writeText("{broken")
        assertNull(store.load())
    }

    @Test
    fun networkCheck() {
        fun ip(vararg b: Int) = b.map { it.toByte() }.toByteArray()
        assertEquals(NetworkStatus.Kind.TAILSCALE,
            NetworkCheck.check(listOf("eth0" to listOf(ip(192, 168, 1, 5)), "tailscale0" to listOf(ip(100, 114, 203, 100)))).kind)
        assertEquals(NetworkStatus.Kind.VPN, NetworkCheck.check(listOf("wg0 WireGuard" to listOf(ip(10, 0, 0, 2)))).kind)
        assertEquals(NetworkStatus.Kind.NONE, NetworkCheck.check(listOf("eth0" to listOf(ip(192, 168, 1, 5)))).kind)
        assertEquals(NetworkStatus.Kind.NONE, NetworkCheck.check(listOf("eth0" to listOf(ip(100, 128, 0, 1)))).kind)
    }

    @Test
    fun connectionLogFile() {
        val file = File(dir, "connection.log")
        val log = ConnectionLog(ConnectionLog.fileSink(file, maxBytes = 200))
        repeat(20) { log.add(1_790_000_000_000L + it, "Connected", "s-$it") }
        assertTrue(file.readText().contains("Connected  (s-19)"))
        assertTrue(File(dir, "connection.log.1").exists())
        assertEquals(20, log.entries.value.size)
    }
}
