package io.github.meepdong.talaria.chat

import io.github.meepdong.talaria.session.RpcException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Talk's voice through the bridge (spec/README.md §9, Talk 2): natural speech for a short piece of text, and a
 * quick first line to say while the agent works. Failures come back as null, so Talk can fall back to the
 * device's own voice; a bridge without a voice key says so once and isn't asked again.
 */
class VoiceApi(private val api: ChatApi) {
    /** False once the bridge said it has no voice. */
    @Volatile var available = true
        private set

    /** Audio for [text] (at most [MAX_SPEECH] characters), WAV or MP3, or null. */
    suspend fun speech(text: String): ByteArray? {
        if (!available || text.isBlank()) return null
        val r = call("voice.speech", buildJsonObject { put("text", text.trim().take(MAX_SPEECH)) }) ?: return null
        val audio = (r["audio"] as? JsonPrimitive)?.content ?: return null
        return runCatching { Base64.getDecoder().decode(audio) }.getOrNull()
    }

    /** One short line acknowledging [text], said in [conversationId], or null. */
    suspend fun ack(text: String, conversationId: String?): String? {
        if (!available || text.isBlank()) return null
        val r = call("voice.ack", buildJsonObject {
            put("text", text.trim().take(2000))
            conversationId?.let { put("conversation_id", it) }
        }) ?: return null
        return (r["text"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }

    private suspend fun call(method: String, params: kotlinx.serialization.json.JsonObject) = try {
        api.request(method, params, timeoutMs = TIMEOUT_MS)
    } catch (e: CancellationException) {
        throw e
    } catch (e: RpcException) {
        if (e.code == METHOD_NOT_FOUND) available = false
        null
    } catch (e: Exception) {
        null
    }

    companion object {
        /** The bridge's limit for one piece of speech. */
        const val MAX_SPEECH = 150
        private const val METHOD_NOT_FOUND = -32601
        private const val TIMEOUT_MS = 15_000L
    }
}
