package com.didww.android.sdk.verification.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.didww.android.sdk.verification.VerificationState
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun CodeScreen(
    state: VerificationState?,
    log: List<FlowViewModel.LogLine>,
    secondCollectorVerdict: String?,
    onSubmit: (String) -> Unit,
    onResend: () -> Unit,
    onCollectASecondTime: () -> Unit,
    onBack: () -> Unit,
) {
    // rememberSaveable, not remember: the verification survives rotation, and a demo whose
    // selling point is exactly that should not visibly throw away what the user just typed.
    var entry by rememberSaveable { mutableStateOf("") }
    val awaiting = state as? VerificationState.AwaitingInput

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val copy = state?.describe()
        Text(copy?.headline ?: "Starting…", style = MaterialTheme.typography.headlineSmall)
        Text(
            copy?.explanation ?: "Creating the verification.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (awaiting != null) {
            Section("Verification") {
                Field("id", awaiting.verificationId)
                Field("method", awaiting.deliveryMethod?.name)
                Field("destination", awaiting.destination)
                Field("fee", awaiting.fee)
                Field("template", awaiting.sms?.template)
                Countdown(awaiting.expiresAtEpochMillis)
                awaiting.lastError?.let {
                    Text(
                        "Last error: ${it.code}${it.detail?.let { d -> " — $d" }.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (!state.isTerminal()) {
            Section("Submit") {
                OutlinedTextField(
                    value = entry,
                    onValueChange = { entry = it },
                    // Not a numeric keyboard: the SDK compiles in no code length or
                    // alphabet, so whatever the server sent has to be typeable here.
                    label = { Text("Code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    // Disabled while a submission is in flight, and the field is cleared on
                    // send. The SDK's submission sink is an UNLIMITED channel, so a second
                    // tap does not coalesce — it queues a second submission and burns a
                    // second of the server's limited attempts on the same code.
                    onClick = {
                        onSubmit(entry)
                        entry = ""
                    },
                    enabled = entry.isNotBlank() && state != VerificationState.Submitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Verify")
                }
            }
        }

        Section("Actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Disabled while a create is in flight. Every tap is a real verification —
                // a real SMS or call, really billed — and nothing on screen changes fast
                // enough to stop an impatient second tap.
                OutlinedButton(
                    onClick = onResend,
                    enabled = state != VerificationState.Starting,
                ) { Text("Resend") }
                OutlinedButton(onClick = onBack) { Text("Start over") }
            }
            Text(
                "Resend is a NEW verification — the API has no resend endpoint. The old " +
                    "handle is superseded, and you will see it say so in the log below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            TextButton(onClick = onCollectASecondTime) { Text("Debug: collect states a second time") }
            Text(
                "Collects this handle's states flow again. The flow is cold and permits " +
                    "exactly one collector, so the SDK answers AlreadyRunning rather than " +
                    "issuing a second POST. This button exists so that contract is visible " +
                    "rather than discovered by accident.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            secondCollectorVerdict?.let {
                Text(
                    "Second collector received: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Section("Raw state log (${log.size})") {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // A plain `for`, not `forEach`. Written as `log.forEach { … }` this line
                    // produced, at minSdk 23:
                    //   Error: Call requires API level 24 [NewApi]: java.lang.Iterable#forEach
                    // Why lint resolves it to the Java default method here rather than to
                    // kotlin.collections' inline extension is not established — so this
                    // records the observation, not a theory. `for` avoids it either way.
                    for (line in log) {
                        Text(
                            "${line.at}  #${line.generation}  " +
                                (line.tag?.let { "[$it] " } ?: "") + line.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Countdown(expiresAtEpochMillis: Long?) {
    if (expiresAtEpochMillis == null) {
        Field("expires in", null)
        return
    }
    // Display only. The authoritative signal is the Expired emission from the SDK, which
    // is driven by the SDK's own monotonic deadline — this wall-clock subtraction is just
    // something for a human to watch, and may disagree by a second at the boundary.
    // Seeded with the real remaining time, not 0. produceState's body is an effect and runs
    // AFTER the first composition, so an initial value of 0 paints "expires in 0:00" for one
    // frame — on the one screen whose entire job is a live countdown.
    val remaining by produceState(
        initialValue = (expiresAtEpochMillis - System.currentTimeMillis()).coerceAtLeast(0L),
        expiresAtEpochMillis,
    ) {
        while (true) {
            value = (expiresAtEpochMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            delay(500)
        }
    }
    val seconds = remaining / 1000
    // Locale.US pinned, as the log timestamp is: the default locale renders non-ASCII digits
    // in some locales, and a countdown that changes alphabet by device is not a demo feature.
    Field("expires in", String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60))
}

private fun VerificationState?.isTerminal(): Boolean = when (this) {
    null,
    VerificationState.Starting,
    is VerificationState.AwaitingInput,
    is VerificationState.Captured,
    VerificationState.Submitting,
    -> false

    is VerificationState.Verified,
    is VerificationState.Failed,
    is VerificationState.Denied,
    is VerificationState.SetupError,
    VerificationState.Expired,
    -> true
}
