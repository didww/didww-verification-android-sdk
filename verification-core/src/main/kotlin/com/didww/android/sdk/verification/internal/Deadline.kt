package com.didww.android.sdk.verification.internal

import android.os.SystemClock

/** Seam so expiry can be driven deterministically in tests. */
internal interface Clock {
    fun epochMillis(): Long
    fun elapsedRealtimeMillis(): Long
}

internal object AndroidClock : Clock {
    override fun epochMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}

/**
 * Counts down to the server's `expires_at` **without ever consulting the wall clock again**.
 *
 * The wall clock is read exactly once, at receipt, to convert the server's absolute
 * deadline into a duration; from then on the countdown runs on
 * [SystemClock.elapsedRealtime], which cannot jump.
 *
 * The decisive problem is not clock *skew* — a steady offset would merely make the
 * deadline slightly early or late. It is clock *jumps*: an NTP correction or a user
 * changing the date moves `currentTimeMillis()` by minutes or hours mid-verification, and
 * against a two-minute deadline that either expires the verification instantly or never
 * expires it at all. `elapsedRealtime()` counts since boot and is immune to both.
 *
 * This is a UX affordance layered over an authoritative server: reaching zero locally
 * does not make the verification expired, and a value submitted after zero is still sent.
 */
internal class Deadline private constructor(
    private val clock: Clock,
    private val deadlineElapsedRealtime: Long,
) {

    fun remainingMillis(): Long = deadlineElapsedRealtime - clock.elapsedRealtimeMillis()

    companion object {
        /**
         * `null` when the server sent no deadline. The schema currently makes the field
         * non-null, but an absent value must neither throw nor silently become an
         * unbounded wait — the caller treats `null` as "no local countdown" and lets the
         * server decide.
         */
        fun from(expiresAtEpochMillis: Long?, clock: Clock): Deadline? {
            if (expiresAtEpochMillis == null) return null
            // coerceAtLeast(0) deliberately collapses two cases: a verification that has
            // genuinely already expired, and a device whose clock runs ahead of the
            // server's. Both mean "do not wait". Do not "fix" this by trusting the local
            // clock to tell them apart — it is the thing that cannot be trusted.
            val remaining = (expiresAtEpochMillis - clock.epochMillis()).coerceAtLeast(0)
            return Deadline(clock, clock.elapsedRealtimeMillis() + remaining)
        }
    }
}
