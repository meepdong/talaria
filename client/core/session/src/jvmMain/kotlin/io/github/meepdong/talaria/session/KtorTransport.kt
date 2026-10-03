package io.github.meepdong.talaria.session

import io.github.meepdong.talaria.protocol.Tnp
import io.github.meepdong.talaria.protocol.b64uEncode
import io.github.meepdong.talaria.protocol.isAllowedBridgeUrl
import io.github.meepdong.talaria.protocol.sha256
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** WebSockets over Ktor with the OkHttp engine. */
class KtorTransport : Transport {
    override suspend fun open(url: String, tlsSpkiSha256: String?): WsConnection {
        if (!isAllowedBridgeUrl(url)) {
            throw TnpException(Failure.PROTOCOL, "the bridge address must start with wss:// (got $url)")
        }
        val client = HttpClient(OkHttp) {
            install(WebSockets)
            engine {
                config {
                    connectTimeout(10, TimeUnit.SECONDS)
                    readTimeout(0, TimeUnit.SECONDS) // TNP has its own heartbeat
                    if (tlsSpkiSha256 != null) {
                        // Self-signed bridge: trust exactly the certificate key from the pairing link.
                        val trust = PinnedTrustManager(tlsSpkiSha256)
                        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
                        sslSocketFactory(ssl.socketFactory, trust)
                        hostnameVerifier { _, _ -> true }
                    }
                }
            }
        }
        val session = try {
            client.webSocketSession(url) { header(HttpHeaders.SecWebSocketProtocol, Tnp.SUBPROTOCOL) }
        } catch (e: CancellationException) {
            client.close()
            throw e
        } catch (e: Exception) {
            client.close()
            if (generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains(PIN_MISMATCH) == true }) {
                throw TnpException(Failure.IDENTITY_CHANGED, PIN_MISMATCH)
            }
            throw TnpException(Failure.UNREACHABLE, e.message ?: e::class.simpleName)
        }
        return KtorConnection(client, session)
    }
}

private class KtorConnection(
    private val client: HttpClient,
    private val session: DefaultClientWebSocketSession,
) : WsConnection {
    override var closeInfo: CloseInfo? = null
        private set

    override suspend fun send(text: String) {
        try {
            session.send(Frame.Text(text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The reader will see the close and report it.
        }
    }

    override suspend fun receive(): String? {
        while (true) {
            val frame = try {
                session.incoming.receive()
            } catch (e: ClosedReceiveChannelException) {
                return finished()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return finished()
            }
            if (frame is Frame.Text) return frame.readText()
        }
    }

    private suspend fun finished(): String? {
        val reason = withTimeoutOrNull(2_000) { session.closeReason.await() }
        closeInfo = CloseInfo(reason?.code?.toInt(), reason?.message.orEmpty())
        client.close()
        return null
    }

    override suspend fun close(code: Int, reason: String) {
        try {
            session.close(CloseReason(code.toShort(), reason))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // already closed
        }
        client.close()
    }
}

private const val PIN_MISMATCH = "the bridge's TLS certificate does not match the pinned one"

/** Accepts the server only if its leaf certificate's SPKI hash matches the pin. */
private class PinnedTrustManager(private val pin: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate")
        if (b64uEncode(sha256(leaf.publicKey.encoded)) != pin) {
            throw CertificateException(PIN_MISMATCH)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("not a server")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
