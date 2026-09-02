package com.didww.android.sdk.verification.sample

import android.content.Context
import com.didww.android.sdk.verification.Auth
import com.didww.android.sdk.verification.DeliveryMethod
import com.didww.android.sdk.verification.Environment

/** Declaration order is the order the picker offers them, so the default comes first. */
enum class EnvironmentChoice(val label: String) {
    SANDBOX("Sandbox"),
    PRODUCTION("Production"),
    CUSTOM("Custom URL"),
}

enum class AuthChoice(val label: String) {
    PUBLIC("Public (application key)"),
    BASIC("Basic (key + secret)"),
}

/**
 * Everything the demo needs to build a `DidwwVerification`, plus the last destination typed
 * so a re-run is one tap.
 *
 * Stored in plain `SharedPreferences`. The iOS demo puts its credentials in the Keychain;
 * the Android analogue, `androidx.security:security-crypto`, is deprecated and unmaintained,
 * and this holds a sandbox application key on a local emulator. Encrypting it would buy
 * nothing and add a dead dependency to a catalog whose header forbids exactly that.
 */
data class DemoSettings(
    val environmentChoice: EnvironmentChoice = EnvironmentChoice.SANDBOX,
    val customUrl: String = DEFAULT_CUSTOM_URL,
    val authChoice: AuthChoice = AuthChoice.PUBLIC,
    val appKey: String = "",
    val secret: String = "",
    val lastDestination: String = "",
    val languages: String = "",
    val methodName: String = DeliveryMethod.SMS.name,
) {

    fun lastMethod(): DeliveryMethod =
        DeliveryMethod.entries.firstOrNull { it.name == methodName } ?: DeliveryMethod.SMS

    fun environmentSummary(): String = when (environmentChoice) {
        EnvironmentChoice.CUSTOM -> customUrl
        EnvironmentChoice.SANDBOX -> "Sandbox"
        EnvironmentChoice.PRODUCTION -> "Production"
    }

    /**
     * Normalised the same way the SDK normalises it internally — `Environment.baseUrl`
     * applies `trimEnd('/')`. Doing it here too means two URLs the SDK cannot tell apart
     * (`…:3000` and `…:3000/`) are also equal to *this* class, which is what lets
     * [FlowViewModel] key its client cache on this value without a trailing slash silently
     * producing a second engine.
     */
    fun toEnvironment(): Environment = when (environmentChoice) {
        EnvironmentChoice.CUSTOM -> Environment.Custom(customUrl.trim().trimEnd('/'))
        EnvironmentChoice.SANDBOX -> Environment.Sandbox
        EnvironmentChoice.PRODUCTION -> Environment.Production
    }

    /** The key as the SDK will actually receive it — trimmed. */
    fun normalizedAppKey(): String = appKey.trim()

    fun toAuth(): Auth = when (authChoice) {
        AuthChoice.PUBLIC -> Auth.Public(normalizedAppKey())
        AuthChoice.BASIC -> Auth.Basic(normalizedAppKey(), secret)
    }

    /**
     * Comma-separated in the UI because `SmsOptions.languages` and `CalloutOptions.languages`
     * are both lists — and both take the same tags, so one field feeds either channel.
     */
    fun toLanguages(): List<String>? = languages
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .ifEmpty { null }

    val isStartable: Boolean
        get() = appKey.isNotBlank() &&
            (authChoice != AuthChoice.BASIC || secret.isNotBlank()) &&
            (environmentChoice != EnvironmentChoice.CUSTOM || customUrl.isNotBlank())

    companion object {
        /**
         * `10.0.2.2`, not `localhost`. Inside an emulator `localhost` is the emulator, so a
         * backend running on the host machine is unreachable by that name. `10.0.2.2` is
         * the emulator's standing alias for the host's loopback interface.
         *
         * A real handset has no such alias — nothing is routed over the USB cable by
         * default, and the host's loopback is by definition not on the network. The
         * equivalent there is `adb reverse tcp:3000 tcp:3000`, which makes the device's own
         * `localhost:3000` arrive at the host's port 3000; the URL becomes
         * `http://localhost:3000`. Both hosts are permitted by
         * `res/xml/network_security_config.xml`, so either path works with no rebuild.
         */
        const val DEFAULT_CUSTOM_URL = "http://10.0.2.2:3000"
    }
}

class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("didww-demo-settings", Context.MODE_PRIVATE)

    fun load(): DemoSettings = DemoSettings(
        environmentChoice = prefs.getString(KEY_ENV, null)
            ?.let { name -> EnvironmentChoice.entries.firstOrNull { it.name == name } }
            ?: EnvironmentChoice.SANDBOX,
        customUrl = prefs.getString(KEY_URL, null) ?: DemoSettings.DEFAULT_CUSTOM_URL,
        authChoice = prefs.getString(KEY_AUTH, null)
            ?.let { name -> AuthChoice.entries.firstOrNull { it.name == name } }
            ?: AuthChoice.PUBLIC,
        appKey = prefs.getString(KEY_APP_KEY, null).orEmpty(),
        secret = prefs.getString(KEY_SECRET, null).orEmpty(),
        lastDestination = prefs.getString(KEY_DESTINATION, null).orEmpty(),
        languages = prefs.getString(KEY_LANGUAGES, null).orEmpty(),
        methodName = prefs.getString(KEY_METHOD, null) ?: DeliveryMethod.SMS.name,
    )

    fun save(settings: DemoSettings) {
        prefs.edit()
            .putString(KEY_ENV, settings.environmentChoice.name)
            .putString(KEY_URL, settings.customUrl)
            .putString(KEY_AUTH, settings.authChoice.name)
            .putString(KEY_APP_KEY, settings.appKey)
            .putString(KEY_SECRET, settings.secret)
            .putString(KEY_DESTINATION, settings.lastDestination)
            .putString(KEY_LANGUAGES, settings.languages)
            .putString(KEY_METHOD, settings.methodName)
            .apply()
    }

    private companion object {
        const val KEY_ENV = "environment"
        const val KEY_URL = "custom_url"
        const val KEY_AUTH = "auth"
        const val KEY_APP_KEY = "app_key"
        const val KEY_SECRET = "secret"
        const val KEY_DESTINATION = "destination"
        const val KEY_LANGUAGES = "languages"
        const val KEY_METHOD = "method"
    }
}
