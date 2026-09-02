package com.didww.android.sdk.verification.sms

import android.content.Context
import com.didww.android.sdk.verification.Auth
import com.didww.android.sdk.verification.Config
import com.didww.android.sdk.verification.DeliveryMethod
import com.didww.android.sdk.verification.DidwwInternalApi
import com.didww.android.sdk.verification.Environment
import com.didww.android.sdk.verification.InterceptionContext
import com.didww.android.sdk.verification.SmsOptions
import com.didww.android.sdk.verification.VerificationEngine
import com.didww.android.sdk.verification.VerificationHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Verification by SMS, usable on its own — a host that only ever sends SMS can depend on
 * `verification-sms` alone and never see the umbrella artifact.
 */
public class SmsVerification @DidwwInternalApi public constructor(
    private val engine: VerificationEngine,
) {

    @OptIn(DidwwInternalApi::class)
    public constructor(
        context: Context,
        auth: Auth,
        environment: Environment = Environment.Production,
        config: Config = Config(),
    ) : this(VerificationEngine(context, auth, environment, config))

    /**
     * Starts an SMS verification. Performs no I/O; the request is issued when
     * [VerificationHandle.states] is first collected.
     */
    @OptIn(DidwwInternalApi::class)
    public fun start(destination: String, options: SmsOptions? = null): VerificationHandle =
        engine.start(destination, DeliveryMethod.SMS, channelBlock(options), ::interceptorFor)

    /**
     * Reattaches to the SMS verification the API currently holds for [destination], rather
     * than starting one. Performs no I/O; the lookup is issued when
     * [VerificationHandle.states] is first collected.
     *
     * ### No [SmsOptions]
     *
     * Every option in that class is a create-time choice — `languages` selects the template
     * the server renders — and a resume creates nothing. The block built here is therefore
     * the app hash alone, and it is never sent: it exists only as the value the capability
     * gate compares against the hash the response echoes, which is what lets a resumed
     * verification keep automatic capture instead of silently dropping to manual entry.
     *
     * @throws IllegalArgumentException if [destination] contains no digits at all.
     */
    @OptIn(DidwwInternalApi::class)
    public fun resume(destination: String): VerificationHandle =
        engine.resume(destination, DeliveryMethod.SMS, channelBlock(null), ::interceptorFor)

    /**
     * The app hash goes out on **every** verification, whether or not the server will use
     * it. Sending it conditionally would mean the client deciding when the backend
     * capability exists; sending it unconditionally means every installed copy of this SDK
     * is already supplying it, whatever backend it happens to be talking to.
     *
     * Only a well-formed hash is sent — see [AppHash.wellFormed]. The key is validated
     * server-side, so a malformed value would fail the verification rather than the feature.
     */
    @OptIn(DidwwInternalApi::class)
    private fun channelBlock(options: SmsOptions?): JsonObject? {
        val block = buildJsonObject {
            options?.languages?.takeIf { it.isNotEmpty() }?.let { languages ->
                put("languages", JsonArray(languages.map(::JsonPrimitive)))
            }
            AppHash.compute(engine.applicationContext())?.let { put(APP_HASH, it) }
        }
        return block.takeIf { it.isNotEmpty() }
    }

    /**
     * The capability gate. Auto-capture is armed only when the response echoes back the
     * exact app hash this client sent.
     *
     * The echo has to be the **stored** hash, not a "we support this" flag. Rendering the
     * response and dispatching the message are separate concerns on the server side, so a
     * capability flag could truthfully report that the API understands `app_hash` while the
     * component that actually appends it does not — and the Retriever would be armed for a
     * message that can never reach it. Echoing the persisted value makes "echo present and
     * equal" imply "it will be appended", because it is the same datum rather than merely
     * the same version.
     *
     * A backend that does not echo gets `null` from here every time, and Play Services is
     * never touched — which the dormancy tests assert rather than assume. That path is what
     * let the capability switch on without an SDK release, and it is still the path an
     * older backend takes.
     */
    @OptIn(DidwwInternalApi::class)
    private fun interceptorFor(context: InterceptionContext): Flow<String>? {
        val sent = context.requestChannelBlock?.appHash() ?: return null
        val echoed = context.responseChannelBlock?.appHash() ?: return null
        if (sent != echoed) return null
        return SmsCodeInterceptor(engine.applicationContext(), context.template).codes()
    }

    private fun JsonObject.appHash(): String? =
        (this[APP_HASH] as? JsonPrimitive)?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }

    private companion object {
        private const val APP_HASH = "app_hash"
    }
}
