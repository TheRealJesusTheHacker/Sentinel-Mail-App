package net.sentinelsafeguard.mailapp.notify

import net.sentinelsafeguard.mailapp.data.AccountRepository
import net.sentinelsafeguard.mailapp.data.SecureStoreApi
import net.sentinelsafeguard.mailapp.data.SettingsStoreApi
import net.sentinelsafeguard.mailapp.data.mail.MailRules
import net.sentinelsafeguard.mailapp.data.mail.SnoozeLogic

/**
 * Snooze returns (1.2.0). There is deliberately no dedicated
 * timer: returns are processed by the periodic new-mail check
 * (SnoozeWorker piggybacks it) and when the app opens (the
 * ViewModel calls in). The chosen time is therefore approximate
 * BY DESIGN — Android's periodic floor is 15 minutes and Doze
 * stretches it — and the snooze dialog says exactly that before
 * the user picks a time, so the approximation is disclosed, not
 * discovered.
 *
 * A record resolves in exactly three ways: the message moves
 * back to its origin folder (record dropped); the message is no
 * longer in Snoozed — it was moved or deleted elsewhere (record
 * dropped, nothing invented to move); or the server can't be
 * reached (record kept for the next pass).
 */
object SnoozeProcessor {

    /** Returns how many messages were moved back. The stores are
     * passed in (desktop port seam, 2026-11-13): on Android the
     * worker and the view model hand over the same store instances
     * they already hold; on desktop the desktop stores ride in —
     * the return logic itself is platform-free. */
    suspend fun processDue(secure: SecureStoreApi, settings: SettingsStoreApi): Int {
        val repo = AccountRepository(secure)
        val account = repo.activeAccount() ?: return 0
        val records = SnoozeLogic.decode(settings.snoozeRecordsEncoded)
        if (records.isEmpty()) return 0
        val due = SnoozeLogic.due(records, System.currentTimeMillis())
        if (due.isEmpty()) return 0
        val service = repo.serviceFor(account)
        var remaining = records
        var moved = 0
        for (record in due) {
            val ok = try {
                service.moveByMessageId(
                    account.address,
                    account.password,
                    MailRules.snoozedTarget(),
                    record.messageId,
                    record.originFolder,
                )
            } catch (e: Exception) {
                // Unreachable: keep the record for the next pass.
                continue
            }
            // Moved back (true) or gone for good (false): either
            // way this record's work is finished.
            remaining = SnoozeLogic.remove(remaining, record.messageId)
            if (ok) moved++
        }
        settings.snoozeRecordsEncoded = SnoozeLogic.encode(remaining)
        return moved
    }
}
