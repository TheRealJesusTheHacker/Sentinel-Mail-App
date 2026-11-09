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
                    val fresh = service
                        .listMessages(account.address, account.password, "INBOX", limit = 25)
                        .filter { it.uid > lastUid && it.unread }
                        .take(MAX_NOTIFICATIONS)
                    for (summary in fresh) {
                        Notifier.notifyNewMail(
                            applicationContext,
                            folder = "INBOX",
                            uid = summary.uid,
                            from = summary.from,
                            subject = summary.subject,
                        )
                    }
                    settings.lastSeenInboxUid = maxUid
                }
            }
            Result.success()
        } catch (e: Exception) {
            // A failed check is a quiet retry later, never a
            // user-facing alarm — the mailbox may just be offline.
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "sentinel_mail_periodic_check"
        private const val MAX_NOTIFICATIONS = 5

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
