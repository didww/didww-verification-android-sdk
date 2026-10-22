package com.didww.android.sdk.verification

/**
 * The error slugs the verification API can return.
 *
 * `app_hash_invalid` was added here ahead of the release that first returned it, on the
 * standing principle that knowing a slug before it can arrive costs nothing and saves an
 * SDK release when it does.
 *
 * ### Forty slugs, and why the list is not longer
 *
 * These are the ones reachable on *this* wire. DIDWW's error vocabulary is shared
 * with other product surfaces whose slugs can never appear on a verification response, so a
 * list assembled from the vocabulary as a whole would ship dead cases. The entries were
 * chosen against the verification API's documented responses. A slug counts when the API
 * can return it, even if this SDK's own requests never provoke it — `sms_domain_not_configured`
 * answers an autofill type this SDK does not send, and is listed all the same.
 *
 * ### There is deliberately no `Other` case
 *
 * An unrecognised slug leaves [ApiErrorItem.known] `null` while [ApiErrorItem.code] and
 * [ApiErrorItem.detail] survive verbatim. That matters more here than it looks: the API
 * derives its validation slugs mechanically from the attribute and the kind of failure, so
 * it can mint a slug this SDK has never seen simply by gaining a validation — and it has
 * done exactly that, more than once, within days. Switch on [ApiErrorItem.known] and fall
 * back to [ApiErrorItem.code].
 */
public enum class ApiErrorCode(public val slug: String) {

    // Derived validation slugs — "<attribute>_<type>" (422).
    DESTINATION_BLANK("destination_blank"),
    DESTINATION_INVALID("destination_invalid"),
    DELIVERY_METHOD_BLANK("delivery_method_blank"),
    DELIVERY_METHOD_INCLUSION("delivery_method_inclusion"),
    DELIVERY_METHOD_INVALID("delivery_method_invalid"),
    LANGUAGES_INVALID("languages_invalid"),
    AUTOFILL_INVALID("autofill_invalid"),
    APP_HASH_INVALID("app_hash_invalid"),
    CUSTOM_TOO_LONG("custom_too_long"),
    CODE_BLANK("code_blank"),
    CODE_VALUE_PRESENT("code_value_present"),

    // Returned when starting a verification (422).
    TEMPLATE_GROUP_NOT_FOUND("template_group_not_found"),
    SMS_DOMAIN_NOT_CONFIGURED("sms_domain_not_configured"),

    // The first now arrives only as an outcome code (status `failed`) and is kept for
    // compatibility. The rest are returned when submitting a code (422).
    DESTINATION_NOT_SUPPORTED_FOR_CHANNEL("destination_not_supported_for_channel"),
    CODE_INVALID("code_invalid"),
    ALREADY_VERIFIED("already_verified"),
    NOT_READY_TO_REPORT("not_ready_to_report"),

    // Request-level errors, each with its own HTTP status.
    PARAMETER_MISSING("parameter_missing"),
    NOT_FOUND("not_found"),
    UNAUTHORIZED("unauthorized"),
    BALANCE_INSUFFICIENT("balance_insufficient"),
    VALIDATION_FAILED("validation_failed"),
    INTERNAL_ERROR("internal_error"),

    // Rate and volume limits (429), each with a `Retry-After` header.
    DESTINATION_IN_COOLDOWN("destination_in_cooldown"),
    RATE_LIMIT_PER_IP("rate_limit_per_ip"),
    RATE_LIMIT_FAILED_AUTH("rate_limit_failed_auth"),
    RATE_LIMIT_PER_ACCOUNT("rate_limit_per_account"),
    RATE_LIMIT_PUBLIC_PER_ACCOUNT("rate_limit_public_per_account"),
    VOLUME_LIMIT_REACHED("volume_limit_reached"),

    // Outcome codes, surfaced as `error_code` on a finished verification.
    DISPATCH_FAILED("dispatch_failed"),
    EXPIRED("expired"),
    TOO_MANY_ATTEMPTS("too_many_attempts"),

    @Deprecated("No longer sent: these verifications now fail with DISPATCH_FAILED. Removed in the next major version.")
    STALE_DISPATCH("stale_dispatch"),
    APPLICATION_DELETED("application_deleted"),
    SUPERSEDED("superseded"),

    // Denial causes, surfaced as `error_code` on a denied verification.
    DENIED_MISSING_CALLBACK_URL("denied_missing_callback_url"),
    DENIED_BY_CALLBACK("denied_by_callback"),
    DENIED_INVALID_CALLBACK_RESPONSE("denied_invalid_callback_response"),
    APPLICATION_PAUSED("application_paused"),
    DESTINATION_COUNTRY_DISABLED("destination_country_disabled"),
    ;

    public companion object {
        /** `null` for any slug this release does not know. Never throws. */
        public fun fromSlug(slug: String?): ApiErrorCode? =
            entries.firstOrNull { it.slug == slug }
    }
}
