package io.github.meepdong.talaria.protocol

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Every file in spec/vectors, so the Kotlin client agrees byte for byte with the bridge. */
class VectorsTest {
    private fun spec(name: String): JsonObject {
        val text = javaClass.getResource("/spec/$name")?.readText()
            ?: error("spec/$name missing from the test resources")
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun frame() {
        for (case in spec("vectors/frame.json").getValue("cases").jsonArray) {
            val fields = case.jsonObject.getValue("fields").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(case.jsonObject.str("hex"), hex(frame(*fields.toTypedArray())), "frame($fields)")
        }
    }

    @Test
    fun keyIds() {
        for (key in spec("vectors/keys.json").getValue("keys").jsonArray.map { it.jsonObject }) {
            assertEquals(key.str("id"), keyId(key.str("public_key")))
        }
    }

    @Test
    fun privateScalarsMatchPublicKeys() {
        // ECDSA signatures are randomized, so sign with the vector scalar and check that
        // the vector public key verifies it.
        for (key in spec("vectors/keys.json").getValue("keys").jsonArray.map { it.jsonObject }) {
            val signer = scalarSigner(key.str("private_scalar_hex"))
            val data = frame("tnp0-test", key.str("role"))
            assertTrue(P256.verify(key.str("public_key"), data, signer.signB64u(data)), key.str("role"))
        }
    }

    @Test
    fun signatures() {
        val file = spec("vectors/signatures.json")
        val inputs = file.getValue("inputs").jsonObject
        val pk = mapOf("bridge" to inputs.str("bridge_pk"), "device" to inputs.str("device_pk"))
        val ts = inputs.getValue("ts").jsonPrimitive.long
        val built = mapOf(
            "hello" to Tnp.helloSignedData(inputs.str("bridge_id"), inputs.str("nonce_b"), ts),
            "auth" to Tnp.authSignedData(inputs.str("bridge_id"), inputs.str("device_id"),
                inputs.str("nonce_b"), inputs.str("nonce_d"), ts),
            "pair.request" to Tnp.pairSignedData(inputs.str("bridge_id"), inputs.str("nonce_b"),
                inputs.str("device_pk"), inputs.str("pair_token"), inputs.str("name"), inputs.str("platform"), ts),
        )
        for (case in file.getValue("cases").jsonArray.map { it.jsonObject }) {
            val name = case.str("name")
            val data = case.str("data_hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            built[name]?.let { assertEquals(case.str("data_hex"), hex(it), "signed data for $name") }
            val valid = case.getValue("valid").jsonPrimitive.content.toBooleanStrict()
            assertEquals(valid, P256.verify(pk.getValue(case.str("signer")), data, case.str("sig")), name)
        }
    }

    @Test
    fun sas() {
        for (case in spec("vectors/sas.json").getValue("cases").jsonArray.map { it.jsonObject }) {
            val sas = Sas.derive(case.str("bridge_pk"), case.str("device_pk"), case.str("pairing_secret"))
            assertEquals(case.str("digits"), sas.digits, case.str("name"))
            assertEquals(case.getValue("emoji_indices").jsonArray.map { it.jsonPrimitive.int }, sas.emojiIndices)
            assertEquals(case.str("emoji"), sas.emoji)
        }
    }

    @Test
    fun sasEmojiTable() {
        val table = spec("sas-emoji.json").getValue("emoji").jsonArray.map { it.jsonObject }
        assertEquals(64, table.size)
        assertEquals(table.map { it.str("emoji") to it.str("name") }, SAS_EMOJI)
    }

    @Test
    fun pairingLinks() {
        val file = spec("vectors/pairing.json")
        for (case in file.getValue("links").jsonArray.map { it.jsonObject }) {
            val p = case.getValue("payload").jsonObject
            val expected = PairingPayload(p.str("url"), p.str("bridge_id"), p.str("bridge_pk"),
                p.str("pair_token"), p.getValue("exp").jsonPrimitive.long, p["tls_spki_sha256"]?.jsonPrimitive?.content)
            assertEquals(expected, PairingPayload.fromLink(case.str("link")))
            assertEquals(expected, PairingPayload.fromLink("  " + case.str("link") + "\n"))
        }
        for (case in file.getValue("short_codes").jsonArray.map { it.jsonObject }) {
            assertEquals(case.str("normalized"), ShortCode.normalize(case.str("typed")))
        }
        for (bad in file.getValue("short_codes_invalid").jsonArray) {
            assertFailsWith<EncodingException> { ShortCode.normalize(bad.jsonPrimitive.content) }
        }
    }

    companion object {
        private val P256_PARAMS: ECParameterSpec = AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)

        /** Test-only signer from a raw scalar; real keys live in core/security. */
        fun scalarSigner(hex: String): Signer {
            val key = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(hex, 16), P256_PARAMS))
            return Signer { data ->
                Signature.getInstance("SHA256withECDSA").run { initSign(key); update(data); sign() }
            }
        }
    }
}
