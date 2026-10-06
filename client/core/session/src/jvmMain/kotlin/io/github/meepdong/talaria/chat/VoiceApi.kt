package io.github.meepdong.talaria.chat

import io.github.meepdong.talaria.session.RpcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
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

    /** False once the bridge said it has no talker (Talk 3: a voice model that hears audio and briefs the agent). */
    @Volatile var talkerAvailable = true
        private set

    /** Talk 3: what the talker says, as it says it. */
    sealed interface TalkEvent {
        val talkId: String
        val conversationId: String?
        /** 24 kHz 16-bit mono PCM. */
        data class Audio(override val talkId: String, override val conversationId: String?, val seq: Int, val pcm: ByteArray) : TalkEvent
        data class Text(override val talkId: String, override val conversationId: String?, val text: String) : TalkEvent
        data class Done(override val talkId: String, override val conversationId: String?, val text: String,
                        val unprompted: Boolean, val error: String?) : TalkEvent
    }

    val talkEvents: Flow<TalkEvent> = api.notifications.mapNotNull { msg ->
        val method = (msg["method"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        val p = msg["params"] as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        fun str(k: String) = (p[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = str("talk_id") ?: return@mapNotNull null
        val conv = str("conversation_id")
        when (method) {
            "talk.audio" -> runCatching { Base64.getDecoder().decode(str("data")) }.getOrNull()
                ?.let { TalkEvent.Audio(id, conv, (p["seq"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0, it) }
            "talk.text" -> TalkEvent.Text(id, conv, str("text").orEmpty())
            "talk.done" -> TalkEvent.Done(id, conv, str("text").orEmpty(),
                (p["unprompted"] as? JsonPrimitive)?.content == "true", str("error"))
            else -> null
        }
    }

    /** What was said, as WAV: the talker hears it, answers through [talkEvents]. Returns the talk id, or null. */
    suspend fun talkTurn(wav: ByteArray, conversationId: String?): String? {
        if (!talkerAvailable) return null
        val r = call("talk.turn", buildJsonObject {
            put("audio", Base64.getEncoder().encodeToString(wav))
            put("format", "wav")
            conversationId?.let { put("conversation_id", it) }
        }, talk = true) ?: return null
        return (r["talk_id"] as? JsonPrimitive)?.content
    }

    /** The talker says [text] as it is. */
    suspend fun talkSay(text: String, conversationId: String?): String? {
        if (!talkerAvailable) return null
        val r = call("talk.say", buildJsonObject {
            put("text", text.take(2000))
            conversationId?.let { put("conversation_id", it) }
        }, talk = true) ?: return null
        return (r["talk_id"] as? JsonPrimitive)?.content
    }

    /** Talk ended: the agent's replies in [conversationId] aren't spoken any more. */
    suspend fun talkEnd(conversationId: String) {
        if (talkerAvailable) call("talk.end", buildJsonObject { put("conversation_id", conversationId) }, talk = true)
    }

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

    private suspend fun call(method: String, params: kotlinx.serialization.json.JsonObject, talk: Boolean = false) = try {
        api.request(method, params, timeoutMs = TIMEOUT_MS)
    } catch (e: CancellationException) {
        throw e
    } catch (e: RpcException) {
        if (e.code == METHOD_NOT_FOUND) { if (talk) talkerAvailable = false else available = false }
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
