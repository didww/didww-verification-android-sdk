package com.didww.android.sdk.verification.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.didww.android.sdk.verification.DeliveryMethod

@Composable
fun StartScreen(
    settings: DemoSettings,
    onChange: (DemoSettings) -> Unit,
    onStart: (String, DeliveryMethod) -> Unit,
    onOpenConfig: () -> Unit,
) {
    val method = settings.lastMethod()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Start a verification", style = MaterialTheme.typography.headlineSmall)

        Section("Destination") {
            OutlinedTextField(
                value = settings.lastDestination,
                onValueChange = { onChange(settings.copy(lastDestination = it)) },
                label = { Text("Phone number in E.164") },
                placeholder = { Text("+37112345678") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Section("Channel") {
            RadioGroup(
                options = DeliveryMethod.entries,
                selected = method,
                label = { it.humanLabel() },
                onSelect = { onChange(settings.copy(methodName = it.name)) },
            )
            // SMS and callout take the same tags with the same meaning — the template the
            // message is rendered from, and the recording the call plays. A channel that
            // announces nothing would have no language to pick.
            if (method.takesLanguages()) {
                OutlinedTextField(
                    value = settings.languages,
                    onValueChange = { onChange(settings.copy(languages = it)) },
                    label = { Text("Languages (optional, comma separated)") },
                    placeholder = { Text("pt-BR, en-US") },
                    supportingText = {
                        Text(
                            "BCP-47 tags, most preferred first. Matched exactly, so the region " +
                                "matters: \"pt\" is not \"pt-PT\". An unmatched tag falls back to " +
                                "en-US, and the tag actually used comes back on the next screen.",
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Section("What happens on an emulator") {
            Text(
                "No SMS and no call ever arrives here, and the API never returns the code. " +
                    "Whichever channel you pick, read the code out of the backend and type " +
                    "it on the next screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = { onStart(settings.lastDestination.trim(), method) },
            enabled = settings.lastDestination.isNotBlank() && settings.isStartable,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Send code")
        }

        OutlinedButton(onClick = onOpenConfig, modifier = Modifier.fillMaxWidth()) {
            Text("Configuration")
        }

        Field("Environment", settings.environmentSummary())
        Field("Auth", settings.authChoice.label)
    }
}

/** Named explicitly rather than defaulted, so a third channel has to answer the question. */
private fun DeliveryMethod.takesLanguages(): Boolean = when (this) {
    DeliveryMethod.SMS, DeliveryMethod.CALLOUT -> true
}

private fun DeliveryMethod.humanLabel(): String = when (this) {
    DeliveryMethod.SMS -> "SMS"
    DeliveryMethod.CALLOUT -> "Callout (the code is spoken)"
}
