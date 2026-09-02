package com.didww.android.sdk.verification.callout

import com.didww.android.sdk.verification.Auth
import com.didww.android.sdk.verification.CalloutOptions
import com.didww.android.sdk.verification.Config
import com.didww.android.sdk.verification.DidwwInternalApi
import com.didww.android.sdk.verification.Environment
import com.didww.android.sdk.verification.VerificationEngine
import com.didww.android.sdk.verification.VerificationState
import com.didww.android.sdk.verification.testing.FakeTransport
import com.didww.android.sdk.verification.testing.Fixtures
import com.didww.android.sdk.verification.testing.created
import com.didww.android.sdk.verification.testing.ok
import com.didww.android.sdk.verification.testing.unprocessable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@OptIn(DidwwInternalApi::class)
@RunWith(RobolectricTestRunner::class)
class CalloutVerificationTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private fun callout(transport: FakeTransport) = CalloutVerification(
        VerificationEngine(
            context = app,
            auth = Auth.Public("app-key"),
            environment = Environment.Custom("https://verification.example"),
            config = Config(),
            transport = transport,
        ),
    )

    private fun script() = FakeTransport(
        created(Fixtures.pendingCallout()),
        ok(Fixtures.verified(method = "callout")),
    )

    @Test
    fun `a manually entered code reaches Verified`() = runTest {
        val transport = script()
        val handle = callout(transport).start("+37112345678")

        handle.submit("123456")
        val states = handle.states.toList()

        assertEquals(
            listOf("Starting", "AwaitingInput", "Submitting", "Verified"),
            states.map { it::class.simpleName },
        )
    }

    @Test
    fun `the code is reported under code, named by the callout delivery method`() = runTest {
        val transport = script()
        val handle = callout(transport).start("+37112345678")
        handle.submit("123456")
        handle.states.toList()

        val report = transport.requests[1].body!!
        assertTrue(report.contains("\"code\":\"123456\""))
        assertTrue(report.contains("\"delivery_method\":\"callout\""))
    }

    @Test
    fun `a wrong code keeps the verification live for another try`() = runTest {
        val transport = FakeTransport(
            created(Fixtures.pending("callout")),
            unprocessable(Fixtures.error("code_invalid", "code is invalid")),
            ok(Fixtures.verified(method = "callout")),
        )
        val handle = callout(transport).start("+37112345678")
        handle.submit("000000")
        handle.submit("123456")

        assertEquals("Verified", handle.states.toList().last()::class.simpleName)
    }

    // ------------------------------------------------------------------- languages

    @Test
    fun `languages travel in the callout block, in the order they were given`() = runTest {
        val transport = script()
        val handle = callout(transport).start(
            "+37112345678",
            CalloutOptions(languages = listOf("pt-BR", "en-US")),
        )
        handle.submit("123456")
        handle.states.toList()

        val create = transport.requests[0].body!!
        assertTrue(create, create.contains("\"callout\":{\"languages\":[\"pt-BR\",\"en-US\"]}"))
        // Nested under the delivery method's own name, never at the top level: the server
        // reads only the block matching `delivery_method` and silently drops the rest.
        assertFalse(create, create.contains("\"sms\""))
    }

    @Test
    fun `no options, or an empty list, sends no callout block at all`() = runTest {
        // An empty block says nothing the absent one does not, so it is not sent. This is
        // also what keeps `start(destination)` byte-identical to what it sent before the
        // channel had options.
        for (options in listOf(null, CalloutOptions(), CalloutOptions(languages = emptyList()))) {
            val transport = script()
            val handle = callout(transport).start("+37112345678", options)
            handle.submit("123456")
            handle.states.toList()

            val create = transport.requests[0].body!!
            // The block key, not the bare word: `"delivery_method":"callout"` is always there.
            assertFalse("options=$options -> $create", create.contains("\"callout\":{"))
        }
    }

    @Test
    fun `the language the announcement is played in reaches AwaitingInput`() = runTest {
        val transport = FakeTransport(created(Fixtures.pendingCallout(language = "pt-BR")))
        val handle = callout(transport).start(
            "+37112345678",
            CalloutOptions(languages = listOf("pt-BR")),
        )

        val awaiting = handle.states.first { it is VerificationState.AwaitingInput }
            as VerificationState.AwaitingInput
        assertEquals("pt-BR", awaiting.callout?.language)
    }

    @Test
    fun `a fallback is visible, because the server reports what it chose rather than what was asked`() = runTest {
        // ka-GE has an SMS template and no announcement audio, so the API accepts it and
        // announces in en-US. Comparing the two is the only way a host can tell, which is
        // why the chosen tag has to survive all the way to the state.
        val transport = FakeTransport(created(Fixtures.pendingCallout(language = "en-US")))
        val handle = callout(transport).start(
            "+37112345678",
            CalloutOptions(languages = listOf("ka-GE")),
        )

        val awaiting = handle.states.first { it is VerificationState.AwaitingInput }
            as VerificationState.AwaitingInput
        assertEquals("en-US", awaiting.callout?.language)
    }

    @Test
    fun `callout is the channel discriminator, block or no block`() = runTest {
        // Non-null exactly when the verification is a callout, even for a response that
        // carried no block at all — otherwise `callout != null` would be a statement about
        // which optional keys arrived rather than about the channel.
        val transport = FakeTransport(created(Fixtures.pending("callout")))
        val handle = callout(transport).start("+37112345678")

        val awaiting = handle.states.first { it is VerificationState.AwaitingInput }
            as VerificationState.AwaitingInput
        assertNull(awaiting.callout?.language)
        assertTrue("callout must be non-null on a callout verification", awaiting.callout != null)
        assertNull("sms must stay null on a callout verification", awaiting.sms)
    }

    @Test
    fun `resume sends no channel block, because languages are a create-time choice`() = runTest {
        val transport = FakeTransport(
            ok(Fixtures.pendingCallout()),
            ok(Fixtures.verified(method = "callout")),
        )
        val handle = callout(transport).resume("+37112345678")
        handle.submit("123456")
        handle.states.toList()

        assertEquals("GET", transport.requests[0].method)
        assertNull(transport.requests[0].body)
    }

    @Test
    fun `this channel registers nothing and costs no permission`() = runTest {
        val before = shadowOf(app).registeredReceivers.size
        val transport = script()
        val handle = callout(transport).start("+37112345678")
        handle.submit("123456")
        handle.states.toList()

        assertEquals(before, shadowOf(app).registeredReceivers.size)
    }
}
