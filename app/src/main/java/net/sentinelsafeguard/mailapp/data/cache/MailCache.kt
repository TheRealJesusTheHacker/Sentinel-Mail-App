package net.sentinelsafeguard.mailapp.data.cache

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.nio.charset.StandardCharsets
import net.sentinelsafeguard.mailapp.data.mail.AddressBook
import net.sentinelsafeguard.mailapp.data.mail.MailFolder
import net.sentinelsafeguard.mailapp.data.mail.MailMessage
import net.sentinelsafeguard.mailapp.data.mail.MailSummary

/**
 * The persistent offline cache (1.2.0) — the deliberate
 * replacement for the 1.0.0 "memory only" rule.
 *
 * What is cached: the folder tree, the newest page of each
 * folder's message list, opened message bodies, and the
 * harvested correspondent list. What is NEVER cached: attachment
 * bytes (fetched live, as before) and any credential (the cache
 * has no password to protect because it never sees one).
 *
 * At rest: every record is written through EncryptedFile
 * (AES-256-GCM content + HKDF-derived keys, Android Keystore
 * master key — the same mechanism class as SecureStore's
 * EncryptedSharedPreferences). The one cleartext file is the
 * manifest, and it contains only record hashes, sizes,
 * timestamps, and kinds — no addresses, no folder names, no
 * mail content (keys are SHA-256 digests via [CachePolicy]).
 *
 * Freshness discipline lives in the ViewModel: the network is
 * always tried first; this cache is what the app falls back to
 * when the network fails, and the UI labels what it shows as a
 * saved copy with its saved-at time. Opening a cached message
 * does not mark it read on the server — offline cannot do that
 * honestly, so it doesn't do it at all.
 *
 * IO failures degrade to "no cache" everywhere: a cache that can
 * take the app down with it is worse than no cache.
 */
class MailCache(context: Context) {

    private val dir: File = File(context.filesDir, "mailcache").apply { mkdirs() }
    private val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private var accountKey: String? = null

    /** The cache is per-account. Set on sign-in / start; sign-out
     * wipes the account's records (see [clearAll]). */
    fun bindAccount(address: String) {
        accountKey = CachePolicy.accountKey(address)
    }

    // ---- folders ----

    fun saveFolders(folders: List<MailFolder>) {
        write(CachePolicy.Kind.FOLDERS, "tree", CacheJson.foldersToJson(folders))
    }

    fun loadFolders(): List<MailFolder>? = CacheJson.foldersFromJson(
        read(CachePolicy.Kind.FOLDERS, "tree"),
    )

    // ---- message lists ----

    fun saveSummaries(folderName: String, summaries: List<MailSummary>) {
        write(CachePolicy.Kind.SUMMARIES, "list|$folderName", CacheJson.summariesToJson(summaries))
    }

    fun loadSummaries(folderName: String): List<MailSummary>? = CacheJson.summariesFromJson(
        read(CachePolicy.Kind.SUMMARIES, "list|$folderName"),
    )

    // ---- opened messages ----

    fun saveMessage(folderName: String, message: MailMessage) {
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

    fun loadMessage(folderName: String, uid: Long): MailMessage? = CacheJson.messageFromJson(
        read(CachePolicy.Kind.MESSAGE, "msg|$folderName|$uid"),
    )

    // ---- correspondents ----

    fun saveCorrespondents(entries: List<AddressBook.Entry>) {
        write(CachePolicy.Kind.OTHER, "correspondents", CacheJson.correspondentsToJson(entries))
    }

    fun loadCorrespondents(): List<AddressBook.Entry> = CacheJson.correspondentsFromJson(
        read(CachePolicy.Kind.OTHER, "correspondents"),
    )

    // ---- housekeeping ----

    /** When a record was SAVED (epoch ms), for the "saved copy"
     * labels; 0 when there is no record. This is the write time,
     * never the last-read time — reads touch the eviction clock
     * only (1.2.2, audit finding D2). */
    fun savedAtMs(kind: CachePolicy.Kind, name: String): Long {
        val key = key(kind, name) ?: return 0L
        return manifest()[key]?.savedAtMs ?: 0L
    }

    /** Total bytes the cache occupies on disk (payloads only —
     * the manifest is a few hundred bytes). */
    fun totalSizeBytes(): Long = manifest().values.sumOf { it.sizeBytes }

    /** The Settings "Clear cache" control: everything goes.
     * Signing back in re-fetches from the server. */
    fun clearAccount() {
        val acct = accountKey ?: return
        for (entry in manifest().values) {
            if (entry.key.startsWith(acct)) fileFor(entry.key).delete()
        }
        rewriteManifest(manifest().filterKeys { !it.startsWith(acct) })
    }

    /** Sign-out: drop the binding and the data. */
    fun clearAll() {
        clearAccount()
        accountKey = null
    }

    // ---- storage plumbing ----

    private fun key(kind: CachePolicy.Kind, name: String): String? =
        accountKey?.let { CachePolicy.fileKey(it, kind, name) }

    private fun fileFor(key: String): File = File(dir, "$key.bin")
    private fun manifestFile(): File = File(dir, "manifest.json")

    @Synchronized
    private fun write(kind: CachePolicy.Kind, name: String, payload: String) {
        val key = key(kind, name) ?: return
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        // A record bigger than the whole budget is not cached at
        // all (CachePolicy.shouldCache) — evicting everything
        // else for it would still break the cap.
        if (!CachePolicy.shouldCache(bytes.size.toLong())) return
        try {
            // Eviction first: the cap is enforced BEFORE the new
            // bytes land, so the disk never exceeds it by more
            // than this one record.
            val others = manifest().values
                .filterNot { it.key == key }
                .map {
                    CachePolicy.EntryInfo(it.key, it.kind, it.sizeBytes, it.lastAccessMs)
                }
            val victims = CachePolicy.evictionPlan(others, bytes.size.toLong()).toSet()
            for (victim in victims) fileFor(victim).delete()
            val kept = manifest().filterKeys { it != key && it !in victims }
            val target = fileFor(key)
            if (target.exists()) target.delete()
            EncryptedFile.Builder(
                applicationContext(),
                target,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build().openFileOutput().use { it.write(bytes) }
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
            EncryptedFile.Builder(
                applicationContext(),
                file,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build().openFileInput().use { input ->
                input.readBytes().toString(StandardCharsets.UTF_8)
            }
        } catch (e: Exception) {
            // Undecryptable (key rotated, file damaged): drop it.
            runCatching { file.delete() }
            return null
        }
        // Touch: last-access drives eviction order.
        val entries = manifest().toMutableMap()
        entries[key]?.let {
            entries[key] = it.copy(lastAccessMs = System.currentTimeMillis())
            rewriteManifest(entries)
        }
        return text
    }

    private val appContext: Context = context.applicationContext

    private fun applicationContext(): Context = appContext

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
            // Losing the manifest loses the index, not the data's
            // safety: orphaned encrypted files are unreachable
            // (their names are unguessable digests) and the next
            // write rebuilds the index going forward.
        }
    }
}
