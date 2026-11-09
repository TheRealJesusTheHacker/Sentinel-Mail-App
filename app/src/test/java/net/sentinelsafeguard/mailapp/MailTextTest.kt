package net.sentinelsafeguard.mailapp

import net.sentinelsafeguard.mailapp.data.mail.MailMessage
import net.sentinelsafeguard.mailapp.data.mail.MailText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailTextTest {

    private fun message(
        subject: String = "Quarterly report",
        body: String = "Numbers attached.",
    ) = MailMessage(
        uid = 7,
        subject = subject,
        from = "Client <client@example.com>",
        to = "skyler@sentinelsafeguard.net",
        cc = "",
        dateText = "Mon, 3 Nov 2026 09:15:00 -0600",
        bodyText = body,
        htmlBody = null,
        unread = true,
        starred = false,
        attachments = emptyList(),
        messageId = "<abc@example.com>",
        references = null,
    )

    @Test
    fun htmlFallsBackToReadableText() {
        val text = MailText.htmlToText(
            "<html><head><style>p{color:red}</style></head><body>" +
                "<p>Hello &amp; welcome</p><script>alert(1)</script>" +
                "<p>Second&nbsp;paragraph</p></body></html>",
        )
        assertTrue(text.contains("Hello & welcome"))
        assertTrue(text.contains("Second paragraph"))
        assertFalse(text.contains("alert"))
        assertFalse(text.contains("color:red"))
    }

    @Test
    fun entitiesDecode() {
        assertEquals(
            "a < b & \"c\" 'd'",
            MailText.decodeEntities("a &lt; b &amp; &quot;c&quot; &#39;d&#39;"),
        )
    }

    @Test
    fun snippetCollapsesAndCaps() {
        val long = "word ".repeat(80).trim()
        val snippet = MailText.makeSnippet(long)
        assertTrue(snippet.length <= 141)
        assertTrue(snippet.endsWith("…"))
        assertEquals("one line", MailText.makeSnippet("  one\n\nline  "))
        assertEquals("", MailText.makeSnippet("   "))
    }

    @Test
    fun subjectsPrefixOnce() {
        assertEquals("Re: Hello", MailText.replySubject("Hello"))
        assertEquals("Re: Hello", MailText.replySubject("Re: Hello"))
        assertEquals("re: Hello", MailText.replySubject("re: Hello"))
        assertEquals("Fwd: Hello", MailText.forwardSubject("Hello"))
        assertEquals("Fwd: Hello", MailText.forwardSubject("Fwd: Hello"))
        assertEquals("Fw: Hello", MailText.forwardSubject("Fw: Hello"))
    }

    @Test
    fun addressParsing() {
        assertEquals("a@b.com", MailText.extractAddress("Name <a@b.com>"))
        assertEquals("a@b.com", MailText.extractAddress("  a@b.com "))
        assertEquals(
            listOf("a@b.com", "c@d.com", "e@f.com"),
            MailText.parseAddressList("a@b.com, c@d.com; e@f.com"),
        )
        assertEquals(emptyList<String>(), MailText.parseAddressList(" , ; "))
    }

    @Test
    fun addressShapeCheck() {
        assertTrue(MailText.looksLikeAddress("a@b.com"))
        assertTrue(MailText.looksLikeAddress("Name <a@b.com>"))
        assertFalse(MailText.looksLikeAddress("a@b"))
        assertFalse(MailText.looksLikeAddress("a b@c.com"))
        assertFalse(MailText.looksLikeAddress("@b.com"))
        assertFalse(MailText.looksLikeAddress("a@@b.com"))
        assertFalse(MailText.looksLikeAddress("a@.com"))
        assertFalse(MailText.looksLikeAddress("a@com."))
    }

    @Test
    fun replyQuoteKeepsTheFamilyFormat() {
        val quoted = MailText.quoteForReply("Thanks!", message())
        assertTrue(quoted.startsWith("Thanks!\n\n----- Original message -----"))
        assertTrue(quoted.contains("From: Client <client@example.com>"))
        assertTrue(quoted.contains("Subject: Quarterly report"))
        assertTrue(quoted.endsWith("Numbers attached."))
    }

    @Test
    fun forwardBodyCarriesTheHeaderBlock() {
        val fwd = MailText.forwardBody("", message())
        assertTrue(fwd.startsWith("----- Forwarded message -----"))
        assertTrue(fwd.contains("To: skyler@sentinelsafeguard.net"))
        assertTrue(fwd.endsWith("Numbers attached."))
        val withNote = MailText.forwardBody("FYI —", message())
        assertTrue(withNote.startsWith("FYI —\n\n----- Forwarded message -----"))
    }
}
