package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.security.KeyProtection
import io.github.meepdong.talaria.session.ConnectionLog
import io.github.meepdong.talaria.session.ConnectionState
import io.github.meepdong.talaria.session.ConnectionState.Phase
import io.github.meepdong.talaria.session.Failure
import io.github.meepdong.talaria.session.NetworkStatus
import io.github.meepdong.talaria.session.PairedBridge
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/** "just now", "5 min ago", "3 h ago", then the date. */
fun relativeTime(atMs: Long?, nowMs: Long): String {
    if (atMs == null) return "Never"
    val s = (nowMs - atMs).coerceAtLeast(0) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 24 * 3600 -> "${s / 3600} h ago"
        else -> DATE.format(Instant.ofEpochMilli(atMs))
    }
}

private fun Failure.withDetail(detail: String?): String = listOfNotNull(message, detail).joinToString(": ")

private fun agentHealth(state: String): Health = when (state) {
    "ready" -> Health.GOOD
    "degraded" -> Health.WARN
    "offline" -> Health.BAD
    else -> Health.UNKNOWN
}

/** Turn the session's state into the Connection status screen (UI.md §2). */
fun statusView(
    state: ConnectionState,
    bridge: PairedBridge,
    network: NetworkStatus?,
    protection: KeyProtection,
    log: List<ConnectionLog.Entry>,
    test: TestView?,
    nowMs: Long,
): StatusView {
    val failure = state.failure
    val down = state.phase != Phase.CONNECTED

    val networkRow = when {
        network == null -> StatusRow("Network", Health.UNKNOWN, "Checking…")
        network.ok -> StatusRow("Network", Health.GOOD, network.detail)
        down && failure?.layer == Failure.Layer.NETWORK ->
            StatusRow("Network", Health.BAD, "No private network", network.detail)
        else -> StatusRow("Network", Health.WARN, "No VPN found", network.detail)
    }

    val bridgeRow = when (state.phase) {
        Phase.CONNECTED -> StatusRow("Bridge", Health.GOOD,
            listOfNotNull("Connected", state.latencyMs?.let { "$it ms" }).joinToString(" · "))
        Phase.CONNECTING -> if (failure == null) {
            StatusRow("Bridge", Health.WARN, "Connecting…")
        } else {
            StatusRow("Bridge", Health.BAD, "Connecting (attempt ${state.attempt})…", failure.withDetail(state.detail))
        }
        Phase.WAITING -> StatusRow("Bridge", Health.BAD,
            if (failure?.layer == Failure.Layer.NETWORK) "Unreachable" else "Disconnected",
            failure?.withDetail(state.detail))
        Phase.FAILED -> StatusRow("Bridge", Health.BAD, "Stopped", failure?.withDetail(state.detail))
        Phase.STOPPED -> StatusRow("Bridge", Health.UNKNOWN, "Not connected")
    }

    val report = state.status
    val agentRows = when {
        down -> listOf(StatusRow("Agents", Health.UNKNOWN, "Unknown while disconnected"))
        !state.statusSupported -> listOf(StatusRow("Agents", Health.UNKNOWN, "Not reported",
            "This bridge is older than M1 and doesn't report agent health yet"))
        report == null -> listOf(StatusRow("Agents", Health.WARN, "Waiting for the bridge's report…"))
        report.agents.isEmpty() -> listOf(StatusRow("Agents", Health.UNKNOWN, "None configured on the bridge"))
        else -> report.agents.map { a ->
            StatusRow(a.name, agentHealth(a.state), a.state.replaceFirstChar { it.uppercase() }, a.detail)
        }
    }

    val overall = when {
        state.phase == Phase.CONNECTED ->
            if (agentRows.any { it.health == Health.BAD || it.health == Health.WARN }) Health.WARN else Health.GOOD
        failure != null -> Health.BAD
        state.phase == Phase.CONNECTING -> Health.WARN
        else -> Health.UNKNOWN
    }
    val summary = when (state.phase) {
        Phase.CONNECTED -> "Connected"
        Phase.CONNECTING -> "Connecting"
        Phase.WAITING -> "Disconnected, retrying"
        Phase.FAILED -> "Stopped: ${failure?.message ?: "pair again"}"
        Phase.STOPPED -> "Not connected"
    }

    return StatusView(
        rows = listOf(networkRow, bridgeRow) + agentRows,
        failure = if (down) failure?.withDetail(state.detail) else null,
        mustPairAgain = state.phase == Phase.FAILED,
        lastConnected = relativeTime(state.lastConnectedAtMs, nowMs),
        reconnectIn = state.nextRetryAtMs?.takeIf { state.phase == Phase.WAITING }?.let {
            "Retrying in ${((it - nowMs).coerceAtLeast(0) + 999) / 1000} s"
        },
        deviceName = bridge.deviceName,
        server = bridge.url,
        keyProtection = protection.description,
        keyWarning = protection.weak,
        test = test,
        log = log.asReversed().take(200).map { it.line() },
        overall = overall,
        summary = summary,
    )
}
