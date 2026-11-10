package net.sentinelsafeguard.mailapp.data.cache

import java.security.MessageDigest

/**
 * Offline-cache policy (1.2.0) — pure logic.
 *
 * 1.0.0/1.1.0 kept mail in memory only. 1.2.0 caches on disk,
 * encrypted at rest (see MailCache), so the mailbox is fully
 * browsable with no connection. This object decides the keys,
 * the cap, and who leaves when the cap bites — all of it
 * testable without a device:
 *
 * Keys are SHA-256 digests of the account + record identity.
 * The mailbox address and folder names never appear in a
 * filename, and the cached CONTENT never travels unencrypted
 * (MailCache writes through EncryptedFile; the plain index of
 * hashes/sizes/timestamps is the only cleartext on disk).
 *
 * Eviction: when a write would pass the cap, message BODIES go
 * first (least recently read), then message lists, then other
 * records. The folder tree is evicted last and only if nothing
 * else suffices — it is small and it is the map to everything.
 */
object CachePolicy {

    /** Total on-disk budget for cached mail: 64 MB. Bodies of
     * ordinary mail are kilobytes; this is years of reading for
     * this mailbox's shape, and small enough to stay polite on
     * the phone. */
    const val MAX_TOTAL_BYTES: Long = 64L * 1024 * 1024

    /** One cached body is capped well under the server's 25 MB
     * message ceiling: a body larger than this is simply not
     * cached (it still reads live; it just isn't kept). */
    const val MAX_BODY_BYTES: Long = 8L * 1024 * 1024

    enum class Kind { MESSAGE, SUMMARIES, FOLDERS, OTHER }

    data class EntryInfo(
        val key: String,
        val kind: Kind,
        val sizeBytes: Long,
        val lastAccessMs: Long,
    )

    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** The per-account namespace key: a digest of the account
     * address, lowercased (addresses are case-insensitive here,
     * and two accounts never share a cache). */
    fun accountKey(address: String): String =
        sha256Hex("account|" + address.trim().lowercase()).substring(0, 32)

    fun fileKey(accountKey: String, kind: Kind, name: String): String =
        sha256Hex("$accountKey|$kind|$name")

    /**
     * Which entries must be deleted so that the current total
     * plus [incomingBytes] fits under [capBytes]. Eviction
     * order: MESSAGE by least-recent access, then SUMMARIES,
     * then OTHER, then FOLDERS. Returns entry keys, in the
     * order they should go. If even evicting everything cannot
     * fit the incoming record, everything is returned (the
     * caller then skips caching the oversized record itself).
     */
    fun evictionPlan(
        entries: List<EntryInfo>,
        incomingBytes: Long,
        capBytes: Long = MAX_TOTAL_BYTES,
    ): List<String> {
        var total = entries.sumOf { it.sizeBytes } + incomingBytes
        if (total <= capBytes) return emptyList()
        val order = compareBy<EntryInfo>(
            { kindRank(it.kind) },
            { it.lastAccessMs },
        )
        val victims = mutableListOf<String>()
        for (entry in entries.sortedWith(order)) {
            if (total <= capBytes) break
            victims += entry.key
            total -= entry.sizeBytes
        }
        return victims
    }

    private fun kindRank(kind: Kind): Int = when (kind) {
        Kind.MESSAGE -> 0
        Kind.SUMMARIES -> 1
        Kind.OTHER -> 2
        Kind.FOLDERS -> 3
    }
}
