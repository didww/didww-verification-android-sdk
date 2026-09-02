package com.didww.android.sdk.verification

import android.util.Base64

/**
 * How this client authenticates to the verification API.
 *
 * The API ranks three auth modes, and each application carries a configured minimum:
 *
 * | Mode | Header | Here |
 * |---|---|---|
 * | `public` | `Application <key>` | [Public] |
 * | `basic` | `Basic base64(key:secret)` | [Basic] |
 * | `application` | `Application <key>:<signature>` plus `x-timestamp` | not implemented |
 *
 * The header token is `Application` for two of them, so these are named for the mode.
 *
 * ### The signed `application` mode is deliberately not implemented
 *
 * Worth stating because it is the strongest of the three, so its absence looks like an
 * oversight. Computing the signature requires the shared secret on the device, which is
 * exactly the extraction risk that makes [Basic] unsuitable for a shipped app — so it buys
 * nothing over [Public] while costing the same. Its validity window is also bounded against
 * the device's wall clock, and a wall clock that jumps is precisely the failure this SDK
 * defends against everywhere else by counting down on elapsed time instead.
 *
 * Raising an application's minimum above `public` therefore rejects every call this SDK
 * makes: [Basic] would ship a secret inside the APK, and `application` is unavailable.
 */
public sealed interface Auth {

    /**
     * `Authorization: Application <key>` — the API's `public` mode.
     *
     * The mode intended for a shipped mobile app: the key identifies the application but is
     * not a secret, so extracting it from an APK gains an attacker nothing beyond what they
     * could do by installing the app. Each verification is authorized by the server calling
     * the application's `callback_url` instead.
     */
    public class Public(public val applicationKey: String) : Auth

    /**
     * `Authorization: Basic base64(key:secret)`.
     *
     * **Server-to-server credentials.** The secret is recoverable from any APK that
     * contains it. Supported for local development and for hosts that proxy through
     * their own backend; using it in a shipped app logs a warning at runtime, decided by
     * the *host's* debuggable flag rather than this library's build type.
     */
    public class Basic(
        public val key: String,
        public val secret: String,
    ) : Auth
}

internal val Auth.headerValue: String
    get() = when (this) {
        is Auth.Public -> "Application $applicationKey"
        is Auth.Basic -> {
            val encoded = Base64.encodeToString(
                "$key:$secret".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
            "Basic $encoded"
        }
    }
