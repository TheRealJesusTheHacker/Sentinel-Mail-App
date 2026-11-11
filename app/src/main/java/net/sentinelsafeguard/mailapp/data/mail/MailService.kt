package net.sentinelsafeguard.mailapp.data.mail

import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.search.AndTerm
import jakarta.mail.search.BodyTerm
import jakarta.mail.search.FlagTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.SearchTerm
import jakarta.mail.search.SubjectTerm
import jakarta.activation.DataHandler
import jakarta.mail.util.ByteArrayDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPFolder
import net.sentinelsafeguard.mailapp.config.MailConfig
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/**
 * Direct IMAP/SMTP to Sentinel Mail via Eclipse Angus Mail (the
 * maintained Jakarta Mail implementation). No hand-rolled protocol
 * code, no proxy through any backend, no server changes.
 *
 * PROVENANCE: this engine is a port of the Command App's audited
 * mail module (data/mail/MailService.kt there) — the connection
 * pattern, the TLS posture, folder listing, message fetch, server
 * search, archive/delete moves, mark-unread, and the SMTP reply
 * path are that code, carried over. The dedicated app extends the
 * same engine with what a mail-first app needs: full compose
 * (new / reply / reply-all / forward) with attachments, IMAP
 * drafts, Sent appends on send, star toggles, arbitrary moves,
 * permanent delete from Trash, attachment download, and list
 * snippets. Extensions are marked as such at each function.
 *
 * The mailbox password is supplied per call from SecureStore by
 * the caller, is only ever sent to the mail server over TLS, and
 * is never logged or written anywhere by this class.
 *
 * TLS posture: both protocols run through [MailTls] — platform
 * chain validation plus SPKI anchor pinning. The
 * `mail.imap.ssl` trust property an early Command App version set
 * is GONE and must never return: in Angus that property replaces
 * chain validation with blind trust for the listed host, the
 * opposite of pinning.
 *
 * The single exception to TLS is a [MailConfig] built by
 * `rehearsalLoopback` (plaintext, loopback only), which exists so
 * the rehearsal harness — a separate JVM program, never shipped —
 * can drive THIS engine against a local fake server. The app only
 * ever builds `MailConfig.production`. A unit test pins that.
 *
 * Cache rule: THIS class still writes nothing to disk — its
 * in-memory list cache (one open folder, dropped on sign-out via
 * [clearCache]) is unchanged. 1.2.0 adds a separate, deliberate
 * layer: the ViewModel-owned offline disk cache
 * (data/cache/MailCache), encrypted at rest, which is what makes
 * the mailbox browsable with no connection. The old
 * "memory-only" family rule is replaced BY THAT CLASS, on the
 * record — see PROGRAM-RECORD's 1.2.0 section.
 */
