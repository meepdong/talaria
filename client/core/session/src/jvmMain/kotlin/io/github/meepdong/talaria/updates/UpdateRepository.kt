package io.github.meepdong.talaria.updates

import io.github.meepdong.talaria.chat.ChatApi
import io.github.meepdong.talaria.session.RpcException
import io.github.meepdong.talaria.session.TnpException
import io.github.meepdong.talaria.session.long
import io.github.meepdong.talaria.session.obj
import io.github.meepdong.talaria.session.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/** A release the bridge offers (spec/README.md §17). */
data class AppRelease(
    val version: String,
    val versionCode: Long,
    val size: Long,
    val sha256: String,
    val notes: String? = null,
)

data class UpdateState(
    /** The bridge's channel: "beta" or "stable"; null until asked. */
    val channel: String? = null,
    val release: AppRelease? = null,
    /** [release] is newer than this app. */
    val available: Boolean = false,
    val checking: Boolean = false,
    /** A check the user asked for found nothing newer. */
    val upToDate: Boolean = false,
    /** 0..1 while the installer downloads. */
    val progress: Float? = null,
    val error: String? = null,
    /** False when the bridge has no app updates (an older bridge). */
    val supported: Boolean = true,
)

/** App updates from the bridge (§17): what's newer than [currentCode], and its installer, checked before it's used. */
class UpdateRepository(
    private val scope: CoroutineScope,
    private val api: ChatApi,
    private val currentCode: Long,
    private val platform: String = "android",
) {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            launch {
                api.notifications.collect { msg ->
                    if (msg.str("method") != "app.available") return@collect
                    val p = msg.obj("params") ?: return@collect
                    if (p.str("platform") != platform) return@collect
                    p.obj("release")?.let(::release)?.let { r -> offer(p.str("channel"), r) }
                }
            }
            launch { api.sessions.collect { check(manual = false) } }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Ask the bridge for its newest release; [manual] shows "up to date" when there is nothing newer. */
    fun check(manual: Boolean = true) {
        _state.update { it.copy(checking = true, upToDate = false, error = if (manual) null else it.error) }
        scope.launch {
            try {
                val r = api.request("app.latest", buildJsonObject { put("platform", platform) })
                val release = r.obj("release")?.let(::release)
                offer(r.str("channel"), release)
                _state.update { it.copy(checking = false, upToDate = manual && !it.available) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RpcException) {
                _state.update {
                    if (e.code == METHOD_NOT_FOUND) it.copy(checking = false, supported = false)
                    else it.copy(checking = false, error = if (manual) e.message else it.error)
                }
            } catch (e: TnpException) {
                _state.update { it.copy(checking = false, error = if (manual) "Not connected to the bridge" else it.error) }
            }
        }
    }

    /**
     * The newest release's installer, read in chunks and checked against its size and SHA-256;
     * null when it fails ([UpdateState.error] says why).
     */
    suspend fun download(): ByteArray? {
        val release = _state.value.release?.takeIf { _state.value.available } ?: return null
        _state.update { it.copy(progress = 0f, error = null) }
        val error = try {
            val out = ByteArrayOutputStream(release.size.toInt())
            while (true) {
                val r = api.request("app.read", buildJsonObject {
                    put("platform", platform)
                    put("version_code", release.versionCode)
                    put("offset", out.size())
                }, READ_TIMEOUT_MS)
                val data = Base64.getDecoder().decode(r.str("data") ?: "")
                out.write(data)
                _state.update { it.copy(progress = (out.size().toFloat() / release.size).coerceIn(0f, 1f)) }
                val eof = (r["eof"] as? JsonPrimitive)?.booleanOrNull == true
                if (eof || data.isEmpty() || out.size() > release.size) break
            }
            val bytes = out.toByteArray()
            when {
                bytes.size.toLong() != release.size -> "The download was incomplete"
                sha256(bytes) != release.sha256 -> "The download didn't match its checksum"
                else -> {
                    _state.update { it.copy(progress = null) }
                    return bytes
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            if (e.code == CONFLICT) check(manual = false)  // a newer one replaced it
            e.message ?: "The bridge refused it"
        } catch (e: TnpException) {
            "Not connected to the bridge"
        }
        _state.update { it.copy(progress = null, error = "Couldn't update: $error") }
        return null
    }

    /** The installer was handed to the system, or it said why it couldn't install it. */
    fun installFailed(message: String) = _state.update { it.copy(progress = null, error = "Couldn't update: $message") }

    private fun offer(channel: String?, release: AppRelease?) = _state.update {
        it.copy(channel = channel ?: it.channel, release = release, available = release != null && release.versionCode > currentCode,
            supported = true)
    }

    private fun release(o: JsonObject): AppRelease? {
        return AppRelease(
            version = o.str("version") ?: return null,
            versionCode = o.long("version_code") ?: return null,
            size = o.long("size")?.takeIf { it in 1..MAX_SIZE } ?: return null,
            sha256 = o.str("sha256")?.lowercase()?.takeIf { it.length == 64 } ?: return null,
            notes = o.str("notes")?.takeIf { it.isNotBlank() },
        )
    }

    companion object {
        const val METHOD_NOT_FOUND = -32601
        const val CONFLICT = -32013
        const val MAX_SIZE = 200L * 1024 * 1024
        const val READ_TIMEOUT_MS = 60_000L

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
