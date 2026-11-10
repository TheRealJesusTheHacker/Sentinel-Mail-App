package net.sentinelsafeguard.mailapp.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import net.sentinelsafeguard.mailapp.data.AccountRepository
import net.sentinelsafeguard.mailapp.data.MailAccount
import net.sentinelsafeguard.mailapp.data.SecureStore
import net.sentinelsafeguard.mailapp.data.SettingsStore
import net.sentinelsafeguard.mailapp.data.cache.CachePolicy
import net.sentinelsafeguard.mailapp.data.cache.MailCache
import net.sentinelsafeguard.mailapp.data.mail.AddressBook
import net.sentinelsafeguard.mailapp.data.mail.AllFolderSearch
import net.sentinelsafeguard.mailapp.data.mail.ComposeModel
import net.sentinelsafeguard.mailapp.data.mail.InlineImages
import net.sentinelsafeguard.mailapp.data.mail.MailFolder
import net.sentinelsafeguard.mailapp.data.mail.MailMessage
import net.sentinelsafeguard.mailapp.data.mail.MailQuota
import net.sentinelsafeguard.mailapp.data.mail.MailRules
import net.sentinelsafeguard.mailapp.data.mail.MailService
import net.sentinelsafeguard.mailapp.data.mail.MailSummary
import net.sentinelsafeguard.mailapp.data.mail.RecentSearches
import net.sentinelsafeguard.mailapp.data.mail.SnoozeLogic
import net.sentinelsafeguard.mailapp.notify.NewMailWorker
import net.sentinelsafeguard.mailapp.notify.SnoozeProcessor

/**
 * The screen layer's single gateway to the engine. Every call
 * resolves the ACTIVE account through [AccountRepository] (the
 * multi-account seam) and a [MailService] built for it, so no
 * screen ever touches a store or a password directly.
 */
class MailViewModel(application: Application) : AndroidViewModel(application) {

    val secureStore = SecureStore(application)
    val settingsStore = SettingsStore(application)
    private val accounts = AccountRepository(secureStore)

    /** The 1.2.0 encrypted offline cache. Bound to the account
     * whenever one is active; wiped on sign-out. */
    val cache = MailCache(application)

    private var service: MailService? = null

    var account: MailAccount? by mutableStateOf(null)
        private set

    var locked: Boolean by mutableStateOf(false)
        private set

    var folders: List<MailFolder> by mutableStateOf(emptyList())
        private set
    var foldersLoading: Boolean by mutableStateOf(false)
        private set
    var foldersError: String? by mutableStateOf(null)
        private set

    /** True when the last load fell back to the offline cache —
     * the banners key off this, and the labels say "saved copy",
     * never pretending the data is live. */
    var offline: Boolean by mutableStateOf(false)
        private set

    /** Correspondents harvested from this account's own mail —
     * the autocomplete book. Device-only. */
    var correspondents: List<AddressBook.Entry> by mutableStateOf(emptyList())
        private set

    /** The server's storage answer, when it gives one (null =
     * no surface, by design). */
    var quota: MailQuota? by mutableStateOf(null)
        private set

    var recentSearches: List<String> by mutableStateOf(emptyList())
        private set

    init {
        account = accounts.activeAccount()
        locked = isLockArmed()
        recentSearches = RecentSearches.decode(settingsStore.recentSearchesEncoded)
        if (account != null) {
            refreshService()
            cache.bindAccount(account!!.address)
            correspondents = cache.loadCorrespondents()
            // Snoozed mail also returns when the app opens.
            viewModelScope.launch {
                runCatching { SnoozeProcessor.processDue(getApplication()) }
            }
            loadQuota()
        }
    }

    // ---- account / lock ----

    /** The lock is armed by the setting alone; the lock screen
     * offers biometrics / device credential, plus the app PIN when
     * one has been set. */
    fun isLockArmed(): Boolean = settingsStore.appLockEnabled

    fun lockNow() {
        if (settingsStore.appLockEnabled) locked = true
    }

    fun unlock() {
        locked = false
    }

    private fun refreshService() {
        service = account?.let { accounts.serviceFor(it) }
    }

