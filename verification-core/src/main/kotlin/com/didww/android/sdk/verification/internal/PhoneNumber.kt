package com.didww.android.sdk.verification.internal

/**
 * Reduces a destination to its ASCII digits — the canonical form of the `by_number` path
 * segment, and the same normalisation the sibling iOS SDK applies.
 *
 * ### Digits-only is load-bearing, not hygiene
 *
 * Percent-encoding a formatted number and letting the server undo it is not equivalent:
 *
 * - A `.` survives [android.net.Uri.encode] untouched, and the API's router reads a trailing
 *   `.something` in the last path segment as a format suffix. `+371.12345678` therefore
 *   arrives as a *different* number, silently.
 * - The two SDKs must put the same bytes on the wire for the same input. `Uri.encode` sends
 *   `%2B37112345678` where iOS sends `37112345678`; a shared backend, a shared log, or a
 *   cross-platform bridge then has two spellings of one destination to reconcile.
 *
 * Filters an explicit `'0'..'9'` range rather than [Char.isDigit], which also admits
 * Arabic-Indic and other non-ASCII digits that the server does not accept.
 */
internal fun digitsOf(number: String): String = number.filter { it in '0'..'9' }
