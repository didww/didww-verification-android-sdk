package com.didww.android.sdk.verification.internal

import android.net.Uri
import com.didww.android.sdk.verification.Auth
import com.didww.android.sdk.verification.DeliveryMethod
import com.didww.android.sdk.verification.DidwwInternalApi
import com.didww.android.sdk.verification.HttpRequest
import com.didww.android.sdk.verification.headerValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds every request this SDK makes.
 *
 * All five client operations the API exposes are bound. Note that `PUT` and `PATCH`
 * are *one* operation, not two: the API accepts either verb for the same action. The client
 * sends `PUT`; `PATCH` is an alias and gets no separate code path and no separate fixture.
 *
 * The two addressing schemes are encoded differently on purpose. An id is opaque, so it is
 * percent-encoded; a destination is normalised to digits by [digitsOf] instead, which leaves
 * nothing an encoder would have to escape. Encoding a formatted number rather than
 * normalising it is not the same thing — see [digitsOf].
 */
@OptIn(DidwwInternalApi::class)
internal class RequestFactory(
    private val baseUrl: String,
    private val auth: Auth,
) {

    fun create(destination: String, method: DeliveryMethod, channelBlock: JsonObject?): HttpRequest {
        val body = buildJsonObject {
            put(
                "data",
                buildJsonObject {
                    put("destination", destination)
                    put("delivery_method", method.wireValue)
                    // The per-channel block is keyed by the delivery method's own name.
                    // Only the block matching `delivery_method` is read server-side.
                    channelBlock?.let { put(method.wireValue, it) }
                },
            )
        }
        return post("$baseUrl$API_PREFIX/verifications", body)
    }

    fun show(id: String): HttpRequest = get("$baseUrl$API_PREFIX/verifications/${Uri.encode(id)}")

    fun showByNumber(number: String): HttpRequest =
        get("$baseUrl$API_PREFIX/verifications/by_number/${digitsOf(number)}")

    fun report(id: String, method: DeliveryMethod, value: String): HttpRequest =
        put("$baseUrl$API_PREFIX/verifications/${Uri.encode(id)}", reportBody(method, value))

    fun reportByNumber(number: String, method: DeliveryMethod, value: String): HttpRequest =
        put("$baseUrl$API_PREFIX/verifications/by_number/${digitsOf(number)}", reportBody(method, value))

    /**
     * Every channel reports a typed code under `code`, alongside the `delivery_method` the
     * report is against. The API validates that pairing rather than ignoring it: a report
     * naming a method the verification is not on is rejected, not silently accepted.
     */
    private fun reportBody(method: DeliveryMethod, value: String): JsonObject = buildJsonObject {
        put(
            "data",
            buildJsonObject {
                put("delivery_method", method.wireValue)
                put("code", value)
            },
        )
    }

    private fun get(url: String) = HttpRequest("GET", url, headers(json = false), null)

    private fun post(url: String, body: JsonObject) =
        HttpRequest("POST", url, headers(json = true), body.toString())

    private fun put(url: String, body: JsonObject) =
        HttpRequest("PUT", url, headers(json = true), body.toString())

    private fun headers(json: Boolean): Map<String, String> = buildMap {
        put("Authorization", auth.headerValue)
        put("Accept", CONTENT_TYPE_JSON)
        if (json) put("Content-Type", CONTENT_TYPE_JSON)
    }

    private companion object {
        private const val API_PREFIX = "/api/v1"
        private const val CONTENT_TYPE_JSON = "application/json"
    }
}
