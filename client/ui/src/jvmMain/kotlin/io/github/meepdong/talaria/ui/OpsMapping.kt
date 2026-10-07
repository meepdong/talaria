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

fun serverView(s: OpsState, deviceId: String, bots: List<Pair<String, String>> = emptyList()): ServerView {
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
    val skills = s.reads["hermes.skills"]
    val skillRows = (skills?.data as? List<*>).orEmpty().mapNotNull { e ->
        val x = e as? Map<*, *> ?: return@mapNotNull null
        SkillRow(x["name"] as? String ?: return@mapNotNull null, x["description"] as? String ?: "",
            x["category"] as? String ?: "", x["enabled"] == true)
    }
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
        hasSkills = s.catalogue.any { it.op == "hermes.skills" },
        skillsSummary = skills?.summary.orEmpty(),
        skills = skillRows,
        skillsBatch = s.catalogue.any { it.op == "hermes.skills.set" },
        settings = settingRows(s.reads["hermes.settings"]?.data),
        settingModels = ((s.reads["hermes.settings"]?.data as? Map<*, *>)?.get("models") as? List<*>).orEmpty().filterIsInstance<String>(),
        settingApplying = when {
            s.pending.any { it.op == "hermes.setting.set" } -> "Waiting for your approval…"
            s.busy.any { it == "hermes.setting.set" } -> "Changing…"
            else -> null
        },
        canFindSkills = s.catalogue.any { it.op == "hermes.skill.search" },
        skillsFound = (s.reads["hermes.skill.search"]?.data as? List<*>).orEmpty().mapNotNull { e ->
            val x = e as? Map<*, *> ?: return@mapNotNull null
            SkillFound(x["name"] as? String ?: "", x["identifier"] as? String ?: return@mapNotNull null, x["source"] as? String ?: "",
                x["trust"] as? String ?: "", x["description"] as? String ?: "")
        },
        skillsFoundSummary = s.reads["hermes.skill.search"]?.summary,
        searchingSkills = "hermes.skill.search" in s.busy,
        installFor = listOf("default" to "Hermes") + bots,
        memory = if (s.catalogue.none { it.op == "hermes.memory" }) null else (s.reads["hermes.memory"]?.data as? Map<*, *>).let { d ->
            val limits = d?.get("limits") as? Map<*, *>
            MemoryView(
                profile = d?.get("profile") as? String ?: "default", whoOptions = listOf("default" to "Hermes") + bots,
                notes = (d?.get("memory") as? List<*>).orEmpty().filterIsInstance<String>(),
                aboutYou = (d?.get("user") as? List<*>).orEmpty().filterIsInstance<String>(),
                notesLimit = (limits?.get("memory") as? Number)?.toInt() ?: 2200, aboutYouLimit = (limits?.get("user") as? Number)?.toInt() ?: 1375,
                loaded = d != null,
                applying = when {
                    s.pending.any { it.op == "hermes.memory.set" } -> "Waiting for your approval…"
                    s.busy.any { it == "hermes.memory.set" || it == "hermes.memory" } -> "…"
                    else -> null
                },
            )
        },
        skillsApplying = when {
            s.pending.any { it.op == "hermes.skills.set" || it.op == "hermes.skill.set" } -> "Waiting for your approval…"
            s.busy.any { it == "hermes.skills.set" || it == "hermes.skill.set" } -> "Applying…"
            "hermes.skills" in s.busy -> "Checking Hermes's skills…"
            else -> null
        },
    )
}

private fun Map<*, *>.num(key: String): Double? = (this[key] as? Number)?.toDouble()

private fun settingRows(data: Any?): List<SettingRow> =
    ((data as? Map<*, *>)?.get("settings") as? List<*>).orEmpty().mapNotNull { e ->
        val x = e as? Map<*, *> ?: return@mapNotNull null
        SettingRow(x["key"] as? String ?: return@mapNotNull null, x["label"] as? String ?: "", x["kind"] as? String ?: "",
            x["value"] as? String ?: "", (x["choices"] as? List<*>).orEmpty().filterIsInstance<String>(), x["empty"] as? String)
    }

private fun gb(bytes: Double): String = "%.1f GB".format(bytes / 1_000_000_000)
