package com.didww.android.sdk.verification

/**
 * What the server reported about one channel, for the verification currently awaiting input.
 *
 * ### Why these are types rather than fields on [VerificationState.AwaitingInput]
 *
 * The API returns a block keyed by the delivery method's own name — `sms: { … }`,
 * `callout: { … }` — and each block has its own key set. Flattening one channel's keys onto
 * a state that both channels share means every key the API ever adds widens a surface the
 * other channel has no use for, and `template` (an SMS-only concept) already sat there.
 *
 * So each channel gets a type. A channel that gains keys later grows *its* type; a channel
 * that gains a block for the first time adds a type here and a property on `AwaitingInput`.
 * Neither touches the other — callout arrived exactly that way. This mirrors [SmsOptions]
 * and [CalloutOptions] on the request side, and the sibling iOS SDK's `ChannelOptions`.
 *
 * ### A limit worth knowing
 *
 * Adding a parameter to a Kotlin class is a **binary** break even when it has a default,
 * because the synthetic default-argument constructor's signature changes. So these types
 * grow by adding a secondary constructor one at a time, or in a major version — never by
 * extending the primary constructor in place. The same is true of [SmsOptions],
 * [CalloutOptions], [Config] and [Auth].
 */
public class SmsInfo(
    /**
     * The rendered message template with `{{CODE}}` still in place, or `null` when the
     * server sent no template for this verification.
     *
     * This is what the SMS channel matches a delivered message against when automatic
     * capture is active.
     */
    public val template: String? = null,

    /**
     * The BCP-47 tag the template was rendered in — the first tag from
     * [SmsOptions.languages] the server had a template for, or `en-US` when none matched
     * — and `null` when the server sent none.
     *
     * It is what the server **chose**, never an echo of what was asked for, so comparing
     * it against the requested list is how a host detects a fallback instead of guessing.
     */
    public val language: String? = null,

    /**
     * How long, in seconds, an on-device SMS listener is worth keeping armed — a fixed budget
     * granted at creation, not a countdown, and `null` when the server sent none.
     *
     * **It is not a deadline.** Reaching it stops automatic capture and nothing else: manual
     * entry stays live and `expiresAtEpochMillis` remains the only thing that ends a
     * verification. The SDK already stops its own listener at whichever of the two comes
     * first, so this is here to display, not to enforce.
     */
    public val interceptionTimeoutSeconds: Int? = null,
) {
    override fun toString(): String =
        "SmsInfo(template=${template?.let { "\"$it\"" }}, language=$language, " +
            "interceptionTimeoutSeconds=$interceptionTimeoutSeconds)"
}

/**
 * What the server reported about the callout channel — see [SmsInfo] for why this is its
 * own type rather than fields on [VerificationState.AwaitingInput], and for how it grows.
 */
public class CalloutInfo(
    /**
     * The BCP-47 tag the announcement is played in — the first tag from
     * [CalloutOptions.languages] the server had a recording for, or `en-US` when none
     * matched — and `null` when the server sent none.
     *
     * As with [SmsInfo.language], this is the server's choice rather than an echo, so a
     * host that asked for `de-DE` and reads `en-US` here knows the recording was missing
     * without having to model the catalogue itself.
     */
    public val language: String? = null,
) {
    override fun toString(): String = "CalloutInfo(language=$language)"
}
