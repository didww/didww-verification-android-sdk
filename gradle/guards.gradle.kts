// Guards wired into `check`.
//
// EVERY task here fails when its OWN INPUT IS ABSENT. That is not a stylistic preference:
// an earlier iteration of this repository had three verification steps that passed against
// an empty repository, because a grep that matches nothing exits non-zero and a task that
// finds no files has nothing to complain about. A check that cannot fail is worse than no
// check, because it also removes the suspicion that would have led someone to look.
//
// Each guard therefore starts by asserting that it found something to inspect, and each has
// been demonstrated to fail by removing or corrupting its input — the failure was observed,
// not assumed. Two were themselves broken when first written and were caught that way.

val libraryModules = listOf(
    "verification-core",
    "verification-sms",
    "verification-all",
)

// ---------------------------------------------------------------------------------------
// The public surface may not mention host lifecycle types.
//
// `apiCheck` alone does NOT enforce this. It freezes whatever is in the committed dump, so
// running `apiDump` after accidentally adding an Activity parameter records it and every
// subsequent `apiCheck` passes forever. Freshness and content are two different properties
// and need two different checks.
// ---------------------------------------------------------------------------------------
abstract class ApiSurfaceGuard : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apiFiles: ConfigurableFileCollection

    @get:Input
    abstract val expectedDumpCount: Property<Int>

    @get:Input
    abstract val bannedDescriptors: ListProperty<String>

    @TaskAction
    fun verify() {
        val present = apiFiles.files.filter { it.isFile }
        val expected = expectedDumpCount.get()
        if (present.size != expected) {
            throw GradleException(
                "expected $expected committed .api dumps but found ${present.size}. " +
                    "A missing dump means the public surface of a published module is " +
                    "unrecorded and therefore unchecked. Run `./gradlew apiDump`.",
            )
        }

        val violations = present.flatMap { file ->
            file.readLines().withIndex().flatMap { (index, line) ->
                bannedDescriptors.get()
                    .filter { line.contains(it) }
                    .map { "${file.name}:${index + 1} exposes $it" }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "public API must take an application Context and nothing tied to a host's " +
                    "lifecycle:\n" + violations.joinToString("\n") { "  $it" },
            )
        }
        logger.lifecycle("apiSurfaceGuard: ${present.size} dumps clean of host lifecycle types")
    }
}

// ---------------------------------------------------------------------------------------
// No @RequiresApi on a public declaration.
//
// A BCV dump records signatures, not annotations, so `apiCheck` cannot discharge this at
// all. An @RequiresApi on public API pushes the version gate onto the integrator, who
// discovers it as a lint failure in their build rather than in our documentation.
// Internal declarations may carry it freely.
// ---------------------------------------------------------------------------------------
abstract class RequiresApiGuard : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val files = sources.files.filter { it.isFile && it.extension == "kt" }
        if (files.isEmpty()) {
            throw GradleException(
                "found no Kotlin sources to inspect. This guard cannot pass vacuously.",
            )
        }

        // Matches the fully-qualified form too. An earlier version compared against the
        // literal "@RequiresApi" and silently ignored `@androidx.annotation.RequiresApi`,
        // which is the form an IDE inserts when the import is missing — so the guard read
        // as working while passing the exact case it existed to catch.
        val annotation = Regex("""^@(?:[\w.]*\.)?RequiresApi\b""")

        val violations = mutableListOf<String>()
        files.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                if (!annotation.containsMatchIn(line.trimStart())) return@forEachIndexed
                // The annotated declaration is the next line that actually declares
                // something, skipping further annotations and comments.
                val declaration = lines.drop(index + 1).firstOrNull { candidate ->
                    val t = candidate.trim()
                    t.isNotEmpty() && !t.startsWith("@") && !t.startsWith("//") && !t.startsWith("*")
                }.orEmpty().trim()
                val isPublic = !declaration.startsWith("internal") &&
                    !declaration.startsWith("private") &&
                    declaration.isNotEmpty()
                if (isPublic) {
                    violations += "${file.name}:${index + 1} -> $declaration"
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "@RequiresApi must not appear on a public declaration; mark the internal " +
                    "function instead:\n" + violations.joinToString("\n") { "  $it" },
            )
        }
        logger.lifecycle("requiresApiGuard: ${files.size} source files clean")
    }
}

