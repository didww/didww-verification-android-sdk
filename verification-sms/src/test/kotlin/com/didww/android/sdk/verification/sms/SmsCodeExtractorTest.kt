package com.didww.android.sdk.verification.sms

import com.didww.android.sdk.verification.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsCodeExtractorTest {

    @Test
    fun `extracts the code from a plain rendered template`() {
        assertEquals(
            "123456",
            SmsCodeExtractor.extract(
                "Your DIDWW code is {{CODE}}. Do not share it.",
                "Your DIDWW code is 123456. Do not share it.",
            ),
        )
    }

    @Test
    fun `extracts from the Retriever's wrapped body the API actually sends`() {
        // Composed by Fixtures.deliveredBody, so this tracks the wire rather than a guess:
        // an `<#>` prefix and the eleven-character hash as the final token. An anchored match
        // finds nothing here, which is why the matcher is unanchored.
        assertEquals(
            "123456",
            SmsCodeExtractor.extract(
                Fixtures.TEMPLATE,
                Fixtures.deliveredBody(code = "123456", appHash = "FA+9qCX9VSu"),
            ),
        )
    }

    @Test
    fun `extracts when the placeholder ends the template, so the hash follows the code directly`() {
        // Nothing but a space separates code from hash here — the narrowest case there is.
        assertEquals(
            "123456",
            SmsCodeExtractor.extract(
                "Your code is {{CODE}}",
                Fixtures.deliveredBody("123456", "FA+9qCX9VSu", "Your code is {{CODE}}"),
            ),
        )
    }

    @Test
    fun `an all-digit hash is not swallowed into the code`() {
        // Base64 of a digest can be all digits. With the placeholder last, `(\d+)` sits directly
        // against the hash and only the space keeps them apart.
        assertEquals(
            "123456",
            SmsCodeExtractor.extract(
                "Your code is {{CODE}}",
                Fixtures.deliveredBody("123456", "12345678901", "Your code is {{CODE}}"),
            ),
        )
    }

    @Test
    fun `survives carrier text prepended and appended`() {
        assertEquals(
            "987654",
            SmsCodeExtractor.extract("Code: {{CODE}}", "FREE MSG: Code: 987654 -- reply STOP to opt out"),
        )
    }

    @Test
    fun `regex metacharacters in the template are matched literally`() {
        // Every one of these is an operator in a regex. Without Regex.escape the pattern
        // either fails to compile or matches something else entirely.
        assertEquals("111111", SmsCodeExtractor.extract("Code (secret): {{CODE}}.", "Code (secret): 111111."))
        assertEquals("222222", SmsCodeExtractor.extract("Is it {{CODE}}?", "Is it 222222?"))
        assertEquals("333333", SmsCodeExtractor.extract("A+B {{CODE}}", "A+B 333333"))
        assertEquals("444444", SmsCodeExtractor.extract("x*y [{{CODE}}]", "x*y [444444]"))
        assertEquals("555555", SmsCodeExtractor.extract("^start {{CODE}} end${'$'}", "^start 555555 end${'$'}"))
    }

    @Test
    fun `does not truncate trailing template text`() {
        // Trimming the suffix corrupts templates whose trailing text is meaningful — and
        // the reason once given for trimming (that an anchored suffix match would fail)
        // describes a problem an unanchored match does not have.
        assertNull(SmsCodeExtractor.extract("Code {{CODE}} for ACME", "Code 123456 for OTHER"))
        assertEquals("123456", SmsCodeExtractor.extract("Code {{CODE}} for ACME", "Code 123456 for ACME"))
    }

    @Test
    fun `captures digits only, so trailing template text is never swallowed into the code`() {
        // A greedy `(.+)` here would return "123456. Do not share it. FA+9qCX9VSu".
        assertEquals(
            "123456",
            SmsCodeExtractor.extract(Fixtures.TEMPLATE, Fixtures.deliveredBody("123456", "FA+9qCX9VSu")),
        )
    }

    @Test
    fun `imposes no code length, because length is the server's to choose`() {
        assertEquals("1234", SmsCodeExtractor.extract("Code {{CODE}}", "Code 1234"))
        assertEquals("123456", SmsCodeExtractor.extract("Code {{CODE}}", "Code 123456"))
        assertEquals("1234567890123", SmsCodeExtractor.extract("Code {{CODE}}", "Code 1234567890123"))
    }

    @Test
    fun `returns null rather than throwing when there is nothing to match`() {
        assertNull(SmsCodeExtractor.extract(null, "Your code is 123456"))
        assertNull(SmsCodeExtractor.extract("", "Your code is 123456"))
        assertNull(SmsCodeExtractor.extract("A template with no placeholder", "anything"))
        assertNull(SmsCodeExtractor.extract("Code {{CODE}}", "an unrelated message"))
        assertNull(SmsCodeExtractor.extract("Code {{CODE}}", ""))
    }
}
