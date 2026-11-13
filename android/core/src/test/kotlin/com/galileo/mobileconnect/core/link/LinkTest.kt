package com.galileo.mobileconnect.core.link

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Kotlin side of iOS `LinkTests.swift`: same cases, so the two implementations agree (MAPP-PLT-040). */
class MasterAddressTest {
    private fun accepted(text: String, host: String, port: Int) {
        val address = MasterAddress.parse(text).getOrThrow()
        assertEquals(host, address.host, text)
        assertEquals(port, address.port, text)
        assertTrue(address.baseUrl.startsWith("https://"))
    }

    @Test
    fun `MAPP-CON-020 accepts the forms people type`() {
        accepted("master.local", "master.local", 8765)
        accepted("192.168.1.20", "192.168.1.20", 8765)
        accepted("192.168.1.20:9000", "192.168.1.20", 9000)
        accepted("https://obs.example.org:8765/", "obs.example.org", 8765)
        accepted("  observatory  ", "observatory", 8765)
        accepted("[fe80::1]:9000", "fe80::1", 9000)
        accepted("fe80::1", "fe80::1", 8765)
    }

    private fun problem(text: String) = MasterAddress.check(text).second

    @Test
    fun `MAPP-CON-020 refuses in plain words`() {
        assertEquals(MasterAddress.Problem.Empty, problem(""))
        assertEquals(MasterAddress.Problem.NotEncrypted, problem("http://master.local"))
        assertEquals(MasterAddress.Problem.UnsupportedScheme("ftp"), problem("ftp://master"))
        assertEquals(MasterAddress.Problem.BadPort, problem("master.local:0"))
        assertEquals(MasterAddress.Problem.BadPort, problem("master.local:70000"))
        assertEquals(MasterAddress.Problem.BadPort, problem("master.local:abc"))
        assertEquals(MasterAddress.Problem.BadHost, problem("bad host"))
        assertEquals(MasterAddress.Problem.BadHost, problem("https://master/path"))
        assertEquals(MasterAddress.Problem.BadHost, problem("-bad.local"))
    }

    @Test
    fun `a failed parse carries the problem's message`() {
        val failure = MasterAddress.parse("http://x").exceptionOrNull()
        assertNotNull(failure)
        assertEquals(MasterAddress.Problem.NotEncrypted.message, failure.message)
    }

    @Test
    fun `an IPv6 base URL is bracketed`() {
        assertTrue(MasterAddress("fe80::1", 9000).baseUrl.contains("[fe80::1]:9000"))
    }
}

class PinPolicyTest {
    private val cert: X509Certificate = CertificateFactory.getInstance("X.509").generateCertificate(
        ByteArrayInputStream(javaClass.getResourceAsStream("/test-master-cert.pem")!!.readBytes()),
    ) as X509Certificate

    // Computed here with the JDK directly, independently of the code under test.
    private val certFingerprint =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    @Test
    fun `MAPP-CON-050 a pin accepts only its own certificate`() {
        val policy = PinPolicy.Pinned("aabb")
        assertTrue(policy.accepts("aabb"))
        assertTrue(policy.accepts("AABB"))
        assertFalse(policy.accepts("ccdd"))
    }

    @Test
    fun `pairing accepts what is shown`() = assertTrue(PinPolicy.Pairing.accepts("anything"))

    @Test
    fun `fingerprint is lower-case hex SHA-256`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PinPolicy.fingerprintOfDer("abc".toByteArray()),
        )
    }

    @Test
    fun `MAPP-CON-050 the trust manager accepts the pinned certificate and records it`() {
        val tm = PinningTrustManager(PinPolicy.Pinned(certFingerprint))
        tm.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
        assertEquals(certFingerprint, tm.presentedFingerprint)
        assertFalse(tm.identityChanged)
    }

    @Test
    fun `MAPP-CON-050 a different certificate is refused and flags IdentityChanged`() {
        val tm = PinningTrustManager(PinPolicy.Pinned("00".repeat(32)))
        assertFailsWith<CertificateException> { tm.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA") }
        assertTrue(tm.identityChanged)
        assertEquals(certFingerprint, tm.presentedFingerprint)
    }

    @Test
    fun `no certificate and client certificates are refused`() {
        val tm = PinningTrustManager(PinPolicy.Pairing)
        assertFailsWith<CertificateException> { tm.checkServerTrusted(emptyArray(), "x") }
        assertFailsWith<CertificateException> { tm.checkClientTrusted(arrayOf(cert), "x") }
        assertEquals(0, tm.acceptedIssuers.size)
    }

    @Test
    fun `during pairing the trust manager reports the fingerprint for comparison with the response body`() {
        val tm = PinningTrustManager(PinPolicy.Pairing)
        tm.checkServerTrusted(arrayOf(cert), "x")
        assertEquals(certFingerprint, tm.presentedFingerprint)
        assertFalse(tm.identityChanged)
    }
}

class SecureStoreTest {
    private val item = MasterCredentials("M", MasterAddress("m"), "secret-token", "f")

    @Test
    fun `MAPP-CON-040 in-memory store round trips`() {
        val store = InMemorySecureStore()
        store.save(item)
        assertEquals(listOf(item), store.all())
        store.delete(item.id)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `MAPP-CON-040 the token never appears in the printed form`() {
        assertFalse("secret-token" in item.toString())
    }
}

class StreamLinkTest {
    @Test
    fun `MAPP-CON-070 resume uses the core parameters`() {
        val a = MasterAddress("m.local", 8765)
        assertEquals("wss://m.local:8765/connect/v1/stream?epoch=e&last=4", StreamUrl.of(a, "e", 4))
        assertEquals("wss://m.local:8765/connect/v1/stream", StreamUrl.of(a, null, null))
        assertEquals("wss://[fe80::1]:9000/connect/v1/stream", StreamUrl.of(MasterAddress("fe80::1", 9000), null, 3))
    }

    @Test
    fun `backoff grows and is capped`() {
        val b = Backoff(jitter = { 1.0 })
        assertEquals(1.0, b.delaySeconds(0))
        assertEquals(8.0, b.delaySeconds(3))
        assertEquals(30.0, b.delaySeconds(20))
    }
}
