package net.sentinelsafeguard.mailapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.sentinelsafeguard.mailapp.data.mail.ComposeModel
import net.sentinelsafeguard.mailapp.data.mail.MailMessage

/** How a compose session starts. The original message rides in
 * memory only — nothing mail-shaped is written to disk. */
sealed interface ComposeInit {
    data class New(val fromFolder: String) : ComposeInit
    data class Reply(val fromFolder: String, val original: MailMessage, val all: Boolean) : ComposeInit
    data class Forward(val fromFolder: String, val original: MailMessage) : ComposeInit
    data class FromDraft(val model: ComposeModel) : ComposeInit
}

sealed interface Route {
    data object Folders : Route
    data class Messages(val folder: String) : Route
    data class Read(val folder: String, val uid: Long) : Route
    data class Compose(val init: ComposeInit) : Route
    data object Settings : Route
}

@Composable
fun AppRoot(
    viewModel: MailViewModel,
    deepLinkFolder: String?,
    deepLinkUid: Long?,
    deepLinkNonce: Int,
    onDeepLinkConsumed: () -> Unit,
) {
    if (viewModel.account == null) {
        SignInScreen(viewModel)
        return
    }
    if (viewModel.locked) {
        LockScreen(viewModel)
        return
    }

    var route by remember { mutableStateOf<Route>(Route.Folders) }

    LaunchedEffect(deepLinkNonce) {
        if (deepLinkFolder != null && deepLinkUid != null) {
            route = Route.Read(deepLinkFolder, deepLinkUid)
            onDeepLinkConsumed()
        }
    }

    when (val r = route) {
        Route.Folders -> FolderListScreen(
            viewModel,
            onOpenFolder = { route = Route.Messages(it) },
            onCompose = { route = Route.Compose(ComposeInit.New("INBOX")) },
            onSettings = { route = Route.Settings },
        )
        is Route.Messages -> MessageListScreen(
            viewModel,
            folder = r.folder,
            onBack = { route = Route.Folders },
            onOpenMessage = { uid -> route = Route.Read(r.folder, uid) },
            onCompose = { route = Route.Compose(ComposeInit.New(r.folder)) },
            onOpenDraft = { model -> route = Route.Compose(ComposeInit.FromDraft(model)) },
        )
        is Route.Read -> MessageReadScreen(
            viewModel,
            folder = r.folder,
            uid = r.uid,
            onBack = { route = Route.Messages(r.folder) },
            onReply = { original, all ->
                route = Route.Compose(ComposeInit.Reply(r.folder, original, all))
            },
            onForward = { original ->
                route = Route.Compose(ComposeInit.Forward(r.folder, original))
            },
            onGone = { route = Route.Messages(r.folder) },
        )
        is Route.Compose -> ComposeScreen(
            viewModel,
            init = r.init,
            onDone = { route = Route.Folders },
        )
        Route.Settings -> SettingsScreen(
            viewModel,
            onBack = { route = Route.Folders },
        )
    }
}
