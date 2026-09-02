plugins {
    // Turns src/main/kotlin/*.gradle.kts into precompiled script plugins whose id is the
    // file name minus the .gradle.kts suffix.
    `kotlin-dsl`
}

dependencies {
    // TRAP 2 of 3 — a precompiled script plugin cannot `apply` what is not on its own
    // compile classpath. Declaring the plugins in the version catalog is not enough;
    // build-logic needs the plugin ARTIFACTS here, or `id("com.android.library")` inside
    // didww.library.gradle.kts fails to resolve with a message that points nowhere useful.
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.maven.publish.plugin)
}
