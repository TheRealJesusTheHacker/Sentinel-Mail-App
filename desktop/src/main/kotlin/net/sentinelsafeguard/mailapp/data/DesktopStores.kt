package net.sentinelsafeguard.mailapp.data

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import net.sentinelsafeguard.mailapp.data.cache.CacheJson
import net.sentinelsafeguard.mailapp.data.cache.CachePolicy
import net.sentinelsafeguard.mailapp.data.mail.AddressBook
import net.sentinelsafeguard.mailapp.data.mail.MailFolder
import net.sentinelsafeguard.mailapp.data.mail.MailMessage
import net.sentinelsafeguard.mailapp.data.mail.MailSummary
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

// ---------------------------------------------------------------------------
// Desktop stores — the desktop implementations of the AppStores
// seams (desktop port, 2026-11-13; the Academy port's DesktopStore
// chain, adapted to Mail's three stores).
//
// One KV backend chain, chosen at runtime in this order:
//  1. Windows: DPAPI (CryptProtectData via JNA) over the value
//     file; only the same Windows user can decrypt.
//  2. Linux: Secret Service via libsecret (JNA) when the library
//     is present AND a secret service actually answers a probe.
//  3. Fallback (either OS): AES-256-GCM under a random key kept in
//     a 0600-permissions key file in the per-user config dir.
//     This posture is LABELED: [DesktopKv.osKeyStorageActive] is
//     false and the app's About section says so, in the user's
//     language, via the PlatformExtraSettings seam.
//
// The offline mail cache is file storage, not KV: every record is
// AES-256-GCM encrypted under a key file in the cache dir, the
// manifest discipline identical to Android's MailCache (hashes,
// sizes, timestamps, kinds — no addresses, no content).
// ---------------------------------------------------------------------------

interface KVBackend {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun clear()
}

/** libsecret-1 direct mapping (only the password calls we use). */
private interface LibSecret : Library {
    fun secret_password_store_sync(
        schema: Pointer?, flags: Int, collection: String?, label: String?,
        password: String, cancellable: Pointer?, error: PointerByReference?,
        vararg attributes: Any?,
    ): Boolean

    fun secret_password_lookup_sync(
        schema: Pointer?, cancellable: Pointer?, error: PointerByReference?,
        vararg attributes: Any?,
    ): String?

    fun secret_password_clear_sync(
        schema: Pointer?, cancellable: Pointer?, error: PointerByReference?,
        vararg attributes: Any?,
    ): Boolean

    fun secret_schema_new(
        name: String, flags: Int, vararg attributes: Any?,
    ): Pointer?
}

private object SecretServiceBackend : KVBackend {
    private const val APP_ATTR = "application"
    private const val APP_VAL = "net.sentinelsafeguard.mail.desktop"
    private const val KEY_ATTR = "key"
    private val lib: LibSecret? = runCatching {
        Native.load("secret-1", LibSecret::class.java)
    }.getOrNull()
    private val schema: Pointer? = runCatching {
        lib?.secret_schema_new(
            "net.sentinelsafeguard.mail.desktop.password", 0,
            APP_ATTR, 0, KEY_ATTR, 0, null,
        )
    }.getOrNull()

    /** True only when the library loads and a real secret service
     *  answers a lookup without error. Probed once. */
    val available: Boolean by lazy {
        val l = lib
        if (l == null || schema == null) {
            false
        } else {
            runCatching {
                val err = PointerByReference()
                l.secret_password_lookup_sync(
                    schema, null, err,
                    APP_ATTR, APP_VAL, KEY_ATTR, "__probe__", null,
                )
                err.value == null
            }.getOrDefault(false)
        }
    }

    override fun get(key: String): String? = runCatching {
        lib?.secret_password_lookup_sync(
            schema, null, null,
            APP_ATTR, APP_VAL, KEY_ATTR, key, null,
        )
    }.getOrNull()

    override fun put(key: String, value: String) {
        runCatching {
            lib?.secret_password_store_sync(
                schema, 0, null, "Sentinel Mail: $key", value, null, null,
                APP_ATTR, APP_VAL, KEY_ATTR, key, null,
            )
        }
    }

