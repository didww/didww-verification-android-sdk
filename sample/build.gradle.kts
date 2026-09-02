plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// NEVER PUBLISHED. Every channel end to end against a configurable backend, with an
// R8-enabled release variant so the modules' consumer ProGuard rules are actually
// exercised — a debug build never reveals a missing serialization keep rule.
//
// This module is also the only place in the repository that depends on Compose. Those
// pins live in the version catalog under a `:sample` ONLY comment for that reason.

android {
    namespace = "com.didww.android.sdk.verification.sample"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.didww.android.sdk.verification.sample"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))

            // SAMPLE ONLY, and never do this in a real app.
            //
            // Without a signing config `assembleRelease` still succeeds but emits an
            // UNSIGNED apk, so `installRelease` fails at the adb step — which would make
            // the R8 path unrunnable on a device and quietly leave the consumer ProGuard
            // rules untested, the one thing this variant exists to test.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(libs.versions.javaVersion.get().toInt())
}

dependencies {
    implementation(project(":verification-all"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
}
