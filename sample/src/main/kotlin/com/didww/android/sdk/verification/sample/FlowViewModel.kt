package com.didww.android.sdk.verification.sample

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.didww.android.sdk.verification.CalloutOptions
import com.didww.android.sdk.verification.DeliveryMethod
import com.didww.android.sdk.verification.Environment
import com.didww.android.sdk.verification.SmsOptions
import com.didww.android.sdk.verification.VerificationHandle
import com.didww.android.sdk.verification.VerificationState
import com.didww.android.sdk.verification.all.DidwwVerification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the verification for as long as the screen exists, across configuration changes.
 *
 * ### The one rule this file exists to enforce
 *
 * `VerificationHandle.states` is **cold** and permits **exactly one** collection. The POST
 * happens on first collection, and a second collector receives
 * `Failed(FailureReason.Sdk(SdkError.AlreadyRunning))` — the SDK refuses rather than
 * silently creating a second verification and double-billing the account.
 *
 * So the flow is collected here, once, in `viewModelScope`, and mirrored into a hot
 * `StateFlow` that composables read. **No composable may collect `handle.states`.** Doing so
 * would re-collect on every recomposition and every return from the background, and the
 * screen would appear to die on rotation — which reads as an SDK bug and is not one.
 *
 * ### Generations
 *
 * A resend is a *new* verification (there is no resend endpoint), and starting one makes the
 * SDK's own **in-process** registry supersede the previous handle. (The server supersedes
 * independently and invisibly; what surfaces here is the client-side signal.) The previous
 * collection is therefore deliberately **not** cancelled: cancelling it would stop us
 * observing the `Superseded` emission that proves the supersession happened. Every collection
 * carries a generation number for the log, while the visible state is driven only by whichever
 * handle is currently the live one.
 */
class FlowViewModel(application: Application) : AndroidViewModel(application) {

    data class LogLine(
        val at: String,
        val generation: Int,
        val text: String,
        val tag: String? = null,
    )

    private val _state = MutableStateFlow<VerificationState?>(null)
    val state: StateFlow<VerificationState?> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    /** Set by the debug action so a second collector's verdict is impossible to miss. */
    private val _secondCollectorVerdict = MutableStateFlow<String?>(null)
    val secondCollectorVerdict: StateFlow<String?> = _secondCollectorVerdict.asStateFlow()

    private val _started = MutableStateFlow(false)
    val started: StateFlow<Boolean> = _started.asStateFlow()

    private var handle: VerificationHandle? = null
    private var generation = 0

    /**
     * Exactly what makes one client different from another — keyed on the **normalised**
     * values the SDK actually receives, never on the raw text in the fields.
     *
     * Both halves of that matter:
     * - Not the whole `DemoSettings`: it also carries the destination and the language
     *   list, so an incidental edit would rebuild the client and break supersession.
     * - Not the raw strings either. `toEnvironment()` trims and drops a trailing slash, so
     *   `…:3000` and `…:3000/` are the same server; keying on the raw field would treat
     *   them as different, build a second engine with a second `ActiveHandleRegistry`, and
     *   reintroduce exactly the bug this cache exists to prevent — from a change as
     *   innocuous as typing a trailing slash.
     *
     * `Environment` compares by value (`Custom` is a data class, the other two are data
     * objects). `Auth` does not, so its parts are spelled out instead.
     */
    private data class ClientKey(
        val environment: Environment,
        val authChoice: AuthChoice,
        val appKey: String,
        val secret: String,
    )

    private var client: DidwwVerification? = null
    private var clientKey: ClientKey? = null

