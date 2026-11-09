package net.sentinelsafeguard.mailapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import net.sentinelsafeguard.mailapp.BuildConfig
import net.sentinelsafeguard.mailapp.notify.NewMailWorker
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
    val context = LocalContext.current
    val settings = viewModel.settingsStore
    val secure = viewModel.secureStore

    var interval by remember { mutableStateOf(settings.checkIntervalMinutes) }
    var lockEnabled by remember { mutableStateOf(settings.appLockEnabled) }
    var hasPin by remember { mutableStateOf(secure.hasPin) }
    var pinDialog by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
            Text("Account", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                viewModel.account?.address ?: "",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Connected directly to mail.sentinelsafeguard.net (IMAP 993, submission 587). " +
                    "Your password lives only in this phone's secure storage.",
                color = SentinelMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Divider()
            Text("New mail checks", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Sentinel Mail checks the server on a timer and lets you know when new mail " +
                    "arrives. It is periodic checking — mail is not pushed to the phone.",
                color = SentinelMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            val options = listOf(
                15 to "Every 15 minutes",
                30 to "Every 30 minutes",
                60 to "Every hour",
                0 to "Manual only — check when you open a folder",
            )
            options.forEach { (minutes, label) ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = interval == minutes,
                        onClick = {
                            interval = minutes
                            settings.checkIntervalMinutes = minutes
                            NewMailWorker.reschedule(context)
                        },
                    )
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Divider()
            Text("App lock", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Lock Sentinel Mail", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Ask for biometrics, your device unlock, or your app PIN when the " +
                            "app comes back after about five minutes away.",
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
                Text(if (hasPin) "Change app PIN" else "Set an app PIN")
            }
            if (hasPin) {
                TextButton(onClick = {
                    secure.clearPin()
                    hasPin = false
                    if (!lockEnabled) note = "PIN removed."
                }) { Text("Remove PIN") }
            }
            Divider()
            Button(
                onClick = { confirmSignOut = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sign out") }
            Text(
                "Sign-out removes your password and settings from this phone and stops the " +
                    "periodic checks. Your mail itself stays on the server, untouched.",
                color = SentinelMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (note != null) {
                Spacer(Modifier.height(10.dp))
                Text(note!!, color = SentinelMuted)
            }
            Divider()
            Text(
                "Sentinel Mail ${BuildConfig.VERSION_NAME}",
                color = SentinelMuted,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                "Sentinel Safeguard LLC — your mail, from your own server, with no one in between.",
                color = SentinelMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
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
                note = "PIN set. Only a scrambled verifier is stored — never the PIN itself."
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
            title = { Text("Sign out of Sentinel Mail?") },
            text = {
                Text("Your password and settings are removed from this phone. Your mail stays on the server.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    viewModel.signOut()
                }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Stay signed in") }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App PIN") },
        text = {
            Column {
                Text(
                    "At least 4 digits. The PIN is never stored — only a scrambled check of it.",
                    color = SentinelMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(12) },
                    label = { Text("New PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter { c -> c.isDigit() }.take(12) },
                    label = { Text("Repeat PIN") },
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
                    pin.length < 4 -> error = "Use at least 4 digits."
                    pin != confirm -> error = "The two entries don't match."
                    else -> onSave(pin)
                }
            }) { Text("Save PIN") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun Divider() {
    Spacer(Modifier.height(18.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    Spacer(Modifier.height(18.dp))
}
