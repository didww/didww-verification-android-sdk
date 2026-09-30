package com.didww.android.sdk.verification.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpUrlTransportTest {

    @Test
    fun `parses a whole-second Retry-After`() {
        assertEquals(17, parseRetryAfterSeconds("17"))
        assertEquals(0, parseRetryAfterSeconds("0"))
    }

    @Test
    fun `a negative, signed, decimal or non-numeric Retry-After degrades to null, not a bogus wait`() {
        assertNull(parseRetryAfterSeconds("-5"))
        assertNull(parseRetryAfterSeconds("+5"))
        assertNull(parseRetryAfterSeconds("5.5"))
        assertNull(parseRetryAfterSeconds("soon"))
        assertNull(parseRetryAfterSeconds(""))
        assertNull(parseRetryAfterSeconds(null))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(17, parseRetryAfterSeconds(" 17 "))
    }
}
