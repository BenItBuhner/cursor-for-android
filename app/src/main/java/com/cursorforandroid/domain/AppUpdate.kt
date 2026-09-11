package com.cursorforandroid.domain

import kotlinx.serialization.Serializable
import java.io.File

/**
 * A `MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD]` version as the release tags and `BuildConfig.VERSION_NAME` carry it.
 *
 * [versionCode] reproduces the scheme in `app/build.gradle.kts` exactly, so the code derived from a release tag can
 * be compared with the installed build's `BuildConfig.VERSION_CODE`: `MAJOR * 1_000_000 + MINOR * 10_000 +
 * PATCH * 100 + STAGE`, where STAGE is 0–24 for `alpha.N` (and any other pre-release word, such as the `dev.N` of CI
 * builds), 25–49 for `beta.N`, 50–98 for `rc.N` and 99 for a stable version. Every pre-release therefore sorts
 * below its final build, and a `0.2.0-dev.42` build sees `0.2.0-rc.1` and `0.2.0` as updates.
 */
@Serializable
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** `rc.1`, `beta.2`, `dev.42`; null for a stable version. */
    val preRelease: String? = null,
    /** Build metadata after `+` (`gabc1234` on CI builds); ignored for ordering, like the Gradle scheme does. */
    val build: String? = null,
) : Comparable<AppVersion> {

    val isPreRelease: Boolean get() = preRelease != null

    val versionCode: Int get() = major * 1_000_000 + minor * 10_000 + patch * 100 + stage(preRelease)

    override fun compareTo(other: AppVersion): Int = versionCode.compareTo(other.versionCode)

    /** The version without the tag's `v`, e.g. `0.2.0-rc.1+gabc1234`. */
    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        preRelease?.let { append('-').append(it) }
        build?.let { append('+').append(it) }
    }

    companion object {
        private val SEMVER = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+([0-9A-Za-z.-]+))?$""")

        /**
         * Parses a version name or a `vX.Y.Z` tag; null when the text is not one, or when it falls outside the range
         * the versionCode scheme can represent (the same bounds the build enforces).
         */
        fun parse(text: String): AppVersion? {
            val match = SEMVER.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, preRelease, build) = match.destructured
            val version = AppVersion(
                major = major.toIntOrNull() ?: return null,
                minor = minor.toIntOrNull() ?: return null,
                patch = patch.toIntOrNull() ?: return null,
                preRelease = preRelease.ifEmpty { null },
                build = build.ifEmpty { null },
            )
            if (version.minor >= 100 || version.patch >= 100 || version.major >= 2_000) return null
            return version
        }

        /** `alpha.N` (or any other word) -> N (0..24), `beta.N` -> 25 + N, `rc.N` -> 50 + N, stable -> 99. */
        fun stage(preRelease: String?): Int {
            if (preRelease.isNullOrEmpty()) return 99
            val parts = preRelease.split('.')
            val iteration = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return when (parts.first().lowercase()) {
                "beta" -> 25 + iteration.coerceIn(0, 24)
                "rc" -> 50 + iteration.coerceIn(0, 48)
                else -> iteration.coerceIn(0, 24)
            }
        }
    }
}

/** One downloadable file of a GitHub release. */
@Serializable
data class ReleaseAsset(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    /** Lowercase hex SHA-256 when GitHub reported one for the asset (its `digest` field); verified after download. */
    val sha256: String? = null,
)

/** A published release of this app, as far as the updater needs to know it. */
@Serializable
data class AppRelease(
    /** `v0.2.0`, `v0.2.0-rc.1`. */
    val tagName: String,
    val version: AppVersion,
    val isPreRelease: Boolean,
    val publishedAtMs: Long,
    /** The release page on GitHub, where the generated notes are. */
    val htmlUrl: String,
    val apk: ReleaseAsset,
    /** `SHA256SUMS.txt`, when the release carries one; used when the APK asset has no digest of its own. */
    val checksumsUrl: String? = null,
    /**
     * SHA-256 of the certificate the APK was signed with, from the release notes header the release workflow
     * writes (lowercase hex, no separators). Lets a release signed with a different key be recognised before it is
     * downloaded: Android refuses to install it over this build either way.
     */
    val signingCertSha256: String? = null,
) {
    val versionCode: Int get() = version.versionCode
    val versionName: String get() = version.toString()
}

/** Which step of an update went wrong; decides what "Retry" does. */
enum class UpdatePhase { Check, Download, Install }

/**
 * Where the updater stands. One value at a time; the Settings row, the sidebar hint and the background job all read
 * it. A [Failed] state keeps the release it concerns (if any) so the user can retry from where it stopped.
 */
sealed interface UpdateState {
    /** The release this state is about, when there is one. */
    val release: AppRelease? get() = null

    /** Never checked in this installation (or nothing is remembered). */
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** The newest eligible release is the installed one, or older. */
    data class UpToDate(val checkedAtMs: Long) : UpdateState

    /**
     * A newer release exists and has not been downloaded. [signatureMismatch] is true when the release and this
     * install are signed with different keys — the release names a certificate this build does not trust, or this
     * install predates release signing and carries the debug key. Android cannot update either in place, so the
     * release page and a manual reinstall are offered instead of a download.
     */
    data class Available(override val release: AppRelease, val checkedAtMs: Long, val signatureMismatch: Boolean = false) : UpdateState

    /** [totalBytes] is -1 while the size is unknown. */
    data class Downloading(override val release: AppRelease, val bytesRead: Long, val totalBytes: Long) : UpdateState {
        val fraction: Float? get() = if (totalBytes > 0) (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }

    /** Downloaded and verified; [apk] is the file an install session is fed from. */
    data class Downloaded(override val release: AppRelease, val apk: File) : UpdateState

    /**
     * An install session has been committed. [awaitingConfirmation] is true once the system asked for the user's
     * consent (Android 8–11, or Android 12+ before "Install unknown apps" was allowed); the confirmation is on
     * screen, or a notification is waiting to bring it up.
     */
    data class Installing(override val release: AppRelease, val awaitingConfirmation: Boolean = false) : UpdateState

    data class Failed(val phase: UpdatePhase, val message: String, override val release: AppRelease? = null) : UpdateState

    /** The first launch after an update the app installed itself; shown once in Settings. */
    data class Installed(val versionName: String) : UpdateState

    /** True while a network or install operation is in flight and another must not start. */
    val isBusy: Boolean
        get() = this is Checking || this is Downloading || (this is Installing && !awaitingConfirmation)
}
