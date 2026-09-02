package com.didww.android.sdk.verification

/**
 * Per-verification options for the SMS channel.
 *
 * Plain constructor with a default argument, no builder. The builder ceremony exists to let a
 * class gain a parameter without a binary break, which from 1.0.0 is a live concern: this class,
 * [Config], [Auth] and the channel classes all carry the same exposure through Kotlin's
 * synthetic default-argument constructor, so each would need a major version to grow one.
 *
 * Kept as-is on the same reasoning as [Config]. A new per-channel option is far more likely to
 * arrive as a new options class for a new channel than as a parameter added to this one.
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
)