// ---------------------------------------------------------------------------------------
// No compiled-in verification policy, and no drift in the literal inventory.
//
// Two halves, because an inventory on its own is only a DRIFT check: someone who adds
// `const val MAX_ATTEMPTS = 3` and dutifully adds a matching inventory row passes it, and
// nothing else in the build would object.
//
// NOTE — a deliberate narrowing of the obvious rule, which would fail on "any
// identifier" matching TIMEOUT|EXPIR|ATTEMPT|RETRY|INTERVAL|MAX_. Taken literally that
// flags `expiresAtEpochMillis`, `VerificationState.Expired`, `ApiErrorCode.EXPIRED` and
// `TOO_MANY_ATTEMPTS` — all of which are the wire's own vocabulary, not policy. A check
// that false-fails on day one gets loosened or deleted, which is exactly the failure this
// guard exists to prevent. So the policy half fires on a named declaration whose name
// matches AND whose initializer is a NUMBER. `const val MAX_ATTEMPTS = 3` is caught;
// `EXPIRED("expired")` is not, because a slug is not a policy.
// ---------------------------------------------------------------------------------------
abstract class LiteralsGuard : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inventory: RegularFileProperty

    @get:Input
    abstract val policyPattern: Property<String>

    @get:Input
    abstract val policyAllowlist: ListProperty<String>

    @TaskAction
    fun verify() {
        val files = sources.files.filter { it.isFile && it.extension == "kt" }
        if (files.isEmpty()) {
            throw GradleException("found no Kotlin sources to inspect. This guard cannot pass vacuously.")
        }
        val inventoryFile = inventory.get().asFile
        if (!inventoryFile.isFile) {
            throw GradleException("${inventoryFile.name} is missing; the inventory cannot be checked against nothing.")
        }
        val inventoryText = inventoryFile.readText()

        val declaration = Regex("""\b(?:const\s+)?val\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=]+)?=\s*(.+)""")
        val policy = Regex(policyPattern.get())
        val allowlist = policyAllowlist.get().toSet()

        val named = mutableListOf<Pair<String, String>>()
        val policyViolations = mutableListOf<String>()

        files.forEach { file ->
            file.readLines().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.startsWith("//") || line.startsWith("*")) return@forEachIndexed
                val match = declaration.find(line) ?: return@forEachIndexed
                val name = match.groupValues[1]
                val initializer = match.groupValues[2].trim()
                named += name to initializer

                val looksNumeric = initializer.firstOrNull()?.isDigit() == true
                if (policy.containsMatchIn(name) && looksNumeric && name !in allowlist) {
                    policyViolations += "${file.name}:${index + 1} $name = $initializer"
                }
            }
        }

        if (policyViolations.isNotEmpty()) {
            throw GradleException(
                "verification policy is the server's to choose and arrives on the wire; it " +
                    "must never be a compiled-in number:\n" +
                    policyViolations.joinToString("\n") { "  $it" } +
                    "\nIf this is transport hygiene rather than policy, say so and add it to " +
                    "the allowlist in gradle/guards.gradle.kts.",
            )
        }

        // Drift, both directions.
        val constants = named.filter { (_, init) ->
            init.firstOrNull()?.isDigit() == true || init.startsWith("Regex(")
        }.map { it.first }.distinct()

        val undocumented = constants.filterNot { inventoryText.contains("`$it`") }
        if (undocumented.isNotEmpty()) {
            throw GradleException(
                "${inventoryFile.name} does not account for: ${undocumented.joinToString()}. " +
                    "Every named numeric or regex constant needs a one-line justification.",
            )
        }

        // Only the first cell of a table row counts as an inventory entry. Matching every
        // backticked token would make ordinary prose — a mention of `Config`, or of
        // `Regex` — read as a documented constant and then fail as stale.
        val documented = Regex("""^\|\s*`([A-Za-z_][A-Za-z0-9_]*)`\s*\|""", RegexOption.MULTILINE)
            .findAll(inventoryText)
            .map { it.groupValues[1] }
            .toSet()
        val stale = documented.filterNot { doc -> named.any { it.first == doc } }
        if (stale.isNotEmpty()) {
            throw GradleException(
                "${inventoryFile.name} documents constants that no longer exist: " +
                    "${stale.joinToString()}. An inventory that drifts stops being read.",
            )
        }

        logger.lifecycle("literalsGuard: ${constants.size} constants inventoried, 0 policy values")
    }
}

// ---------------------------------------------------------------------------------------
// Wiring
// ---------------------------------------------------------------------------------------

val apiSurfaceGuard by tasks.registering(ApiSurfaceGuard::class) {
    group = "verification"
    description = "Asserts every published module has a committed .api dump, free of host lifecycle types"
    apiFiles.from(libraryModules.map { layout.projectDirectory.file("$it/api/$it.api") })
    expectedDumpCount.set(libraryModules.size)
    bannedDescriptors.set(
        listOf(
            "Landroid/app/Activity;",
            "Landroidx/activity/",
            "Landroidx/fragment/",
            "Landroid/app/Fragment;",
            "Landroidx/lifecycle/LifecycleOwner;",
        ),
    )
}

