package com.didww.android.sdk.verification.testing

/**
 * Response bodies in the exact shape the API emits, for both a verification and an
 * error envelope.
 *
 * Transcribed from real responses, not invented. If the API changes shape these are the one
 * place to update — which is the entire reason they are shared across modules rather than
 * copied into each one.
 */
public object Fixtures {

    public const val ID: String = "0197f3a1-9f4b-7c2e-8a11-3b6d5e2c9f01"
    public const val DESTINATION: String = "+37112345678"
    public const val TEMPLATE: String = "Your DIDWW code is {{CODE}}. Do not share it."

    /**
     * The body the API composes for dispatch: the Retriever prefix, the rendered template, then
     * the app hash as the final token — separated by a single space, exactly as sent.
     *
     * One definition, so the extractor tests and the capability-gate test cannot drift apart or
     * from the wire. The returned `template` stays bare; only the delivered body is framed.
     */
    public fun deliveredBody(code: String, appHash: String, template: String = TEMPLATE): String =
        "<#> " + template.replace("{{CODE}}", code) + " " + appHash

    /** Seconds the API grants for on-device capture. A fixed budget, not a countdown. */
    public const val INTERCEPTION_TIMEOUT: Int = 300

    /**
     * The tag the API says it chose. `en-US` is its fallback, so it is what an unmatched
     * request comes back as — which is exactly the case a host has to be able to detect.
     */
    public const val LANGUAGE: String = "en-US"

    /** The digit length the API generated this verification's code at (4–8), server-owned. */
    public const val CODE_LENGTH: Int = 6

    /** A live verification awaiting input. `fee` is rendered as a string, as Alba does. */
    public fun pendingSms(
        id: String = ID,
        template: String? = TEMPLATE,
        expiresAt: String? = "2030-01-01T00:02:00Z",
        appHash: String? = null,
        interceptionTimeout: Int? = INTERCEPTION_TIMEOUT,
        language: String? = LANGUAGE,
        codeLength: Int? = CODE_LENGTH,
        fee: String = "\"0.0450\"",
    ): String = """
        {"data":{
          "id":"$id",
          "destination":"$DESTINATION",
          "delivery_method":"sms",
          "fee":$fee,
          "status":"pending",
          "error_code":null,
          "error_detail":null,
          ${expiresAt?.let { "\"expires_at\":\"$it\"," } ?: ""}
          "sms":{${template?.let { "\"template\":\"$it\"" } ?: "\"template\":null"}${
        language?.let { ",\"language\":\"$it\"" } ?: ""
    }${
        interceptionTimeout?.let { ",\"interception_timeout\":$it" } ?: ""
    }${
        codeLength?.let { ",\"code_length\":$it" } ?: ""
    }${
        appHash?.let { ",\"app_hash\":\"$it\"" } ?: ""
    }}
        }}
    """.trimIndent()

    /**
     * A live callout verification. The block carries `language` and `code_length` — that is
     * the whole of the API's callout block at this version.
     */
    public fun pendingCallout(
        id: String = ID,
        expiresAt: String? = "2030-01-01T00:02:00Z",
        language: String? = LANGUAGE,
        codeLength: Int? = CODE_LENGTH,
    ): String {
        // Joined rather than chained with a leading comma, since no field here is unconditional.
        val fields = listOfNotNull(
            language?.let { "\"language\":\"$it\"" },
            codeLength?.let { "\"code_length\":$it" },
        ).joinToString(",")
        return """
            {"data":{
              "id":"$id",
              "destination":"$DESTINATION",
              "delivery_method":"callout",
              "fee":"0.0450",
              "status":"pending",
              "error_code":null,
              "error_detail":null,
              ${expiresAt?.let { "\"expires_at\":\"$it\"," } ?: ""}
              "callout":{$fields}
            }}
        """.trimIndent()
    }

    public fun pending(method: String, id: String = ID, expiresAt: String? = "2030-01-01T00:02:00Z"): String = """
        {"data":{
          "id":"$id","destination":"$DESTINATION","delivery_method":"$method",
          "fee":"0.0450","status":"pending","error_code":null,"error_detail":null
          ${expiresAt?.let { ",\"expires_at\":\"$it\"" } ?: ""}
        }}
    """.trimIndent()

    public fun verified(id: String = ID, method: String = "sms"): String = """
        {"data":{
          "id":"$id","destination":"$DESTINATION","delivery_method":"$method",
          "fee":"0.0450","status":"verified","error_code":null,"error_detail":null,
          "expires_at":"2030-01-01T00:02:00Z"
        }}
    """.trimIndent()

    /**
     * 201 + `denied`. An application authenticated publicly with no `callback_url` gets
     * this on every single start, which is why it needs its own state.
     */
    public fun deniedMissingCallbackUrl(id: String = ID): String = """
        {"data":{
          "id":"$id","destination":"$DESTINATION","delivery_method":"sms",
          "fee":"0.0","status":"denied",
          "error_code":"denied_missing_callback_url",
          "error_detail":"application has no callback_url",
          "expires_at":"2030-01-01T00:02:00Z"
        }}
    """.trimIndent()

    public fun deniedByCallback(id: String = ID): String = """
        {"data":{
          "id":"$id","destination":"$DESTINATION","delivery_method":"sms",
          "fee":"0.0","status":"denied",
          "error_code":"denied_by_callback","error_detail":"your callback denied the request",
          "expires_at":"2030-01-01T00:02:00Z"
        }}
    """.trimIndent()

    public fun failed(id: String = ID, code: String = "dispatch_failed", detail: String = "failed to deliver"): String = """
        {"data":{
          "id":"$id","destination":"$DESTINATION","delivery_method":"sms",
          "fee":"0.0450","status":"failed","error_code":"$code","error_detail":"$detail",
          "expires_at":"2030-01-01T00:02:00Z"
        }}
    """.trimIndent()

    /** `{"errors":[{"code","detail"}]}` — the envelope for every non-2xx. */
    public fun errors(vararg codes: Pair<String, String>): String =
        """{"errors":[${codes.joinToString(",") { """{"code":"${it.first}","detail":"${it.second}"}""" }}]}"""

    public fun error(code: String, detail: String = "detail for $code"): String = errors(code to detail)

    /**
     * `429`, `destination_in_cooldown` — a start too soon after a non-denied one for the
     * same application and destination. The `Retry-After` header itself is not part of this
     * body; script it separately, e.g. via `tooManyRequests`.
     */
    public fun cooldown(detail: String = "please wait before starting another verification"): String =
        error("destination_in_cooldown", detail)
}
