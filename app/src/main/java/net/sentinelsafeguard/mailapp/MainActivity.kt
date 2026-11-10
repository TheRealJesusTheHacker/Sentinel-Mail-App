package net.sentinelsafeguard.mailapp

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import net.sentinelsafeguard.mailapp.notify.Notifier
import net.sentinelsafeguard.mailapp.ui.AppRoot
import net.sentinelsafeguard.mailapp.ui.MailViewModel
import net.sentinelsafeguard.mailapp.ui.theme.SentinelTheme

class MainActivity : FragmentActivity() {

    private val viewModel: MailViewModel by viewModels()
    private var backgroundedAtMillis: Long = 0L

    // A notification tap's destination: folder + message UID, plus
    // a nonce so a second tap on the same message re-navigates.
    private var deepLinkFolder by mutableStateOf<String?>(null)
    private var deepLinkUid by mutableStateOf<Long?>(null)
    private var deepLinkNonce by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mail content and credentials stay off screenshots, the
        // recents thumbnail, and screen recording — family parity.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        readDeepLink(intent)
        setContent {
            SentinelTheme {
                AppRoot(
                    viewModel,
                    deepLinkFolder = deepLinkFolder,
                    deepLinkUid = deepLinkUid,
                    deepLinkNonce = deepLinkNonce,
                    onDeepLinkConsumed = { deepLinkFolder = null; deepLinkUid = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDeepLink(intent)
    }

    private fun readDeepLink(intent: Intent?) {
        val folder = intent?.getStringExtra(Notifier.EXTRA_FOLDER)?.takeIf { it.isNotBlank() }
        val uid = intent?.getLongExtra(Notifier.EXTRA_UID, -1L)?.takeIf { it > 0 }
        // Folder-with-UID opens the message; folder alone (the
        // 1.2.0 summary notification) opens that folder's list.
        if (folder != null) {
            deepLinkFolder = folder
            deepLinkUid = uid
            deepLinkNonce++
        }
    }

    override fun onStop() {
        super.onStop()
        backgroundedAtMillis = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        // Re-lock after about five minutes in the background
        // (the family lock pattern).
        if (backgroundedAtMillis > 0 &&
            System.currentTimeMillis() - backgroundedAtMillis > RELOCK_AFTER_MS
        ) {
            viewModel.lockNow()
        }
        backgroundedAtMillis = 0L
    }

    companion object {
        const val RELOCK_AFTER_MS = 5 * 60 * 1000L
    }
}
