plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.binary.compatibility.validator)
    base
}

apiValidation {
    // Neither is published, so neither has a public surface worth freezing.
    ignoredProjects.addAll(listOf("sample", "manifest-probe"))

    // Anything annotated @DidwwInternalApi is a cross-module seam, not public API, and
    // must never enter the committed dump. That answers the standing objection to opt-in
    // markers — that the marker itself becomes public surface: the marker exists, but
    // nothing behind it is frozen or discoverable as API.
    nonPublicMarkers.add("com.didww.android.sdk.verification.DidwwInternalApi")
}

apply(from = "gradle/guards.gradle.kts")
