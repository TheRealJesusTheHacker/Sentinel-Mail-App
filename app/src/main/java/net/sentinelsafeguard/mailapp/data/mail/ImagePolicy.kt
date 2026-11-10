package net.sentinelsafeguard.mailapp.data.mail

/**
 * Remote-image privacy decisions (1.2.0) — pure logic.
 *
 * The rule from 1.0.0 stands: remote images are blocked by
 * default, because an image fetch is a phone-home. The reader
 * can load them for ONE message, or remember the choice per
 * SENDER. Senders are keyed by bare address, normalized the
 * same way everywhere the policy is consulted.
 */
object ImagePolicy {

    /** The identity a sender is remembered by: bare address,
     * lowercase ("Client <Client@Example.com>" → client@example.com). */
    fun senderKey(fromField: String): String =
        MailText.extractAddress(fromField).trim().lowercase()

    /** True when this sender has an always-load memory. */
    fun shouldAutoLoad(fromField: String, alwaysLoadSenders: Set<String>): Boolean {
        val key = senderKey(fromField)
        return key.isNotEmpty() && key in alwaysLoadSenders
    }

    fun remember(alwaysLoadSenders: Set<String>, fromField: String): Set<String> {
        val key = senderKey(fromField)
        return if (key.isEmpty()) alwaysLoadSenders else alwaysLoadSenders + key
    }

    fun forget(alwaysLoadSenders: Set<String>, fromField: String): Set<String> =
        alwaysLoadSenders - senderKey(fromField)
}
