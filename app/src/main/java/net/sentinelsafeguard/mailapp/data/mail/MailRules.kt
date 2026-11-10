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
 *
 * Folder CANONICAL identity (1.1.0 — the ×3 fix): Dovecot presents
 * the personal namespace more than one way in a single LIST reply.
 * The same mailbox arrives bare ("Sent"), INBOX.-prefixed
 * ("INBOX.Sent"), and (for the inbox itself) as both "INBOX" and
 * "INBOX.INBOX"-style presentations — the app's original list code
 * mapped every LIST line 1:1 into the UI with no canonicalization,
 * so every personal folder rendered once per presentation (three
 * times on the production server), while shared folders, which
 * have exactly one presentation (Shared.<owner>.*), rendered once.
 * Identity is therefore computed on the personal-namespace-
 * normalized path ([canonicalKey]): strip one leading INBOX
 * segment from personal paths, compare case-insensitively, and
 * keep shared paths whole — they are distinct mailboxes, never
 * aliases of personal ones.
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
    const val SNOOZED_FOLDER = "Snoozed"

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

    /** Where snoozed mail waits (1.2.0). A real folder on the
     * server, so webmail sees the same state as the app. */
    fun snoozedTarget(): String = SNOOZED_FOLDER

    // ---- folder management rails (1.2.0) ----

    /**
     * Whether rename/delete may be attempted on a folder:
     * - [BLOCKED_SPECIAL]: the folder carries a working role
     *   (by SPECIAL-USE flag or by name — Inbox, Sent, Drafts,
     *   Archive, Trash, Junk). Renaming or deleting the mailbox's
     *   own furniture is refused HERE, with the reason named,
     *   before the server is ever asked.
     * - [SERVER_DECIDES]: a shared folder. The app holds no proof
     *   of the owner's ACL grant, so the attempt goes to the
     *   server and the server's answer — success or refusal — is
     *   what the UI reports. Nothing is faked either way.
     * - [ALLOWED]: an ordinary personal folder. The server is
     *   still the final word; the app simply has no rail against.
     */
    enum class ManageRail { ALLOWED, BLOCKED_SPECIAL, SERVER_DECIDES }

    fun manageRail(folderName: String, attributes: Set<String> = emptySet()): ManageRail =
        when {
            roleOf(folderName, attributes) != Role.OTHER -> ManageRail.BLOCKED_SPECIAL
            isSharedFolder(folderName) -> ManageRail.SERVER_DECIDES
            else -> ManageRail.ALLOWED
        }

    /** Why a proposed new-folder name is not acceptable, or null
     * when it is. Deliberately conservative: the name becomes a
     * real mailbox on the server. */
    enum class NameProblem { BLANK, TOO_LONG, EMPTY_SEGMENT, SHARED_NAMESPACE, BAD_CHARS }

    fun validateFolderName(name: String): NameProblem? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> NameProblem.BLANK
            trimmed.length > 64 -> NameProblem.TOO_LONG
            // The "Shared" namespace is the mesh's, not the
            // creator's: anything whose FIRST segment is exactly
            // "Shared" would render and behave as a shared folder.
            isSharedFolder(trimmed) ||
                trimmed.split(delimiterOf(trimmed)).firstOrNull()
                    ?.equals("Shared", ignoreCase = true) == true ->
                NameProblem.SHARED_NAMESPACE
            trimmed.any { it.code < 0x20 || it == '"' || it == '%' || it == '*' } ->
                NameProblem.BAD_CHARS
            trimmed.split(delimiterOf(trimmed)).any { it.isEmpty() } ->
                NameProblem.EMPTY_SEGMENT
            else -> null
        }
    }

    // ---- canonical folder identity (1.1.0) ----

    /** The hierarchy delimiter a folder path uses ('.' on the
     * production Dovecot; '/' tolerated for other servers). */
    fun delimiterOf(folderName: String): Char = if ('/' in folderName) '/' else '.'

    /** Path segments of a folder name, split on its delimiter. */
    fun segmentsOf(folderName: String): List<String> =
        folderName.split(delimiterOf(folderName)).filter { it.isNotEmpty() }

    /**
     * The personal-namespace presentation prefix, removed: one
     * leading INBOX segment on a multi-segment PERSONAL path is a
     * presentation of the same mailbox, not part of its identity
     * ("INBOX.Sent" → "Sent", "INBOX.INBOX" → "INBOX"). The bare
     * name "INBOX" is the inbox itself and is never stripped.
     * Shared paths are returned untouched — Shared.<owner>.INBOX
     * is the owner's inbox, a different mailbox from INBOX.
     */
    fun stripPersonalPrefix(folderName: String): String {
        if (isSharedFolder(folderName)) return folderName
        val segments = segmentsOf(folderName)
        if (segments.size > 1 && segments[0].equals("INBOX", ignoreCase = true)) {
            return segments.drop(1).joinToString(delimiterOf(folderName).toString())
        }
        return folderName
    }

    /** The canonical identity two LIST presentations are compared
     * by: shared paths whole, personal paths prefix-normalized,
     * everything case-insensitive. Equal keys = same mailbox. */
    fun canonicalKey(folderName: String): String =
        if (isSharedFolder(folderName)) {
            "shared|" + folderName.lowercase()
        } else {
            "personal|" + stripPersonalPrefix(folderName).lowercase()
        }

    /**
     * Collapse raw LIST presentations to one entry per real
     * mailbox. The survivor for each mailbox is the presentation
     * the app will open: a SPECIAL-USE-flagged path first (the
     * server's own canonical statement), then a bare path over an
     * INBOX.-prefixed one, then the shortest name. Attributes are
     * unioned across presentations so a flag offered on any
     * presentation counts. Groups whose every presentation is
     * \Noselect and which carry no working role are pure namespace
     * containers, not mailboxes, and are dropped — their children
     * still arrive as their own entries.
     */
    fun dedupeFolders(entries: List<ListedFolder>): List<ListedFolder> {
        val groups = LinkedHashMap<String, MutableList<ListedFolder>>()
        for (entry in entries) {
            groups.getOrPut(canonicalKey(entry.name)) { mutableListOf() }.add(entry)
        }
        val out = mutableListOf<ListedFolder>()
        for (group in groups.values) {
            val attrs = group.flatMap { it.attributes }.toSet()
            val allNoSelect = group.all { g ->
                g.attributes.any { it.equals("\\Noselect", ignoreCase = true) }
            }
            if (allNoSelect && roleOf(group.first().name, attrs) == Role.OTHER) continue
            val survivor = group.minWithOrNull(
                compareBy(
                    { if (it.attributes.isNotEmpty()) 0 else 1 },
                    { if (stripPersonalPrefix(it.name) == it.name) 0 else 1 },
                    { it.name.length },
                    { it.name.lowercase() },
                )
            ) ?: continue
            out += ListedFolder(name = survivor.name, attributes = attrs)
        }
        return out
    }

    /** The canonical role of a folder, by leaf name, so the UI can
     * order and label the tree the same way whatever the server's
     * delimiter or a "Sent Items"-style variant. */
    enum class Role { INBOX, SENT, DRAFTS, ARCHIVE, TRASH, JUNK, OTHER }

    fun roleOf(folderName: String): Role = roleOf(folderName, emptySet())

    /**
     * Role with the server's own word first: a SPECIAL-USE
     * attribute (\Sent, \Drafts, \Trash, \Junk, \Archive) decides
     * the role outright; only when the server offers no flag does
     * the (prefix-normalized) leaf name decide, as before.
     */
    fun roleOf(folderName: String, attributes: Set<String>): Role {
        val attrs = attributes.map { it.trim().lowercase() }.toSet()
        when {
            "\\sent" in attrs -> return Role.SENT
            "\\drafts" in attrs -> return Role.DRAFTS
            "\\trash" in attrs -> return Role.TRASH
            "\\junk" in attrs -> return Role.JUNK
            "\\archive" in attrs -> return Role.ARCHIVE
        }
        val path = if (isSharedFolder(folderName)) folderName else stripPersonalPrefix(folderName)
        val leaf = path.substringAfterLast('.').substringAfterLast('/')
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
    fun sortKey(folderName: String): Int = sortKey(roleOf(folderName))

    fun sortKey(role: Role): Int = when (role) {
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

    // ---- display names (1.1.0) ----

    /**
     * What a folder is called on screen — its true purpose, never
     * a raw path. Working folders read as their role ("Inbox",
     * "Sent", …) however the server spelled the path; other
     * personal folders read as their prefix-normalized path with
     * each segment humanized; shared folders read as
     * "<Owner> · <purpose>" from the path, e.g.
     * Shared.sentinel.TicketIntake → "Sentinel · Ticket Intake"
     * and Shared.sentinel.INBOX → "Sentinel · Inbox".
     */
    fun displayName(folderName: String, attributes: Set<String> = emptySet()): String {
        val roleLabel = when (roleOf(folderName, attributes)) {
            Role.INBOX -> "Inbox"
            Role.SENT -> "Sent"
            Role.DRAFTS -> "Drafts"
            Role.ARCHIVE -> "Archive"
            Role.TRASH -> "Trash"
            Role.JUNK -> "Junk"
            Role.OTHER -> null
        }
        if (isSharedFolder(folderName)) {
            val segments = segmentsOf(folderName)
            val owner = segments.getOrNull(1)?.let { humanizeSegment(it) } ?: "Shared"
            if (segments.size <= 2) return "$owner · Shared root"
            val purpose = roleLabel
                ?: segments.drop(2).joinToString(" / ") { humanizeSegment(it) }
            return "$owner · $purpose"
        }
        if (roleLabel != null) return roleLabel
        return segmentsOf(stripPersonalPrefix(folderName))
            .joinToString(" / ") { humanizeSegment(it) }
    }

    /**
     * One path segment as a person would write it: camelCase and
     * -/_ separators become spaces ("TicketIntake" → "Ticket
     * Intake"), words are title-cased where that is safe — a
     * shouty all-caps word longer than three letters is calmed
     * ("RECEIPTS" → "Receipts"), short all-caps words are left
     * alone as likely acronyms ("VIP" stays "VIP"), and digits
     * pass through untouched.
     */
    fun humanizeSegment(segment: String): String {
        if (segment.isBlank()) return segment
        val words = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                words += current.toString()
                current.clear()
            }
        }
        for (c in segment) {
            when {
                c == '-' || c == '_' || c == ' ' -> flush()
                c.isUpperCase() && current.isNotEmpty() &&
                    (current.last().isLowerCase() || current.last().isDigit()) -> {
                    flush()
                    current.append(c)
                }
                else -> current.append(c)
            }
        }
        flush()
        return words.joinToString(" ") { titleWord(it) }
    }

    private fun titleWord(word: String): String {
        if (word.isEmpty()) return word
        val hasLetter = word.any { it.isLetter() }
        val allCaps = hasLetter && word.all { !it.isLetter() || it.isUpperCase() }
        return when {
            allCaps && word.length <= 3 -> word
            allCaps -> word.lowercase().replaceFirstChar { it.uppercase() }
            word.first().isLowerCase() -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
    }
}
