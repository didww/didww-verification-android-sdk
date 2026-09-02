plugins {
    id("didww.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.didww.android.sdk.verification.core"
}

dependencies {
    // These two and NOTHING else. No OkHttp, no Retrofit, no Play Services.
    // `api` rather than `implementation` because Flow and the wire DTOs are both part of
    // the public surface. Enforced below, not merely intended.
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
}

// ---------------------------------------------------------------------------------------
// verification-core gets coroutines and serialization, and nothing else.
//
// An ALLOWLIST, not a denylist. A denylist has to enumerate every HTTP client that might
// ever be added, which is unwriteable — the whole risk is a dependency nobody thought of.
//
// The list below was DERIVED FROM A REAL RESOLUTION, not hand-written. A hand-written one
// would have named the two obvious artifacts and then failed on day one against the BOMs,
// the three Gradle-metadata `-jvm` components, kotlin-stdlib and org.jetbrains:annotations
// — all of which appear as separate components in the resolution result. A guard that
// fails on its first run for bookkeeping reasons is a guard that gets deleted.
//
// Compared on `group:name` only, never versions: a version-bearing allowlist false-fails
// on every routine bump and gets loosened to make the noise stop.
// ---------------------------------------------------------------------------------------
abstract class CoreDependencyAllowlistGuard : DefaultTask() {

    @get:Input
    abstract val resolved: SetProperty<String>

    @get:Input
    abstract val allowed: SetProperty<String>

    @TaskAction
    fun verify() {
        val actual = resolved.get()
        if (actual.isEmpty()) {
            throw GradleException(
                "resolved no dependencies at all. Either the configuration name is wrong or " +
                    "the graph is empty; both mean this guard is not checking anything.",
            )
        }
        val unexpected = (actual - allowed.get()).sorted()
        if (unexpected.isNotEmpty()) {
            throw GradleException(
                "verification-core is depended on by every channel module and therefore by " +
                    "every integrator. It may not grow a dependency:\n" +
                    unexpected.joinToString("\n") { "  $it" },
            )
        }
        logger.lifecycle("coreDependencyAllowlist: ${actual.size} components, all expected")
    }
}

val coreDependencyAllowlist by tasks.registering(CoreDependencyAllowlistGuard::class) {
    group = "verification"
    description = "Fails if verification-core's runtime closure grows beyond coroutines + serialization"
    // Empty unless the variant hook below fills it in, so a hook that silently stops
    // firing fails the guard instead of quietly passing it.
    resolved.convention(emptySet())
    allowed.set(
        setOf(
            "org.jetbrains.kotlinx:kotlinx-coroutines-core",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
            "org.jetbrains.kotlinx:kotlinx-coroutines-bom",
            "org.jetbrains.kotlinx:kotlinx-serialization-json",
            "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm",
            "org.jetbrains.kotlinx:kotlinx-serialization-core",
            "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm",
            "org.jetbrains.kotlinx:kotlinx-serialization-bom",
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains:annotations",
        ),
    )
}

// AGP creates variant configurations lazily, so `releaseRuntimeClasspath` does not exist
// while this script is being evaluated. The variant callback is the supported way in.
androidComponents {
    onVariants(selector().withName("release")) { variant ->
        val coordinates = variant.runtimeConfiguration
            // Resolving here in the build script would break the configuration cache;
            // going through the Provider keeps resolution at execution time.
            .incoming.resolutionResult.rootComponent
            .map { root ->
                val found = sortedSetOf<String>()
                val visited = mutableSetOf<org.gradle.api.artifacts.component.ComponentIdentifier>()
                fun walk(node: org.gradle.api.artifacts.result.ResolvedComponentResult) {
                    if (!visited.add(node.id)) return
                    (node.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)
                        ?.let { found += "${it.group}:${it.module}" }
                    node.dependencies
                        .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                        .forEach { walk(it.selected) }
                }
                walk(root)
                found.toSet()
            }
        coreDependencyAllowlist.configure { resolved.set(coordinates) }
    }
}

tasks.named("check") { dependsOn(coreDependencyAllowlist) }
