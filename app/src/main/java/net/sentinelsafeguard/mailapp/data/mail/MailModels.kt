package net.sentinelsafeguard.mailapp.data.mail

/** One row of the folder tree. Ported from the Command App.
 * [attributes] carries the server's LIST attributes for the
 * folder (SPECIAL-USE flags like \Sent where the server offers
 * them) so role and label decisions never guess from the name
 * alone when the server has said outright. */
data class MailFolder(
    val name: String,
    val unreadCount: Int,
    val shared: Boolean,
    val attributes: Set<String> = emptySet(),
)

/** One raw LIST presentation of a folder, before canonical
 * dedupe (see MailRules.dedupeFolders). A single real mailbox
 * can arrive as several of these — bare and INBOX.-prefixed
 * presentations of the same box — and this type is what the
 * canned-LIST unit tests drive, with no mail library involved. */
data class ListedFolder(
    val name: String,
    val attributes: Set<String> = emptySet(),
)

/** One row of a message list. Extended over the Command App's
 * summary with the star, the attachment marker, and a real snippet
 * (the dedicated app fetches a bounded text peek per list page). */
data class MailSummary(
    val uid: Long,
    val subject: String,
    val from: String,
    val to: String = "",
    val dateText: String,
    val unread: Boolean,
    val snippet: String,
    val starred: Boolean = false,
    val hasAttachments: Boolean = false,
)

/** A downloadable/viewable attachment part of a message. */
data class MailAttachment(
    val partIndex: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
)

/** A fully opened message. [bodyText] is the family's plain-text
 * rendering (the audited default view); [htmlBody] is the raw HTML
 * part when one exists — it reaches a screen only through
 * HtmlSanitizer and the locked-down formatted view, never raw. */
data class MailMessage(
    val uid: Long,
    val subject: String,
    val from: String,
    val to: String,
    val cc: String,
    val dateText: String,
    val bodyText: String,
    val htmlBody: String?,
    val unread: Boolean,
    val starred: Boolean,
    val attachments: List<MailAttachment>,
    val messageId: String?,
    val references: String?,
)

/** What the compose screen edits. Attachments are local files the
 * user picked, carried as bytes + display name + MIME type. */
data class ComposeModel(
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val body: String = "",
    val attachments: List<ComposeAttachment> = emptyList(),
    /** The Drafts-folder UID this compose session is editing, if it
     * was opened from a draft or has been saved as one. */
    val draftUid: Long? = null,
    val inReplyTo: String? = null,
    val references: String? = null,
)

data class ComposeAttachment(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ComposeAttachment && fileName == other.fileName &&
            mimeType == other.mimeType && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        31 * (31 * fileName.hashCode() + mimeType.hashCode()) + bytes.contentHashCode()
}
