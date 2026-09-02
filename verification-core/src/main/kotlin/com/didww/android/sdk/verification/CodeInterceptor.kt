package com.didww.android.sdk.verification

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Everything a channel needs in order to decide whether automatic capture is possible
 * for *this* verification.
 *
 * The decision is per-verification and taken from the response, never from a compiled-in
 * flag — see [CodeInterceptorFactory].
 */
@DidwwInternalApi
public class InterceptionContext(
    public val deliveryMethod: DeliveryMethod,
    /** The rendered template with `{{CODE}}` still in place, when the channel sends one. */
    public val template: String?,
    /** The channel block the server returned, e.g. `{"template": "...", "app_hash": "..."}`. */
    public val responseChannelBlock: JsonObject?,
    /** The channel block this client sent on create. */
    public val requestChannelBlock: JsonObject?,
)

/**
 * Builds the automatic-capture source for one verification, or declines.
 *
 * ### Returning `null` is a first-class outcome, not a stub
 *
 * A factory returns `null` when the server has not signalled that automatic capture will
 * work for this verification. Capability lives on the wire, so the SDK asks the response
 * rather than a constant it was compiled with.
 *
 * The consequence is the point. A backend that gains the capability turns capture on for every
 * SDK already installed on every handset — with no SDK release, no host recompile and no
 * coordination.
 *
 * ### It is called after the create response, never before
 *
 * The capability signal *is* part of the response, so there is nothing to consult
 * earlier. This differs from other verification SDKs, which arm their listener before the
 * HTTP call so an unusually fast message cannot be missed. That option is not available
 * here, and pretending otherwise would mean touching Play Services on every verification
 * including the ones that will never capture — which is exactly what the dormancy tests
 * assert never happens.
 *
 * The residual race is accepted: a message delivered before the response is parsed will
 * not be captured automatically. Manual entry still works, and is live from t=0.
 */
@DidwwInternalApi
public fun interface CodeInterceptorFactory {

    /**
     * @return a cold flow of captured values, or `null` to decline. The flow is cancelled when
     *   the verification ends, or when the server's interception budget runs out — whichever
     *   comes first — so all teardown belongs in the flow's own `awaitClose`. Running out of
     *   budget stops listening only; the verification stays live and manual entry is unaffected.
     */
    public fun create(context: InterceptionContext): Flow<String>?
}