class MailService(
    private val config: MailConfig,
) {
    private var cachedFolder: String? = null
    private var cachedSummaries: List<MailSummary> = emptyList()

    /** The INBOX state (UIDVALIDITY + highest UID) observed by
     * the most recent [listMessages] of the inbox on THIS service
     * instance, or -1 when this instance hasn't listed it. The
     * ViewModel's seen-marking reads it through [inboxState]
     * instead of opening a second connection (1.2.2, audit
     * finding D9) — and the value is exactly the page the user
     * was shown, so mail that arrived after the listing is never
     * baselined as "seen". The periodic worker builds a fresh
     * service per run, so it always takes the live path. */
    private var lastInboxValidity: Long = -1L
    private var lastInboxMaxUid: Long = -1L

    fun clearCache() {
        cachedFolder = null
        cachedSummaries = emptyList()
        lastInboxValidity = -1L
        lastInboxMaxUid = -1L
    }

    private fun imapSession(): Session {
        val props = Properties().apply {
            put("mail.imap.connectiontimeout", "20000")
            put("mail.imap.timeout", "30000")
            if (config.tlsRequired) {
                put("mail.imap.ssl.enable", "true")
                // Pinning socket factory (object property — the one
                // Angus consults first for implicit-TLS connections).
                put("mail.imap.ssl.socketFactory", MailTls.socketFactory(config))
                put("mail.imap.ssl.checkserveridentity", "true")
            } else {
                put("mail.imap.ssl.enable", "false")
            }
        }
        return Session.getInstance(props)
    }

    private fun <T> withStore(username: String, password: String, block: (Store) -> T): T {
        val store = imapSession().getStore("imap")
        try {
            store.connect(config.host, config.imapPort, username, password)
            return block(store)
        } finally {
            runCatching { store.close() }
        }
    }

    /** Sign-in proof: connect and list. Throws on bad credentials,
     * with the server's refusal as the message. */
    suspend fun verifyCredentials(username: String, password: String): Int =
        withContext(Dispatchers.IO) {
            withStore(username, password) { store ->
                store.defaultFolder.list("*").count { it.exists() }
            }
        }

    /**
     * The folder tree, one row per real mailbox (1.1.0).
     *
     * The LIST reply is NOT the tree: Dovecot presents each
     * personal mailbox under several paths at once (bare,
     * INBOX.-prefixed, and the inbox's own alias presentations),
     * and the 1.0.0 code mapped LIST lines 1:1 into the UI — the
     * owner's "everything times three." The raw presentations are
     * now canonicalized and collapsed by [MailRules.dedupeFolders]
     * BEFORE any folder is opened, so duplicate presentations
     * cost no extra server round-trips either; unread counts are
     * fetched once per surviving mailbox. SPECIAL-USE attributes
     * ride along on the result for role/label decisions.
     */
    suspend fun listFolders(username: String, password: String): List<MailFolder> =
        withContext(Dispatchers.IO) {
            withStore(username, password) { store ->
                val listed = store.defaultFolder.list("*")
                    .filter { it.exists() }
                val raw = listed.map { folder ->
                    ListedFolder(
                        name = folder.fullName,
                        attributes = runCatching {
                            (folder as? IMAPFolder)?.attributes?.toSet()
                        }.getOrNull() ?: emptySet(),
                    )
                }
                MailRules.dedupeFolders(raw).map { entry ->
                    val unread = runCatching {
                        val folder = store.getFolder(entry.name)
                        folder.open(Folder.READ_ONLY)
                        val count = folder.unreadMessageCount
                        folder.close(false)
                        count
                    }.getOrDefault(0)
                    MailFolder(
                        name = entry.name,
                        unreadCount = unread,
                        shared = MailRules.isSharedFolder(entry.name),
                        attributes = entry.attributes,
                    )
                }.sortedWith(
                    compareBy(
                        { it.shared },
                        { MailRules.sortKey(MailRules.roleOf(it.name, it.attributes)) },
                        { MailRules.displayName(it.name, it.attributes).lowercase() },
                    )
                )
            }
        }

    suspend fun totalUnread(username: String, password: String): Int =
        listFolders(username, password).sumOf { it.unreadCount }

    /** INBOX state for the periodic checker (extension): the
     * folder's UIDVALIDITY and its current highest UID. The pair
     * is the honest "where did I last look" marker — if the
     * validity ever changes, UIDs are incomparable and the checker
     * re-baselines silently instead of crying new-mail over an
     * old box. */
    suspend fun inboxState(username: String, password: String): Pair<Long, Long> =
        withContext(Dispatchers.IO) {
            // This instance just listed the inbox: answer from
            // that listing, no second connection (see the field
            // note). Anything else takes the live path.
            if (lastInboxValidity >= 0L) {
                return@withContext Pair(lastInboxValidity, lastInboxMaxUid)
            }
            withStore(username, password) { store ->
                val folder = store.getFolder("INBOX")
                folder.open(Folder.READ_ONLY)
                try {
                    val uidFolder = folder as jakarta.mail.UIDFolder
                    val maxUid = if (folder.messageCount == 0) {
                        0L
                    } else {
                        uidFolder.getUID(folder.getMessage(folder.messageCount))
                    }
                    Pair(uidFolder.uidValidity, maxUid)
                } finally {
                    folder.close(false)
                }
            }
        }

    suspend fun listMessages(
        username: String,
        password: String,
        folderName: String,
        limit: Int = 40,
        forceRefresh: Boolean = false,
        snippets: Boolean = true,
    ): List<MailSummary> = withContext(Dispatchers.IO) {
        if (!forceRefresh && cachedFolder == folderName && cachedSummaries.isNotEmpty()) {
            return@withContext cachedSummaries
        }
        withStore(username, password) { store ->
            val folder = store.getFolder(folderName)
            folder.open(Folder.READ_ONLY)
            try {
                val total = folder.messageCount
                if (total == 0) {
                    if (folderName.equals("INBOX", ignoreCase = true)) {
                        lastInboxValidity = (folder as jakarta.mail.UIDFolder).uidValidity
                        lastInboxMaxUid = 0L
                    }
                    return@withStore emptyList()
                }
                val start = maxOf(1, total - limit + 1)
                val messages = folder.getMessages(start, total)
                val profile = FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add(FetchProfile.Item.FLAGS)
                    add(FetchProfile.Item.CONTENT_INFO)
                    // Threading headers (1.2.0): fetched with the
                    // envelope so the list can group conversations
                    // without opening individual messages.
                    add("Message-ID")
                    add("In-Reply-To")
                    add("References")
                }
                folder.fetch(messages, profile)
                val uidFolder = folder as jakarta.mail.UIDFolder
                val summaries = messages.reversed().map { message ->
                    MailSummary(
                        uid = uidFolder.getUID(message),
                        subject = message.subject ?: "(no subject)",
                        from = formatAddresses(message.from),
                        to = formatAddresses(message.allRecipients),
                        dateText = formatDate(message.receivedDate ?: message.sentDate),
                        unread = !message.isSet(Flags.Flag.SEEN),
                        // Extension over the port: a bounded peek at
                        // the first text part (per-section fetch —
                        // BODYSTRUCTURE is already in hand from
                        // CONTENT_INFO), capped hard. Callers that
                        // only need headers (the periodic checker)
                        // pass snippets = false and skip the peek.
                        snippet = if (snippets) {
                            runCatching { peekSnippet(message) }.getOrDefault("")
                        } else {
                            ""
                        },
                        starred = message.isSet(Flags.Flag.FLAGGED),
                        hasAttachments = runCatching { structureHasAttachments(message) }
                            .getOrDefault(false),
                        folderName = folderName,
                        messageId = runCatching { message.getHeader("Message-ID")?.firstOrNull() }
                            .getOrNull(),
                        inReplyTo = runCatching { message.getHeader("In-Reply-To")?.firstOrNull() }
                            .getOrNull(),
                        references = runCatching { message.getHeader("References")?.firstOrNull() }
                            .getOrNull(),
                    )
                }
                cachedFolder = folderName
                cachedSummaries = summaries
                if (folderName.equals("INBOX", ignoreCase = true)) {
                    lastInboxValidity = uidFolder.uidValidity
                    lastInboxMaxUid = summaries.firstOrNull()?.uid ?: 0L
                }
                summaries
            } finally {
                folder.close(false)
            }
        }
    }

    suspend fun getMessage(
        username: String,
        password: String,
        folderName: String,
        uid: Long,
    ): MailMessage = withContext(Dispatchers.IO) {
        withStore(username, password) { store ->
            val folder = store.getFolder(folderName)
            folder.open(Folder.READ_WRITE)
            try {
                val uidFolder = folder as jakarta.mail.UIDFolder
                val message = uidFolder.getMessageByUID(uid)
                    ?: throw IllegalStateException("That message could not be found. It may have been moved or deleted — go back and refresh the list.")
                val parts = collectParts(message)
                val textPart = parts.firstOrNull { it.isMimeType("text/plain") && !isAttachment(it) }
                val htmlPart = parts.firstOrNull { it.isMimeType("text/html") && !isAttachment(it) }
                val html = htmlPart?.let { runCatching { it.content?.toString() }.getOrNull() }
                val bodyText = when {
                    textPart != null -> runCatching { textPart.content?.toString() }.getOrNull() ?: ""
                    html != null -> MailText.htmlToText(html)
                    else -> ""
                }
                val mail = MailMessage(
                    uid = uid,
                    subject = message.subject ?: "(no subject)",
                    from = formatAddresses(message.from),
                    to = formatAddresses(message.allRecipients),
                    cc = formatAddresses(message.getRecipients(Message.RecipientType.CC)),
                    dateText = formatDate(message.receivedDate ?: message.sentDate),
                    bodyText = bodyText,
                    htmlBody = html,
                    unread = !message.isSet(Flags.Flag.SEEN),
                    starred = message.isSet(Flags.Flag.FLAGGED),
                    attachments = parts.filter { isAttachment(it) }.map { part ->
                        MailAttachment(
                            partIndex = partIndexOf(message, part),
                            fileName = part.fileName ?: "attachment",
                            mimeType = runCatching { part.contentType.substringBefore(';') }
                                .getOrDefault("application/octet-stream"),
                            sizeBytes = runCatching { part.size.toLong() }.getOrDefault(-1L),
                        )
                    },
                    messageId = runCatching { message.getHeader("Message-ID")?.firstOrNull() }.getOrNull(),
                    references = runCatching { message.getHeader("References")?.firstOrNull() }.getOrNull(),
                )
                message.setFlag(Flags.Flag.SEEN, true)
                mail
            } finally {
                folder.close(false)
            }
        }
    }

    /** Download one attachment's bytes (extension). Capped at the
     * server's own 25 MB message-size ceiling. */
    suspend fun downloadAttachment(
        username: String,
        password: String,
        folderName: String,
        uid: Long,
        partIndex: String,
    ): ByteArray = withContext(Dispatchers.IO) {
        withStore(username, password) { store ->
            val folder = store.getFolder(folderName)
            folder.open(Folder.READ_ONLY)
            try {
                val uidFolder = folder as jakarta.mail.UIDFolder
                val message = uidFolder.getMessageByUID(uid)
                    ?: throw IllegalStateException("That message could not be found.")
                val part = findPartByIndex(message, partIndex)
                    ?: throw IllegalStateException("That attachment could not be found.")
                // Never hand back a silently truncated file
                // (1.2.2, audit finding D5): over the cap is an
                // honest refusal, not a partial download wearing
                // the whole file's name.
                val declared = runCatching { part.size.toLong() }.getOrDefault(-1L)
                if (declared > MAX_ATTACHMENT_BYTES) {
                    throw IllegalStateException(
                        "That attachment is larger than the 25 MB limit, so it can't be downloaded whole."
                    )
                }
                part.inputStream.use { input ->
                    readCappedStrict(input, MAX_ATTACHMENT_BYTES)
                }
            } finally {
                folder.close(false)
            }
        }
    }

    suspend fun search(
        username: String,
        password: String,
        folderName: String,
        query: String,
        limit: Int = 50,
        filter: SearchFilter = SearchFilter.ALL,
    ): List<MailSummary> = withContext(Dispatchers.IO) {
        withStore(username, password) { store ->
            searchOnFolder(store, folderName, query, limit, filter)
        }
    }

    /** One folder's search on an ALREADY-OPEN connection
     * (1.2.2): the found set is fetched in ONE batched
     * FetchProfile round — envelope, flags, and BODYSTRUCTURE —
     * so attachment flags on search rows are real (1.2.0/1.2.1
     * hardcoded them false and let every property read trickle
     * out as its own round trip; audit findings D7/D8). */
    private fun searchOnFolder(
        store: Store,
        folderName: String,
        query: String,
        limit: Int,
        filter: SearchFilter = SearchFilter.ALL,
    ): List<MailSummary> {
        val folder = store.getFolder(folderName)
        folder.open(Folder.READ_ONLY)
        try {
            val term: SearchTerm = searchTermFor(query, filter)
            val found = folder.search(term).takeLast(limit).reversed()
            if (found.isEmpty()) return emptyList()
            val profile = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
                add(FetchProfile.Item.CONTENT_INFO)
                add("Message-ID")
            }
            folder.fetch(found.toTypedArray(), profile)
            val uidFolder = folder as jakarta.mail.UIDFolder
            val summaries = found.map { message ->
                MailSummary(
                    uid = uidFolder.getUID(message),
                    subject = message.subject ?: "(no subject)",
                    from = formatAddresses(message.from),
                    to = formatAddresses(message.allRecipients),
                    dateText = formatDate(message.receivedDate ?: message.sentDate),
                    unread = !message.isSet(Flags.Flag.SEEN),
                    snippet = "",
                    starred = message.isSet(Flags.Flag.FLAGGED),
                    hasAttachments = runCatching { structureHasAttachments(message) }
                        .getOrDefault(false),
                    folderName = folderName,
                    messageId = runCatching { message.getHeader("Message-ID")?.firstOrNull() }
                        .getOrNull(),
                )
            }
            // ATTACHMENTS is the one filter with no server-side
            // SEARCH term (IMAP has none): it narrows the fetched
            // page on the structure-derived flags — real flags
            // since the D7 fetch fix, and the cap disclosure in
            // the UI covers the page it narrowed.
            return if (filter == SearchFilter.ATTACHMENTS) {
                summaries.filter { it.hasAttachments }
            } else {
                summaries
            }
        } finally {
            folder.close(false)
        }
    }

    /**
     * SEARCH ACROSS ALL FOLDERS (1.2.0). The same server-side
     * subject/from/body search, run folder by folder over the
     * mailbox's real folder list, merged by [SearchMerge]
     * (round-robin, capped, every result folder-labeled). A
     * folder the server refuses is reported in the result, not
     * silently skipped — the UI states the searched scope.
     */
    suspend fun searchAll(
        username: String,
        password: String,
        query: String,
        folderNames: List<String>,
        filter: SearchFilter = SearchFilter.ALL,
    ): AllFolderSearch = withContext(Dispatchers.IO) {
        val perFolder = mutableListOf<List<MailSummary>>()
        val searched = mutableListOf<String>()
        val failed = mutableListOf<String>()
        // ONE connection for the whole sweep (1.2.2): the
        // 1.2.0 shape connected + logged in once per folder,
        // which on a real mailbox is dozens of TLS handshakes
        // standing between the user and their results.
        withStore(username, password) { store ->
            for (name in folderNames) {
                try {
                    perFolder += searchOnFolder(
                        store, name, query, SearchMerge.PER_FOLDER_CAP, filter,
                    )
                    searched += name
                } catch (e: Exception) {
                    failed += name
                }
            }
        }
        AllFolderSearch(
            results = SearchMerge.merge(perFolder),
            searchedFolders = searched,
            failedFolders = failed,
            capped = perFolder.sumOf { it.size } > SearchMerge.TOTAL_CAP,
        )
    }

    // ---- folder management (1.2.0) ----
    //
    // The app-side rails live in MailRules.manageRail; these are
    // the raw operations. Refusals from the server surface as
    // exceptions carrying the server's answer, which is exactly
    // what the UI reports — for shared folders that refusal IS
    // the estate's ACL speaking.

    /** Create a personal folder (parents are created by the
     * server as needed, per Dovecot's CREATE semantics). */
    suspend fun createFolder(username: String, password: String, fullName: String) =
        withContext(Dispatchers.IO) {
            withStore(username, password) { store ->
                val folder = store.getFolder(fullName)
                if (folder.exists()) {
                    throw IllegalStateException("A folder with that name already exists.")
                }
                if (!folder.create(Folder.HOLDS_MESSAGES)) {
                    throw IllegalStateException("The server refused to create that folder.")
                }
                invalidateCache()
            }
        }

    suspend fun renameFolder(username: String, password: String, from: String, to: String) =
        withContext(Dispatchers.IO) {
            withStore(username, password) { store ->
                val source = store.getFolder(from)
                if (!source.exists()) {
                    throw IllegalStateException("That folder could not be found.")
                }
                val target = store.getFolder(to)
                if (target.exists()) {
                    throw IllegalStateException("A folder with that name already exists.")
                }
                if (!source.renameTo(target)) {
                    throw IllegalStateException("The server refused to rename that folder.")
                }
                invalidateCache()
            }
        }

    /** Delete a folder and everything in it (recursive). The UI
     * confirms with the message count before this is called. */
    suspend fun deleteFolder(username: String, password: String, fullName: String) =
        withContext(Dispatchers.IO) {
            withStore(username, password) { store ->
                val folder = store.getFolder(fullName)
                if (!folder.exists()) {
                    throw IllegalStateException("That folder could not be found.")
                }
                if (!folder.delete(true)) {
                    throw IllegalStateException("The server refused to delete that folder.")
                }
                invalidateCache()
            }
        }

    /**
     * The server's storage answer for this mailbox (1.2.0), or
     * null when the server does not answer GETQUOTAROOT in a form
     * that carries a STORAGE resource. The Settings surface
     * exists only when this returns a value — no estimate, no
     * placeholder.
     */
    suspend fun quota(username: String, password: String): MailQuota? =
        withContext(Dispatchers.IO) {
            runCatching {
                withStore(username, password) { store ->
                    val folder = store.getFolder("INBOX")
                    folder.open(Folder.READ_ONLY)
                    try {
                        val quotas = (folder as? IMAPFolder)?.getQuota()
                            ?: return@withStore null
                        val storage = quotas.firstNotNullOfOrNull { quota ->
                            quota.resources?.firstOrNull {
                                it.name.equals("STORAGE", ignoreCase = true)
                            }
                        } ?: return@withStore null
                        MailQuota(usedKb = storage.usage, limitKb = storage.limit)
                    } finally {
                        folder.close(false)
                    }
                }
            }.getOrNull()
        }

    /**
     * The message's own inline (Content-ID) parts (1.2.0), for
     * the formatted view's in-place rendering. These bytes came
     * WITH the message — nothing is fetched from the network to
     * render them. Capped per part and in total; oversized parts
     * are simply not returned (the view then shows its
     * broken-image frame, the honest outcome).
     */
    suspend fun inlineImages(
        username: String,
        password: String,
        folderName: String,
        uid: Long,
    ): Map<String, InlineImages.Part> = withContext(Dispatchers.IO) {
        withStore(username, password) { store ->
            val folder = store.getFolder(folderName)
            folder.open(Folder.READ_ONLY)
            try {
                val uidFolder = folder as jakarta.mail.UIDFolder
                val message = uidFolder.getMessageByUID(uid) ?: return@withStore emptyMap()
                val out = LinkedHashMap<String, InlineImages.Part>()
                var totalBytes = 0
                for (part in collectParts(message)) {
                    val cid = runCatching {
                        part.getHeader("Content-ID")?.firstOrNull()
                    }.getOrNull() ?: continue
                    val mime = runCatching { part.contentType.substringBefore(';').trim() }
                        .getOrDefault("")
                    if (!mime.startsWith("image/", ignoreCase = true)) continue
                    if (isAttachment(part)) continue
                    val bytes = runCatching {
                        part.inputStream.use { readCapped(it, MAX_INLINE_IMAGE_BYTES) }
                    }.getOrNull() ?: continue
                    if (totalBytes + bytes.size > MAX_INLINE_TOTAL_BYTES) continue
                    totalBytes += bytes.size
                    out[InlineImages.normalize(cid)] = InlineImages.Part(mime, bytes)
                }
                out
            } finally {
                folder.close(false)
            }
        }
    }

    /** Locate a message by its Message-ID inside [folderName]
     * (1.2.0 — snooze's return path: UIDs do not survive moves,
     * Message-IDs do). Null when it is not there anymore. */
    suspend fun findUidByMessageId(
        username: String,
        password: String,
        folderName: String,
        messageId: String,
    ): Long? = withContext(Dispatchers.IO) {
        runCatching {
            withStore(username, password) { store ->
                val folder = store.getFolder(folderName)
                if (!folder.exists()) return@withStore null
                folder.open(Folder.READ_ONLY)
                try {
                    val found = folder.search(
                        jakarta.mail.search.MessageIDTerm(messageId),
                    )
                    val uidFolder = folder as jakarta.mail.UIDFolder
                    found.firstOrNull()?.let { uidFolder.getUID(it) }
                } finally {
                    folder.close(false)
                }
            }
        }.getOrNull()
    }

    /** The Message-ID of one message, headers only (1.2.2,
     * audit finding D6): the folder opens READ_ONLY (EXAMINE —
     * no \Seen is set) and no body is fetched. Snooze's fallback
     * path uses this; 1.2.0/1.2.1 opened the whole message
     * READ_WRITE just to learn the ID, marking the message read
     * as a side effect of snoozing it. Null when the message is
     * gone or carries no Message-ID. */
    suspend fun messageIdForUid(
        username: String,
        password: String,
        folderName: String,
        uid: Long,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            withStore(username, password) { store ->
                val folder = store.getFolder(folderName)
                folder.open(Folder.READ_ONLY)
                try {
                    val uidFolder = folder as jakarta.mail.UIDFolder
                    val message = uidFolder.getMessageByUID(uid)
                        ?: return@withStore null
                    message.getHeader("Message-ID")?.firstOrNull()
                } finally {
                    folder.close(false)
                }
            }
        }.getOrNull()
    }

    /** Move a message identified by Message-ID (see above).
     * False when the message is gone — the snooze record is then
     * dropped, because there is nothing left to return. */
    suspend fun moveByMessageId(
        username: String,
        password: String,
        fromFolder: String,
        messageId: String,
        toFolder: String,
    ): Boolean {
        val uid = findUidByMessageId(username, password, fromFolder, messageId)
            ?: return false
        move(username, password, fromFolder, uid, toFolder)
        return true
    }

    /** Archive = move to the keep-forever folder. */
    suspend fun archive(username: String, password: String, folderName: String, uid: Long) =
        move(username, password, folderName, uid, MailRules.archiveTarget())

    /** Delete = move to Trash, or permanent when already there. */
    suspend fun delete(username: String, password: String, folderName: String, uid: Long) {
        if (MailRules.deleteIsPermanent(folderName)) {
            deleteForever(username, password, folderName, uid)
        } else {
            move(username, password, folderName, uid, MailRules.trashTarget())
        }
    }

    suspend fun deleteForever(username: String, password: String, folderName: String, uid: Long) =
        withContext(Dispatchers.IO) {
            withStore(username, password) { store ->
                val folder = store.getFolder(folderName)
                folder.open(Folder.READ_WRITE)
                try {
                    val uidFolder = folder as jakarta.mail.UIDFolder
                    val message = uidFolder.getMessageByUID(uid)
                        ?: throw IllegalStateException("That message could not be found. It may have been moved or deleted.")
                    message.setFlag(Flags.Flag.DELETED, true)
                    invalidateCache()
                } finally {
                    folder.close(true)
                }
            }
        }

    /** Move to any folder (extension — the port generalized). */
    suspend fun moveTo(username: String, password: String, folderName: String, uid: Long, target: String) =
        move(username, password, folderName, uid, target)

    private suspend fun move(
        username: String,
        password: String,
        fromFolder: String,
        uid: Long,
        toFolder: String,
    ) = withContext(Dispatchers.IO) {
        withStore(username, password) { store ->
            val source = store.getFolder(fromFolder)
            source.open(Folder.READ_WRITE)
            // The manual path flags \Deleted and needs the close
            // to expunge; a successful IMAP MOVE leaves nothing
            // to expunge and must not sweep other sessions'
            // \Deleted mail on its way out.
            var expungeOnClose = true
            try {
                val uidFolder = source as jakarta.mail.UIDFolder
                val message = uidFolder.getMessageByUID(uid)
                    ?: throw IllegalStateException("That message could not be found. It may have been moved or deleted — go back and refresh the list.")
                val target = store.getFolder(toFolder)
                // A move target that does not exist yet is created
                // (the Snoozed folder above all, and Archive/Trash
                // on a fresh mailbox) — the same pattern send()
                // already used for Sent.
                if (!target.exists()) target.create(Folder.HOLDS_MESSAGES)
                // IMAP MOVE first (1.2.2, capability gap G1):
                // Dovecot advertises the MOVE extension, which is
                // ONE atomic operation instead of copy + flag +
                // expunge — no window where the message exists
                // twice, no expunge of other clients' \Deleted
                // mail. Angus itself falls back when a server
                // lacks MOVE; any failure here drops to the manual
                // sequence below, which works everywhere.
                val moved = runCatching {
                    val imapSource = source as? IMAPFolder
                        ?: throw IllegalStateException("Not an IMAP folder.")
                    imapSource.moveMessages(arrayOf<Message>(message), target)
                }.isSuccess
                if (!moved) {
                    source.copyMessages(arrayOf<Message>(message), target)
                    message.setFlag(Flags.Flag.DELETED, true)
                } else {
                    expungeOnClose = false
                }
                invalidateCache()
            } finally {
                source.close(expungeOnClose)
            }
        }
    }

    suspend fun setSeen(username: String, password: String, folderName: String, uid: Long, seen: Boolean) =
        setFlag(username, password, folderName, uid, Flags.Flag.SEEN, seen)

    suspend fun setStarred(username: String, password: String, folderName: String, uid: Long, starred: Boolean) =
        setFlag(username, password, folderName, uid, Flags.Flag.FLAGGED, starred)

    private suspend fun setFlag(
        username: String,
        password: String,
        folderName: String,
        uid: Long,
        flag: Flags.Flag,
        value: Boolean,
    ) = withContext(Dispatchers.IO) {
        withStore(username, password) { store ->
            val folder = store.getFolder(folderName)
            folder.open(Folder.READ_WRITE)
            try {
                val uidFolder = folder as jakarta.mail.UIDFolder
                val message = uidFolder.getMessageByUID(uid)
                    ?: throw IllegalStateException("That message could not be found. It may have been moved or deleted.")
                message.setFlag(flag, value)
                invalidateCache()
            } finally {
                folder.close(false)
            }
        }
    }

    /**
     * Send a composed message over SMTP submission, then file a
     * copy in Sent (extension — built on the ported reply path's
     * session/TLS pattern). The From identity follows [fromFolder]
     * by the family rule in MailRules.
     */
    suspend fun send(
        username: String,
        password: String,
        fromFolder: String,
        model: ComposeModel,
    ) = withContext(Dispatchers.IO) {
        val identity = MailRules.identityForFolder(fromFolder, username)
        val message = buildMessage(username, identity, model)
        val transport: Transport = smtpSession().getTransport("smtp")
        try {
            transport.connect(config.host, config.smtpPort, username, password)
            transport.sendMessage(message, message.allRecipients)
        } finally {
            runCatching { transport.close() }
        }
        // File the Sent copy. A filing failure must not pretend the
        // send failed — the mail is gone; surface the distinction.
        runCatching {
            withStore(username, password) { store ->
                val sent = store.getFolder(MailRules.sentTarget())
                if (!sent.exists()) sent.create(Folder.HOLDS_MESSAGES)
                message.setFlag(Flags.Flag.SEEN, true)
                sent.appendMessages(arrayOf<Message>(message))
            }
        }.onFailure {
            throw IllegalStateException(
                "Your message was sent, but the copy could not be filed in Sent: ${it.message}"
            )
        }
        invalidateCache()
    }

    /**
     * Save (or re-save) a draft in the Drafts folder (extension).
     * Returns the new draft's UID. Re-saving deletes the previous
     * copy — a draft has exactly one live version.
     */
    suspend fun saveDraft(
        username: String,
        password: String,
        model: ComposeModel,
    ): Long = withContext(Dispatchers.IO) {
        val message = buildMessage(username, username, model)
        message.setFlag(Flags.Flag.DRAFT, true)
        message.setFlag(Flags.Flag.SEEN, true)
        withStore(username, password) { store ->
            val drafts = store.getFolder(MailRules.draftsTarget())
            if (!drafts.exists()) drafts.create(Folder.HOLDS_MESSAGES)
            drafts.open(Folder.READ_WRITE)
            try {
                if (model.draftUid != null) {
                    val uidFolder = drafts as jakarta.mail.UIDFolder
                    runCatching { uidFolder.getMessageByUID(model.draftUid) }
                        .getOrNull()?.setFlag(Flags.Flag.DELETED, true)
                }
                drafts.appendMessages(arrayOf<Message>(message))
                val uidFolder = drafts as jakarta.mail.UIDFolder
                val appended = drafts.getMessage(drafts.messageCount)
                val newUid = uidFolder.getUID(appended)
                invalidateCache()
                newUid
            } finally {
                drafts.close(true)
            }
        }
    }

    /** Delete a draft outright (after it is sent, or discarded). */
    suspend fun deleteDraft(username: String, password: String, draftUid: Long) =
        deleteForever(username, password, MailRules.draftsTarget(), draftUid)

    /** Open a draft back into the compose model (extension). */
    suspend fun openDraft(
        username: String,
        password: String,
        draftUid: Long,
    ): ComposeModel = withContext(Dispatchers.IO) {
        withStore(username, password) { store ->
            val drafts = store.getFolder(MailRules.draftsTarget())
            drafts.open(Folder.READ_ONLY)
            try {
                val uidFolder = drafts as jakarta.mail.UIDFolder
                val message = uidFolder.getMessageByUID(draftUid)
                    ?: throw IllegalStateException("That draft could not be found.")
                val parts = collectParts(message)
                val textPart = parts.firstOrNull { it.isMimeType("text/plain") && !isAttachment(it) }
                val htmlPart = parts.firstOrNull { it.isMimeType("text/html") && !isAttachment(it) }
                val body = when {
                    textPart != null -> runCatching { textPart.content?.toString() }.getOrNull() ?: ""
                    htmlPart != null -> MailText.htmlToText(
                        runCatching { htmlPart.content?.toString() }.getOrNull() ?: ""
                    )
                    else -> ""
                }
                ComposeModel(
                    to = formatAddresses(message.getRecipients(Message.RecipientType.TO)),
                    cc = formatAddresses(message.getRecipients(Message.RecipientType.CC)),
                    bcc = formatAddresses(message.getRecipients(Message.RecipientType.BCC)),
                    subject = message.subject ?: "",
                    body = body,
                    attachments = parts.filter { isAttachment(it) }.map { part ->
                        ComposeAttachment(
                            fileName = part.fileName ?: "attachment",
                            mimeType = runCatching { part.contentType.substringBefore(';') }
                                .getOrDefault("application/octet-stream"),
                            // Strict, like downloads (D5): a
                            // truncated draft attachment would be
                            // re-sent corrupted under its real name.
                            bytes = part.inputStream.use {
                                readCappedStrict(it, MAX_ATTACHMENT_BYTES)
                            },
                        )
                    },
                    draftUid = draftUid,
                    inReplyTo = runCatching { message.getHeader("In-Reply-To")?.firstOrNull() }.getOrNull(),
                    references = runCatching { message.getHeader("References")?.firstOrNull() }.getOrNull(),
                )
            } finally {
                drafts.close(false)
            }
        }
    }

    private fun smtpSession(): Session {
        val props = Properties().apply {
            put("mail.smtp.host", config.host)
            put("mail.smtp.port", config.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "30000")
            if (config.tlsRequired) {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
                // Same pinning factory, used for the STARTTLS upgrade
                // (Angus consults this property first in startTLS too).
                put("mail.smtp.ssl.socketFactory", MailTls.socketFactory(config))
                put("mail.smtp.ssl.checkserveridentity", "true")
            }
        }
        return Session.getInstance(props)
    }

    private fun buildMessage(
        username: String,
        identity: String,
        model: ComposeModel,
    ): MimeMessage {
        val message = MimeMessage(smtpSession())
        message.setFrom(InternetAddress(identity))
        for (addr in MailText.parseAddressList(model.to)) {
            message.addRecipient(Message.RecipientType.TO, InternetAddress(addr))
        }
        for (addr in MailText.parseAddressList(model.cc)) {
            message.addRecipient(Message.RecipientType.CC, InternetAddress(addr))
        }
        for (addr in MailText.parseAddressList(model.bcc)) {
            message.addRecipient(Message.RecipientType.BCC, InternetAddress(addr))
        }
        message.subject = model.subject
        message.sentDate = Date()
        model.inReplyTo?.takeIf { it.isNotBlank() }?.let { message.setHeader("In-Reply-To", it) }
        model.references?.takeIf { it.isNotBlank() }?.let { message.setHeader("References", it) }
        message.setHeader("X-Sentinel-Mail-App", "Sentinel Mail App")
        if (model.attachments.isEmpty()) {
            message.setText(model.body)
        } else {
            val text = MimeBodyPart().apply { setText(model.body) }
            val multipart = MimeMultipart()
            multipart.addBodyPart(text)
            for (att in model.attachments) {
                val part = MimeBodyPart()
                part.dataHandler = DataHandler(
                    ByteArrayDataSource(att.bytes, att.mimeType)
                )
                part.fileName = att.fileName
                multipart.addBodyPart(part)
            }
            message.setContent(multipart)
        }
        message.saveChanges()
        return message
    }

    // ---- part traversal helpers (extensions) ----

    private fun collectParts(part: Part): List<Part> {
        val out = mutableListOf<Part>()
        fun walk(p: Part) {
            if (p.isMimeType("multipart/*")) {
                val mp = p.content as? Multipart ?: return
                for (i in 0 until mp.count) walk(mp.getBodyPart(i))
            } else {
                out.add(p)
            }
        }
        walk(part)
        return out
    }

    private fun isAttachment(part: Part): Boolean {
        val disp = runCatching { part.disposition }.getOrNull()
        if (disp != null && disp.equals(Part.ATTACHMENT, ignoreCase = true)) return true
        // A named non-text part is an attachment for our purposes.
        val named = runCatching { part.fileName }.getOrNull() != null
        return named && !part.isMimeType("text/plain") && !part.isMimeType("text/html")
    }

    private fun structureHasAttachments(message: Message): Boolean =
        collectParts(message).any { isAttachment(it) }

    private fun peekSnippet(message: Message): String {
        val parts = collectParts(message)
        val part = parts.firstOrNull { it.isMimeType("text/plain") && !isAttachment(it) }
            ?: parts.firstOrNull { it.isMimeType("text/html") && !isAttachment(it) }
            ?: return ""
        val raw = part.inputStream.use { readCapped(it, SNIPPET_READ_CAP).toString(Charsets.UTF_8) }
        val text = if (part.isMimeType("text/html")) MailText.htmlToText(raw) else raw
        return MailText.makeSnippet(text)
    }

    /** IMAP body-part index path ("2", "1.3") for a part, computed
     * from the message's own structure. */
    private fun partIndexOf(root: Part, target: Part): String {
        fun walk(p: Part, prefix: String): String? {
            if (p === target) return prefix
            if (p.isMimeType("multipart/*")) {
                val mp = p.content as? Multipart ?: return null
                for (i in 0 until mp.count) {
                    val childPrefix = if (prefix.isEmpty()) "${i + 1}" else "$prefix.${i + 1}"
                    walk(mp.getBodyPart(i), childPrefix)?.let { return it }
                }
            }
            return null
        }
        return walk(root, if (root.isMimeType("multipart/*")) "" else "1") ?: "1"
    }

    private fun findPartByIndex(root: Part, index: String): Part? {
        var current: Part = root
        if (index == "1" && !root.isMimeType("multipart/*")) return root
        for (step in index.split('.')) {
            val n = step.toIntOrNull() ?: return null
            if (!current.isMimeType("multipart/*")) return null
            val mp = current.content as? Multipart ?: return null
            if (n < 1 || n > mp.count) return null
            current = mp.getBodyPart(n - 1)
        }
        return current
    }

    private fun readCapped(input: InputStream, cap: Int): ByteArray {
        val buffer = ByteArray(cap)
        var read = 0
        while (read < cap) {
            val n = input.read(buffer, read, cap - read)
            if (n < 0) break
            read += n
        }
        return buffer.copyOf(read)
    }

    /** [readCapped] where a truncated result would be a lie
     * (downloads, draft attachments): reads one byte past the
     * cap and refuses when the stream has more to give. */
    private fun readCappedStrict(input: InputStream, cap: Int): ByteArray {
        val bytes = readCapped(input, cap + 1)
        if (bytes.size > cap) {
            throw IllegalStateException(
                "That attachment is larger than the 25 MB limit, so it can't be loaded whole."
            )
        }
        return bytes
    }

    private fun invalidateCache() {
        cachedFolder = null
        cachedSummaries = emptyList()
    }

    private fun formatAddresses(addresses: Array<out jakarta.mail.Address>?): String =
        addresses?.joinToString(", ") { it.toString() } ?: ""

    private fun formatDate(date: Date?): String {
        if (date == null) return ""
        return SimpleDateFormat("EEE, MMM d, yyyy h:mm a", Locale.US).format(date)
    }

    companion object {
        /** The server's own message-size ceiling (SIZE 26214400,
         * verified in the live EHLO on 2026-11-09). */
        const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024
        const val SNIPPET_READ_CAP = 8192

        /** Inline-image caps (1.2.0): one part, then the whole
         * message's set. Generous for real mail (logos and inline
         * photos are tens of KB), hard against a hostile part. */
        const val MAX_INLINE_IMAGE_BYTES = 5 * 1024 * 1024
        const val MAX_INLINE_TOTAL_BYTES = 10 * 1024 * 1024
    }
}

