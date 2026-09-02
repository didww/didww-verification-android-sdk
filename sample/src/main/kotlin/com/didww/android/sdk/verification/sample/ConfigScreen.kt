package com.didww.android.sdk.verification.sample

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ConfigScreen(
    settings: DemoSettings,
    onChange: (DemoSettings) -> Unit,
    onSave: () -> Unit,
) {
    val context = LocalContext.current
    // Computed once per composition of this screen: it depends only on the installed APK.
    val appHash = remember { AppSignature.compute(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Configuration", style = MaterialTheme.typography.headlineSmall)

        Section("Environment") {
            RadioGroup(
                options = EnvironmentChoice.entries,
                selected = settings.environmentChoice,
                label = { it.label },
                onSelect = { onChange(settings.copy(environmentChoice = it)) },
            )
            if (settings.environmentChoice == EnvironmentChoice.CUSTOM) {
                OutlinedTextField(
                    value = settings.customUrl,
                    onValueChange = { onChange(settings.copy(customUrl = it)) },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "On an emulator, 10.0.2.2 is the alias for the host machine's " +
                        "localhost; typing \"localhost\" here points the app at the " +
                        "emulator itself, where nothing is listening. On a real device, " +
                        "run \"adb reverse tcp:3000 tcp:3000\" and use " +
                        "http://localhost:3000 instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section("Authorization") {
            RadioGroup(
                options = AuthChoice.entries,
                selected = settings.authChoice,
                label = { it.label },
                onSelect = { onChange(settings.copy(authChoice = it)) },
            )
            OutlinedTextField(
                value = settings.appKey,
                onValueChange = { onChange(settings.copy(appKey = it)) },
                label = { Text(if (settings.authChoice == AuthChoice.BASIC) "Key" else "Application key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // Shown only for Basic: the Application scheme sends no secret at all.
            if (settings.authChoice == AuthChoice.BASIC) {
                OutlinedTextField(
                    value = settings.secret,
                    onValueChange = { onChange(settings.copy(secret = it)) },
                    label = { Text("Secret") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    "Application-key auth requires the application to have a callback_url. " +
                        "Without one the backend fails closed and the verification is created " +
                        "already denied — which arrives here as SetupError, not as an HTTP error.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section("SMS Retriever app hash") {
            SelectionContainer {
                Text(
                    appHash ?: "unavailable — no signing certificate could be read",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                "The 11 characters an SMS must end with before Android will hand it to this " +
                    "app with no permission and no prompt. Computed from this APK's package " +
                    "name and SIGNING CERTIFICATE, so it changes whenever the signing key " +
                    "does — a build signed with a real release key produces a different value " +
                    "from this one. (This sample signs its release variant with the debug key, " +
                    "so both of its variants happen to agree.) Nothing sends this yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (appHash != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { copyToClipboard(context, appHash) }) { Text("Copy") }
                }
            }
        }

        Button(
            onClick = onSave,
            enabled = settings.isStartable,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save and continue")
        }
        if (!settings.isStartable) {
            Text(
                "Enter an application key" +
                    (if (settings.authChoice == AuthChoice.BASIC) " and a secret" else "") +
                    " to continue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun copyToClipboard(context: Context, value: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("app hash", value))
}
