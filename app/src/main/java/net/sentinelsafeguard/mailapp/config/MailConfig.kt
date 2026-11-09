package net.sentinelsafeguard.mailapp.config

/**
 * Where the app connects, and how. The production values are the
 * verified Sentinel Mail settings (confirmed against the live
 * server 2026-11-09, and identical to the Command App's working
 * mail configuration):
 *
 *   host        mail.sentinelsafeguard.net
 *   IMAP        993, implicit TLS (port 143 is disabled server-side)
 *   SMTP        587, STARTTLS required (submission); 465 also live
 *
 * [tlsRequired] is true in production and there is NO production
 * code path that builds a config with it false: plaintext exists
 * only so the rehearsal harness (a separate JVM program that is
 * never shipped) can drive this same engine against a local fake
 * server on loopback. A unit test pins the production defaults so
 * this seam can never quietly become a cleartext mode in the app.
 */
data class MailConfig(
    val host: String,
    val imapPort: Int,
    val smtpPort: Int,
    val tlsRequired: Boolean,
    val spkiPins: Set<String>,
) {
    companion object {
        fun production(host: String = "mail.sentinelsafeguard.net"): MailConfig =
            MailConfig(
                host = host,
                imapPort = 993,
                smtpPort = 587,
                tlsRequired = true,
                spkiPins = CertificatePins.MAIL_PINS,
            )

        /** Loopback rehearsal ONLY — never referenced by app code. */
        fun rehearsalLoopback(imapPort: Int, smtpPort: Int): MailConfig =
            MailConfig(
                host = "127.0.0.1",
                imapPort = imapPort,
                smtpPort = smtpPort,
                tlsRequired = false,
                spkiPins = emptySet(),
            )
    }
}
