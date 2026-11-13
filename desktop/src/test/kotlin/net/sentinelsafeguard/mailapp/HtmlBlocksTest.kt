package net.sentinelsafeguard.mailapp

import net.sentinelsafeguard.mailapp.data.mail.HtmlSanitizer
import net.sentinelsafeguard.mailapp.ui.HtmlBlock
import net.sentinelsafeguard.mailapp.ui.HtmlBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop formatted view's block model (desktop port,
 * 2026-11-13): the sanitizer's whitelist output parses into the
 * blocks the native renderer draws, with spans and links intact,
 * and nothing outside the whitelist can reach the renderer.
 */
class HtmlBlocksTest {

    @Test
    fun paragraphsAndBold() {
        val blocks = HtmlBlocks.parse("<p>Hello <b>world</b></p><p>Second</p>")
        assertEquals(2, blocks.size)
        val first = blocks[0] as HtmlBlock.Paragraph
        assertEquals("Hello world", first.text.text)
        assertTrue(first.text.spanStyles.any { it.item.fontWeight != null })
        assertEquals("Second", (blocks[1] as HtmlBlock.Paragraph).text.text)
    }

    @Test
    fun listItems() {
        val blocks = HtmlBlocks.parse("<ul><li>one</li><li>two</li></ul>")
        assertEquals(1, blocks.size)
        val list = blocks[0] as HtmlBlock.ListBlock
        assertFalse(list.ordered)
        assertEquals(listOf("one", "two"), list.items.map { it.text })
    }

    @Test
    fun standaloneImageBecomesImageBlock() {
        val blocks = HtmlBlocks.parse(
            "<p>x</p><img src=\"https://example.com/a.png\" alt=\"pic\">",
        )
        val image = blocks.filterIsInstance<HtmlBlock.ImageBlock>().single()
        assertEquals("https://example.com/a.png", image.src)
        assertEquals("pic", image.alt)
    }

    @Test
    fun linkAnnotationSurvives() {
        val blocks = HtmlBlocks.parse(
            "<p>See <a href=\"https://example.org/page\">the site</a></p>",
        )
        val paragraph = blocks.single() as HtmlBlock.Paragraph
        val annotations = paragraph.text.getStringAnnotations("URL", 0, paragraph.text.length)
        assertEquals(listOf("https://example.org/page"), annotations.map { it.item })
    }

    @Test
    fun headingAndQuoteAndPre() {
        val blocks = HtmlBlocks.parse(
            "<h2>Title</h2><blockquote><p>quoted</p></blockquote><pre>a  b\nc</pre>",
        )
        assertTrue(blocks[0] is HtmlBlock.Heading)
        assertEquals(2, (blocks[0] as HtmlBlock.Heading).level)
        assertEquals("quoted", (blocks[1] as HtmlBlock.Quote).text.text)
        assertEquals("a  b\nc", (blocks[2] as HtmlBlock.Pre).text)
    }

    @Test
    fun tableRowsAndCells() {
        val blocks = HtmlBlocks.parse(
            "<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>",
        )
        val table = blocks.single() as HtmlBlock.TableBlock
        assertEquals(2, table.rows.size)
        assertEquals(listOf("A", "B"), table.rows[0].map { it.text })
        assertEquals(listOf("1", "2"), table.rows[1].map { it.text })
    }

    @Test
    fun sanitizerOutputOnly() {
        // End to end: hostile HTML goes through the shared
        // sanitizer first; the block model can only ever contain
        // what the sanitizer let through.
        val dirty = "<script>alert(1)</script>" +
            "<p onclick=\"steal()\">Hi</p>" +
            "<img src=\"https://tracker.example/pixel.png\" onerror=\"x()\">"
        val blocks = HtmlBlocks.parse(HtmlSanitizer.sanitize(dirty))
        val texts = blocks.joinToString(" ") {
            when (it) {
                is HtmlBlock.Paragraph -> it.text.text
                is HtmlBlock.ImageBlock -> it.src
                else -> ""
            }
        }
        assertFalse(texts.contains("alert"))
        assertFalse(texts.contains("steal"))
        assertTrue(texts.contains("Hi"))
    }

    @Test
    fun entitiesDecode() {
        val blocks = HtmlBlocks.parse("<p>Fish &amp; Chips &lt;3</p>")
        assertEquals("Fish & Chips <3", (blocks.single() as HtmlBlock.Paragraph).text.text)
    }
}
