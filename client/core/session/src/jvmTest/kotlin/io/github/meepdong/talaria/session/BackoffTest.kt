package io.github.meepdong.talaria.session

import io.github.meepdong.talaria.security.InMemoryKeyStore
import io.github.meepdong.talaria.security.loadOrCreate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackoffTest {
    private fun client(random: Random) = TnpClient(
        CoroutineScope(Dispatchers.Unconfined),
        { _, _ -> throw TnpException(Failure.UNREACHABLE) },
        PairedBridge("ws://127.0.0.1:1/tnp", "B", "P", "D", "test"),
        InMemoryKeyStore().loadOrCreate(),
        "desktop",
        random = random,
    )

    @Test
    fun doublesUpToAMinuteWithJitter() {
        val c = client(Random(42))
        for ((attempt, base) in listOf(1 to 1_000L, 2 to 2_000L, 3 to 4_000L, 6 to 32_000L, 7 to 60_000L, 30 to 60_000L)) {
            repeat(20) {
                val ms = c.backoffMs(attempt)
                assertTrue(ms in (base * 0.8).toLong()..(base * 1.2).toLong(), "attempt $attempt gave $ms")
            }
        }
    }

    @Test
    fun noJitterAtTheMidpoint() {
        val half = object : Random() {
            override fun nextBits(bitCount: Int) = 0
            override fun nextDouble() = 0.5
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L),
            (1..7).map { client(half).backoffMs(it) })
    }
}
