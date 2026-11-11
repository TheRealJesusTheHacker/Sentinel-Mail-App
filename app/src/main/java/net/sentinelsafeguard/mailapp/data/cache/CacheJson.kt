package net.sentinelsafeguard.mailapp.data.cache

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import net.sentinelsafeguard.mailapp.data.mail.AddressBook
import net.sentinelsafeguard.mailapp.data.mail.MailAttachment
import net.sentinelsafeguard.mailapp.data.mail.MailFolder
import net.sentinelsafeguard.mailapp.data.mail.MailMessage
import net.sentinelsafeguard.mailapp.data.mail.MailSummary

/**
 * The offline cache's serialization (1.2.0) — the mail models
 * to/from JSON, written by hand against kotlinx-serialization's
 * JSON tree API (already a declared dependency). No reflection,
 * no generated serializers: what is stored is exactly what is
 * written here, and decode is total — a damaged record yields
 * null/empty, never an exception into a screen. Pure JVM:
 * unit-tested directly.
 */
object CacheJson {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- folders ----

    fun foldersToJson(folders: List<MailFolder>): String =
        buildJsonArray {
            for (f in folders) {
                add(
                    buildJsonObject {
                        put("name", f.name)
                        put("unread", f.unreadCount)
                        put("shared", f.shared)
                        put("attrs", buildJsonArray { f.attributes.forEach { add(it) } })
                    },
                )
            }
        }.toString()

