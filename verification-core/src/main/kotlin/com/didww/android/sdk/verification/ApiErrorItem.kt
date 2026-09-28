package com.didww.android.sdk.verification

/**
 * One element of the API's error envelope, `{"errors":[{"code","detail"}]}`.
 *
 * [code] is the raw wire slug and always survives, even when this SDK release has never
 * heard of it. [known] is the same slug resolved to an enum, or `null`.
 *
 * [detail] is the server's static, human-readable fragment for that code. It is prose,
 * not contract: the server pairs a fixed code with a fixed detail precisely so a typed
 * client switches on [known]/[code] and ignores the wording. Do not parse it.
 */
public class ApiErrorItem(
    public val code: String,
    public val detail: String?,
    public val known: ApiErrorCode?,
) {
    /**
     * Whole seconds until the server will accept another attempt, read from the response's
     * `Retry-After` header — `null` when it sent none.
     *
     * Excluded from [equals]/[hashCode], on the same terms as [known]: enrichment, not identity.
     */
    public var retryAfterSeconds: Int? = null
        private set

    /** Adds [retryAfterSeconds] without touching the primary constructor above — see its doc. */
    public constructor(
        code: String,
        detail: String?,
        known: ApiErrorCode?,
        retryAfterSeconds: Int?,
    ) : this(code, detail, known) {
        this.retryAfterSeconds = retryAfterSeconds
    }

    override fun toString(): String =
        "ApiErrorItem(code=$code, detail=$detail, retryAfterSeconds=$retryAfterSeconds)"

    override fun equals(other: Any?): Boolean =
        other is ApiErrorItem && other.code == code && other.detail == detail

    override fun hashCode(): Int = 31 * code.hashCode() + (detail?.hashCode() ?: 0)

    internal companion object {
        internal fun of(code: String?, detail: String?): ApiErrorItem {
            val slug = code.orEmpty()
            return ApiErrorItem(code = slug, detail = detail, known = ApiErrorCode.fromSlug(slug))
        }
    }
}
