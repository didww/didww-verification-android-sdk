package com.didww.android.sdk.verification.testing

import com.didww.android.sdk.verification.DidwwInternalApi
import com.didww.android.sdk.verification.HttpRequest
import com.didww.android.sdk.verification.HttpResponse
import com.didww.android.sdk.verification.Transport
import com.didww.android.sdk.verification.TransportException
import kotlinx.coroutines.CompletableDeferred

/**
 * A scripted [Transport]. The whole default test run drives this, so `./gradlew test`
 * touches no network at all.
 */
@OptIn(DidwwInternalApi::class)
public class FakeTransport(private vararg val script: Reply) : Transport {

    public sealed interface Reply {
        public class Http(public val statusCode: Int, public val body: String) : Reply

        /** The request fails at the transport layer, as a dropped connection would. */
        public class Failure(public val message: String) : Reply

        /** Never answers, so a caller's cancellation has something real to interrupt. */
        public object Hang : Reply
    }

    private val lock = Any()
    private val _requests = mutableListOf<HttpRequest>()

    /** Every request in order. Assert on this to prove a call was — or was not — made. */
    public val requests: List<HttpRequest>
        get() = synchronized(lock) { _requests.toList() }

    public val postCount: Int
        get() = requests.count { it.method == "POST" }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val index = synchronized(lock) {
            _requests += request
            _requests.size - 1
        }
        return when (val reply = script.getOrNull(index)) {
            null -> error(
                "FakeTransport has no scripted reply #$index for ${request.method} ${request.url}. " +
                    "An unscripted request is a test bug, not a pass.",
            )
            is Reply.Http -> HttpResponse(reply.statusCode, reply.body)
            is Reply.Failure -> throw TransportException(reply.message)
            // Suspends until cancelled. Completing normally would let a cancellation test
            // pass without ever cancelling anything.
            Reply.Hang -> CompletableDeferred<HttpResponse>().await()
        }
    }
}

public fun ok(body: String): FakeTransport.Reply = FakeTransport.Reply.Http(200, body)

public fun created(body: String): FakeTransport.Reply = FakeTransport.Reply.Http(201, body)

public fun unprocessable(body: String): FakeTransport.Reply = FakeTransport.Reply.Http(422, body)
