package net.sentinelsafeguard.mailapp.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import net.sentinelsafeguard.mailapp.data.mail.MailText
import net.sentinelsafeguard.mailapp.ui.theme.SentinelGreen

// ---------------------------------------------------------------------------
// HtmlBlocks — the desktop formatted view's block model (desktop
// port, 2026-11-13).
//
// The phone renders the sanitized HTML part in a locked-down
// WebView; Compose Desktop has no WebView, so the desktop port
// renders the SAME sanitized output natively: HtmlSanitizer's
// whitelist rebuild (already applied by the caller, after the
// message's own CID images are inlined) is parsed here into a
// block model — paragraphs, headings, lists, quotes, code,
// tables, images, rules — with inline spans for emphasis and
// links. Nothing outside the sanitizer's whitelist can appear:
// the parser only knows the tags the sanitizer can emit, and
// unknown tags are dropped with their markup (text survives).
//
// Remote-image policy is the caller's, unchanged: http(s) image
// blocks are HANDED to the renderer, which fetches them only
// when the reader has loaded images for this message; data:
// images (the message's own bytes) render directly.
// ---------------------------------------------------------------------------

sealed interface HtmlBlock {
    data class Heading(val level: Int, val text: AnnotatedString) : HtmlBlock
    data class Paragraph(val text: AnnotatedString) : HtmlBlock
    data class Quote(val text: AnnotatedString) : HtmlBlock
    data class Pre(val text: String) : HtmlBlock
    data class ListBlock(val ordered: Boolean, val items: List<AnnotatedString>) : HtmlBlock
    data class ImageBlock(val src: String, val alt: String) : HtmlBlock
    data class TableBlock(val rows: List<List<AnnotatedString>>) : HtmlBlock
    data object Rule : HtmlBlock
}

object HtmlBlocks {

