package com.cursorforandroid.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The curated section of a release body, cut by shape from what the workflow, GitHub and the runbook put around it. */
class ReleaseNotesFormatTest {

    @Test
    fun `the header table, the install line, the reinstall notice and the generated list are cut away`() {
        val curated = ReleaseNotesFormat.curated(WhatsNewFixtures.BODY)

        assertThat(curated).isEqualTo(WhatsNewFixtures.CURATED)
        assertThat(curated).startsWith("## Goals")
        assertThat(curated).contains("(`/goal …`)")
        listOf("versionCode", "Signing certificate", "**Install:**", "Upgrading from v0.1.0", "uninstall Cursor", "Release notes generated", "What's Changed", "Full Changelog", "pull/133")
            .forEach { assertThat(curated).doesNotContain(it) }
    }

    @Test
    fun `a body with only the header and the generated list has no notes`() {
        assertThat(ReleaseNotesFormat.curated(WhatsNewFixtures.BODY_WITHOUT_NOTES)).isNull()
        assertThat(ReleaseNotesFormat.curated(WhatsNewFixtures.HEADER)).isNull()
        assertThat(ReleaseNotesFormat.curated("")).isNull()
        assertThat(ReleaseNotesFormat.curated("   \n\n")).isNull()
        assertThat(ReleaseNotesFormat.curated(null)).isNull()
    }

    @Test
    fun `a hand-written body with none of the parts is kept whole, whatever its line endings`() {
        val body = "Fixes a crash on launch.\r\n\r\n- One `thing`\r\n- Another"

        assertThat(ReleaseNotesFormat.curated(body)).isEqualTo("Fixes a crash on launch.\n\n- One `thing`\n- Another")
    }

    @Test
    fun `the reinstall notice is cut as a section, ending at the next heading of its level`() {
        val body = """
            ## Fixes

            - A fix.

            ## Upgrading from v0.1.0: uninstall, then install

            Do the thing.

            ### A sub-heading inside the notice

            Still the notice.

            ## Stability

            - Kept.
        """.trimIndent()

        assertThat(ReleaseNotesFormat.curated(body)).isEqualTo("## Fixes\n\n- A fix.\n\n## Stability\n\n- Kept.")
    }

    @Test
    fun `a debug-key header and a body whose generated list has no comment are cut the same way`() {
        val body = """
            | | |
            |---|---|
            | versionName | `0.4.0` |
            | Signing | **Debug key** - release signing secrets were not configured for this build. |

            **Install:** download `cursor-for-android-0.4.0.apk`.

            Some notes.

            ## What's Changed
            * A commit by @someone in https://example.com/pull/1
        """.trimIndent()

        assertThat(ReleaseNotesFormat.curated(body)).isEqualTo("Some notes.")
    }

    @Test
    fun `nothing inside a fenced block is read as a heading or a marker`() {
        val body = """
            Run this:

            ```sh
            ## What's Changed
            **Full Changelog**: nope
            ## Upgrading from v0.1.0
            ```

            - Then read on.
        """.trimIndent()

        assertThat(ReleaseNotesFormat.curated(body)).isEqualTo(body)
    }

    @Test
    fun `the lead is the bold opening sentence of the first paragraph, as plain text`() {
        assertThat(ReleaseNotesFormat.lead(WhatsNewFixtures.CURATED)).isEqualTo(WhatsNewFixtures.LEAD)
    }

    @Test
    fun `notes that go straight to bullets lead with the first item, and a paragraph without bold is the lead whole`() {
        assertThat(ReleaseNotesFormat.lead("## Projects\n\n- **Projects no longer leak** into the list. Root cause: things.\n- Another."))
            .isEqualTo("Projects no longer leak")
        assertThat(ReleaseNotesFormat.lead("## Fixes\n\nA plain first line\nthat wraps onto a second.\n\nMore."))
            .isEqualTo("A plain first line that wraps onto a second.")
        assertThat(ReleaseNotesFormat.lead("1. Numbered `first` item\n2. Second")).isEqualTo("Numbered first item")
    }

    @Test
    fun `code spans, emphasis and links are plain in the lead, and notes that open on a table or a code block have none`() {
        assertThat(ReleaseNotesFormat.lead("See [the docs](https://example.com) for `adb install -r` and __more__.")).isEqualTo("See the docs for adb install -r and more.")
        assertThat(ReleaseNotesFormat.lead("| a | b |\n|---|---|\n| 1 | 2 |")).isNull()
        assertThat(ReleaseNotesFormat.lead("```\ncode\n```\n\nText after.")).isNull()
        assertThat(ReleaseNotesFormat.lead("## Only a heading")).isNull()
    }

    @Test
    fun `a release maps to its notes, and a draft, a non-version tag or an empty body to none`() {
        val dto = GitHubReleaseDto(tagName = WhatsNewFixtures.TAG, publishedAt = WhatsNewFixtures.PUBLISHED_AT, htmlUrl = WhatsNewFixtures.HTML_URL, body = WhatsNewFixtures.BODY)

        val notes = ReleaseNotesFormat.from(dto)!!
        assertThat(notes.tagName).isEqualTo(WhatsNewFixtures.TAG)
        assertThat(notes.versionName).isEqualTo(WhatsNewFixtures.VERSION)
        assertThat(notes.publishedAtMs).isEqualTo(WhatsNewFixtures.PUBLISHED_AT_MS)
        assertThat(notes.htmlUrl).isEqualTo(WhatsNewFixtures.HTML_URL)
        assertThat(notes.markdown).isEqualTo(WhatsNewFixtures.CURATED)
        assertThat(notes.lead).isEqualTo(WhatsNewFixtures.LEAD)
        assertThat(notes).isEqualTo(WhatsNewFixtures.notes())

        assertThat(ReleaseNotesFormat.from(dto.copy(draft = true))).isNull()
        assertThat(ReleaseNotesFormat.from(dto.copy(tagName = "screenshots-2026-09"))).isNull()
        assertThat(ReleaseNotesFormat.from(dto.copy(body = WhatsNewFixtures.BODY_WITHOUT_NOTES))).isNull()
        assertThat(ReleaseNotesFormat.from(dto.copy(body = null))).isNull()
        // A pre-release tag is a version too; its page is titled the way the build names itself.
        assertThat(ReleaseNotesFormat.from(dto.copy(tagName = "v0.4.0-rc.1", prerelease = true))!!.versionName).isEqualTo("0.4.0-rc.1")
    }
}
