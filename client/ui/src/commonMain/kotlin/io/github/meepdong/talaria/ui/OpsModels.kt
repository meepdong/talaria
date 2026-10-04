package io.github.meepdong.talaria.ui

/** A server operation waiting for approval (PROTOCOL §10.8): "Restart docker", asked by Hermes. */
data class OpsApprovalItem(
    val requestId: String,
    val summary: String,
    /** Its parameters as text, such as "service: docker"; empty when it has none. */
    val detail: String,
    /** "Hermes", "this device" or "another device". */
    val from: String,
    /** 1: change; 2: disruptive, so Allow asks again. */
    val tier: Int,
    /** This device is sending its answer. */
    val answering: Boolean = false,
)

/** An approved operation that finished, until dismissed. */
data class OpsResultItem(val requestId: String, val summary: String, val ok: Boolean, val output: String, val from: String)

/** One service on the Server page. */
data class ServiceRow(val name: String, val state: String, val canRestart: Boolean)

/** One running Docker container. */
data class ContainerRow(val name: String, val status: String)

/** The Server page, opened from the ☰ menu: the server's state and what can be done to it. */
data class ServerView(
    /** False when the bridge offers no server operations (talaria-ops isn't installed). */
    val available: Boolean = true,
    val loading: Boolean = false,
    /** "up 47 h, disk 13% used, 0 updates" */
    val overview: String = "",
    val overviewRows: List<Pair<String, String>> = emptyList(),
    val services: List<ServiceRow> = emptyList(),
    val containers: List<ContainerRow> = emptyList(),
    /** "up to date" or "2 commits behind GitHub" */
    val bridge: String = "",
    val bridgeBehind: Boolean = false,
    val logServices: List<String> = emptyList(),
    val logsTitle: String? = null,
    val logs: String? = null,
    val history: String? = null,
    val approvals: List<OpsApprovalItem> = emptyList(),
    val results: List<OpsResultItem> = emptyList(),
    /** Operations on their way, by name (e.g. "service.logs"). */
    val busy: Set<String> = emptySet(),
    val error: String? = null,
)
