package io.github.meepdong.talaria.session

/** What a device remembers about the bridge it paired with. */
data class PairedBridge(
    val url: String,
    val bridgeId: String,
    val bridgePk: String,
    val deviceId: String,
    val deviceName: String,
    val tlsSpkiSha256: String? = null,
)

/**
 * Why a connection failed, grouped by the layer that broke so the UI can name it and
 * the fix (UI.md §2).
 */
enum class Failure(val layer: Layer, val terminal: Boolean, val message: String) {
    UNREACHABLE(Layer.NETWORK, false, "Server unreachable. Is your private network (Tailscale/WireGuard) on?"),
    TIMEOUT(Layer.BRIDGE, false, "The bridge stopped answering"),
    PROTOCOL(Layer.BRIDGE, false, "The bridge sent something unexpected"),
    CLOSED(Layer.BRIDGE, false, "The bridge closed the connection"),
    CLOCK(Layer.BRIDGE, false, "This computer's clock and the bridge's differ by more than 2 minutes"),
    AUTH_REFUSED(Layer.BRIDGE, true, "The bridge refused this device's credentials. Pair again"),
    REVOKED(Layer.BRIDGE, true, "This device was revoked. Pair again"),
    IDENTITY_CHANGED(Layer.BRIDGE, true,
        "Server identity changed ⚠️ Do not continue unless you re-installed the bridge"),
    LINK_EXPIRED(Layer.BRIDGE, true, "This pairing link has expired. Run talaria pair again"),
    PAIR_REJECTED(Layer.BRIDGE, true, "Pairing was rejected on the bridge"),
    ;

    enum class Layer { NETWORK, BRIDGE }
}

class TnpException(val failure: Failure, detail: String? = null) :
    Exception(if (detail == null) failure.message else "${failure.message}: $detail")

/** An error reply from the bridge, with its JSON-RPC code (PROTOCOL §13). */
class RpcException(val code: Int, message: String) : Exception(message)

/** One agent's health from the bridge's status report (PROTOCOL §10.1). */
data class AgentStatus(val id: String, val name: String, val state: String, val model: String?, val detail: String?)

data class StatusReport(
    val bridgeVersion: String,
    val uptimeS: Long,
    val agents: List<AgentStatus>,
)

data class ConnectionState(
    val phase: Phase = Phase.STOPPED,
    val failure: Failure? = null,
    /** Extra detail for [failure], such as the close reason. */
    val detail: String? = null,
    val sessionId: String? = null,
    val lastConnectedAtMs: Long? = null,
    val nextRetryAtMs: Long? = null,
    val attempt: Int = 0,
    val latencyMs: Long? = null,
    /** Null until the first report, or when the bridge is too old to send one. */
    val status: StatusReport? = null,
    val statusSupported: Boolean = true,
) {
    enum class Phase { STOPPED, CONNECTING, CONNECTED, WAITING, FAILED }
}
