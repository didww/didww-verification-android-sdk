package com.didww.android.sdk.verification.callout

import android.content.Context
import com.didww.android.sdk.verification.Auth
import com.didww.android.sdk.verification.CalloutOptions
import com.didww.android.sdk.verification.Config
import com.didww.android.sdk.verification.DeliveryMethod
import com.didww.android.sdk.verification.DidwwInternalApi
import com.didww.android.sdk.verification.Environment
import com.didww.android.sdk.verification.VerificationEngine
import com.didww.android.sdk.verification.VerificationHandle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Verification by automated call: the server calls the destination and reads out a code
 * the user types back, in the language [CalloutOptions.languages] asked for.
 *
 * ### Manual entry by nature
 *
 * A spoken code cannot be intercepted, so this channel registers nothing, observes nothing,
 * and declares no permission beyond the `INTERNET` `verification-core` already asks for.
 *
 * ### Ships in `verification-core`, not in a coordinate of its own
 *
 * It had one before the first release and does not now: measurement showed the split would
 * buy nothing, because callout adds no dependency and contributes no merged-manifest
 * element. The one split that measurement does justify is `verification-sms`, which is
 * where Play Services and everything `docs/manifest-cost.md` records enter. See
 * `settings.gradle.kts`.
 */
public class CalloutVerification @DidwwInternalApi public constructor(
    private val engine: VerificationEngine,
) {

    @OptIn(DidwwInternalApi::class)
    public constructor(
        context: Context,
        auth: Auth,
        environment: Environment = Environment.Production,
        config: Config = Config(),
    ) : this(VerificationEngine(context, auth, environment, config))

    /** Performs no I/O; the request is issued on first collection of the handle's states. */
    @OptIn(DidwwInternalApi::class)
    public fun start(destination: String, options: CalloutOptions? = null): VerificationHandle =
        engine.start(destination, DeliveryMethod.CALLOUT, channelBlock(options), null)

    /**
     * Reattaches to the callout verification the API currently holds for [destination],
     * rather than starting one. Performs no I/O; the lookup is issued on first collection
     * of the handle's states.
     *
     * ### No [CalloutOptions]
     *
     * Every option in that class is a create-time choice — `languages` selects the
     * recording the server plays — and a resume creates nothing. The language the call is
     * actually announcing in still arrives, on
     * [VerificationState.AwaitingInput.callout][com.didww.android.sdk.verification.VerificationState.AwaitingInput.callout],
     * because the server reports it on every response for this channel.
     *
     * @throws IllegalArgumentException if [destination] contains no digits at all.
     */
    @OptIn(DidwwInternalApi::class)
    public fun resume(destination: String): VerificationHandle =
        engine.resume(destination, DeliveryMethod.CALLOUT, null, null)

    /**
     * The `callout` block of the create request, or `null` when there is nothing to say.
     *
     * An empty block is deliberately not sent: it would be indistinguishable server-side
     * from one with no keys set, and sending it only makes the request larger. A new
     * callout option is one more line here and one more parameter on [CalloutOptions] —
     * nothing between this and the wire needs to know the difference, because
     * [com.didww.android.sdk.verification.VerificationEngine] and `RequestFactory` carry
     * the block through opaquely.
     */
    private fun channelBlock(options: CalloutOptions?): JsonObject? {
        if (options == null) return null
        val block = buildJsonObject {
            options.languages?.takeIf { it.isNotEmpty() }?.let { languages ->
                put("languages", JsonArray(languages.map(::JsonPrimitive)))
            }
        }
        return block.takeIf { it.isNotEmpty() }
    }
}
