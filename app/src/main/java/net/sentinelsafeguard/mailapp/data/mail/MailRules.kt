package net.sentinelsafeguard.mailapp.data.mail

/**
 * Pure mail rules — kept free of any mail library so they are
 * unit-tested directly. Ported from the Command App's MailRules and
 * extended for the dedicated app (folder roles, draft/sent targets).
 *
 * Folder identity rule (family rule, unchanged): mail for the
 * business lives in skyler@sentinelsafeguard.net, and the shared
 * business folders arrive as Shared.sentinel.* under the scoped
 * sharing grant. A message sent from a shared folder is sent as
 * sentinel@sentinelsafeguard.net; a message sent from anywhere else
 * is sent as the signed-in mailbox itself.
 */
object MailRules {

    const val PERSONAL_ADDRESS = "skyler@sentinelsafeguard.net"
    const val SHARED_ADDRESS = "sentinel@sentinelsafeguard.net"
    const val SHARED_PREFIX = "Shared.sentinel"
    const val ARCHIVE_FOLDER = "Archive"
    const val TRASH_FOLDER = "Trash"
    const val SENT_FOLDER = "Sent"
    const val DRAFTS_FOLDER = "Drafts"
    const val JUNK_FOLDER = "Junk"

    fun isSharedFolder(folderName: String): Boolean =
        folderName.equals(SHARED_PREFIX, ignoreCase = true) ||
            folderName.startsWith("$SHARED_PREFIX.", ignoreCase = true)

    /** The From address a send from [folderName] must use. When the
     * signed-in account IS the shared address, it sends as itself. */
    fun identityForFolder(folderName: String, accountAddress: String = PERSONAL_ADDRESS): String =
        if (isSharedFolder(folderName) && !accountAddress.equals(SHARED_ADDRESS, ignoreCase = true)) {
            SHARED_ADDRESS
        } else {
            accountAddress
        }

    /** Archive is the keep-forever folder — always the same target. */
    fun archiveTarget(): String = ARCHIVE_FOLDER

    fun trashTarget(): String = TRASH_FOLDER

    fun sentTarget(): String = SENT_FOLDER

    fun draftsTarget(): String = DRAFTS_FOLDER

    /** The canonical role of a folder, by leaf name, so the UI can
     * order and label the tree the same way whatever the server's
     * delimiter or a "Sent Items"-style variant. */
    enum class Role { INBOX, SENT, DRAFTS, ARCHIVE, TRASH, JUNK, OTHER }

    fun roleOf(folderName: String): Role {
        val leaf = folderName.substringAfterLast('.').substringAfterLast('/')
        return when {
            leaf.equals("INBOX", ignoreCase = true) -> Role.INBOX
            leaf.equals("Sent", ignoreCase = true) ||
                leaf.equals("Sent Items", ignoreCase = true) ||
                leaf.equals("Sent Mail", ignoreCase = true) -> Role.SENT
            leaf.equals("Drafts", ignoreCase = true) -> Role.DRAFTS
            leaf.equals("Archive", ignoreCase = true) -> Role.ARCHIVE
            leaf.equals("Trash", ignoreCase = true) ||
                leaf.equals("Deleted Items", ignoreCase = true) -> Role.TRASH
            leaf.equals("Junk", ignoreCase = true) ||
                leaf.equals("Spam", ignoreCase = true) ||
                leaf.equals("Junk E-mail", ignoreCase = true) -> Role.JUNK
            else -> Role.OTHER
        }
    }

    /** Display order for the folder tree: the six first-class
     * folders in working order, everything else after, shared
     * folders grouped with their own section by the caller. */
    fun sortKey(folderName: String): Int = when (roleOf(folderName)) {
        Role.INBOX -> 0
        Role.SENT -> 1
        Role.DRAFTS -> 2
        Role.ARCHIVE -> 3
        Role.JUNK -> 4
        Role.TRASH -> 5
        Role.OTHER -> 6
    }

    /** True for the folders the folder list shows first-class. */
    fun isSystemFolder(folderName: String): Boolean = roleOf(folderName) != Role.OTHER

    /** Deleting while already in Trash is permanent (expunge);
     * deleting anywhere else files to Trash. */
    fun deleteIsPermanent(folderName: String): Boolean = roleOf(folderName) == Role.TRASH

    /** A draft opened for editing is identified by its folder role,
     * not by a flag — Drafts membership is the whole signal. */
    fun isDraftsFolder(folderName: String): Boolean = roleOf(folderName) == Role.DRAFTS
}
