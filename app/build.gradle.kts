import java.util.Properties

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
// `app.versionName` lives in gradle.properties and is overridden with `-Papp.versionName=X.Y.Z` by the release
// workflow, which derives it from the `vX.Y.Z` git tag. versionCode is computed from that name so a tag is the only
// input a release needs:
//
//     MAJOR * 1_000_000 + MINOR * 10_000 + PATCH * 100 + STAGE
//     STAGE: alpha.N -> N (0..24), beta.N -> 25 + N, rc.N -> 50 + N, stable (no pre-release) -> 99
//
// e.g. 0.2.0-alpha.1 -> 20001, 0.2.0-beta.1 -> 20026, 0.2.0-rc.1 -> 20051, 0.2.0 -> 20099. Build metadata after `+`
// is ignored. `-Papp.versionCode=N` overrides the derived value; `-Papp.versionNameSuffix=...` is appended to the
// versionName only (CI uses it to stamp dev builds with the run number and commit).
//
// The in-app updater (domain/AppUpdate.kt, AppVersion.versionCode) reproduces this scheme to compare a release tag
// with the installed BuildConfig.VERSION_CODE; AppVersionTest pins both to the same examples. Change them together.
// ---------------------------------------------------------------------------------------------------------------------
val appVersionName: String = providers.gradleProperty("app.versionName").get()
val appVersionCode: Int = providers.gradleProperty("app.versionCode").map(String::toInt).getOrElse(versionCodeFor(appVersionName))
val appVersionNameSuffix: String? = providers.gradleProperty("app.versionNameSuffix").orNull?.takeIf { it.isNotBlank() }
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
// CI:    RELEASE_KEYSTORE_FILE / RELEASE_KEYSTORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD env vars
// Local: a git-ignored keystore.properties next to settings.gradle.kts with storeFile / storePassword / keyAlias /
//        keyPassword (storeFile is resolved against the repository root).
// When neither is present, release builds are signed with the debug key so `assembleRelease` still produces an
// installable APK; the release workflow reports which key was used.
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
        versionNameSuffix = appVersionNameSuffix
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "GITHUB_REPO", "\"$appGitHubRepo\"")
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
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            buildConfigField("String", "UPDATE_API_BASE_URL", "\"$GITHUB_API_BASE_URL\"")
        }
        debug {
            applicationIdSuffix = ".debug"
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
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

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
    debugImplementation(libs.compose.ui.test.manifest)
}

roborazzi {
    outputDir.set(file("$rootDir/screenshots"))
}

// `-Papp.skipScreenshotTests=true` leaves the Roborazzi walkthrough to the dedicated screenshot job in CI; everything
// else in src/test still runs.
if (providers.gradleProperty("app.skipScreenshotTests").map(String::toBoolean).getOrElse(false)) {
    tasks.withType<Test>().configureEach {
        exclude("com/cursorforandroid/screenshots/**")
    }
}

// Prints the resolved version so the release workflow can reuse it without duplicating the versionCode scheme.
tasks.register("printAppVersion") {
    group = "help"
    description = "Prints the resolved versionName and versionCode."
    val resolvedName = appVersionName + (appVersionNameSuffix ?: "")
    val resolvedCode = appVersionCode
    doLast {
        println("versionName=$resolvedName")
        println("versionCode=$resolvedCode")
    }
}

if (releaseSigning == null) {
    val appTaskPrefix = "${project.path}:"
    gradle.taskGraph.whenReady {
        if (allTasks.any { it.path.startsWith(appTaskPrefix) && it.name.contains("Release") }) {
            logger.warn("Release signing is not configured (RELEASE_KEYSTORE_FILE or keystore.properties); release artifacts will be signed with the debug key.")
        }
    }
}
