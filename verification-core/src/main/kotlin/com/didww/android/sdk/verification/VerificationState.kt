package com.didww.android.sdk.verification

/**
 * The observable lifecycle of one verification, emitted on [VerificationHandle.states].
 */
public sealed interface VerificationState {

    /**
     * The handle has been collected and its opening request is in flight — the create for
     * a handle from `start(...)`, the by-number lookup for one from `resume(...)`.
     */
    public data object Starting : VerificationState

    /**
     * The verification exists and the server is waiting for a value.
     *
     * [VerificationHandle.submit] is valid before this arrives — see its documentation.
     */
    public class AwaitingInput(
        public val verificationId: String,
        public val deliveryMethod: DeliveryMethod?,
        public val destination: String?,
        public val fee: String?,
        /**
         * What the server reported about the SMS channel, or `null` when this is not an
         * SMS verification.
         *
         * Non-null **exactly when** [deliveryMethod] is [DeliveryMethod.SMS], so it doubles
         * as the channel discriminator. See [SmsInfo].
         */
        public val sms: SmsInfo?,
        /**
         * What the server reported about the callout channel, or `null` when this is not a
         * callout verification.
         *
         * Non-null **exactly when** [deliveryMethod] is [DeliveryMethod.CALLOUT], on the
         * same terms as [sms] — including when the block itself was absent, so it stays a
         * reliable discriminator rather than a statement about which keys arrived. A channel
         * added later gains a sibling here the same way; see [CalloutInfo].
         */
        public val callout: CalloutInfo?,
        /** Server deadline as epoch milliseconds, or `null` when the server sent none. */
        public val expiresAtEpochMillis: Long?,
        /**
         * Why the previous submission was rejected, when this state was re-entered after
         * one — a wrong code, a value the server was not yet ready to accept.
         *
         * `null` on the first arrival and after an accepted submission. This is how a
         * host distinguishes "waiting for the first code" from "that code was wrong, try
         * again" without the SDK inventing a tenth state or, worse, counting attempts
         * locally: whether another attempt is allowed is the server's to decide, and it
         * says so by returning `too_many_attempts`, which is terminal.
         */
        public val lastError: ApiErrorItem? = null,
    ) : VerificationState {
        override fun toString(): String =
            "AwaitingInput(id=$verificationId, method=$deliveryMethod, lastError=$lastError)"
    }

    /**
     * A value was captured automatically rather than typed, and is about to be submitted.
     *
     * **SMS only, and only when the server enables it for that verification** — see the SMS
     * channel's app-hash echo. Callout has no capture path at all, so this never arrives on
     * it. Treat it as a normal arm of the `when`: it is emitted
     * immediately before [Submitting], and manual entry stays live either way.
     */
    public class Captured(public val value: String) : VerificationState

    /** A value is in flight to the server. */
    public data object Submitting : VerificationState

    /** Terminal. The server accepted the value. */
    public class Verified(public val verificationId: String) : VerificationState

    /** Terminal. */
    public class Failed(public val reason: FailureReason) : VerificationState {
        override fun toString(): String = "Failed($reason)"
    }

    /**
     * Terminal. The server declined to start or continue the verification — the
     * application's callback said no, or answered in a way the server could not read.
     */
    public class Denied(public val error: ApiErrorItem?) : VerificationState

    /**
     * Terminal, and **not** the caller's fault: the application itself is misconfigured,
     * so no input from the end user can rescue it.
     *
     * The case that forces this state to exist is `denied_missing_callback_url`. An
     * application authenticated publicly with no `callback_url` returns 201 plus a denial
     * on *every* start, forever. Surfacing that as an ordinary failure would send a host
     * looking at the phone number.
     */
    public class SetupError(
        public val code: String,
        public val detail: String?,
    ) : VerificationState {
        override fun toString(): String = "SetupError($code)"
    }

    /**
     * Terminal. The deadline passed with no accepted value.
     *
     * May be emitted locally from the countdown as a UX affordance rather than waiting
     * for the server to say so — but the server stays authoritative, a value submitted
     * late is still sent, and this is never emitted while a submission is in flight.
     */
    public data object Expired : VerificationState
}
