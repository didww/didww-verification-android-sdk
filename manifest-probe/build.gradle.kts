plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// NEVER PUBLISHED. This module exists for exactly one reason: it is the subject of
// the merged-manifest golden. It declares nothing of its own and depends only on
// verification-all, so its merged manifest IS the answer to "what does an integrator
// inherit by adopting this SDK?" — with zero contamination from anything we wrote.
//
// Deliberately not :sample. A sample app has its own activities, its own permissions
// and its own churn; a golden taken against it would mix SDK cost with sample-UI noise
// and would be edited every time the sample changed.

android {
    namespace = "com.didww.android.sdk.verification.probe"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.didww.android.sdk.verification.probe"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
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
}

// ---------------------------------------------------------------------------------------
// What an integrator's merged manifest inherits, frozen.
//
// The subject is this module precisely because it declares NOTHING of its own and depends
// only on verification-all, so its merged manifest IS the SDK's manifest cost. A control
// build with the dependency line removed merges nothing at all, which is what makes that
// claim checkable rather than assumed.
//
// Obtained through SingleArtifact.MERGED_MANIFEST, never a hardcoded
// build/intermediates/... path: that layout is AGP-internal, it has already moved within
// 8.x, and a stale path would silently match no file and pass.
// ---------------------------------------------------------------------------------------
abstract class ManifestGoldenGuard : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val golden: RegularFileProperty

    @TaskAction
    fun verify() {
        val manifest = mergedManifest.get().asFile
        if (!manifest.isFile || manifest.length() == 0L) {
            throw GradleException("merged manifest at ${manifest.path} is missing or empty")
        }

        val actual = extract(manifest)
        if (actual.isEmpty()) {
            throw GradleException(
                "extracted nothing from the merged manifest. Either the format changed or " +
                    "the extraction is broken; both mean this guard is checking nothing.",
            )
        }

        val goldenFile = golden.get().asFile
        val expected = goldenFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSortedSet()

        val added = (actual - expected).toList()
        val removed = (expected - actual).toList()
        if (added.isNotEmpty() || removed.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("the SDK's manifest cost changed. That cost is not just about")
                    appendLine("permissions — an activity, a provider, a receiver or a queries entry")
                    appendLine("is a cost every integrator pays too.")
                    if (added.isNotEmpty()) {
                        appendLine("  NEWLY INHERITED:")
                        added.forEach { appendLine("    + $it") }
                    }
                    if (removed.isNotEmpty()) {
                        appendLine("  NO LONGER PRESENT:")
                        removed.forEach { appendLine("    - $it") }
                    }
                    appendLine("If this is intended, update ${goldenFile.name} deliberately.")
                },
            )
        }
        logger.lifecycle("manifestGolden: ${actual.size} inherited manifest entries, unchanged")
    }

    /**
     * Enumerates BY POSITION — every direct child of `<manifest>`, every direct child of
     * `<application>`, every child of `<queries>` — rather than by an allowlist of element
     * types. A type allowlist would silently pass a future Play Services bump that
     * introduces a `<service>`, a `<permission>` or a `<uses-feature>`. Positional
     * extraction fails on anything new, which is the whole point.
     *
     * Only the element path and `android:name` are kept. AGP injects `versionCode`,
     * `versionName`, `debuggable` and `platformBuildVersionCode`, and rewrites `<uses-sdk>`,
     * so a raw XML diff churns on every AGP bump and gets deleted the first time it
     * false-fails.
     */
    private fun extract(file: java.io.File): java.util.SortedSet<String> {
        val ns = "http://schemas.android.com/apk/res/android"
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
        val root = document.documentElement
        val entries = sortedSetOf<String>()

        fun childElements(node: org.w3c.dom.Element): List<org.w3c.dom.Element> =
            (0 until node.childNodes.length)
                .mapNotNull { node.childNodes.item(it) as? org.w3c.dom.Element }

        fun record(path: String, element: org.w3c.dom.Element) {
            val name = element.getAttributeNS(ns, "name").orEmpty()
            entries += "$path/${element.tagName}${if (name.isEmpty()) "" else "  $name"}"
        }

        childElements(root).forEach { child ->
            record("manifest", child)
            when (child.tagName) {
                "application" -> {
                    // Kept deliberately although it is an attribute rather than a child:
                    // appComponentFactory is a real inherited cost, it comes from
                    // androidx.core, and unlike versionCode it is stable across AGP bumps.
                    child.getAttributeNS(ns, "appComponentFactory").takeIf { it.isNotEmpty() }
                        ?.let { entries += "manifest/application@appComponentFactory  $it" }
                    childElements(child).forEach { record("manifest/application", it) }
                }
                "queries" -> childElements(child).forEach { record("manifest/queries", it) }
            }
        }
        return entries
    }
}

androidComponents {
    onVariants(selector().withName("release")) { variant ->
        val guard = tasks.register<ManifestGoldenGuard>("manifestGolden") {
            group = "verification"
            description = "Freezes the merged manifest an integrator inherits from this SDK"
            mergedManifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            golden.set(layout.projectDirectory.file("manifest-golden.txt"))
        }
        tasks.named("check") { dependsOn(guard) }
    }
}