    /**
     * One client per configuration, reused across starts — NOT a fresh one per start.
     *
     * This matters, and it is easy to get wrong: the in-process supersede registry lives
     * on the engine instance (`VerificationEngine` holds its own `ActiveHandleRegistry`).
     * Build a new client for every start and the second one knows nothing about the first
     * handle, so resending never supersedes and the abandoned handle sits there waiting
     * for a code that can no longer be accepted.
     *
     * Changing environment or auth produces a different [ClientKey] and a correspondingly
     * new client, so switching either takes effect with no restart.
     */
    private fun clientFor(settings: DemoSettings): DidwwVerification {
        val key = ClientKey(
            environment = settings.toEnvironment(),
            authChoice = settings.authChoice,
            appKey = settings.normalizedAppKey(),
            secret = settings.secret,
        )
        val existing = client
        if (existing != null && clientKey == key) return existing
        // Application context, never an Activity. The SDK takes applicationContext itself,
        // so this is belt and braces — but a host should never have to think about it.
        return DidwwVerification(
            context = getApplication(),
            auth = settings.toAuth(),
            environment = settings.toEnvironment(),
        ).also {
            client = it
            clientKey = key
        }
    }

    fun start(settings: DemoSettings, destination: String, method: DeliveryMethod) {
        val client = clientFor(settings)

        // One language list from the UI, routed into the options belonging to the channel
        // actually being started. Options for the wrong channel are refused by `start` with
        // IllegalArgumentException, so the per-channel gating here is the contract, not a
        // nicety — and it is the reason the demo can offer a single language field.
        val languages = settings.toLanguages()
        val sms = languages?.takeIf { method == DeliveryMethod.SMS }?.let { SmsOptions(languages = it) }
        val callout = languages?.takeIf { method == DeliveryMethod.CALLOUT }?.let { CalloutOptions(languages = it) }

        val started = client.start(destination, method, sms = sms, callout = callout)
        handle = started
        generation += 1
        val gen = generation

        _state.value = null
        _secondCollectorVerdict.value = null
        _started.value = true
        val sent = when {
            sms != null -> ", sms=SmsOptions(languages=$languages)"
            callout != null -> ", callout=CalloutOptions(languages=$languages)"
            else -> ""
        }
        append(gen, "start(\"$destination\", $method$sent)", tag = "call")

        viewModelScope.launch {
            started.states.collect { emission ->
                append(gen, emission.describe().log)
                // Guarded on handle IDENTITY, not on the generation counter. `handle` is
                // null between reset() and the next start(), so during that gap nobody
                // wins and a still-running old collection cannot write to a screen the
                // user has left. The generation number is then purely a log label, which
                // is the only thing it is actually good for.
                if (started === handle) _state.value = emission
            }
            append(gen, "states flow completed", tag = "end")
        }
    }

    /** Legal at any time, including before `AwaitingInput` — the sink exists with the handle. */
    fun submit(value: String) {
        val current = handle ?: return
        append(generation, "submit(\"$value\")", tag = "call")
        current.submit(value)
    }

    /**
     * Deliberately collects the *current* handle a second time, to make the
     * single-collection contract visible instead of leaving it to be discovered by
     * accident. Writes only to the log and its own verdict field — it must not disturb the
     * real state machine.
     */
    fun collectASecondTime() {
        val current = handle ?: return
        val gen = generation
        viewModelScope.launch {
            current.states.collect { emission ->
                val line = emission.describe().log
                append(gen, line, tag = "2nd collector")
                _secondCollectorVerdict.value = line
            }
        }
    }

    fun reset() {
        // The in-flight collection is left alone on purpose: if it is still live it will
        // report its own ending, and the log keeps that record. Nothing is cancelled and
        // nothing is released — this only stops the ViewModel treating that handle as the
        // one driving the screen.
        handle = null
        _state.value = null
        _secondCollectorVerdict.value = null
        _started.value = false
        append(generation, "left the code screen; this handle no longer drives the UI", tag = "ui")
    }

    private fun append(generation: Int, text: String, tag: String? = null) {
        val line = LogLine(at = TIMESTAMP.format(Date()), generation = generation, text = text, tag = tag)
        _log.update { it + line }
    }

    private companion object {
        val TIMESTAMP: SimpleDateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}
