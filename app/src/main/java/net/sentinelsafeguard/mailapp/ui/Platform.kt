package net.sentinelsafeguard.mailapp.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.launch
import net.sentinelsafeguard.mailapp.BuildConfig
import net.sentinelsafeguard.mailapp.R
import net.sentinelsafeguard.mailapp.data.SecureStore
import net.sentinelsafeguard.mailapp.data.SettingsStore
import net.sentinelsafeguard.mailapp.data.cache.MailCache
import net.sentinelsafeguard.mailapp.data.mail.HtmlSanitizer
import net.sentinelsafeguard.mailapp.data.mail.InlineImages
import net.sentinelsafeguard.mailapp.notify.NewMailWorker
import net.sentinelsafeguard.mailapp.ui.theme.SentinelMuted
import androidx.compose.ui.res.painterResource as AndroidPainterResource
import androidx.compose.ui.res.stringResource as AndroidStringResource

// ---------------------------------------------------------------------------
// Platform.kt — ANDROID implementations of the desktop-port seams
// (2026-11-13). Every function here has an identical signature in
// the desktop module's Platform.kt; the shared UI sources call
// only these. Android behavior is exactly what the pre-port code
// did inline — these are moves, not changes.
// ---------------------------------------------------------------------------

@Composable
fun stringResource(id: Int): String = AndroidStringResource(id)

@Composable
fun stringResource(id: Int, vararg formatArgs: Any): String =
    AndroidStringResource(id, *formatArgs)

@Composable
fun painterResource(id: Int): Painter = AndroidPainterResource(id)

/** Format a string-resource template outside composition (the
 *  callback paths). Pure JVM — the desktop twin is identical. */
fun formatText(template: String, vararg args: Any): String =
    String.format(java.util.Locale.getDefault(), template, *args)

/**
 * True when the system animator duration scale is 0 — the
 * device's reduced-motion setting. Read once per composition
 * that asks. Every motion pattern consults it.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        } catch (e: Exception) {
            false
        }
    }
}

/** The family haptic tick (garnish only, always guarded). */
@Composable
fun rememberHapticTick(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            } catch (ignored: Exception) {
                // Garnish only — never break the action.
            }
        }
    }
}

/** The primary-action haptic: CONFIRM on API 30+, LONG_PRESS below. */
@Composable
fun rememberPrimaryHaptic(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                } else {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            } catch (ignored: Exception) {
                // Garnish only — never break the action.
            }
        }
    }
}

@Composable
fun appVersionName(): String = BuildConfig.VERSION_NAME

@Composable
fun appVersionCode(): Int = BuildConfig.VERSION_CODE

/** Retained holder so the plain MailViewModel survives
 *  configuration changes exactly as the old AndroidViewModel did. */
class MailViewModelHolder(app: Application) : AndroidViewModel(app) {
    val vm: MailViewModel = MailViewModel(
        secureStore = SecureStore(app),
        settingsStore = SettingsStore(app),
        cache = MailCache(app),
        onSignOutPlatform = { NewMailWorker.cancel(app) },
    )

    override fun onCleared() {
        vm.close()
    }
}

@Composable
fun rememberMailViewModel(): MailViewModel {
    val holder: MailViewModelHolder = viewModel()
    return holder.vm
}

/** Android's relock law lives in MainActivity.onStart (the
 *  five-minute background rule) — nothing for the shared root to
 *  do here. Desktop's twin watches window focus instead. */
@Composable
fun PlatformLifecycleEffects(viewModel: MailViewModel) {
}

/** The interval radio in Settings reschedules the periodic
 *  WorkManager check to match. */
@Composable
fun rememberCheckScheduler(): () -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { NewMailWorker.reschedule(context) }
    }
}

/** Android needs no extra settings section: reduced motion is the
 *  system animator scale and the credential store is the Android
 *  Keystore — both already stated in the app's own copy. The
 *  desktop edition renders its own section here (storage posture
 *  label + reduce-motion switch). */
@Composable
fun PlatformExtraSettings() {
}

/** Lock-screen body copy: the Android wording (the password stays
 *  in this phone's secure storage). Desktop's twin names this
 *  computer's storage instead. */
fun lockBodyRes(): Int = R.string.lock_body

