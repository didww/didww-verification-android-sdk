package com.didww.android.sdk.verification

/**
 * The `status` field of a verification, as the API reports it.
 *
 * Exactly five values reach a client. The API has in-progress states of its own that
 * collapse into [Pending] before it serialises a response, so they never appear on the wire
 * and there are deliberately no cases for them.
 *
 * ### Internal, deliberately
 *
 * This was public through early development and never appeared in a single public
 * signature: nothing the SDK hands back exposes a `VerificationStatus`, because
 * [VerificationState] is the observable model and this is only how the wire's `status` field
 * is read on the way to it. Public-and-unreachable is the worst of both — an integrator can
 * see it, can even construct one, and can do nothing with it, while the binary-compatibility
 * validator freezes it forever. Withdrawing it before the first release costs nothing; after
 * the first release it costs a major version.
 */
internal sealed interface VerificationStatus {

    data object Pending : VerificationStatus

    data object Verified : VerificationStatus

    data object Failed : VerificationStatus

    data object Expired : VerificationStatus

    data object Denied : VerificationStatus

    /**
     * A status this SDK release does not recognise. Unknown wire values degrade; they
     * never throw.
     *
     * **Non-terminal, and deliberately has no [VerificationState] case.** On receiving
     * one the state machine stays where it is and emits nothing, so a server that grows
     * a sixth status does not strand or mislead an older SDK. That silence is the
     * designed behaviour, not a gap in the mapping.
     */
    data class Other(val raw: String) : VerificationStatus

    companion object {
        fun fromWire(raw: String?): VerificationStatus = when (raw) {
            "pending" -> Pending
            "verified" -> Verified
            "failed" -> Failed
            "expired" -> Expired
            "denied" -> Denied
            else -> Other(raw.orEmpty())
        }
    }
}