    fun foldersFromJson(text: String?): List<MailFolder>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            json.parseToJsonElement(text).jsonArray.map { el ->
                val o = el.jsonObject
                MailFolder(
                    name = o.str("name") ?: return null,
                    unreadCount = o.int("unread") ?: 0,
                    shared = o.bool("shared") ?: false,
                    attributes = (o["attrs"] as? JsonArray)
                        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                        ?.toSet() ?: emptySet(),
                )
            }
        }.getOrNull()
    }

    // ---- message lists ----

    fun summariesToJson(summaries: List<MailSummary>): String =
        buildJsonArray { for (s in summaries) add(summaryToJson(s)) }.toString()

    fun summariesFromJson(text: String?): List<MailSummary>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            json.parseToJsonElement(text).jsonArray.mapNotNull { el ->
                summaryFromJson(el as? JsonObject ?: return@mapNotNull null)
            }
        }.getOrNull()
    }

    private fun summaryToJson(s: MailSummary): JsonObject = buildJsonObject {
        put("uid", s.uid)
        put("subject", s.subject)
        put("from", s.from)
        put("to", s.to)
        put("date", s.dateText)
        put("unread", s.unread)
        put("snippet", s.snippet)
        put("starred", s.starred)
        put("att", s.hasAttachments)
        put("folder", s.folderName)
        put("mid", s.messageId ?: "")
        put("irt", s.inReplyTo ?: "")
        put("refs", s.references ?: "")
    }

    private fun summaryFromJson(o: JsonObject): MailSummary? {
        val uid = o.long("uid") ?: return null
        return MailSummary(
            uid = uid,
            subject = o.str("subject") ?: "(no subject)",
            from = o.str("from") ?: "",
            to = o.str("to") ?: "",
            dateText = o.str("date") ?: "",
            unread = o.bool("unread") ?: false,
            snippet = o.str("snippet") ?: "",
            starred = o.bool("starred") ?: false,
            hasAttachments = o.bool("att") ?: false,
            folderName = o.str("folder") ?: "",
            messageId = o.str("mid")?.ifBlank { null },
            inReplyTo = o.str("irt")?.ifBlank { null },
            references = o.str("refs")?.ifBlank { null },
        )
    }

    // ---- one opened message ----

    fun messageToJson(m: MailMessage): String = buildJsonObject {
        put("uid", m.uid)
        put("subject", m.subject)
        put("from", m.from)
        put("to", m.to)
        put("cc", m.cc)
        put("date", m.dateText)
        put("body", m.bodyText)
        put("html", m.htmlBody ?: "")
        put("unread", m.unread)
        put("starred", m.starred)
        put("mid", m.messageId ?: "")
        put("refs", m.references ?: "")
        put(
            "atts",
            buildJsonArray {
                for (a in m.attachments) {
                    add(
                        buildJsonObject {
                            put("idx", a.partIndex)
                            put("name", a.fileName)
                            put("mime", a.mimeType)
                            put("size", a.sizeBytes)
                        },
                    )
                }
            },
        )
    }.toString()

    fun messageFromJson(text: String?): MailMessage? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val o = json.parseToJsonElement(text).jsonObject
            MailMessage(
                uid = o.long("uid") ?: return null,
                subject = o.str("subject") ?: "(no subject)",
                from = o.str("from") ?: "",
                to = o.str("to") ?: "",
                cc = o.str("cc") ?: "",
                dateText = o.str("date") ?: "",
                bodyText = o.str("body") ?: "",
                htmlBody = o.str("html")?.ifBlank { null },
                unread = o.bool("unread") ?: false,
                starred = o.bool("starred") ?: false,
                attachments = (o["atts"] as? JsonArray)?.mapNotNull { el ->
                    val a = el as? JsonObject ?: return@mapNotNull null
                    MailAttachment(
                        partIndex = a.str("idx") ?: return@mapNotNull null,
                        fileName = a.str("name") ?: "attachment",
                        mimeType = a.str("mime") ?: "application/octet-stream",
                        sizeBytes = a.long("size") ?: -1L,
                    )
                } ?: emptyList(),
                messageId = o.str("mid")?.ifBlank { null },
                references = o.str("refs")?.ifBlank { null },
            )
        }.getOrNull()
    }

    // ---- correspondents (the autocomplete book) ----

    fun correspondentsToJson(entries: List<AddressBook.Entry>): String =
        buildJsonArray {
            for (e in entries) {
                add(
                    buildJsonObject {
                        put("address", e.address)
                        put("display", e.display)
                        put("count", e.count)
                    },
                )
            }
        }.toString()

    fun correspondentsFromJson(text: String?): List<AddressBook.Entry> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(text).jsonArray.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                AddressBook.Entry(
                    address = o.str("address") ?: return@mapNotNull null,
                    display = o.str("display") ?: return@mapNotNull null,
                    count = o.int("count") ?: 0,
                )
            }
        }.getOrDefault(emptyList())
    }

    // ---- manifest (the cleartext index: hashes/sizes/times only) ----

    fun manifestToJson(entries: Map<String, CachePolicy.ManifestEntry>): String =
        buildJsonObject {
            for ((key, entry) in entries) {
                put(
                    key,
                    buildJsonObject {
                        put("kind", entry.kind.name)
                        put("size", entry.sizeBytes)
                        put("saved", entry.savedAtMs)
                        put("at", entry.lastAccessMs)
                    },
                )
            }
        }.toString()

    /** Total decode: a damaged manifest yields what parses,
     * worst case empty (the index is rebuildable; the encrypted
     * payloads it pointed at simply age out unreachable). A
     * pre-1.2.2 manifest row has no "saved" — its write time is
     * unknown, so the access time stands in (the old semantics,
     * for old rows only). */
    fun manifestFromJson(text: String?): Map<String, CachePolicy.ManifestEntry> {
        if (text.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.parseToJsonElement(text).jsonObject.entries.mapNotNull { (key, el) ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val at = obj.long("at") ?: 0L
                CachePolicy.ManifestEntry(
                    key = key,
                    kind = runCatching {
                        CachePolicy.Kind.valueOf(obj.str("kind") ?: "")
                    }.getOrDefault(CachePolicy.Kind.OTHER),
                    sizeBytes = obj.long("size") ?: 0L,
                    savedAtMs = obj.long("saved") ?: at,
                    lastAccessMs = at,
                )
            }.associateBy { it.key }
        }.getOrDefault(emptyMap())
    }

    // ---- tiny typed readers ----

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.let {
            runCatching { it.long }.getOrNull()
        }

    private fun JsonObject.int(key: String): Int? = long(key)?.toInt()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.let {
            runCatching { it.boolean }.getOrNull()
        }
}
