package net.sentinelsafeguard.mailapp

import jakarta.mail.Flags
import jakarta.mail.search.AndTerm
import jakarta.mail.search.FlagTerm
import jakarta.mail.search.OrTerm
import net.sentinelsafeguard.mailapp.data.mail.SearchFilter
import net.sentinelsafeguard.mailapp.data.mail.searchTermFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search narrowing (1.2.2, capability gap G2): the flag
 * filters must compile to SERVER-side SEARCH terms — an
 * AndTerm over the text legs with the right FlagTerm — never
 * a client-side post-filter wearing a server costume. (The
 * ATTACHMENTS narrowing is the documented exception: IMAP has
 * no attachment term, so it stays the plain text term and the
 * engine narrows the fetched page on real structure flags.) */
class SearchFilterTest {

    @Test
    fun allAndAttachmentsStayThePlainTextTerm() {
        assertTrue(searchTermFor("invoice", SearchFilter.ALL) is OrTerm)
        assertTrue(searchTermFor("invoice", SearchFilter.ATTACHMENTS) is OrTerm)
    }

    @Test
    fun unreadAndsAnUnseenFlagTerm() {
        val term = searchTermFor("invoice", SearchFilter.UNREAD)
        assertTrue(term is AndTerm)
        val parts = (term as AndTerm).terms.toList()
        assertTrue(parts.any { it is OrTerm })
        val flag = parts.filterIsInstance<FlagTerm>().single()
        assertTrue(flag.flags.contains(Flags.Flag.SEEN))
        assertEquals(false, flag.testSet)
    }

    @Test
    fun starredAndsAFlaggedFlagTerm() {
        val term = searchTermFor("invoice", SearchFilter.STARRED)
        assertTrue(term is AndTerm)
        val flag = (term as AndTerm).terms.filterIsInstance<FlagTerm>().single()
        assertTrue(flag.flags.contains(Flags.Flag.FLAGGED))
        assertEquals(true, flag.testSet)
    }
}
