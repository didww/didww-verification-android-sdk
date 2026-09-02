package com.didww.android.sdk.verification.internal

import com.didww.android.sdk.verification.ApiErrorItem
import com.didww.android.sdk.verification.DeliveryMethod
import com.didww.android.sdk.verification.VerificationStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * A decoded verification, exactly as the API serialises one:
 *
 * ```json
 * { "data": { "id", "destination", "delivery_method", "fee", "status",
 *             "error_code", "error_detail", "expires_at",
 *             "sms": { "template": "...", "language": "de-DE", "interception_timeout": 120 },
 *             "callout": { "language": "pt-BR" } } }
 * ```
 *
 * At most one channel block is present, keyed by `delivery_method`, and a channel that has
 * no block of its own simply omits it.
 */
internal class VerificationPayload(
    val id: String,
    val destination: String?,
    val deliveryMethod: DeliveryMethod?,
    val fee: String?,
    val status: VerificationStatus,
    val error: ApiErrorItem?,
    val expiresAtEpochMillis: Long?,
    /** The channel block, keyed in the response by the delivery method's own name. */
    val channelBlock: JsonObject?,
) {
    val template: String? get() = channelBlock?.stringOrNull("template")

    /**
     * The content-language tag the server chose for this verification — the first requested
     * tag it could serve, or its `en-US` fallback.
     *
     * One accessor for both channels on purpose: `sms` and `callout` spell the key the same
     * way and mean the same thing by it, and the block this reads from is already the one
     * matching `delivery_method`. A channel that later reports the key differently gets its
     * own accessor; until then, two would only be two places to keep in step.
     */
    val language: String? get() = channelBlock?.stringOrNull("language")

    /**
     * Seconds, or `null` when absent or unreadable — never a throw on a field we only display.
     *
     * The wire type is a JSON **number**, not a string. [stringOrNull] reads the primitive's raw
     * content, so a quoted one reads identically — the same tolerance `fee` needs, and for the
     * same reason: a field read through an assumption about its JSON type is a field that
     * silently becomes `null` the day the assumption is wrong, and here that would drop the
     * server's interception budget on every response without anything failing.
     */
    val interceptionTimeoutSeconds: Int?
        get() = channelBlock?.stringOrNull("interception_timeout")?.toIntOrNull()
}

internal object ResponseDecoder {

    /**
     * `ignoreUnknownKeys` is load-bearing, not defensive. The response gains
     * fields over time, and an SDK that threw on an unrecognised one would break in the
     * field with no client change at all — every already-installed copy at once.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** @throws DecodeException when the body is not a verification at all. */
    fun verification(body: String): VerificationPayload {
        val data = runCatching { json.parseToJsonElement(body).jsonObject["data"]?.jsonObject }
            .getOrNull()
            ?: throw DecodeException("response has no `data` object")

        val id = data.stringOrNull("id")
            ?: throw DecodeException("verification has no `id`")

        val errorCode = data.stringOrNull("error_code")

        return VerificationPayload(
            id = id,
            destination = data.stringOrNull("destination"),
            deliveryMethod = DeliveryMethod.fromWire(data.stringOrNull("delivery_method")),
            // Kept as text, never parsed to a Double: `fee` is money, and Alba renders a
            // BigDecimal as a JSON string while a hand-written fixture may render a
            // number. Reading the primitive's raw content accepts both without ever
            // introducing binary-float rounding into a currency amount.
            fee = data.stringOrNull("fee"),
            status = VerificationStatus.fromWire(data.stringOrNull("status")),
            error = errorCode?.let { ApiErrorItem.of(it, data.stringOrNull("error_detail")) },
            expiresAtEpochMillis = Iso8601.parseEpochMillis(data.stringOrNull("expires_at")),
            channelBlock = (data[data.stringOrNull("delivery_method").orEmpty()] as? JsonObject),
        )
    }

    /**
     * The error envelope, `{"errors":[{"code","detail"}]}`.
     *
     * Never throws: a non-2xx with an unreadable body still has to produce something a
     * caller can act on, so an empty list is returned and the caller substitutes a
     * transport-level description.
     */
    fun errors(body: String): List<ApiErrorItem> = runCatching {
        json.parseToJsonElement(body).jsonObject["errors"]?.jsonArray.orEmpty().mapNotNull {
            val obj = it as? JsonObject ?: return@mapNotNull null
            ApiErrorItem.of(obj.stringOrNull("code"), obj.stringOrNull("detail"))
        }
    }.getOrDefault(emptyList())
}

internal class DecodeException(message: String) : Exception(message)

/** Reads a field as text whether the server sent a string, a number or a boolean. */
private fun JsonObject.stringOrNull(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.content.takeIf { it.isNotEmpty() && it != "null" }
}

private fun List<kotlinx.serialization.json.JsonElement>?.orEmpty() = this ?: emptyList()
