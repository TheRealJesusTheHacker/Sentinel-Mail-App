package net.sentinelsafeguard.mailapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.ReplyAll
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch
import net.sentinelsafeguard.mailapp.R
import net.sentinelsafeguard.mailapp.data.mail.HtmlSanitizer
import net.sentinelsafeguard.mailapp.data.mail.ImagePolicy
import net.sentinelsafeguard.mailapp.data.mail.InlineImages
import net.sentinelsafeguard.mailapp.data.mail.MailAttachment
import net.sentinelsafeguard.mailapp.data.mail.MailMessage
import net.sentinelsafeguard.mailapp.data.mail.MailRules
import net.sentinelsafeguard.mailapp.data.mail.MailSummary
import net.sentinelsafeguard.mailapp.data.mail.MailText
import net.sentinelsafeguard.mailapp.data.mail.SnoozeLogic
import net.sentinelsafeguard.mailapp.ui.theme.SentinelAmber
import net.sentinelsafeguard.mailapp.ui.theme.SentinelMuted
import net.sentinelsafeguard.mailapp.ui.theme.SentinelSurfaceTop

/**
 * One open message. The default view is the family's audited
 * plain-text rendering — the text is the mail. "View formatted"
 * (offered only when an HTML part exists) renders that part
 * through [HtmlSanitizer] inside the platform's formatted view
 * (the [FormattedMailView] seam): on Android a locked-down
 * WebView — JavaScript off, no file or content access; on
 * desktop a native Compose rendering of the same sanitized
 * block model, there being no WebView there. Either way remote
 * images stay blocked until the reader taps "Load remote
 * images" for THIS message only. Opening a message marks it
 * read on the server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageReadScreen(
    viewModel: MailViewModel,
    folder: String,
    uid: Long,
    onBack: () -> Unit,
    onReply: (MailMessage, Boolean) -> Unit,
    onForward: (MailMessage) -> Unit,
    onGone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val attachmentSavedText = stringResource(R.string.attachment_saved)
    val attachmentSaveFailedText = stringResource(R.string.attachment_save_failed)
    val openAttachmentExternal = rememberExternalFileOpener()
    var message by remember { mutableStateOf<MailMessage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var formatted by remember { mutableStateOf(false) }
    var loadImages by remember { mutableStateOf(false) }
    var senderAutoLoad by remember { mutableStateOf(false) }
    var inlineParts by remember { mutableStateOf<Map<String, InlineImages.Part>>(emptyMap()) }
    var menuOpen by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    var snoozeOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loadNonce by remember { mutableStateOf(0) }
    // 1.2.0: whether the message on screen is the offline cache's
    // saved copy, and when it was saved.
    var fromCache by remember { mutableStateOf(false) }
    var savedAtMs by remember { mutableStateOf(0L) }
    // Attachment save/preview state (1.2.0).
    var pendingSave by remember { mutableStateOf<MailAttachment?>(null) }
    var preview by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    // Attachment save (platform seam): Android's document
    // picker / the desktop save dialog hand back a writer for the
    // chosen destination; the download + notes are shared.
    val saveLauncher = rememberAttachmentSaveLauncher { writer ->
        val att = pendingSave
        pendingSave = null
        if (writer == null || att == null) return@rememberAttachmentSaveLauncher
        scope.launch {
            try {
                val bytes = viewModel.downloadAttachment(folder, uid, att.partIndex)
                writer(bytes)
                note = attachmentSavedText
            } catch (e: Exception) {
                error = attachmentSaveFailedText
            }
        }
    }

    LaunchedEffect(folder, uid, loadNonce) {
        try {
            val loaded = viewModel.loadMessage(folder, uid)
            message = loaded.message
            fromCache = loaded.fromCache
            savedAtMs = loaded.savedAtMs
            // Per-sender image memory (1.2.0): a remembered
            // sender's remote images load with the message.
            senderAutoLoad = ImagePolicy.shouldAutoLoad(
                loaded.message.from,
                viewModel.settingsStore.alwaysLoadImageSenders,
            )
            if (senderAutoLoad) loadImages = true
            if (!loaded.fromCache && loaded.message.unread) {
                // The engine marks SEEN as it fetches; this state
                // copy keeps the header honest and refreshes the
                // folder badges. A cached copy never runs this —
                // offline cannot mark anything read, and doesn't
                // pretend to.
                message = loaded.message.copy(unread = false)
                viewModel.loadFolders()
            }
        } catch (e: Exception) {
            error = MailViewModel.friendlyError(e)
        }
    }

    // CID parts ride with the formatted view (1.2.0): fetched
    // when the reader opens the formatted rendering, never
    // before, and never from the network — they are the
    // message's own bytes.
    LaunchedEffect(formatted, message?.uid) {
        if (formatted && message?.htmlBody != null && inlineParts.isEmpty() && !fromCache) {
            inlineParts = runCatching { viewModel.inlineImages(folder, uid) }
                .getOrDefault(emptyMap())
        }
    }

    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
                viewModel.loadFolders()
                onGone()
            } catch (e: Exception) {
                error = MailViewModel.friendlyError(e)
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(MailRules.displayName(folder)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.read_back_list),
                        )
                    }
                },
                actions = {
                    val msg = message
                    if (msg != null) {
                        IconButton(onClick = {
                            scope.launch {
                                try {
                                    viewModel.setStarred(folder, uid, !msg.starred)
                                    message = msg.copy(starred = !msg.starred)
                                } catch (e: Exception) {
                                    error = MailViewModel.friendlyError(e)
                                }
                            }
                        }) {
                            Icon(
                                if (msg.starred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = stringResource(
                                    if (msg.starred) R.string.unstar else R.string.star,
                                ),
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.more_actions),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.read_mark_unread)) },
                                leadingIcon = { Icon(Icons.Filled.MarkEmailUnread, null) },
                                onClick = {
                                    menuOpen = false
                                    act { viewModel.setSeen(folder, uid, false) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.snooze)) },
                                leadingIcon = { Icon(Icons.Filled.Bedtime, null) },
                                onClick = { menuOpen = false; snoozeOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.read_move_to)) },
                                leadingIcon = { Icon(Icons.Filled.DriveFileMove, null) },
                                onClick = { menuOpen = false; moveOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.read_archive)) },
                                leadingIcon = { Icon(Icons.Filled.Archive, null) },
                                onClick = {
                                    menuOpen = false
                                    act { viewModel.archive(folder, uid) }
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (MailRules.deleteIsPermanent(folder)) {
                                                R.string.read_delete_permanent
                                            } else {
                                                R.string.read_delete
                                            },
                                        ),
                                    )
                                },
                                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                onClick = {
                                    menuOpen = false
                                    act { viewModel.delete(folder, uid) }
                                },
                            )
                        }
                        DropdownMenu(expanded = moveOpen, onDismissRequest = { moveOpen = false }) {
                            val targets = viewModel.folders
                                .filter { !it.name.equals(folder, ignoreCase = true) }
                            if (targets.isEmpty()) {
                                // 1.1.0 fix: an empty move menu rendered as
                                // a control that did nothing. Say why, and
                                // offer the refresh that fills it.
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.read_move_loading)) },
                                    enabled = false,
                                    onClick = {},
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.read_move_reload)) },
                                    onClick = {
                                        moveOpen = false
                                        viewModel.loadFolders()
                                    },
                                )
                            }
                            targets.forEach { target ->
                                DropdownMenuItem(
                                    text = {
                                        Text(MailRules.displayName(target.name, target.attributes))
                                    },
                                    onClick = {
                                        moveOpen = false
                                        act { viewModel.moveTo(folder, uid, target.name) }
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        val msg = message
        when {
            error != null && msg == null -> {
                Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = { error = null; loadNonce++ }) {
                            Text(stringResource(R.string.try_again))
                        }
                    }
                }
            }
            msg == null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (fromCache) {
                        Surface(
                            color = SentinelSurfaceTop,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    R.string.offline_banner_read,
                                    savedAtLabel(savedAtMs),
                                ),
                                color = SentinelAmber,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            )
                        }
                    }
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        Text(msg.subject, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(10.dp))
                        HeaderLine(stringResource(R.string.read_from), msg.from, bold = true)
                        HeaderLine(stringResource(R.string.read_to), msg.to)
                        if (msg.cc.isNotBlank()) HeaderLine(stringResource(R.string.read_cc), msg.cc)
                        HeaderLine(stringResource(R.string.read_date), msg.dateText)
                        if (error != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(error!!, color = MaterialTheme.colorScheme.error)
                        }
                        if (note != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(note!!, color = SentinelMuted)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedButton(onClick = { onReply(msg, false) }) {
                            Icon(Icons.Filled.Reply, null, Modifier.padding(end = 6.dp))
                            Text(stringResource(R.string.read_reply))
                        }
                        OutlinedButton(onClick = { onReply(msg, true) }) {
                            Icon(Icons.Filled.ReplyAll, null, Modifier.padding(end = 6.dp))
                            Text(stringResource(R.string.read_reply_all))
                        }
                        OutlinedButton(onClick = { onForward(msg) }) {
                            Icon(Icons.Filled.Forward, null, Modifier.padding(end = 6.dp))
                            Text(stringResource(R.string.read_forward))
                        }
                    }
                    if (msg.htmlBody != null) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            OutlinedButton(onClick = { formatted = !formatted }) {
                                Text(
                                    stringResource(
                                        if (formatted) R.string.read_view_plain
                                        else R.string.read_view_formatted,
                                    ),
                                )
                            }
                            if (formatted && !loadImages) {
                                OutlinedButton(onClick = { loadImages = true }) {
                                    Text(stringResource(R.string.read_load_images))
                                }
                            }
                        }
                        if (formatted && !senderAutoLoad && !loadImages) {
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                                Text(
                                    stringResource(R.string.read_images_note),
                                    color = SentinelMuted,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(onClick = {
                                    viewModel.settingsStore.alwaysLoadImageSenders =
                                        ImagePolicy.remember(
                                            viewModel.settingsStore.alwaysLoadImageSenders,
                                            msg.from,
                                        )
                                    senderAutoLoad = true
                                    loadImages = true
                                }) {
                                    Text(stringResource(R.string.read_always_load_sender))
                                }
                            }
                        }
                        if (formatted && loadImages) {
                            Text(
                                stringResource(R.string.read_images_loaded),
                                color = SentinelMuted,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                            )
                        }
                    }
                    if (formatted && msg.htmlBody != null) {
                        FormattedMailView(
                            html = msg.htmlBody,
                            inlineParts = inlineParts,
                            loadImages = loadImages,
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                msg.bodyText,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(18.dp),
                            )
                        }
                    }
                    if (preview != null) {
                        Image(
                            bitmap = preview!!,
                            contentDescription = stringResource(R.string.attachment_preview),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                        )
                    }
                    if (msg.attachments.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                            Text(
                                stringResource(R.string.read_attachments),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            msg.attachments.forEach { att ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    val bytes = viewModel.downloadAttachment(
                                                        folder, uid, att.partIndex,
                                                    )
                                                    if (att.mimeType.startsWith("image/", true)) {
                                                        // Images preview in place
                                                        // (1.2.0) — no round trip
                                                        // through another app to
                                                        // see what you were sent.
                                                        val bitmap = decodeImageBitmap(bytes)
                                                        if (bitmap != null) {
                                                            preview = bitmap
                                                            return@launch
                                                        }
                                                    }
                                                    // Hand the bytes to the
                                                    // platform opener (Android:
                                                    // FileProvider + chooser;
                                                    // desktop: the OS default
                                                    // app for the file).
                                                    openAttachmentExternal(
                                                        att.fileName, att.mimeType, bytes,
                                                    )
                                                } catch (e: Exception) {
                                                    error = MailViewModel.friendlyError(e)
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Filled.AttachFile, null, Modifier.padding(end = 6.dp))
                                        Text("${att.fileName} (${MailText.formatBytes(att.sizeBytes)})")
                                    }
                                    IconButton(onClick = {
                                        pendingSave = att
                                        saveLauncher(att.fileName)
                                    }) {
                                        Icon(
                                            Icons.Filled.Save,
                                            contentDescription = stringResource(
                                                R.string.attachment_save,
                                            ),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    // ---- snooze dialog (1.2.0) ----
    if (snoozeOpen && message != null) {
        val msg = message!!
        AlertDialog(
            onDismissRequest = { snoozeOpen = false },
            title = { Text(stringResource(R.string.snooze_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.snooze_explainer),
                        color = SentinelMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    val snoozeChoices: List<Pair<SnoozeLogic.Choice, Int>> = listOf(
                        SnoozeLogic.Choice.LATER_TODAY to R.string.snooze_later_today,
                        SnoozeLogic.Choice.TOMORROW to R.string.snooze_tomorrow,
                        SnoozeLogic.Choice.NEXT_WEEK to R.string.snooze_next_week,
                    )
                    snoozeChoices.forEach { (choice, label) ->
                        val returnsAt = remember(choice) {
                            SnoozeLogic.returnTimeMs(choice, System.currentTimeMillis())
                        }
                        val whenText = remember(returnsAt) {
                            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                                .withZone(ZoneId.systemDefault())
                                .format(java.time.Instant.ofEpochMilli(returnsAt))
                        }
                        TextButton(onClick = {
                            snoozeOpen = false
                            val summary = MailSummary(
                                uid = uid,
                                subject = msg.subject,
                                from = msg.from,
                                to = msg.to,
                                dateText = msg.dateText,
                                unread = msg.unread,
                                snippet = "",
                                starred = msg.starred,
                                hasAttachments = msg.attachments.isNotEmpty(),
                                folderName = folder,
                                messageId = msg.messageId,
                                references = msg.references,
                            )
                            act { viewModel.snooze(folder, summary, choice) }
                        }) {
                            Text("${stringResource(label)} — $whenText")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { snoozeOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun HeaderLine(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            color = SentinelMuted,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