/** Settings lock explainer: the Android wording (biometrics,
 *  device unlock, or PIN). Desktop's twin promises PIN only. */
fun lockExplainerRes(): Int = R.string.settings_lock_explainer

/**
 * The notification-permission explainer (the family's pattern:
 * ask once, in context, with the reason stated, before any
 * announcement can show). Rendered at the end of the folder list;
 * owns its own open state exactly as the pre-port folder screen
 * did inline.
 */
@Composable
fun PlatformNotificationExplainer(viewModel: MailViewModel) {
    val context = LocalContext.current
    val settings = viewModel.settingsStore
    var explainerOpen by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { settings.notificationPermissionAsked = true }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            settings.notificationsEnabled &&
            !settings.notificationPermissionAsked &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            explainerOpen = true
        }
    }
    if (explainerOpen) {
        AlertDialog(
            onDismissRequest = {
                explainerOpen = false
                settings.notificationPermissionAsked = true
            },
            title = { Text(stringResource(R.string.notify_explainer_title)) },
            text = { Text(stringResource(R.string.notify_explainer_body)) },
            confirmButton = {
                TextButton(onClick = {
                    explainerOpen = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) { Text(stringResource(R.string.notify_explainer_allow)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    explainerOpen = false
                    settings.notificationPermissionAsked = true
                }) { Text(stringResource(R.string.notify_explainer_not_now)) }
            },
        )
    }
}

/**
 * The Settings notifications section: the master switch plus the
 * standing permission-grant row — verbatim the pre-port block,
 * state included. Desktop's twin states plainly that the desktop
 * edition posts no system notifications instead.
 */
@Composable
fun PlatformNotificationsSection(viewModel: MailViewModel) {
    val context = LocalContext.current
    val settings = viewModel.settingsStore
    var notificationsOn by remember { mutableStateOf(settings.notificationsEnabled) }
    var permissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        settings.notificationPermissionAsked = true
    }
    Text(stringResource(R.string.settings_notifications), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    if (notificationsOn) R.string.settings_notifications_on
                    else R.string.settings_notifications_off,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.settings_notifications_explainer),
                color = SentinelMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Switch(
            checked = notificationsOn,
            onCheckedChange = { on ->
                notificationsOn = on
                settings.notificationsEnabled = on
            },
        )
    }
    if (notificationsOn && !permissionGranted) {
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.settings_permission_missing),
            color = SentinelMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }) { Text(stringResource(R.string.settings_permission_grant)) }
    }
}

/**
 * The lock screen's biometric / device-credential unlock button —
 * verbatim the pre-port LockScreen logic. Desktop's twin renders
 * nothing: the desktop lock is PIN only, and no row claims
 * otherwise.
 */
@Composable
fun PlatformBiometricButton(
    viewModel: MailViewModel,
    hasPin: Boolean,
    setError: (String?) -> Unit,
) {
    val context = LocalContext.current
    fun tryBiometric() {
        val activity = context as? FragmentActivity ?: return
        val manager = BiometricManager.from(context)
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }
        if (manager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            if (!hasPin) {
                // No biometric and no PIN: let the PIN-less owner in
                // rather than bricking the mailbox behind a lock
                // with no key (the pre-port fallback, unchanged).
                viewModel.unlock()
            } else {
                setError(context.getString(R.string.lock_biometric_unavailable))
            }
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.unlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        setError(errString.toString())
                    }
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.lock_title))
            .setSubtitle(context.getString(R.string.lock_subtitle))
            .setAllowedAuthenticators(authenticators)
            .apply {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    setNegativeButtonText(context.getString(R.string.lock_use_pin))
                }
            }
            .build()
        prompt.authenticate(info)
    }
    Button(
        onClick = { tryBiometric() },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.lock_unlock_biometrics)) }
}

/**
 * The one WebView in the app, and it is a cage: JavaScript
 * disabled, no file or content access, no DOM storage, no
 * zoom-driven script surface, and network images blocked unless
 * the reader asked for them for this message. The HTML it shows
 * has already passed through [HtmlSanitizer]; nothing the sender
 * wrote reaches this view unexamined. (Moved verbatim from
 * MessageReadScreen in the desktop port; desktop's twin renders
 * the same sanitized model natively in Compose — no WebView
 * exists there.)
 */