    override fun remove(key: String) {
        runCatching {
            lib?.secret_password_clear_sync(
                schema, null, null,
                APP_ATTR, APP_VAL, KEY_ATTR, key, null,
            )
        }
    }

    override fun clear() {
        // No per-schema bulk clear through the simple password
        // API; stores remove their known keys one by one.
    }
}

/** AES-GCM encrypted-file backend (the labeled fallback, and the
 *  value container under DPAPI on Windows). */
private class EncryptedFileBackend(
    private val dir: File,
    private val useDpapi: Boolean,
) : KVBackend {
    private val dataFile = File(dir, "store.enc")
    private val keyFile = File(dir, "store.key")

    private fun key(): SecretKeySpec {
        dir.mkdirs()
        if (!keyFile.exists()) {
            val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
            keyFile.writeText(Base64.getEncoder().encodeToString(k))
            lockDown(keyFile)
        }
        return SecretKeySpec(Base64.getDecoder().decode(keyFile.readText().trim()), "AES")
    }

    private fun protect(plain: ByteArray): ByteArray =
        if (useDpapi) WinDpapi.protect(plain) else plain

    private fun unprotect(data: ByteArray): ByteArray =
        if (useDpapi) WinDpapi.unprotect(data) else data

    @Synchronized
    private fun loadMap(): MutableMap<String, String> {
        if (!dataFile.exists()) return mutableMapOf()
        return runCatching {
            val raw = unprotect(Base64.getDecoder().decode(dataFile.readText().trim()))
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            // Plaintext payload is a java.util.Properties
            // document — dependency-free on the desktop JVM
            // (org.json is an Android platform library).
            val props = Properties()
            props.load(StringReader(String(cipher.doFinal(ct), Charsets.UTF_8)))
            val map = mutableMapOf<String, String>()
            for (k in props.stringPropertyNames()) map[k] = props.getProperty(k)
            map
        }.getOrElse { mutableMapOf() }
    }

    @Synchronized
    private fun saveMap(map: Map<String, String>) {
        dir.mkdirs()
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val props = Properties()
        for ((k, v) in map) props.setProperty(k, v)
        val payload = StringWriter().also { props.store(it, null) }.toString()
        val ct = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        val tmp = File(dir, "store.enc.tmp")
        tmp.writeText(Base64.getEncoder().encodeToString(protect(iv + ct)))
        lockDown(tmp)
        if (!tmp.renameTo(dataFile)) {
            dataFile.writeText(tmp.readText()); tmp.delete()
        }
    }

    override fun get(key: String): String? = loadMap()[key]

    override fun put(key: String, value: String) {
        val m = loadMap(); m[key] = value; saveMap(m)
    }

    override fun remove(key: String) {
        val m = loadMap(); if (m.remove(key) != null) saveMap(m)
    }

    override fun clear() {
        runCatching { dataFile.delete() }
    }
}

private fun lockDown(f: File) {
    runCatching {
        f.setReadable(false, false); f.setWritable(false, false)
        f.setReadable(true, true); f.setWritable(true, true)
    }
}

/** Minimal DPAPI mapping on core JNA (CryptProtectData /
 *  CryptUnprotectData with CRYPTPROTECT_UI_FORBIDDEN). Only ever
 *  loaded on Windows. */
private object WinDpapi {
    class DataBlob : com.sun.jna.Structure() {
        @JvmField var cbData: Int = 0
        @JvmField var pbData: Pointer? = null
        override fun getFieldOrder(): List<String> = listOf("cbData", "pbData")
    }

    private interface Crypt32 : Library {
        fun CryptProtectData(
            dataIn: DataBlob, dataDescr: String?, optionalEntropy: Pointer?,
            reserved: Pointer?, promptStruct: Pointer?, flags: Int, dataOut: DataBlob,
        ): Boolean

        fun CryptUnprotectData(
            dataIn: DataBlob, dataDescr: PointerByReference?, optionalEntropy: Pointer?,
            reserved: Pointer?, promptStruct: Pointer?, flags: Int, dataOut: DataBlob,
        ): Boolean
    }

