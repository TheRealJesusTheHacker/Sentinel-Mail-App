package net.sentinelsafeguard.mailapp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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

/** Navigation depth, for transition direction only: moving to a
 * deeper route slides forward (toward the message), moving back
 * slides the other way. Settings sits beside the folder list. */
private fun Route.depth(): Int = when (this) {
    Route.Folders -> 0
    is Route.Messages -> 1
    Route.Settings -> 1
    is Route.Read -> 2
    is Route.Compose -> 3
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

    // Screen transitions (1.1.0, estate motion standard): a tap
    // that opens a folder, a message, compose, or settings now
    // shows where it went — forward slides toward the deeper
    // screen, back slides home. Under reduced motion the whole
    // thing collapses to the family's 120 ms plain fade. The
    // transition explains navigation only; it never delays the
    // underlying load, which starts with the route change.
    val reduced = rememberReducedMotion()
    AnimatedContent(
        targetState = route,
        transitionSpec = {
            val forward = targetState.depth() >= initialState.depth()
            if (reduced) {
                fadeIn(tween(MotionLogic.REDUCED_FADE_MS)) togetherWith
                    fadeOut(tween(MotionLogic.REDUCED_FADE_MS))
            } else {
                (fadeIn(tween(MotionLogic.SCREEN_FADE_IN_MS)) +
                    slideInHorizontally(tween(MotionLogic.SCREEN_SLIDE_MS)) {
                        if (forward) it / 6 else -it / 6
                    }) togetherWith
                    (fadeOut(tween(MotionLogic.SCREEN_FADE_OUT_MS)) +
                        slideOutHorizontally(tween(MotionLogic.SCREEN_SLIDE_MS)) {
                            if (forward) -it / 6 else it / 6
                        })
            }
        },
        label = "route",
    ) { r ->
        when (r) {
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
}
