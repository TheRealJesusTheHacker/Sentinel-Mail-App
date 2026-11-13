package net.sentinelsafeguard.mailapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sentinelsafeguard.mailapp.R
import net.sentinelsafeguard.mailapp.data.DesktopKv
import net.sentinelsafeguard.mailapp.data.DesktopMailCache
import net.sentinelsafeguard.mailapp.data.DesktopSecureStore
import net.sentinelsafeguard.mailapp.data.DesktopSettingsStore
import net.sentinelsafeguard.mailapp.data.mail.HtmlSanitizer
import net.sentinelsafeguard.mailapp.data.mail.InlineImages
import net.sentinelsafeguard.mailapp.desktop.DesktopStrings
import net.sentinelsafeguard.mailapp.ui.theme.SentinelGreen
import net.sentinelsafeguard.mailapp.ui.theme.SentinelMuted
import net.sentinelsafeguard.mailapp.ui.theme.SentinelSurfaceTop
import org.jetbrains.skia.Image as SkiaImage

// ---------------------------------------------------------------------------
// Platform.kt — DESKTOP implementations of the port seams.
// Signatures are identical to the Android Platform.kt; behavior
// notes live on each function where desktop genuinely differs.
// ---------------------------------------------------------------------------

private fun stringsFor(locale: Locale): Map<Int, String> =
    if (locale.language == "es") DesktopStrings.ES else DesktopStrings.EN

@Composable
fun stringResource(id: Int): String =
    stringsFor(Locale.getDefault())[id] ?: DesktopStrings.EN[id] ?: ""

@Composable
fun stringResource(id: Int, vararg formatArgs: Any): String {
    val template = stringResource(id)
    return runCatching { String.format(Locale.getDefault(), template, *formatArgs) }
        .getOrDefault(template)
}

/** Format a string-resource template outside composition (the
 *  callback paths). Pure JVM — the Android twin is identical. */
fun formatText(template: String, vararg args: Any): String =
    String.format(Locale.getDefault(), template, *args)

@Composable
fun painterResource(id: Int): Painter {
    val path = when (id) {
        R.drawable.mail_mark -> "images/mail_mark.png"
        else -> "images/sentinel_logo.jpg"
    }
    return remember(path) {
        val bytes = requireNotNull(
            Thread.currentThread().contextClassLoader.getResourceAsStream(path),
        ) { "missing desktop resource $path" }.readAllBytes()
        BitmapPainter(SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap())
    }
}

private object MotionPrefs {
    var enabled: Boolean by mutableStateOf(DesktopKv.pref("reduce_motion") == "1")
        private set

    fun set(value: Boolean) {
        enabled = value
        DesktopKv.setPref("reduce_motion", if (value) "1" else "0")
    }
}

/** Desktop reduced motion is the app's own setting (Settings →
 *  Reduce motion, persisted in the desktop store); there is no
 *  desktop animator-scale equivalent to read. */
@Composable
fun rememberReducedMotion(): Boolean = MotionPrefs.enabled

/** Desktop has no haptic device path; the seams are no-ops. */
@Composable
fun rememberHapticTick(): () -> Unit = remember { {} }

@Composable
fun rememberPrimaryHaptic(): () -> Unit = remember { {} }

@Composable
fun appVersionName(): String = "1.0.0"

@Composable
fun appVersionCode(): Int = 1

@Composable
fun rememberMailViewModel(): MailViewModel {
    val vm = remember {
        MailViewModel(
            secureStore = DesktopSecureStore(),
            settingsStore = DesktopSettingsStore(),
            cache = DesktopMailCache(),
            onSignOutPlatform = {},
        ).also { it.startRunningChecker() }
    }
    DisposableEffect(vm) {
        onDispose { vm.close() }
    }
    return vm
}

/** Window-focus twin of Android's MainActivity relock law: after
 *  about five minutes without focus, returning re-locks (when the
 *  lock is armed). */
@Composable
fun PlatformLifecycleEffects(viewModel: MailViewModel) {
    val focused = LocalWindowInfo.current.isWindowFocused
    var unfocusedAt by remember { mutableStateOf(0L) }
    LaunchedEffect(focused) {
        if (!focused) {
            unfocusedAt = System.currentTimeMillis()
        } else {
            if (unfocusedAt > 0 &&
                System.currentTimeMillis() - unfocusedAt > 5 * 60 * 1000L
            ) {
                viewModel.lockNow()
            }
            unfocusedAt = 0L
        }
    }
}

/** The desktop checker reads the interval live on every tick
 *  (MailViewModel.startRunningChecker), so there is no worker to
 *  reschedule when the setting changes. */
@Composable
fun rememberCheckScheduler(): () -> Unit = remember { {} }

/** Desktop-only settings section: the Reduce motion switch and
 *  the honest credential-storage label for THIS machine — OS key
 *  storage when it is actually in use, the labeled file fallback
 *  when it is not — plus the desktop checks note. */
