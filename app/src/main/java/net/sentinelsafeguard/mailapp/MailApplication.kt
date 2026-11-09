package net.sentinelsafeguard.mailapp

import android.app.Application
import net.sentinelsafeguard.mailapp.notify.NewMailWorker
import net.sentinelsafeguard.mailapp.notify.Notifier

class MailApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
        // Keep the periodic check aligned with the saved setting
        // across restarts and updates.
        NewMailWorker.reschedule(this)
    }
}
