package net.sentinelsafeguard.mailapp.data.mail

import net.sentinelsafeguard.mailapp.config.CertificatePins
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Chain validation first, pin second — in that order, always.
 *
 * Ported unchanged from the Command App's mail module.
 *
 * The platform's own trust manager decides whether the presented
 * chain is a real, trusted chain for the host (with hostname
 * verification handled by the socket layer, which Angus configures
 * from `mail.*.ssl.checkserveridentity`). Only if the platform
 * accepts the chain does this manager additionally require that one
 * of the presented certificates carries one of [pins] as its SPKI.
 * A pin can therefore only ever make validation STRICTER, never
 * weaker — it is not a trust override, and it is the replacement
 * for the `mail.imap.ssl` trust property this class's introduction
 * removed (that property made Angus skip chain validation entirely
 * for the listed host; see HARDENING.md).
 */
class SpkiPinningTrustManager(
    private val pins: Set<String>,
    private val delegate: X509TrustManager = platformTrustManager(),
) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        // 1. The platform decides the chain is genuine. Any rejection
        //    propagates untouched, pin or no pin.
        delegate.checkServerTrusted(chain, authType)
        // 2. Then the pin must match one presented certificate.
        if (!CertificatePins.chainMatches(chain, pins)) {
            throw CertificateException(
                "Certificate pinning failure: no certificate in the presented " +
                    "chain matches the pinned mail-server anchors."
            )
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    companion object {
        /** The platform default trust manager (system trust store). */
        fun platformTrustManager(): X509TrustManager {
            val factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}
