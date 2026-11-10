package net.sentinelsafeguard.mailapp.ui

import android.content.Intent
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.ReplyAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.launch
import net.sentinelsafeguard.mailapp.data.mail.HtmlSanitizer
import net.sentinelsafeguard.mailapp.data.mail.MailMessage
import net.sentinelsafeguard.mailapp.data.mail.MailRules
import net.sentinelsafeguard.mailapp.data.mail.MailText
import net.sentinelsafeguard.mailapp.ui.theme.SentinelMuted

/**
 * One open message. The default view is the family's audited
 * plain-text rendering — the text is the mail. "View formatted"
 * (offered only when an HTML part exists) renders that part
 * through [HtmlSanitizer] inside a locked-down WebView:
 * JavaScript off, no file or content access, remote images
 * blocked until the reader taps "Load remote images" for THIS
 * message only. Opening a message marks it read on the server.
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<MailMessage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var formatted by remember { mutableStateOf(false) }
    var loadImages by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loadNonce by remember { mutableStateOf(0) }

    LaunchedEffect(folder, uid, loadNonce) {
        try {
            val loaded = viewModel.loadMessage(folder, uid)
            message = loaded
            if (loaded.unread) {
                runCatching { viewModel.setSeen(folder, uid, true) }
                message = loaded.copy(unread = false)
                viewModel.loadFolders()
            }
        } catch (e: Exception) {
            error = MailViewModel.friendlyError(e)
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
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back to the list")
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
                                contentDescription = if (msg.starred) "Remove star" else "Star",
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Mark as unread") },
                                leadingIcon = { Icon(Icons.Filled.MarkEmailUnread, null) },
                                onClick = {
                                    menuOpen = false
                                    act { viewModel.setSeen(folder, uid, false) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Move to folder…") },
                                leadingIcon = { Icon(Icons.Filled.DriveFileMove, null) },
                                onClick = { menuOpen = false; moveOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Archive") },
                                leadingIcon = { Icon(Icons.Filled.Archive, null) },
                                onClick = {
                                    menuOpen = false
                                    act { viewModel.archive(folder, uid) }
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (MailRules.deleteIsPermanent(folder)) {
                                            "Delete permanently"
                                        } else {
                                            "Move to Trash"
                                        },
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
                                    text = { Text("Folders have not loaded yet") },
                                    enabled = false,
                                    onClick = {},
                                )
                                DropdownMenuItem(
                                    text = { Text("Reload folders") },
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
                        Button(onClick = { error = null; loadNonce++ }) { Text("Try again") }
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
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        Text(msg.subject, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(10.dp))
                        HeaderLine("From", msg.from)
                        HeaderLine("To", msg.to)
                        if (msg.cc.isNotBlank()) HeaderLine("Cc", msg.cc)
                        HeaderLine("Date", msg.dateText)
                        if (error != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(error!!, color = MaterialTheme.colorScheme.error)
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
                            Text("Reply")
                        }
                        OutlinedButton(onClick = { onReply(msg, true) }) {
                            Icon(Icons.Filled.ReplyAll, null, Modifier.padding(end = 6.dp))
                            Text("Reply all")
                        }
                        OutlinedButton(onClick = { onForward(msg) }) {
                            Icon(Icons.Filled.Forward, null, Modifier.padding(end = 6.dp))
                            Text("Forward")
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
                                Text(if (formatted) "View plain text" else "View formatted")
                            }
                            if (formatted) {
                                OutlinedButton(onClick = { loadImages = !loadImages }) {
                                    Text(if (loadImages) "Block remote images" else "Load remote images")
                                }
                            }
                        }
                        if (formatted && !loadImages) {
                            Text(
                                "Remote images are blocked. Nothing in this message can phone home until you load them.",
                                color = SentinelMuted,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                            )
                        }
                    }
                    if (formatted && msg.htmlBody != null) {
                        FormattedMailView(html = msg.htmlBody, loadImages = loadImages)
                    } else {
                        SelectionContainer {
                            Text(
                                msg.bodyText,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(18.dp),
                            )
                        }
                    }
                    if (msg.attachments.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                            Text(
                                "Attachments",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            msg.attachments.forEach { att ->
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                val bytes = viewModel.downloadAttachment(
                                                    folder, uid, att.partIndex,
                                                )
                                                val dir = File(context.cacheDir, "attachments")
                                                    .apply { mkdirs() }
                                                val safeName = att.fileName
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
                                                    setDataAndType(
                                                        uri,
                                                        att.mimeType.ifBlank { "*/*" },
                                                    )
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(
                                                    Intent.createChooser(intent, "Open attachment"),
                                                )
                                            } catch (e: Exception) {
                                                error = MailViewModel.friendlyError(e)
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Filled.AttachFile, null, Modifier.padding(end = 6.dp))
                                    Text("${att.fileName} (${MailText.formatBytes(att.sizeBytes)})")
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
}

@Composable
private fun HeaderLine(label: String, value: String) {
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
            fontWeight = if (label == "From") FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * The one WebView in the app, and it is a cage: JavaScript
 * disabled, no file or content access, no DOM storage, no
 * zoom-driven script surface, and network images blocked unless
 * the reader asked for them for this message. The HTML it shows
 * has already passed through [HtmlSanitizer]; nothing the sender
 * wrote reaches this view unexamined.
 */
@Composable
private fun FormattedMailView(html: String, loadImages: Boolean) {
    val document = remember(html) {
        val body = HtmlSanitizer.sanitize(html)
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


