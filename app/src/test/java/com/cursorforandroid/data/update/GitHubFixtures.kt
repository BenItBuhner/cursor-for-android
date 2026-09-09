package com.cursorforandroid.data.update

/**
 * A release list in the shape `GET /repos/{owner}/{repo}/releases` returns, modelled on the repository's real
 * `v0.1.0` payload: newest first, a debug-signed first release without a certificate row, a signed stable release
 * with GitHub asset digests, a release candidate, plus a draft and an unrelated tag that must be ignored.
 */
object GitHubFixtures {
    const val RELEASE_CERT_SHA256 = "3f2a9c0d1e8b7a6f5e4d3c2b1a0f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a29"
    const val APK_020_SHA256 = "07ed1912c66a412b829974ab7a22cf4f7e3427f21b1d5eeddf2e58e240a35568"
    const val OWNER_REPO = "BenItBuhner/cursor-for-android"
    private const val DOWNLOADS = "https://github.com/$OWNER_REPO/releases/download"

    private fun notes(version: String, code: Int, signingRow: String) = """
        || | |
        ||---|---|
        || versionName | `$version` |
        || versionCode | `$code` |
        || Commit | 731de660b7773fd5e9678deb9e3bfb650ee4c386 |
        |$signingRow
        |
        |**Install:** download `cursor-for-android-$version.apk`, then `adb install -r cursor-for-android-$version.apk`.
        |
        |## What's Changed
        |* Something by @BenItBuhner in https://github.com/$OWNER_REPO/pull/40
    """.trimMargin().replace("\n", "\\n").replace("\"", "\\\"")

    private const val SIGNED_ROW = "| Signing certificate (SHA-256) | `$RELEASE_CERT_SHA256` |"
    private const val DEBUG_ROW = "| Signing | **Debug key** - release signing secrets were not configured for this build; it will not install over a release-signed build. |"

    private fun asset(downloads: String, tag: String, name: String, size: Long, contentType: String, digest: String? = null) = """
        {"name":"$name","size":$size,"content_type":"$contentType","browser_download_url":"$downloads/$tag/$name"${digest?.let { ""","digest":"sha256:$it"""" } ?: ""}}
    """.trimIndent()

    /** The version name each fixture release's versionCode stands for, as an APK built from it would declare it. */
    private val VERSION_NAMES = mapOf(10099L to "0.1.0", 20099L to "0.2.0", 30051L to "0.3.0-rc.1")

    /** Null for a code no fixture release has, which stands for an archive that declares no version name. */
    fun versionNameFor(versionCode: Long): String? = VERSION_NAMES[versionCode]

    val releasesJson: String get() = releasesJson()

    /** [downloads] is the base the asset URLs hang off; [apk020Sha256] and [apk020Size] describe the stable APK served there. */
    fun releasesJson(downloads: String = DOWNLOADS, apk020Sha256: String = APK_020_SHA256, apk020Size: Long = 2_300_000L, rcSize: Long = 2_400_000L): String {
        fun asset(tag: String, name: String, size: Long, contentType: String, digest: String? = null) = asset(downloads, tag, name, size, contentType, digest)
        return """
        [
          {
            "tag_name": "v0.3.0-rc.1", "name": "v0.3.0-rc.1", "draft": false, "prerelease": true,
            "published_at": "2026-09-10T09:30:00Z",
            "html_url": "https://github.com/$OWNER_REPO/releases/tag/v0.3.0-rc.1",
            "body": "${notes("0.3.0-rc.1", 30051, SIGNED_ROW)}",
            "assets": [
              ${asset("v0.3.0-rc.1", "cursor-for-android-0.3.0-rc.1.apk", rcSize, "application/vnd.android.package-archive")},
              ${asset("v0.3.0-rc.1", "SHA256SUMS.txt", 190, "text/plain")}
            ]
          },
          {
            "tag_name": "v0.3.0", "name": "v0.3.0 (draft)", "draft": true, "prerelease": false,
            "published_at": null,
            "html_url": "https://github.com/$OWNER_REPO/releases/tag/untagged-1",
            "body": "",
            "assets": [ ${asset("v0.3.0", "cursor-for-android-0.3.0.apk", 1, "application/vnd.android.package-archive")} ]
          },
          {
            "tag_name": "screenshots-2026-09", "name": "Screenshot refresh", "draft": false, "prerelease": false,
            "published_at": "2026-09-08T12:00:00Z",
            "html_url": "https://github.com/$OWNER_REPO/releases/tag/screenshots-2026-09",
            "body": "Not an app release.",
            "assets": [ ${asset("screenshots-2026-09", "cursor-for-android-0.2.0.apk", 1, "application/vnd.android.package-archive")} ]
          },
          {
            "tag_name": "v0.2.0", "name": "v0.2.0", "draft": false, "prerelease": false,
            "published_at": "2026-09-07T16:53:20Z",
            "html_url": "https://github.com/$OWNER_REPO/releases/tag/v0.2.0",
            "body": "${notes("0.2.0", 20099, SIGNED_ROW)}",
            "assets": [
              ${asset("v0.2.0", "cursor-for-android-0.2.0-mapping.txt", 37272606, "text/plain", "53974e4f29e1dcf2d03c6036eea5a7715dd6193f944f59cc633afa15d64bbe4a")},
              ${asset("v0.2.0", "cursor-for-android-0.2.0.aab", 4857291, "application/x-authorware-bin", "a43d83c9a44a16c5c02c85fa6d03e075d3b30769a563ddbc853e9482a8635fff")},
              ${asset("v0.2.0", "cursor-for-android-0.2.0.apk", apk020Size, "application/vnd.android.package-archive", apk020Sha256)},
              ${asset("v0.2.0", "SHA256SUMS.txt", 190, "text/plain", "117bdc3aa83b526dd1663d22cda47dabb0c19c22a3759919a0df4ff309125bf4")}
            ]
          },
          {
            "tag_name": "v0.1.1", "name": "v0.1.1", "draft": false, "prerelease": false,
            "published_at": "2026-09-07T10:00:00Z",
            "html_url": "https://github.com/$OWNER_REPO/releases/tag/v0.1.1",
            "body": "Source-only tag; the workflow failed before uploading.",
            "assets": []
          },
          {
            "tag_name": "v0.1.0", "name": "v0.1.0", "draft": false, "prerelease": false,
            "published_at": "2026-09-07T06:41:44Z",
            "html_url": "https://github.com/$OWNER_REPO/releases/tag/v0.1.0",
            "body": "${notes("0.1.0", 10099, DEBUG_ROW)}",
            "assets": [
              ${asset("v0.1.0", "cursor-for-android-0.1.0.apk", 2281764, "application/vnd.android.package-archive", "07ed1912c66a412b829974ab7a22cf4f7e3427f21b1d5eeddf2e58e240a35568")},
              ${asset("v0.1.0", "SHA256SUMS.txt", 190, "text/plain")}
            ]
          }
        ]
        """.trimIndent()
    }
}
