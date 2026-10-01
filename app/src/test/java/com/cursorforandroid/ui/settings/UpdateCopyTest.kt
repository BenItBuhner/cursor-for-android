package com.cursorforandroid.ui.settings

import com.cursorforandroid.domain.AppRelease
import com.cursorforandroid.domain.AppVersion
import com.cursorforandroid.domain.ReleaseAsset
import com.cursorforandroid.domain.UpdatePhase
import com.cursorforandroid.domain.UpdateState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The sidebar's hint is the one place the app raises an update with the user, and it always leads to Settings. These
 * pin which states earn a line there and which stay quiet.
 */
class UpdateCopyTest {

    private val release = AppRelease(
        tagName = "v0.3.9",
        version = AppVersion(0, 3, 9),
        isPreRelease = false,
        publishedAtMs = 0L,
        htmlUrl = "https://github.com/x/y/releases/tag/v0.3.9",
        apk = ReleaseAsset("cursor-for-android-0.3.9.apk", "https://example.invalid/app.apk", 3L),
    )

    @Test
    fun `a release a check found, one the user downloaded, and one the system is waiting on them for each get a line`() {
        assertThat(UpdateCopy.hint(UpdateState.Available(release, checkedAtMs = 1L))).isEqualTo("Update available: v0.3.9")
        assertThat(UpdateCopy.hint(UpdateState.Downloaded(release, File("0.apk")))).isEqualTo("Update ready to install · 0.3.9")
        assertThat(UpdateCopy.hint(UpdateState.Installing(release, awaitingConfirmation = true))).isEqualTo("Update waiting for your confirmation")
    }

    @Test
    fun `nothing in flight, nothing to do, and a release this install cannot take all stay quiet`() {
        assertThat(UpdateCopy.hint(UpdateState.Idle)).isNull()
        assertThat(UpdateCopy.hint(UpdateState.Checking)).isNull()
        assertThat(UpdateCopy.hint(UpdateState.UpToDate(checkedAtMs = 1L))).isNull()
        assertThat(UpdateCopy.hint(UpdateState.Downloading(release, bytesRead = 1L, totalBytes = 3L))).isNull()
        // Committed and in the installer's hands: there is nothing for the user to do until a verdict comes back.
        assertThat(UpdateCopy.hint(UpdateState.Installing(release, awaitingConfirmation = false))).isNull()
        assertThat(UpdateCopy.hint(UpdateState.Failed(UpdatePhase.Download, "offline", release))).isNull()
        assertThat(UpdateCopy.hint(UpdateState.Installed("0.3.9"))).isNull()
        assertThat(UpdateCopy.hint(UpdateState.Available(release, checkedAtMs = 1L, signatureMismatch = true))).isNull()
    }
}