    private interface Kernel32 : Library {
        fun LocalFree(mem: Pointer?): Pointer?
    }

    private val crypt32: Crypt32 by lazy { Native.load("crypt32", Crypt32::class.java) }
    private val kernel32: Kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }
    private const val CRYPTPROTECT_UI_FORBIDDEN = 0x1

    private fun blobOf(bytes: ByteArray): DataBlob {
        val blob = DataBlob()
        val mem = com.sun.jna.Memory(bytes.size.toLong())
        mem.write(0, bytes, 0, bytes.size)
        blob.cbData = bytes.size
        blob.pbData = mem
        blob.write()
        return blob
    }

    private fun readAndFree(blob: DataBlob): ByteArray {
        val out = blob.pbData!!.getByteArray(0, blob.cbData)
        kernel32.LocalFree(blob.pbData)
        return out
    }

    fun protect(plain: ByteArray): ByteArray {
        val out = DataBlob()
        check(crypt32.CryptProtectData(blobOf(plain), null, null, null, null, CRYPTPROTECT_UI_FORBIDDEN, out)) {
            "CryptProtectData failed"
        }
        out.read()
        return readAndFree(out)
    }

    fun unprotect(data: ByteArray): ByteArray {
        val out = DataBlob()
        check(crypt32.CryptUnprotectData(blobOf(data), null, null, null, null, CRYPTPROTECT_UI_FORBIDDEN, out)) {
            "CryptUnprotectData failed"
        }
        out.read()
        return readAndFree(out)
    }
}

/** The one desktop KV backend + the config-dir and posture facts. */
object DesktopKv {

    /** Per-user config dir: %APPDATA% on Windows,
     *  $XDG_CONFIG_HOME or ~/.config elsewhere. */
    fun configDir(): File {
        val os = System.getProperty("os.name").lowercase()
        return if (os.contains("win")) {
            File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "SentinelMail")
        } else {
            val xdg = System.getenv("XDG_CONFIG_HOME")
            File(if (!xdg.isNullOrBlank()) xdg else System.getProperty("user.home") + "/.config", "SentinelMail")
        }
    }

    /** Process-wide posture flag for the About label: true when an
     *  OS key store (DPAPI / Secret Service) protects the
     *  credentials, false for the labeled file fallback. */
    @Volatile
    var osKeyStorageActive: Boolean = false
        private set

    val backend: KVBackend by lazy {
        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("win") -> {
                osKeyStorageActive = true
                EncryptedFileBackend(configDir(), useDpapi = true)
            }
            SecretServiceBackend.available -> {
                osKeyStorageActive = true
                SecretServiceBackend
            }
            else -> {
                osKeyStorageActive = false
                EncryptedFileBackend(configDir(), useDpapi = false)
            }
        }
    }

    /** Desktop preferences (e.g. the reduce-motion setting). */
    fun pref(key: String): String? = backend.get("pref:$key")
    fun setPref(key: String, value: String) = backend.put("pref:$key", value)
}

/** The desktop SecureStoreApi: the account + PIN verifier in the
 *  DesktopKv backend. PIN semantics are Android's exactly: salted
 *  SHA-256 verifier, the PIN itself never stored. */
