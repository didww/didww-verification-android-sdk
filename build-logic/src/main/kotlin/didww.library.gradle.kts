import com.android.build.gradle.LibraryExtension
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Everything the three published library modules share.
 *
 * A single precompiled script plugin rather than three build files that drift invisibly, and
 * rather than `subprojects {}` — which would lose the type-safe `android { }` accessor in
 * the Kotlin DSL and apply to `:sample` and `:manifest-probe` as well, neither of which is
 * a library.
 *
 * Each module keeps only what is genuinely its own: its `namespace` and its dependencies.
 */

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

// Coordinates live here once, so all three artifacts version together. Three modules drifting
// to different versions is a support problem that cannot be undone once published, and these
// modules are meaningless apart.
group = "com.didww.android.sdk.verification"
version = "1.0.0"

mavenPublishing {
    // AGP's own javadoc task embeds a Dokka that cannot resolve KDoc links across module
    // boundaries on this Kotlin version. Measured, not assumed: `verification-core` documents
    // cleanly, while `verification-sms` and `verification-all` — the two that reference types
    // from a dependency module — both fail in `MarkdownParser.resolveDRI`. Central only
    // requires that a javadoc artifact exist, and for a Kotlin library the sources jar is the
    // documentation anyone actually reads, so an empty one is attached below instead.
    configure(
        com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = false,
        ),
    )

    // The upload is left pending in the portal rather than released automatically. A published
    // version is permanent — it cannot be deleted, replaced or amended — so the last look
    // happens with a human in front of it.
    publishToMavenCentral(automaticRelease = false)

    // Signing only when a key is actually configured, which is never in the repository and
    // always in the Gradle user home on a release machine.
    //
    // Unconditional signing makes `publishToMavenLocal` fail for everyone else with "no
    // configured signatory", so nobody can inspect what a release would contain without first
    // owning the release key. That is the wrong trade: verifying the artifact set is exactly
    // the check most worth running before an upload that cannot be undone.
    //
    // The failure mode this accepts — a release machine with no key uploads unsigned artifacts
    // — is caught by Central's own validation, which rejects the deployment before anything
    // becomes public.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name.set(project.name)
        description.set("DIDWW phone-number verification SDK for Android")
        url.set("https://github.com/didww/didww-verification-android-sdk")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("didww")
                name.set("DIDWW")
                url.set("https://didww.com")
            }
        }
        scm {
            url.set("https://github.com/didww/didww-verification-android-sdk")
            connection.set("scm:git:https://github.com/didww/didww-verification-android-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/didww/didww-verification-android-sdk.git")
        }
    }
}

// TRAP 3 of 3, and the one that makes newcomers abandon convention plugins entirely:
// THE VERSION CATALOG IS NOT VISIBLE INSIDE A PRECOMPILED SCRIPT PLUGIN. `libs.foo` does
// not resolve here, however correctly the catalog is declared in settings.gradle.kts —
// the generated accessors simply do not exist in this compilation unit. Reaching it
// through VersionCatalogsExtension is the supported way.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun version(name: String): String = libs.findVersion(name).get().requiredVersion

fun library(name: String): Any = libs.findLibrary(name).get()

configure<LibraryExtension> {
    compileSdk = version("compileSdk").toInt()

    defaultConfig {
        minSdk = version("minSdk").toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    // The Auth.Basic warning keys off the HOST's ApplicationInfo.FLAG_DEBUGGABLE, because
    // a library's own BuildConfig.DEBUG is always false in a published AAR. Not generating
    // BuildConfig makes the wrong mechanism unavailable rather than merely discouraged.
    buildFeatures {
        buildConfig = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true

        // `--offline` alone is not enough: Robolectric resolves its android-all
        // runtime through its own Maven client, outside Gradle, so a build Gradle thinks
        // is offline still opens sockets. Two system properties are what actually make the
        // test run network-free.
        //
        // They are supplied CONDITIONALLY, and that is the whole subtlety. Setting them
        // unconditionally made a fresh clone unbootstrappable: with nothing staged yet,
        // Robolectric was told to look offline in an empty directory and failed, on every
        // machine that had never run these tests before. See `vendorRobolectricRuntime` for
        // why nothing else could populate that directory first.
        //
        // A CommandLineArgumentProvider rather than `systemProperty`, because the answer is
        // only known at EXECUTION time — `vendorRobolectricRuntime` runs first and may have
        // just created the thing being tested for. Deciding at configuration time would
        // read the directory one build too early, and would be a configuration-cache input
        // besides.
        unitTests.all { test ->
            test.dependsOn(":vendorRobolectricRuntime")
            test.jvmArgumentProviders.add(
                objects.newInstance(RobolectricRuntimeArgs::class.java).apply {
                    runtimeDir.set(rootProject.layout.projectDirectory.dir("robolectric-runtime"))
                },
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Shared test-only sources. NOT `testFixtures`: AGP creates that variant and even a
    // compileDebugTestFixturesJavaWithJavac task, but this AGP/KGP pair creates no
    // compileDebugTestFixturesKotlin — Kotlin fixtures are silently never compiled while
    // the build stays green.
    sourceSets.getByName("test").java.srcDir(
        rootProject.file("verification-core/src/testSupport/kotlin"),
    )
}

configure<KotlinAndroidProjectExtension> {
    explicitApi()
    jvmToolchain(version("javaVersion").toInt())
}

dependencies {
    add("testImplementation", library("junit"))
    add("testImplementation", library("robolectric"))
    add("testImplementation", library("kotlinx-coroutines-test"))
}

/**
 * Pins Robolectric to the locally staged runtime — but only once there is one.
 *
 * Declared with a `DirectoryProperty` and nothing else. A task or provider class declared
 * inside a `.gradle.kts` that touches a bare Project property (`rootDir`, `layout`,
 * `logger`) captures the enclosing script, and Gradle then refuses to instantiate it with
 * the thoroughly misleading "non-static inner class".
 *
 * `@InputFiles` rather than `@Internal`: whether the directory is empty is precisely what
 * decides the arguments, so it is a real input, and the test task must re-run when the
 * first staged jar turns an online run into an offline one.
 */
abstract class RobolectricRuntimeArgs : CommandLineArgumentProvider {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val runtimeDir: DirectoryProperty

    override fun asArguments(): Iterable<String> {
        val dir = runtimeDir.get().asFile
        val staged = dir.listFiles { file ->
            file.isFile && file.name.startsWith("android-all") && file.extension == "jar"
        }
        // Nothing staged: say nothing. Robolectric then resolves the runtime its own way,
        // over the network, which on a fresh clone is the only thing that can work.
        if (staged.isNullOrEmpty()) return emptyList()
        return listOf(
            "-Drobolectric.offline=true",
            "-Drobolectric.dependency.dir=${dir.absolutePath}",
        )
    }
}

// Maven Central rejects a deployment with no javadoc artifact, so one is attached explicitly
// now that AGP's generator is switched off above. Empty by design rather than by accident:
// the `-sources.jar` carries every KDoc comment, which is what a Kotlin integrator reads.
val emptyJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

configure<PublishingExtension> {
    publications.withType<MavenPublication>().configureEach {
        artifact(emptyJavadocJar)
    }
}
