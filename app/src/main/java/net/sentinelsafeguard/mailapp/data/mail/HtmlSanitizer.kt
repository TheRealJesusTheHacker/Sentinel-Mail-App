package net.sentinelsafeguard.mailapp.data.mail

/**
 * The formatted view's gatekeeper. The family's default rendering
 * is plain text (see MailText); an HTML part reaches a screen only
 * after this sanitizer has rebuilt it tag by tag, and only inside
 * a WebView with JavaScript disabled, no file/content access, and
 * remote loads blocked by default (the view layer enforces those;
 * see HARDENING.md).
 *
 * The rebuild is a whitelist, not a blacklist:
 *  - tags not on the list are dropped (their text survives, except
 *    script/style/head/title/iframe/object/embed/form content,
 *    which is dropped whole);
 *  - the only attributes that survive are a[href] (http, https,
 *    mailto only), img[src] (http, https, cid, data:image only)
 *    and img[alt], td/th[colspan,rowspan];
 *  - every event handler, style, class, id, and URL trick
 *    (javascript:, vbscript:, data:text/html) is gone by
 *    construction — nothing unexamined is ever copied across.
 *
 * Pure and unit-tested directly.
 */
object HtmlSanitizer {

    private val DROP_WHOLE = setOf(
        "script", "style", "head", "title", "iframe", "object", "embed",
        "form", "button", "input", "textarea", "select", "option",
        "link", "meta", "base", "svg", "math", "video", "audio", "source",
        "noscript", "template",
    )

    private val ALLOWED = setOf(
        "a", "abbr", "b", "blockquote", "br", "caption", "cite", "code",
        "dd", "del", "div", "dl", "dt", "em", "h1", "h2", "h3", "h4",
        "h5", "h6", "hr", "i", "img", "ins", "li", "ol", "p", "pre",
        "q", "s", "small", "span", "strong", "sub", "sup", "table",
        "tbody", "td", "tfoot", "th", "thead", "tr", "u", "ul",
    )

    private val VOID = setOf("br", "hr", "img")

    /** DROP_WHOLE tags that are HTML void elements: they never
     * have content or a closing tag, so they must not move the
     * drop-depth counter — an unclosed <input> inside a dropped
     * <form> would otherwise swallow the rest of the message. */
    private val DROP_VOID = setOf("base", "embed", "input", "link", "meta", "source")

    fun sanitize(html: String): String {
        val out = StringBuilder(html.length)
        var i = 0
        val n = html.length
        var dropDepth = 0 // inside a DROP_WHOLE element
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                if (dropDepth == 0) out.append(escapeText(html.substring(i)))
                break
            }
            if (dropDepth == 0 && lt > i) out.append(escapeText(html.substring(i, lt)))
            // A '<' that cannot start a tag (not followed by a
            // letter, '/', '!', or '?') is literal text — HTML's
            // own rule; treating it as a tag would swallow the
            // text that follows it.
            val after = html.getOrNull(lt + 1)
            if (after == null || !(after.isLetter() || after == '/' || after == '!' || after == '?')) {
                if (dropDepth == 0) out.append("&lt;")
                i = lt + 1
                continue
            }
            // Comments and declarations: dropped.
            if (html.startsWith("<!--", lt)) {
                val end = html.indexOf("-->", lt + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            val gt = findTagEnd(html, lt)
            if (gt < 0) {
                if (dropDepth == 0) out.append(escapeText(html.substring(lt)))
                break
            }
            val rawTag = html.substring(lt + 1, gt).trim()
            i = gt + 1
            if (rawTag.isEmpty() || rawTag.startsWith("!") || rawTag.startsWith("?")) continue
            val closing = rawTag.startsWith("/")
            val selfClosing = rawTag.endsWith("/")
            val namePart = (if (closing) rawTag.substring(1) else rawTag.removeSuffix("/")).trim()
            val name = namePart.takeWhile { it.isLetterOrDigit() }.lowercase()
            if (name.isEmpty()) continue
            if (name in DROP_WHOLE) {
                if (closing) {
                    if (dropDepth > 0 && name !in DROP_VOID) dropDepth--
                } else if (!selfClosing && name !in DROP_VOID) {
                    dropDepth++
                }
                continue
            }
            if (dropDepth > 0) continue
            if (name !in ALLOWED) continue // unknown tag: drop the tag, keep its text
            if (closing) {
                if (name !in VOID) out.append("</").append(name).append('>')
                continue
            }
            out.append('<').append(name)
            out.append(keptAttributes(name, namePart))
            if (name in VOID) out.append(" />") else out.append('>')
        }
        return out.toString()
    }

    private fun findTagEnd(html: String, lt: Int): Int {
        var quote: Char? = null
        var j = lt + 1
        while (j < html.length) {
            val c = html[j]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return j
            }
            j++
        }
        return -1
    }

    private fun keptAttributes(tag: String, namePart: String): String {
        val attrs = parseAttributes(namePart)
        val out = StringBuilder()
        when (tag) {
            "a" -> attrs["href"]?.let { href ->
                if (isSafeLink(href)) out.append(" href=\"").append(escapeAttr(href)).append('"')
            }
            "img" -> {
                attrs["src"]?.let { src ->
                    if (isSafeImage(src)) out.append(" src=\"").append(escapeAttr(src)).append('"')
                }
                attrs["alt"]?.let { alt ->
                    out.append(" alt=\"").append(escapeAttr(alt)).append('"')
                }
            }
            "td", "th" -> {
                for (span in listOf("colspan", "rowspan")) {
                    attrs[span]?.toIntOrNull()?.takeIf { it in 1..50 }?.let {
                        out.append(' ').append(span).append("=\"").append(it).append('"')
                    }
                }
            }
        }
        return out.toString()
    }

    private fun parseAttributes(namePart: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val rest = namePart.dropWhile { it.isLetterOrDigit() }
        val regex = Regex("""([a-zA-Z-]+)\s*(?:=\s*("[^"]*"|'[^']*'|[^\s"'>]+))?""")
        for (m in regex.findAll(rest)) {
            val key = m.groupValues[1].lowercase()
            var value = m.groupValues.getOrNull(2) ?: ""
            if (value.length >= 2 &&
                ((value.first() == '"' && value.last() == '"') ||
                    (value.first() == '\'' && value.last() == '\''))
            ) {
                value = value.substring(1, value.length - 1)
            }
            out[key] = MailText.decodeEntities(value)
        }
        return out
    }

    private fun isSafeLink(href: String): Boolean {
        val h = href.trim().lowercase()
        return h.startsWith("http://") || h.startsWith("https://") || h.startsWith("mailto:")
    }

    private fun isSafeImage(src: String): Boolean {
        val s = src.trim().lowercase()
        return s.startsWith("http://") || s.startsWith("https://") ||
            s.startsWith("cid:") || s.startsWith("data:image/")
    }

    private fun escapeText(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeAttr(s: String): String =
        escapeText(s).replace("\"", "&quot;")
}