@Composable
fun FormattedMailView(
    html: String,
    inlineParts: Map<String, InlineImages.Part>,
    loadImages: Boolean,
) {
    val document = remember(html, inlineParts) {
        // The message's own inline images are inlined FIRST (as
        // data: URIs), then the result passes the sanitizer —
        // the cage gets the last word, as always.
        val body = HtmlSanitizer.sanitize(InlineImages.inlineIntoHtml(html, inlineParts))
        """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body { background:#060806; color:#E6EDE6; font-family:sans-serif;
                 font-size:15px; line-height:1.5; margin:0; padding:18px;
                 word-wrap:break-word; }
          a { color:#00E676; }
          img { max-width:100%; height:auto; }
          table { max-width:100%; }
          pre { white-space:pre-wrap; }
        </style></head><body>$body</body></html>
        """.trimIndent()
    }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.databaseEnabled = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.setSupportZoom(false)
                webViewClient = WebViewClient()
                setBackgroundColor(0xFF060806.toInt())
            }
        },
        update = { view ->
            view.settings.blockNetworkImage = !loadImages
            view.settings.loadsImagesAutomatically = loadImages
            view.loadDataWithBaseURL(null, document, "text/html", "UTF-8", null)
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(560.dp)
            .background(MaterialTheme.colorScheme.background),
    )
}

/** Decode image bytes for the in-place attachment preview. */
fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

/**
 * The attachment save flow's platform half: the system document
 * picker (SAF CreateDocument — no storage permission). The
 * callback receives a writer for the chosen destination, or null
 * when the reader cancelled.
 */
@Composable
fun rememberAttachmentSaveLauncher(
    onResult: (writer: ((ByteArray) -> Unit)?) -> Unit,
): (String) -> Unit {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        if (uri == null) {
            callback(null)
        } else {
            callback { bytes ->
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("No output stream.")
            }
        }
    }
    return remember(launcher) { { name: String -> launcher.launch(name) } }
}

/**
 * Open a downloaded attachment in another app: the bytes land in
 * the app-private cache and leave only via a FileProvider grant
 * on this user action (the pre-port behavior, unchanged).
 */
@Composable
fun rememberExternalFileOpener(): (fileName: String, mimeType: String, bytes: ByteArray) -> Unit {
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.read_open_attachment)
    return remember(context) {
        { fileName: String, mimeType: String, bytes: ByteArray ->
            val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
            val safeName = fileName
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "attachment" }
            val file = File(dir, safeName)
            file.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, chooserTitle))
        }
    }
}

/**
 * The compose file picker: the system document picker, files read
 * through the resolver (the pre-port behavior — the shared screen
 * applies the size cap to what arrives).
 */
@Composable
fun rememberFilePicker(
    onFiles: (List<PickedAttachment>) -> Unit,
    onReadError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filesCallback by rememberUpdatedState(onFiles)
    val errorCallback by rememberUpdatedState(onReadError)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = mutableListOf<PickedAttachment>()
            for (uri in uris) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() } ?: continue
                    var name = "attachment"
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && cursor.moveToFirst()) {
                            cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { name = it }
                        }
                    }
                    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    picked += PickedAttachment(name, mime, bytes)
                } catch (e: Exception) {
                    errorCallback(e.message ?: "")
                }
            }
            if (picked.isNotEmpty()) filesCallback(picked)
        }
    }
    return remember(launcher) { { launcher.launch(arrayOf("*/*")) } }
}

/** The phone has a camera; the compose screen shows the camera
 *  attach row. Desktop's twin answers false and the row is
 *  absent there — not rendered, not disabled. */
@Composable
fun platformHasCamera(): Boolean = true

/**
 * Camera attach: the photo lands in the app's cache attachments
 * dir (already FileProvider-covered), is read once, and the
 * bytes go to the shared screen, which caps and names them like
 * any attachment (the pre-port flow, unchanged).
 */
@Composable
fun rememberCameraAttach(onPhoto: (ByteArray) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photoCallback by rememberUpdatedState(onPhoto)
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = cameraUri
        cameraUri = null
        if (!ok || uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes() } ?: return@launch
                photoCallback(bytes)
            }
        }
    }
    return remember(camera, context) {
        {
            val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
            val file = File(dir, "camera-${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            cameraUri = uri
            camera.launch(uri)
        }
    }
}
