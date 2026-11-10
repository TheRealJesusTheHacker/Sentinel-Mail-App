package net.sentinelsafeguard.mailapp.data.mail

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Snooze (1.2.0) — pure logic. Snoozing MOVES a message to the
 * Snoozed folder on the server (so webmail sees the same state)
 * and records, on this device only, where it came from and when
 * it is due back. The record is keyed by Message-ID — the one
 * identifier that survives the move; folder UIDs do not.
 *
 * Honesty rule, carried into the UI copy: return happens when
 * the background worker next runs or the app next opens, which
 * on Android is "around" the chosen time, never to the minute.
 * Nothing here pretends otherwise.
 */
object SnoozeLogic {

    enum class Choice { LATER_TODAY, TOMORROW, NEXT_WEEK }

    const val LATER_TODAY_HOUR = 20
    const val MORNING_HOUR = 8

    /** When a message snoozed for [choice] at [nowMs] is due
     * back, in the device's own timezone. */
    fun returnTimeMs(choice: Choice, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val target = when (choice) {
            Choice.LATER_TODAY -> {
                val tonight = now.toLocalDate().atTime(LATER_TODAY_HOUR, 0).atZone(zone)
                // "Later today" after the evening hour is tomorrow
                // morning — a return time in the past is a lie.
                if (tonight.isAfter(now)) tonight
                else now.toLocalDate().plusDays(1).atTime(MORNING_HOUR, 0).atZone(zone)
            }
            Choice.TOMORROW ->
                now.toLocalDate().plusDays(1).atTime(MORNING_HOUR, 0).atZone(zone)
            Choice.NEXT_WEEK -> {
                var date = now.toLocalDate().plusDays(1)
                while (date.dayOfWeek != DayOfWeek.MONDAY) date = date.plusDays(1)
                date.atTime(MORNING_HOUR, 0).atZone(zone)
            }
        }
        return target.toInstant().toEpochMilli()
    }

    /** One snoozed message's return ticket. [subject] rides
     * along only so a future surface could name it; nothing
     * reads mail content from this record. */
    data class Record(
        val messageId: String,
        val originFolder: String,
        val returnAtMs: Long,
        val subject: String = "",
    )

    fun due(records: List<Record>, nowMs: Long): List<Record> =
        records.filter { it.returnAtMs <= nowMs }

    fun upsert(records: List<Record>, record: Record): List<Record> =
        records.filterNot { it.messageId == record.messageId } + record

    fun remove(records: List<Record>, messageId: String): List<Record> =
        records.filterNot { it.messageId == messageId }

    // ---- persistence shape (settings storage) ----

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(records: List<Record>): String =
        buildJsonArray {
            for (r in records) {
                add(
                    buildJsonObject {
                        put("id", r.messageId)
                        put("from", r.originFolder)
                        put("at", r.returnAtMs)
                        put("subject", r.subject)
                    },
                )
            }
        }.toString()

    /** A corrupt or foreign record list decodes to what can be
     * salvaged, worst case empty — a lost snooze record means a
     * message stays in Snoozed (visible, recoverable by hand),
     * never a crash. */
    fun decode(text: String?): List<Record> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(text).jsonArray.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val from = obj["from"]?.jsonPrimitive?.content
                    ?: return@mapNotNull null
                val at = obj["at"]?.jsonPrimitive?.long ?: return@mapNotNull null
                Record(
                    messageId = id,
                    originFolder = from,
                    returnAtMs = at,
                    subject = obj["subject"]?.jsonPrimitive?.content ?: "",
                )
            }
        }.getOrDefault(emptyList())
    }
}
