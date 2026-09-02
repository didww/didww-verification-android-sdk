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
    override fun toString(): String = "ApiErrorItem(code=$code, detail=$detail)"

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
