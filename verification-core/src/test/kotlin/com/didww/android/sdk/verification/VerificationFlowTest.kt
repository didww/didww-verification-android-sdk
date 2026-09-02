package com.didww.android.sdk.verification

import com.didww.android.sdk.verification.testing.FakeTransport
import com.didww.android.sdk.verification.testing.Fixtures
import com.didww.android.sdk.verification.testing.created
import com.didww.android.sdk.verification.testing.ok
import com.didww.android.sdk.verification.testing.unprocessable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(DidwwInternalApi::class)
@RunWith(RobolectricTestRunner::class)
class VerificationFlowTest {

    private fun engine(transport: FakeTransport) = VerificationEngine(
        context = RuntimeEnvironment.getApplication(),
        auth = Auth.Public("app-key"),
        environment = Environment.Custom("https://verification.example"),
        config = Config(),
        transport = transport,
    )

    private fun List<VerificationState>.shape() = map { it::class.simpleName }

    // ---------------------------------------------------------------- happy path

    @Test
    fun `a submitted code that the server accepts reaches Verified`() = runTest {
        val transport = FakeTransport(created(Fixtures.pendingSms()), ok(Fixtures.verified()))
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null, null)

        handle.submit("123456")
        val states = handle.states.toList()

        assertEquals(listOf("Starting", "AwaitingInput", "Submitting", "Verified"), states.shape())
        assertEquals(Fixtures.ID, (states.last() as VerificationState.Verified).verificationId)
    }

    @Test
    fun `the template reaches AwaitingInput with the placeholder intact`() = runTest {
        val transport = FakeTransport(created(Fixtures.pendingSms()), ok(Fixtures.verified()))
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null, null)
        handle.submit("123456")

        val awaiting = handle.states.toList()[1] as VerificationState.AwaitingInput
        assertEquals(Fixtures.TEMPLATE, awaiting.sms?.template)
        assertEquals("0.0450", awaiting.fee)
        assertNull(awaiting.lastError)
    }

    // ------------------------------------------------------- submit before collect

    @Test
    fun `submit is legal before the flow is ever collected and the value survives`() = runTest {
        // AwaitingInput only arrives after a network round trip. A user who already has
        // the code should not have to wait for that, so the sink exists from t=0 and is
        // UNLIMITED rather than CONFLATED.
        val transport = FakeTransport(created(Fixtures.pendingSms()), ok(Fixtures.verified()))
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null, null)

        handle.submit("123456")
        handle.submit("999999") // must be buffered, not dropped by conflation

        val states = handle.states.toList()
        assertEquals("Verified", states.last()::class.simpleName)
        assertTrue(transport.requests[1].body!!.contains("\"code\":\"123456\""))
    }

    // --------------------------------------------------------------- cold-flow latch

    @Test
    fun `two concurrent collections issue exactly one POST`() = runTest {
        // The failure this prevents is a screen rotation double-billing the customer: two
        // collections would mean two POSTs, and the server's unique-active index would
        // supersede the first.
        val transport = FakeTransport(created(Fixtures.pendingSms()), ok(Fixtures.verified()))
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null, null)
        handle.submit("123456")

        val first = async { handle.states.toList() }
        val second = async { handle.states.toList() }
        val results = listOf(first.await(), second.await())

        assertEquals(1, transport.postCount)
        val refused = results.single { it.size == 1 }
        val reason = (refused.single() as VerificationState.Failed).reason
        assertEquals(SdkError.AlreadyRunning, (reason as FailureReason.Sdk).error)
    }

    @Test
    fun `the latch is permanent, so a sequential re-collect is refused too`() = runTest {
        // Resetting the latch on completion looks like the obvious way to allow retry.
        // It is not: a recomposed LaunchedEffect cancels the old collection before
        // starting the new one, so they are sequential — and a reset latch would let the
        // second POST straight through.
        val transport = FakeTransport(created(Fixtures.pendingSms()), ok(Fixtures.verified()))
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null, null)
        handle.submit("123456")

        handle.states.toList()
        val again = handle.states.toList()

        assertEquals(1, transport.postCount)
        assertEquals(listOf("Failed"), again.shape())
    }

    // ------------------------------------------------------------------- rejections

    @Test
    fun `a wrong code returns to AwaitingInput carrying the reason, and stays live`() = runTest {
        val transport = FakeTransport(
            created(Fixtures.pendingSms()),
            unprocessable(Fixtures.error("code_invalid", "code is invalid")),
            ok(Fixtures.verified()),
        )
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null, null)
        handle.submit("000000")
        handle.submit("123456")

        val states = handle.states.toList()

        assertEquals(
            listOf("Starting", "AwaitingInput", "Submitting", "AwaitingInput", "Submitting", "Verified"),
            states.shape(),
        )
        val retry = states[3] as VerificationState.AwaitingInput
        assertEquals(ApiErrorCode.CODE_INVALID, retry.lastError?.known)
    }

    @Test
    fun `too_many_attempts is terminal and is never counted locally`() = runTest {
        // There is no attempt counter anywhere in this SDK. Whether another try is allowed
        // is the server's decision, and this slug is how it says no.
        val transport = FakeTransport(
            created(Fixtures.pendingSms()),
            unprocessable(Fixtures.error("too_many_attempts", "too many attempts")),
        )
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null, null)
        handle.submit("000000")
        handle.submit("111111") // must never be sent

        val states = handle.states.toList()

        assertEquals(listOf("Starting", "AwaitingInput", "Submitting", "Failed"), states.shape())
        val reason = (states.last() as VerificationState.Failed).reason
        assertEquals(ApiErrorCode.TOO_MANY_ATTEMPTS, (reason as FailureReason.Api).error.known)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `already_verified is a failure, not a success`() = runTest {
        // The server's own wording is "verification is already verified; provided value is
        // invalid". Mapping it to Verified would let a host grant access to someone who
        // typed the wrong code.
        val transport = FakeTransport(
            created(Fixtures.pendingSms()),
            unprocessable(Fixtures.error("already_verified", "verification is already verified")),
        )
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null, null)
        handle.submit("000000")

        val states = handle.states.toList()
        assertEquals("Failed", states.last()::class.simpleName)
    }

    @Test
    fun `not_ready_to_report keeps the verification alive for another attempt`() = runTest {
        val transport = FakeTransport(
            created(Fixtures.pendingSms()),
            unprocessable(Fixtures.error("not_ready_to_report", "verification is not ready to be reported")),
            ok(Fixtures.verified()),
        )
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null, null)
        handle.submit("123456")
        handle.submit("123456")

        assertEquals("Verified", handle.states.toList().last()::class.simpleName)
    }

    // ------------------------------------------------------------ denial and setup

    @Test
    fun `denied_missing_callback_url is a SetupError, not an ordinary failure`() = runTest {
        // A public-auth application with no callback_url gets 201 + denied on EVERY start,
        // forever. Surfacing it as a failure would send a host looking at the phone number.
        val transport = FakeTransport(created(Fixtures.deniedMissingCallbackUrl()))
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null, null)

        val states = handle.states.toList()

        assertEquals(listOf("Starting", "SetupError"), states.shape())
        val setup = states.last() as VerificationState.SetupError
        assertEquals("denied_missing_callback_url", setup.code)
        assertEquals("application has no callback_url", setup.detail)
    }

    @Test
    fun `a callback denial is Denied, and a 201 does not mean the verification is live`() = runTest {
        val transport = FakeTransport(created(Fixtures.deniedByCallback()))
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null, null)

        val states = handle.states.toList()
        assertEquals(listOf("Starting", "Denied"), states.shape())
        assertEquals(
            ApiErrorCode.DENIED_BY_CALLBACK,
            (states.last() as VerificationState.Denied).error?.known,
        )
    }

    @Test
    fun `a failed status on create carries the wire error code`() = runTest {
        val transport = FakeTransport(created(Fixtures.failed()))
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null, null)

        val states = handle.states.toList()
        val reason = (states.last() as VerificationState.Failed).reason
        assertEquals(ApiErrorCode.DISPATCH_FAILED, (reason as FailureReason.Api).error.known)
    }

    // ------------------------------------------------------------------- deadline

    @Test
    fun `a deadline already in the past expires without waiting`() = runTest {
        val transport = FakeTransport(created(Fixtures.pendingSms(expiresAt = "2000-01-01T00:00:00Z")))
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null, null)

        val states = handle.states.toList()
        assertEquals(listOf("Starting", "AwaitingInput", "Expired"), states.shape())
    }

    @Test
    fun `a missing expires_at does not expire and does not crash`() = runTest {
        // The schema makes the field non-null today. An absent value must neither NPE nor
        // silently become an unbounded wait that no deadline will ever end.
        val transport = FakeTransport(created(Fixtures.pendingSms(expiresAt = null)), ok(Fixtures.verified()))
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null, null)
        handle.submit("123456")

        assertEquals("Verified", handle.states.toList().last()::class.simpleName)
    }

    // --------------------------------------------------------------------- resume

    @Test
    fun `resume looks the verification up by number instead of creating one`() = runTest {
        val transport = FakeTransport(ok(Fixtures.pendingSms()), ok(Fixtures.verified()))
        val handle = engine(transport).resume("+37112345678", DeliveryMethod.SMS, null, null)
        handle.submit("123456")

        val states = handle.states.toList()

        assertEquals(listOf("Starting", "AwaitingInput", "Submitting", "Verified"), states.shape())
        assertEquals(0, transport.postCount)
        assertEquals("GET", transport.requests[0].method)
        assertEquals(
            "https://verification.example/api/v1/verifications/by_number/37112345678",
            transport.requests[0].url,
        )
    }

    @Test
    fun `a submission on a resumed handle is addressed by number, not by the id it just read`() = runTest {
        // The destination is the only thing the host was asked to keep across a process
        // death, and it is what the by-number report needs. It is also the request an
        // iOS-parity bridge has to be able to make.
        val transport = FakeTransport(ok(Fixtures.pendingSms()), ok(Fixtures.verified()))
        val handle = engine(transport).resume("+37112345678", DeliveryMethod.SMS, null, null)
        handle.submit("123456")
        handle.states.toList()

        assertEquals("PUT", transport.requests[1].method)
        assertEquals(
            "https://verification.example/api/v1/verifications/by_number/37112345678",
            transport.requests[1].url,
        )
        assertTrue(transport.requests[1].body!!.contains("\"code\":\"123456\""))
    }

    @Test
    fun `resuming a number with no verification fails with not_found and creates nothing`() = runTest {
        // The failure mode this exists to prevent: falling back to a create would supersede
        // nothing, bill the account, and invalidate the code the user is already holding.
        val transport = FakeTransport(
            FakeTransport.Reply.Http(404, Fixtures.error("not_found", "verification not found")),
        )
        val handle = engine(transport).resume("+37112345678", DeliveryMethod.SMS, null, null)

        val states = handle.states.toList()

        assertEquals(listOf("Starting", "Failed"), states.shape())
        val reason = (states.last() as VerificationState.Failed).reason
        assertEquals(ApiErrorCode.NOT_FOUND, (reason as FailureReason.Api).error.known)
        assertEquals(0, transport.postCount)
    }

    @Test
    fun `resuming a finished verification reports its terminal state rather than waiting`() = runTest {
        val transport = FakeTransport(ok(Fixtures.verified()))
        val handle = engine(transport).resume("+37112345678", DeliveryMethod.SMS, null, null)

        assertEquals(listOf("Starting", "Verified"), handle.states.toList().shape())
    }

    @Test
    fun `a destination with no digits is refused before any request is made`() = runTest {
        // Mirrors the iOS SDK's VerificationError.invalidNumber. There is no by-number path
        // to look an empty number up under, so this is a programming error rather than a
        // verification outcome — the one class of thing this SDK throws for.
        val transport = FakeTransport()
        var threw = false
        try {
            engine(transport).resume("not a number", DeliveryMethod.SMS, null, null)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a digitless destination must be refused", threw)
        assertTrue("nothing may reach the transport", transport.requests.isEmpty())
    }

    @Test
    fun `a resumed verification reports on the channel the server names, not the one guessed`() = runTest {
        // resume()'s method argument selects the interception machinery; the verification
        // itself was started elsewhere. Reporting a callout under `delivery_method: "sms"`
        // because the caller guessed SMS would be rejected server-side.
        val transport = FakeTransport(
            ok(Fixtures.pendingCallout()),
            ok(Fixtures.verified(method = "callout")),
        )
        val handle = engine(transport).resume("+37112345678", DeliveryMethod.SMS, null, null)
        handle.submit("123456")

        val states = handle.states.toList()

        assertEquals("Verified", states.last()::class.simpleName)
        assertTrue(transport.requests[1].body!!.contains("\"delivery_method\":\"callout\""))
        val awaiting = states[1] as VerificationState.AwaitingInput
        assertEquals(DeliveryMethod.CALLOUT, awaiting.deliveryMethod)
    }

    // ---------------------------------------------------------------- supersession

    @Test
    fun `a second start for the same destination supersedes the first handle`() = runTest {
        val transport = FakeTransport(created(Fixtures.pendingSms()), created(Fixtures.pendingSms()))
        val engine = engine(transport)

        val first = engine.start("+37112345678", DeliveryMethod.SMS, null, null)
        engine.start("+37112345678", DeliveryMethod.SMS, null, null)

        val states = first.states.toList()

        assertEquals(listOf("Starting", "AwaitingInput", "Failed"), states.shape())
        val reason = (states.last() as VerificationState.Failed).reason
        assertEquals(SdkError.Superseded, (reason as FailureReason.Sdk).error)
    }

    @Test
    fun `a different destination does not supersede`() = runTest {
        val transport = FakeTransport(created(Fixtures.pendingSms()), ok(Fixtures.verified()))
        val engine = engine(transport)

        val first = engine.start("+37112345678", DeliveryMethod.SMS, null, null)
        engine.start("+37199999999", DeliveryMethod.SMS, null, null)
        first.submit("123456")

        assertEquals("Verified", first.states.toList().last()::class.simpleName)
    }

    // ----------------------------------------------------------------- transport

    @Test
    fun `a transport failure surfaces as an SDK failure, not a crash`() = runTest {
        val transport = FakeTransport(FakeTransport.Reply.Failure("connection reset"))
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null, null)

        val states = handle.states.toList()
        val reason = (states.last() as VerificationState.Failed).reason
        assertTrue((reason as FailureReason.Sdk).error is SdkError.Transport)
    }

    @Test
    fun `cancelling the collection cancels the request instead of emitting a failure`() = runTest {
        // Once the socket is disconnected the blocking read surfaces as an IOException,
        // not a CancellationException. Without ensureActive() that would be reported as a
        // spurious transport failure — a cancelled verification would look like a broken
        // network to the host.
        val transport = FakeTransport(FakeTransport.Reply.Hang)
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null, null)

        val seen = mutableListOf<VerificationState>()
        val job = launch { handle.states.toList(seen) }
        advanceUntilIdle()

        // The request is genuinely in flight, so the cancellation has something to interrupt.
        assertEquals(1, transport.requests.size)

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(
            "a cancelled verification must not report itself as failed",
            seen.none { it is VerificationState.Failed },
        )
    }

    // ------------------------------------------------------------ forward tolerance

    @Test
    fun `an unknown status on create is non-terminal and leaves the verification usable`() = runTest {
        // An unrecognised status must neither strand nor mislead an older SDK. It
        // stays where it is and emits nothing, so manual entry still works.
        val quarantined = Fixtures.pendingSms().replace("\"status\":\"pending\"", "\"status\":\"quarantined\"")
        val transport = FakeTransport(created(quarantined), ok(Fixtures.verified()))
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null, null)
        handle.submit("123456")

        assertEquals(
            listOf("Starting", "AwaitingInput", "Submitting", "Verified"),
            handle.states.toList().shape(),
        )
    }

    @Test
    fun `an unknown error slug still terminates with its code and detail intact`() = runTest {
        val transport = FakeTransport(
            created(Fixtures.pendingSms()),
            unprocessable(Fixtures.error("destination_wildly_unlikely", "something new")),
        )
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null, null)
        handle.submit("000000")

        val states = handle.states.toList()
        val error = ((states.last() as VerificationState.Failed).reason as FailureReason.Api).error
        assertEquals("destination_wildly_unlikely", error.code)
        assertEquals("something new", error.detail)
        assertNull(error.known)
    }

    // --------------------------------------------------------------------- dormancy

    @Test
    fun `no interceptor is ever constructed when the channel supplies no factory`() = runTest {
        val transport = FakeTransport(created(Fixtures.pending("callout")), ok(Fixtures.verified("id", "callout")))
        val handle = engine(transport).start("+37112345678", DeliveryMethod.CALLOUT, null, null)
        handle.submit("123456")

        assertEquals(
            listOf("Starting", "AwaitingInput", "Submitting", "Verified"),
            handle.states.toList().shape(),
        )
    }

    // ------------------------------------------------------- the interception budget

    @Test
    fun `the server's interception budget tears the listener down without ending the verification`() = runTest {
        var tornDown = false
        val transport = FakeTransport(created(Fixtures.pendingSms(interceptionTimeout = BUDGET_SECONDS)))
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null) {
            callbackFlow { awaitClose { tornDown = true } }
        }

        val states = mutableListOf<VerificationState>()
        val collection = launch { handle.states.toList(states) }
        runCurrent()
        assertTrue("the listener must still be live before the budget elapses", !tornDown)

        advanceTimeBy(BUDGET_SECONDS * 1_000L + 1)
        runCurrent()

        assertTrue("the budget must stop the listener", tornDown)
        // Stopping is not a state change: the verification is still waiting for a typed code,
        // and expires_at (years away in the fixture) remains the only thing that ends it.
        assertEquals(listOf("Starting", "AwaitingInput"), states.shape())
        collection.cancel()
    }

    @Test
    fun `the interception timeout reaches AwaitingInput for a host to display`() = runTest {
        val transport = FakeTransport(created(Fixtures.pendingSms()))
        val handle = engine(transport).start("+37112345678", DeliveryMethod.SMS, null, null)

        val states = mutableListOf<VerificationState>()
        val collection = launch { handle.states.toList(states) }
        runCurrent()

        val awaiting = states.filterIsInstance<VerificationState.AwaitingInput>().single()
        assertEquals(Fixtures.INTERCEPTION_TIMEOUT, awaiting.sms?.interceptionTimeoutSeconds)
        collection.cancel()
    }

    private companion object {
        private const val BUDGET_SECONDS = 5
    }
}
