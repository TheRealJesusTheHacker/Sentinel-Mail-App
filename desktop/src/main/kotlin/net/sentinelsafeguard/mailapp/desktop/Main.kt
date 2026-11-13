package net.sentinelsafeguard.mailapp.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import net.sentinelsafeguard.mailapp.R
import net.sentinelsafeguard.mailapp.ui.AppRoot
import net.sentinelsafeguard.mailapp.ui.painterResource
import net.sentinelsafeguard.mailapp.ui.rememberMailViewModel
import net.sentinelsafeguard.mailapp.ui.theme.SentinelTheme

// Sentinel Mail desktop entry point (desktop port, 2026-11-13).
// The whole shared UI runs here unchanged: sign-in, folders,
// lists, read, compose, settings, lock — driven by the desktop
// stores behind the same seams the Android app uses.
fun main() = application {
    val icon = painterResource(R.drawable.mail_mark)
    Window(
        onCloseRequest = ::exitApplication,
        title = "Sentinel Mail",
        icon = icon,
        state = WindowState(size = DpSize(1180.dp, 800.dp)),
    ) {
        SentinelTheme {
            val viewModel = rememberMailViewModel()
            AppRoot(
                viewModel = viewModel,
                deepLinkFolder = null,
                deepLinkUid = null,
                deepLinkNonce = 0,
                onDeepLinkConsumed = {},
            )
        }
    }
}