class DesktopSecureStore(
    private val backend: KVBackend = DesktopKv.backend,
) : SecureStoreApi {

    override var accountAddress: String?
        get() = backend.get(KEY_ACCOUNT_ADDRESS)
        set(value) = putOrRemove(KEY_ACCOUNT_ADDRESS, value)

    override var mailPassword: String?
        get() = backend.get(KEY_MAIL_PASSWORD)
        set(value) = putOrRemove(KEY_MAIL_PASSWORD, value)

    override var serverHost: String?
        get() = backend.get(KEY_SERVER_HOST)
        set(value) = putOrRemove(KEY_SERVER_HOST, value)

    override var imapPort: Int
        get() = backend.get(KEY_IMAP_PORT)?.toIntOrNull() ?: 0
        set(value) = backend.put(KEY_IMAP_PORT, value.toString())

    override var smtpPort: Int
        get() = backend.get(KEY_SMTP_PORT)?.toIntOrNull() ?: 0
        set(value) = backend.put(KEY_SMTP_PORT, value.toString())

    override val hasAccount: Boolean
        get() = !accountAddress.isNullOrBlank() && !mailPassword.isNullOrBlank()

    override fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        backend.put(KEY_PIN_SALT, Base64.getEncoder().encodeToString(salt))
        backend.put(KEY_PIN_VERIFIER, pinVerifier(pin, salt))
    }

    override fun checkPin(pin: String): Boolean {
        val saltB64 = backend.get(KEY_PIN_SALT) ?: return false
        val expected = backend.get(KEY_PIN_VERIFIER) ?: return false
        val salt = runCatching { Base64.getDecoder().decode(saltB64) }.getOrNull() ?: return false
        return MessageDigest.isEqual(
            pinVerifier(pin, salt).toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }

    override val hasPin: Boolean get() = backend.get(KEY_PIN_VERIFIER) != null

    override fun clearPin() {
        backend.remove(KEY_PIN_SALT); backend.remove(KEY_PIN_VERIFIER)
    }

    override fun clearAll() {
        // Only this store's keys: the backend is shared with the
        // settings store and desktop preferences, whose own
        // clear() calls run on the same sign-out path (the view
        // model calls both, as on Android).
        for (k in ALL_KEYS) backend.remove(k)
    }

    private fun pinVerifier(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return Base64.getEncoder().encodeToString(
            digest.digest(pin.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun putOrRemove(key: String, value: String?) {
        if (value.isNullOrBlank()) backend.remove(key) else backend.put(key, value)
    }

    companion object {
        private const val KEY_ACCOUNT_ADDRESS = "account_address"
        private const val KEY_MAIL_PASSWORD = "mail_password"
        private const val KEY_SERVER_HOST = "server_host"
        private const val KEY_IMAP_PORT = "imap_port"
        private const val KEY_SMTP_PORT = "smtp_port"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_VERIFIER = "pin_verifier"
        private val ALL_KEYS = listOf(
            KEY_ACCOUNT_ADDRESS, KEY_MAIL_PASSWORD, KEY_SERVER_HOST,
            KEY_IMAP_PORT, KEY_SMTP_PORT, KEY_PIN_SALT, KEY_PIN_VERIFIER,
        )
    }
}

/** The desktop SettingsStoreApi: the same keys, defaults, and
 *  encodings as Android's SettingsStore, in the DesktopKv backend
 *  (so snooze subjects and signatures get the same at-rest
 *  protection as everything else in the store). */
class DesktopSettingsStore(
    private val backend: KVBackend = DesktopKv.backend,
) : SettingsStoreApi {

    private fun str(key: String, default: String): String = backend.get("set:$key") ?: default
    private fun putStr(key: String, value: String) = backend.put("set:$key", value)
    private fun int(key: String, default: Int): Int = backend.get("set:$key")?.toIntOrNull() ?: default
    private fun long(key: String, default: Long): Long = backend.get("set:$key")?.toLongOrNull() ?: default
    private fun bool(key: String, default: Boolean): Boolean =
        backend.get("set:$key")?.let { it == "1" } ?: default

    override var checkIntervalMinutes: Int
        get() = int("check_interval_minutes", 15)
        set(value) = putStr("check_interval_minutes", value.toString())

    override var appLockEnabled: Boolean
        get() = bool("app_lock_enabled", false)
        set(value) = putStr("app_lock_enabled", if (value) "1" else "0")

    override var lastSeenInboxUid: Long
        get() = long("last_seen_inbox_uid", 0L)
        set(value) = putStr("last_seen_inbox_uid", value.toString())

    override var lastSeenInboxUidValidity: Long
        get() = long("last_seen_inbox_uidvalidity", -1L)
        set(value) = putStr("last_seen_inbox_uidvalidity", value.toString())

    override var notificationsEnabled: Boolean
        get() = bool("notifications_enabled", true)
        set(value) = putStr("notifications_enabled", if (value) "1" else "0")

    override var notificationPermissionAsked: Boolean
        get() = bool("notification_permission_asked", false)
        set(value) = putStr("notification_permission_asked", if (value) "1" else "0")

    override var undoSendSeconds: Int
        get() = int("undo_send_seconds", 5)
        set(value) = putStr("undo_send_seconds", value.toString())

    override var signaturePersonal: String
        get() = str("signature_personal", "")
        set(value) = putStr("signature_personal", value)

    override var signatureShared: String
        get() = str("signature_shared", "")
        set(value) = putStr("signature_shared", value)

    override var alwaysLoadImageSenders: Set<String>
        get() = str("always_load_image_senders", "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        set(value) = putStr("always_load_image_senders", value.sorted().joinToString(","))

    override var recentSearchesEncoded: String
        get() = str("recent_searches", "")
        set(value) = putStr("recent_searches", value)

    override var snoozeRecordsEncoded: String
        get() = str("snooze_records", "")
        set(value) = putStr("snooze_records", value)

    override fun clear() {
        for (k in ALL_KEYS) backend.remove("set:$k")
    }

    companion object {
        private val ALL_KEYS = listOf(
            "check_interval_minutes", "app_lock_enabled", "last_seen_inbox_uid",
            "last_seen_inbox_uidvalidity", "notifications_enabled",
            "notification_permission_asked", "undo_send_seconds",
            "signature_personal", "signature_shared",
            "always_load_image_senders", "recent_searches", "snooze_records",
        )
    }
}

/**
 * The desktop MailCacheApi: Android MailCache's discipline
 * verbatim — per-account records, the cleartext manifest of
 * hashes/sizes/times/kinds, bodies-first LRU eviction under the
 * shared 64 MB cap — with each record AES-256-GCM encrypted
 * under a random key in a 0600 key file in the cache dir (the
 * desktop twin of EncryptedFile's Keystore master key).
 * Records live in the per-user config dir (SentinelMail/mailcache).
 */
class DesktopMailCache(
    private val dir: File = File(DesktopKv.configDir(), "mailcache").apply { mkdirs() },
) : MailCacheApi {

    private val keyFile = File(dir, "cache.key")

    private var accountKey: String? = null

    override fun bindAccount(address: String) {
        accountKey = CachePolicy.accountKey(address)
    }

    override fun saveFolders(folders: List<MailFolder>) {
        write(CachePolicy.Kind.FOLDERS, "tree", CacheJson.foldersToJson(folders))
    }

    override fun loadFolders(): List<MailFolder>? = CacheJson.foldersFromJson(
        read(CachePolicy.Kind.FOLDERS, "tree"),
    )

    override fun saveSummaries(folderName: String, summaries: List<MailSummary>) {
        write(CachePolicy.Kind.SUMMARIES, "list|$folderName", CacheJson.summariesToJson(summaries))
    }

    override fun loadSummaries(folderName: String): List<MailSummary>? = CacheJson.summariesFromJson(
        read(CachePolicy.Kind.SUMMARIES, "list|$folderName"),
    )

    override fun saveMessage(folderName: String, message: MailMessage) {
        if (message.htmlBody.orEmpty().length + message.bodyText.length >
            CachePolicy.MAX_BODY_BYTES
        ) {
            return
        }
        write(
            CachePolicy.Kind.MESSAGE,
            "msg|$folderName|${message.uid}",
            CacheJson.messageToJson(message),
        )
    }

    override fun loadMessage(folderName: String, uid: Long): MailMessage? = CacheJson.messageFromJson(
        read(CachePolicy.Kind.MESSAGE, "msg|$folderName|$uid"),
    )

    override fun saveCorrespondents(entries: List<AddressBook.Entry>) {
        write(CachePolicy.Kind.OTHER, "correspondents", CacheJson.correspondentsToJson(entries))
    }

    override fun loadCorrespondents(): List<AddressBook.Entry> = CacheJson.correspondentsFromJson(
        read(CachePolicy.Kind.OTHER, "correspondents"),
    )

    override fun savedAtMs(kind: CachePolicy.Kind, name: String): Long {
        val key = key(kind, name) ?: return 0L
        return manifest()[key]?.savedAtMs ?: 0L
    }

    override fun totalSizeBytes(): Long = manifest().values.sumOf { it.sizeBytes }

    override fun clearAccount() {
        val acct = accountKey ?: return
        for (entry in manifest().values) {
            if (entry.key.startsWith(acct)) fileFor(entry.key).delete()
        }
        rewriteManifest(manifest().filterKeys { !it.startsWith(acct) })
    }

    override fun clearAll() {
        clearAccount()
        accountKey = null
    }

    // ---- storage plumbing ----

    private fun key(kind: CachePolicy.Kind, name: String): String? =
        accountKey?.let { CachePolicy.fileKey(it, kind, name) }

    private fun fileFor(key: String): File = File(dir, "$key.bin")
    private fun manifestFile(): File = File(dir, "manifest.json")

    private fun cipherKey(): SecretKeySpec {
        dir.mkdirs()
        if (!keyFile.exists()) {
            val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
            keyFile.writeText(Base64.getEncoder().encodeToString(k))
            lockDown(keyFile)
        }
        return SecretKeySpec(Base64.getDecoder().decode(keyFile.readText().trim()), "AES")
    }

    private fun encrypt(bytes: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, cipherKey(), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(bytes)
    }

    private fun decrypt(raw: ByteArray): ByteArray {
        val iv = raw.copyOfRange(0, 12)
        val ct = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, cipherKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    @Synchronized
    private fun write(kind: CachePolicy.Kind, name: String, payload: String) {
        val key = key(kind, name) ?: return
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        if (!CachePolicy.shouldCache(bytes.size.toLong())) return
        try {
            val others = manifest().values
                .filterNot { it.key == key }
                .map { CachePolicy.EntryInfo(it.key, it.kind, it.sizeBytes, it.lastAccessMs) }
            val victims = CachePolicy.evictionPlan(others, bytes.size.toLong()).toSet()
            for (victim in victims) fileFor(victim).delete()
            val kept = manifest().filterKeys { it != key && it !in victims }
            val target = fileFor(key)
            if (target.exists()) target.delete()
            target.writeBytes(encrypt(bytes))
            val now = System.currentTimeMillis()
            val updated = kept.toMutableMap()
            updated[key] = CachePolicy.ManifestEntry(
                key = key,
                kind = kind,
                sizeBytes = bytes.size.toLong(),
                savedAtMs = now,
                lastAccessMs = now,
            )
            rewriteManifest(updated)
        } catch (e: Exception) {
            // A cache write failure is a shrug, never a crash.
        }
    }

    @Synchronized
    private fun read(kind: CachePolicy.Kind, name: String): String? {
        val key = key(kind, name) ?: return null
        val file = fileFor(key)
        if (!file.exists()) return null
        val text = try {
            decrypt(file.readBytes()).toString(StandardCharsets.UTF_8)
        } catch (e: Exception) {
            runCatching { file.delete() }
            return null
        }
        val entries = manifest().toMutableMap()
        entries[key]?.let {
            entries[key] = it.copy(lastAccessMs = System.currentTimeMillis())
            rewriteManifest(entries)
        }
        return text
    }

    @Synchronized
    private fun manifest(): Map<String, CachePolicy.ManifestEntry> {
        val file = manifestFile()
        if (!file.exists()) return emptyMap()
        return CacheJson.manifestFromJson(runCatching { file.readText() }.getOrNull())
    }

    @Synchronized
    private fun rewriteManifest(entries: Map<String, CachePolicy.ManifestEntry>) {
        try {
            val tmp = File(dir, "manifest.json.tmp")
            tmp.writeText(CacheJson.manifestToJson(entries))
            tmp.renameTo(manifestFile())
        } catch (e: Exception) {
            // Same shrug discipline as Android's cache.
        }
    }
}
