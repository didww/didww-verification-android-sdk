package com.didww.android.sdk.verification.internal

/**
 * Parses the ISO-8601 timestamps the API emits (`Time#iso8601`) to epoch milliseconds.
 *
 * Hand-rolled rather than `java.time`, which needs API 26 or core-library desugaring —
 * and desugaring is a dependency this SDK would be spending out of the integrator's
 * budget to parse one field.
 *
 * Fails open to `null`. A deadline the SDK cannot read must degrade to
 * "no local countdown", never to a crash.
 */
internal object Iso8601 {

    private val PATTERN = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?\s*(?:([Zz])|([+-])(\d{2}):?(\d{2}))?$""",
    )

    fun parseEpochMillis(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        val m = PATTERN.matchEntire(text) ?: return null
        val g = m.groupValues

        val year = g[1].toIntOrNull() ?: return null
        val month = g[2].toIntOrNull() ?: return null
        val day = g[3].toIntOrNull() ?: return null
        val hour = g[4].toIntOrNull() ?: return null
        val minute = g[5].toIntOrNull() ?: return null
        val second = g[6].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null

        // Left-pad the fraction to milliseconds: ".5" is 500 ms, ".123456" is 123 ms.
        val millis = g[7].takeIf { it.isNotEmpty() }
            ?.padEnd(3, '0')?.take(3)?.toIntOrNull() ?: 0

        val offsetSeconds = when {
            g[8].isNotEmpty() -> 0
            g[9].isNotEmpty() -> {
                val oh = g[10].toIntOrNull() ?: return null
                val om = g[11].toIntOrNull() ?: return null
                val magnitude = oh * SECONDS_PER_HOUR + om * SECONDS_PER_MINUTE
                if (g[9] == "-") -magnitude else magnitude
            }
            // No designator at all. The API always emits one; treat the absence as UTC
            // rather than as the device's zone, which would make the value depend on
            // where the handset happens to be.
            else -> 0
        }

        val days = daysFromCivil(year, month, day)
        val secondsOfDay = hour * SECONDS_PER_HOUR + minute * SECONDS_PER_MINUTE + second
        val epochSeconds = days * SECONDS_PER_DAY + secondsOfDay - offsetSeconds
        return epochSeconds * MILLIS_PER_SECOND + millis
    }

    /**
     * Howard Hinnant's `days_from_civil`: proleptic Gregorian, no time zone database,
     * no `Calendar`, valid far beyond any range this SDK will see.
     */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val shiftedMonth = if (month > 2) month - 3 else month + 9
        val dayOfYear = (153 * shiftedMonth + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }

    private const val SECONDS_PER_MINUTE = 60
    private const val SECONDS_PER_HOUR = 3_600
    private const val SECONDS_PER_DAY = 86_400L
    private const val MILLIS_PER_SECOND = 1_000L
}
