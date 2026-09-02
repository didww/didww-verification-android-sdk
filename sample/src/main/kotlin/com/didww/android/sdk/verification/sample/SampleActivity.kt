package com.didww.android.sdk.verification.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Minimal integrator, written the way a host actually would.
 *
 * Its job is not to be a beautiful UI. It is to exercise the SDK's public surface from the
 * outside — every state the state machine can reach — and to be a **real R8 subject**: this
 * module builds a minified release variant, so the SDK's consumer ProGuard rules and its
 * whole dependency closure are shrunk exactly as they would be in a customer's app. Nothing
 * else in the build exercises that; the unit tests run on a desktop JVM with no R8 in sight.
 *
 * Note what is absent: no `Activity` is ever handed to the SDK. The verification lives in a
 * `ViewModel` and is built from the application `Context`, so rotating the device changes
 * nothing about it.
 *
 * See `sample/README.md` for how to run this and how to reach each state deliberately.
 */
class SampleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // From API 35 an app targeting the current SDK is laid out edge to edge by
                // default, so without this the first heading sits underneath the status bar
                // clock. safeDrawingPadding() insets for the status bar, the navigation bar
                // and the IME together.
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) { SampleApp() }
            }
        }
    }
}

private enum class Screen { CONFIG, START, CODE }

@Composable
private fun SampleApp(viewModel: FlowViewModel = viewModel()) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }

    // Written through on every edit rather than only on Save, so an in-progress edit
    // survives a rotation, and the force-stop check needs no separate ceremony.
    var settings by remember { mutableStateOf(store.load()) }
    val update: (DemoSettings) -> Unit = { updated ->
        settings = updated
        store.save(updated)
    }

    // A first launch with nothing saved lands on Configuration; afterwards it goes
    // straight to the start screen.
    // Enums are Serializable, so rememberSaveable stores one directly — no .name/valueOf.
    var screen by rememberSaveable {
        mutableStateOf(if (settings.appKey.isBlank()) Screen.CONFIG else Screen.START)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val secondCollectorVerdict by viewModel.secondCollectorVerdict.collectAsStateWithLifecycle()
    val started by viewModel.started.collectAsStateWithLifecycle()

    // The ViewModel outlives the Activity, so after a rotation mid-verification the app
    // must come back to the code screen rather than to whatever `screen` was restored to.
    val effective = if (started) Screen.CODE else screen

    // Without this, Back finishes the Activity, which clears the ViewModel and abandons a
    // live verification with no way to get back to it. Within the app Back means "up one
    // screen"; only the start screen lets it fall through and exit.
    BackHandler(enabled = effective != Screen.START) {
        if (effective == Screen.CODE) viewModel.reset()
        screen = Screen.START
    }

    when (effective) {
        Screen.CONFIG -> ConfigScreen(
            settings = settings,
            onChange = update,
            onSave = { screen = Screen.START },
        )

        Screen.START -> StartScreen(
            settings = settings,
            onChange = update,
            onStart = { destination, method ->
                viewModel.start(settings, destination, method)
            },
            onOpenConfig = { screen = Screen.CONFIG },
        )

        Screen.CODE -> CodeScreen(
            state = state,
            log = log,
            secondCollectorVerdict = secondCollectorVerdict,
            onSubmit = viewModel::submit,
            // A resend is start() again with the same settings, which is what makes the
            // previous handle report Superseded.
            onResend = { viewModel.start(settings, settings.lastDestination.trim(), settings.lastMethod()) },
            onCollectASecondTime = viewModel::collectASecondTime,
            onBack = {
                viewModel.reset()
                screen = Screen.START
            },
        )
    }
}
