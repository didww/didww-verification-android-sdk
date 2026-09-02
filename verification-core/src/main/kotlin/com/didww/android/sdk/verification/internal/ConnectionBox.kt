package com.didww.android.sdk.verification.internal

import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Holds the live [HttpURLConnection] for one request so a cancellation can abort it.
 *
 * ### Why not `runInterruptible`
 *
 * `runInterruptible` aborts blocking work with `Thread.interrupt()`, and a blocking
 * socket read on a `HttpURLConnection` ignores interruption. Calling `disconnect()`
 * explicitly is the only mechanism that actually stops it.
 *
 * ### The cancel-before-store race
 *
 * The cancellation handler is registered *before* the connection is opened, so a
 * cancellation can land while `openConnection()` is still running — at which point there
 * is nothing to disconnect and the handler does nothing. Without the [cancelled] flag the
 * connection then gets stored into a box nobody will ever look at again and blocks until
 * its read timeout. So [store] checks the flag and disconnects immediately instead.
 *
 * ### The disconnect never runs inline
 *
 * A cancellation handler runs on an undefined thread, and in practice usually on the
 * thread that requested the cancellation — for a UI-driven cancel, the main thread.
 * `HttpURLConnection.disconnect()` is not guaranteed to be non-blocking and over TLS can
 * perform I/O, so it is handed to an executor and never called while the lock is held.
 */
internal class ConnectionBox {

    private val lock = Any()
    private var connection: HttpURLConnection? = null
    private var cancelled = false

    fun store(connection: HttpURLConnection) {
        val alreadyCancelled = synchronized(lock) {
            if (cancelled) {
                true
            } else {
                this.connection = connection
                false
            }
        }
        if (alreadyCancelled) disconnectOffThread(connection)
    }

    fun cancel() {
        val toDisconnect = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            connection.also { connection = null }
        }
        toDisconnect?.let(::disconnectOffThread)
    }

    /** Releases the reference on the normal completion path. */
    fun release() {
        synchronized(lock) { connection = null }
    }

    private fun disconnectOffThread(connection: HttpURLConnection) {
        DISCONNECT_EXECUTOR.execute {
            runCatching { connection.disconnect() }
        }
    }

    private companion object {
        /**
         * Daemon threads: this pool must never hold up JVM shutdown, and it is idle in
         * every run that has no cancellation.
         */
        private val DISCONNECT_EXECUTOR = Executors.newCachedThreadPool(
            ThreadFactory { runnable ->
                Thread(runnable, "didww-verification-disconnect").apply { isDaemon = true }
            },
        )
    }
}
