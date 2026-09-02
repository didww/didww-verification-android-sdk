package com.didww.android.sdk.verification.internal

import com.didww.android.sdk.verification.ApiErrorCode
import com.didww.android.sdk.verification.ApiErrorItem
import com.didww.android.sdk.verification.CalloutInfo
import com.didww.android.sdk.verification.CodeInterceptorFactory
import com.didww.android.sdk.verification.DeliveryMethod
import com.didww.android.sdk.verification.DidwwInternalApi
import com.didww.android.sdk.verification.FailureReason
import com.didww.android.sdk.verification.InterceptionContext
import com.didww.android.sdk.verification.SdkError
import com.didww.android.sdk.verification.SmsInfo
import com.didww.android.sdk.verification.Transport
import com.didww.android.sdk.verification.TransportException
import com.didww.android.sdk.verification.VerificationHandle
import com.didww.android.sdk.verification.VerificationState
import com.didww.android.sdk.verification.VerificationStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How a handle acquires the verification it drives.
 *
 * The two differ in their opening request and in how they address the row afterwards;
 * everything between — the deadline, the interception budget, the submission loop, the
 * terminal-state mapping — is one code path, deliberately.
 */
internal enum class Origin {

    /** `POST /verifications`, then report against the id it returned. */
    CREATE,

    /**
     * `GET /verifications/by_number/{digits}`, then report against the same destination.
     *
     * Addressed by number end to end. The lookup does return an id, but a handle that
     * reattached to a verification it did not start knows the destination and nothing else
     * — that is the whole point of the mode — and reporting by number is also the request
     * an iOS-parity bridge has to be able to make.
     */
    RESUME,
}

