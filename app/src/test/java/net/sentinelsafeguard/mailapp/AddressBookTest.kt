package net.sentinelsafeguard.mailapp

import net.sentinelsafeguard.mailapp.data.mail.AddressBook
import net.sentinelsafeguard.mailapp.data.mail.MailSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Autocomplete harvesting (1.2.0): correspondents come only
 * from the account's own mail; the owner is never their own
 * suggestion; display forms track the most recent sighting. */
class AddressBookTest {

    private fun summary(from: String, to: String = "") = MailSummary(
        uid = 1, subject = "s", from = from, to = to,
        dateText = "", unread = false, snippet = "",
    )

    @Test
    fun harvestCountsFromAndTo() {
        val entries = AddressBook.harvest(
            listOf(
                summary("Client <client@example.com>", "skyler@sentinelsafeguard.net"),
                summary("client@example.com"),
                summary("Other <other@example.com>", "client@example.com"),
            ),
            ownAddress = "skyler@sentinelsafeguard.net",
        )
        val byAddress = entries.associateBy { it.address }
        assertEquals(3, byAddress.getValue("client@example.com").count)
        assertEquals(1, byAddress.getValue("other@example.com").count)
        assertTrue("skyler@sentinelsafeguard.net" !in byAddress)
    }

    @Test
    fun displayTracksMostRecentSighting() {
        // Batch is newest-first: the first sighting's form wins.
        val entries = AddressBook.harvest(
            listOf(
                summary("New Name <pal@example.com>"),
                summary("Old Name <pal@example.com>"),
            ),
            ownAddress = "me@example.com",
        )
        assertEquals("New Name <pal@example.com>", entries.single().display)
    }

    @Test
    fun harvestMergesWithExistingCounts() {
        val first = AddressBook.harvest(
            listOf(summary("pal@example.com")),
            ownAddress = "me@example.com",
        )
        val second = AddressBook.harvest(
            listOf(summary("pal@example.com")),
            ownAddress = "me@example.com",
            existing = first,
        )
        assertEquals(2, second.single().count)
    }

    @Test
    fun suggestMatchesNameAndAddressRankedByCount() {
        val entries = listOf(
            AddressBook.Entry("carol@example.com", "Carol <carol@example.com>", 5),
            AddressBook.Entry("carlos@example.com", "Carlos <carlos@example.com>", 1),
            AddressBook.Entry("dan@example.com", "Dan <dan@example.com>", 9),
        )
        assertEquals(
            listOf("carol@example.com", "carlos@example.com"),
            AddressBook.suggest(entries, "car").map { it.address },
        )
        // Name match as well as address match.
        assertEquals(
            listOf("carlos@example.com"),
            AddressBook.suggest(entries, "carlos").map { it.address },
        )
        assertTrue(AddressBook.suggest(entries, "").isEmpty())
        assertTrue(AddressBook.suggest(entries, "zzz").isEmpty())
    }

    @Test
    fun tokenAndPick() {
        assertEquals("bo", AddressBook.currentToken("amy@example.com, bo"))
        assertEquals("", AddressBook.currentToken("amy@example.com, "))
        val entry = AddressBook.Entry("bob@example.com", "Bob <bob@example.com>", 3)
        assertEquals(
            "amy@example.com, Bob <bob@example.com>, ",
            AddressBook.applyPick("amy@example.com, bo", entry),
        )
        assertEquals(
            "Bob <bob@example.com>, ",
            AddressBook.applyPick("bo", entry),
        )
    }
}
