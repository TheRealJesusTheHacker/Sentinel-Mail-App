package net.sentinelsafeguard.mailapp

import java.time.ZoneId
import java.time.ZonedDateTime
import net.sentinelsafeguard.mailapp.data.mail.SnoozeLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Snooze return times + record persistence (1.2.0). Times are
 * computed in the device zone; "later today" never lands in the
 * past; "next week" is the coming Monday morning. */
class SnoozeLogicTest {

    private val zone: ZoneId = ZoneId.of("America/Chicago")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
            .toInstant().toEpochMilli()

    @Test
    fun laterTodayIsThisEvening() {
        // Tuesday 2026-11-10 09:00 → 20:00 the same day.
        val due = SnoozeLogic.returnTimeMs(
            SnoozeLogic.Choice.LATER_TODAY, at(2026, 11, 10, 9), zone,
        )
        assertEquals(at(2026, 11, 10, 20), due)
    }

    @Test
    fun laterTodayAfterTheHourIsTomorrowMorning() {
        val due = SnoozeLogic.returnTimeMs(
            SnoozeLogic.Choice.LATER_TODAY, at(2026, 11, 10, 21, 30), zone,
        )
        assertEquals(at(2026, 11, 11, 8), due)
    }

    @Test
    fun tomorrowIsMorning() {
        val due = SnoozeLogic.returnTimeMs(
            SnoozeLogic.Choice.TOMORROW, at(2026, 11, 10, 15), zone,
        )
        assertEquals(at(2026, 11, 11, 8), due)
    }

    @Test
    fun nextWeekIsTheComingMonday() {
        // Tuesday 2026-11-10 → Monday 2026-11-16 08:00.
        val due = SnoozeLogic.returnTimeMs(
            SnoozeLogic.Choice.NEXT_WEEK, at(2026, 11, 10, 9), zone,
        )
        assertEquals(at(2026, 11, 16, 8), due)
        // Sunday 2026-11-15 → the next day is Monday.
        val sunday = SnoozeLogic.returnTimeMs(
            SnoozeLogic.Choice.NEXT_WEEK, at(2026, 11, 15, 9), zone,
        )
        assertEquals(at(2026, 11, 16, 8), sunday)
    }

    @Test
    fun dueFilteringAndUpsert() {
        val records = listOf(
            SnoozeLogic.Record("<a@x>", "INBOX", 100L),
            SnoozeLogic.Record("<b@x>", "INBOX", 500L),
        )
        assertEquals(listOf("<a@x>"), SnoozeLogic.due(records, 250L).map { it.messageId })
        val replaced = SnoozeLogic.upsert(
            records, SnoozeLogic.Record("<a@x>", "Archive", 900L),
        )
        assertEquals(2, replaced.size)
        assertEquals(900L, replaced.first { it.messageId == "<a@x>" }.returnAtMs)
        assertEquals(
            listOf("<b@x>"),
            SnoozeLogic.remove(records, "<a@x>").map { it.messageId },
        )
    }

    @Test
    fun recordsRoundTripThroughJson() {
        val records = listOf(
            SnoozeLogic.Record("<a@x>", "INBOX", 123L, "Hello"),
            SnoozeLogic.Record("<b@x>", "Shared.sentinel.INBOX", 456L),
        )
        assertEquals(records, SnoozeLogic.decode(SnoozeLogic.encode(records)))
        assertTrue(SnoozeLogic.decode(null).isEmpty())
        assertTrue(SnoozeLogic.decode("not json").isEmpty())
        assertTrue(SnoozeLogic.decode("").isEmpty())
    }
}
