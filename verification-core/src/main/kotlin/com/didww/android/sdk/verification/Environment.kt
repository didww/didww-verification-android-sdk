package com.didww.android.sdk.verification

/**
 * Which DIDWW verification backend to target.
 *
 * The SDK appends `/api/v1` itself, so [Custom] takes only a scheme and host (optionally
 * with a base path) — a proxy, a test server, or a backend you run yourself.
 */
public sealed interface Environment {

    public data object Production : Environment

    public data object Sandbox : Environment

    /** Any other origin. Trailing slashes are ignored. */
    public data class Custom(public val url: String) : Environment
}

internal val Environment.baseUrl: String
    get() = when (this) {
        Environment.Production -> "https://verification.didww.com"
        Environment.Sandbox -> "https://verification-sandbox.didww.com"
        is Environment.Custom -> url.trimEnd('/')
    }
