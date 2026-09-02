package com.didww.android.sdk.verification

/** One HTTP exchange, reduced to what this SDK actually needs. */
@DidwwInternalApi
public class HttpRequest(
    public val method: String,
    public val url: String,
    public val headers: Map<String, String>,
    public val body: String?,
)

@DidwwInternalApi
public class HttpResponse(
    public val statusCode: Int,
    public val body: String,
)

/**
 * The seam every network call in this SDK goes through.
 *
 * Exists so the default test run can drive a complete verification with no network at
 * all. `HttpUrlTransport` is the only production implementation.
 */
@DidwwInternalApi
public interface Transport {

    /**
     * Must be cancellable: cancelling the calling coroutine has to abort the in-flight
     * request rather than leave a socket blocked on a read.
     *
     * @throws TransportException on any I/O failure
     * @throws kotlinx.coroutines.CancellationException if the caller was cancelled
     */
    public suspend fun execute(request: HttpRequest): HttpResponse
}

/** Raised by a [Transport] when the exchange never completed. */
@DidwwInternalApi
public class TransportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
