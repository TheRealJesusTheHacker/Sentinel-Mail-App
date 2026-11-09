package net.sentinelsafeguard.mailapp.data.mail

import net.sentinelsafeguard.mailapp.config.MailConfig
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

/**
 * The mail stack's TLS entry point: an [SSLSocketFactory] whose
 * trust manager is the platform default PLUS the SPKI anchor pins
 * for the mail host ([SpkiPinningTrustManager]).
 *
 * Ported from the Command App's MailTls, with the pins arriving via
 * [MailConfig] instead of a global so the rehearsal harness can
 * build a loopback factory without touching production wiring.
 *
 * MailService hands this factory to Angus through the
 * `mail.<protocol>.ssl.socketFactory` session property as an object.
 * That property is the one Angus consults first for both kinds of
 * connection this app makes — implicit TLS (IMAP 993) and the
 * STARTTLS upgrade (SMTP 587) — verified against the Angus 2.0.3
 * SocketFetcher source when the family engine was written (see
 * HARDENING.md).
 */
object MailTls {

    fun socketFactory(config: MailConfig): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(
            null,
            arrayOf(SpkiPinningTrustManager(config.spkiPins)),
            null,
        )
        return context.socketFactory
    }
}
