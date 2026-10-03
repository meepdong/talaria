package io.github.meepdong.talaria.files

import io.github.meepdong.talaria.chat.ChatApi
import io.github.meepdong.talaria.chat.ServerFile
import io.github.meepdong.talaria.session.RpcException
import io.github.meepdong.talaria.session.TnpException
import io.github.meepdong.talaria.session.long
import io.github.meepdong.talaria.session.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.Base64

/** A folder on the server a device can browse (spec/README.md §12). */
data class FileRoot(val id: String, val name: String, val error: String? = null)

data class FileEntry(
    val name: String,
    /** Relative to the root, with `/`. */
    val path: String,
    val folder: Boolean,
    val size: Long?,
    val mime: String?,
    /** Unix seconds. */
    val modified: Long,
)

data class FilesState(
    val roots: List<FileRoot> = emptyList(),
    val root: String? = null,
    /** The folder showing, relative to [root]; "" is the root itself. */
    val path: String = "",
    /** A search under [path], or null to list the folder. */
    val query: String? = null,
    val entries: List<FileEntry> = emptyList(),
    val truncated: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** False when the bridge shares no folders. */
    val available: Boolean = true,
)

/** Browses the files next to the agent: the roots, one folder at a time, or a search by name. */
class FilesRepository(private val scope: CoroutineScope, private val api: ChatApi) {
    private val _state = MutableStateFlow(FilesState())
    val state: StateFlow<FilesState> = _state.asStateFlow()
    private var listing: Job? = null

    /** Load the roots, and open the first one unless a root is open already. */
    fun load() {
        scope.launch {
            try {
                val r = api.request("files.roots", JsonObject(emptyMap()))
                val roots = (r["roots"] as? JsonArray).orEmpty().mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    FileRoot(o.str("id") ?: return@mapNotNull null, o.str("name") ?: "", o.str("error"))
                }
                _state.update { it.copy(roots = roots, available = true) }
                val s = _state.value
                when {
                    s.root != null && roots.any { it.id == s.root } -> list(s.root, s.path, s.query)
                    roots.isNotEmpty() -> list(roots.first().id, "", null)
                    else -> _state.update { it.copy(entries = emptyList(), error = "The bridge shares no folders yet") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RpcException) {
                _state.update { it.copy(available = false, error = e.message) }
            } catch (e: TnpException) {
                _state.update { it.copy(error = "Not connected to the bridge") }
            }
        }
    }

    /** Show a folder of [root], or with [query] the files under it whose names contain it. */
    fun list(root: String, path: String = "", query: String? = null) {
        val q = query?.trim()?.takeIf { it.isNotEmpty() }
        _state.update { it.copy(root = root, path = path, query = q, loading = true, error = null) }
        listing?.cancel()
        listing = scope.launch {
            try {
                val r = api.request("files.list", buildJsonObject {
                    put("root", root)
                    if (path.isNotEmpty()) put("path", path)
                    q?.let { put("query", it) }
                })
                val entries = (r["entries"] as? JsonArray).orEmpty().mapNotNull { e -> (e as? JsonObject)?.let(::entry) }
                val truncated = (r["truncated"] as? JsonPrimitive)?.booleanOrNull == true
                _state.update { s ->
                    if (s.root != root || s.path != path || s.query != q) s
                    else s.copy(entries = entries, truncated = truncated, loading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RpcException) {
                _state.update { it.copy(entries = emptyList(), loading = false, error = e.message) }
            } catch (e: TnpException) {
                _state.update { it.copy(loading = false, error = "Not connected to the bridge") }
            }
        }
    }

    fun open(entry: FileEntry) {
        val root = _state.value.root ?: return
        if (entry.folder) list(root, entry.path)
    }

    /** One folder up, or out of a search. */
    fun up() {
        val s = _state.value
        val root = s.root ?: return
        if (s.query != null) list(root, s.path) else list(root, s.path.substringBeforeLast('/', ""))
    }

    fun search(query: String) {
        val s = _state.value
        list(s.root ?: return, s.path, query)
    }

    /** The whole file, read in chunks. Throws [RpcException] or [TnpException] if it can't be read. */
    suspend fun read(root: String, path: String): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0L
        while (true) {
            val r = api.request("files.read", buildJsonObject {
                put("root", root)
                put("path", path)
                put("offset", offset)
            }, READ_TIMEOUT_MS)
            val bytes = Base64.getDecoder().decode(r.str("data").orEmpty())
            out.write(bytes)
            offset += bytes.size
            val size = r.long("size") ?: offset
            if ((r["eof"] as? JsonPrimitive)?.booleanOrNull == true || bytes.isEmpty() || offset >= size) break
        }
        return out.toByteArray()
    }

    /** A file to name in chat.send, so the agent reads it where it is. */
    fun serverFile(entry: FileEntry): ServerFile? {
        val root = _state.value.root ?: return null
        if (entry.folder) return null
        return ServerFile(root, entry.path, entry.name, entry.mime ?: "application/octet-stream", entry.size)
    }

    private fun entry(o: JsonObject): FileEntry? {
        val name = o.str("name") ?: return null
        val path = o.str("path") ?: return null
        return FileEntry(name, path, o.str("kind") == "folder", o.long("size"), o.str("mime"), o.long("modified") ?: 0)
    }

    companion object {
        const val READ_TIMEOUT_MS = 30_000L
    }
}
