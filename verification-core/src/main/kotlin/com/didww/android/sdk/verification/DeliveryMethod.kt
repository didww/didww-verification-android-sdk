package com.didww.android.sdk.verification

/**
 * How the verification code reaches the destination number.
 *
 * The wire values are the API's own `delivery_method` strings.
 */
public enum class DeliveryMethod(internal val wireValue: String) {
    SMS("sms"),
    CALLOUT("callout"),
    ;

    internal companion object {
        internal fun fromWire(value: String?): DeliveryMethod? =
            entries.firstOrNull { it.wireValue == value }
    }
}
