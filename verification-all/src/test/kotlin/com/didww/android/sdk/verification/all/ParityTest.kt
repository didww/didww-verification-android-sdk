package com.didww.android.sdk.verification.all

import com.didww.android.sdk.verification.Auth
import com.didww.android.sdk.verification.CalloutOptions
import com.didww.android.sdk.verification.Config
import com.didww.android.sdk.verification.DeliveryMethod
import com.didww.android.sdk.verification.DidwwInternalApi
import com.didww.android.sdk.verification.Environment
import com.didww.android.sdk.verification.SmsOptions
import com.didww.android.sdk.verification.VerificationEngine
import com.didww.android.sdk.verification.VerificationHandle
import com.didww.android.sdk.verification.VerificationState
import com.didww.android.sdk.verification.callout.CalloutVerification
import com.didww.android.sdk.verification.sms.SmsVerification
import com.didww.android.sdk.verification.testing.FakeTransport
import com.didww.android.sdk.verification.testing.Fixtures
import com.didww.android.sdk.verification.testing.created
import com.didww.android.sdk.verification.testing.ok
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Two public entrypoint styles have to stay indistinguishable, or the SDK has two
 * behaviours wearing one name.
 *
 * `DidwwVerification` is meant to be a dispatch table over the same channel classes, not a
 * parallel implementation. Nothing about that is enforced by the type system — someone can
 * add a behaviour to one side at any time — so it is asserted here, per channel, against
 * one scripted transport.
 */
