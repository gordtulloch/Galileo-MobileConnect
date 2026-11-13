package com.galileo.mobileconnect.core.link

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The pin against a real TLS handshake, not a stubbed one: a self-signed Master certificate, the app's pinned
 * client, the generated API and the WebSocket stream (MAPP-CON-050, MAPP-CON-070). What the vectors cannot show.
 */
class RealTlsTest {
    private val masterCert = HeldCertificate.Builder().commonName("test-master").build()
    private val masterFingerprint = PinPolicy.fingerprintOfDer(masterCert.certificate.encoded)
    private val identity = ClientIdentity("Test Tablet", "0.1.0", "android", "tablet")
    private val servers = mutableListOf<MockWebServer>()

    private fun server(): MockWebServer = MockWebServer().also {
        val certificates = HandshakeCertificates.Builder().heldCertificate(masterCert).build()
        it.useHttps(certificates.sslSocketFactory(), false)
        it.start()
        servers += it
    }

    @AfterTest
    fun shutDown() = servers.forEach { runCatching { it.shutdown() } }

    private fun addressOf(server: MockWebServer) = MasterAddress(server.hostName, server.port)

    private fun pinned(policy: PinPolicy, holder: TokenHolder = TokenHolder()) =
        MasterHttp.pinnedClient(policy, identity, holder)

    private fun sendThenClose(vararg frames: String) = MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                frames.forEach { webSocket.send(it) }
                webSocket.close(1000, null)
            }
        },
    )

    @Test
    fun `MAPP-CON-070 the stream delivers frames over a pinned connection, resuming with the core's parameters`() = runBlocking<Unit> {
        val server = server()
        server.enqueue(sendThenClose("""{"type":"hello","epoch":"e1","seq":5}""", """{"seq":6,"data":{"type":"unknown"}}"""))
        val (client, trust) = pinned(PinPolicy.Pinned(masterFingerprint), TokenHolder("tok-1"))
        val transport = WebSocketStreamTransport(client, addressOf(server)) { trust.identityChanged }

        val frames = withTimeout(10_000) { transport.connect("e1", 5).toList() }

        assertEquals(2, frames.size)
        val request = server.takeRequest()
        assertEquals("/connect/v1/stream?epoch=e1&last=5", request.path)
        assertEquals("Bearer tok-1", request.getHeader("Authorization"))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("GalileoMobileConnect/"))
    }

    @Test
    fun `MAPP-CON-050 a different certificate ends the stream as IdentityChanged`() = runBlocking<Unit> {
        val server = server()
        server.enqueue(sendThenClose("""{"type":"hello","epoch":"e1","seq":0}"""))
        val (client, trust) = pinned(PinPolicy.Pinned("00".repeat(32)))
        val transport = WebSocketStreamTransport(client, addressOf(server)) { trust.identityChanged }

        assertFailsWith<LinkError.IdentityChanged> { withTimeout(10_000) { transport.connect(null, null).toList() } }
        assertTrue(trust.identityChanged)
        assertEquals(0, server.requestCount) // nothing was sent to a Master that failed the pin
    }

    @Test
    fun `MAPP-CON-060 a 401 on the stream means the pairing is gone`() = runBlocking<Unit> {
        val server = server()
        server.enqueue(MockResponse().setResponseCode(401))
        val (client, trust) = pinned(PinPolicy.Pinned(masterFingerprint))
        val transport = WebSocketStreamTransport(client, addressOf(server)) { trust.identityChanged }

        assertFailsWith<LinkError.Unpaired> { withTimeout(10_000) { transport.connect(null, null).toList() } }
    }

    @Test
    fun `MAPP-CON-050 the API refuses a different certificate and says the identity changed`() = runBlocking<Unit> {
        val server = server()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"state":"pending"}"""))
        val (client, trust) = pinned(PinPolicy.Pinned("00".repeat(32)))
        val api = MasterApi(addressOf(server).baseUrl, client, identity, identityChanged = { trust.identityChanged })

        assertFailsWith<LinkError.IdentityChanged> { api.pollPairing("r1") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `MAPP-CON-030 pairing over TLS compares the certificate shown with the one the Master reports`() = runBlocking<Unit> {
        val server = server()
        val started = """{"request_id":"r1","code":"482913","expires_in":300,"fingerprint":"$masterFingerprint","protocol":1}"""
        server.enqueue(MockResponse().setResponseCode(202).setBody(started))
        val (client, trust) = pinned(PinPolicy.Pairing)
        val address = addressOf(server)
        val store = InMemorySecureStore()
        val flow = PairingFlow(
            MasterApi(address.baseUrl, client, identity, identityChanged = { trust.identityChanged }),
            store, address, "Master", presentedFingerprint = { trust.presentedFingerprint },
        )

        assertIs<PairingStatus.Waiting>(flow.start())
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun `MAPP-CON-050 pairing ends and stores nothing when the Master reports another certificate`() = runBlocking<Unit> {
        val server = server()
        val started = """{"request_id":"r1","code":"482913","expires_in":300,"fingerprint":"${"ab".repeat(32)}","protocol":1}"""
        server.enqueue(MockResponse().setResponseCode(202).setBody(started))
        val (client, trust) = pinned(PinPolicy.Pairing)
        val address = addressOf(server)
        val store = InMemorySecureStore()
        val flow = PairingFlow(
            MasterApi(address.baseUrl, client, identity, identityChanged = { trust.identityChanged }),
            store, address, "Master", presentedFingerprint = { trust.presentedFingerprint },
        )

        val status = flow.start()

        assertIs<PairingStatus.Failed>(status)
        assertEquals(LinkError.IdentityChanged.message, status.message)
        assertTrue(store.all().isEmpty())
    }
}
