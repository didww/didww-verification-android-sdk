package com.didww.android.sdk.verification

/**
 * Per-verification options for the callout channel.
 *
 * Plain constructor with a default argument, no builder — the same shape and the same
 * reasoning as [SmsOptions], so the two channels are configured the same way.
 *
 * ### Growing this class
 *
 * Adding a parameter to a Kotlin class is a **binary** break even when it has a default,
 * because the synthetic default-argument constructor's signature changes. So a second
 * callout option arrives as a secondary constructor, or in a major version — never by
 * extending the primary constructor in place. Nothing outside this file has to change
 * when it does: the request block is built from whatever this class carries, and the
 * server reads only the block matching `delivery_method`.
 */
public class CalloutOptions(
    /**
     * BCP-47 language tags, most preferred first, for the announcement the call plays.
     *
     * **The same tags and the same semantics as [SmsOptions.languages]**, so one language
     * list works for both channels — pass the user's preferences once and use them
     * whichever way the code goes out.
     *
     * Tags are matched **exactly**, so a region subtag is required: `"pt"` does not match
     * the `pt-PT` recording. A well-formed tag the server has no recording for is not an
     * error — it falls back to `en-US`, which is also what happens to a tag that has an
     * SMS template but no announcement audio. The tag actually used comes back as
     * [CalloutInfo.language], so a host that cares can compare the two and see the
     * fallback rather than guess at it.
     *
     * A malformed tag *is* rejected, as `languages_invalid`
     * ([ApiErrorCode.LANGUAGES_INVALID]) rather than being silently ignored. `null` lets
     * the server's default stand.
     */
    public val languages: List<String>? = null,
)