@OptIn(DidwwInternalApi::class)
internal class RealVerificationHandle(
    private val destination: String,
    private val method: DeliveryMethod,
    private val origin: Origin,
    private val requestChannelBlock: JsonObject?,
    private val interceptorFactory: CodeInterceptorFactory?,
    private val transport: Transport,
    private val requests: RequestFactory,
    private val clock: Clock,
) : VerificationHandle {

    private class Submission(val value: String, val automatic: Boolean)

    private sealed interface Next {
        class Value(val submission: Submission) : Next
        data object Superseded : Next
    }

    /**
     * UNLIMITED, and created with the handle rather than when collection starts.
     *
     * That is what makes `submit()` legal from t=0. It must not be CONFLATED:
     * automatic capture and a user typing both feed this sink, and conflation would drop
     * one of them silently — with no state emission to tell anybody it happened.
     */
    private val submissions = Channel<Submission>(Channel.UNLIMITED)

    /** CONFLATED: being superseded twice is the same event as being superseded once. */
    private val supersede = Channel<Unit>(Channel.CONFLATED)

    /**
     * Permanent, never reset on completion. Resetting it looks like the obvious way to
     * support retry and is not: a recomposed `LaunchedEffect` cancels the old collection
     * before starting the new one, so the two are sequential rather than concurrent, and
     * a latch that reset on completion would wave the second `POST` straight through.
     * Retry means a new handle.
     */
    private val collected = AtomicBoolean(false)

    override fun submit(value: String) {
        submissions.trySend(Submission(value, automatic = false))
    }

    fun markSuperseded() {
        supersede.trySend(Unit)
    }

    override val states: Flow<VerificationState> = channelFlow { runVerification() }

    private suspend fun ProducerScope<VerificationState>.runVerification() {
        if (!collected.compareAndSet(false, true)) {
            send(VerificationState.Failed(FailureReason.Sdk(SdkError.AlreadyRunning)))
            return
        }

        send(VerificationState.Starting)

        val opening = when (origin) {
            Origin.CREATE -> requests.create(destination, method, requestChannelBlock)
            Origin.RESUME -> requests.showByNumber(destination)
        }

        val current = when (val outcome = exchange(opening)) {
            is Exchange.Terminal -> {
                send(outcome.state)
                return
            }
            is Exchange.Ok -> outcome.payload
            // Neither opening request can be "retryable": there is no verification yet to
            // retry against, and a lookup that was rejected has nothing to resubmit.
            is Exchange.Retryable -> {
                send(VerificationState.Failed(FailureReason.Api(outcome.error)))
                return
            }
        }

        // A 201 does not mean the verification is live: a denial is reported as a
        // successfully created verification carrying `status: denied`. The same reading
        // answers the resume case, where the row found may already be finished.
        terminalStateFor(current)?.let {
            send(it)
            return
        }

        awaitAndSubmit(current)
    }

    private suspend fun ProducerScope<VerificationState>.awaitAndSubmit(created: VerificationPayload) {
        val deadline = Deadline.from(created.expiresAtEpochMillis, clock)

        // The server's answer wins; ours is the fallback for a response that omitted it.
        // On a create the two always agree. On a resume they need not: the caller named a
        // channel to pick an interceptor with, but the live verification was started
        // elsewhere and may be on another one — and the reported `delivery_method` has to
        // match the verification, not the guess.
        val effectiveMethod = created.deliveryMethod ?: method

        val interceptor = interceptorFactory?.create(
            InterceptionContext(
                deliveryMethod = effectiveMethod,
                template = created.template,
                responseChannelBlock = created.channelBlock,
                requestChannelBlock = requestChannelBlock,
            ),
        )
        // Bounded by the server's interception budget when it sends one, and by `expires_at`
        // either way — the loop below cancels this job on exit. Whichever comes first wins.
        // Running out only stops listening; it never ends the verification.
        val budgetMillis = created.interceptionTimeoutSeconds?.let { TimeUnit.SECONDS.toMillis(it.toLong()) }
        val interceptorJob = interceptor?.let { source ->
            launch {
                val capture: suspend () -> Unit = {
                    source.collect { submissions.trySend(Submission(it, automatic = true)) }
                }
                if (budgetMillis == null) capture() else withTimeoutOrNull(budgetMillis) { capture() }
            }
        }

        try {
            var payload = created
            var lastError: ApiErrorItem? = null

            while (true) {
                send(awaitingInput(payload, effectiveMethod, lastError))

                // Recomputed every iteration, so a submission that took ten seconds to
                // answer shortens the next wait rather than restarting it.
                val next = awaitNext(deadline)
                if (next == null) {
                    // Reached only between submissions, never with one in flight — the
                    // countdown is not consulted while awaiting a response.
                    send(VerificationState.Expired)
                    return
                }
                if (next is Next.Superseded) {
                    send(VerificationState.Failed(FailureReason.Sdk(SdkError.Superseded)))
                    return
                }

                val submission = (next as Next.Value).submission
                if (submission.automatic) send(VerificationState.Captured(submission.value))
                send(VerificationState.Submitting)

                when (val outcome = exchange(reportRequest(payload, effectiveMethod, submission.value))) {
                    is Exchange.Terminal -> {
                        send(outcome.state)
                        return
                    }
                    is Exchange.Retryable -> {
                        lastError = outcome.error
                    }
                    is Exchange.Ok -> {
                        payload = outcome.payload
                        terminalStateFor(payload)?.let {
                            send(it)
                            return
                        }
                        lastError = null
                    }
                }
            }
        } finally {
            interceptorJob?.cancel()
        }
    }

    /**
     * Addressed by id for a created verification and by destination for a resumed one —
     * see [Origin].
     */
    private fun reportRequest(
        payload: VerificationPayload,
        effectiveMethod: DeliveryMethod,
        value: String,
    ) = when (origin) {
        Origin.CREATE -> requests.report(payload.id, effectiveMethod, value)
        Origin.RESUME -> requests.reportByNumber(destination, effectiveMethod, value)
    }

    private fun awaitingInput(
        payload: VerificationPayload,
        // The response keys its channel block by the delivery method's own name, so a
        // block is only meaningful once we know which method this is.
        effectiveMethod: DeliveryMethod,
        lastError: ApiErrorItem?,
    ): VerificationState.AwaitingInput {
        return VerificationState.AwaitingInput(
            verificationId = payload.id,
            deliveryMethod = effectiveMethod,
            destination = payload.destination,
            fee = payload.fee,
            // Non-null exactly when this is an SMS verification, even if the block itself
            // was absent — `sms != null` is then a reliable channel discriminator rather
            // than a statement about which optional keys happened to arrive. `callout`
            // reads the same way, from the same block: the response carries at most one,
            // keyed by the method, so only the matching arm can be non-null.
            sms = if (effectiveMethod == DeliveryMethod.SMS) {
                SmsInfo(
                    template = payload.template,
                    language = payload.language,
                    interceptionTimeoutSeconds = payload.interceptionTimeoutSeconds,
                )
            } else {
                null
            },
            callout = if (effectiveMethod == DeliveryMethod.CALLOUT) {
                CalloutInfo(language = payload.language)
            } else {
                null
            },
            expiresAtEpochMillis = payload.expiresAtEpochMillis,
            lastError = lastError,
        )
    }

    private suspend fun awaitNext(deadline: Deadline?): Next? {
        val remaining = deadline?.remainingMillis()
        if (remaining != null && remaining <= 0L) return null
        return if (remaining == null) awaitEither() else withTimeoutOrNull(remaining) { awaitEither() }
    }

    private suspend fun awaitEither(): Next = select {
        submissions.onReceive { Next.Value(it) }
        supersede.onReceive { Next.Superseded }
    }

    private sealed interface Exchange {
        class Ok(val payload: VerificationPayload) : Exchange
        /** The server said no, but the verification is still live and can take another value. */
        class Retryable(val error: ApiErrorItem) : Exchange
        class Terminal(val state: VerificationState) : Exchange
    }

    private suspend fun exchange(request: com.didww.android.sdk.verification.HttpRequest): Exchange {
        val response = try {
            transport.execute(request)
        } catch (e: TransportException) {
            return Exchange.Terminal(
                VerificationState.Failed(
                    FailureReason.Sdk(SdkError.Transport(e.message ?: "request failed", e.cause)),
                ),
            )
        }

        if (response.statusCode !in SUCCESS_RANGE) {
            val errors = ResponseDecoder.errors(response.body)
            val first = errors.firstOrNull()
                ?: return Exchange.Terminal(
                    VerificationState.Failed(
                        FailureReason.Sdk(
                            SdkError.Transport("HTTP ${response.statusCode} with no error envelope"),
                        ),
                    ),
                )
            return if (first.known in RETRYABLE) Exchange.Retryable(first) else {
                Exchange.Terminal(terminalStateFor(first))
            }
        }

        return try {
            Exchange.Ok(ResponseDecoder.verification(response.body))
        } catch (e: DecodeException) {
            Exchange.Terminal(
                VerificationState.Failed(
                    FailureReason.Sdk(SdkError.Decoding(e.message ?: "undecodable response", e)),
                ),
            )
        }
    }

    private fun terminalStateFor(error: ApiErrorItem): VerificationState = when (error.known) {
        ApiErrorCode.DENIED_MISSING_CALLBACK_URL -> VerificationState.SetupError(error.code, error.detail)
        ApiErrorCode.DENIED_BY_CALLBACK,
        ApiErrorCode.DENIED_INVALID_CALLBACK_RESPONSE,
        -> VerificationState.Denied(error)
        ApiErrorCode.EXPIRED -> VerificationState.Expired
        else -> VerificationState.Failed(FailureReason.Api(error))
    }

    private fun terminalStateFor(payload: VerificationPayload): VerificationState? = when (payload.status) {
        VerificationStatus.Verified -> VerificationState.Verified(payload.id)
        VerificationStatus.Expired -> VerificationState.Expired
        VerificationStatus.Failed ->
            VerificationState.Failed(FailureReason.Api(payload.error ?: UNSPECIFIED_FAILURE))
        VerificationStatus.Denied -> payload.error?.let { terminalStateFor(it) }
            ?: VerificationState.Denied(null)
        VerificationStatus.Pending -> null
        // Non-terminal by design. A status this release does not know leaves the
        // state machine where it is and emits nothing, so a server that grows a sixth
        // status neither strands nor misinforms an older SDK. The silence is deliberate.
        is VerificationStatus.Other -> null
    }

    private companion object {
        private val SUCCESS_RANGE = 200..299

        /**
         * Slugs after which the verification is still alive and the user may try again.
         * Everything else — including `too_many_attempts` — is terminal.
         *
         * Note what is NOT here: any local attempt counter. Whether another attempt is
         * permitted is the server's decision and it communicates it by returning
         * `too_many_attempts`, which falls through to terminal.
         */
        private val RETRYABLE = setOf(
            ApiErrorCode.CODE_INVALID,
            ApiErrorCode.CODE_BLANK,
            ApiErrorCode.CODE_VALUE_PRESENT,
            ApiErrorCode.DELIVERY_METHOD_INVALID,
            ApiErrorCode.VALIDATION_FAILED,
            // The row exists but has not reached a reportable state yet. Keep waiting.
            ApiErrorCode.NOT_READY_TO_REPORT,
        )

        /**
         * `already_verified` is deliberately terminal-as-a-FAILURE rather than mapped to
         * `Verified`. The server's own wording is "verification is already verified;
         * provided value is invalid" — the row succeeded earlier, but *this* submission
         * was wrong. Reporting success here would let a host grant access to someone who
         * typed the wrong code.
         */
        private val UNSPECIFIED_FAILURE = ApiErrorItem.of("internal_error", null)
    }
}
