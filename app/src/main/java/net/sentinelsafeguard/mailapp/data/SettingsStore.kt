package net.sentinelsafeguard.mailapp.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Non-secret preferences (plain SharedPreferences — nothing here
 * is a credential): the periodic-check interval, the last-seen
 * inbox state the checker diffs against, and whether the app lock
 * is enabled. 1.2.0 adds the notification master switch, the
 * undo-send window, per-identity signatures (the owner's own
 * words, not secrets), the always-load-images sender memory,
 * recent searches, and the snooze records. Signatures and snooze
 * subjects are mailbox content the owner wrote themselves; they
 * sit here, not in the encrypted store, because that store is for
 * credentials — the offline mail cache has its own encrypted
 * home (data/cache), and the record documents the split.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Minutes between periodic checks; 0 = manual only. */
    var checkIntervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MINUTES)
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value).apply()

    var appLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCK, value).apply()

    /** Highest INBOX UID the checker has already accounted for. */
    var lastSeenInboxUid: Long
        get() = prefs.getLong(KEY_LAST_UID, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UID, value).apply()

    var lastSeenInboxUidValidity: Long
        get() = prefs.getLong(KEY_LAST_UIDV, -1L)
        set(value) = prefs.edit().putLong(KEY_LAST_UIDV, value).apply()

    // ---- 1.2.0 additions ----

    /** The notification master switch. Off = the periodic worker
     * still baselines (so re-enabling doesn't dump the backlog)
     * but posts nothing. */
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()

    /** Whether the notification-permission explainer has been
     * shown (the family pattern asks once, in context, and the
     * Settings row carries the standing control after). */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_ASKED, value).apply()

    /** Undo-send grace window in seconds; 0 = send immediately. */
    var undoSendSeconds: Int
        get() = prefs.getInt(KEY_UNDO_SEND, DEFAULT_UNDO_SEND_SECONDS)
        set(value) = prefs.edit().putInt(KEY_UNDO_SEND, value).apply()

    /** Signature for the personal identity (skyler@). */
    var signaturePersonal: String
        get() = prefs.getString(KEY_SIG_PERSONAL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SIG_PERSONAL, value).apply()

    /** Signature for the shared identity (sentinel@). */
    var signatureShared: String
        get() = prefs.getString(KEY_SIG_SHARED, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SIG_SHARED, value).apply()

    /** Senders whose remote images always load (bare addresses). */
    var alwaysLoadImageSenders: Set<String>
        get() = prefs.getString(KEY_IMAGE_SENDERS, "")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()
        set(value) = prefs.edit()
            .putString(KEY_IMAGE_SENDERS, value.sorted().joinToString(","))
            .apply()

    /** Recent search queries, encoded by RecentSearches. */
    var recentSearchesEncoded: String
        get() = prefs.getString(KEY_RECENT_SEARCHES, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RECENT_SEARCHES, value).apply()

    /** Snooze records, encoded by SnoozeLogic. */
    var snoozeRecordsEncoded: String
        get() = prefs.getString(KEY_SNOOZE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SNOOZE, value).apply()

    fun clear() = prefs.edit().clear().apply()

    companion object {
        const val FILE_NAME = "sentinel_mail_settings"
        const val DEFAULT_INTERVAL_MINUTES = 15
        const val DEFAULT_UNDO_SEND_SECONDS = 5
        private const val KEY_INTERVAL = "check_interval_minutes"
        private const val KEY_LOCK = "app_lock_enabled"
        private const val KEY_LAST_UID = "last_seen_inbox_uid"
        private const val KEY_LAST_UIDV = "last_seen_inbox_uidvalidity"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_NOTIFY_ASKED = "notification_permission_asked"
        private const val KEY_UNDO_SEND = "undo_send_seconds"
        private const val KEY_SIG_PERSONAL = "signature_personal"
        private const val KEY_SIG_SHARED = "signature_shared"
        private const val KEY_IMAGE_SENDERS = "always_load_image_senders"
        private const val KEY_RECENT_SEARCHES = "recent_searches"
        private const val KEY_SNOOZE = "snooze_records"
    }
}
