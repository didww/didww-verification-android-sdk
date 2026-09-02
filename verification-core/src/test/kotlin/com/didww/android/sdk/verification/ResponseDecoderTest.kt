package com.didww.android.sdk.verification

import com.didww.android.sdk.verification.internal.DecodeException
import com.didww.android.sdk.verification.internal.ResponseDecoder
import com.didww.android.sdk.verification.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseDecoderTest {

    @Test
    fun `decodes a pending sms verification`() {
        val payload = ResponseDecoder.verification(Fixtures.pendingSms())
        assertEquals(Fixtures.ID, payload.id)
        assertEquals(Fixtures.DESTINATION, payload.destination)
        assertEquals(DeliveryMethod.SMS, payload.deliveryMethod)
        assertEquals(VerificationStatus.Pending, payload.status)
        assertEquals(Fixtures.TEMPLATE, payload.template)
        assertNull(payload.error)
    }

    @Test
    fun `fee decodes from a string and from a number, and stays exact`() {
        // Alba renders a BigDecimal as a JSON string; a hand-written fixture or a future
        // serializer change may render it as a number. Both must work, and neither may
        // go through a Double — this is money.
        assertEquals("0.0450", ResponseDecoder.verification(Fixtures.pendingSms(fee = "\"0.0450\"")).fee)
        assertEquals("0.0450", ResponseDecoder.verification(Fixtures.pendingSms(fee = "0.0450")).fee)
    }

    @Test
    fun `an unknown status decodes to Other and does not throw`() {
        val body = Fixtures.pendingSms().replace("\"status\":\"pending\"", "\"status\":\"quarantined\"")
        val status = ResponseDecoder.verification(body).status
        assertEquals(VerificationStatus.Other("quarantined"), status)
    }

    @Test
    fun `unknown fields are ignored rather than fatal`() {
        val body = Fixtures.pendingSms().replace(
            "\"status\":\"pending\"",
            "\"status\":\"pending\",\"some_field_from_the_future\":{\"nested\":[1,2,3]}",
        )
        assertEquals(VerificationStatus.Pending, ResponseDecoder.verification(body).status)
    }

    @Test
    fun `a missing expires_at yields null rather than throwing`() {
        val payload = ResponseDecoder.verification(Fixtures.pendingSms(expiresAt = null))
        assertNull(payload.expiresAtEpochMillis)
    }

    @Test
    fun `decodes the interception timeout, and tolerates its absence`() {
        assertEquals(
            Fixtures.INTERCEPTION_TIMEOUT,
            ResponseDecoder.verification(Fixtures.pendingSms()).interceptionTimeoutSeconds,
        )
        assertNull(
            ResponseDecoder.verification(Fixtures.pendingSms(interceptionTimeout = null))
                .interceptionTimeoutSeconds,
        )
    }

    @Test
    fun `interception_timeout is a JSON number on the wire, and a quoted one still reads`() {
        // The server sends `"interception_timeout": 120` — a NUMBER. Reading the primitive's
        // raw content accepts a quoted one too, exactly as `fee` does, so neither a
        // serializer change nor a hand-written fixture can silently zero out the budget.
        val asNumber = Fixtures.pendingSms()
        assertTrue(
            "the fixture must carry the wire's own shape, or this asserts nothing",
            asNumber.contains("\"interception_timeout\":${Fixtures.INTERCEPTION_TIMEOUT}"),
        )
        assertEquals(
            Fixtures.INTERCEPTION_TIMEOUT,
            ResponseDecoder.verification(asNumber).interceptionTimeoutSeconds,
        )

        val asString = asNumber.replace(
            "\"interception_timeout\":${Fixtures.INTERCEPTION_TIMEOUT}",
            "\"interception_timeout\":\"${Fixtures.INTERCEPTION_TIMEOUT}\"",
        )
        assertEquals(
            Fixtures.INTERCEPTION_TIMEOUT,
            ResponseDecoder.verification(asString).interceptionTimeoutSeconds,
        )
    }

    @Test
    fun `a non-numeric interception timeout yields null rather than throwing`() {
        // It is displayed, never enforced against, so an unreadable value must not be fatal.
        val body = Fixtures.pendingSms().replace("\"interception_timeout\":120", "\"interception_timeout\":\"soon\"")
        assertNull(ResponseDecoder.verification(body).interceptionTimeoutSeconds)
    }

    @Test
    fun `a null template is tolerated`() {
        val payload = ResponseDecoder.verification(Fixtures.pendingSms(template = null))
        assertNull(payload.template)
    }

    @Test
    fun `decodes the language the server chose, on either channel`() {
        // One key, one meaning, two blocks. The decoder reads whichever block matches
        // `delivery_method`, so this must work without the caller saying which channel.
        assertEquals(
            Fixtures.LANGUAGE,
            ResponseDecoder.verification(Fixtures.pendingSms()).language,
        )
        assertEquals(
            "pt-BR",
            ResponseDecoder.verification(Fixtures.pendingCallout(language = "pt-BR")).language,
        )
    }

    @Test
    fun `a missing language yields null rather than throwing`() {
        // It is displayed and compared against, never enforced with, so a response that
        // omits it must not be fatal — including one carrying no channel block at all.
        assertNull(ResponseDecoder.verification(Fixtures.pendingCallout(language = null)).language)
        assertNull(ResponseDecoder.verification(Fixtures.pendingSms(language = null)).language)
        assertNull(ResponseDecoder.verification(Fixtures.pending("callout")).language)
    }

    @Test
    fun `decodes a pending callout verification`() {
        val payload = ResponseDecoder.verification(Fixtures.pendingCallout())
        assertEquals(DeliveryMethod.CALLOUT, payload.deliveryMethod)
        assertEquals(VerificationStatus.Pending, payload.status)
        assertEquals(Fixtures.LANGUAGE, payload.language)
        assertNull("callout has no template of its own", payload.template)
    }

    @Test
    fun `reads the channel block by the delivery method's own name`() {
        val payload = ResponseDecoder.verification(Fixtures.pendingSms(appHash = "FA+9qCX9VSu"))
        assertEquals("FA+9qCX9VSu", payload.channelBlock?.get("app_hash")?.toString()?.trim('"'))
    }

    @Test
    fun `a body with no data object is a decode failure, not a silent empty verification`() {
        var threw = false
        try {
            ResponseDecoder.verification("""{"errors":[{"code":"not_found","detail":"not found"}]}""")
        } catch (e: DecodeException) {
            threw = true
        }
        assertTrue("a response with no `data` must not decode to an empty verification", threw)
    }

    @Test
    fun `decodes the error envelope`() {
        val errors = ResponseDecoder.errors(
            Fixtures.errors("code_invalid" to "code is invalid", "code_blank" to "code can't be blank"),
        )
        assertEquals(listOf("code_invalid", "code_blank"), errors.map { it.code })
        assertEquals(ApiErrorCode.CODE_INVALID, errors.first().known)
    }

    @Test
    fun `an unreadable error body yields an empty list rather than an exception`() {
        // A non-2xx with a proxy's HTML error page still has to produce something the
        // caller can act on.
        assertEquals(emptyList<ApiErrorItem>(), ResponseDecoder.errors("<html>502 Bad Gateway</html>"))
        assertEquals(emptyList<ApiErrorItem>(), ResponseDecoder.errors(""))
    }
}
