package com.galileo.mobileconnect.core.link

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/** Which certificate a connection accepts (S-LINK certificate trust, MAPP-CON-050). */
sealed interface PinPolicy {
    /**
     * Pairing only: accept what is shown, for the one exchange, and compare it with the fingerprint in the pairing
     * response before anything is stored. The human check of the code is what makes this safe.
     */
    data object Pairing : PinPolicy

    /** Accept only this fingerprint. There is no fall-back to an unverified connection. */
    data class Pinned(val fingerprint: String) : PinPolicy

    fun accepts(presented: String): Boolean = when (this) {
        Pairing -> true
        is Pinned -> fingerprint.equals(presented, ignoreCase = true)
    }

    companion object {
        /** Lower-case hex SHA-256 of a certificate's DER form, as `galileo.connect_link.certs` computes it. */
        fun fingerprintOfDer(der: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The TLS callback for one OkHttp client (docs/android/SDD.md 2.3). It replaces chain validation for the Master's
 * self-signed certificate with [PinPolicy], and nothing else: it is never a trust-everything manager. Only the leaf
 * is looked at, as on iOS. Server authentication only; the app presents no client certificate.
 */
class PinningTrustManager(private val policy: PinPolicy) : X509TrustManager {
    private val presentedRef = AtomicReference<String?>(null)
    private val mismatchRef = AtomicReference(false)

    /** The fingerprint of the certificate most recently shown by the Master. */
    val presentedFingerprint: String? get() = presentedRef.get()

    /** True once a certificate was refused; the connection layer raises IdentityChanged from it. */
    val identityChanged: Boolean get() = mismatchRef.get()

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("The Master presented no certificate.")
        val fingerprint = PinPolicy.fingerprintOfDer(leaf.encoded)
        presentedRef.set(fingerprint)
        if (!policy.accepts(fingerprint)) {
            mismatchRef.set(true)
            throw CertificateException("The Master's certificate is not the one that was paired.")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("This app does not accept client certificates.")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * The Master is addressed by LAN IP or a .local name and presents a self-signed certificate that names neither, so
 * the host name cannot be checked; the fingerprint check in [PinningTrustManager] is what authenticates the peer.
 * Use only together with it.
 */
object PinnedHostnameVerifier : HostnameVerifier {
    override fun verify(hostname: String?, session: SSLSession?): Boolean = true
}
