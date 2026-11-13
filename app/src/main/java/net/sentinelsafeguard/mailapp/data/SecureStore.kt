package net.sentinelsafeguard.mailapp.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

/**
 * The only home for secrets on the device (family pattern, ported
 * from the Command App's SecureStore): the mailbox address and
 * password, and the app-lock PIN verifier, in Android
 * Keystore-backed encrypted storage. This file's preferences are
 * excluded from every backup and device transfer by
 * res/xml/data_extraction_rules.xml and res/xml/backup_rules.xml.
 * Nothing here is ever logged.
 *
 * The PIN itself is never stored — only a salted SHA-256 verifier.
 */
class SecureStore(context: Context) : SecureStoreApi {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override var accountAddress: String?
        get() = prefs.getString(KEY_ACCOUNT_ADDRESS, null)
        set(value) = putOrRemove(KEY_ACCOUNT_ADDRESS, value)

    override var mailPassword: String?
        get() = prefs.getString(KEY_MAIL_PASSWORD, null)
        set(value) = putOrRemove(KEY_MAIL_PASSWORD, value)

    /** Advanced server overrides (blank = the verified defaults). */
    override var serverHost: String?
        get() = prefs.getString(KEY_SERVER_HOST, null)
        set(value) = putOrRemove(KEY_SERVER_HOST, value)

    override var imapPort: Int
        get() = prefs.getInt(KEY_IMAP_PORT, 0)
        set(value) = prefs.edit().putInt(KEY_IMAP_PORT, value).apply()

    override var smtpPort: Int
        get() = prefs.getInt(KEY_SMTP_PORT, 0)
        set(value) = prefs.edit().putInt(KEY_SMTP_PORT, value).apply()

    override val hasAccount: Boolean
        get() = !accountAddress.isNullOrBlank() && !mailPassword.isNullOrBlank()

    // ---- app lock PIN (verifier only) ----

    override fun setPin(pin: String) {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val saltB64 = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)
        prefs.edit()
            .putString(KEY_PIN_SALT, saltB64)
            .putString(KEY_PIN_VERIFIER, pinVerifier(pin, salt))
            .apply()
    }

    override fun checkPin(pin: String): Boolean {
        val saltB64 = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val expected = prefs.getString(KEY_PIN_VERIFIER, null) ?: return false
        val salt = android.util.Base64.decode(saltB64, android.util.Base64.NO_WRAP)
        return MessageDigest.isEqual(
            pinVerifier(pin, salt).toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }

    override val hasPin: Boolean get() = prefs.contains(KEY_PIN_VERIFIER)

    override fun clearPin() {
        prefs.edit().remove(KEY_PIN_SALT).remove(KEY_PIN_VERIFIER).apply()
    }

    private fun pinVerifier(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return android.util.Base64.encodeToString(
            digest.digest(pin.toByteArray(Charsets.UTF_8)),
            android.util.Base64.NO_WRAP,
        )
    }

    /** Sign-out wipes every secret this store holds. */
    override fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun putOrRemove(key: String, value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove(key) else putString(key, value)
        }.apply()
    }

    companion object {
        const val FILE_NAME = "sentinel_mail_secure_store"
        private const val KEY_ACCOUNT_ADDRESS = "account_address"
        private const val KEY_MAIL_PASSWORD = "mail_password"
        private const val KEY_SERVER_HOST = "server_host"
        private const val KEY_IMAP_PORT = "imap_port"
        private const val KEY_SMTP_PORT = "smtp_port"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_VERIFIER = "pin_verifier"
    }
}