    private val BLOCK_TAGS = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote",
        "pre", "ul", "ol", "table", "hr",
    )

    fun parse(sanitizedHtml: String): List<HtmlBlock> {
        val parser = Parser(sanitizedHtml)
        return parser.parseBlocks(stopAt = emptySet())
    }

    /** One pass over the token stream. Forgiving by design: the
     *  sanitizer's output is a rebuild, not a validated document,
     *  so unclosed and mis-nested tags degrade to text, never to
     *  a crash. */
    private class Parser(private val html: String) {
        private var pos = 0

        private sealed interface Tok {
            data class Text(val value: String) : Tok
            data class Tag(
                val name: String,
                val closing: Boolean,
                val attrs: Map<String, String>,
            ) : Tok
        }

        private fun next(): Tok? {
            if (pos >= html.length) return null
            val lt = html.indexOf('<', pos)
            if (lt < 0) {
                val t = html.substring(pos); pos = html.length
                return Tok.Text(t)
            }
            if (lt > pos) {
                val t = html.substring(pos, lt); pos = lt
                return Tok.Text(t)
            }
            val gt = findTagEnd(lt)
            if (gt < 0) {
                val t = html.substring(pos); pos = html.length
                return Tok.Text(t)
            }
            val raw = html.substring(lt + 1, gt).trim()
            pos = gt + 1
            if (raw.isEmpty() || raw.startsWith("!") || raw.startsWith("?")) return next()
            val closing = raw.startsWith("/")
            val namePart = (if (closing) raw.substring(1) else raw.removeSuffix("/")).trim()
            val name = namePart.takeWhile { it.isLetterOrDigit() }.lowercase()
            if (name.isEmpty()) return next()
            return Tok.Tag(name, closing, parseAttrs(namePart))
        }

        private fun findTagEnd(lt: Int): Int {
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

        private fun parseAttrs(namePart: String): Map<String, String> {
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

        /** Parse blocks until EOF or one of [stopAt] closing tags. */
        fun parseBlocks(stopAt: Set<String>): List<HtmlBlock> {
            val blocks = mutableListOf<HtmlBlock>()
            var inline = InlineSink()
            fun flush() {
                val text = inline.finish()
                if (text.text.isNotBlank()) blocks += HtmlBlock.Paragraph(text)
                inline = InlineSink()
            }
            while (true) {
                val tok = next() ?: break
                when (tok) {
                    is Tok.Text -> inline.text(tok.value)
                    is Tok.Tag -> {
                        if (tok.closing) {
                            if (tok.name in stopAt) {
                                flush()
                                return blocks
                            }
                            inline.closeTag(tok.name)
                            continue
                        }
                        when (tok.name) {
                            "br" -> inline.text("\n")
                            "hr" -> {
                                flush(); blocks += HtmlBlock.Rule
                            }
                            "img" -> {
                                flush()
                                val src = tok.attrs["src"].orEmpty()
                                if (src.isNotEmpty()) {
                                    blocks += HtmlBlock.ImageBlock(
                                        src, tok.attrs["alt"].orEmpty(),
                                    )
                                }
                            }
                            "p", "div" -> {
                                flush()
                                val inner = collectInline(stopAt = setOf(tok.name))
                                if (inner.text.isNotBlank()) {
                                    blocks += HtmlBlock.Paragraph(inner)
                                }
                            }
                            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                flush()
                                val inner = collectInline(stopAt = setOf(tok.name))
                                if (inner.text.isNotBlank()) {
                                    blocks += HtmlBlock.Heading(
                                        tok.name.removePrefix("h").toIntOrNull() ?: 2, inner,
                                    )
                                }
                            }
                            "blockquote" -> {
                                flush()
                                val inner = collectBlocksAsInline(stopAt = setOf("blockquote"))
                                if (inner.text.isNotBlank()) {
                                    blocks += HtmlBlock.Quote(inner)
                                }
                            }
                            "pre" -> {
                                flush()
                                val raw = collectRawText(stopAt = "pre")
                                if (raw.isNotBlank()) blocks += HtmlBlock.Pre(raw.trim('\n'))
                            }
                            "ul", "ol" -> {
                                flush()
                                val items = collectListItems(
                                    ordered = tok.name == "ol",
                                    stopTag = tok.name,
                                )
                                if (items.isNotEmpty()) {
                                    blocks += HtmlBlock.ListBlock(tok.name == "ol", items)
                                }
                            }
                            "table" -> {
                                flush()
                                val rows = collectTable(stopTag = "table")
                                if (rows.isNotEmpty()) blocks += HtmlBlock.TableBlock(rows)
                            }
                            else -> inline.openTag(tok.name, tok.attrs)
                        }
                    }
                }
            }
            flush()
            return blocks
        }

        /** Inline content until the matching close (used for p/div/headings). */
        private fun collectInline(stopAt: Set<String>): AnnotatedString {
            val sink = InlineSink()
            while (true) {
                val tok = next() ?: break
                when (tok) {
                    is Tok.Text -> sink.text(tok.value)
                    is Tok.Tag -> {
                        if (tok.closing) {
                            if (tok.name in stopAt) return sink.finish()
                            sink.closeTag(tok.name)
                        } else {
                            when (tok.name) {
                                "br" -> sink.text("\n")
                                "img" -> {
                                    // An image inside a paragraph: its
                                    // alt text stands in line; the block
                                    // renderer owns standalone images.
                                    val alt = tok.attrs["alt"].orEmpty()
                                    if (alt.isNotBlank()) sink.text("[$alt]")
                                }
                                in BLOCK_TAGS -> {
                                    // A block start inside an inline
                                    // context: treat as a soft break.
                                    sink.text("\n")
                                }
                                else -> sink.openTag(tok.name, tok.attrs)
                            }
                        }
                    }
                }
            }
            return sink.finish()
        }

        /** Blocks flattened to inline (blockquote bodies). */
        private fun collectBlocksAsInline(stopAt: Set<String>): AnnotatedString {
            val inner = parseBlocks(stopAt)
            return buildAnnotatedString {
                inner.forEachIndexed { idx, block ->
                    if (idx > 0) append("\n\n")
                    when (block) {
                        is HtmlBlock.Paragraph -> append(block.text)
                        is HtmlBlock.Heading -> append(block.text)
                        is HtmlBlock.Quote -> append(block.text)
                        is HtmlBlock.Pre -> append(block.text)
                        is HtmlBlock.ListBlock ->
                            block.items.forEachIndexed { i, item ->
                                if (i > 0) append("\n")
                                append("• ")
                                append(item)
                            }
                        is HtmlBlock.ImageBlock ->
                            if (block.alt.isNotBlank()) append("[${block.alt}]")
                        is HtmlBlock.TableBlock ->
                            block.rows.forEachIndexed { r, row ->
                                if (r > 0) append("\n")
                                append(row.joinToString("  ") { it.text })
                            }
                        HtmlBlock.Rule -> append("—")
                    }
                }
            }
        }

        private fun collectRawText(stopAt: String): String {
            val out = StringBuilder()
            while (true) {
                val tok = next() ?: break
                when (tok) {
                    is Tok.Text -> out.append(MailText.decodeEntities(tok.value))
                    is Tok.Tag -> {
                        if (tok.closing && tok.name == stopAt) return out.toString()
                        if (!tok.closing && tok.name == "br") out.append("\n")
                    }
                }
            }
            return out.toString()
        }

        private fun collectListItems(ordered: Boolean, stopTag: String): List<AnnotatedString> {
            val items = mutableListOf<AnnotatedString>()
            while (true) {
                val tok = next() ?: break
                if (tok is Tok.Tag && tok.closing && tok.name == stopTag) break
                if (tok is Tok.Tag && !tok.closing && tok.name == "li") {
                    val item = collectInline(stopAt = setOf("li"))
                    if (item.text.isNotBlank()) items += item
                }
                // Any other content directly inside a list is
                // ignored markup-wise; item text lives in <li>.
            }
            return items
        }

        private fun collectTable(stopTag: String): List<List<AnnotatedString>> {
            val rows = mutableListOf<List<AnnotatedString>>()
            var row: MutableList<AnnotatedString>? = null
            while (true) {
                val tok = next() ?: break
                if (tok !is Tok.Tag) continue
                when {
                    tok.closing && tok.name == stopTag -> {
                        row?.let { if (it.isNotEmpty()) rows += it }
                        return rows
                    }
                    !tok.closing && tok.name == "tr" -> {
                        row?.let { if (it.isNotEmpty()) rows += it }
                        row = mutableListOf()
                    }
                    tok.closing && tok.name == "tr" -> {
                        row?.let { if (it.isNotEmpty()) rows += it }
                        row = null
                    }
                    !tok.closing && (tok.name == "td" || tok.name == "th") -> {
                        val cell = collectInline(stopAt = setOf("td", "th"))
                        (row ?: mutableListOf<AnnotatedString>().also { row = it }) += cell
                    }
                }
            }
            row?.let { if (it.isNotEmpty()) rows += it }
            return rows
        }
    }

    /** Accumulates inline spans with a style stack. Whitespace is
     *  collapsed outside <pre> (the renderer owns pre separately),
     *  matching how the WebView would lay the same HTML out. */
    private class InlineSink {
        private data class Frame(val tag: String, val href: String? = null)

        private val stack = mutableListOf<Frame>()
        private var pendingSpace = false
        private var atLineStart = true

        fun text(raw: String) {
            val decoded = MailText.decodeEntities(raw)
            for (ch in decoded) {
                when {
                    ch == '\n' -> {
                        appendStyled("\n")
                        atLineStart = true
                        pendingSpace = false
                    }
                    ch.isWhitespace() -> {
                        if (!atLineStart) pendingSpace = true
                    }
                    else -> {
                        if (pendingSpace) {
                            appendStyled(" ")
                            pendingSpace = false
                        }
                        appendStyled(ch.toString())
                        atLineStart = false
                    }
                }
            }
        }

        private data class Segment(val text: String, val style: SpanStyle, val href: String?)

        private val segments = mutableListOf<Segment>()

        private fun appendStyled(s: String) {
            var style = SpanStyle()
            var href: String? = null
            for (frame in stack) {
                when (frame.tag) {
                    "b", "strong" -> style = style.merge(SpanStyle(fontWeight = FontWeight.Bold))
                    "i", "em", "cite" -> style = style.merge(SpanStyle(fontStyle = FontStyle.Italic))
                    "u", "ins" -> style = style.merge(SpanStyle(textDecoration = TextDecoration.Underline))
                    "s", "del" -> style = style.merge(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    "code" -> style = style.merge(SpanStyle(fontFamily = FontFamily.Monospace))
                    "a" -> {
                        style = style.merge(
                            SpanStyle(
                                color = SentinelGreen,
                                textDecoration = TextDecoration.Underline,
                            ),
                        )
                        href = frame.href
                    }
                }
            }
            val last = segments.lastOrNull()
            if (last != null && last.style == style && last.href == href) {
                segments[segments.size - 1] = Segment(last.text + s, style, href)
            } else {
                segments += Segment(s, style, href)
            }
        }

        fun openTag(name: String, attrs: Map<String, String>) {
            when (name) {
                "b", "strong", "i", "em", "cite", "u", "ins", "s", "del", "code",
                "small", "span", "abbr", "sub", "sup",
                ->
                    stack += Frame(name)
                "a" -> stack += Frame(name, attrs["href"])
                "q" -> {
                    stack += Frame(name)
                    text("“")
                }
            }
        }

        fun closeTag(name: String) {
            if (name == "q") text("”")
            val idx = stack.indexOfLast { it.tag == name }
            if (idx >= 0) {
                while (stack.size > idx) stack.removeAt(stack.size - 1)
            }
        }

        fun finish(): AnnotatedString {
            // Trim trailing whitespace/newlines the sink accumulated.
            val segs = segments.toMutableList()
            while (segs.isNotEmpty()) {
                val trimmed = segs.last().text.trimEnd()
                if (trimmed.isEmpty()) {
                    segs.removeAt(segs.size - 1)
                } else {
                    segs[segs.size - 1] = segs.last().copy(text = trimmed)
                    break
                }
            }
            return buildAnnotatedString {
                for (seg in segs) {
                    if (seg.href != null) {
                        pushStringAnnotation("URL", seg.href)
                        withStyle(seg.style) { append(seg.text) }
                        pop()
                    } else if (seg.style != SpanStyle()) {
                        withStyle(seg.style) { append(seg.text) }
                    } else {
                        append(seg.text)
                    }
                }
            }
        }
    }
}
