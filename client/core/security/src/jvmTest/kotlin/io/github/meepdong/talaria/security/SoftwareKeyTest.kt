package io.github.meepdong.talaria.security

import io.github.meepdong.talaria.protocol.P256
import io.github.meepdong.talaria.protocol.Tnp
import io.github.meepdong.talaria.protocol.keyId
import io.github.meepdong.talaria.protocol.signB64u
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoftwareKeyTest {
    @Test
    fun signsWhatTheBridgeVerifies() {
        val key = SoftwareKey.generate()
        val data = Tnp.authSignedData("O2AFUY5GTAGBQB2UESQT6MTXFU", key.deviceId, "q83vEjRWeJCrze8SNFZ4kA",
            "3q2-7wEjRWeJq83vASNFZw", 1790000000)
        assertTrue(P256.verify(key.publicKey, data, key.signB64u(data)))
        assertEquals(keyId(key.publicKey), key.deviceId)
        assertEquals(26, key.deviceId.length)
    }

    @Test
    fun exportImportRoundTrip() {
        val key = SoftwareKey.generate()
        val back = SoftwareKey.import(key.export())
        assertEquals(key.publicKey, back.publicKey)
        val data = "hello".encodeToByteArray()
        assertTrue(P256.verify(key.publicKey, data, back.signB64u(data)))
    }

    @Test
    fun mismatchedHalvesAreRejected() {
        val a = SoftwareKey.generate().export().decodeToString()
        val b = SoftwareKey.generate()
        val mixed = a.replace(Regex("\"public_key\":\"[^\"]*\""), "\"public_key\":\"${b.publicKey}\"")
        assertFailsWith<KeyStoreException> { SoftwareKey.import(mixed.encodeToByteArray()) }
    }

    @Test
    fun damagedBlobsAreRejected() {
        for (bad in listOf("", "{}", """{"v":2}""", """{"v":1,"private_pkcs8":"AAAA","public_key":"AAAA"}""")) {
            assertFailsWith<KeyStoreException>(bad) { SoftwareKey.import(bad.encodeToByteArray()) }
        }
    }

    @Test
    fun inMemoryStore() {
        val store = InMemoryKeyStore()
        assertNull(store.load())
        val key = store.loadOrCreate()
        assertEquals(key.publicKey, store.loadOrCreate().publicKey)
        store.delete()
        assertNotEquals(key.publicKey, store.loadOrCreate().publicKey)
    }
}
