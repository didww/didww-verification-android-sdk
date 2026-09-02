plugins {
    id("didww.library")
}

android {
    namespace = "com.didww.android.sdk.verification.all"
}

dependencies {
    // Umbrella. `api` on both so a host depending only on verification-all also sees the
    // channel classes and the state model. Callout lives in core.
    api(project(":verification-core"))
    api(project(":verification-sms"))
}