@Composable
fun PlatformExtraSettings() {
    Spacer(Modifier.height(18.dp))
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                stringResource(R.string.settings_reduce_motion),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(R.string.settings_reduce_motion_body),
                style = MaterialTheme.typography.bodySmall,
                color = SentinelMuted,
            )
        }
        Switch(
            checked = MotionPrefs.enabled,
            onCheckedChange = { MotionPrefs.set(it) },
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.desktop_checks_note),
        style = MaterialTheme.typography.bodySmall,
        color = SentinelMuted,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(
            if (DesktopKv.osKeyStorageActive) {
                R.string.about_storage_os
            } else {
                R.string.about_storage_fallback
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = SentinelMuted,
    )
}

fun lockBodyRes(): Int = R.string.lock_body_desktop

fun lockExplainerRes(): Int = R.string.settings_lock_explainer_desktop

/** Desktop posts no system notifications, so there is no
 *  permission to explain or request — the explainer never opens.
 *  New mail surfaces in-app (see the notifications section). */
@Composable
fun PlatformNotificationExplainer(viewModel: MailViewModel) {
}

/** The desktop notifications section states the disposition
 *  plainly: no system notifications on desktop; unread counts
 *  while the app is open are the surfacing. No switch pretends
 *  otherwise. */
@Composable
fun PlatformNotificationsSection(viewModel: MailViewModel) {
    Text(
        stringResource(R.string.settings_notifications),
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(R.string.desktop_notifications_body),
        color = SentinelMuted,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Desktop unlock is PIN only — no biometric row is rendered. */
@Composable
fun PlatformBiometricButton(
    viewModel: MailViewModel,
    hasPin: Boolean,
    setError: (String?) -> Unit,
) {
}

// ---------------------------------------------------------------------------
// Formatted mail — the native Compose rendering.
//
// Android shows the sanitized HTML in a locked-down WebView;
// Compose Desktop has no WebView, so the desktop twin renders the
// same sanitized block model natively (HtmlBlocks). The security
// shape is unchanged: only HtmlSanitizer output reaches the
// renderer, the message's own CID images are inlined first as
// data: URIs, and remote images are fetched ONLY when the reader
// loaded images for this message — until then an http(s) image
// renders as a labeled placeholder and nothing is fetched.
// ---------------------------------------------------------------------------

@Composable
fun FormattedMailView(
    html: String,
    inlineParts: Map<String, InlineImages.Part>,
    loadImages: Boolean,
) {
    val blocks = remember(html, inlineParts) {
        HtmlBlocks.parse(HtmlSanitizer.sanitize(InlineImages.inlineIntoHtml(html, inlineParts)))
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        blocks.forEach { block ->
            when (block) {
                is HtmlBlock.Heading -> {
                    Text(
                        block.text,
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        },
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                is HtmlBlock.Paragraph -> {
                    LinkedText(block.text, MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                }
                is HtmlBlock.Quote -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                            .padding(vertical = 4.dp),
                    ) {
                        Box(
                            Modifier
                                .padding(end = 10.dp)
                                .background(SentinelGreen)
                                .fillMaxHeight()
                                .width(3.dp),
                        )
                        LinkedText(
                            block.text,
                            MaterialTheme.typography.bodyLarge,
                            color = SentinelMuted,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                is HtmlBlock.Pre -> {
                    Text(
                        block.text,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SentinelSurfaceTop)
                            .padding(10.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                is HtmlBlock.ListBlock -> {
                    block.items.forEachIndexed { idx, item ->
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 3.dp)) {
                            Text(
                                if (block.ordered) "${idx + 1}. " else "• ",
                                style = MaterialTheme.typography.bodyLarge,
                                color = SentinelMuted,
                            )
                            LinkedText(item, MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                is HtmlBlock.ImageBlock -> {
                    MailImage(block, loadImages)
                    Spacer(Modifier.height(8.dp))
                }
                is HtmlBlock.TableBlock -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(SentinelSurfaceTop)
                            .padding(8.dp),
                    ) {
                        block.rows.forEachIndexed { r, row ->
                            if (r > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                )
                            }
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { cell ->
                                    Box(Modifier.weight(1f).padding(4.dp)) {
                                        LinkedText(
                                            cell,
                                            MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                HtmlBlock.Rule -> {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** A paragraph with tappable links (the only annotation the
 *  block model emits is "URL"). */
@Composable
private fun LinkedText(
    text: androidx.compose.ui.text.AnnotatedString,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) {
    val uriHandler = LocalUriHandler.current
    if (text.getStringAnnotations("URL", 0, text.length).isEmpty()) {
        Text(text, style = style, color = color)
    } else {
        ClickableText(text, style = style.copy(color = color)) { offset ->
            text.getStringAnnotations("URL", offset, offset)
                .firstOrNull()?.let { uriHandler.openUri(it.item) }
        }
    }
}

@Composable
private fun MailImage(block: HtmlBlock.ImageBlock, loadImages: Boolean) {
    val src = block.src
    when {
        src.startsWith("data:image/", ignoreCase = true) -> {
            val bitmap = remember(src) { decodeDataImage(src) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = block.alt,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                )
            } else {
                ImagePlaceholder(block.alt)
            }
        }
        src.startsWith("http://", ignoreCase = true) ||
            src.startsWith("https://", ignoreCase = true) -> {
            if (!loadImages) {
                // Blocked by default — the privacy rule. Nothing
                // is fetched; the placeholder is all that renders.
                ImagePlaceholder(block.alt)
            } else {
                val bitmap by produceState<ImageBitmap?>(null, src) {
                    value = withContext(Dispatchers.IO) { fetchImage(src) }
                }
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = block.alt,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    )
                } else {
                    ImagePlaceholder(block.alt)
                }
            }
        }
        else -> ImagePlaceholder(block.alt)
    }
}

@Composable
private fun ImagePlaceholder(alt: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SentinelSurfaceTop)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            alt.ifBlank { "Image" },
            color = SentinelMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun decodeDataImage(src: String): ImageBitmap? = runCatching {
    val comma = src.indexOf(',')
    if (comma < 0) return null
    val bytes = Base64.getDecoder().decode(src.substring(comma + 1))
    SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()

private fun fetchImage(src: String): ImageBitmap? = runCatching {
    val conn = (URL(src).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "SentinelMailDesktop/1.0.0")
    }
    val bytes = conn.inputStream.use { it.readBytes() }
    if (bytes.size > 10 * 1024 * 1024) {
        null
    } else {
        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    }
}.getOrNull()

/** Decode image bytes for the in-place attachment preview. */
fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? = runCatching {
    SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()

/**
 * The attachment save flow's platform half: the native save
 * dialog. The callback receives a writer for the chosen file, or
 * null when the reader cancelled.
 */
@Composable
fun rememberAttachmentSaveLauncher(
    onResult: (writer: ((ByteArray) -> Unit)?) -> Unit,
): (String) -> Unit {
    val callback by androidx.compose.runtime.rememberUpdatedState(onResult)
    return remember {
        { suggestedName: String ->
            val dialog = FileDialog(null as Frame?, "Save attachment", FileDialog.SAVE)
            dialog.file = suggestedName
            dialog.isVisible = true
            val dir = dialog.directory
            val name = dialog.file
            if (dir == null || name == null) {
                callback(null)
            } else {
                val target = File(dir, name)
                callback { bytes -> target.writeBytes(bytes) }
            }
        }
    }
}

/**
 * Open a downloaded attachment with the OS default application:
 * the bytes land in the app's per-user "opened" folder first
 * (the desktop twin of Android's cache + FileProvider hand-off).
 */
@Composable
fun rememberExternalFileOpener(): (fileName: String, mimeType: String, bytes: ByteArray) -> Unit =
    remember {
        { fileName: String, _: String, bytes: ByteArray ->
            runCatching {
                val dir = File(DesktopKv.configDir(), "opened").apply { mkdirs() }
                val safeName = fileName
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .ifBlank { "attachment" }
                val file = File(dir, safeName)
                file.writeBytes(bytes)
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(file)
                } else {
                    ProcessBuilder("xdg-open", file.absolutePath).start()
                }
            }
            Unit
        }
    }

/**
 * The compose file picker: the native open dialog (multiple
 * selection); files are read from the filesystem and handed to
 * the shared screen, which applies the size cap.
 */
@Composable
fun rememberFilePicker(
    onFiles: (List<PickedAttachment>) -> Unit,
    onReadError: (String) -> Unit,
): () -> Unit {
    val filesCallback by androidx.compose.runtime.rememberUpdatedState(onFiles)
    val errorCallback by androidx.compose.runtime.rememberUpdatedState(onReadError)
    return remember {
        {
            val dialog = FileDialog(null as Frame?, "Attach files", FileDialog.LOAD)
            dialog.isMultipleMode = true
            dialog.isVisible = true
            val picked = mutableListOf<PickedAttachment>()
            for (file in dialog.files.orEmpty()) {
                try {
                    val bytes = file.readBytes()
                    val mime = runCatching {
                        java.nio.file.Files.probeContentType(file.toPath())
                    }.getOrNull() ?: java.net.URLConnection.guessContentTypeFromName(file.name)
                        ?: "application/octet-stream"
                    picked += PickedAttachment(file.name, mime, bytes)
                } catch (e: Exception) {
                    errorCallback(e.message ?: "")
                }
            }
            if (picked.isNotEmpty()) filesCallback(picked)
            Unit
        }
    }
}

/** Desktop has no camera attach: the compose screen's camera row
 *  is absent on desktop (not rendered), per the port's honest
 *  dispositions. */
@Composable
fun platformHasCamera(): Boolean = false

@Composable
fun rememberCameraAttach(onPhoto: (ByteArray) -> Unit): () -> Unit = remember { {} }
