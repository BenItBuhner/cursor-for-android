package com.cursorforandroid.ui.shortcuts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.PaletteEntry
import com.cursorforandroid.domain.TranscriptHit
import com.cursorforandroid.domain.TranscriptPassage
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The search palette's highlighted row stays on the chat it is on while the results change under the same query — the
 * transcripts coming in as they are read, the list moving on — so Enter opens the chat that was highlighted; a new
 * query starts from the top again.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h600dp-night-240dpi")
class CommandPaletteSelectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val palette = PaletteState()
    private var entries by mutableStateOf(emptyList<PaletteEntry>())
    private var transcripts by mutableStateOf(emptyMap<String, List<TranscriptPassage>>())
    private var reading by mutableStateOf(true)
    private val opened = mutableListOf<Pair<String, TranscriptHit?>>()

    private fun entry(id: String, title: String, updatedAt: Long) = PaletteEntry(id, title, "bennett/visual-engine", isProject = false, updatedAtMillis = updatedAt)

    private fun show(initial: List<PaletteEntry>) {
        entries = initial
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CommandPalette(
                    state = palette,
                    entries = entries,
                    switcher = emptyList(),
                    transcripts = transcripts,
                    readingTranscripts = reading,
                    glyph = {},
                    onOpen = { entry, hit -> opened += entry.agentId to hit },
                    onDismiss = palette::close,
                )
            }
        }
        compose.runOnIdle { palette.openSearch() }
        compose.waitForIdle()
    }

    private val field get() = compose.onNodeWithTag(PaletteTags.FIELD)

    private fun type(query: String, firstRow: String) {
        field.performTextInput(query)
        awaitRow(0, firstRow)
    }

    private fun awaitRow(index: Int, title: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasTestTag(PaletteTags.result(index)) and hasText(title, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        field.performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun assertSelected(index: Int, title: String) {
        compose.onNode(hasTestTag(PaletteTags.result(index)) and hasText(title, substring = true)).assertIsSelected()
    }

    private val atlasChats = listOf(
        entry("bc-1", "Atlas packing", 40),
        entry("bc-2", "Atlas baking", 30),
        entry("bc-3", "Atlas review", 20),
        entry("bc-x", "Material pass", 50),
    )

    @Test
    fun `transcripts read after Down Down leave the highlight on the chat it was on, and Enter opens that chat`() {
        show(atlasChats)
        type("atlas", "Atlas packing")
        press(Key.DirectionDown, 2)
        assertSelected(2, "Atlas review")

        transcripts = mapOf("bc-x" to listOf(TranscriptPassage("a1", "Packed the shared atlas for every prop.")))
        reading = false
        awaitRow(3, "Material pass")

        assertSelected(2, "Atlas review")
        press(Key.Enter)
        assertThat(opened.map { it.first }).containsExactly("bc-3")
    }

    @Test
    fun `a chat listed above the highlighted one moves the highlight down with its chat`() {
        show(atlasChats)
        type("atlas", "Atlas packing")
        press(Key.DirectionDown, 2)
        assertSelected(2, "Atlas review")

        entries = listOf(entry("bc-new", "Atlas lighting", 60)) + atlasChats
        awaitRow(0, "Atlas lighting")

        assertSelected(3, "Atlas review")
        press(Key.Enter)
        assertThat(opened.map { it.first }).containsExactly("bc-3")
    }

    @Test
    fun `the highlighted chat leaving the results keeps the highlight in place, on the last row at most`() {
        show(atlasChats)
        type("atlas", "Atlas packing")
        press(Key.DirectionDown, 2)
        assertSelected(2, "Atlas review")

        entries = atlasChats.filter { it.agentId != "bc-3" }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Atlas review", substring = true)).fetchSemanticsNodes().isEmpty() }

        assertSelected(1, "Atlas baking")
    }

    @Test
    fun `a new query starts from the top row again`() {
        show(atlasChats)
        type("atlas", "Atlas packing")
        press(Key.DirectionDown, 2)
        assertSelected(2, "Atlas review")

        field.performTextClearance()
        type("atlas b", "Atlas baking")
        assertSelected(0, "Atlas baking")

        field.performTextClearance()
        type("atlas", "Atlas packing")
        assertSelected(0, "Atlas packing")
    }

    @Test
    fun `the highlighted row is scrolled back into view when its chat moves off screen`() {
        val many = (0 until 30).map { entry("bc-$it", "Atlas scene ${'A' + it}", 1_000L - it) }
        show(many)
        type("atlas", "Atlas scene A")
        press(Key.DirectionDown, 20)
        assertSelected(20, "Atlas scene U")
        compose.onNodeWithTag(PaletteTags.result(20)).assertIsDisplayed()

        entries = many.map { if (it.agentId == "bc-20") it.copy(updatedAtMillis = 5_000L) else it }
        awaitRow(0, "Atlas scene U")

        assertSelected(0, "Atlas scene U")
        compose.onNodeWithTag(PaletteTags.result(0)).assertIsDisplayed()
        press(Key.Enter)
        assertThat(opened.map { it.first }).containsExactly("bc-20")
    }

    @Test
    fun `the highlighted row is kept in view when the rows above it grow as longer transcripts are read`() {
        val many = (0 until 30).map { entry("bc-$it", "Scene ${'A' + it}", 1_000L - it) }
        transcripts = many.associate { it.agentId to listOf(TranscriptPassage("a1", "The atlas.")) }
        show(many)
        type("atlas", "Scene A")
        press(Key.DirectionDown, 20)
        assertSelected(20, "Scene U")
        compose.onNodeWithTag(PaletteTags.result(20)).assertIsDisplayed()

        val long = "Packed the shared atlas for every prop in the scene, then baked the mipmaps, checked the seams on the " +
            "rock set and the foliage cards, and wrote the budget per material into the notes for the next pass."
        transcripts = many.associate { it.agentId to listOf(TranscriptPassage("a1", long)) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("rock set", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()

        assertSelected(20, "Scene U")
        compose.onNodeWithTag(PaletteTags.result(20)).assertIsDisplayed()
    }
}
