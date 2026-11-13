package net.sentinelsafeguard.mailapp.ui

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.sentinelsafeguard.mailapp.R
import net.sentinelsafeguard.mailapp.data.mail.AddressBook
import net.sentinelsafeguard.mailapp.data.mail.ComposeAttachment
import net.sentinelsafeguard.mailapp.data.mail.ComposeModel
import net.sentinelsafeguard.mailapp.data.mail.MailRules
import net.sentinelsafeguard.mailapp.data.mail.MailText
import net.sentinelsafeguard.mailapp.data.mail.SignatureText
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
    val scope = rememberCoroutineScope()
    val attachCapText = stringResource(R.string.compose_attach_cap)
    val attachReadErrorFmt = stringResource(R.string.compose_attach_read_error)
    val badAddressFmt = stringResource(R.string.compose_error_bad_address)
    val draftSavedText = stringResource(R.string.compose_draft_saved_long)
    val photoNameFmt = stringResource(R.string.compose_photo_name)
    val snackbar = remember { SnackbarHostState() }

    val fromFolder = when (val i = init) {
        is ComposeInit.New -> i.fromFolder
        is ComposeInit.Reply -> i.fromFolder
        is ComposeInit.Forward -> i.fromFolder
        is ComposeInit.FromDraft -> MailRules.draftsTarget()
    }
    val ownAddress = viewModel.account?.address ?: ""

    // The identity's signature (1.2.0) rides from the first
    // moment: a new message opens with it under the cursor, a
    // reply carries it above the quote, a reopened draft never
    // grows a second one. SignatureText owns those mechanics.
    val signature = viewModel.signatureFor(fromFolder)
    var model by remember {
        mutableStateOf(
            when (val i = init) {
                is ComposeInit.New -> ComposeModel(
                    body = SignatureText.forNew("", signature),
                )
                is ComposeInit.Reply -> {
                    val base = replyModel(i, ownAddress)
                    base.copy(body = SignatureText.forReply(base.body, signature))
                }
                is ComposeInit.Forward -> {
                    val base = ComposeModel(
                        subject = MailText.forwardSubject(i.original.subject),
                        body = MailText.forwardBody("", i.original),
                    )
                    base.copy(body = SignatureText.forReply(base.body, signature))
                }
                is ComposeInit.FromDraft -> i.model.copy(
                    body = SignatureText.forNew(i.model.body, signature),
                )
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
    // Undo-send countdown state (1.2.0).
    var countingDown by remember { mutableStateOf(false) }
    var countdownLeft by remember { mutableStateOf(0) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }
    val sendHaptic = rememberPrimaryHaptic()

    /** Nothing typed, nothing attached, not an existing draft:
     * there is nothing to discard, so the back arrow just leaves
     * (1.1.0 — 1.0.0 demanded a discard decision for an empty
     * compose, a dialog guarding nothing). An untouched
     * signature doesn't count as typing (1.2.0). */
    fun isPristine(): Boolean {
        val bodyWithoutSignature = model.body
            .replace(SignatureText.block(signature), "")
            .trim()
        return model.to.isBlank() && model.cc.isBlank() && model.bcc.isBlank() &&
            model.subject.isBlank() && bodyWithoutSignature.isBlank() &&
            model.attachments.isEmpty() && model.draftUid == null
    }

    // Attach files (platform seam): Android's document picker /
    // the desktop file dialog hand over name+mime+bytes; the cap
    // and the model wiring are shared, exactly as pre-port.
    val picker = rememberFilePicker(
        onFiles = { picked ->
            scope.launch {
                var total = model.attachments.sumOf { it.bytes.size }
                val added = mutableListOf<ComposeAttachment>()
                for (file in picked) {
                    if (total + file.bytes.size > MAX_ATTACHMENT_TOTAL) {
                        error = attachCapText
                        continue
                    }
                    added += ComposeAttachment(file.name, file.mimeType, file.bytes)
                    total += file.bytes.size
                }
                if (added.isNotEmpty()) {
                    model = model.copy(attachments = model.attachments + added)
                }
            }
        },
        onReadError = { message -> error = formatText(attachReadErrorFmt, message) },
    )

    // Camera attach (1.2.0) — Android only: the seam exists only
    // where the platform has a camera (platformHasCamera); on
    // desktop the row below is absent, not disabled.
    val camera = rememberCameraAttach { bytes ->
        scope.launch {
            try {
                val total = model.attachments.sumOf { it.bytes.size }
                if (total + bytes.size > MAX_ATTACHMENT_TOTAL) {
                    error = attachCapText
                    return@launch
                }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val name = formatText(photoNameFmt, stamp)
                model = model.copy(
                    attachments = model.attachments +
                        ComposeAttachment(name, "image/jpeg", bytes),
                )
            } catch (e: Exception) {
                error = MailViewModel.friendlyError(e)
            }
        }
    }

    val noRecipientText = stringResource(R.string.compose_error_no_recipient)
    fun validate(): String? {
        if (model.to.isBlank()) return noRecipientText
        val all = MailText.parseAddressList(model.to) +
            MailText.parseAddressList(model.cc) +
            MailText.parseAddressList(model.bcc)
        val bad = all.firstOrNull { !MailText.looksLikeAddress(it) }
        if (bad != null) {
            return formatText(badAddressFmt, bad)
        }
        return null
    }

    /** The real send — the M-08 morph path, unchanged from
     * 1.1.0. Undo-send (below) only delays ARRIVAL here. */
    fun doSend() {
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
    }

    /**
     * Send, honoring the undo window (1.2.0): with a window set,
     * the tap starts a countdown and NOTHING is transmitted —
     * Undo simply cancels the countdown and the compose stands
     * untouched. Only an expired countdown reaches [doSend].
     */
    fun startSend() {
        if (sending || sentDone || countingDown) return
        val problem = validate()
        if (problem != null) {
            error = problem
            return
        }
        val waitSeconds = viewModel.settingsStore.undoSendSeconds
        if (waitSeconds <= 0) {
            doSend()
            return
        }
        countingDown = true
        countdownLeft = waitSeconds
        countdownJob = scope.launch {
            var elapsed = 0L
            while (elapsed < waitSeconds * 1000L) {
                delay(200)
                elapsed += 200
                countdownLeft = UndoSendLogic.remainingSeconds(waitSeconds, elapsed)
            }
            countingDown = false
            countdownJob = null
            doSend()
        }
    }

    fun cancelSend() {
        countdownJob?.cancel()
        countdownJob = null
        countingDown = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (init) {
                                is ComposeInit.New -> R.string.compose_title_new
                                is ComposeInit.Reply -> R.string.compose_title_reply
                                is ComposeInit.Forward -> R.string.compose_title_forward
                                is ComposeInit.FromDraft -> R.string.compose_title_draft
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (isPristine()) onDone() else confirmDiscard = true }) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.compose_back_discard),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (saving || sending || countingDown) return@TextButton
                            saving = true
                            scope.launch {
                                try {
                                    val uid = viewModel.saveDraft(model)
                                    model = model.copy(draftUid = uid)
                                    snackbar.showSnackbar(draftSavedText)
                                } catch (e: Exception) {
                                    error = MailViewModel.friendlyError(e)
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        enabled = !sending && !countingDown,
                    ) {
                        Text(
                            stringResource(
                                if (saving) R.string.compose_saving
                                else R.string.compose_save_draft,
                            ),
                        )
                    }
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
                stringResource(R.string.compose_sending_as, identity),
                color = SentinelMuted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            AddressField(
                value = model.to,
                onValueChange = { model = model.copy(to = it) },
                label = stringResource(R.string.compose_to),
                correspondents = viewModel.correspondents,
            )
            if (!showCcBcc) {
                TextButton(onClick = { showCcBcc = true }) {
                    Text(stringResource(R.string.compose_add_cc_bcc))
                }
            } else {
                Spacer(Modifier.height(8.dp))
                AddressField(
                    value = model.cc,
                    onValueChange = { model = model.copy(cc = it) },
                    label = stringResource(R.string.compose_cc),
                    correspondents = viewModel.correspondents,
                )
                Spacer(Modifier.height(8.dp))
                AddressField(
                    value = model.bcc,
                    onValueChange = { model = model.copy(bcc = it) },
                    label = stringResource(R.string.compose_bcc),
                    correspondents = viewModel.correspondents,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model.subject,
                onValueChange = { model = model.copy(subject = it) },
                label = { Text(stringResource(R.string.compose_subject)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model.body,
                onValueChange = { model = model.copy(body = it) },
                label = { Text(stringResource(R.string.compose_body)) },
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
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(
                                R.string.compose_remove_attachment, att.fileName,
                            ),
                        )
                    }
                }
            }
            Row {
                OutlinedButton(onClick = { picker() }) {
                    Icon(Icons.Filled.AttachFile, null, Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.compose_attach_file))
                }
                if (platformHasCamera()) {
                    Spacer(Modifier.size(10.dp))
                    OutlinedButton(onClick = { camera() }) {
                        Icon(Icons.Filled.PhotoCamera, null, Modifier.padding(end = 6.dp))
                        Text(stringResource(R.string.compose_attach_camera))
                    }
                }
            }
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            // The send control, three acts (1.2.0):
            // 1. With an undo window set, the tap opens a
            //    COUNTDOWN row (family motion language: a live
            //    count and an Undo that actually undoes — nothing
            //    has been transmitted yet, so Undo is a cancel,
            //    never a "recall").
            // 2. The window expiring (or no window) runs the send
            //    itself: the M-08 morph from 1.1.0 — idle →
            //    sending → the M-06 drawn tick with the confirming
            //    haptic, a beat's rest, then leave. Sending is a
            //    trust moment: morph + drawn tick only, no
            //    confetti, per the owner's ruling.
            // 3. Failure returns the button to its label and the
            //    error line carries what happened.
            if (countingDown) {
                Row(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { /* counting; the Undo is the action */ },
                        enabled = false,
                        modifier = Modifier.weight(1f),
                    ) {
                        CircularProgressIndicator(
                            // The ring drains with the seconds left:
                            // full at the tap, empty when it fires.
                            progress = {
                                val total = viewModel.settingsStore.undoSendSeconds
                                    .coerceAtLeast(1)
                                countdownLeft.toFloat() / total.toFloat()
                            },
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp).padding(end = 8.dp),
                        )
                        Text(stringResource(R.string.compose_undo_window, countdownLeft))
                    }
                    Spacer(Modifier.size(10.dp))
                    OutlinedButton(onClick = { cancelSend() }) {
                        Text(stringResource(R.string.undo))
                    }
                }
            } else {
                Button(
                    onClick = { startSend() },
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
                            Text(stringResource(R.string.compose_sending))
                        }
                        else -> {
                            Icon(Icons.Filled.Send, null, Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.compose_send))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.compose_discard_title_long)) },
            text = {
                Text(
                    stringResource(
                        if (model.draftUid != null) {
                            R.string.compose_discard_body_draft
                        } else {
                            R.string.compose_discard_body_new
                        },
                    ),
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
                }) { Text(stringResource(R.string.compose_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.compose_keep_writing))
                }
            },
        )
    }
}

