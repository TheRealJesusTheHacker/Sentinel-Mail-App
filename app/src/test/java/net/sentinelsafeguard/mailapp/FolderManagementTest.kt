package net.sentinelsafeguard.mailapp

import net.sentinelsafeguard.mailapp.data.mail.MailRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Folder-management rails (1.2.0): special-use furniture is
 * refused app-side by flag OR by name; shared folders go to the
 * server for the honest answer; new names are validated before
 * a CREATE is ever attempted. */
class FolderManagementTest {

    @Test
    fun specialUseFoldersAreBlockedByName() {
        for (name in listOf("INBOX", "Sent", "Drafts", "Archive", "Trash", "Junk",
            "Sent Items", "Deleted Items", "Spam")) {
            assertEquals(
                "$name must be blocked",
                MailRules.ManageRail.BLOCKED_SPECIAL,
                MailRules.manageRail(name),
            )
        }
        // The shared mailbox's own inbox sibling carries the role too.
        assertEquals(
            MailRules.ManageRail.BLOCKED_SPECIAL,
            MailRules.manageRail("Shared.sentinel.INBOX"),
        )
    }

    @Test
    fun specialUseFoldersAreBlockedByFlag() {
        assertEquals(
            MailRules.ManageRail.BLOCKED_SPECIAL,
            MailRules.manageRail("Filing Cabinet", setOf("\\Sent")),
        )
        assertEquals(
            MailRules.ManageRail.BLOCKED_SPECIAL,
            MailRules.manageRail("Old Mail", setOf("\\Archive")),
        )
    }

    @Test
    fun sharedFoldersAreTheServersCall() {
        assertEquals(
            MailRules.ManageRail.SERVER_DECIDES,
            MailRules.manageRail("Shared.sentinel.TicketIntake"),
        )
        assertEquals(
            MailRules.ManageRail.SERVER_DECIDES,
            MailRules.manageRail("Shared.sentinel.OldProjects"),
        )
    }

    @Test
    fun personalFoldersAreAllowed() {
        assertEquals(
            MailRules.ManageRail.ALLOWED,
            MailRules.manageRail("Projects"),
        )
        assertEquals(
            MailRules.ManageRail.ALLOWED,
            MailRules.manageRail("Receipts.2026"),
        )
        assertEquals(
            MailRules.ManageRail.ALLOWED,
            MailRules.manageRail("Snoozed"),
        )
    }

    @Test
    fun nameValidation() {
        assertNull(MailRules.validateFolderName("Projects"))
        assertNull(MailRules.validateFolderName("Receipts 2026"))
        assertNull(MailRules.validateFolderName("Trips/2027"))
        assertEquals(
            MailRules.NameProblem.BLANK, MailRules.validateFolderName("   "),
        )
        assertEquals(
            MailRules.NameProblem.TOO_LONG, MailRules.validateFolderName("x".repeat(65)),
        )
        assertEquals(
            MailRules.NameProblem.SHARED_NAMESPACE,
            MailRules.validateFolderName("Shared.sentinel.Mine"),
        )
        assertEquals(
            MailRules.NameProblem.SHARED_NAMESPACE,
            MailRules.validateFolderName("Shared"),
        )
        // Merely starting with the letters is a personal name.
        assertNull(MailRules.validateFolderName("SharedStuff"))
        assertEquals(
            MailRules.NameProblem.EMPTY_SEGMENT,
            MailRules.validateFolderName("Trips..2027"),
        )
        assertEquals(
            MailRules.NameProblem.EMPTY_SEGMENT,
            MailRules.validateFolderName(".Trips"),
        )
        assertEquals(
            MailRules.NameProblem.BAD_CHARS,
            MailRules.validateFolderName("Bad\"Name"),
        )
        assertEquals(
            MailRules.NameProblem.BAD_CHARS,
            MailRules.validateFolderName("Wild*card"),
        )
    }
}
