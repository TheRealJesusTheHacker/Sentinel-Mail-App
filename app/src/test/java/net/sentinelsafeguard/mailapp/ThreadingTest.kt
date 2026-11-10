package net.sentinelsafeguard.mailapp

import net.sentinelsafeguard.mailapp.data.mail.MailSummary
import net.sentinelsafeguard.mailapp.data.mail.Threading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Threading (1.2.0): References root anchors the conversation,
 * In-Reply-To is the fallback, normalized subject is the last
 * resort. Grouping folds a newest-first list without reordering
 * conversations relative to each other. */
class ThreadingTest {

    private fun summary(
        uid: Long,
        subject: String,
        mid: String? = null,
        irt: String? = null,
        refs: String? = null,
    ) = MailSummary(
        uid = uid,
        subject = subject,
        from = "a@example.com",
        dateText = "",
        unread = false,
        snippet = "",
        messageId = mid,
        inReplyTo = irt,
        references = refs,
    )

    @Test
    fun parsesReferencesInOrder() {
        assertEquals(
            listOf("<a@x>", "<b@x>", "<c@x>"),
            Threading.parseReferences("<a@x> <b@x>\n <c@x>"),
        )
        assertEquals(emptyList<String>(), Threading.parseReferences(null))
        assertEquals(emptyList<String>(), Threading.parseReferences("no ids here"))
    }

    @Test
    fun keyPrefersReferencesRoot() {
        val key = Threading.threadKey("<c@x>", "<a@x> <b@x>", "<b@x>", "Re: Hi")
        assertEquals("ref:<a@x>", key)
    }

    @Test
    fun keyFallsBackToInReplyToThenOwnId() {
        assertEquals(
            "ref:<b@x>",
            Threading.threadKey("<c@x>", null, "<b@x>", "Re: Hi"),
        )
        assertEquals(
            "ref:<c@x>",
            Threading.threadKey("<c@x>", null, null, "Re: Hi"),
        )
    }

    @Test
    fun keyFallsBackToNormalizedSubject() {
        assertEquals(
            "subj:project kickoff",
            Threading.threadKey(null, null, null, "Re: Fwd: Project Kickoff"),
        )
        assertEquals("project kickoff", Threading.normalizedSubject("RE[2]: Project Kickoff"))
        assertEquals("", Threading.normalizedSubject("Re:"))
    }

    @Test
    fun groupingFoldsRepliesUnderTheRoot() {
        val list = listOf(
            summary(3, "Re: Project kickoff", mid = "<c@x>", irt = "<b@x>", refs = "<a@x> <b@x>"),
            summary(2, "Re: Project kickoff", mid = "<b@x>", irt = "<a@x>", refs = "<a@x>"),
            summary(1, "Project kickoff", mid = "<a@x>"),
            summary(4, "Invoice", mid = "<inv@x>"),
        )
        val groups = Threading.group(list)
        assertEquals(2, groups.size)
        assertEquals(3, groups[0].count)
        assertEquals(3L, groups[0].latest.uid)
        assertEquals(listOf(3L, 2L, 1L), groups[0].members.map { it.uid })
        assertEquals(1, groups[1].count)
    }

    @Test
    fun groupingBySubjectWhenNoHeadersExist() {
        val list = listOf(
            summary(2, "Re: Hello there"),
            summary(1, "Hello there"),
        )
        val groups = Threading.group(list)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].count)
    }

    @Test
    fun unreadRollsUpToTheGroup() {
        val list = listOf(
            summary(2, "Re: Hi", mid = "<b@x>", irt = "<a@x>", refs = "<a@x>").copy(unread = false),
            summary(1, "Hi", mid = "<a@x>").copy(unread = true),
        )
        assertTrue(Threading.group(list)[0].hasUnread)
    }
}
