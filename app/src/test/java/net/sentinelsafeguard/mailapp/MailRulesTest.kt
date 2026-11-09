package net.sentinelsafeguard.mailapp

import net.sentinelsafeguard.mailapp.data.mail.MailRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the Command App's MailRulesTest and extended for
 * the dedicated app's folder roles and targets. */
class MailRulesTest {

    @Test
    fun sharedFolderSendsAsSentinel() {
        assertEquals(
            "sentinel@sentinelsafeguard.net",
            MailRules.identityForFolder("Shared.sentinel.INBOX"),
        )
        assertEquals(
            "sentinel@sentinelsafeguard.net",
            MailRules.identityForFolder("Shared.sentinel.Sent"),
        )
        assertTrue(MailRules.isSharedFolder("Shared.sentinel"))
    }

    @Test
    fun personalFolderSendsAsAccount() {
        assertEquals(
            "skyler@sentinelsafeguard.net",
            MailRules.identityForFolder("INBOX"),
        )
        assertEquals(
            "skyler@sentinelsafeguard.net",
            MailRules.identityForFolder("Archive"),
        )
        assertFalse(MailRules.isSharedFolder("INBOX"))
        assertFalse(MailRules.isSharedFolder("Shared.other.INBOX"))
    }

    @Test
    fun theSharedAccountSendsAsItself() {
        assertEquals(
            "sentinel@sentinelsafeguard.net",
            MailRules.identityForFolder(
                "Shared.sentinel.INBOX",
                "sentinel@sentinelsafeguard.net",
            ),
        )
        assertEquals(
            "sentinel@sentinelsafeguard.net",
            MailRules.identityForFolder("INBOX", "sentinel@sentinelsafeguard.net"),
        )
    }

    @Test
    fun fixedTargets() {
        assertEquals("Archive", MailRules.archiveTarget())
        assertEquals("Trash", MailRules.trashTarget())
        assertEquals("Sent", MailRules.sentTarget())
        assertEquals("Drafts", MailRules.draftsTarget())
    }

    @Test
    fun folderRoles() {
        assertEquals(MailRules.Role.INBOX, MailRules.roleOf("INBOX"))
        assertEquals(MailRules.Role.INBOX, MailRules.roleOf("Shared.sentinel.INBOX"))
        assertEquals(MailRules.Role.SENT, MailRules.roleOf("Sent"))
        assertEquals(MailRules.Role.SENT, MailRules.roleOf("Sent Items"))
        assertEquals(MailRules.Role.DRAFTS, MailRules.roleOf("Drafts"))
        assertEquals(MailRules.Role.ARCHIVE, MailRules.roleOf("Archive"))
        assertEquals(MailRules.Role.TRASH, MailRules.roleOf("Trash"))
        assertEquals(MailRules.Role.TRASH, MailRules.roleOf("Deleted Items"))
        assertEquals(MailRules.Role.JUNK, MailRules.roleOf("Junk"))
        assertEquals(MailRules.Role.JUNK, MailRules.roleOf("Spam"))
        assertEquals(MailRules.Role.OTHER, MailRules.roleOf("Clients.2026"))
    }

    @Test
    fun treeOrderPutsWorkingFoldersFirst() {
        val sorted = listOf("Clients.2026", "Trash", "INBOX", "Junk", "Sent", "Archive", "Drafts")
            .sortedBy { MailRules.sortKey(it) }
        assertEquals(
            listOf("INBOX", "Sent", "Drafts", "Archive", "Junk", "Trash", "Clients.2026"),
            sorted,
        )
    }

    @Test
    fun deleteIsPermanentOnlyInTrash() {
        assertTrue(MailRules.deleteIsPermanent("Trash"))
        assertTrue(MailRules.deleteIsPermanent("Deleted Items"))
        assertFalse(MailRules.deleteIsPermanent("INBOX"))
        assertFalse(MailRules.deleteIsPermanent("Archive"))
    }

    @Test
    fun draftsMembershipIsTheDraftSignal() {
        assertTrue(MailRules.isDraftsFolder("Drafts"))
        assertFalse(MailRules.isDraftsFolder("INBOX"))
        assertFalse(MailRules.isDraftsFolder("Sent"))
    }
}
