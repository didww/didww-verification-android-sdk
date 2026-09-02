package com.didww.android.sdk.verification

import android.content.pm.ApplicationInfo
import com.didww.android.sdk.verification.testing.FakeTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLog

/**
 * The `Auth.Basic` warning, and specifically its *mechanism*.
 *
 * The obvious implementation is `if (BuildConfig.DEBUG)`, and it is silently useless: a
 * library's `BuildConfig.DEBUG` is fixed when the library is built, a published AAR is
 * built in release, so it is `false` in every host — the warning would never fire for
 * anybody, and nothing would ever reveal that.
 *
 * These two tests fail if anyone swaps the mechanism back, because they drive the *host's*
 * debuggable flag and nothing else.
 */
@OptIn(DidwwInternalApi::class)
@RunWith(RobolectricTestRunner::class)
class BasicAuthWarningTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun captureLogs() {
        ShadowLog.clear()
        ShadowLog.stream = null
    }

    private fun setHostDebuggable(debuggable: Boolean) {
        val info = app.applicationInfo
        info.flags = if (debuggable) {
            info.flags or ApplicationInfo.FLAG_DEBUGGABLE
        } else {
            info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        }
    }

    private fun buildEngine(auth: Auth) = VerificationEngine(
        context = app,
        auth = auth,
        environment = Environment.Custom("https://verification.example"),
        config = Config(),
        transport = FakeTransport(),
    )

    private fun warnings() = ShadowLog.getLogsForTag(VerificationEngine.LOG_TAG)
        .filter { it.type == android.util.Log.WARN }

    @Test
    fun `basic auth in a non-debuggable host warns`() {
        setHostDebuggable(false)
        buildEngine(Auth.Basic("key", "secret"))

        assertEquals(1, warnings().size)
        assertTrue(warnings().single().msg.contains("Auth.Basic"))
    }

    @Test
    fun `basic auth in a debuggable host stays quiet`() {
        setHostDebuggable(true)
        buildEngine(Auth.Basic("key", "secret"))

        assertTrue(warnings().isEmpty())
    }

    @Test
    fun `application auth never warns, debuggable or not`() {
        setHostDebuggable(false)
        buildEngine(Auth.Public("app-key"))
        assertTrue(warnings().isEmpty())
    }
}
