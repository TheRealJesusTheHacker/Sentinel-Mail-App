package net.sentinelsafeguard.mailapp.data.mail

/**
 * Compose signatures (1.2.0) — pure text mechanics.
 *
 * A signature is plain text the owner wrote in Settings, one
 * per sending identity (the shared-folder identity rule in
 * MailRules decides WHICH identity a compose session sends as;
 * the signature follows the identity, never the folder name
 * typed anywhere). The conventional "-- " separator line
 * introduces it. Signatures attach to NEW messages and to the
 * fresh text of replies — never inside the quoted history, and
 * never twice: a draft that already carries it does not grow a
 * second one when reopened.
 */
object SignatureText {

    const val SEPARATOR = "-- "

    /** The block to append, or "" when the identity has no
     * signature. Separator line + the owner's text, trimmed. */
    fun block(signature: String): String {
        val text = signature.trim()
        if (text.isEmpty()) return ""
        return "\n\n$SEPARATOR\n$text"
    }

    /** True when [body] already carries this signature — the
     * draft-reopen guard. */
    fun alreadySigned(body: String, signature: String): Boolean {
        val text = signature.trim()
        return text.isNotEmpty() && body.contains(text)
    }

    /** A new message: signature at the end of the body. */
    fun forNew(body: String, signature: String): String {
        if (alreadySigned(body, signature)) return body
        return body + block(signature)
    }

    /** A reply: the signature belongs to the fresh text, above
     * the quoted original — appending it at the very end would
     * sign the OTHER person's words. */
    fun forReply(body: String, signature: String): String {
        if (alreadySigned(body, signature)) return body
        val quoteAt = body.indexOf("\n\n----- Original message -----")
        return if (quoteAt >= 0) {
            body.substring(0, quoteAt) + block(signature) + body.substring(quoteAt)
        } else {
            body + block(signature)
        }
    }
}
