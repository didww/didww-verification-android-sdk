package com.didww.android.sdk.verification

import com.didww.android.sdk.verification.internal.RequestFactory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(DidwwInternalApi::class)
@RunWith(RobolectricTestRunner::class)
class RequestFactoryTest {

    private val factory = RequestFactory("https://verification.example", Auth.Public("app-key"))

    /**
     * All five client operations the API exposes. Note this is five, not seven: the
     * routes file yields both PUT and PATCH for `update` and for `by_number`, but they
     * are the same operation and the client only ever sends PUT.
     */
    @Test
    fun `binds all five operations to the api v1 paths`() {
        assertEquals(
            "https://verification.example/api/v1/verifications",
            factory.create("+37112345678", DeliveryMethod.SMS, null).url,
        )
        assertEquals(
            "https://verification.example/api/v1/verifications/abc",
            factory.show("abc").url,
        )
        assertEquals(
            "https://verification.example/api/v1/verifications/by_number/37112345678",
            factory.showByNumber("+37112345678").url,
        )
        assertEquals(
            "https://verification.example/api/v1/verifications/abc",
            factory.report("abc", DeliveryMethod.SMS, "123456").url,
        )
        assertEquals(
            "https://verification.example/api/v1/verifications/by_number/37112345678",
            factory.reportByNumber("+37112345678", DeliveryMethod.SMS, "123456").url,
        )
    }

    @Test
    fun `the by-number segment is digits only, whatever the caller formatted`() {
        // Not percent-encoding: the canonical form is the digits themselves, and it is the
        // form the sibling iOS SDK puts on the wire for the same input. Encoding "+" to
        // "%2B" instead would leave the two platforms sending different bytes for one
        // destination.
        val expected = "https://verification.example/api/v1/verifications/by_number/37112345678"
        for (written in listOf("+37112345678", "37112345678", "+371 12 345 678", "(371) 12-345-678")) {
            assertEquals(written, expected, factory.showByNumber(written).url)
            assertEquals(written, expected, factory.reportByNumber(written, DeliveryMethod.SMS, "1").url)
        }
    }

    @Test
    fun `a dot in the number never reaches the path, where it would read as a format suffix`() {
        // Uri.encode leaves "." alone, and the API's router reads a trailing ".something"
        // in the last path segment as a format suffix — so a percent-encoded
        // "+371.12345678" would silently address a different number.
        assertTrue(factory.showByNumber("+371.12345678").url.endsWith("/by_number/37112345678"))
    }

    @Test
    fun `create nests the channel block under the delivery method's own name`() {
        val body = factory.create(
            "+37112345678",
            DeliveryMethod.SMS,
            buildJsonObject { put("app_hash", "FA+9qCX9VSu") },
        ).body!!
        assertTrue(body.contains("\"delivery_method\":\"sms\""))
        assertTrue(body.contains("\"sms\":{\"app_hash\":\"FA+9qCX9VSu\"}"))

        // The factory never inspects the block, so a channel with different keys nests
        // the same way with no change here — which is what makes adding one cheap.
        val callout = factory.create(
            "+37112345678",
            DeliveryMethod.CALLOUT,
            buildJsonObject { put("languages", JsonArray(listOf(JsonPrimitive("pt-BR")))) },
        ).body!!
        assertTrue(callout.contains("\"delivery_method\":\"callout\""))
        assertTrue(callout.contains("\"callout\":{\"languages\":[\"pt-BR\"]}"))
    }

    @Test
    fun `every channel reports its code under code, named by delivery method`() {
        // The method travels with the report and is validated server-side against the
        // verification, so it is not decoration: reporting under the wrong one is refused.
        for (method in DeliveryMethod.entries) {
            val body = factory.report("id", method, "123456").body!!
            assertTrue(body.contains("\"code\":\"123456\""))
            assertTrue(body.contains("\"delivery_method\":\"${method.wireValue}\""))
        }
    }

    @Test
    fun `public auth sends the key unencoded`() {
        assertEquals(
            "Application app-key",
            factory.create("+371", DeliveryMethod.SMS, null).headers["Authorization"],
        )
    }

    @Test
    fun `basic auth base64-encodes key and secret with no line wrapping`() {
        // A wrapped base64 value would embed a newline in a header. Rare enough that a
        // long key is the first place it shows up, and it shows up as a 401.
        val basic = RequestFactory("https://x", Auth.Basic("some-rather-long-key", "some-rather-long-secret"))
        val header = basic.show("id").headers["Authorization"]!!
        assertTrue(header.startsWith("Basic "))
        assertTrue("base64 must not be wrapped", !header.contains("\n"))
        assertEquals(
            "some-rather-long-key:some-rather-long-secret",
            String(android.util.Base64.decode(header.removePrefix("Basic "), android.util.Base64.DEFAULT)),
        )
    }

    @Test
    fun `a GET carries no Content-Type and no body`() {
        val get = factory.show("id")
        assertEquals(null, get.body)
        assertEquals(null, get.headers["Content-Type"])
        assertEquals("application/json", get.headers["Accept"])
    }
}
