package com.didww.android.sdk.verification.all

import android.content.Context
import com.didww.android.sdk.verification.ApiErrorCode
import com.didww.android.sdk.verification.Auth
import com.didww.android.sdk.verification.CalloutOptions
import com.didww.android.sdk.verification.Config
import com.didww.android.sdk.verification.DeliveryMethod
import com.didww.android.sdk.verification.DidwwInternalApi
import com.didww.android.sdk.verification.Environment
import com.didww.android.sdk.verification.SmsOptions
import com.didww.android.sdk.verification.VerificationEngine
import com.didww.android.sdk.verification.VerificationHandle
import com.didww.android.sdk.verification.VerificationState
import com.didww.android.sdk.verification.callout.CalloutVerification
import com.didww.android.sdk.verification.sms.SmsVerification

/**
 * One entrypoint for every channel, for hosts that pick the method at runtime.
 *
 * This is a dispatch table, not a second implementation: [start] is a `when` over
 * [DeliveryMethod] that delegates to the very same channel classes a host could use
 * directly, sharing one engine and therefore one supersede registry. There is exactly one
 * code path with two names, and a parity test asserts that
 * `DidwwVerification.start(number, SMS)` and `SmsVerification.start(number)` produce
 * identical state sequences against the same scripted transport.
 */
public class DidwwVerification @DidwwInternalApi public constructor(
    engine: VerificationEngine,
) {

    @OptIn(DidwwInternalApi::class)
    public constructor(
        context: Context,
        auth: Auth,
        environment: Environment = Environment.Production,
        config: Config = Config(),
    ) : this(VerificationEngine(context, auth, environment, config))

    @OptIn(DidwwInternalApi::class)
    private val smsChannel = SmsVerification(engine)

    @OptIn(DidwwInternalApi::class)
    private val calloutChannel = CalloutVerification(engine)

    /**
     * Performs no I/O; the request is issued on first collection of the handle's states.
     *
     * ### One parameter per channel
     *
     * Options are named after the channel they belong to rather than sharing one slot, so a
     * channel that gains options later adds a parameter instead of overloading this one —
     * `callout` arrived exactly that way, and a channel that grows a *new option* does not
     * touch this signature at all. This matches the sibling iOS SDK's
     * `start(destination:method:sms:)`.
     *
     * `sms` and `callout` both take a `languages` list, and they take the same tags with
     * the same meaning, so one language preference from the host serves either channel:
     *
     * ```kotlin
     * val preferred = listOf("de-DE", "en-US")
     * when (method) {
     *     DeliveryMethod.SMS -> didww.start(number, method, sms = SmsOptions(preferred))
     *     DeliveryMethod.CALLOUT -> didww.start(number, method, callout = CalloutOptions(preferred))
     * }
     * ```
     *
     * @throws IllegalArgumentException if options are supplied for a channel other than
     *   [method]. The server would read only the block matching `delivery_method` and
     *   answer `201` with its defaults, so the request would silently not be the one the
     *   caller wrote — a mistake worth failing on at the call site rather than discovering
     *   from a message in the wrong language. The iOS SDK rejects the same mistake with
     *   `VerificationError.channelMismatch`; this SDK used to ignore it.
     */
    public fun start(
        destination: String,
        method: DeliveryMethod,
        sms: SmsOptions? = null,
        callout: CalloutOptions? = null,
    ): VerificationHandle {
        requireOwnChannel(method, DeliveryMethod.SMS, sms, "sms")
        requireOwnChannel(method, DeliveryMethod.CALLOUT, callout, "callout")
        return when (method) {
            DeliveryMethod.SMS -> smsChannel.start(destination, sms)
            DeliveryMethod.CALLOUT -> calloutChannel.start(destination, callout)
        }
    }

    /**
     * One line per options parameter, so adding a channel's options to [start] cannot
     * quietly leave the mismatch unchecked for that one channel.
     */
    private fun requireOwnChannel(
        method: DeliveryMethod,
        owner: DeliveryMethod,
        options: Any?,
        parameterName: String,
    ) {
        require(options == null || method == owner) {
            "$parameterName options were supplied for a $method verification. Options belong " +
                "to the channel they are named after; the server reads only the block matching " +
                "delivery_method, so these would be silently dropped."
        }
    }

    /**
     * Reattaches to the verification the API currently holds for [destination] instead of
     * creating one, and drives it exactly as [start] does — same states, same submissions,
     * same terminal outcomes.
     *
     * Performs no I/O; the lookup is issued on first collection of the handle's states.
     *
     * ### What it is for
     *
     * A verification outlives the process that started it. The handle does not: it is
     * in-memory, single-use, and gone after a low-memory kill or a cold start. Without this
     * the only way back is to start a *second* verification, which supersedes the first and
     * bills for it — while the code the user is holding, from the first one, stops working.
     * Persist the destination, resume against it, and the user finishes typing the code
     * they already have.
     *
     * ### What arrives
     *
     * The API keeps at most one unfinished verification per number and answers the by-number
     * routes with it, falling back to the most recent finished one. So a resumed handle
     * emits `AwaitingInput` when a verification is still live, a terminal state when the one
     * it found is already over, and `Failed` carrying [ApiErrorCode.NOT_FOUND] when the
     * number has no verification at all. Submissions go out against the destination rather
     * than an id.
     *
     * ### [method] is the channel to *listen* on, not an assertion
     *
     * It selects the same per-channel machinery [start] would — for SMS, the automatic-capture
     * gate. The verification itself was started elsewhere, so the server's `delivery_method`
     * is what the SDK reports against and what reaches
     * [VerificationState.AwaitingInput.deliveryMethod]; naming the wrong channel here costs
     * automatic capture, not correctness.
     *
     * @throws IllegalArgumentException if [destination] contains no digits at all. It is
     *   reduced to its digits for the by-number path, so any format will do — but an empty
     *   path segment is not a request the API can answer.
     */
    public fun resume(destination: String, method: DeliveryMethod): VerificationHandle =
        when (method) {
            DeliveryMethod.SMS -> smsChannel.resume(destination)
            DeliveryMethod.CALLOUT -> calloutChannel.resume(destination)
        }
}
