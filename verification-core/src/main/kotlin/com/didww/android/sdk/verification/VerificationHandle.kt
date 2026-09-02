package com.didww.android.sdk.verification

import kotlinx.coroutines.flow.Flow

/**
 * One verification attempt. Returned by every `start(...)` entrypoint in this SDK.
 *
 * A handle is single-use. To retry, start a new one.
 */
public interface VerificationHandle {

    /**
     * The verification's lifecycle.
     *
     * **Cold. Started on first collection, cancelled when collection is cancelled.
     * Collect once; to retry, start a new handle.**
     *
     * Coldness is what makes teardown-on-cancel work: cancelling the collection cancels
     * the request and unregisters anything the channel registered, with no SDK-owned
     * scope to leak. The cost is that a second collection would mean a second
     * `POST /verifications`, so a second collection is refused — it emits
     * [VerificationState.Failed] with [SdkError.AlreadyRunning] instead, and the refusal
     * is permanent for the life of the handle.
     */
    public val states: Flow<VerificationState>

    /**
     * Offer a value — the code the user received, typed or captured.
     *
     * **Valid from the moment the handle exists**, including before [states] is collected
     * and before [VerificationState.AwaitingInput] arrives. The submission sink is
     * created with the handle and is unbounded, so an early value is buffered and
     * delivered once the verification is live rather than dropped. That matters because
     * `AwaitingInput` only arrives after a network round trip, and a user who has already
     * received the code by then should not have to wait for a UI to unlock.
     *
     * Never blocks and never throws.
     */
    public fun submit(value: String)
}
