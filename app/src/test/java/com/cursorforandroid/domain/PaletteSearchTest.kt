package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The search palette's matching: names before transcripts, a title that starts with the query above one that only
 * contains it, Projects a nose ahead, and a transcript match's newest place with its snippet and count.
 */
class PaletteSearchTest {

    private fun entry(id: String, title: String, repo: String? = null, project: Boolean = false, at: Long = 0L) =
        PaletteEntry(id, title, repo, project, at)

    private fun ids(results: List<PaletteResult>) = results.map { it.entry.agentId }

    @Test
    fun `a blank query finds nothing`() {
        val entries = listOf(entry("a", "Material atlas"))
        assertThat(PaletteSearch.search("   ", entries, emptyMap())).isEmpty()
        assertThat(PaletteSearch.search("", entries, emptyMap())).isEmpty()
    }

    @Test
    fun `a title that starts with the query ranks over a word that does, over one that only holds it`() {
        val entries = listOf(
            entry("inside", "Rework the scatlas", at = 30),
            entry("word", "Fix atlas seams", at = 20),
            entry("starts", "Atlas packing", at = 10),
        )
        val results = PaletteSearch.search("atlas", entries, emptyMap())
        assertThat(ids(results)).containsExactly("starts", "word", "inside").inOrder()
        assertThat(results.first().titleMatch).isEqualTo(0 until 5)
        assertThat(results[1].titleMatch).isEqualTo(4 until 9)
    }

    @Test
    fun `every word typed must be in the name, in any order, case and spacing aside`() {
        val entries = listOf(entry("hit", "House environment overhaul"), entry("miss", "House cleaning"))
        assertThat(ids(PaletteSearch.search("  OVERHAUL   house ", entries, emptyMap()))).containsExactly("hit")
    }

    @Test
    fun `the repository counts toward the name, below the title`() {
        val entries = listOf(
            entry("repo", "Tidy the build", repo = "acme/cursor-for-android", at = 50),
            entry("title", "Android widgets", at = 1),
        )
        assertThat(ids(PaletteSearch.search("android", entries, emptyMap()))).containsExactly("title", "repo").inOrder()
        assertThat(ids(PaletteSearch.search("tidy cursor", entries, emptyMap()))).containsExactly("repo")
    }

    @Test
    fun `a Project is a nose ahead of a chat matched as well, and the newer chat of two alike first`() {
        val entries = listOf(
            entry("older", "Search palette", at = 1),
            entry("newer", "Search overhaul", at = 9),
            entry("project", "Search sweep", project = true, at = 0),
        )
        assertThat(ids(PaletteSearch.search("search", entries, emptyMap()))).containsExactly("project", "newer", "older").inOrder()
    }

    @Test
    fun `a transcript match comes after every name match, with its newest place, a snippet and a count`() {
        val entries = listOf(entry("named", "Shared material library", at = 1), entry("said", "House environment overhaul", at = 99))
        val transcripts = mapOf(
            "said" to listOf(
                TranscriptPassage("m1", "First we need a shared material atlas for the props."),
                TranscriptPassage("g2", "Thinking about the atlas layout."),
                TranscriptPassage("m3", "Done: the Shared Material Atlas is packed.\nIt is 4k and the shared material atlas is mipmapped."),
            ),
        )
        val results = PaletteSearch.search("shared material", entries, transcripts)
        assertThat(ids(results)).containsExactly("named", "said").inOrder()

        val hit = results[1]
        assertThat(hit.hit).isEqualTo(TranscriptHit("m3", "shared material", matches = 3))
        val snippet = checkNotNull(hit.snippet)
        assertThat(snippet.text.substring(snippet.matchStart, snippet.matchEnd)).isEqualTo("shared material")
        assertThat(snippet.text).doesNotContain("\n")
        assertThat(results[0].hit).isNull()
    }

    @Test
    fun `a transcript is searched for the phrase as typed, not its words apart`() {
        val entries = listOf(entry("a", "Chat"))
        val transcripts = mapOf("a" to listOf(TranscriptPassage("m1", "material for the atlas")))
        assertThat(PaletteSearch.search("material atlas", entries, transcripts)).isEmpty()
        assertThat(ids(PaletteSearch.search("the  ATLAS", entries, transcripts))).containsExactly("a")
    }

    @Test
    fun `a chat matched by name is not listed again for its transcript`() {
        val entries = listOf(entry("a", "Atlas work"))
        val transcripts = mapOf("a" to listOf(TranscriptPassage("m1", "atlas atlas")))
        val results = PaletteSearch.search("atlas", entries, transcripts)
        assertThat(results).hasSize(1)
        assertThat(results.single().hit).isNull()
    }

    @Test
    fun `only as many rows as asked for`() {
        val entries = (1..80).map { entry("c$it", "Chat $it", at = it.toLong()) }
        assertThat(PaletteSearch.search("chat", entries, emptyMap())).hasSize(PaletteSearch.DEFAULT_LIMIT)
        assertThat(ids(PaletteSearch.search("chat", entries, emptyMap(), limit = 2))).containsExactly("c80", "c79").inOrder()
    }

    @Test
    fun `a snippet starts a word or so before the match and says where the text goes on`() {
        val text = "word ".repeat(30) + "needle" + " tail".repeat(60)
        val at = text.indexOf("needle")
        val snippet = PaletteSearch.snippet(text, at, at + 6)
        assertThat(snippet.text).startsWith("…")
        assertThat(snippet.text).endsWith("…")
        assertThat(snippet.text.substring(snippet.matchStart, snippet.matchEnd)).isEqualTo("needle")
        assertThat(snippet.text.substring(1, snippet.matchStart)).startsWith("word")

        val short = PaletteSearch.snippet("a needle here", 2, 8)
        assertThat(short).isEqualTo(Snippet("a needle here", 2, 8))
    }
}
