package net.sentinelsafeguard.mailapp.config

import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * SPKI certificate pins (SHA-256 of the SubjectPublicKeyInfo, the
 * standard "sha256/…" pin format), and the code that checks a
 * presented chain against them.
 *
 * Ported unchanged in substance from the Command App's
 * config/CertificatePins.kt — the same server, the same anchors.
 *
 * MAIL host — pinned, anchor-style. The pins are the two CA anchors
 * in the chain the live mail server actually presents, computed from
 * certificates fetched from mail.sentinelsafeguard.net:993 on
 * 2026-10-25 (Let's Encrypt "Generation Y" hierarchy) and
 * RE-VERIFIED byte-for-byte against the live chain on 2026-11-09
 * during this app's build:
 *
 *   leaf  CN=mail.sentinelsafeguard.net   (renews ~90 days — NOT pinned)
 *     ← YE2 intermediate                  (rotates by design — NOT pinned)
 *     ← ISRG Root YE                      PINNED
 *     ← ISRG Root X2 (cross-signed by X1) PINNED
 *
 * Anchor pinning means leaf renewals and intermediate rotations
 * change nothing: any future chain that still runs through either
 * root key matches. Both roots are in the presented chain today
 * (verified pin agreement across independent fetches on both
 * occasions), so either alone is sufficient and the pair is the
 * backup for each other. See HARDENING.md for the full provenance
 * and the rotation procedure.
 */
object CertificatePins {

    /** Pins for mail.sentinelsafeguard.net (SPKI SHA-256, base64). */
    val MAIL_PINS: Set<String> = setOf(
        // ISRG Root YE
        "sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=",
        // ISRG Root X2
        "diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=",
    )

    /** The pin of one certificate: Base64(SHA-256(SPKI DER)). */
    fun spkiPin(certificate: Certificate): String {
        val spki = (certificate as X509Certificate).publicKey.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(spki)
        return Base64.getEncoder().encodeToString(digest)
    }

    /** True when any certificate in [chain] carries a pinned SPKI. */
    fun chainMatches(chain: Array<out Certificate>, pins: Set<String>): Boolean {
        if (pins.isEmpty()) return false
        return chain.any { spkiPin(it) in pins }
    }
}
