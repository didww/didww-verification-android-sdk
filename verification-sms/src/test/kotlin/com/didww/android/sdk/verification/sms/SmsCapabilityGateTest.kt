package com.didww.android.sdk.verification.sms

import android.content.Intent
import android.content.pm.Signature
import com.didww.android.sdk.verification.Auth
import com.didww.android.sdk.verification.Config
import com.didww.android.sdk.verification.DidwwInternalApi
import com.didww.android.sdk.verification.Environment
import com.didww.android.sdk.verification.VerificationEngine
import com.didww.android.sdk.verification.VerificationState
import com.didww.android.sdk.verification.testing.FakeTransport
import com.didww.android.sdk.verification.testing.Fixtures
import com.didww.android.sdk.verification.testing.created
import com.didww.android.sdk.verification.testing.ok
import com.google.android.gms.auth.api.phone.SmsRetriever
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The one gating mechanism, exercised from both sides **in the default test run**,
 * with no source edit and no flag to flip.
 *
 * That is the whole point of putting the gate on the wire rather than in a `const val`:
 * a compile-time constant is inlined at every call site, so a test compiled against the
 * same source can never see the other value, and the guarded branch is unreachable dead
 * code that no test can reach.
 */
@OptIn(DidwwInternalApi::class)
@RunWith(RobolectricTestRunner::class)
class SmsCapabilityGateTest {

    private val app get() = RuntimeEnvironment.getApplication()

    /**
     * Robolectric ships a package with no signing certificate at all, and `AppHash` then
     * correctly declines to compute a hash. Give the test package a certificate so the
     * gate has something real to compare.
     */
    @Before
    fun signTestPackage() {
        shadowOf(app.packageManager)
            .getInternalMutablePackageInfo(app.packageName)
            .signatures = arrayOf(Signature(byteArrayOf(0x30, 0x82.toByte(), 0x02, 0x53)))
    }

    private fun sms(transport: FakeTransport) = SmsVerification(
        VerificationEngine(
            context = app,
            auth = Auth.Public("app-key"),
            environment = Environment.Custom("https://verification.example"),
            config = Config(),
            transport = transport,
        ),
    )

    private fun retrieverReceivers() = shadowOf(app).registeredReceivers.filter { wrapper ->
        (0 until wrapper.intentFilter.countActions())
            .any { wrapper.intentFilter.getAction(it) == SmsRetriever.SMS_RETRIEVED_ACTION }
    }

    // -------------------------------------------------------------------- dormant

    @Test
    fun `without the capability echo, a full verification completes and Play Services is never touched`() = runTest {
        val transport = FakeTransport(created(Fixtures.pendingSms()), ok(Fixtures.verified()))
        val handle = sms(transport).start("+37112345678")

        handle.submit("123456")
        val states = handle.states.toList()

        assertEquals("Verified", states.last()::class.simpleName)
        assertTrue(
            "no SMS_RETRIEVED_ACTION receiver may be registered when the gate is closed",
            retrieverReceivers().isEmpty(),
        )
    }

    @Test
    fun `the app hash is sent on every verification, capability or not`() = runTest {
        // Sent unconditionally so that the day the backend starts persisting it, every
        // already-installed copy of this SDK is already supplying it — no release needed.
        val transport = FakeTransport(created(Fixtures.pendingSms()), ok(Fixtures.verified()))
        val handle = sms(transport).start("+37112345678")
        handle.submit("123456")
        handle.states.toList()

        assertTrue(transport.requests.first().body!!.contains("\"app_hash\""))
    }

    @Test
    fun `an echo that does not match what was sent keeps the gate closed`() = runTest {
        // The echo has to be the persisted value, so a mismatch means the row does not
        // carry our hash and the dispatcher will not append it.
        val transport = FakeTransport(
            created(Fixtures.pendingSms(appHash = "NOTOURHASH")),
            ok(Fixtures.verified()),
        )
        val handle = sms(transport).start("+37112345678")
        handle.submit("123456")
        handle.states.toList()

        assertTrue(retrieverReceivers().isEmpty())
    }

    // --------------------------------------------------------------------- active

    @Test
    fun `with a matching echo the Retriever is armed and a delivered code is auto-submitted`() = runTest {
        val ourHash = AppHash.compute(app)!!
        val transport = FakeTransport(
            created(Fixtures.pendingSms(appHash = ourHash)),
            ok(Fixtures.verified()),
        )
        val handle = sms(transport).start("+37112345678")

        val states = mutableListOf<VerificationState>()
        val job = launch { handle.states.toList(states) }
        // runCurrent(), NOT advanceUntilIdle(): the verification arms a withTimeoutOrNull for
        // its own expires_at, so advancing virtual time to idle fast-forwards straight past
        // the deadline, expires the verification and tears the receiver down again — the
        // assertion below then fails for a reason that has nothing to do with the gate.
        runCurrent()

        val receivers = retrieverReceivers()
        assertEquals("the gate is open, so exactly one receiver must be registered", 1, receivers.size)

        // Delivered straight to the receiver rather than through sendBroadcast: the
        // receiver is registered WITH SmsRetriever.SEND_PERMISSION, and asserting on
        // Robolectric's permission emulation would be testing Robolectric.
        receivers.single().broadcastReceiver.onReceive(
            app,
            Intent(SmsRetriever.SMS_RETRIEVED_ACTION)
                .putExtra(SmsRetriever.EXTRA_STATUS, successStatus())
                .putExtra(SmsRetriever.EXTRA_SMS_MESSAGE, Fixtures.deliveredBody("123456", ourHash)),
        )
        runCurrent()
        job.join()

        assertEquals(
            listOf("Starting", "AwaitingInput", "Captured", "Submitting", "Verified"),
            states.map { it::class.simpleName },
        )
        assertEquals("123456", (states[2] as VerificationState.Captured).value)
        assertTrue(transport.requests[1].body!!.contains("\"code\":\"123456\""))
    }

    @Test
    fun `the armed receiver is unregistered when the verification ends`() = runTest {
        val ourHash = AppHash.compute(app)!!
        val transport = FakeTransport(
            created(Fixtures.pendingSms(appHash = ourHash)),
            ok(Fixtures.verified()),
        )
        val handle = sms(transport).start("+37112345678")
        handle.submit("123456")

        handle.states.toList()

        assertTrue(
            "teardown must run when the flow completes, not only when it is cancelled",
            retrieverReceivers().isEmpty(),
        )
    }

    private fun successStatus() = com.google.android.gms.common.api.Status(
        com.google.android.gms.common.api.CommonStatusCodes.SUCCESS,
    )
}
