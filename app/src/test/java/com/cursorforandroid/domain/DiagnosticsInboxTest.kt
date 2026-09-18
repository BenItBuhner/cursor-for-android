package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

/** Where a send from Settings › Debug lands in the Project's store, and what the file holds. */
class DiagnosticsInboxTest {

    @Test
    fun `the file is named by the moment it was written, under the inbox, and holds both exports behind a header`() {
        val at = Instant.parse("2026-09-18T18:30:12.345Z").toEpochMilli()
        assertThat(DiagnosticsInbox.path(at)).isEqualTo("inbox/diagnostics/20260918T183012Z.txt")
        assertThat(DiagnosticsInbox.PROJECT_ID).isEqualTo("bc-bae107cb-2562-40b2-b814-4f8eca874668")

        val text = DiagnosticsInbox.compose("0.3.40", at, "Cursor for Android 0.3.40 · Project diagnostics\nroots=1\n", "Cursor for Android 0.3.40 · transcript diagnostics\nmode=extended\n")
        assertThat(text).startsWith("Cursor for Android 0.3.40 · diagnostics sent from Settings › Debug · 2026-09-18T18:30:12.345Z")
        assertThat(text).contains("########## Project diagnostics ##########\nCursor for Android 0.3.40 · Project diagnostics\nroots=1\n")
        assertThat(text).contains("########## Transcript diagnostics ##########\nCursor for Android 0.3.40 · transcript diagnostics\nmode=extended")
    }
}
