package com.didww.android.sdk.verification

/**
 * Transport tuning.
 *
 * ### These are not verification policy
 *
 * The SDK compiles in no TTL, no attempt count, no code length and no interception
 * timeout — every one of those is server-owned and arrives on the wire.
 * The two values here are different in kind: they never appear on the wire, the server
 * cannot tune them, and they exist only because [java.net.HttpURLConnection] defaults
 * both to `0`, meaning *wait forever*. A hung socket with no timeout is a hang, not a
 * policy.
 *
 * They are the only two identifiers exempted from the failing-closed policy check that
 * rejects `TIMEOUT|EXPIR|ATTEMPT|RETRY|INTERVAL|MAX_` in a named constant declaration.
 *
 * Plain constructor with default arguments rather than a builder. From 1.0.0 that is a real
 * constraint and not a free choice: Kotlin's synthetic default-argument constructor means
 * gaining a parameter here is a binary break, so a third timeout would need a major version.
 * Accepted deliberately — these two values are the entire surface, and a builder would add
 * ceremony to every call site to protect against a change that should not happen.
 */
public class Config(
    public val connectTimeoutMillis: Int = 15_000,
    public val readTimeoutMillis: Int = 30_000,
)
