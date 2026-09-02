package com.didww.android.sdk.verification.sample

import com.didww.android.sdk.verification.ApiErrorItem
import com.didww.android.sdk.verification.FailureReason
import com.didww.android.sdk.verification.SdkError
import com.didww.android.sdk.verification.VerificationState

/** Everything the UI says about one state. */
data class StateCopy(
    /** The headline a human reads. */
    val headline: String,
    /** What it means and what, if anything, to do about it. */
    val explanation: String,
    /** One line for the raw log — detailed, and never truncated. */
    val log: String,
)

/**
 * The single place any state is turned into words.
 *
 * All three strings for a state sit together, so working out what `SetupError` looks like is
 * one place to read rather than three. Every arm is named explicitly rather than falling back
 * to `toString()`, so an arm added to the SDK breaks this `when` at compile time instead of
 * silently degrading the log to a class name.
 */
fun VerificationState.describe(): StateCopy = when (this) {

    VerificationState.Starting -> StateCopy(
        headline = "Starting…",
        explanation = "Creating the verification. No code has been sent yet.",
        log = "Starting",
    )

    is VerificationState.AwaitingInput -> StateCopy(
        headline = "Waiting for the code",
        explanation = lastError
            ?.let { "Rejected: ${it.render()}. The verification is still live — try again." }
            // The chosen language is worth saying out loud: it is what the server picked,
            // not what was asked for, so it is how a fallback to en-US becomes visible.
            ?: ("Read the expected value out of the backend and type it in. The API never returns it." +
                (callout?.language?.let { " The call announces it in $it." } ?: "") +
                (sms?.language?.let { " The message is rendered in $it." } ?: "")),
        log = "AwaitingInput(id=$verificationId, method=$deliveryMethod, destination=$destination, " +
            "fee=$fee, expiresAt=$expiresAtEpochMillis, sms=${sms?.let { it.toString() } ?: "none"}, " +
            "callout=${callout?.let { it.toString() } ?: "none"}" +
            (lastError?.let { ", lastError=${it.render()}" } ?: "") + ")",
    )

    is VerificationState.Captured -> StateCopy(
        headline = "Captured automatically",
        explanation = "The SMS Retriever handed the code over without the user typing it. " +
            "Reached only when the backend echoes this app's hash — see the README.",
        log = "Captured(value=$value)",
    )

    VerificationState.Submitting -> StateCopy(
        headline = "Checking…",
        explanation = "Reporting the value to the server.",
        log = "Submitting",
    )

    is VerificationState.Verified -> StateCopy(
        headline = "Verified",
        explanation = "The server accepted the value. Verification $verificationId is done.",
        log = "Verified(id=$verificationId)",
    )

    is VerificationState.Failed -> StateCopy(
        headline = "Failed",
        explanation = when (val r = reason) {
            is FailureReason.Api ->
                "The server ended the verification: ${r.error.render()}. Nothing further can be submitted."
            is FailureReason.Sdk -> r.error.explain()
        },
        log = "Failed(${reason.render()})",
    )

    is VerificationState.Denied -> StateCopy(
        headline = "Denied",
        explanation = "Created but immediately refused — the application's request callback said " +
            "no, or answered with something unusable. ${error?.render().orEmpty()}",
        log = "Denied(${error?.render() ?: "no error payload"})",
    )

    is VerificationState.SetupError -> StateCopy(
        headline = "Application misconfigured",
        explanation = "This is a server-side configuration problem ($code): ${detail.orEmpty()}. " +
            "Nothing the user types can help. The usual cause is an application with no " +
            "callback_url being used with Application-key auth.",
        log = "SetupError(code=$code, detail=$detail)",
    )

    VerificationState.Expired -> StateCopy(
        headline = "Expired",
        explanation = "The verification passed its expiry. Start a new one — there is no resend endpoint.",
        log = "Expired",
    )
}

private fun SdkError.explain(): String = when (this) {
    SdkError.AlreadyRunning ->
        "This handle's states flow was collected more than once. The flow is cold and " +
            "performs the POST on first collection, so the SDK refuses a second collector " +
            "rather than silently creating a second verification. Retry means a NEW handle."
    SdkError.Superseded ->
        "Another verification started for the same destination, so this one was abandoned. " +
            "This is what a resend looks like from the old handle's side."
    is SdkError.Transport -> "The request never completed: $message. Check the base URL and that the backend is up."
    is SdkError.Decoding -> "The response could not be decoded: $message."
}

private fun FailureReason.render(): String = when (this) {
    is FailureReason.Api -> "api=${error.render()}"
    is FailureReason.Sdk -> "sdk=$error"
}

private fun ApiErrorItem.render(): String =
    code + (detail?.let { " — $it" } ?: "") + (if (known == null) " [slug unknown to this SDK]" else "")
