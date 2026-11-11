package net.sentinelsafeguard.mailapp.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.sentinelsafeguard.mailapp.data.AccountRepository
import net.sentinelsafeguard.mailapp.data.SecureStore
import net.sentinelsafeguard.mailapp.data.SettingsStore
import java.util.concurrent.TimeUnit

/**
 * The periodic new-mail check. WorkManager runs it on the user's
 * chosen interval (15 minutes by default — Android's own floor for
 * periodic work); each run diffs the INBOX's highest UID against
 * the last check and notifies for what is genuinely new.
 *
 * This is periodic checking. It is never called push anywhere in
 * this app, because it isn't.
 */
class NewMailWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val secure = SecureStore(applicationContext)
        val settings = SettingsStore(applicationContext)
        val repo = AccountRepository(secure)
        val account = repo.activeAccount() ?: return Result.success()
        val service = repo.serviceFor(account)
        return try {
            val (validity, maxUid) = service.inboxState(account.address, account.password)
            val lastValidity = settings.lastSeenInboxUidValidity
            val lastUid = settings.lastSeenInboxUid
            when {
                // First ever check (or a fresh sign-in): baseline
                // silently — the existing box is not "new mail".
                lastValidity == -1L -> {
                    settings.lastSeenInboxUidValidity = validity
                    settings.lastSeenInboxUid = maxUid
                }
                // UID space changed under us: re-baseline silently.
                validity != lastValidity -> {
                    settings.lastSeenInboxUidValidity = validity
                    settings.lastSeenInboxUid = maxUid
                }
                maxUid > lastUid -> {
                    // Headers only, and a window wide enough for a
                    // busy interval: 1.2.0/1.2.1 peeked at the
                    // newest 25 WITH body snippets, so a burst
                    // larger than 25 was silently undercounted
                    // while the baseline jumped past it (D11).
                    val fresh = service
                        .listMessages(
                            account.address, account.password, "INBOX",
                            limit = 100, snippets = false,
                        )
                        .filter { it.uid > lastUid && it.unread }
                    if (settings.notificationsEnabled) {
                        // One arrival gets its own tappable
                        // notification; a batch gets ONE summary
                        // (count + senders) — a stack of singles
                        // for a busy minute is noise, not signal.
                        if (fresh.size == 1) {
                            val only = fresh.single()
                            Notifier.notifyNewMail(
                                applicationContext,
                                folder = "INBOX",
                                uid = only.uid,
                                from = only.from,
                                subject = only.subject,
                            )
                        } else if (fresh.isNotEmpty()) {
                            Notifier.notifyNewMailSummary(
                                applicationContext,
                                folder = "INBOX",
                                count = fresh.size,
                                senders = fresh.map { it.from },
                            )
                        }
                    }
                    // The baseline advances either way: with the
                    // switch off, mail is accounted for silently
                    // so re-enabling never dumps the backlog.
                    settings.lastSeenInboxUid = maxUid
                }
            }
            // Snoozed mail rides this same tick (1.2.0): the
            // checker's cadence is the return cadence, disclosed
            // as approximate in the snooze dialog itself. Its own
            // failure never fails the check.
            runCatching { SnoozeProcessor.processDue(applicationContext) }
            Result.success()
        } catch (e: Exception) {
            // A failed check is a quiet retry later, never a
            // user-facing alarm — the mailbox may just be offline.
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "sentinel_mail_periodic_check"

        /** (Re)schedule to match the setting; 0 minutes = manual
         * only, which cancels the periodic work outright. */
        fun reschedule(context: Context) {
            val settings = SettingsStore(context)
            val minutes = settings.checkIntervalMinutes
            val manager = WorkManager.getInstance(context)
            if (minutes <= 0) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<NewMailWorker>(
                minutes.toLong(), TimeUnit.MINUTES,
            ).build()
            manager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
