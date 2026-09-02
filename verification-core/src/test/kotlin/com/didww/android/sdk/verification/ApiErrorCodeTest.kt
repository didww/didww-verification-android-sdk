package com.didww.android.sdk.verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ApiErrorCodeTest {

    /**
     * Exactly twenty-eight slugs are reachable on this wire. DIDWW's error vocabulary as a
     * whole is larger, but the rest belong to other product surfaces and can never appear
     * on a verification response.
     *
     * This asserts the count so that transcribing the whole vocabulary — the natural
     * mistake — fails loudly instead of shipping unreachable cases.
     */
    @Test
    fun `enumerates exactly the twenty-eight slugs reachable on this wire`() {
        assertEquals(28, ApiErrorCode.entries.size)

        val expected = setOf(
            "destination_blank", "destination_invalid",
            "delivery_method_blank", "delivery_method_inclusion", "delivery_method_invalid",
            "languages_invalid", "app_hash_invalid",
            "code_blank", "code_value_present",
            "destination_not_supported_for_channel", "code_invalid",
            "already_verified", "not_ready_to_report",
            "parameter_missing", "not_found", "unauthorized", "balance_insufficient",
            "validation_failed", "internal_error",
            "dispatch_failed", "expired", "too_many_attempts", "stale_dispatch",
            "application_deleted", "superseded",
            "denied_missing_callback_url", "denied_by_callback", "denied_invalid_callback_response",
        )
        assertEquals(expected, ApiErrorCode.entries.map { it.slug }.toSet())
    }

    @Test
    fun `slugs are unique`() {
        assertEquals(ApiErrorCode.entries.size, ApiErrorCode.entries.map { it.slug }.toSet().size)
    }

    @Test
    fun `an unknown slug resolves to null instead of throwing`() {
        assertNull(ApiErrorCode.fromSlug("destination_wildly_unlikely"))
        assertNull(ApiErrorCode.fromSlug(null))
        assertNull(ApiErrorCode.fromSlug(""))
        assertNotNull(ApiErrorCode.fromSlug("code_invalid"))
    }

    @Test
    fun `an unknown slug keeps its code and detail on the item`() {
        // The server derives validation slugs mechanically as "attribute_type", so it can
        // mint one this SDK has never seen just by adding a validation. The raw values
        // must survive that.
        val item = ApiErrorItem.of("some_future_attribute_taken", "some future detail")
        assertEquals("some_future_attribute_taken", item.code)
        assertEquals("some future detail", item.detail)
        assertNull(item.known)
    }
}
