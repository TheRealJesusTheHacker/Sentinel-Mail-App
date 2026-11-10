package net.sentinelsafeguard.mailapp.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.sentinelsafeguard.mailapp.data.mail.ComposeAttachment
import net.sentinelsafeguard.mailapp.data.mail.ComposeModel
import net.sentinelsafeguard.mailapp.data.mail.MailRules
import net.sentinelsafeguard.mailapp.data.mail.MailText
import net.sentinelsafeguard.mailapp.ui.theme.SentinelMuted

/** The server's own ceiling (EHLO SIZE 26214400) — attachments are
 * capped to it here so a send is never refused for size. */
private const val MAX_ATTACHMENT_TOTAL = 25 * 1024 * 1024

/**
 * Compose: new, reply, reply-all, forward, and draft editing.
 * Sends go out over submission and are filed in Sent by the
 * engine; drafts live in the IMAP Drafts folder, so a draft
 * started here is waiting in webmail too. Sending a draft
 * consumes it (the engine deletes the Drafts copy after a
 * successful send).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    viewModel: MailViewModel,
    init: ComposeInit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val fromFolder = when (val i = init) {
        is ComposeInit.New -> i.fromFolder
        is ComposeInit.Reply -> i.fromFolder
        is ComposeInit.Forward -> i.fromFolder
        is ComposeInit.FromDraft -> MailRules.draftsTarget()
    }
    val ownAddress = viewModel.account?.address ?: ""

    var model by remember {
        mutableStateOf(
            when (val i = init) {
                is ComposeInit.New -> ComposeModel()
                is ComposeInit.Reply -> replyModel(i, ownAddress)
                is ComposeInit.Forward -> ComposeModel(
                    subject = MailText.forwardSubject(i.original.subject),
                    body = MailText.forwardBody("", i.original),
                )
                is ComposeInit.FromDraft -> i.model
            },
        )
    }
    var showCcBcc by remember {
        mutableStateOf(model.cc.isNotBlank() || model.bcc.isNotBlank())
    }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sentDone by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val sendHaptic = rememberPrimaryHaptic()

    /** Nothing typed, nothing attached, not an existing draft:
     * there is nothing to discard, so the back arrow just leaves
     * (1.1.0 — 1.0.0 demanded a discard decision for an empty
     * compose, a dialog guarding nothing). */
    fun isPristine(): Boolean =
        model.to.isBlank() && model.cc.isBlank() && model.bcc.isBlank() &&
            model.subject.isBlank() && model.body.isBlank() &&
            model.attachments.isEmpty() && model.draftUid == null

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var total = model.attachments.sumOf { it.bytes.size }
            val added = mutableListOf<ComposeAttachment>()
            for (uri in uris) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() } ?: continue
                    if (total + bytes.size > MAX_ATTACHMENT_TOTAL) {
                        error = "Attachments are capped at 25 MB in total — the server's own limit. " +
                            "That file would put this message over."
                        continue
                    }
                    var name = "attachment"
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && cursor.moveToFirst()) {
                            cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { name = it }
                        }
                    }
                    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    added += ComposeAttachment(name, mime, bytes)
                    total += bytes.size
                } catch (e: Exception) {
                    error = "That file could not be read: ${e.message ?: "unknown error"}"
                }
            }
            if (added.isNotEmpty()) {
                model = model.copy(attachments = model.attachments + added)
            }
        }
    }

    fun validate(): String? {
        if (model.to.isBlank()) return "Add at least one recipient."
        val all = MailText.parseAddressList(model.to) +
            MailText.parseAddressList(model.cc) +
            MailText.parseAddressList(model.bcc)
        val bad = all.firstOrNull { !MailText.looksLikeAddress(it) }
        if (bad != null) return "\"$bad\" doesn't look like a complete email address."
        return null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (init) {
                            is ComposeInit.New -> "New message"
                            is ComposeInit.Reply -> "Reply"
                            is ComposeInit.Forward -> "Forward"
                            is ComposeInit.FromDraft -> "Draft"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (isPristine()) onDone() else confirmDiscard = true }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Discard and go back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (saving || sending) return@TextButton
                            saving = true
                            scope.launch {
                                try {
                                    val uid = viewModel.saveDraft(model)
                                    model = model.copy(draftUid = uid)
                                    snackbar.showSnackbar("Draft saved to your Drafts folder")
                                } catch (e: Exception) {
                                    error = MailViewModel.friendlyError(e)
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        enabled = !sending,
                    ) { Text(if (saving) "Saving…" else "Save draft") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            val identity = MailRules.identityForFolder(fromFolder, ownAddress)
            Text(
                "Sending as $identity",
                color = SentinelMuted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            OutlinedTextField(
                value = model.to,
                onValueChange = { model = model.copy(to = it) },
                label = { Text("To") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!showCcBcc) {
                TextButton(onClick = { showCcBcc = true }) { Text("Add Cc / Bcc") }
            } else {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model.cc,
                    onValueChange = { model = model.copy(cc = it) },
                    label = { Text("Cc") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model.bcc,
                    onValueChange = { model = model.copy(bcc = it) },
                    label = { Text("Bcc") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model.subject,
                onValueChange = { model = model.copy(subject = it) },
                label = { Text("Subject") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model.body,
                onValueChange = { model = model.copy(body = it) },
                label = { Text("Message") },
                minLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            model.attachments.forEach { att ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Icon(Icons.Filled.AttachFile, null, Modifier.padding(end = 6.dp))
                    Text(
                        "${att.fileName} (${MailText.formatBytes(att.bytes.size.toLong())})",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        model = model.copy(attachments = model.attachments - att)
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove ${att.fileName}")
                    }
                }
            }
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                Icon(Icons.Filled.AttachFile, null, Modifier.padding(end = 6.dp))
                Text("Attach a file")
            }
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            // M-08 send morph (1.1.0): one button carries idle →
            // sending → sent. On success it resolves into the
            // M-06 drawn tick with the confirming haptic, rests
            // a beat, and only then leaves — the owner SEES the
            // send land. Sending is a trust moment: morph + drawn
            // tick only, no confetti, per the owner's ruling.
            // On failure the button returns to its label and the
            // error line carries what happened, as before.
            Button(
                onClick = {
                    if (sending || sentDone) return@Button
                    val problem = validate()
                    if (problem != null) {
                        error = problem
                        return@Button
                    }
                    sending = true
                    error = null
                    scope.launch {
                        try {
                            viewModel.send(fromFolder, model)
                            sending = false
                            sentDone = true
                            sendHaptic()
                            delay(MotionLogic.MORPH_HOLD_MS)
                            onDone()
                        } catch (e: Exception) {
                            error = MailViewModel.friendlyError(e)
                            sending = false
                        }
                    }
                },
                enabled = !sending && !saving && !sentDone,
                modifier = Modifier.fillMaxWidth(),
            ) {
                when {
                    sentDone -> DrawnTick(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    sending -> {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text("Sending…")
                    }
                    else -> {
                        Icon(Icons.Filled.Send, null, Modifier.padding(end = 8.dp))
                        Text("Send")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this message?") },
            text = {
                Text(
                    if (model.draftUid != null) {
                        "This draft will be deleted from your Drafts folder."
                    } else {
                        "Nothing will be saved. Use Save draft first if you want to keep it."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    val draftUid = model.draftUid
                    if (draftUid != null) {
                        scope.launch {
                            runCatching { viewModel.deleteDraft(draftUid) }
                            onDone()
                        }
                    } else {
                        onDone()
                    }
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep writing") }
            },
        )
    }
}

private fun replyModel(init: ComposeInit.Reply, ownAddress: String): ComposeModel {
    val original = init.original
    val own = ownAddress.lowercase()
    fun withoutOwn(field: String): List<String> =
        MailText.parseAddressList(field)
            .filter { MailText.extractAddress(it).lowercase() != own }
    val to = if (init.all) {
        (listOf(original.from) + withoutOwn(original.to))
            .distinctBy { MailText.extractAddress(it).lowercase() }
            .joinToString(", ")
    } else {
        original.from
    }
    val cc = if (init.all) {
        withoutOwn(original.cc)
            .distinctBy { MailText.extractAddress(it).lowercase() }
            .joinToString(", ")
    } else {
        ""
    }
    val references = listOfNotNull(
        original.references?.takeIf { it.isNotBlank() },
        original.messageId?.takeIf { it.isNotBlank() },
    ).joinToString(" ").ifBlank { null }
    return ComposeModel(
        to = to,
        cc = cc,
        subject = MailText.replySubject(original.subject),
        body = MailText.quoteForReply("", original),
        inReplyTo = original.messageId,
        references = references,
    )
}
