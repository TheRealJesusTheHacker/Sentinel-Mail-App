package net.sentinelsafeguard.mailapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import net.sentinelsafeguard.mailapp.ui.theme.SentinelMuted

/**
 * The app lock (family pattern): biometrics or the device
 * credential, with the app PIN as an always-available alternative
 * when one is set. The mailbox itself is never at risk from this
 * screen — the password stays in the Keystore store whatever
 * happens here.
 *
 * Desktop port (2026-11-13): the biometric half is the
 * [PlatformBiometricButton] seam — on desktop it renders nothing
 * and the lock is PIN only. The body copy names the platform's
 * own storage via [lockBodyRes].
 */
@Composable
fun LockScreen(viewModel: MailViewModel) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val hasPin = viewModel.secureStore.hasPin
    val pinMismatchText = stringResource(R.string.lock_pin_mismatch)

    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.sentinel_logo),
            contentDescription = stringResource(R.string.logo_description),
            modifier = Modifier.size(120.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(lockBodyRes()),
            color = SentinelMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        // Biometrics / device credential on Android; on desktop
        // this seam renders nothing — the lock there is PIN only.
        PlatformBiometricButton(viewModel, hasPin) { error = it }
        if (hasPin) {
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it; error = null },
                label = { Text(stringResource(R.string.lock_pin_hint)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = {
                    if (viewModel.secureStore.checkPin(pin)) {
                        viewModel.unlock()
                    } else {
                        error = pinMismatchText
                    }
                },
                enabled = pin.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.lock_unlock_pin)) }
        } else if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
    }
}
