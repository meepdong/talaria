package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.ops.OpsState

/** Who asked: "Hermes", "this device" or "another device". */
fun opsFrom(requestedBy: String, deviceId: String): String = when {
    requestedBy.startsWith("agent:") -> requestedBy.removePrefix("agent:").replaceFirstChar { it.uppercase() }
    requestedBy == "device:$deviceId" -> "this device"
    requestedBy.startsWith("device:") -> "another device"
    else -> requestedBy
}

/** "service: docker" from `{"service":"docker"}`; empty when there are none. Values are simple, so no JSON parser. */
fun paramsText(paramsJson: String): String =
    Regex("\"([^\"]+)\":(\"((?:[^\"\\\\]|\\\\.)*)\"|[^,}]+)").findAll(paramsJson)
        .joinToString("\n") { m -> "${m.groupValues[1]}: ${m.groupValues[3].ifEmpty { m.groupValues[2] }}" }

fun opsApprovals(s: OpsState, deviceId: String): List<OpsApprovalItem> = s.pending.map {
    OpsApprovalItem(it.requestId, it.summary, paramsText(it.paramsJson), opsFrom(it.requestedBy, deviceId), it.tier,
        answering = it.requestId in s.answering)
}

fun opsResults(s: OpsState, deviceId: String): List<OpsResultItem> = s.results.map {
    OpsResultItem(it.requestId, it.outcome.summary, it.outcome.ok, it.outcome.output, opsFrom(it.requestedBy, deviceId))
}

private val RESTARTABLE = setOf("talaria-bridge", "docker", "tailscaled", "hermes-gateway")

fun serverView(s: OpsState, deviceId: String): ServerView {
    val overview = s.reads["system.overview"]
    val o = overview?.data as? Map<*, *>
    val rows = buildList {
        if (o != null) {
            o.num("mem_total")?.let { add("Memory" to "${gb(o.num("mem_available") ?: 0.0)} free of ${gb(it)}") }
            o.num("disk_total")?.let { add("Disk" to "${gb(o.num("disk_free") ?: 0.0)} free of ${gb(it)}") }
            (o["load"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.isNotEmpty() }
                ?.let { l -> add("Load" to l.joinToString("  ") { "%.2f".format(it) }) }
            (o["kernel"] as? String)?.let { add("Kernel" to it) }
            (o["failed_units"] as? List<*>)?.filterIsInstance<String>()?.takeIf { it.isNotEmpty() }
                ?.let { add("Failed" to it.joinToString(", ")) }
        }
    }
    val services = (s.reads["services.list"]?.data as? List<*>).orEmpty().mapNotNull { e ->
        val x = e as? Map<*, *> ?: return@mapNotNull null
        val name = x["service"] as? String ?: return@mapNotNull null
        ServiceRow(name, x["state"] as? String ?: "unknown", name in RESTARTABLE)
    }
    val containers = (s.reads["docker.ps"]?.data as? List<*>).orEmpty().mapNotNull { e ->
        val x = e as? Map<*, *> ?: return@mapNotNull null
        ContainerRow(x["name"] as? String ?: return@mapNotNull null, x["status"] as? String ?: "")
    }
    val bridge = s.reads["bridge.version"]
    val behind = ((bridge?.data as? Map<*, *>)?.get("behind") as? Number)?.toInt()
    val logs = s.reads["service.logs"]
    return ServerView(
        available = s.available,
        loading = s.busy.isNotEmpty(),
        overview = overview?.summary.orEmpty(),
        overviewRows = rows,
        services = services,
        containers = containers,
        bridge = bridge?.summary.orEmpty(),
        bridgeBehind = (behind ?: 0) > 0,
        logServices = services.map { it.name },
        logsTitle = logs?.summary,
        logs = logs?.output,
        history = s.reads["ops.history"]?.output,
        approvals = opsApprovals(s, deviceId),
        results = opsResults(s, deviceId),
        busy = s.busy,
        error = s.error,
    )
}

private fun Map<*, *>.num(key: String): Double? = (this[key] as? Number)?.toDouble()

private fun gb(bytes: Double): String = "%.1f GB".format(bytes / 1_000_000_000)
