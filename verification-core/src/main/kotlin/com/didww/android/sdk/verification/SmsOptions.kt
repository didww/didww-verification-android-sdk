package com.didww.android.sdk.verification

/**
 * Per-verification options for the SMS channel.
 *
 * Plain constructor with a default argument, no builder. The builder ceremony exists to let a
 * class gain a parameter without a binary break, which from 1.0.0 is a live concern: this class,
 * [Config], [Auth] and the channel classes all carry the same exposure through Kotlin's
 * synthetic default-argument constructor, so extending the primary constructor would need a
 * major version. A later option arrives as a secondary constructor instead, as [templateGroup] did.
 */
public class SmsOptions(
    /**
     * BCP-47 language tags, most preferred first, for the message template.
     *
     * The server canonicalises and validates these; an unusable tag comes back as
     * `languages_invalid` rather than being silently ignored. `null` lets the application's
     * configured default stand.
     */
    public val languages: List<String>? = null,
) {
    /**
     * The name of one of the application's template groups, used instead of its default
     * group. An unknown name is rejected as `template_group_not_found`
     * ([ApiErrorCode.TEMPLATE_GROUP_NOT_FOUND]). `null` lets the default stand.
     *
     * Whether the group's template was actually used comes back on [SmsInfo.templateGroupUsed].
     */
    public var templateGroup: String? = null
        private set

    /** Adds [templateGroup] without touching the primary constructor above — see its doc. */
    public constructor(
        languages: List<String>? = null,
        templateGroup: String?,
    ) : this(languages) {
        this.templateGroup = templateGroup
    }
}
