package com.didww.android.sdk.verification.internal

import java.lang.ref.WeakReference

/**
 * Tracks the most recent handle per destination so a second `start()` for the same number
 * can tell the first one it has lost.
 *
 * The server enforces one active verification per destination and supersedes the older
 * row. Without this, the first handle would sit in `AwaitingInput` forever with a
 * verification the server has already retired — the user types the code they received,
 * and it is rejected with no explanation the host can act on.
 *
 * ### What this cannot do
 *
 * Only **in-process** supersession is visible. A verification superseded from another
 * process, another device, or the customer's own backend stays undetected until this
 * handle's next request comes back rejected. The API has no push channel and publishes no
 * poll interval, so there is nothing to observe and no interval this SDK could invent
 * without compiling in a policy value it has no right to choose.
 *
 * Weak references throughout: a host that abandons a handle without collecting it must
 * not have it pinned in memory by this map.
 */
internal class ActiveHandleRegistry {

    private val lock = Any()
    private val handles = HashMap<String, WeakReference<RealVerificationHandle>>()

    /**
     * Registers [handle] and returns the handle it displaced, already notified.
     *
     * Called from `start()`, which performs no I/O — so the previous handle is told it has
     * been superseded strictly before the new one's `POST` goes out.
     */
    fun register(destination: String, handle: RealVerificationHandle) {
        val displaced = synchronized(lock) {
            handles.values.removeAll { it.get() == null }
            handles.put(destination, WeakReference(handle))?.get()
        }
        displaced?.markSuperseded()
    }
}
