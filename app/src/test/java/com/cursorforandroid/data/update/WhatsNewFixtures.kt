package com.cursorforandroid.data.update

import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.domain.ReleaseNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A release as GitHub publishes it for this repository: the workflow's header table and install line, the curated
 * notes (v0.3.37's, verbatim), the reinstall notice, and the generated commit list. What the parser has to cut
 * around, and what the What's new page and its two surfaces are shown with.
 */
object WhatsNewFixtures {
    const val VERSION = "0.3.37"
    const val TAG = "v0.3.37"
    const val HTML_URL = "https://github.com/BenItBuhner/cursor-for-android/releases/tag/v0.3.37"
    /** Within the screenshot clock's year (2025-01-15 14:00 UTC), so the header reads "Released Jan 14". */
    const val PUBLISHED_AT = "2025-01-14T15:12:11Z"
    const val PUBLISHED_AT_MS = 1_736_867_531_000L

    val HEADER = """
        | | |
        |---|---|
        | versionName | `0.3.37` |
        | versionCode | `33799` |
        | Commit | a189fca55460ccfb496bed16d1b304a461b68664 |
        | Signing certificate (SHA-256) | `379a87854ada552f58933bf981624333bf8f6a170cd70999d3d35060da2be56b` |
        | Crash reports | None - this build was made without a project to report to. |

        **Install:** download `cursor-for-android-0.3.37.apk`, then `adb install -r cursor-for-android-0.3.37.apk` (or open it on the device). `SHA256SUMS.txt` holds the checksums of the APK and AAB; `*-mapping.txt` is the R8 mapping for de-obfuscating stack traces. Installs of **v0.2.0 and later** update in place — same signing key.
    """.trimIndent()

    const val LEAD = "Project coordinator replies show up again in busy Projects (the app stopped after the first 20 runs)."

    val CURATED = """
        ## Coordinator chats

        **Project coordinator replies show up again in busy Projects (the app stopped after the first 20 runs).** Under Extended mode the record path read one page of runs and never paged further, while the record's window kept widening; a coordinator's turn behind more injected worker reports than that first page paired with no run, so its reply — which lives in the run's log — never arrived and a stretch showed its narration with no message above it.

        - **The run list is paged until it covers the record's window**, in the background on open and inline before older pages, so every turn on screen pairs with its run and gets its footer and its reply from the run's log.
        - **Record growth re-reads the newest runs**, so a turn finished while the phone was not following lines up and has its log replayed instead of pairing with the wrong run.
        - **A message whose text did not reach the device** is an explicit row — "Message text unavailable" with the reason and **Reload** beside it — never a bare success marker; an empty reply still being written shows as pending.
        - Transcript diagnostics say, per coordinator turn, what the record had of the message and what reached the screen from where.
    """.trimIndent()

    val REINSTALL_NOTICE = """
        ## Upgrading from v0.1.0: uninstall, then install

        **v0.2.0 and later are signed with the project's release key; v0.1.0 was signed with a debug key.** Android cannot update between the two, so the update check in v0.1.0 cannot install this in place. Download `cursor-for-android-0.3.37.apk` above, **uninstall Cursor, install the APK, and sign in again.** From v0.2.0 on, updates install in place, and the app refuses any APK not signed with the certificate named in the header.
    """.trimIndent()

    val GENERATED = """
        <!-- Release notes generated using configuration in .github/release.yml at a189fca55460ccfb496bed16d1b304a461b68664 -->

        ## What's Changed
        ### Other changes
        * Bump app.versionName to 0.3.37 now that v0.3.36 is tagged by @BenItBuhner in https://github.com/BenItBuhner/cursor-for-android/pull/201
        * Coordinator replies in Extended mode: the run list is paged to cover the record's window by @BenItBuhner in https://github.com/BenItBuhner/cursor-for-android/pull/202


        **Full Changelog**: https://github.com/BenItBuhner/cursor-for-android/compare/v0.3.36...v0.3.37
    """.trimIndent()

    /** The whole body, the parts joined as GitHub holds them. */
    val BODY = "$HEADER\n\n$CURATED\n\n$REINSTALL_NOTICE\n\n\n$GENERATED"

    /** The header and the generated list alone: the body between the workflow publishing and the notes being pasted in. */
    val BODY_WITHOUT_NOTES = "$HEADER\n\n\n$GENERATED"

    private fun jsonString(text: String): String = buildString {
        append('"')
        text.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /** `GET /repos/{repo}/releases/tags/{tag}` as GitHub answers it, for a MockWebServer. */
    fun releaseJson(tag: String = TAG, body: String? = BODY, publishedAt: String = PUBLISHED_AT, draft: Boolean = false, prerelease: Boolean = false): String {
        val version = tag.removePrefix("v")
        return """
            {
              "tag_name": ${jsonString(tag)},
              "name": ${jsonString(tag)},
              "draft": $draft,
              "prerelease": $prerelease,
              "published_at": ${jsonString(publishedAt)},
              "html_url": ${jsonString("https://github.com/BenItBuhner/cursor-for-android/releases/tag/$tag")},
              "body": ${if (body == null) "null" else jsonString(body)},
              "assets": [
                {"name": "cursor-for-android-$version.apk", "size": 5818614, "browser_download_url": "https://github.com/BenItBuhner/cursor-for-android/releases/download/$tag/cursor-for-android-$version.apk", "content_type": "application/vnd.android.package-archive"}
              ]
            }
        """.trimIndent()
    }

    /** The notes the parser reads out of [BODY], for tests that start from the parsed form. */
    fun notes(versionName: String = VERSION): ReleaseNotes = ReleaseNotes(
        tagName = "v$versionName",
        versionName = versionName,
        publishedAtMs = PUBLISHED_AT_MS,
        htmlUrl = "https://github.com/BenItBuhner/cursor-for-android/releases/tag/v$versionName",
        markdown = CURATED,
        lead = LEAD,
    )

    /** A client that can reach nothing: for repositories whose notes are already on disk and must not ask GitHub. */
    fun unreachableClient(): GitHubReleasesClient = GitHubReleasesClient(
        OkHttpClient.Builder().connectTimeout(200, TimeUnit.MILLISECONDS).readTimeout(200, TimeUnit.MILLISECONDS).build(),
        GitHubFixtures.OWNER_REPO,
        apiBaseUrl = "http://127.0.0.1:9/",
        freeSpace = { Long.MAX_VALUE / 2 },
    )

    /**
     * A repository whose disk already holds [notes] for [versionName] (or nothing, with null), read in: what a test
     * of the page or its surfaces starts from. [prefs] is the store the read state lives in.
     */
    fun repository(prefs: PreferencesStore, cacheDir: File, versionName: String = VERSION, notes: ReleaseNotes? = notes(), now: () -> Long = System::currentTimeMillis): WhatsNewRepository {
        val cache = JsonDiskCache(cacheDir, dispatcher = Dispatchers.Unconfined)
        if (notes != null) {
            runBlocking {
                cache.write(WhatsNewRepository.CACHE_KEY, CachedReleaseNotes.serializer(), WhatsNewRepository.CACHE_VERSION, CachedReleaseNotes(versionName, now(), notes))
            }
        }
        return WhatsNewRepository(unreachableClient(), prefs, cache, installedVersionName = versionName, now = now)
            .also { if (notes != null) runBlocking { it.refreshNow() } }
    }
}
