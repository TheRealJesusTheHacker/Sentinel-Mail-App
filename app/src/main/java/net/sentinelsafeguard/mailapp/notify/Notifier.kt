package net.sentinelsafeguard.mailapp.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import net.sentinelsafeguard.mailapp.MainActivity
import net.sentinelsafeguard.mailapp.R

/**
 * New-mail notifications. The small icon is the silhouette
 * mechanically derived from the owner's real logo (see
 * generate_icons.py) — the family pattern from Command App 1.5.3,
 * never a drawn mark.
 *
 * Copy honesty: this app CHECKS on a schedule. Nothing here ever
 * says "push", "instant", or "real-time".
 */
object Notifier {

    const val CHANNEL_ID = "new_mail"
    const val EXTRA_FOLDER = "net.sentinelsafeguard.mailapp.extra.FOLDER"
    const val EXTRA_UID = "net.sentinelsafeguard.mailapp.extra.UID"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_new_mail),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_new_mail_desc)
        }
        manager.createNotificationChannel(channel)
    }

    /** One notification per newly arrived message (capped by the
     * caller). Tapping opens that message in the app. */
    fun notifyNewMail(
        context: Context,
        folder: String,
        uid: Long,
        from: String,
        subject: String,
    ) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_FOLDER, folder)
            putExtra(EXTRA_UID, uid)
        }
        val pending = PendingIntent.getActivity(
            context,
            (uid and 0x7fffffff).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sentinel)
            .setContentTitle(from.ifBlank { context.getString(R.string.notif_fallback_title) })
            .setContentText(subject.ifBlank { context.getString(R.string.notif_no_subject) })
            .setStyle(NotificationCompat.BigTextStyle().bigText(subject))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(
                (uid and 0x7fffffff).toInt() + NOTIFICATION_BASE,
                notification,
            )
        }
    }

    /**
     * The badge-worthy summary (1.2.0): when several messages
     * arrive in one check, ONE notification carries the count
     * and the sender names instead of a stack of singles —
     * tapping it opens the inbox. It replaces the singles for
     * that check (the worker posts either singles or this).
     */
    fun notifyNewMailSummary(
        context: Context,
        folder: String,
        count: Int,
        senders: List<String>,
    ) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_FOLDER, folder)
        }
        val pending = PendingIntent.getActivity(
            context,
            SUMMARY_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val names = senders.filter { it.isNotBlank() }.distinct().take(4)
        val title = if (count == 1) {
            context.getString(R.string.notif_new_one)
        } else {
            context.getString(R.string.notif_new_many, count)
        }
        val text = when {
            names.isEmpty() -> context.getString(R.string.notif_in_inbox)
            count > names.size -> context.getString(
                R.string.notif_from_others, names.joinToString(", "),
            )
            else -> context.getString(R.string.notif_from, names.joinToString(", "))
        }
        val style = NotificationCompat.InboxStyle()
            .setSummaryText(text)
        for (name in names) style.addLine(name)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sentinel)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setNumber(count)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(SUMMARY_NOTIFICATION_ID, notification)
        }
    }

    private const val NOTIFICATION_BASE = 4100
    private const val SUMMARY_NOTIFICATION_ID = 4099
    private const val SUMMARY_REQUEST_CODE = 4099
}
