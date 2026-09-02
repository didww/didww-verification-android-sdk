package com.didww.android.sdk.verification.internal

import com.didww.android.sdk.verification.Config
import com.didww.android.sdk.verification.DidwwInternalApi
import com.didww.android.sdk.verification.HttpRequest
import com.didww.android.sdk.verification.HttpResponse
import com.didww.android.sdk.verification.Transport
import com.didww.android.sdk.verification.TransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * The only production [Transport]: `HttpURLConnection`, no third-party HTTP client.
 *
 * That is not asceticism. `verification-core` is depended on by every channel module and
 * therefore by every integrator, and an SDK that drags OkHttp into a host's graph picks a
 * fight with whatever the host already uses.
 */
@OptIn(DidwwInternalApi::class)
internal class HttpUrlTransport(private val config: Config) : Transport {

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val box = ConnectionBox()
        val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) box.cancel()
        }
        try {
            val connection = open(request)
            box.store(connection)
            try {
                exchange(connection, request)
            } finally {
                box.release()
                drainAndClose(connection)
            }
        } catch (e: IOException) {
            // Once box.cancel() has disconnected the socket the blocking read surfaces as
            // an IOException, NOT a CancellationException — so an unguarded mapping here
            // would convert a clean cancellation into a spurious transport failure.
            // ensureActive() throws CancellationException when this coroutine was
            // cancelled, and is one line that cannot be got subtly wrong the way an
            // explicit isCancelled check can.
            coroutineContext.ensureActive()
            throw TransportException(e.message ?: e.toString(), e)
        } finally {
            cancellationHandle?.dispose()
        }
    }

    private fun open(request: HttpRequest): HttpURLConnection =
        (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = config.connectTimeoutMillis
            readTimeout = config.readTimeoutMillis
            instanceFollowRedirects = false
            // `headers.forEach { (name, value) -> ... }` reads identically but resolves to
            // java.util.Map#forEach, which is API 24 — a NoSuchMethodError on every API 23
            // device, which is exactly this SDK's floor. A `for` over entries has no such
            // overload to be captured by.
            for ((name, value) in request.headers) setRequestProperty(name, value)
        }

    private fun exchange(connection: HttpURLConnection, request: HttpRequest): HttpResponse {
        request.body?.let { body ->
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.toByteArray(Charsets.UTF_8).size)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }

        val statusCode = connection.responseCode
        // inputStream THROWS on a non-2xx; the body of every routine 422 slug response
        // arrives on errorStream instead.
        val stream: InputStream? =
            if (statusCode in SUCCESS_RANGE) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        return HttpResponse(statusCode, body)
    }

    /**
     * The stream that carried the response was read to EOF by [exchange]; this closes
     * whichever one did not, because an unclosed stream leaves the socket neither pooled
     * nor closed until the garbage collector happens to reach it.
     */
    private fun drainAndClose(connection: HttpURLConnection) {
        runCatching { connection.errorStream?.close() }
        runCatching { connection.inputStream?.close() }
    }

    private companion object {
        private val SUCCESS_RANGE = 200..299
    }
}
