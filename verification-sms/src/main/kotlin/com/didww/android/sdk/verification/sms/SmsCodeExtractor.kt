package com.didww.android.sdk.verification.sms

/**
 * Recovers the code from a delivered SMS by turning the server's own template into a
 * pattern.
 *
 * The template arrives on the wire with `{{CODE}}` still in place, e.g.
 * `"Your DIDWW code is {{CODE}}. Do not share it."` Everything around the placeholder is
 * quoted literally and the placeholder becomes a digit group.
 */
internal object SmsCodeExtractor {

    private const val PLACEHOLDER = "{{CODE}}"

    /**
     * @return the captured code, or `null` when there is nothing to match against — a
     *   missing template, a template without the placeholder, or a body that does not
     *   contain the message. Every `null` falls through to manual entry, which is live
     *   the whole time anyway.
     */
    fun extract(template: String?, body: String): String? {
        if (template.isNullOrEmpty()) return null
        val placeholderAt = template.indexOf(PLACEHOLDER)
        if (placeholderAt < 0) return null

        val before = Regex.escape(template.take(placeholderAt))
        val after = Regex.escape(template.substring(placeholderAt + PLACEHOLDER.length))

        // `find`, deliberately NOT `matchEntire`. The delivered body is wider than the
        // template on both sides: the SMS Retriever protocol prefixes `<#>` and the app
        // hash is appended after it, and carriers add text of their own. An anchored
        // match fails on every real message.
        //
        // The capture is `(\d+)`, not `(\d{4,10})`. Code length is server-owned
        // (`CODE_LENGTH = 6` today) and is exactly the class of value that must never be
        // compiled into a client. `\d+` asserts only a character class, which is true by
        // construction. It is also narrower than the greedy `(.+)` other SDKs use, which
        // would swallow the appended app hash into the code.
        return Regex("$before(\\d+)$after").find(body)?.groupValues?.get(1)
    }
}
