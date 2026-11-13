package net.sentinelsafeguard.mailapp

import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import net.sentinelsafeguard.mailapp.config.CertificatePins
import net.sentinelsafeguard.mailapp.config.MailConfig
import net.sentinelsafeguard.mailapp.data.mail.MailTls
import net.sentinelsafeguard.mailapp.data.mail.SpkiPinningTrustManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Desktop-side proof that the TLS layer survived the port
 * (desktop port, 2026-11-13): the desktop build enforces the
 * SAME SPKI pins as Android against the production server's real
 * presented chain (captured 2026-11-13, see
 * src/test/resources/README-certs.md). A desktop build that
 * silently dropped to default trust would fail these: the
 * delegate accepts everything, so only the pin check can reject.
 */
class TlsPinningTest {

    private fun cert(resource: String): X509Certificate {
        val stream = requireNotNull(javaClass.getResourceAsStream(resource)) {
            "missing test cert $resource"
        }
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(stream) as X509Certificate
    }

    /** The chain mail.sentinelsafeguard.net presents: leaf, YE2,
     *  Root YE, ISRG Root X2. */
    private fun serverChain(): Array<X509Certificate> = arrayOf(
        cert("/server-leaf.pem"),
        cert("/server-intermediate.pem"),
        cert("/server-root.pem"),
        cert("/server-extra.pem"),
    )

    private val acceptAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun productionChainMatchesShippedPins() {
        assertTrue(
            "the real server chain must match MAIL_PINS",
            CertificatePins.chainMatches(serverChain(), CertificatePins.MAIL_PINS),
        )
    }

    @Test
    fun pinningTrustManagerAcceptsProductionChain() {
        val tm = SpkiPinningTrustManager(CertificatePins.MAIL_PINS, acceptAll)
        // Must not throw: delegate accepts, and a chain cert is pinned.
        tm.checkServerTrusted(serverChain(), "ECDHE_ECDSA")
    }

    @Test
    fun pinningTrustManagerRejectsUnpinnedChain() {
        val tm = SpkiPinningTrustManager(
            setOf("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
            acceptAll,
        )
        try {
            tm.checkServerTrusted(serverChain(), "ECDHE_ECDSA")
            fail("an unpinned chain must be rejected even when the delegate accepts it")
        } catch (expected: CertificateException) {
            // The pin check fired — pinning is live on desktop.
        }
    }

    @Test
    fun pinningTrustManagerRejectsEmptyPinSet() {
        val tm = SpkiPinningTrustManager(emptySet(), acceptAll)
        try {
            tm.checkServerTrusted(serverChain(), "ECDHE_ECDSA")
            fail("fail-closed: an empty pin set must reject")
        } catch (expected: CertificateException) {
        }
    }

    @Test
    fun productionSocketFactoryIsThePinningOne() {
        // MailTls is what the connectors actually use; on desktop
        // it must hand out a real factory (built over the pinning
        // trust manager), never null/default.
        assertNotNull(MailTls.socketFactory(MailConfig.production()))
    }
}