val mainSources = libraryModules.map { layout.projectDirectory.dir("$it/src/main") }

val requiresApiGuard by tasks.registering(RequiresApiGuard::class) {
    group = "verification"
    description = "Fails when @RequiresApi is applied to a public declaration"
    sources.from(mainSources.map { it.asFileTree.matching { include("**/*.kt") } })
}

val literalsGuard by tasks.registering(LiteralsGuard::class) {
    group = "verification"
    description = "Literal inventory drift check plus the failing-closed policy-constant check"
    sources.from(mainSources.map { it.asFileTree.matching { include("**/*.kt") } })
    inventory.set(layout.projectDirectory.file("CONTRIBUTING.md"))
    policyPattern.set("TIMEOUT|EXPIR|ATTEMPT|RETRY|INTERVAL|MAX_")
    // The only two exemptions, and both are transport hygiene: HttpURLConnection defaults
    // both timeouts to 0 = wait forever. Neither appears on the wire; neither can be
    // server-tuned. See Config's KDoc.
    policyAllowlist.set(listOf("connectTimeoutMillis", "readTimeoutMillis"))
}

tasks.named("check") {
    dependsOn(apiSurfaceGuard, requiresApiGuard, literalsGuard)
}

// ---------------------------------------------------------------------------------------
// The default test run must not touch the network.
//
// `--offline` on its own does NOT achieve this. Robolectric fetches its `android-all`
// runtime through its OWN Maven client, entirely outside Gradle's resolution, so a build
// that Gradle believes is offline still opens sockets. Pointing Robolectric at a local
// directory and telling it it is offline is what actually closes them.
//
// The jars are NOT committed. One `android-all-instrumented` jar is 144 MB; putting that in
// git would make every clone of this SDK 144 MB heavier forever, to save a one-time fetch.
// They are copied into a gitignored directory from the local Maven cache instead.
//
// AN EMPTY CACHE IS NOT AN ERROR, and used to be. This task failed the build when it found
// nothing to copy, telling the reader to "run `./gradlew test` ONCE with network access" —
// advice that could not be followed, because `test` depends on this task, so it failed
// before any test ran and nothing ever populated the cache. Only Robolectric's own Maven
// client writes to ~/.m2, and only while a test is executing. A fresh clone was therefore
// unbootstrappable on any machine, and it went unnoticed for as long as it did because the
// machine it was written on already had a populated cache from before the task existed.
//
// So: an empty cache stages nothing and warns. That run's tests fetch the runtime and fill
// the cache; the next run stages it and is offline. The invariant that survives is the one
// worth having — every REPEAT run is network-free — and the single run that is not is the
// bootstrap one, which is downloading Gradle, AGP and Kotlin anyway.
// ---------------------------------------------------------------------------------------
abstract class VendorRobolectricRuntime : DefaultTask() {

    @get:Internal
    abstract val mavenCache: DirectoryProperty

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    fun vendor() {
        val source = mavenCache.get().asFile
        val target = destination.get().asFile
        target.mkdirs()

        val jars = source.walkTopDown()
            .filter { it.isFile && it.extension == "jar" && it.name.startsWith("android-all") }
            .toList()

        if (jars.isEmpty()) {
            logger.warn(
                "\nvendorRobolectricRuntime: no android-all jars under ${source.path}.\n" +
                    "  This run's tests will FETCH Robolectric's runtime over the network — the\n" +
                    "  one time that happens. It lands in the local Maven cache, this task stages\n" +
                    "  it on the next build, and every run after that is offline.\n" +
                    "  Do not pass --offline to this first run; nothing can satisfy it.\n",
            )
            return
        }
        jars.forEach { jar ->
            val copy = File(target, jar.name)
            if (!copy.exists() || copy.length() != jar.length()) jar.copyTo(copy, overwrite = true)
        }
        logger.lifecycle("vendorRobolectricRuntime: ${jars.size} runtime jar(s) available offline")
    }
}

val vendorRobolectricRuntime by tasks.registering(VendorRobolectricRuntime::class) {
    group = "verification"
    description = "Stages Robolectric's android-all runtime locally so tests never reach the network"
    mavenCache.set(layout.projectDirectory.dir(System.getProperty("user.home") + "/.m2/repository"))
    destination.set(layout.projectDirectory.dir("robolectric-runtime"))
}
