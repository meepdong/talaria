package io.github.meepdong.talaria.session

/** How a WebSocket closed: the code and reason from the close frame, if there was one. */
data class CloseInfo(val code: Int?, val reason: String)

/** One open WebSocket carrying TNP text frames. */
interface WsConnection {
    suspend fun send(text: String)

    /** The next text frame, or null once the connection has closed. */
    suspend fun receive(): String?

    /** Set once [receive] has returned null. */
    val closeInfo: CloseInfo?

    suspend fun close(code: Int, reason: String)
}

/** Opens WebSockets. Throws [TnpException] with [Failure.UNREACHABLE] when it can't. */
fun interface Transport {
    suspend fun open(url: String, tlsSpkiSha256: String?): WsConnection
}
