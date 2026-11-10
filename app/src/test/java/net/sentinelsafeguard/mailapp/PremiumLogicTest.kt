package net.sentinelsafeguard.mailapp

import net.sentinelsafeguard.mailapp.data.mail.ImagePolicy
import net.sentinelsafeguard.mailapp.data.mail.InlineImages
import net.sentinelsafeguard.mailapp.data.mail.RecentSearches
import net.sentinelsafeguard.mailapp.data.mail.SearchMerge
import net.sentinelsafeguard.mailapp.data.mail.SignatureText
import net.sentinelsafeguard.mailapp.data.mail.MailSummary
import net.sentinelsafeguard.mailapp.ui.UndoSendLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The smaller 1.2.0 pure-logic surfaces, one suite: recent
 * searches, signatures, per-sender image memory, inline-image
 * rewriting, all-folder search merging, undo-send math. */
class PremiumLogicTest {

    // ---- recent searches ----

    @Test
    fun recentSearchesDedupeCapAndRoundTrip() {
        var list = emptyList<String>()
        list = RecentSearches.add(list, "  invoice   2026 ")
        list = RecentSearches.add(list, "report")
        list = RecentSearches.add(list, "INVOICE 2026")
        assertEquals(listOf("INVOICE 2026", "report"), list)
        repeat(10) { list = RecentSearches.add(list, "q$it") }
        assertEquals(RecentSearches.MAX, list.size)
        assertEquals(list, RecentSearches.decode(RecentSearches.encode(list)))
        assertTrue(RecentSearches.decode(null).isEmpty())
    }

    // ---- signatures ----

    @Test
    fun signatureBlockAndNewMessage() {
        assertEquals("", SignatureText.block("  "))
        assertEquals("\n\n-- \nSkyler", SignatureText.block("  Skyler  "))
        assertEquals(
            "Body\n\n-- \nSkyler",
            SignatureText.forNew("Body", "Skyler"),
        )
    }

    @Test
    fun signatureLandsBeforeTheQuoteOnReplies() {
        val body = "Thanks!\n\n----- Original message -----\nFrom: A"
        val signed = SignatureText.forReply(body, "Skyler")
        assertTrue(signed.startsWith("Thanks!\n\n-- \nSkyler\n\n----- Original"))
    }

    @Test
    fun signatureNeverDoubles() {
        val once = SignatureText.forNew("Body", "Skyler")
        assertEquals(once, SignatureText.forNew(once, "Skyler"))
        assertEquals(once, SignatureText.forReply(once, "Skyler"))
    }

    // ---- image policy ----

    @Test
    fun imagePolicyKeysByBareAddress() {
        assertEquals(
            "client@example.com",
            ImagePolicy.senderKey("Client <Client@Example.com>"),
        )
        val senders = ImagePolicy.remember(emptySet(), "Client <client@example.com>")
        assertTrue(ImagePolicy.shouldAutoLoad("client@example.com", senders))
        assertFalse(ImagePolicy.shouldAutoLoad("other@example.com", senders))
        assertTrue(ImagePolicy.forget(senders, "client@example.com").isEmpty())
    }

    // ---- inline images ----

    @Test
    fun inlineImagesRewriteKnownCidsOnly() {
        val html = """<img src="cid:logo@x"><img src="cid:missing@x">""" +
            """<img src="https://tracker.example/p.gif">"""
        assertEquals(
            setOf("logo@x", "missing@x"),
            InlineImages.referencedCids(html),
        )
        val out = InlineImages.inlineIntoHtml(
            html,
            mapOf("<logo@x>" to InlineImages.Part("image/png", byteArrayOf(1, 2, 3))),
        )
        assertTrue(out.contains("data:image/png;base64,AQID"))
        assertTrue(out.contains("cid:missing@x"))
        // Remote references are NOT rewritten — privacy rule.
        assertTrue(out.contains("https://tracker.example/p.gif"))
        assertEquals(html, InlineImages.inlineIntoHtml(html, emptyMap()))
    }

    // ---- search merge ----

    private fun s(uid: Long) = MailSummary(
        uid = uid, subject = "", from = "", dateText = "", unread = false, snippet = "",
    )

    @Test
    fun searchMergeInterleavesAndCaps() {
        val merged = SearchMerge.merge(
            listOf(listOf(s(1), s(2), s(3)), listOf(s(4)), listOf(s(5), s(6))),
        )
        assertEquals(listOf(1L, 4L, 5L, 2L, 6L, 3L), merged.map { it.uid })
        val capped = SearchMerge.merge(
            listOf(listOf(s(1), s(2)), listOf(s(3), s(4))), cap = 3,
        )
        assertEquals(listOf(1L, 3L, 2L), capped.map { it.uid })
        assertTrue(SearchMerge.merge(emptyList()).isEmpty())
    }

    // ---- undo send ----

    @Test
    fun undoSendCountdownMath() {
        assertEquals(5, UndoSendLogic.remainingSeconds(5, 0L))
        assertEquals(5, UndoSendLogic.remainingSeconds(5, 1L))
        assertEquals(4, UndoSendLogic.remainingSeconds(5, 1001L))
        assertEquals(0, UndoSendLogic.remainingSeconds(5, 5000L))
        assertFalse(UndoSendLogic.isExpired(5, 4999L))
        assertTrue(UndoSendLogic.isExpired(5, 5000L))
        assertEquals(0.5f, UndoSendLogic.fraction(10, 5000L), 0.001f)
        assertEquals(1f, UndoSendLogic.fraction(0, 0L), 0.001f)
    }
}
