package com.didww.android.sdk.verification

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.didww.android.sdk.verification.internal.ActiveHandleRegistry
import com.didww.android.sdk.verification.internal.AndroidClock
import com.didww.android.sdk.verification.internal.Clock
import com.didww.android.sdk.verification.internal.HttpUrlTransport
import com.didww.android.sdk.verification.internal.Origin
import com.didww.android.sdk.verification.internal.RealVerificationHandle
import com.didww.android.sdk.verification.internal.RequestFactory
import com.didww.android.sdk.verification.internal.digitsOf
import kotlinx.serialization.json.JsonObject

/**
 * The shared machinery behind every channel class. Hosts never touch this — they use
 * `SmsVerification`, `CalloutVerification`, or `DidwwVerification`, each of which is a thin
 * wrapper over one of these.
 *
 * Sharing one instance across several channel wrappers also shares the supersede registry,
 * which is what makes `DidwwVerification.start(number, SMS)` supersede an in-flight
 * `DidwwVerification.start(number, CALLOUT)` for the same number — the server would do it
 * anyway; this is how the abandoned handle finds out.
 */
@DidwwInternalApi
public class VerificationEngine internal constructor(
    context: Context,
    private val auth: Auth,
    private val environment: Environment,
    private val config: Config,
    transport: Transport?,
    private val clock: Clock,
) {

    public constructor(
        context: Context,
        auth: Auth,
        environment: Environment = Environment.Production,
        config: Config = Config(),
        transport: Transport? = null,
    ) : this(context, auth, environment, config, transport, AndroidClock)

    /**
     * Only ever the application context. Holding the `Context` a caller passed would
     * leak an `Activity` for as long as the SDK object lived; taking `applicationContext`
     * up front means passing an `Activity` is harmless rather than a documented hazard.
     */
    private val applicationContext: Context = context.applicationContext

    private val transport: Transport = transport ?: HttpUrlTransport(config)
    private val requests = RequestFactory(environment.baseUrl, auth)
    private val registry = ActiveHandleRegistry()

    init {
        warnIfBasicAuthInReleaseBuild()
    }

    /**
     * Creates a handle. **Performs no I/O and returns immediately** — the `POST` happens
     * on first collection of [VerificationHandle.states].
     */
    public fun start(
        destination: String,
        method: DeliveryMethod,
        channelBlock: JsonObject?,
        interceptorFactory: CodeInterceptorFactory?,
    ): VerificationHandle = handle(destination, method, Origin.CREATE, channelBlock, interceptorFactory)

    /**
     * Reattaches to the verification the API currently holds for [destination], instead of
     * creating one. **Performs no I/O and returns immediately** — the lookup happens on
     * first collection of [VerificationHandle.states].
     *
     * @throws IllegalArgumentException if [destination] contains no digits at all. The
     *   by-number routes address a verification by the number itself, so an empty path
     *   segment is not a request the API can answer — it is a programming error, and it is
     *   rejected here rather than becoming a confusing `404` one round trip later. The
     *   sibling iOS SDK rejects the same input with `VerificationError.invalidNumber`.
     */
    public fun resume(
        destination: String,
        method: DeliveryMethod,
        channelBlock: JsonObject?,
        interceptorFactory: CodeInterceptorFactory?,
    ): VerificationHandle {
        require(digitsOf(destination).isNotEmpty()) {
            "\"$destination\" has no digits in it, so there is no by-number path to look it " +
                "up under. Pass the destination in any format you like; it is reduced to its " +
                "digits, but it has to have some."
        }
        return handle(destination, method, Origin.RESUME, channelBlock, interceptorFactory)
    }

    private fun handle(
        destination: String,
        method: DeliveryMethod,
        origin: Origin,
        channelBlock: JsonObject?,
        interceptorFactory: CodeInterceptorFactory?,
    ): VerificationHandle {
        val handle = RealVerificationHandle(
            destination = destination,
            method = method,
            origin = origin,
            requestChannelBlock = channelBlock,
            interceptorFactory = interceptorFactory,
            transport = transport,
            requests = requests,
            clock = clock,
        )
        // Before any request by construction, since neither entrypoint does I/O. Resuming
        // registers on the same terms as starting: two live collections driving one
        // server-side verification is the situation the registry exists to end, and it
        // does not matter which entrypoint each of them came from.
        registry.register(destination, handle)
        return handle
    }

    /** Exposed so a channel module can compute values that need the host's package. */
    public fun applicationContext(): Context = applicationContext

    /**
     * ### Why not `BuildConfig.DEBUG`
     *
     * A library's own `BuildConfig.DEBUG` is compiled when the *library* is built, and a
     * published AAR is built in release — so it is `false` in every host, debug or not,
     * and a warning gated on it would never fire for anyone. The host's own
     * [ApplicationInfo.FLAG_DEBUGGABLE] is the only value that answers the question
     * actually being asked. This module does not generate `BuildConfig` at all, so the
     * wrong mechanism is not merely discouraged, it is unavailable.
     */
    private fun warnIfBasicAuthInReleaseBuild() {
        if (auth !is Auth.Basic) return
        val hostIsDebuggable =
            (applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (hostIsDebuggable) return

        Log.w(
            LOG_TAG,
            "Auth.Basic in a non-debuggable build: the secret is recoverable from the APK. " +
                "Ship Auth.Public, or proxy verification through your own backend.",
        )
    }

    internal companion object {
        internal const val LOG_TAG: String = "DidwwVerification"
    }
}
