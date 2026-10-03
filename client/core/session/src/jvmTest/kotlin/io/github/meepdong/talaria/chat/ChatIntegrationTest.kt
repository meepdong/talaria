package io.github.meepdong.talaria.chat

import io.github.meepdong.talaria.protocol.PairingPayload
import io.github.meepdong.talaria.security.InMemoryKeyStore
import io.github.meepdong.talaria.security.loadOrCreate
import io.github.meepdong.talaria.session.BridgeHarness
import io.github.meepdong.talaria.session.ConnectionState
import io.github.meepdong.talaria.session.KtorTransport
import io.github.meepdong.talaria.session.PairingTarget
import io.github.meepdong.talaria.session.TnpClient
import io.github.meepdong.talaria.session.pair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals

/** Chat from two Kotlin clients through the real Python bridge to a fake Hermes. */
class ChatIntegrationTest {
    private lateinit var bridge: BridgeHarness
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transport = KtorTransport()

    @BeforeEach
    fun setUp() {
        bridge = BridgeHarness.start()
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        if (::bridge.isInitialized) bridge.close()
    }

    private suspend fun device(name: String): Pair<TnpClient, ChatRepository> {
        val key = InMemoryKeyStore().loadOrCreate()
        val (link, _) = bridge.newPairing()
        val paired = pair(transport, PairingTarget.Link(PairingPayload.fromLink(link)), key, name, "desktop", onSas = {})
        val client = TnpClient(scope, transport, paired, key, "desktop")
        val repo = ChatRepository(scope, client.asChatApi())
        repo.start()
        client.start()
        withTimeout(30_000) { client.state.first { it.phase == ConnectionState.Phase.CONNECTED } }
        return client to repo
    }

    @Test
    fun chatReachesBothDevicesAndHistory() = runBlocking {
        val (laptop, fromLaptop) = device("Laptop")
        val (_, onPhone) = device("Phone")
        withTimeout(30_000) { onPhone.state.first { it.listLoaded } }
        val phoneReply = async { withTimeout(30_000) { onPhone.replies.first() } }

        fromLaptop.send("Hi Hermes")
        val done = withTimeout(30_000) {
            fromLaptop.state.first { s -> s.openMessages.lastOrNull()?.state == MessageState.DONE && s.openMessages.size == 2 }
        }
        val (user, reply) = done.openMessages
        assertEquals("Hi Hermes", user.text)
        assertEquals("Hello", reply.text)
        assertEquals(listOf(ToolStep("web_search", "completed", "ok")), reply.tools)
        val conv = done.openId!!
        assertEquals(FinishedReply(conv, "Hi Hermes", "Hello", MessageState.DONE, null), phoneReply.await())

        // the phone opens the conversation from history
        onPhone.open(conv)
        val history = withTimeout(30_000) { onPhone.state.first { it.threads[conv]?.loaded == true } }
        assertEquals(listOf("Hi Hermes", "Hello"), history.threads.getValue(conv).messages.map { it.text })

        // a follow-up in the same conversation, after a reconnect
        laptop.stop()
        laptop.start()
        withTimeout(30_000) { laptop.state.first { it.phase == ConnectionState.Phase.CONNECTED } }
        fromLaptop.send("And again")
        val second = withTimeout(30_000) {
            fromLaptop.state.first { s -> s.openMessages.size == 4 && s.openMessages.last().state == MessageState.DONE }
        }
        assertEquals(conv, second.openId)
        assertEquals(1, second.conversations.size)
    }

    @Test
    fun photosAndFilesUploadInChunks() = runBlocking {
        val (_, fromLaptop) = device("Laptop")
        val (_, onPhone) = device("Phone")
        withTimeout(30_000) { onPhone.state.first { it.listLoaded } }
        val photo = OutgoingFile("IMG_1.jpg", "image/jpeg", ByteArray(3000) { it.toByte() })
        val report = OutgoingFile("report.pdf", "application/pdf", ByteArray(700 * 1024) { (it % 251).toByte() })

        fromLaptop.send("Have a look", listOf(photo, report))
        val done = withTimeout(30_000) {
            fromLaptop.state.first { s -> s.openMessages.size == 2 && s.openMessages.last().state == MessageState.DONE }
        }
        val sent = done.openMessages.first()
        assertEquals(listOf(Attachment.Kind.IMAGE, Attachment.Kind.FILE), sent.attachments.map { it.kind })
        assertEquals(photo.bytes.toList(), sent.attachments[0].preview?.toList())

        // the phone sees what was attached, without the photo's bytes
        val conv = done.openId!!
        onPhone.open(conv)
        val history = withTimeout(30_000) { onPhone.state.first { it.threads[conv]?.loaded == true } }
        val asked = history.threads.getValue(conv).messages.first()
        assertEquals("Have a look", asked.text)
        assertEquals(listOf(Attachment(Attachment.Kind.IMAGE, "Photo", "image/jpeg"),
            Attachment(Attachment.Kind.FILE, "report.pdf", "application/pdf", report.bytes.size.toLong())), asked.attachments)
    }
}
