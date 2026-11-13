package net.sentinelsafeguard.mailapp.data

import net.sentinelsafeguard.mailapp.data.cache.CachePolicy
import net.sentinelsafeguard.mailapp.data.mail.AddressBook
import net.sentinelsafeguard.mailapp.data.mail.MailFolder
import net.sentinelsafeguard.mailapp.data.mail.MailMessage
import net.sentinelsafeguard.mailapp.data.mail.MailSummary

// ---------------------------------------------------------------------------
// AppStores — the platform seams for on-device storage (desktop
// port, 2026-11-13; the Academy port's AppStore pattern).
//
// Android implementations: SecureStore (Keystore-backed
// EncryptedSharedPreferences), SettingsStore (plain
// SharedPreferences), MailCache (EncryptedFile). Desktop
// implementations live in the desktop module (DesktopStore chain:
// DPAPI / Secret Service / labeled AES-256-GCM file fallback) and
// satisfy these same interfaces, so every consumer — the account
// seam, the snooze processor, the view model, the screens — sees
// identical semantics on both platforms by construction:
//  - the single account's address/password + server overrides and
//    the app-lock PIN verifier (salted SHA-256; the PIN itself is
//    never stored on any platform) live behind [SecureStoreApi];
//  - non-secret preferences live behind [SettingsStoreApi];
//  - the encrypted offline cache lives behind [MailCacheApi].
// ---------------------------------------------------------------------------

interface SecureStoreApi {
    var accountAddress: String?
    var mailPassword: String?
    var serverHost: String?
    var imapPort: Int
    var smtpPort: Int
    val hasAccount: Boolean
    fun setPin(pin: String)
    fun checkPin(pin: String): Boolean
    val hasPin: Boolean
    fun clearPin()
    fun clearAll()
}

interface SettingsStoreApi {
    var checkIntervalMinutes: Int
    var appLockEnabled: Boolean
    var lastSeenInboxUid: Long
    var lastSeenInboxUidValidity: Long
    var notificationsEnabled: Boolean
    var notificationPermissionAsked: Boolean
    var undoSendSeconds: Int
    var signaturePersonal: String
    var signatureShared: String
    var alwaysLoadImageSenders: Set<String>
    var recentSearchesEncoded: String
    var snoozeRecordsEncoded: String
    fun clear()
}

interface MailCacheApi {
    fun bindAccount(address: String)
    fun saveFolders(folders: List<MailFolder>)
    fun loadFolders(): List<MailFolder>?
    fun saveSummaries(folderName: String, summaries: List<MailSummary>)
    fun loadSummaries(folderName: String): List<MailSummary>?
    fun saveMessage(folderName: String, message: MailMessage)
    fun loadMessage(folderName: String, uid: Long): MailMessage?
    fun saveCorrespondents(entries: List<AddressBook.Entry>)
    fun loadCorrespondents(): List<AddressBook.Entry>
    fun savedAtMs(kind: CachePolicy.Kind, name: String): Long
    fun totalSizeBytes(): Long
    fun clearAccount()
    fun clearAll()
}
