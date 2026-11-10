package net.sentinelsafeguard.mailapp

import net.sentinelsafeguard.mailapp.data.mail.ListedFolder
import net.sentinelsafeguard.mailapp.data.mail.MailRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 1.1.0 folder fix, driven by canned LIST presentations —
 * no network, no mail library. The production-shaped fixture
 * below is the ×3 the owner saw on his phone: every personal
 * mailbox presented three ways in one LIST reply (bare,
 * INBOX.-prefixed, and INBOX.-prefixed carrying the SPECIAL-USE
 * flag), the inbox under its alias presentations, and each
 * shared folder exactly once — which is why shared folders were
 * the only ones that ever looked right.
 */
class FolderDedupeTest {

    /** One personal mailbox, three presentations. */
    private fun personalTriple(bare: String, specialUse: String? = null): List<ListedFolder> {
        val prefixed = "INBOX.$bare"
        return listOf(
            ListedFolder(bare),
            ListedFolder(prefixed),
            ListedFolder(
                prefixed,
                attributes = listOfNotNull(specialUse).toSet(),
            ),
        )
    }

    private fun productionShapedList(): List<ListedFolder> {
        val out = mutableListOf<ListedFolder>()
        // The inbox's own presentations: itself, its alias, and
        // its flagged form.
        out += ListedFolder("INBOX")
        out += ListedFolder("INBOX.INBOX")
        out += ListedFolder("INBOX", attributes = setOf("\\Inbox"))
        out += personalTriple("Sent", "\\Sent")
        out += personalTriple("Drafts", "\\Drafts")
        out += personalTriple("Archive", "\\Archive")
        out += personalTriple("Trash", "\\Trash")
        out += personalTriple("Junk", "\\Junk")
        out += personalTriple("Receipts")
        // Shared folders: one presentation each, as on the server.
        out += ListedFolder("Shared.sentinel")
        out += ListedFolder("Shared.sentinel.INBOX")
        out += ListedFolder("Shared.sentinel.TicketIntake")
        return out
    }

    @Test
    fun theTimesThreeListCollapsesToOneRowPerMailbox() {
        val raw = productionShapedList()
        assertEquals(24, raw.size)
        val deduped = MailRules.dedupeFolders(raw)
        val names = deduped.map { it.name }
        // 7 personal mailboxes (Inbox, Sent, Drafts, Archive,
        // Trash, Junk, Receipts) + 3 shared entries. Exactly.
        assertEquals(10, deduped.size)
        assertEquals(names.size, names.distinct().size)
        assertEquals(
            deduped.size,
            deduped.map { MailRules.canonicalKey(it.name) }.distinct().size,
        )
    }

    @Test
    fun survivorCarriesTheUnionOfAttributes() {
        val deduped = MailRules.dedupeFolders(productionShapedList())
        val byRole = deduped.associateBy { MailRules.roleOf(it.name, it.attributes) }
        assertTrue(byRole.getValue(MailRules.Role.SENT).attributes.contains("\\Sent"))
        assertTrue(byRole.getValue(MailRules.Role.DRAFTS).attributes.contains("\\Drafts"))
        assertTrue(byRole.getValue(MailRules.Role.TRASH).attributes.contains("\\Trash"))
    }

    @Test
    fun canonicalIdentityNormalizesThePersonalPrefixOnly() {
        assertEquals(
            MailRules.canonicalKey("Sent"),
            MailRules.canonicalKey("INBOX.Sent"),
        )
        assertEquals(
            MailRules.canonicalKey("INBOX"),
            MailRules.canonicalKey("INBOX.INBOX"),
        )
        assertEquals(
            MailRules.canonicalKey("sent"),
            MailRules.canonicalKey("inbox.SENT"),
        )
        // A shared inbox is a DIFFERENT mailbox from the personal
        // inbox — never collapsed into it.
        assertNotEquals(
            MailRules.canonicalKey("INBOX"),
            MailRules.canonicalKey("Shared.sentinel.INBOX"),
        )
        assertNotEquals(
            MailRules.canonicalKey("Shared.sentinel.Sent"),
            MailRules.canonicalKey("Shared.othermail.Sent"),
        )
    }