@OptIn(DidwwInternalApi::class)
@RunWith(RobolectricTestRunner::class)
class ParityTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private fun engine(transport: FakeTransport) = VerificationEngine(
        context = app,
        auth = Auth.Public("app-key"),
        environment = Environment.Custom("https://verification.example"),
        config = Config(),
        transport = transport,
    )

    private suspend fun run(handle: VerificationHandle): List<String> {
        handle.submit("123456")
        return handle.states.toList().map { it::class.simpleName!! }
    }

    private fun script(method: String) = FakeTransport(
        created(pendingFor(method)),
        ok(Fixtures.verified(method = method)),
    )

    /** The same conversation, opened with a by-number lookup instead of a create. */
    private fun resumeScript(method: String) = FakeTransport(
        ok(pendingFor(method)),
        ok(Fixtures.verified(method = method)),
    )

    /** The channel block the API actually returns for each method, blocks and all. */
    private fun pendingFor(method: String): String = when (method) {
        "sms" -> Fixtures.pendingSms()
        "callout" -> Fixtures.pendingCallout()
        else -> error("no pending fixture for channel $method")
    }

    /**
     * Named explicitly rather than derived, so a channel added later has to answer for its
     * own wire value here instead of inheriting one silently.
     */
    private fun DeliveryMethod.wire(): String = when (this) {
        DeliveryMethod.SMS -> "sms"
        DeliveryMethod.CALLOUT -> "callout"
    }

    @Test
    fun `sms via the umbrella matches SmsVerification used directly`() = runTest {
        val viaUmbrella = script("sms")
        val direct = script("sms")

        val umbrellaStates = run(
            DidwwVerification(engine(viaUmbrella)).start("+37112345678", DeliveryMethod.SMS),
        )
        val directStates = run(SmsVerification(engine(direct)).start("+37112345678"))

        assertEquals(directStates, umbrellaStates)
        assertEquals(
            direct.requests.map { it.method to it.body },
            viaUmbrella.requests.map { it.method to it.body },
        )
    }

    @Test
    fun `callout via the umbrella matches CalloutVerification used directly`() = runTest {
        val viaUmbrella = script("callout")
        val direct = script("callout")

        val umbrellaStates = run(
            DidwwVerification(engine(viaUmbrella)).start("+37112345678", DeliveryMethod.CALLOUT),
        )
        val directStates = run(CalloutVerification(engine(direct)).start("+37112345678"))

        assertEquals(directStates, umbrellaStates)
        assertEquals(
            direct.requests.map { it.method to it.body },
            viaUmbrella.requests.map { it.method to it.body },
        )
    }

    @Test
    fun `callout options reach the wire identically through either entrypoint`() = runTest {
        // The dispatch table has to hand the options on, not drop them: the umbrella and
        // the channel class must produce the same bytes, options included.
        val viaUmbrella = script("callout")
        val direct = script("callout")
        val languages = listOf("pt-BR", "en-US")

        run(
            DidwwVerification(engine(viaUmbrella)).start(
                "+37112345678",
                DeliveryMethod.CALLOUT,
                callout = CalloutOptions(languages = languages),
            ),
        )
        run(
            CalloutVerification(engine(direct))
                .start("+37112345678", CalloutOptions(languages = languages)),
        )

        assertEquals(
            direct.requests.map { it.method to it.body },
            viaUmbrella.requests.map { it.method to it.body },
        )
        assertEquals(
            true,
            viaUmbrella.requests[0].body!!.contains("\"callout\":{\"languages\":[\"pt-BR\",\"en-US\"]}"),
        )
    }

    @Test
    fun `options for the wrong channel are refused at the call site, on every channel`() = runTest {
        // The server reads only the block matching `delivery_method`, so a misplaced block
        // is silently dropped and answered 201 with the defaults. Failing here is the only
        // point at which that is still visible as a mistake rather than as a message in the
        // wrong language.
        val client = DidwwVerification(engine(script("sms")))

        for (method in DeliveryMethod.entries - DeliveryMethod.SMS) {
            val thrown = runCatching {
                client.start("+37112345678", method, sms = SmsOptions(languages = listOf("de-DE")))
            }.exceptionOrNull()
            assertEquals("sms options on $method", IllegalArgumentException::class, thrown!!::class)
        }
        for (method in DeliveryMethod.entries - DeliveryMethod.CALLOUT) {
            val thrown = runCatching {
                client.start("+37112345678", method, callout = CalloutOptions(languages = listOf("pt-BR")))
            }.exceptionOrNull()
            assertEquals("callout options on $method", IllegalArgumentException::class, thrown!!::class)
        }
    }

    @Test
    fun `every channel reaches Verified through manual entry alone`() = runTest {
        // The coverage claim, asserted rather than described: every channel works end to
        // end today, with no interception anywhere.
        for (method in DeliveryMethod.entries) {
            val wire = method.wire()
            val states = run(DidwwVerification(engine(script(wire))).start("+37112345678", method))
            assertEquals(
                "channel $wire must reach Verified by manual entry",
                listOf("Starting", "AwaitingInput", "Submitting", "Verified"),
                states,
            )
        }
    }

    @Test
    fun `resume via the umbrella matches the channel class used directly, on every channel`() = runTest {
        // resume() is a second entrypoint into the same dispatch table, so it carries the
        // same risk of becoming a parallel implementation that start() does.
        for (method in DeliveryMethod.entries) {
            val wire = method.wire()
            val viaUmbrella = resumeScript(wire)
            val direct = resumeScript(wire)

            val umbrellaStates = run(DidwwVerification(engine(viaUmbrella)).resume("+37112345678", method))
            val directStates = run(
                when (method) {
                    DeliveryMethod.SMS -> SmsVerification(engine(direct)).resume("+37112345678")
                    DeliveryMethod.CALLOUT -> CalloutVerification(engine(direct)).resume("+37112345678")
                },
            )

            assertEquals("channel $wire", directStates, umbrellaStates)
            assertEquals(
                "channel $wire",
                direct.requests.map { Triple(it.method, it.url, it.body) },
                viaUmbrella.requests.map { Triple(it.method, it.url, it.body) },
            )
            assertEquals(
                "channel $wire must resume rather than create",
                listOf("Starting", "AwaitingInput", "Submitting", "Verified"),
                umbrellaStates,
            )
        }
    }

    @Test
    fun `verification-all exposes VerificationState, so a host needs no extra artifact`() {
        // Asserted at compile time: this file references only verification-all's
        // own dependency, and VerificationState comes from core through `api`.
        val state: VerificationState = VerificationState.Starting
        assertEquals("Starting", state::class.simpleName)
    }
}
