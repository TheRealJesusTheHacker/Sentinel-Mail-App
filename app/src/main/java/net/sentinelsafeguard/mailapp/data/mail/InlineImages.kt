package net.sentinelsafeguard.mailapp.data.mail

/**
 * Inline (CID) images for the formatted view (1.2.0) — pure
 * text mechanics over the message's own parts.
 *
 * HTML mail references its embedded images as src="cid:…".
 * 1.0.0's formatted view let the sanitizer keep those
 * references but nothing could resolve them — the images were
 * simply broken. Now the engine fetches the message's OWN
 * inline parts (they arrived with the message; nothing is
 * fetched from the network), and this helper rewrites each
 * cid: reference to a data: URI carrying those bytes, BEFORE
 * the result passes through HtmlSanitizer (which permits
 * data:image sources). Remote http(s) images are untouched —
 * they stay blocked until the reader loads them, per the
 * privacy rule.
 */
object InlineImages {

    /** One inline part as the engine fetched it. [bytes] are the
     * decoded part bytes, capped by the engine. */
    data class Part(
        val mimeType: String,
        val bytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Part && mimeType == other.mimeType && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * mimeType.hashCode() + bytes.contentHashCode()
    }

    private val CID_REF = Regex("(?i)cid:([^\"'\\s>]+)")

    /** Content-ID references present in [html], normalized
     * (angle brackets stripped, as senders write them both ways). */
    fun referencedCids(html: String): Set<String> =
        CID_REF.findAll(html).map { normalize(it.groupValues[1]) }.toSet()

    fun normalize(cid: String): String = cid.trim().trim('<', '>')

    /**
     * Rewrite cid: references in [html] to data: URIs for the
     * parts in [parts] (keyed by Content-ID as the sender wrote
     * it, brackets or not). References with no matching part are
     * left as-is — the sanitizer keeps them and the view shows
     * its broken-image frame, which is the honest outcome for a
     * part the sender referenced but did not include.
     */
    fun inlineIntoHtml(html: String, parts: Map<String, Part>): String {
        if (parts.isEmpty()) return html
        val byNormalized = parts.entries.associate { (cid, part) -> normalize(cid) to part }
        return CID_REF.replace(html) { match ->
            val part = byNormalized[normalize(match.groupValues[1])]
            if (part == null) {
                match.value
            } else {
                val b64 = java.util.Base64.getMimeEncoder()
                    .encodeToString(part.bytes)
                    .replace("\r", "")
                    .replace("\n", "")
                "data:${part.mimeType};base64,$b64"
            }
        }
    }
}
