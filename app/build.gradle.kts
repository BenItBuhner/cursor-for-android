import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.concurrent.thread

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// ---------------------------------------------------------------------------------------------------------------------
// Versioning
//
// The version comes from git; nothing in the repository is edited to cut a release:
//
//   * `-Papp.versionName=X.Y.Z` names it outright. release.yml and scripts/release-cut.sh pass the `vX.Y.Z` tag's
//     version this way, so a release never depends on what the checkout can see.
//   * A checkout at a release tag `vX.Y.Z` builds X.Y.Z.
//   * Anything else is a dev build of the release that comes next: the highest `v*` tag's PATCH + 1 - or the tag's own
//     version while that tag is a pre-release such as v0.4.0-rc.1 - with `-dev` appended, e.g. `0.3.49-dev` after
//     v0.3.48. A PATCH (then MINOR) that would reach 100 rolls over to the next MINOR (then MAJOR) instead, since the
//     versionCode below has room for 0..99 only: `0.4.0-dev` after v0.3.99. CI replaces the `-dev` with its stamp
//     (below). A checkout without git or without any release tag builds 0.0.0-dev and says so; fetch the tags
//     (`git fetch --tags`) to get the real version.
//
// versionCode is computed from the resolved name, so the tag is the only input a release needs:
//
//     MAJOR * 1_000_000 + MINOR * 10_000 + PATCH * 100 + STAGE
//     STAGE: alpha.N -> N (0..24), beta.N -> 25 + N, rc.N -> 50 + N, stable (no pre-release) -> 99
//
// e.g. 0.2.0-alpha.1 -> 20001, 0.2.0-beta.1 -> 20026, 0.2.0-rc.1 -> 20051, 0.2.0 -> 20099. Build metadata after `+`
// is ignored. `-Papp.versionCode=N` overrides the derived value.
//
// `-Papp.versionNameSuffix=...` replaces the `-dev` (CI uses it to stamp dev builds with the run number and commit,
// `-dev.148+gabc1234`) and the code is derived from the result, not from the base: a `0.2.0-dev.148+gabc1234` build
// has to report 20024, or the updater would refuse the stable 0.2.0 (20099) it leads up to as "not newer". `dev`
// counts as an alpha-class stage, so every dev build sorts below every release of the version it leads up to.
//
// The in-app updater (domain/AppUpdate.kt, AppVersion.versionCode) reproduces this scheme to compare a release tag
// with the installed BuildConfig.VERSION_CODE; AppVersionTest pins both to the same examples, and asserts that this
// script's own output for the build under test agrees with it. Change them together.
// ---------------------------------------------------------------------------------------------------------------------
val releaseTagVersion = Regex("""^v(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$""")

/** stdout of `git args...` in the repository root, or null when git is missing, fails or prints nothing. */
fun git(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        workingDir = rootDir
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
}.getOrNull()

/** The highest release version among [tags] (`vX.Y.Z[-pre]` lines; anything else is ignored), without the `v`. */
fun highestReleaseVersion(tags: String?): String? = tags.orEmpty().lines().map(String::trim)
    .filter(releaseTagVersion::matches)
    .map { it.removePrefix("v") }
    .maxByOrNull { runCatching { versionCodeFor(it) }.getOrDefault(-1) }

/** The version this checkout builds by default, and whether it is a dev build leading up to that version. */
fun versionFromGit(): Pair<String, Boolean> {
    highestReleaseVersion(git("tag", "--points-at", "HEAD", "--list", "v[0-9]*"))?.let { return it to false }
    val latest = highestReleaseVersion(git("tag", "--list", "v[0-9]*"))
    if (latest == null) {
        logger.warn("No release tag (vX.Y.Z) is visible to this checkout, so this is a 0.0.0-dev build. Fetch the tags (git fetch --tags) for the real version, or pass -Papp.versionName.")
        return "0.0.0" to true
    }
    val (major, minor, patch, preRelease) = releaseTagVersion.matchEntire("v$latest")!!.destructured
    return (if (preRelease.isEmpty()) nextReleaseAfter(major.toInt(), minor.toInt(), patch.toInt()) else "$major.$minor.$patch") to true
}

/** The release after stable [major].[minor].[patch]; mirrored by AppVersion.nextDevVersion (see Versioning above). */
fun nextReleaseAfter(major: Int, minor: Int, patch: Int): String = when {
    patch + 1 < 100 -> "$major.$minor.${patch + 1}"
    minor + 1 < 100 -> "$major.${minor + 1}.0"
    else -> "${major + 1}.0.0"
}