/**
 * A recipient field with the correspondent dropdown (1.2.0):
 * suggestions come from the account's own mail (AddressBook),
 * appear while the field is focused and the current token
 * matches someone, and a pick completes just that token.
 */
@Composable
private fun AddressField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    correspondents: List<AddressBook.Entry>,
) {
    var focused by remember { mutableStateOf(false) }
    val suggestions = if (focused) {
        AddressBook.suggest(correspondents, AddressBook.currentToken(value))
    } else {
        emptyList()
    }
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
        )
        DropdownMenu(
            expanded = suggestions.isNotEmpty(),
            onDismissRequest = { },
        ) {
            suggestions.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.display) },
                    onClick = { onValueChange(AddressBook.applyPick(value, entry)) },
                )
            }
        }
    }
}

private fun replyModel(init: ComposeInit.Reply, ownAddress: String): ComposeModel {
    val original = init.original
    val own = ownAddress.lowercase()
    fun withoutOwn(field: String): List<String> =
        MailText.parseAddressList(field)
            .filter { MailText.extractAddress(it).lowercase() != own }
    // Replying to your OWN sent message addresses its recipients,
    // not yourself (1.2.0 — 1.1.0's unconditional from-as-To made
    // a reply to a sent message a note to self with the actual
    // recipients nowhere on it). A message sent only to yourself
    // keeps the self address: it really was a note to self.
    val fromIsOwn = MailText.extractAddress(original.from).lowercase() == own
    val primary = if (fromIsOwn) {
        withoutOwn(original.to).ifEmpty { listOf(original.from) }
    } else {
        listOf(original.from)
    }
    val to = if (init.all) {
        (primary + withoutOwn(original.to))
            .distinctBy { MailText.extractAddress(it).lowercase() }
            .joinToString(", ")
    } else {
        primary.joinToString(", ")
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