/** Search narrowing (1.2.2, capability gap G2). UNREAD and
 * STARRED compose into the server-side SEARCH as flag terms —
 * the server does the narrowing, not the phone. ATTACHMENTS
 * has no IMAP SEARCH term; it narrows the fetched page on its
 * structure-derived attachment flags (see searchOnFolder). */
enum class SearchFilter { ALL, UNREAD, STARRED, ATTACHMENTS }

/** The SEARCH term a query + filter compiles to. Internal and
 * pure so the suite can pin the composition: the text legs are
 * always subject OR sender OR body; flag filters AND on top. */
internal fun searchTermFor(query: String, filter: SearchFilter): SearchTerm {
    val text: SearchTerm = OrTerm(
        arrayOf(SubjectTerm(query), FromStringTerm(query), BodyTerm(query))
    )
    return when (filter) {
        SearchFilter.ALL, SearchFilter.ATTACHMENTS -> text
        SearchFilter.UNREAD -> AndTerm(text, FlagTerm(Flags(Flags.Flag.SEEN), false))
        SearchFilter.STARRED -> AndTerm(text, FlagTerm(Flags(Flags.Flag.FLAGGED), true))
    }
}

/** The all-folders search report (1.2.0): the merged results,
 * which folders were actually searched, which refused, and
 * whether the cap trimmed the total — the UI states all of it,
 * so the scope on screen is the scope that ran. */
data class AllFolderSearch(
    val results: List<MailSummary>,
    val searchedFolders: List<String>,
    val failedFolders: List<String>,
    val capped: Boolean,
)
