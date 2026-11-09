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
import net.sentinelsafeguard.mailapp.data.mail.ComposeModel
import net.sentinelsafeguard.mailapp.data.mail.MailFolder
import net.sentinelsafeguard.mailapp.data.mail.MailMessage
import net.sentinelsafeguard.mailapp.data.mail.MailService
import net.sentinelsafeguard.mailapp.data.mail.MailSummary
import net.sentinelsafeguard.mailapp.notify.NewMailWorker

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

    init {
        account = accounts.activeAccount()
        locked = isLockArmed()
        if (account != null) refreshService()
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
                loadFolders()
                onResult(null)
            } catch (e: Exception) {
                onResult(friendlyError(e))
            }
        }
    }

    fun signOut() {
        service?.clearCache()
        accounts.signOut()
        settingsStore.clear()
        NewMailWorker.cancel(getApplication())
        account = null
        service = null
        folders = emptyList()
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
                folders = requireService().listFolders(acc.address, acc.password)
            } catch (e: Exception) {
                foldersError = friendlyError(e)
            } finally {
                foldersLoading = false
            }
        }
    }

    suspend fun loadMessages(folder: String, forceRefresh: Boolean = false): List<MailSummary> {
        val acc = requireAccount()
        return requireService().listMessages(acc.address, acc.password, folder, forceRefresh = forceRefresh)
    }

    suspend fun search(folder: String, query: String): List<MailSummary> {
        val acc = requireAccount()
        return requireService().search(acc.address, acc.password, folder, query)
    }

    suspend fun loadMessage(folder: String, uid: Long): MailMessage {
        val acc = requireAccount()
        return requireService().getMessage(acc.address, acc.password, folder, uid)
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
