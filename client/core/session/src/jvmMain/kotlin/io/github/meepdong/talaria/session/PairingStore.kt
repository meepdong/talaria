package io.github.meepdong.talaria.session

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/** Remembers which bridge this device paired with. Nothing in it is secret. */
interface PairingStore {
    fun load(): PairedBridge?
    fun save(bridge: PairedBridge)
    fun clear()
}

/** [PairingStore] as a small JSON file, for example `%APPDATA%\Talaria\bridge.json`. */
class FilePairingStore(private val file: File) : PairingStore {
    override fun load(): PairedBridge? {
        if (!file.exists()) return null
        val o = try {
            Json.parseToJsonElement(file.readText()) as? JsonObject
        } catch (e: SerializationException) {
            null
        } catch (e: IOException) {
            null
        } ?: return null
        return PairedBridge(
            url = o.str("url") ?: return null,
            bridgeId = o.str("bridge_id") ?: return null,
            bridgePk = o.str("bridge_pk") ?: return null,
            deviceId = o.str("device_id") ?: return null,
            deviceName = o.str("name") ?: return null,
            tlsSpkiSha256 = o.str("tls_spki_sha256"),
        )
    }

    override fun save(bridge: PairedBridge) {
        val json = buildJsonObject {
            put("url", bridge.url)
            put("bridge_id", bridge.bridgeId)
            put("bridge_pk", bridge.bridgePk)
            put("device_id", bridge.deviceId)
            put("name", bridge.deviceName)
            bridge.tlsSpkiSha256?.let { put("tls_spki_sha256", it) }
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    override fun clear() {
        file.delete()
    }
}