val appVersionNameSuffix: String? = providers.gradleProperty("app.versionNameSuffix").orNull?.takeIf { it.isNotBlank() }
val appExplicitVersionName: String? = providers.gradleProperty("app.versionName").orNull?.takeIf { it.isNotBlank() }
val appGitVersion: Pair<String, Boolean>? = if (appExplicitVersionName == null) versionFromGit() else null
val appVersionName: String = appExplicitVersionName ?: appGitVersion!!.first
val appIsDevBuild: Boolean = appGitVersion?.second ?: false
val appEffectiveVersionNameSuffix: String? = appVersionNameSuffix ?: "-dev".takeIf { appIsDevBuild }
val appResolvedVersionName: String = appVersionName + (appEffectiveVersionNameSuffix ?: "")
val appVersionCode: Int = providers.gradleProperty("app.versionCode").map(String::toInt).getOrElse(versionCodeFor(appResolvedVersionName))
// The GitHub repository whose releases the app updates itself from (`owner/name`); a fork points this at its own.
val appGitHubRepo: String = providers.gradleProperty("app.githubRepo").get().also {
    require(Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""").matches(it)) { "app.githubRepo must be 'owner/name', got '$it'" }
}
val GITHUB_API_BASE_URL = "https://api.github.com/"

fun versionCodeFor(versionName: String): Int {
    val semver = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")
    val match = semver.matchEntire(versionName)
        ?: error("app.versionName '$versionName' must look like MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD]")
    val (major, minor, patch, preRelease) = match.destructured
    require(minor.toInt() < 100 && patch.toInt() < 100) { "MINOR and PATCH must be < 100 to fit the versionCode scheme ($versionName)" }
    require(major.toInt() < 2_000) { "MAJOR must be < 2000 to stay under Android's versionCode limit ($versionName)" }
    return major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + preReleaseStage(preRelease)
}

fun preReleaseStage(preRelease: String): Int {
    if (preRelease.isEmpty()) return 99
    val parts = preRelease.split('.')
    val iteration = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return when (parts.first().lowercase()) {
        "beta" -> 25 + iteration.coerceIn(0, 24)
        "rc" -> 50 + iteration.coerceIn(0, 48)
        else -> iteration.coerceIn(0, 24) // alpha, dev, snapshot, ...
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Release signing
//
// CI:    RELEASE_KEYSTORE_FILE / RELEASE_KEYSTORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD env vars.
//        release.yml decodes the RELEASE_KEYSTORE_BASE64 secret into RUNNER_TEMP and points RELEASE_KEYSTORE_FILE at it.
// Local: a git-ignored keystore.properties next to settings.gradle.kts with storeFile / storePassword / keyAlias /
//        keyPassword (storeFile is resolved against the repository root).
//
// A release APK or bundle built without either FAILS. An artifact signed with the public debug key is not publishable,
// and every install it produces is stranded on a key no later release can reproduce. `-Papp.allowUnsignedRelease=true`
// is the one explicit escape hatch: ci.yml passes it because its `assembleRelease` exists only to exercise R8, and the
// APK that comes out is never published. Debug builds never need signing secrets.
// ---------------------------------------------------------------------------------------------------------------------
data class ReleaseSigning(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

fun releaseSigning(): ReleaseSigning? {
    val env = { name: String -> providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() } }
    env("RELEASE_KEYSTORE_FILE")?.let { path ->
        fun required(name: String) = env(name) ?: error("$name must be set when RELEASE_KEYSTORE_FILE is set")
        return ReleaseSigning(file(path), required("RELEASE_KEYSTORE_PASSWORD"), required("RELEASE_KEY_ALIAS"), required("RELEASE_KEY_PASSWORD"))
    }
    val propertiesFile = rootProject.file("keystore.properties")
    if (!propertiesFile.exists()) return null
    val properties = Properties().apply { propertiesFile.inputStream().use(::load) }
    fun required(name: String) = properties.getProperty(name)?.takeIf { it.isNotBlank() } ?: error("keystore.properties is missing '$name'")
    return ReleaseSigning(rootProject.file(required("storeFile")), required("storePassword"), required("keyAlias"), required("keyPassword"))
}

val releaseSigning: ReleaseSigning? = releaseSigning()
val allowUnsignedRelease: Boolean = providers.gradleProperty("app.allowUnsignedRelease").map(String::toBoolean).getOrElse(false)

/**
 * SHA-256 of the certificate [signing] signs with, in the lowercase hex `apksigner --print-certs` prints, the release
 * notes carry and the RELEASE_CERT_SHA256 repository variable pins. Read straight from the keystore so it cannot
 * disagree with what actually signs the APK.
 */
fun certificateSha256(signing: ReleaseSigning): String {
    require(signing.storeFile.isFile) { "Release keystore not found at ${signing.storeFile}" }
    val certificate = listOf("PKCS12", "JKS").firstNotNullOfOrNull { type ->
        runCatching {
            val store = KeyStore.getInstance(type)
            signing.storeFile.inputStream().use { store.load(it, signing.storePassword.toCharArray()) }
            store.getCertificate(signing.keyAlias)
        }.getOrNull()
    } ?: error(
        "Could not read the certificate for alias '${signing.keyAlias}' from ${signing.storeFile.name} as PKCS12 or JKS; " +
            "check RELEASE_KEYSTORE_PASSWORD and RELEASE_KEY_ALIAS.",
    )
    return MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
}

/**
 * Baked into release builds as `BuildConfig.RELEASE_CERT_SHA256`; empty in a build with no release key. It is the only
 * certificate the in-app updater will install an APK signed with, so it has to come from the key that signs this very
 * build rather than from anything a release's notes claim.
 */
val releaseCertSha256: String = releaseSigning?.let(::certificateSha256).orEmpty()

// ---------------------------------------------------------------------------------------------------------------------
// Crash reports (crash/CrashReporting.kt; currently unwired, with no Settings consent)
//
// `-Papp.sentryDsn=https://<key>@<host>/<project>` bakes the Sentry project into BuildConfig.SENTRY_DSN; the release
// workflow passes it from the SENTRY_DSN secret when that exists. Without it the field is empty and the app has no
// crash reporting at all. `-Papp.sentryProguardUuid=<uuid>` is
// the id the workflow uploads this build's R8 mapping under, so a report from an obfuscated build can be read back;
// it is only meaningful together with the DSN. Neither is ever set for a PR or debug build.
// ---------------------------------------------------------------------------------------------------------------------
val appSentryDsn: String = providers.gradleProperty("app.sentryDsn").orNull?.trim().orEmpty().also {
    require(it.isEmpty() || Regex("""^https://[^@\s]+@[^/\s]+/\d+$""").matches(it)) {
        "app.sentryDsn must look like https://<public-key>@<host>/<project-id>"
    }
}
val appSentryProguardUuid: String = providers.gradleProperty("app.sentryProguardUuid").orNull?.trim().orEmpty().also {
    require(it.isEmpty() || Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""").matches(it)) {
        "app.sentryProguardUuid must be a UUID"
    }
    require(it.isEmpty() || appSentryDsn.isNotEmpty()) { "app.sentryProguardUuid is only meaningful with app.sentryDsn" }
}

android {
    namespace = "com.cursorforandroid"
    // 36 is required by androidx.core 1.17, which carries the Android 16 Live Updates notification APIs.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cursorforandroid"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        versionNameSuffix = appEffectiveVersionNameSuffix
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "GITHUB_REPO", "\"$appGitHubRepo\"")
        buildConfigField("String", "SENTRY_DSN", "\"$appSentryDsn\"")
        buildConfigField("String", "SENTRY_PROGUARD_UUID", "\"$appSentryProguardUuid\"")
    }

    signingConfigs {
        releaseSigning?.let { signing ->
            create("release") {
                storeFile = signing.storeFile
                storePassword = signing.storePassword
                keyAlias = signing.keyAlias
                keyPassword = signing.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The debug fallback is only ever reached under -Papp.allowUnsignedRelease=true; without it the task-graph
            // check at the end of this file fails the build before anything is packaged.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            buildConfigField("String", "UPDATE_API_BASE_URL", "\"$GITHUB_API_BASE_URL\"")
            buildConfigField("String", "RELEASE_CERT_SHA256", "\"$releaseCertSha256\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            // A debug build is a different applicationId and can never be updated by a release APK, so it pins nothing.
            buildConfigField("String", "RELEASE_CERT_SHA256", "\"\"")
            // Debug builds can be pointed at a stand-in for the GitHub API (`-Papp.updateApiBaseUrl=http://10.0.2.2:8080/`)
            // to exercise the updater against an emulator; src/debug's network security config permits cleartext to
            // the emulator host for exactly that. Release builds always use GitHub.
            val override = providers.gradleProperty("app.updateApiBaseUrl").orNull?.takeIf { it.isNotBlank() }
            buildConfigField("String", "UPDATE_API_BASE_URL", "\"${override ?: GITHUB_API_BASE_URL}\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        // `assembleRelease` would otherwise run lintVitalRelease - a second analysis of the same sources for the
        // fatal-only subset of what `lintDebug` already reports in full. There is no release-only source set to check
        // (src/main, src/debug and src/test are all there is), so the vital pass proves nothing lintDebug does not, and
        // CI runs lintDebug on every change. It cost ten minutes of the build job and of every release cut.
        checkReleaseBuilds = false
    }
}

// Domain types are immutable, so rows built from them skip when an emission leaves them equal; see the file itself.
val composeStabilityConfig = layout.projectDirectory.file("compose-stability.conf")
composeCompiler {
    stabilityConfigurationFiles.add(composeStabilityConfig)
}
// The Kotlin compile's up-to-date check and cache key leave the file out, so a change to it (or to whether it is
// used) would keep classes compiled without it, locally and from CI's build cache. As a declared input it is part
// of the key, and a change to it recompiles everything.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    inputs.file(composeStabilityConfig).withPropertyName("composeStabilityConfig").withPathSensitivity(PathSensitivity.NONE)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.core)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.work.runtime)
    implementation(libs.sentry.android.core)
    implementation(libs.androidx.window)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.window.testing)
    debugImplementation(libs.compose.ui.test.manifest)
}

roborazzi {
    outputDir.set(file("$rootDir/screenshots"))
}

// ---------------------------------------------------------------------------------------------------------------------
// Robolectric SDK jars
//
// Every Robolectric test runs on a pre-instrumented android-all jar for its SDK level: `@Config(sdk = [35])` almost
// everywhere in src/test (35 is also the default, being the targetSdk) and `sdk = [30]` for AppNightModeTest's pre-31
// case. Left to itself Robolectric downloads those jars (200 MB for SDK 35) from Maven Central *inside the test JVM*
// (MavenArtifactFetcher, into ~/.m2), on every CI run because nothing caches ~/.m2 - and when Maven Central refuses or
// rate-limits the runner, every test class fails in `classMethod` before a single test runs. Declaring the jars as
// Gradle dependencies moves that download into dependency resolution, where the Gradle dependency cache (restored by
// setup-gradle in CI) serves them and Gradle's repository retries apply; `robolectric.offline` +
// `robolectric.dependency.dir` then point Robolectric at the resolved files, so the tests never touch the network for
// them. The versions live next to `robolectric` in gradle/libs.versions.toml and move with it.
//
// One configuration per jar: they are all versions of the same module, org.robolectric:android-all-instrumented, and a
// single configuration would conflict-resolve them down to the highest one.
// ---------------------------------------------------------------------------------------------------------------------
val robolectricSdkJars = mapOf(35 to libs.robolectric.android.all.sdk35, 30 to libs.robolectric.android.all.sdk30)
val robolectricSdkConfigurations: List<Configuration> = robolectricSdkJars.keys.map { sdk ->
    configurations.create("robolectricSdk$sdk") {
        description = "The android-all-instrumented jar Robolectric runs SDK $sdk unit tests on."
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }
}

dependencies {
    robolectricSdkJars.forEach { (sdk, jar) -> add("robolectricSdk$sdk", jar) }
}

// Robolectric wants one directory holding `android-all-instrumented-<version>.jar` files; the Gradle cache keeps each
// artifact in its own hash directory, so the resolved jars are gathered here (Sync also drops the jar of a previous
// version, which would otherwise linger after a bump).
val robolectricSdkDir: Provider<Directory> = layout.buildDirectory.dir("robolectric-sdks")
val syncRobolectricSdks by tasks.registering(Sync::class) {
    description = "Gathers the Robolectric android-all jars for offline test runs."
    robolectricSdkConfigurations.forEach { from(it) }
    into(robolectricSdkDir)
}

tasks.withType<Test>().configureEach {
    dependsOn(syncRobolectricSdks)
    inputs.dir(robolectricSdkDir).withPropertyName("robolectricSdkDir").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricSdkDir.get().asFile.absolutePath)
}

// ---------------------------------------------------------------------------------------------------------------------
// Unit-test parallelism and sharding
//
// src/test is ~2000 Robolectric tests. One test JVM runs them one after another in about eleven minutes; CI splits the
// classes over several runners, one test JVM each. Both knobs are plain Gradle properties, so a developer can run the
// whole suite the ordinary way and CI can shape it:
//
//   -Papp.testForks=N        test JVMs run side by side, each holding a Robolectric SDK in a 2 GB heap (default: half
//                            the machine's cores, at least one and at most four - never more than the cores minus one,
//                            and no more heap between them than the machine has). CI passes 1: a hosted runner's four
//                            "cores" are two hyper-threaded ones, and with two or three forks the fault, benchmark and
//                            Compose UI tests - which assert on wall-clock behaviour, or wait on it - timed out on one
//                            shard job in five; alone in their JVM, as they always were, they did not.
//   -Papp.testShard=I/N      run only the I-th of N deterministic slices of the test classes (I from 1), the benchmarks
//                            excepted (below). The test source files are assigned heaviest first, each onto the slice
//                            with the least in it so far (`testClassSeconds`), so the slices take about the same time;
//                            they are stable across runs and machines and together cover every class exactly once. A
//                            nested class travels with its outer class; a class whose file is not named after it goes
//                            by a hash of the name. Combine with `--tests` or `-Papp.skipScreenshotTests` as usual:
//                            both filters apply.
//   -Papp.testShard=benchmarks
//                            run only the benchmark classes (`*BenchmarkTest`, `TranscriptPerf*`): the frame-time and
//                            throughput claims. Always one JVM, whatever -Papp.testForks says, and nothing else beside
//                            them, so what they measure is the code and not the neighbour. CI runs them in
//                            benchmarks.yml, which reports on every commit but holds no merge or release.
// ---------------------------------------------------------------------------------------------------------------------
val testForks: Int = providers.gradleProperty("app.testForks").map(String::toInt)
    .getOrElse((Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4))

/** One slice of the test classes: `Slice(index, count)` for the I-th of N ordinary slices, or [Benchmarks]. */
sealed interface TestShard : java.io.Serializable {
    data class Slice(val index: Int, val count: Int) : TestShard
    object Benchmarks : TestShard { private fun readResolve(): Any = Benchmarks }
}

val testShard: TestShard? = providers.gradleProperty("app.testShard").orNull?.let { spec ->
    if (spec == "benchmarks") return@let TestShard.Benchmarks
    val match = Regex("""^(\d+)/(\d+)$""").matchEntire(spec) ?: error("app.testShard must be I/N (e.g. 1/5) or 'benchmarks'; got '$spec'")
    val (index, count) = match.destructured.toList().map(String::toInt)
    require(count >= 1 && index in 1..count) { "app.testShard=$spec: I must be between 1 and N" }
    TestShard.Slice(index, count)
}

/**
 * Keeps the class files of one [TestShard]. [slices] maps an ordinary class (relative path without extension, e.g.
 * `com/x/FooTest`) to its slice; a class not in it goes by a hash of that path. A standalone class rather than a
 * lambda: the configuration cache has to serialize the filter with the task, and a lambda in this script would drag
 * the script object along.
 */
class TestShardFilter(private val shard: TestShard, private val slices: Map<String, Int>) : Spec<FileTreeElement>, java.io.Serializable {
    // Directories have to pass or nothing under them is visited; only class files are assigned.
    override fun isSatisfiedBy(element: FileTreeElement): Boolean {
        if (element.isDirectory || !element.name.endsWith(".class")) return true
        val outerClass = element.relativePath.pathString.substringBefore('$').removeSuffix(".class")
        val benchmark = isBenchmark(outerClass.substringAfterLast('/'))
        return when (shard) {
            is TestShard.Benchmarks -> benchmark
            is TestShard.Slice -> !benchmark && (slices[outerClass] ?: (Math.floorMod(outerClass.hashCode(), shard.count) + 1)) == shard.index
        }
    }

    companion object {
        /** A benchmark class by name: a frame-time or throughput claim, measured rather than asserted on state. */
        fun isBenchmark(simpleName: String): Boolean = simpleName.endsWith("BenchmarkTest") || simpleName.startsWith("TranscriptPerf")
    }
}

/**
 * How long a test class takes alone in its JVM, in seconds, for every class over two (app/test-class-seconds.properties,
 * measured in CI); every other class counts as one second. The slices are filled with these: heaviest class first,
 * each onto the slice with the least in it so far, so no slice ends up with two of the biggest while another has none.
 * Only the balance depends on the numbers - a class missing there, or a stale one, costs seconds of wall time, never
 * correctness. When a slice drifts (a new fault or latency suite lands), `scripts/refresh-test-balance.sh` rewrites the
 * file from the newest green run's `unit-tests-results-*` artifacts.
 */
val testClassSeconds: Map<String, Int> = providers.fileContents(layout.projectDirectory.file("test-class-seconds.properties"))
    .asText.getOrElse("").lineSequence()
    .map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
    .associate { line -> line.substringBefore('=').trim() to line.substringAfter('=').trim().toInt() }

/** Every ordinary test source file as the class path it compiles to, assigned to one of [count] slices: heaviest first, each onto the lightest slice so far. */
fun testShardSlices(count: Int): Map<String, Int> {
    val classPaths = layout.projectDirectory.dir("src/test/java").asFileTree
        .matching { include("**/*.kt", "**/*.java") }
        .files.map { it.relativeTo(file("src/test/java")).path.replace(File.separatorChar, '/').substringBeforeLast('.') }
        .filterNot { TestShardFilter.isBenchmark(it.substringAfterLast('/')) }
    fun seconds(classPath: String) = testClassSeconds[classPath.substringAfterLast('/')] ?: 1
    val load = IntArray(count)
    return classPaths.sortedWith(compareByDescending<String> { seconds(it) }.thenBy { it }).associateWith { classPath ->
        val lightest = load.indices.minBy { load[it] }
        load[lightest] += seconds(classPath)
        lightest + 1
    }
}

tasks.withType<Test>().configureEach {
    maxParallelForks = if (testShard is TestShard.Benchmarks) 1 else testForks
    // The JVM's default collector (G1) stays: the fault and benchmark tests assert on wall-clock behaviour, and a
    // throughput collector's long stop-the-world pauses are one more way to starve them.
    maxHeapSize = "2g"
    when (val shard = testShard) {
        null -> Unit
        is TestShard.Benchmarks -> include(TestShardFilter(shard, emptyMap()))
        is TestShard.Slice -> include(TestShardFilter(shard, testShardSlices(shard.count)))
    }
}

// `-Papp.skipScreenshotTests=true` leaves the Roborazzi walkthrough to the dedicated screenshot job in CI; everything
// else in src/test still runs.
if (providers.gradleProperty("app.skipScreenshotTests").map(String::toBoolean).getOrElse(false)) {
    tasks.withType<Test>().configureEach {
        exclude("com/cursorforandroid/screenshots/**")
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Test stalls
//
// A test JVM that stops making progress - a test waiting on a latch nothing counts down or a response no fake server
// will send, a deadlock in a class's setup - used to sit there until the CI job's timeout took the runner half an hour
// in, and left nothing to go on: the stuck test had not finished, so no report named it, and its last output was still
// in Gradle's buffer. So every test task watches its JVMs ("Gradle Test Executor N"). When one has started or finished
// no test or test class for `-Papp.testStallMinutes` (default 5; no single test in CI has taken two), the watchdog
// logs, and saves under build/reports/test-stalls/, what that JVM was in the middle of, its last lines of output and
// `jcmd Thread.print` and `GC.heap_info` of it, then kills it, so the task fails at once and says where. 0 turns the
// watchdog off; a test JVM under a debugger (--debug-jvm, an IDE's debug run) is never watched.
// ---------------------------------------------------------------------------------------------------------------------
val testStallMinutes: Long = providers.gradleProperty("app.testStallMinutes").map(String::toLong).getOrElse(5L)

/** Watches each run of a test task ([TestStallListener]). A class rather than a lambda for the configuration cache, as with [TestShardFilter]. */
class TestStallWatchdog(private val stallMillis: Long, private val reportDir: File) : Action<Task>, java.io.Serializable {
    override fun execute(task: Task) {
        val test = task as Test
        if (test.debug || test.allJvmArgs.any { "jdwp" in it }) return
        val listener = TestStallListener(test, stallMillis, reportDir)
        test.addTestListener(listener)
        test.addTestOutputListener(listener)
    }
}

/**
 * One run of a test task, watched. The test events reach the build process as they happen, so it knows what each test
 * JVM is in the middle of even once that JVM has stopped; a daemon thread looks every five seconds and ends with the run.
 */
class TestStallListener(private val task: Test, private val stallMillis: Long, private val reportDir: File) : TestListener, TestOutputListener {
    /** One test JVM: when it last started or finished a test or class, what it has started and not finished, its last output. */
    private class Jvm {
        @Volatile var lastProgress: Long = System.nanoTime()
        @Volatile var lastFinished: String? = null
        val running = ConcurrentHashMap<TestDescriptor, Long>()
        val output = ArrayDeque<String>()
    }

    private val jvms = ConcurrentHashMap<String, Jvm>()
    private val gone: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var runFinished = false

    override fun beforeSuite(suite: TestDescriptor) {
        if (suite.parent == null) watch() else progress(suite, started = true)
    }

    override fun afterSuite(suite: TestDescriptor, result: TestResult) {
        when {
            suite.parent == null -> runFinished = true
            suite.parent?.parent == null -> {
                gone += suite.name
                jvms.remove(suite.name)
            }
            else -> progress(suite, started = false)
        }
    }

    override fun beforeTest(testDescriptor: TestDescriptor) = progress(testDescriptor, started = true)

    override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) = progress(testDescriptor, started = false)

    // Kept for the report, but not progress: a test stuck in a loop that logs is still stuck.
    override fun onOutput(testDescriptor: TestDescriptor, outputEvent: TestOutputEvent) {
        val jvm = jvmOf(testDescriptor) ?: return
        synchronized(jvm.output) {
            outputEvent.message.trimEnd('\n').lineSequence().forEach { line ->
                jvm.output.addLast(line.take(500))
                if (jvm.output.size > 40) jvm.output.removeFirst()
            }
        }
    }

    /** The JVM [descriptor] runs in: the run's suites are its JVMs ("Gradle Test Executor N"), their suites the classes. */
    private fun jvmOf(descriptor: TestDescriptor): Jvm? {
        var suite = descriptor
        while (suite.parent?.parent != null) suite = suite.parent!!
        if (suite.parent == null || suite.name in gone) return null
        return jvms.computeIfAbsent(suite.name) { Jvm() }
    }

    private fun progress(descriptor: TestDescriptor, started: Boolean) {
        val jvm = jvmOf(descriptor) ?: return
        val now = System.nanoTime()
        jvm.lastProgress = now
        if (descriptor.parent?.parent == null) return
        if (started) {
            jvm.running[descriptor] = now
        } else {
            jvm.running.remove(descriptor)
            jvm.lastFinished = if (descriptor.isComposite) descriptor.name else "${descriptor.className} > ${descriptor.name}"
        }
    }

    private fun watch() {
        thread(isDaemon = true, name = "Test stall watchdog (${task.path})") {
            while (!runFinished && !task.state.executed) {
                Thread.sleep(5_000)
                val now = System.nanoTime()
                for ((name, jvm) in jvms) {
                    val silentMillis = TimeUnit.NANOSECONDS.toMillis(now - jvm.lastProgress)
                    if (silentMillis >= stallMillis && jvms.remove(name, jvm)) {
                        gone += name
                        stalled(name, jvm, silentMillis / 1000)
                    }
                }
            }
        }
    }

    private fun stalled(name: String, jvm: Jvm, silentSeconds: Long) {
        // Gradle hands a test JVM its display name, quoted, as the one argument to its main class: `'Gradle Test Executor 3'`.
        // Matched among this build's own children, so it finds that JVM and never another build's.
        val processes = ProcessHandle.current().descendants()
            .filter { process -> process.info().arguments().map { args -> args.any { it.removeSurrounding("'") == name } }.orElse(false) }
            .toList()
        try {
            val report = report(name, jvm, silentSeconds, processes)
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val file = File(reportDir, "${task.name}-${name.substringAfterLast(' ')}-$stamp.txt")
            val saved = runCatching { reportDir.mkdirs(); file.writeText(report) }.isSuccess
            val outcome = if (processes.isEmpty()) "its process was not found, so it is left running" else "killing it so that ${task.path} fails now"
            task.logger.error("Test stall: $outcome.${if (saved) " Saved to $file." else ""}\n$report")
        } finally {
            processes.forEach { process ->
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
        }
    }

    private fun report(name: String, jvm: Jvm, silentSeconds: Long, processes: List<ProcessHandle>): String = buildString {
        val now = System.nanoTime()
        fun since(start: Long) = "${TimeUnit.NANOSECONDS.toSeconds(now - start)} s"
        appendLine("${task.path}: $name has started or finished no test for $silentSeconds s (the limit is ${stallMillis / 1000} s, -Papp.testStallMinutes).")
        val running = jvm.running.entries.sortedBy { it.value }
        val tests = running.filterNot { it.key.isComposite }
        val classes = running.filter { it.key.isComposite }
        when {
            tests.isNotEmpty() -> tests.forEach { (test, start) -> appendLine("Running for ${since(start)}: ${test.className} > ${test.name}") }
            classes.isNotEmpty() -> classes.forEach { (suite, start) ->
                appendLine("In ${suite.name} for ${since(start)} with no test running: stuck in its setup or teardown, or between two of its tests.")
            }
            else -> appendLine("Between two test classes: no class had started.")
        }
        appendLine("Last finished: ${jvm.lastFinished ?: "nothing yet"}")
        synchronized(jvm.output) {
            appendLine()
            appendLine("Its last ${jvm.output.size} lines of output:")
            jvm.output.forEach { appendLine("  $it") }
        }
        processes.forEach { process ->
            for (command in listOf(listOf("Thread.print", "-l"), listOf("GC.heap_info"))) {
                appendLine()
                appendLine("jcmd ${process.pid()} ${command.joinToString(" ")}:")
                appendLine(jcmd(process, command))
            }
        }
    }

    /** `jcmd <pid> <command>` from the JDK that JVM runs on (or this build's, or the PATH's): what it printed, or why it could not. */
    private fun jcmd(process: ProcessHandle, command: List<String>): String {
        val executable = sequenceOf(process, ProcessHandle.current())
            .mapNotNull { it.info().command().orElse(null) }
            .map { File(File(it).parentFile, "jcmd") }
            .firstOrNull { it.canExecute() }?.path ?: "jcmd"
        val out = File.createTempFile("jcmd-", ".txt")
        return try {
            val jcmd = ProcessBuilder(listOf(executable, process.pid().toString()) + command).redirectErrorStream(true).redirectOutput(out).start()
            if (jcmd.waitFor(60, TimeUnit.SECONDS)) {
                out.readText()
            } else {
                jcmd.destroyForcibly()
                out.readText() + "\n(jcmd gave no answer within 60 s)"
            }
        } catch (e: Exception) {
            "jcmd could not run: $e"
        } finally {
            out.delete()
        }
    }
}

if (testStallMinutes > 0) {
    val stallReportDir = layout.buildDirectory.dir("reports/test-stalls")
    tasks.withType<Test>().configureEach {
        doFirst(TestStallWatchdog(TimeUnit.MINUTES.toMillis(testStallMinutes), stallReportDir.get().asFile))
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// agentCheck
//
// `./gradlew :app:agentCheck` runs the unit tests a change is likely to break instead of all ~4000, which take over
// half an hour on a four-core machine. The change is everything that differs from where HEAD left origin/main (their
// merge base), committed or not, new files included; `-Papp.agentCheck.base=<ref>` measures from another ref. From
// src/test it runs:
//
//   * the test classes of every changed test file;
//   * the test classes named after a changed source file - SessionManagerProfileTest and SessionManagerRestoreTest for
//     SessionManager.kt - support code in src/test included;
//   * lightest first, the test classes that mention what a changed file declares - a class, interface, object or type
//     alias, a function with a compound name (StatusPill, toRows), the file's own name, a changed fixture's file name -
//     until the run holds `-Papp.agentCheck.seconds` of test time as CI measures it (default 300; `testClassSeconds`).
//     0 leaves them all out. Over the last 40 merges that came to a median of 31 classes and 190 s, and never more than
//     300 s, of a suite that takes 3500.
//
// It compiles all of src/main and src/test whatever it picks, and runs the tests in one JVM unless -Papp.testForks says
// otherwise: two side by side on a four-core machine starve the timing-sensitive tests, as on CI's runners. (A change to
// SessionManager.kt picks 80 classes; alone they took 5m 53s, as two JVMs 4m 37s with one wait timing out.) Before the
// tests start it prints what it picked and why, and what it leaves to CI with the command for each: the mentioning
// classes past the budget, the screenshot tests (verifyRoborazziDebug) and the benchmarks (alone). It is the check
// before a push, not a substitute for CI. The pick is made from the tree on every run: a further change picks again, an
// unchanged tree reuses the configuration cache.
// ---------------------------------------------------------------------------------------------------------------------
val agentCheckRequested: Boolean = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "agentCheck" }

/** What agentCheck runs - the classes of the test files it picked (`com/x/FooTest`) - and what it says before and after. */
data class AgentCheckPlan(val classPaths: List<String>, val before: String, val after: String) : java.io.Serializable

/**
 * Picks agentCheck's tests from git and the sources. A [ValueSource], so the configuration cache keeps the plan and
 * checks it against the tree on each run rather than tracking every file read to make it.
 */
abstract class AgentCheckSelection : ValueSource<AgentCheckPlan, AgentCheckSelection.Parameters> {
    interface Parameters : ValueSourceParameters {
        val appDir: DirectoryProperty
        val base: Property<String>
        val seconds: Property<Int>
        val classSeconds: MapProperty<String, Int>
    }

    @get:Inject abstract val exec: ExecOperations

    /** A test file: its path from the repository root, the classes it declares (`com/x/FooTest`), their seconds in CI. */
    private class TestFile(val path: String, val name: String, val classPaths: List<String>, val seconds: Int, val text: String) {
        val className: String = (classPaths.firstOrNull { it.substringAfterLast('/') == name } ?: classPaths.first()).replace('/', '.')
        /** Its test classes: the one it is named after, then any other `*Test` beside it (PhoneComposerButtonSizeTest). */
        val testClasses: List<String> = listOf(name) + classPaths.map { it.substringAfterLast('/') }.filter { it != name && it.endsWith("Test") }
        val isScreenshot: Boolean get() = classPaths.first().startsWith("com/cursorforandroid/screenshots/")
        val isBenchmark: Boolean get() = TestShardFilter.isBenchmark(name)
    }

    override fun obtain(): AgentCheckPlan {
        val appDir = parameters.appDir.get().asFile
        val root = git(appDir, "rev-parse", "--show-toplevel")?.let(::File)
            ?: return AgentCheckPlan(emptyList(), "agentCheck: this is not a git checkout, so there is no change to pick tests for; it only compiles.", "agentCheck passed: it compiled.")
        val base = parameters.base.get()
        val budget = parameters.seconds.get()
        val mergeBase = git(root, "merge-base", "HEAD", base)
        val changed = (git(root, "diff", "--name-only", "--no-renames", mergeBase ?: "HEAD").orEmpty().lines() +
            git(root, "ls-files", "--others", "--exclude-standard").orEmpty().lines())
            .filter(String::isNotBlank).distinct().sorted()

        val app = appDir.relativeTo(root).invariantSeparatorsPath.let { if (it.isEmpty()) "" else "$it/" }
        val testRoot = File(appDir, "src/test/java")
        val classSeconds = parameters.classSeconds.get()
        val tests = testRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.mapNotNull { file ->
            val text = file.readText()
            if ("@Test" !in text) return@mapNotNull null
            val packagePath = packageLine.find(text)?.groupValues?.get(1)?.replace('.', '/')?.plus('/').orEmpty()
            val classes = topLevelType.findAll(text).map { it.groupValues[1] }.toList().ifEmpty { listOf(file.nameWithoutExtension) }
            TestFile(
                path = app + "src/test/java/" + file.relativeTo(testRoot).invariantSeparatorsPath,
                name = file.nameWithoutExtension,
                classPaths = classes.map { packagePath + it },
                seconds = classes.sumOf { classSeconds[it] ?: 0 }.coerceAtLeast(1),
                text = text,
            )
        }.sortedBy { it.path }.toList()
        val testAt = tests.associateBy { it.path }

        // Why each test file is in: changed itself, or named after a changed source file.
        val why = linkedMapOf<TestFile, String>()
        val stems = mutableListOf<String>()
        val declared = sortedSetOf<String>()
        val unmapped = mutableListOf<String>()
        for (path in changed) {
            val parts = path.removePrefix(app).split('/')
            val inSources = path.startsWith(app) && parts.size > 3 && parts[0] == "src"
            when {
                inSources && parts[2] == "java" && (path.endsWith(".kt") || path.endsWith(".java")) -> {
                    val stem = parts.last().substringBeforeLast('.')
                    val test = testAt[path]
                    if (test != null) why[test] = "changed" else stems += stem
                    val text = File(root, path).takeIf { it.isFile }?.readText() ?: mergeBase?.let { git(root, "show", "$it:$path") }.orEmpty()
                    declared += stem
                    declared += topLevelType.findAll(text).map { it.groupValues[1] }
                    declared += topLevelFunction.findAll(text).map { it.groupValues[1] }.filter { it.length >= 6 && it.drop(1).any(Char::isUpperCase) }
                }
                inSources && parts[1] == "test" && parts[2] == "resources" && tests.any { parts.last() in it.text } -> declared += parts.last()
                path.startsWith(app) || path.endsWith(".gradle.kts") || path == "gradle.properties" || path.startsWith("gradle/") -> unmapped += path
            }
        }
        for (test in tests) if (test !in why) stems.firstOrNull { test.name.startsWith(it) }?.let { why[test] = "named after $it" }
        val mention = declared.takeIf { it.isNotEmpty() }?.let { names -> Regex(names.joinToString("|", """\b(""", """)\b""") { Regex.escape(it) }) }
        val mentioning = tests.filter { it !in why }.mapNotNull { test -> mention?.find(test.text)?.let { test to "mentions ${it.groupValues[1]}" } }

        val touched = why.toList() + mentioning
        val screenshots = touched.map { it.first }.filter { it.isScreenshot }
        val benchmarks = touched.map { it.first }.filter { it.isBenchmark && !it.isScreenshot }
        val direct = why.toList().filter { (test, _) -> !test.isScreenshot && !test.isBenchmark }
        val candidates = mentioning.filter { (test, _) -> !test.isScreenshot && !test.isBenchmark }.sortedWith(compareBy({ it.first.seconds }, { it.first.path }))
        var room = budget - direct.sumOf { it.first.seconds }
        val fits = candidates.takeWhile { (test, _) -> (test.seconds <= room).also { if (it) room -= test.seconds } }
        val pastBudget = candidates.drop(fits.size).map { it.first }
        val run = direct + fits

        fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
        fun classes(tests: List<TestFile>) = count(tests.sumOf { it.testClasses.size }, "test class", "test classes")
        fun command(task: String, tests: List<TestFile>): String =
            "./gradlew $task " + tests.take(10).joinToString(" ") { "--tests '${it.className}'" } + if (tests.size > 10) "   (+${tests.size - 10} more)" else ""
        val before = buildString {
            val from = if (mergeBase == null) "the last commit" else "${mergeBase.take(9)}, where HEAD left $base"
            val files = count(changed.size, "file", "files")
            if (mergeBase == null) {
                val fetch = if ('/' in base) " (try: git fetch ${base.substringBefore('/')} ${base.substringAfter('/')})" else ""
                appendLine("agentCheck: HEAD has no merge base with $base in this checkout$fetch, so only what is not committed counts.")
            }
            when {
                changed.isEmpty() -> appendLine("agentCheck: nothing has changed since $from, so it only compiles.")
                run.isEmpty() -> appendLine("agentCheck: it has nothing to run for the $files changed since $from, so it only compiles.")
                else -> {
                    appendLine("agentCheck: ${classes(run.map { it.first })} for the $files changed since $from - about ${run.sumOf { it.first.seconds }} s of test time as CI measures it:")
                    val kinds = listOf("changed", "named after", "mentions")
                    run.groupBy({ it.second }, { it.first }).toSortedMap(compareBy({ reason -> kinds.indexOfFirst { reason.startsWith(it) } }, { it }))
                        .forEach { (reason, tests) -> appendLine("  $reason: ${tests.flatMap { it.testClasses }.sorted().joinToString()}") }
                }
            }
            if (pastBudget.isNotEmpty()) {
                val seconds = pastBudget.sumOf { it.seconds }
                val more = pastBudget.sumOf { it.testClasses.size }
                appendLine("Past its $budget s budget, $more more ${if (more == 1) "test class mentions" else "test classes mention"} what changed - about $seconds s; -Papp.agentCheck.seconds=${budget + seconds} takes them in:")
                appendLine("  " + command(":app:testDebugUnitTest", pastBudget))
            }
            if (screenshots.isNotEmpty()) {
                appendLine("Screenshot tests that touch the change, compared against screenshots/:")
                appendLine("  " + command(":app:verifyRoborazziDebug", screenshots))
            }
            if (benchmarks.isNotEmpty()) {
                appendLine("Benchmarks that touch the change, to run alone:")
                appendLine("  " + command(":app:testDebugUnitTest -Papp.testShard=benchmarks", benchmarks))
            }
            if (unmapped.isNotEmpty()) appendLine("No unit test maps to ${unmapped.joinToString()}; only CI's full run checks ${if (unmapped.size == 1) "it" else "them"}.")
        }.trimEnd()
        val after = buildString {
            append(if (run.isEmpty()) "agentCheck passed: it compiled." else "agentCheck passed: ${classes(run.map { it.first })}.")
            val left = listOfNotNull(
                pastBudget.takeIf { it.isNotEmpty() }?.let { "${classes(it)} past the budget" },
                screenshots.takeIf { it.isNotEmpty() }?.let { count(it.size, "screenshot test", "screenshot tests") },
                benchmarks.takeIf { it.isNotEmpty() }?.let { count(it.size, "benchmark", "benchmarks") },
            )
            if (left.isNotEmpty()) append(" Not run here: ${left.joinToString()} - the commands are in its plan above.")
            append(" CI runs everything.")
        }
        return AgentCheckPlan(run.flatMap { it.first.classPaths }, before, after)
    }

    /** What `git args` prints in [dir], or null when git is missing or fails. */
    private fun git(dir: File, vararg args: String): String? = runCatching {
        val out = ByteArrayOutputStream()
        val result = exec.exec {
            commandLine("git", "-c", "core.quotePath=false", *args)
            workingDir = dir
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        out.toString(Charsets.UTF_8).trim().takeIf { result.exitValue == 0 }
    }.getOrNull()

    private companion object {
        val packageLine = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
        val topLevelType = Regex("""^(?:(?:public|internal|data|sealed|abstract|open|enum|value|annotation|inline|fun)\s+)*(?:class|interface|object|typealias)\s+(\w+)""", RegexOption.MULTILINE)
        val topLevelFunction = Regex("""^(?:(?:public|internal|inline|suspend|operator|infix|tailrec)\s+)*fun\s+(?:<[^>]*>\s+)?(?:[\w.<>, ?*]+\.)?(\w+)\s*\(""", RegexOption.MULTILINE)
    }
}

/** Logs [text] when its task runs, or fails the task with it. A class rather than a lambda for the configuration cache, as with [TestShardFilter]. */
class AgentCheckMessage(private val text: String, private val fail: Boolean = false) : Action<Task>, java.io.Serializable {
    override fun execute(task: Task) {
        if (fail) throw GradleException(text)
        task.logger.lifecycle(text)
    }
}

val agentCheckPlan: AgentCheckPlan? = if (!agentCheckRequested) null else {
    require(testShard == null) { "agentCheck picks its own test classes; drop -Papp.testShard." }
    providers.of(AgentCheckSelection::class.java) {
        parameters {
            appDir.set(layout.projectDirectory)
            base.set(providers.gradleProperty("app.agentCheck.base").orElse("origin/main"))
            seconds.set(providers.gradleProperty("app.agentCheck.seconds").map(String::toInt).orElse(300))
            classSeconds.set(testClassSeconds)
        }
    }.get()
}

val agentCheckPlanTask: TaskProvider<Task>? = agentCheckPlan?.let { plan ->
    tasks.register("agentCheckPlan") {
        description = "Prints what agentCheck runs, and why."
        doLast(AgentCheckMessage(plan.before))
    }
}

if (agentCheckPlan != null && agentCheckPlan.classPaths.isNotEmpty()) {
    val forksGiven = providers.gradleProperty("app.testForks").isPresent
    tasks.withType<Test>().configureEach {
        if (name == "testDebugUnitTest") {
            mustRunAfter(agentCheckPlanTask)
            agentCheckPlan.classPaths.forEach { include("$it.class", "$it\$*.class") }
            if (!forksGiven) maxParallelForks = 1
        }
    }
}

tasks.register("agentCheck") {
    group = "verification"
    description = "Runs the unit tests a change against origin/main is likely to break; see agentCheck in app/build.gradle.kts."
    if (agentCheckPlan == null) {
        // Asked for by an abbreviation, or as another task's dependency: its tests are picked only when it is named.
        doFirst(AgentCheckMessage("Run agentCheck by its name (./gradlew :app:agentCheck): it picks its tests only when the command line names it.", fail = true))
    } else {
        dependsOn(agentCheckPlanTask, "compileDebugUnitTestKotlin")
        if (agentCheckPlan.classPaths.isNotEmpty()) dependsOn("testDebugUnitTest")
        doLast(AgentCheckMessage(agentCheckPlan.after))
    }
}

// Prints the resolved version so the release workflow can reuse it without duplicating the versionCode scheme, and the
// certificate the build pinned so the workflow can check it against the one that actually signed the APK.
tasks.register("printAppVersion") {
    group = "help"
    description = "Prints the resolved versionName, versionCode and pinned release certificate."
    val resolvedName = appResolvedVersionName
    val resolvedCode = appVersionCode
    val resolvedCert = releaseCertSha256
    doLast {
        println("versionName=$resolvedName")
        println("versionCode=$resolvedCode")
        println("releaseCertSha256=$resolvedCert")
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// tools/transcript-verify
//
// The verification harness (tools/transcript-verify/run.sh): the app's own transcript pipeline — the session exchange,
// the Connect and REST clients, the record pager, the run list, the SSE follower, the trace cache, the presenter, the
// rows, the send gate — run on this JVM against a real account (the API key read from a file named on the command
// line) or, with --replay, against the recorded fixtures. Its sources live in src/test and it runs on the unit-test
// classpath (Robolectric stands in for the Android types the stores need), so nothing of it is in the APK. Options go
// through --args; `--help` documents every call it makes.
// ---------------------------------------------------------------------------------------------------------------------
afterEvaluate {
    val unitTest = tasks.named<Test>("testDebugUnitTest").get()
    tasks.register<JavaExec>("transcriptVerify") {
        group = "verification"
        description = "Runs tools/transcript-verify: the app's transcript pipeline against an account or the fixtures. Options with --args; see --help."
        dependsOn(unitTest.taskDependencies)
        classpath = unitTest.classpath + unitTest.testClassesDirs
        mainClass.set("com.cursorforandroid.tools.transcriptverify.TranscriptVerifyMainKt")
        systemProperties(unitTest.systemProperties)
        jvmArgs(unitTest.jvmArgs)
        maxHeapSize = unitTest.maxHeapSize ?: "3g"
        workingDir = unitTest.workingDir
        standardInput = System.`in`
        outputs.upToDateWhen { false }
    }
}

// `packageRelease` and `signReleaseBundle` are the two tasks that apply a signing config, so this is exact: it does not
// fire for `lintRelease` or `testReleaseUnitTest`, which need no key.
if (releaseSigning == null) {
    val appTaskPrefix = "${project.path}:"
    val signingTasks = setOf("packageRelease", "signReleaseBundle")
    gradle.taskGraph.whenReady {
        val packaging = allTasks.filter { it.path.startsWith(appTaskPrefix) && it.name in signingTasks }
        if (packaging.isEmpty()) return@whenReady
        if (allowUnsignedRelease) {
            logger.warn(
                "app.allowUnsignedRelease is set: ${packaging.joinToString { it.name }} will sign with the public debug key. " +
                    "The result is for build verification only - it must not be published, and it pins no certificate for the updater.",
            )
        } else {
            error(
                "Release signing is not configured, so ${packaging.joinToString { it.name }} cannot produce a publishable artifact.\n" +
                    "  CI:    set the RELEASE_KEYSTORE_BASE64, RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD secrets.\n" +
                    "  Local: create a git-ignored keystore.properties (storeFile / storePassword / keyAlias / keyPassword).\n" +
                    "  Neither: pass -Papp.allowUnsignedRelease=true to build an unpublishable debug-signed release for verification.",
            )
        }
    }
}
