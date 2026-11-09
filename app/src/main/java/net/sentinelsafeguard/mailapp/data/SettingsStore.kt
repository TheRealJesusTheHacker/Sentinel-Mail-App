package net.sentinelsafeguard.mailapp.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Non-secret preferences (plain SharedPreferences — nothing here
 * is a credential): the periodic-check interval, the last-seen
 * inbox state the checker diffs against, and whether the app lock
 * is enabled.
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

    fun clear() = prefs.edit().clear().apply()

    companion object {
        const val FILE_NAME = "sentinel_mail_settings"
        const val DEFAULT_INTERVAL_MINUTES = 15
        private const val KEY_INTERVAL = "check_interval_minutes"
        private const val KEY_LOCK = "app_lock_enabled"
        private const val KEY_LAST_UID = "last_seen_inbox_uid"
        private const val KEY_LAST_UIDV = "last_seen_inbox_uidvalidity"
    }
}
