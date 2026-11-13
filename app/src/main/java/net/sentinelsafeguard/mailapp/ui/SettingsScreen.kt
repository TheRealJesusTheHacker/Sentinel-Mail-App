package net.sentinelsafeguard.mailapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import net.sentinelsafeguard.mailapp.R
import net.sentinelsafeguard.mailapp.data.mail.MailText
import net.sentinelsafeguard.mailapp.ui.theme.SentinelMuted

/**
 * Settings: the account, the periodic-check interval (honest
 * copy — this is timed checking, never "push"), the app lock and
 * its PIN, and sign-out. Sign-out wipes every secret on the
 * device and cancels the periodic check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MailViewModel,
    onBack: () -> Unit,
) {
    val settings = viewModel.settingsStore
    val secure = viewModel.secureStore
    val checkScheduler = rememberCheckScheduler()
    val signatureSavedText = stringResource(R.string.settings_signature_saved)
    val cacheClearedText = stringResource(R.string.settings_cache_cleared)
    val pinRemovedLockNote = stringResource(R.string.settings_pin_removed_lock_note)
    val pinRemovedNote = stringResource(R.string.settings_pin_removed_note)
    val pinSetNote = stringResource(R.string.settings_pin_set_note)

    var interval by remember { mutableStateOf(settings.checkIntervalMinutes) }
    var lockEnabled by remember { mutableStateOf(settings.appLockEnabled) }
    var hasPin by remember { mutableStateOf(secure.hasPin) }
    var pinDialog by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    // 1.2.0 additions: notifications, undo-send window,
    // signatures, the offline cache, and mailbox quota.
    var undoSeconds by remember { mutableStateOf(settings.undoSendSeconds) }
    var sigPersonal by remember { mutableStateOf(settings.signaturePersonal) }
    var sigShared by remember { mutableStateOf(settings.signatureShared) }
    var cacheBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        cacheBytes = viewModel.cache.totalSizeBytes()
        viewModel.loadQuota()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.settings_account), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                viewModel.account?.address ?: "",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.settings_account_line),
                color = SentinelMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            val quotaNow = viewModel.quota
            if (quotaNow != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (quotaNow.limitKb > 0) {
                        stringResource(
                            R.string.settings_storage_line,
                            MailText.formatBytes(quotaNow.usedKb * 1024),
                            MailText.formatBytes(quotaNow.limitKb * 1024),
                        )
                    } else {
                        stringResource(
                            R.string.settings_storage_unlimited,
                            MailText.formatBytes(quotaNow.usedKb * 1024),
                        )
                    },
                    color = SentinelMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Divider()
            PlatformNotificationsSection(viewModel)
            Divider()
            Text(stringResource(R.string.settings_checks), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.settings_checks_explainer),
                color = SentinelMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            val options = listOf(
                15 to stringResource(R.string.settings_checks_minutes, 15),
                30 to stringResource(R.string.settings_checks_minutes, 30),
                60 to stringResource(R.string.settings_checks_hour),
                0 to stringResource(R.string.settings_checks_manual_long),
            )
            options.forEach { (minutes, label) ->
                // The whole row selects (1.1.0 — 1.0.0 wired only
                // the radio circle, so tapping the label did
                // nothing, the classic dead-control shape).
                val select = {
                    interval = minutes
                    settings.checkIntervalMinutes = minutes
                    checkScheduler()
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = select),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = interval == minutes,
                        onClick = select,
                    )
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Divider()
            Text(stringResource(R.string.settings_undo_send), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.settings_undo_explainer),
                color = SentinelMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            val undoOptions = listOf(
                0 to stringResource(R.string.settings_undo_off),
                5 to stringResource(R.string.settings_undo_seconds, 5),
                10 to stringResource(R.string.settings_undo_seconds, 10),
            )
            undoOptions.forEach { (seconds, label) ->
                val select = {
                    undoSeconds = seconds
                    settings.undoSendSeconds = seconds
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = select),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = undoSeconds == seconds, onClick = select)
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Divider()
            Text(stringResource(R.string.settings_signatures), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sigPersonal,
                onValueChange = { sigPersonal = it },
                label = { Text(stringResource(R.string.settings_signature_personal)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sigShared,
                onValueChange = { sigShared = it },
                label = { Text(stringResource(R.string.settings_signature_shared)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.settings_signature_hint),
                color = SentinelMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            OutlinedButton(
                onClick = {
                    settings.signaturePersonal = sigPersonal
                    settings.signatureShared = sigShared
                    note = signatureSavedText
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(stringResource(R.string.save)) }
            Divider()
            Text(stringResource(R.string.settings_cache), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    R.string.settings_cache_size,
                    MailText.formatBytes(cacheBytes),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.settings_cache_explainer),
                color = SentinelMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = {
                    viewModel.cache.clearAccount()
                    cacheBytes = 0L
                    note = cacheClearedText
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(stringResource(R.string.settings_cache_clear)) }
            Divider()
            Text(stringResource(R.string.settings_lock), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_lock_line), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(lockExplainerRes()),
                        color = SentinelMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = lockEnabled,
                    onCheckedChange = { on ->
                        if (on && !secure.hasPin) {
                            // Enabling the lock means setting a PIN
                            // first, so there's always a way back in
                            // even where biometrics are unavailable.
                            pinDialog = true
                            pendingLockAfterPin = true
                        } else {
                            lockEnabled = on
                            settings.appLockEnabled = on
                        }
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { pinDialog = true }) {
                Text(
                    stringResource(
                        if (hasPin) R.string.settings_pin_change else R.string.settings_pin_set,
                    ),
                )
            }
            if (hasPin) {
                TextButton(onClick = {
                    secure.clearPin()
                    hasPin = false
                    // 1.1.0 fix: 1.0.0 could remove the PIN while
                    // the lock stayed ON — leaving a lock whose
                    // only fallback was the lock screen's no-PIN
                    // path, a weaker lock than the settings
                    // claimed. Removing the PIN now turns the lock
                    // off with it, and says so.
                    if (lockEnabled) {
                        lockEnabled = false
                        settings.appLockEnabled = false
                        note = pinRemovedLockNote
                    } else {
                        note = pinRemovedNote
                    }
                }) { Text(stringResource(R.string.settings_pin_remove)) }
            }
            Divider()
            Button(
                onClick = { confirmSignOut = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_sign_out)) }
            Text(
                stringResource(R.string.settings_signout_explainer),
                color = SentinelMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (note != null) {
                Spacer(Modifier.height(10.dp))
                Text(note!!, color = SentinelMuted)
            }
            Divider()
            // About (1.2.0): the real logo, the version the phone
            // is actually running, and the server identity — the
            // three things About exists to answer.
            Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Image(
                painter = painterResource(R.drawable.sentinel_logo),
                contentDescription = stringResource(R.string.logo_description),
                modifier = Modifier.size(96.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(
                    R.string.settings_about_version,
                    appVersionName(),
                    appVersionCode(),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.settings_about_tagline),
                color = SentinelMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.settings_about_server),
                color = SentinelMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.settings_about_line),
                color = SentinelMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            PlatformExtraSettings()
            Spacer(Modifier.height(28.dp))
        }
    }

    if (pinDialog) {
        PinDialog(
            onDismiss = { pinDialog = false; pendingLockAfterPin = false },
            onSave = { pin ->
                secure.setPin(pin)
                hasPin = true
                pinDialog = false
                note = pinSetNote
                if (pendingLockAfterPin) {
                    lockEnabled = true
                    settings.appLockEnabled = true
                    pendingLockAfterPin = false
                }
            },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.settings_signout_title)) },
            text = {
                Text(stringResource(R.string.settings_signout_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    viewModel.signOut()
                }) { Text(stringResource(R.string.settings_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) {
                    Text(stringResource(R.string.settings_signout_stay))
                }
            },
        )
    }
}

// Set when the lock toggle asked for a PIN first; consumed by the
// PIN dialog's save path above. (File-level because both the
// toggle handler and the dialog callback need it.)
private var pendingLockAfterPin = false

@Composable
private fun PinDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val pinShort = stringResource(R.string.settings_pin_short)
    val pinMismatch = stringResource(R.string.settings_pin_mismatch)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_pin_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_pin_explainer),
                    color = SentinelMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(12) },
                    label = { Text(stringResource(R.string.settings_pin_new)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter { c -> c.isDigit() }.take(12) },
                    label = { Text(stringResource(R.string.settings_pin_repeat)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pin.length < 4 -> error = pinShort
                    pin != confirm -> error = pinMismatch
                    else -> onSave(pin)
                }
            }) { Text(stringResource(R.string.settings_pin_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun Divider() {
    Spacer(Modifier.height(18.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    Spacer(Modifier.height(18.dp))
}
