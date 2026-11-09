package net.sentinelsafeguard.mailapp

import net.sentinelsafeguard.mailapp.config.CertificatePins
import net.sentinelsafeguard.mailapp.config.MailConfig
import net.sentinelsafeguard.mailapp.data.mail.SpkiPinningTrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The production mail posture, pinned: the verified Sentinel Mail
 * endpoints, TLS always required, the two anchor pins, and a
 * codebase-wide absence of the cleartext escape hatches.
 */
class MailConfigTest {

    @Test
    fun productionDefaultsAreTheVerifiedSentinelMailValues() {
        val config = MailConfig.production()
        assertEquals("mail.sentinelsafeguard.net", config.host)
        assertEquals(993, config.imapPort)
        assertEquals(587, config.smtpPort)
        assertTrue("production mail is TLS-required, always", config.tlsRequired)
    }

    @Test
    fun productionPinsAreTheTwoLetsEncryptAnchors() {
        assertEquals(
            setOf(
                // ISRG Root YE
                "sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=",
                // ISRG Root X2
                "diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=",
            ),
            CertificatePins.MAIL_PINS,
        )
        assertEquals(CertificatePins.MAIL_PINS, MailConfig.production().spkiPins)
    }

    @Test
    fun emptyPinSetNeverMatchesAChain() {
        assertFalse(CertificatePins.chainMatches(emptyArray(), emptySet()))
        assertFalse(CertificatePins.chainMatches(emptyArray(), CertificatePins.MAIL_PINS))
    }

    @Test
    fun pinningTrustManagerRefusesAnEmptyChain() {
        // Default delegate = the platform trust manager; pins = the
        // production anchors. An empty chain must be refused by one
        // layer or the other, never waved through.
        val tm = SpkiPinningTrustManager(CertificatePins.MAIL_PINS)
        var refused = false
        try {
            tm.checkServerTrusted(emptyArray(), "RSA")
        } catch (e: Exception) {
            // CertificateException from the pin layer, or the
            // platform delegate's own refusal — either way the
            // chain is refused, never waved through.
            refused = true
        }
        assertTrue("an empty chain must be refused, never waved through", refused)
    }

    @Test
    fun noCleartextEscapeHatchesAnywhereInTheAppSources() {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        val srcRoot = roots.firstOrNull { it.isDirectory }
            ?: throw AssertionError("main sources not found from ${File("").absolutePath}")
        val banned = listOf(
            "ssl.trust", // the Jakarta trust-all property — gone in the family, stays gone
            "TrustAll",
            "trustAll",
            "X509TrustManager" + "()" , // anonymous trust manager constructions
            "checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}",
            "HostnameVerifier { _, _ -> true }",
            "usesCleartextTraffic=\"true\"",
        )
        val offenders = mutableListOf<String>()
        srcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                for (token in banned) {
                    if (text.contains(token)) offenders += "${file.path}: $token"
                }
            }
        assertTrue("cleartext escape hatches found: $offenders", offenders.isEmpty())

        // The manifest, too.
        val manifests = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        val manifest = manifests.firstOrNull { it.isFile }
            ?: throw AssertionError("manifest not found")
        assertTrue(manifest.readText().contains("android:usesCleartextTraffic=\"false\""))
    }
}
