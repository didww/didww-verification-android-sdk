plugins {
    id("didww.library")
}

android {
    namespace = "com.didww.android.sdk.verification.sms"
}

dependencies {
    api(project(":verification-core"))

    // This module is the whole reason the SDK ships as three artifacts: it is the only one
    // that pulls Play Services, and with it a content provider, an exported receiver and
    // the androidx fragment/activity/lifecycle chain. See README.md for the measured cost.
    implementation(libs.play.services.auth.api.phone)

    // Declared EXPLICITLY rather than inherited transitively via play-services-base. The
    // 6-arg ContextCompat.registerReceiver overload is a compile-time dependency, and
    // letting it arrive transitively means a Play Services graph change can remove it
    // silently — leaving the 4-arg form, which compiles and drops the sender-permission
    // guard.
    implementation(libs.androidx.core)
}