    fun signIn(
        address: String,
        password: String,
        host: String?,
        imapPort: Int?,
        smtpPort: Int?,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val config = net.sentinelsafeguard.mailapp.config.MailConfig.production(
                    host?.takeIf { it.isNotBlank() } ?: "mail.sentinelsafeguard.net",
                ).copy(
                    imapPort = imapPort ?: 993,
                    smtpPort = smtpPort ?: 587,
                )
                MailService(config).verifyCredentials(address, password)
                accounts.saveAccount(address, password, host, imapPort, smtpPort)
                account = accounts.activeAccount()
                refreshService()
                account?.let { cache.bindAccount(it.address) }
                loadFolders()
                loadQuota()
                onResult(null)
            } catch (e: Exception) {
                onResult(friendlyError(e))
            }
        }
    }

    fun signOut() {
        service?.clearCache()
        cache.clearAll()
        accounts.signOut()
        settingsStore.clear()
        NewMailWorker.cancel(getApplication())
        account = null
        service = null
        folders = emptyList()
        correspondents = emptyList()
        quota = null
        offline = false
    }

    // ---- engine gateway ----

    private fun requireService(): MailService =
        service ?: throw IllegalStateException("Not signed in.")

    private fun requireAccount(): MailAccount =
        account ?: throw IllegalStateException("Not signed in.")

    fun loadFolders() {
        val acc = account ?: return
        foldersLoading = true
        foldersError = null
        viewModelScope.launch {
            try {
                val fresh = requireService().listFolders(acc.address, acc.password)
                folders = fresh
                offline = false
                cache.saveFolders(fresh)
            } catch (e: Exception) {
                val saved = cache.loadFolders()
                if (!saved.isNullOrEmpty()) {
                    // Offline: the saved tree stands in, labeled
                    // by the screen's offline banner (foldersError
                    // stays null — this is a state, not a failure).
                    folders = saved
                    offline = true
                } else {
                    foldersError = friendlyError(e)
                }
            } finally {
                foldersLoading = false
            }
        }
    }

    /** When the folder tree on screen was saved (for the offline
     * label); 0 when it is live. */
    fun foldersSavedAtMs(): Long =
        if (offline) cache.savedAtMs(CachePolicy.Kind.FOLDERS, "tree") else 0L

    /**
     * Network first; the encrypted cache answers when the
     * network can't (and says so via [offline] + [cachedListAtMs]).
     * Every live page is written through to the cache and folded
     * into the correspondent book.
     */
    suspend fun loadMessages(folder: String, forceRefresh: Boolean = false): List<MailSummary> {
        val acc = requireAccount()
        return try {
            val fresh = requireService()
                .listMessages(acc.address, acc.password, folder, forceRefresh = forceRefresh)
            cache.saveSummaries(folder, fresh)
            harvest(fresh)
            if (folder.equals("INBOX", ignoreCase = true)) markInboxSeen()
            fresh
        } catch (e: Exception) {
            val saved = cache.loadSummaries(folder)
            if (saved != null) {
                offline = true
                saved
            } else {
                throw e
            }
        }
    }

    /** Saved-at for a folder's cached list (0 when the on-screen
     * list is live). */
    fun cachedListAtMs(folder: String): Long =
        cache.savedAtMs(CachePolicy.Kind.SUMMARIES, "list|$folder")

    /**
     * Mail the owner has already SEEN in this app must not later
     * arrive as a "new mail" notification: viewing the inbox
     * advances the checker's baseline to the server's current
     * high-water mark (same UIDVALIDITY discipline as the
     * worker — a validity change re-baselines rather than
     * notifying).
     */
    private fun markInboxSeen() {
        val acc = account ?: return
        viewModelScope.launch {
            runCatching {
                val (validity, maxUid) = requireService()
                    .inboxState(acc.address, acc.password)
                when {
                    validity != settingsStore.lastSeenInboxUidValidity -> {
                        settingsStore.lastSeenInboxUidValidity = validity
                        settingsStore.lastSeenInboxUid = maxUid
                    }
                    maxUid > settingsStore.lastSeenInboxUid ->
                        settingsStore.lastSeenInboxUid = maxUid
                }
            }
        }
    }

    private fun harvest(summaries: List<MailSummary>) {
        val acc = account ?: return
        if (summaries.isEmpty()) return
        correspondents = AddressBook.harvest(summaries, acc.address, correspondents)
        cache.saveCorrespondents(correspondents)
    }

    suspend fun search(folder: String, query: String): List<MailSummary> {
        val acc = requireAccount()
        return requireService().search(acc.address, acc.password, folder, query)
    }

    /** All-folders search (1.2.0) over the mailbox's real folder
     * list — the offline tree when the live one isn't loaded. */
    suspend fun searchAll(query: String): AllFolderSearch {
        val acc = requireAccount()
        val names = folders.map { it.name }
            .ifEmpty { cache.loadFolders()?.map { it.name } ?: listOf("INBOX") }
        return requireService().searchAll(acc.address, acc.password, query, names)
    }

    fun recordSearch(query: String) {
        recentSearches = RecentSearches.add(recentSearches, query)
        settingsStore.recentSearchesEncoded = RecentSearches.encode(recentSearches)
    }

    /** An opened message, and whether it came from the offline
     * cache rather than the server. A cached open does NOT mark
     * the message read anywhere — it can't, honestly. */
    data class LoadedMessage(
        val message: MailMessage,
        val fromCache: Boolean,
        val savedAtMs: Long,
    )

    suspend fun loadMessage(folder: String, uid: Long): LoadedMessage {
        val acc = requireAccount()
        return try {
            val fresh = requireService().getMessage(acc.address, acc.password, folder, uid)
            cache.saveMessage(folder, fresh)
            LoadedMessage(fresh, fromCache = false, savedAtMs = 0L)
        } catch (e: Exception) {
            val saved = cache.loadMessage(folder, uid)
            if (saved != null) {
                offline = true
                LoadedMessage(
                    saved,
                    fromCache = true,
                    savedAtMs = cache.savedAtMs(
                        CachePolicy.Kind.MESSAGE, "msg|$folder|$uid",
                    ),
                )
            } else {
                throw e
            }
        }
    }

    suspend fun inlineImages(folder: String, uid: Long): Map<String, InlineImages.Part> {
        val acc = requireAccount()
        return requireService().inlineImages(acc.address, acc.password, folder, uid)
    }

    suspend fun downloadAttachment(folder: String, uid: Long, partIndex: String): ByteArray {
        val acc = requireAccount()
        return requireService().downloadAttachment(acc.address, acc.password, folder, uid, partIndex)
    }

    suspend fun archive(folder: String, uid: Long) {
        val acc = requireAccount()
        requireService().archive(acc.address, acc.password, folder, uid)
    }

    suspend fun delete(folder: String, uid: Long) {
        val acc = requireAccount()
        requireService().delete(acc.address, acc.password, folder, uid)
    }

    suspend fun moveTo(folder: String, uid: Long, target: String) {
        val acc = requireAccount()
        requireService().moveTo(acc.address, acc.password, folder, uid, target)
    }

    suspend fun setSeen(folder: String, uid: Long, seen: Boolean) {
        val acc = requireAccount()
        requireService().setSeen(acc.address, acc.password, folder, uid, seen)
    }

    suspend fun setStarred(folder: String, uid: Long, starred: Boolean) {
        val acc = requireAccount()
        requireService().setStarred(acc.address, acc.password, folder, uid, starred)
    }

    suspend fun send(fromFolder: String, model: ComposeModel) {
        val acc = requireAccount()
        requireService().send(acc.address, acc.password, fromFolder, model)
        // A sent draft is consumed: remove its Drafts copy.
        if (model.draftUid != null) {
            runCatching {
                requireService().deleteDraft(acc.address, acc.password, model.draftUid)
            }
        }
    }

    suspend fun saveDraft(model: ComposeModel): Long {
        val acc = requireAccount()
        return requireService().saveDraft(acc.address, acc.password, model)
    }

    suspend fun openDraft(draftUid: Long): ComposeModel {
        val acc = requireAccount()
        return requireService().openDraft(acc.address, acc.password, draftUid)
    }

    suspend fun deleteDraft(draftUid: Long) {
        val acc = requireAccount()
        requireService().deleteDraft(acc.address, acc.password, draftUid)
    }

    // ---- folder management (1.2.0) ----

    private fun nameProblemText(problem: MailRules.NameProblem): String = when (problem) {
        MailRules.NameProblem.BLANK -> "Give the folder a name first."
        MailRules.NameProblem.TOO_LONG -> "That name is too long — 64 characters at most."
        MailRules.NameProblem.EMPTY_SEGMENT -> "That name has an empty section (a doubled, leading, or trailing separator)."
        MailRules.NameProblem.SHARED_NAMESPACE -> "Names beginning with \"Shared\" belong to the shared folders, which are managed on the server."
        MailRules.NameProblem.BAD_CHARS -> "That name contains a character folder names can't use."
    }

    private val blockedSpecialText =
        "That folder has a job — Inbox, Sent, Drafts, Archive, Trash, and Junk are the mailbox's furniture and can't be renamed or deleted."

    /** Create a personal folder. The callback gets null on
     * success or the honest refusal (ours or the server's). */
    fun createFolder(name: String, onResult: (String?) -> Unit) {
        val acc = account ?: return onResult("Not signed in.")
        MailRules.validateFolderName(name)?.let { return onResult(nameProblemText(it)) }
        viewModelScope.launch {
            try {
                requireService().createFolder(acc.address, acc.password, name.trim())
                loadFolders()
                onResult(null)
            } catch (e: Exception) {
                onResult(friendlyError(e))
            }
        }
    }

    /** Rename a folder's LAST name segment (its leaf) — the rest
     * of its path, and anything filed under it, is untouched.
     * Shared folders go to the server for the honest answer. */
    fun renameFolder(folder: MailFolder, newLeaf: String, onResult: (String?) -> Unit) {
        val acc = account ?: return onResult("Not signed in.")
        if (MailRules.manageRail(folder.name, folder.attributes) ==
            MailRules.ManageRail.BLOCKED_SPECIAL
        ) {
            return onResult(blockedSpecialText)
        }
        MailRules.validateFolderName(newLeaf)?.let { return onResult(nameProblemText(it)) }
        val delimiter = if ('/' in folder.name) '/' else '.'
        val parent = folder.name.substringBeforeLast(delimiter, "")
        val target = if (parent.isEmpty()) newLeaf.trim()
        else parent + delimiter + newLeaf.trim()
        viewModelScope.launch {
            try {
                requireService().renameFolder(acc.address, acc.password, folder.name, target)
                loadFolders()
                onResult(null)
            } catch (e: Exception) {
                onResult(friendlyError(e))
            }
        }
    }

    /** Delete a folder and its contents (the UI confirms with
     * the count first). Same rails as rename. */
    fun deleteFolder(folder: MailFolder, onResult: (String?) -> Unit) {
        val acc = account ?: return onResult("Not signed in.")
        if (MailRules.manageRail(folder.name, folder.attributes) ==
            MailRules.ManageRail.BLOCKED_SPECIAL
        ) {
            return onResult(blockedSpecialText)
        }
        viewModelScope.launch {
            try {
                requireService().deleteFolder(acc.address, acc.password, folder.name)
                loadFolders()
                onResult(null)
            } catch (e: Exception) {
                onResult(friendlyError(e))
            }
        }
    }

    // ---- quota (1.2.0) ----

    fun loadQuota() {
        val acc = account ?: return
        viewModelScope.launch {
            quota = runCatching {
                requireService().quota(acc.address, acc.password)
            }.getOrNull()
        }
    }

    // ---- snooze (1.2.0) ----

    /**
     * Move a message to Snoozed and record its return. The
     * record is keyed by Message-ID; a message whose headers
     * carry none is fetched first (rare — the list now fetches
     * Message-ID with the envelope).
     */
    suspend fun snooze(folder: String, summary: MailSummary, choice: SnoozeLogic.Choice) {
        val acc = requireAccount()
        val messageId = summary.messageId
            ?: requireService().getMessage(acc.address, acc.password, folder, summary.uid).messageId
            ?: throw IllegalStateException(
                "That message carries no Message-ID, so its return can't be tracked. Snooze isn't available for it."
            )
        requireService().moveTo(
            acc.address, acc.password, folder, summary.uid, MailRules.snoozedTarget(),
        )
        val records = SnoozeLogic.decode(settingsStore.snoozeRecordsEncoded)
        val record = SnoozeLogic.Record(
            messageId = messageId,
            originFolder = folder,
            returnAtMs = SnoozeLogic.returnTimeMs(choice, System.currentTimeMillis()),
            subject = summary.subject,
        )
        settingsStore.snoozeRecordsEncoded =
            SnoozeLogic.encode(SnoozeLogic.upsert(records, record))
    }

    /** Process any due snooze returns now (also runs on the
     * periodic checker's tick). */
    fun processSnoozes(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { SnoozeProcessor.processDue(getApplication()) }
            loadFolders()
            onDone()
        }
    }

    // ---- signatures (1.2.0) ----

    /** The signature for the identity a compose session from
     * [fromFolder] sends as — the shared-folder identity rule in
     * MailRules decides, the signature follows the identity. */
    fun signatureFor(fromFolder: String): String {
        val acc = account ?: return ""
        val identity = MailRules.identityForFolder(fromFolder, acc.address)
        return if (identity.equals(MailRules.SHARED_ADDRESS, ignoreCase = true)) {
            settingsStore.signatureShared
        } else {
            settingsStore.signaturePersonal
        }
    }

    companion object {
        /** Server refusals and network failures, in plain words —
         * never a stack trace, never a credential. */
        fun friendlyError(e: Exception): String {
            val msg = e.message ?: ""
            return when {
                msg.contains("AUTHENTICATIONFAILED", ignoreCase = true) ||
                    msg.contains("Invalid credentials", ignoreCase = true) ||
                    msg.contains("authentication failed", ignoreCase = true) ->
                    "The mail server refused that address or password. Check both and try again."
                msg.contains("pinning failure", ignoreCase = true) ->
                    "The mail server's security certificate does not match the expected Sentinel Mail anchors. Nothing was sent. This needs attention before mail can be trusted."
                e is java.net.UnknownHostException ->
                    "The mail server could not be reached (no connection, or the server name did not resolve)."
                e is java.net.SocketTimeoutException ->
                    "The mail server took too long to answer. Try again in a moment."
                msg.isNotBlank() -> msg
                else -> "Something went wrong talking to the mail server. Try again."
            }
        }
    }
}