    @Test
    fun stripPersonalPrefixCases() {
        assertEquals("Sent", MailRules.stripPersonalPrefix("INBOX.Sent"))
        assertEquals("INBOX", MailRules.stripPersonalPrefix("INBOX"))
        assertEquals("INBOX", MailRules.stripPersonalPrefix("INBOX.INBOX"))
        assertEquals("Sent", MailRules.stripPersonalPrefix("Sent"))
        assertEquals(
            "Shared.sentinel.INBOX",
            MailRules.stripPersonalPrefix("Shared.sentinel.INBOX"),
        )
        assertEquals("Clients.2026", MailRules.stripPersonalPrefix("INBOX.Clients.2026"))
    }

    @Test
    fun specialUseFlagBeatsTheName() {
        // A server-named folder whose leaf says nothing: the flag
        // is the server's own statement of purpose and wins.
        assertEquals(
            MailRules.Role.SENT,
            MailRules.roleOf("INBOX.Mailbox7", setOf("\\Sent")),
        )
        assertEquals(
            MailRules.Role.OTHER,
            MailRules.roleOf("INBOX.Mailbox7", emptySet()),
        )
        assertEquals(
            MailRules.Role.JUNK,
            MailRules.roleOf("INBOX.Filtered", setOf("\\Junk")),
        )
    }

    @Test
    fun noselectContainersAreNotMailboxes() {
        val deduped = MailRules.dedupeFolders(
            listOf(
                ListedFolder("Projects", attributes = setOf("\\Noselect")),
                ListedFolder("Projects.Alpha"),
                ListedFolder("INBOX"),
            )
        )
        assertEquals(listOf("INBOX", "Projects.Alpha"), deduped.map { it.name }.sorted())
    }

    @Test
    fun genuinelyDifferentFoldersStayDifferent() {
        val deduped = MailRules.dedupeFolders(
            listOf(
                ListedFolder("Clients.2026"),
                ListedFolder("INBOX.Clients.2026"),
                ListedFolder("Clients.2027"),
                ListedFolder("Work.Clients.2026"),
            )
        )
        assertEquals(3, deduped.size)
    }

    @Test
    fun displayNamesReadAsTruePurpose() {
        assertEquals("Inbox", MailRules.displayName("INBOX"))
        assertEquals("Inbox", MailRules.displayName("INBOX.INBOX"))
        assertEquals("Sent", MailRules.displayName("INBOX.Sent"))
        assertEquals("Sent", MailRules.displayName("Sent"))
        assertEquals("Drafts", MailRules.displayName("INBOX.Drafts"))
        assertEquals("Receipts", MailRules.displayName("INBOX.Receipts"))
        assertEquals("Clients / 2026", MailRules.displayName("INBOX.Clients.2026"))
        assertEquals("VIP", MailRules.displayName("INBOX.VIP"))
        assertEquals("Sentinel · Inbox", MailRules.displayName("Shared.sentinel.INBOX"))
        assertEquals("Sentinel · Sent", MailRules.displayName("Shared.sentinel.Sent"))
        assertEquals(
            "Sentinel · Ticket Intake",
            MailRules.displayName("Shared.sentinel.TicketIntake"),
        )
        assertEquals("Sentinel · Shared root", MailRules.displayName("Shared.sentinel"))
    }

    @Test
    fun humanizedSegments() {
        assertEquals("Ticket Intake", MailRules.humanizeSegment("TicketIntake"))
        assertEquals("Receipts", MailRules.humanizeSegment("RECEIPTS"))
        assertEquals("Receipts", MailRules.humanizeSegment("receipts"))
        assertEquals("VIP", MailRules.humanizeSegment("VIP"))
        assertEquals("2026", MailRules.humanizeSegment("2026"))
        assertEquals("Follow Ups", MailRules.humanizeSegment("follow-ups"))
    }
}
