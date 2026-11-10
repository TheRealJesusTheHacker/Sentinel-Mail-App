package net.sentinelsafeguard.mailapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import net.sentinelsafeguard.mailapp.R
import net.sentinelsafeguard.mailapp.ui.theme.SentinelMuted

/**
 * Sign-in: a Sentinel Mail address and its password. Server
 * settings are preconfigured to the verified Sentinel Mail values
 * (mail.sentinelsafeguard.net, IMAP 993, submission 587) — Advanced
 * exists for other mailboxes on the same server, not because the
 * defaults need help.
 */
@Composable
fun SignInScreen(viewModel: MailViewModel) {
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var errorShakeTrigger by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var imapPort by remember { mutableStateOf("") }
    var smtpPort by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
            .errorShake(errorShakeTrigger),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Image(
            painter = painterResource(R.drawable.sentinel_logo),
            contentDescription = "Sentinel Safeguard",
            modifier = Modifier.size(140.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Sentinel Mail", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Your mail, straight from your own server. This app talks directly to Sentinel Mail — no middleman, no copy anywhere else.",
            color = SentinelMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = address,
            onValueChange = { address = it.trim() },
            label = { Text("Email address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) "Hide advanced server settings" else "Advanced server settings")
        }
        AnimatedVisibility(visible = showAdvanced) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Pre-set for Sentinel Mail. Change these only for another mailbox on a different server.",
                    color = SentinelMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("Server (default mail.sentinelsafeguard.net)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = imapPort,
                    onValueChange = { imapPort = it.filter { c -> c.isDigit() } },
                    label = { Text("IMAP port (default 993, TLS)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = smtpPort,
                    onValueChange = { smtpPort = it.filter { c -> c.isDigit() } },
                    label = { Text("SMTP submission port (default 587)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
            }
        }
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                busy = true
                error = null
                viewModel.signIn(
                    address = address,
                    password = password,
                    host = host.ifBlank { null },
                    imapPort = imapPort.toIntOrNull(),
                    smtpPort = smtpPort.toIntOrNull(),
                ) { err ->
                    busy = false
                    error = err
                    // M-13: a refused sign-in shakes once — the
                    // words above the button carry the reason.
                    if (err != null) errorShakeTrigger++
                }
            },
            enabled = !busy && address.contains("@") && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text("Sign in")
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Your password is stored only in this phone's secure storage and is only ever sent to the mail server, over an encrypted connection.",
            color = SentinelMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
