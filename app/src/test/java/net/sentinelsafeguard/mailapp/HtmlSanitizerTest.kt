package net.sentinelsafeguard.mailapp

import net.sentinelsafeguard.mailapp.data.mail.HtmlSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The formatted view's gatekeeper, pinned hard: anything that
 * could execute, phone home by construction, or smuggle a
 * dangerous URL must not survive sanitizing.
 */
class HtmlSanitizerTest {

    @Test
    fun ordinaryFormattingSurvives() {
        val out = HtmlSanitizer.sanitize(
            "<p>Hello <b>world</b></p><ul><li>one</li><li>two</li></ul>",
        )
        assertEquals("<p>Hello <b>world</b></p><ul><li>one</li><li>two</li></ul>", out)
    }

    @Test
    fun scriptsAndHandlersAreGone() {
        val out = HtmlSanitizer.sanitize(
            "<p onclick=\"steal()\">Hi</p><script>alert(document.cookie)</script>" +
                "<img src=\"https://x.example/a.png\" onerror=\"pwn()\">",
        )
        assertFalse(out.contains("script"))
        assertFalse(out.contains("onclick"))
        assertFalse(out.contains("onerror"))
        assertFalse(out.contains("alert"))
        assertTrue(out.contains("<p>Hi</p>"))
        assertTrue(out.contains("src=\"https://x.example/a.png\""))
    }

    @Test
    fun dangerousLinkSchemesAreDropped() {
        val out = HtmlSanitizer.sanitize(
            "<a href=\"javascript:alert(1)\">a</a>" +
                "<a href=\"data:text/html;base64,AAAA\">b</a>" +
                "<a href=\"vbscript:x\">c</a>" +
                "<a href=\"https://example.com/ok\">d</a>" +
                "<a href=\"mailto:a@b.com\">e</a>",
        )
        assertFalse(out.contains("javascript:"))
        assertFalse(out.contains("data:text/html"))
        assertFalse(out.contains("vbscript:"))
        assertTrue(out.contains("href=\"https://example.com/ok\""))
        assertTrue(out.contains("href=\"mailto:a@b.com\""))
    }

    @Test
    fun styleClassAndIdNeverSurvive() {
        val out = HtmlSanitizer.sanitize(
            "<div style=\"background:url(javascript:x)\" class=\"tracker\" id=\"t1\">Body</div>",
        )
        assertEquals("<div>Body</div>", out)
    }

    @Test
    fun embeddedFramesAndFormsAreDroppedWhole() {
        val out = HtmlSanitizer.sanitize(
            "<p>Before</p><iframe src=\"https://evil.example\">inner</iframe>" +
                "<form action=\"https://evil.example\"><input value=\"x\">formtext</form>" +
                "<p>After</p>",
        )
        assertEquals("<p>Before</p><p>After</p>", out)
    }

    @Test
    fun imageSourcesAreSchemeChecked() {
        val out = HtmlSanitizer.sanitize(
            "<img src=\"cid:part1\" alt=\"chart\">" +
                "<img src=\"data:image/png;base64,AAAA\">" +
                "<img src=\"data:text/html;base64,AAAA\">" +
                "<img src=\"file:///etc/passwd\">",
        )
        assertTrue(out.contains("src=\"cid:part1\""))
        assertTrue(out.contains("alt=\"chart\""))
        assertTrue(out.contains("src=\"data:image/png;base64,AAAA\""))
        assertFalse(out.contains("data:text/html"))
        assertFalse(out.contains("file://"))
    }

    @Test
    fun textIsEscapedAndUnknownTagsUnwrap() {
        val out = HtmlSanitizer.sanitize("<marquee>Fish & Chips < 5</marquee>")
        assertEquals("Fish &amp; Chips &lt; 5", out)
    }

    @Test
    fun commentsAndDeclarationsAreDropped() {
        val out = HtmlSanitizer.sanitize("<!DOCTYPE html><!-- tracker --><p>x</p>")
        assertEquals("<p>x</p>", out)
    }

    @Test
    fun tableSpansAreBoundedNumbersOnly() {
        val out = HtmlSanitizer.sanitize(
            "<table><tr><td colspan=\"2\">a</td><td colspan=\"999\">b</td>" +
                "<td rowspan=\"x\">c</td></tr></table>",
        )
        assertTrue(out.contains("colspan=\"2\""))
        assertFalse(out.contains("999"))
        assertFalse(out.contains("rowspan"))
    }

    @Test
    fun attributeEntitiesCannotBreakOut() {
        val out = HtmlSanitizer.sanitize(
            "<a href=\"https://example.com/?q=&quot; onclick=&quot;x\">y</a>",
        )
        // The decoded quote is re-escaped, so the value can never
        // break out and become a live onclick attribute.
        assertFalse(out.contains("\" onclick"))
        assertTrue(out.contains("&quot;"))
    }
}
