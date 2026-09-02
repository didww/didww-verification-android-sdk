package com.didww.android.sdk.verification

/**
 * Marks a declaration that exists only so `verification-sms`, `verification-all` and their
 * tests can reach into `verification-core`. It is **not** public API, carries no
 * compatibility promise, and may change or vanish in any release.
 *
 * ### Why this exists rather than `internal`
 *
 * Kotlin `internal` is scoped to a single *compilation module*. `verification-sms` is a
 * separate compilation module from `verification-core`, so an `internal` transport seam
 * in core is invisible to the SMS channel and to the SMS module's tests — the very code
 * that has to substitute a fake transport. There is no `internal`-shaped answer here.
 *
 * The usual objection to opt-in markers is aimed at a type read by exactly one module,
 * which genuinely does fit in `internal`. It does not transfer to a seam that crosses a
 * module boundary.
 *
 * The cost that objection warns about — a marker becoming frozen public surface an
 * integrator can pin to — is paid off by listing this in the binary-compatibility validator's
 * `nonPublicMarkers`, so nothing annotated with it enters the committed `.api` dump.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal to the DIDWW verification SDK. Not public API; no compatibility promise.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
)
public annotation class DidwwInternalApi
