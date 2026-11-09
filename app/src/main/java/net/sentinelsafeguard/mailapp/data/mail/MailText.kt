package net.sentinelsafeguard.mailapp.data.mail

/**
 * Pure text mechanics for mail — no mail library, unit-tested
 * directly. The extraction preference and the HTML fallback are
 * ported from the Command App engine's extractText: plain text is
 * the mail; HTML is only ever a source to derive text from (or, in
 * the formatted view, to sanitize — see HtmlSanitizer).
 */
object MailText {

    /** HTML → readable text, the family fallback: tags become
     * spaces, entities are decoded, whitespace is collapsed. */
    fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</p>\\s*"), "\n\n")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = decodeEntities(s)
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex("\\n[ \\t]+"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    fun decodeEntities(s: String): String =
        s.replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    /** A one-line list snippet: first meaningful text, collapsed,
     * capped. Empty when there is nothing worth showing. */
    fun makeSnippet(text: String, maxChars: Int = 140): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= maxChars) return flat
        val cut = flat.substring(0, maxChars)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > maxChars / 2) cut.substring(0, lastSpace) else cut).trimEnd() + "…"
    }

    /** "Re: X" unless X already carries the prefix. */
    fun replySubject(subject: String): String =
        if (subject.startsWith("Re:", ignoreCase = true)) subject else "Re: $subject"

    /** "Fwd: X" unless X already carries a forward prefix. */
    fun forwardSubject(subject: String): String =
        if (subject.startsWith("Fwd:", ignoreCase = true) ||
            subject.startsWith("Fw:", ignoreCase = true)
        ) {
            subject
        } else {
            "Fwd: $subject"
        }

    /** The bare address out of a formatted "Name <a@b>" string. */
    fun extractAddress(formatted: String): String {
        val match = Regex("<([^>]+)>").find(formatted)
        return match?.groupValues?.get(1)?.trim() ?: formatted.trim()
    }

    /** Split a compose address field into addresses (comma or
     * semicolon separated), trimmed, blanks dropped. */
    fun parseAddressList(field: String): List<String> =
        field.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

    /** A light shape check for one address — the server remains
     * the real validator; this only catches obvious typos early. */
    fun looksLikeAddress(address: String): Boolean {
        val bare = extractAddress(address)
        val at = bare.indexOf('@')
        if (at <= 0 || at != bare.lastIndexOf('@')) return false
        val domain = bare.substring(at + 1)
        return domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.') &&
            !bare.contains(' ') && bare.length <= 254
    }

    /** The quoted body for a reply — the family's exact format,
     * ported from the Command App's reply builder. */
    fun quoteForReply(newText: String, original: MailMessage): String =
        "$newText\n\n----- Original message -----\nFrom: ${original.from}\n" +
            "Date: ${original.dateText}\nSubject: ${original.subject}\n\n${original.bodyText}"

    /** The forwarded body header block. */
    fun forwardBody(newText: String, original: MailMessage): String {
        val head = "----- Forwarded message -----\nFrom: ${original.from}\n" +
            "Date: ${original.dateText}\nSubject: ${original.subject}\nTo: ${original.to}\n\n"
        return if (newText.isBlank()) head + original.bodyText
        else "$newText\n\n$head${original.bodyText}"
    }
}
