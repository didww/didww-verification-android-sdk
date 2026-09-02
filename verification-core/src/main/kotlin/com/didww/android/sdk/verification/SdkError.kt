package com.didww.android.sdk.verification

/**
 * A failure originating in this SDK rather than on the wire.
 *
 * [VerificationState.Failed] carries a union of this and [ApiErrorItem] so a caller can
 * tell "the server rejected the code" from "you collected the same handle twice" without
 * inventing a fake slug for the latter.
 */
public sealed interface SdkError {

    /**
     * A second collection was started on a handle that has already been collected.
     *
     * [VerificationHandle.states] is cold, so a second collection would issue a second
     * `POST /verifications` — and the server's unique-active-verification index would
     * supersede the first, silently billing the customer twice for one verification. A
     * screen rotation or a recomposed `LaunchedEffect` is enough to trigger it.
     *
     * To retry, start a new handle.
     */
    public data object AlreadyRunning : SdkError

    /**
     * Another handle from this same client started a verification for the same
     * destination, so this one can no longer win.
     *
     * Only ever detects **in-process** supersession. A verification superseded by a
     * different process or device is invisible until this handle's next request fails —
     * the API has no push channel and publishes no poll interval, so there is nothing to
     * observe. That is a limit of the wire, not an omission here.
     */
    public data object Superseded : SdkError

    /** The request never completed: DNS, connect, TLS, socket read, or a malformed URL. */
    public data class Transport(
        public val message: String,
        public val cause: Throwable? = null,
    ) : SdkError

    /** A 2xx response whose body could not be understood as a verification. */
    public data class Decoding(
        public val message: String,
        public val cause: Throwable? = null,
    ) : SdkError
}

/**
 * Why a verification ended in [VerificationState.Failed].
 *
 * Kept as a union rather than flattening everything into [ApiErrorItem]: a client-side
 * failure has no wire slug, and minting one would put a code on the wire vocabulary that
 * the server does not define.
 */
public sealed interface FailureReason {

    public data class Api(public val error: ApiErrorItem) : FailureReason

    public data class Sdk(public val error: SdkError) : FailureReason
}
