package io.github.meepdong.talaria.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Edge cases the shared vectors don't cover. */
class ProtocolTest {
    private val bridgePk =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEAhfmF_C2RDkoJ4-WmZ5pojpPLBUr321s32bluAKC1O0ZSn3ry5dxLS3aPKhaqHZaVvRfx1hZllLyiXxlMG5XlA"

    @Test
    fun base64urlIsStrict() {
        assertEquals("AAECAwQFBgcICQoLDA0ODw", b64uEncode(ByteArray(16) { it.toByte() }))
        assertFailsWith<EncodingException> { b64uDecode("AAECAwQFBgcICQoLDA0ODw==") }
        assertFailsWith<EncodingException> { b64uDecode("AAECAwQFBgcICQoLDA0ODx") } // non-canonical last char
        assertFailsWith<EncodingException> { b64uDecode("AA+/") }
    }

    @Test
    fun publicKeysMustBeCanonicalP256() {
        assertEquals("O2AFUY5GTAGBQB2UESQT6MTXFU", keyId(bridgePk))
        assertFailsWith<EncodingException> { P256.checkPublicKey("AAAA") }
        assertFalse(P256.verify("AAAA", byteArrayOf(1), "AAAA"))
        assertFalse(P256.verify(bridgePk, byteArrayOf(1), "not base64!"))
    }

    @Test
    fun messageRoundTrip() {
        val msg = Tnp.request("d-1", "status.get")
        assertEquals("""{"jsonrpc":"2.0","tnp":0,"id":"d-1","method":"status.get"}""", Tnp.encode(msg))
        assertEquals(msg, Tnp.decode(Tnp.encode(msg)))
        val result = Tnp.result(JsonPrimitive("d-1"), buildJsonObject { put("ts", 1) })
        assertEquals(result, Tnp.decode(Tnp.encode(result)))
    }

    @Test
    fun decodeRejectsNonTnp() {
        for (bad in listOf("not json", "[]", """{"jsonrpc":"2.0","method":"ping"}""",
            """{"jsonrpc":"2.0","tnp":"0","method":"ping"}""", """{"jsonrpc":"1.0","tnp":0}""",
            """{"jsonrpc":"2.0","tnp":0,"method":"x","params":[1]}""")) {
            assertFailsWith<ProtocolException>(bad) { Tnp.decode(bad) }
        }
    }

    @Test
    fun corruptPairingLinks() {
        for (bad in listOf("https://example.com", "talaria://pair#", "talaria://pair#bm90IGpzb24",
            "talaria://pair#" + b64uEncode("""{"tnp":1}""".encodeToByteArray()),
            "talaria://pair#" + b64uEncode("""{"tnp":0,"url":"wss://x/tnp"}""".encodeToByteArray()),
            "talaria://pair#" + b64uEncode(
                """{"tnp":0,"url":"u","bridge_id":"b","bridge_pk":"p","pair_token":"t","exp":"soon"}""".encodeToByteArray()))) {
            assertFailsWith<EncodingException>(bad) { PairingPayload.fromLink(bad) }
        }
    }

    @Test
    fun shortCodeFormat() {
        assertEquals("HX49-2KQ7", ShortCode.format("HX492KQ7"))
    }

    @Test
    fun plainWsOnlyToLoopback() {
        assertTrue(isAllowedBridgeUrl("wss://srv.tailnet.ts.net:8443/tnp"))
        assertTrue(isAllowedBridgeUrl("ws://127.0.0.1:8765/tnp"))
        assertTrue(isAllowedBridgeUrl("ws://localhost:8765/tnp"))
        assertTrue(isAllowedBridgeUrl("ws://[::1]:8765/tnp"))
        assertFalse(isAllowedBridgeUrl("ws://100.114.203.100:8765/tnp"))
        assertFalse(isAllowedBridgeUrl("ws://127.0.0.1.evil.com/tnp"))
        assertFalse(isAllowedBridgeUrl("http://127.0.0.1/tnp"))
        assertFalse(isAllowedBridgeUrl("ws://user@127.0.0.1/tnp"))
    }

    @Test
    fun sasDisplay() {
        assertEquals("414 818  🍎🌻🐹", Sas("414818", listOf(40, 35, 3)).display())
        assertEquals("apple, sunflower, hamster", Sas("414818", listOf(40, 35, 3)).emojiNames)
    }
}
