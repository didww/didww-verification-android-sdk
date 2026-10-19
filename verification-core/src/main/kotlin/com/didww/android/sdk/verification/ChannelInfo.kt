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
    /**
     * The digit length (4–8) the server generated this verification's code at, set per
     * application — always sent, in the same channel block as [language] and
     * [interceptionTimeoutSeconds] above. It is `Int?`, not `Int`, only because the
     * primary constructor above predates this field and has no value to give it. The
     * server always sends it; `null` only if a response lacks it, or for an instance
     * built through the primary (1.0.0) constructor.
     *
     * Set through the secondary constructor below, never the primary one above: extending
     * the primary constructor's parameter list is the binary break this class's doc warns
     * about, so a field added after 1.0.0 grows this way instead.
     */
    public var codeLength: Int? = null
        private set

    /**
     * The template group applied to this verification — the one named in
     * [SmsOptions.templateGroup], else the application's default — or `null` when none
     * applies or the group no longer exists (see [templateFallbackReason]).
     */
    public var templateGroup: String? = null
        private set

    /**
     * Whether the message was rendered from [templateGroup]'s template. Always sent;
     * `Boolean?` only for the reason given on [codeLength].
     */
    public var templateGroupUsed: Boolean? = null
        private set

    /**
     * Why the group's template was not used, as the raw wire value. `null` when it was used,
     * and also when no template group applies at all ([templateGroupUsed] is then `false`).
     * `group_missing` means the requested or default group no longer exists, and comes with
     * a `null` [templateGroup]. An open vocabulary: resolve it with
     * [TemplateFallbackReason.fromSlug], which returns `null` for a reason this release does
     * not know.
     */
    public var templateFallbackReason: String? = null
        private set

    /**
     * The autofill marker stored for this message, or `null` when none was. This SDK asks for
     * an `app_hash` marker; when it sends none, the application's SMS domain may apply instead.
     */
    public var autofill: SmsAutofill? = null
        private set

    /** Adds [codeLength] without touching the primary constructor above — see its doc. */
    public constructor(
        template: String? = null,
        language: String? = null,
        interceptionTimeoutSeconds: Int? = null,
        codeLength: Int? = null,
    ) : this(template, language, interceptionTimeoutSeconds) {
        this.codeLength = codeLength
    }

    /** Adds the template-group fields and [autofill], on the same terms as [codeLength]. */
    public constructor(
        template: String? = null,
        language: String? = null,
        interceptionTimeoutSeconds: Int? = null,
        codeLength: Int? = null,
        templateGroup: String? = null,
        templateGroupUsed: Boolean? = null,
        templateFallbackReason: String? = null,
        autofill: SmsAutofill? = null,
    ) : this(template, language, interceptionTimeoutSeconds, codeLength) {
        this.templateGroup = templateGroup
        this.templateGroupUsed = templateGroupUsed
        this.templateFallbackReason = templateFallbackReason
        this.autofill = autofill
    }

    override fun toString(): String =
        "SmsInfo(template=${template?.let { "\"$it\"" }}, language=$language, " +
            "interceptionTimeoutSeconds=$interceptionTimeoutSeconds, codeLength=$codeLength, " +
            "templateGroup=$templateGroup, templateGroupUsed=$templateGroupUsed, " +
            "templateFallbackReason=$templateFallbackReason, autofill=$autofill)"
}

/**
 * The SMS autofill marker the server stored for a verification: [type] `app_hash`, whose
 * [value] is the Android SMS Retriever hash, or `domain`, whose [value] is the domain the
 * message is bound to.
 */
public class SmsAutofill(
    public val type: String,
    public val value: String,
) {
    override fun toString(): String = "SmsAutofill(type=$type, value=$value)"
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
    /** See [SmsInfo.codeLength] — same field, same server-owned value, same growth pattern. */
    public var codeLength: Int? = null
        private set

    /** See [SmsInfo.templateGroup]; the group named in [CalloutOptions.templateGroup]. */
    public var templateGroup: String? = null
        private set

    /** See [SmsInfo.templateGroupUsed] — whether the announcement came from [templateGroup]. */
    public var templateGroupUsed: Boolean? = null
        private set

    /** See [SmsInfo.templateFallbackReason]. */
    public var templateFallbackReason: String? = null
        private set

    /** Adds [codeLength] without touching the primary constructor above — see its doc. */
    public constructor(
        language: String? = null,
        codeLength: Int? = null,
    ) : this(language) {
        this.codeLength = codeLength
    }

    /** Adds the template-group fields, on the same terms as [codeLength]. */
    public constructor(
        language: String? = null,
        codeLength: Int? = null,
        templateGroup: String? = null,
        templateGroupUsed: Boolean? = null,
        templateFallbackReason: String? = null,
    ) : this(language, codeLength) {
        this.templateGroup = templateGroup
        this.templateGroupUsed = templateGroupUsed
        this.templateFallbackReason = templateFallbackReason
    }

    override fun toString(): String =
        "CalloutInfo(language=$language, codeLength=$codeLength, templateGroup=$templateGroup, " +
            "templateGroupUsed=$templateGroupUsed, templateFallbackReason=$templateFallbackReason)"
}

/**
 * Why a verification did not use its template group's template, read from
 * [SmsInfo.templateFallbackReason] or [CalloutInfo.templateFallbackReason].
 *
 * Open, like [ApiErrorCode]: a reason this release does not know resolves to `null` while
 * the raw string survives on the info type.
 */
public enum class TemplateFallbackReason(public val slug: String) {
    LANGUAGE_MISSING("language_missing"),
    VERSION_PENDING("version_pending"),
    VERSION_REJECTED("version_rejected"),
    AUDIO_NOT_READY("audio_not_ready"),
    GROUP_MISSING("group_missing"),
    ;

    public companion object {
        /** `null` for any reason this release does not know. Never throws. */
        public fun fromSlug(slug: String?): TemplateFallbackReason? =
            entries.firstOrNull { it.slug == slug }
    }
}
