package com.didww.android.sdk.verification

import com.didww.android.sdk.verification.internal.Iso8601
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Iso8601Test {

    @Test
    fun `parses the UTC form the API emits`() {
        assertEquals(0L, Iso8601.parseEpochMillis("1970-01-01T00:00:00Z"))
        assertEquals(1_000L, Iso8601.parseEpochMillis("1970-01-01T00:00:01Z"))
        assertEquals(1_893_456_000_000L, Iso8601.parseEpochMillis("2030-01-01T00:00:00Z"))
    }

    @Test
    fun `applies a numeric offset in the right direction`() {
        // 03:00+03:00 is the same instant as 00:00Z, so the offset must be SUBTRACTED.
        // Getting the sign backwards produces a plausible timestamp six hours out.
        assertEquals(
            Iso8601.parseEpochMillis("2030-01-01T00:00:00Z"),
            Iso8601.parseEpochMillis("2030-01-01T03:00:00+03:00"),
        )
        assertEquals(
            Iso8601.parseEpochMillis("2030-01-01T00:00:00Z"),
            Iso8601.parseEpochMillis("2029-12-31T19:00:00-05:00"),
        )
    }

    @Test
    fun `accepts an offset without a colon`() {
        assertEquals(
            Iso8601.parseEpochMillis("2030-01-01T03:00:00+03:00"),
            Iso8601.parseEpochMillis("2030-01-01T03:00:00+0300"),
        )
    }

    @Test
    fun `left-pads a fractional second to milliseconds`() {
        assertEquals(500L, Iso8601.parseEpochMillis("1970-01-01T00:00:00.5Z"))
        assertEquals(120L, Iso8601.parseEpochMillis("1970-01-01T00:00:00.12Z"))
        assertEquals(123L, Iso8601.parseEpochMillis("1970-01-01T00:00:00.123Z"))
        assertEquals(123L, Iso8601.parseEpochMillis("1970-01-01T00:00:00.123456789Z"))
    }

    @Test
    fun `handles leap years both ways`() {
        // 2000 is a leap year (divisible by 400); 1900 is not (divisible by 100).
        // A daysFromCivil that gets the century rule wrong passes every other test here.
        assertEquals(951_782_400_000L, Iso8601.parseEpochMillis("2000-02-29T00:00:00Z"))
        assertEquals(
            86_400_000L,
            Iso8601.parseEpochMillis("1900-03-01T00:00:00Z")!! -
                Iso8601.parseEpochMillis("1900-02-28T00:00:00Z")!!,
        )
    }

    @Test
    fun `fails open to null rather than throwing`() {
        assertNull(Iso8601.parseEpochMillis(null))
        assertNull(Iso8601.parseEpochMillis(""))
        assertNull(Iso8601.parseEpochMillis("   "))
        assertNull(Iso8601.parseEpochMillis("not a timestamp"))
        assertNull(Iso8601.parseEpochMillis("2030-13-01T00:00:00Z"))
        assertNull(Iso8601.parseEpochMillis("2030-01-01"))
    }
}
