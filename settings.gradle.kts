pluginManagement {
    // Inside pluginManagement, NOT a bare includeBuild in the body: build-logic supplies a
    // PLUGIN, and a plugin has to be on the settings classpath before any project is
    // evaluated. A plain includeBuild resolves too late and the plugin id is not found.
    includeBuild("build-logic")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "didww-verification-android-sdk"

// Three published libraries.
//
// It was four. `verification-callout` was folded into `verification-core` before the first
// release, because measurement showed it cost nothing to fold: it declared no dependency
// beyond core, and contributed no merged-manifest element. Everything
// `docs/manifest-cost.md` measured — the content provider, the exported receiver, the whole
// androidx chain — enters through `verification-sms` and nowhere else, so the split that
// measurement justifies is one bit wide: does this host use SMS.
include(":verification-core")
include(":verification-sms")
include(":verification-all")

// Never published. `:manifest-probe` is the subject of the merged-manifest golden —
// deliberately not `:sample`, whose own UI churn would mix into it.
include(":sample")
include(":manifest-probe")
