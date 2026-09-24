package com.cursorforandroid.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Settings on a pane wider than a phone: its cards stop at the 640dp column, centred, where a phone's still run to
 * its 16dp gutters; and the New chat page picker's miniatures draw the page's column rather than the whole pane, no
 * larger than a capped size and at about a phone's scale, so a tablet's are neither oversized, mostly empty, nor full
 * of words too small to read.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-night-mdpi")
class SettingsAdaptiveWidthTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The account row's left edge and width in Settings laid out in a pane [pane] wide. */
    private fun accountRow(pane: Dp): Pair<Float, Float> {
        val graph = AppGraph(ApplicationProvider.getApplicationContext<Context>())
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Box(Modifier.width(pane).fillMaxHeight()) { SettingsScreen(graph, USER, isDemo = true, onOpenSidebar = null, onBack = null) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(SettingsTags.ACCOUNT_ROW)).fetchSemanticsNodes().isNotEmpty() }
        val bounds = compose.onNodeWithTag(SettingsTags.ACCOUNT_ROW).getUnclippedBoundsInRoot()
        return bounds.left.value to (bounds.right - bounds.left).value
    }

    @Test
    fun `beside a tablet's sidebar the cards are a centred 640dp column`() {
        // 1280dp less the 278dp sidebar: (1002 - 640) / 2 either side.
        val (left, width) = accountRow(1002.dp)
        assertThat(width).isWithin(0.5f).of(640f)
        assertThat(left).isWithin(0.5f).of(181f)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h914dp-night-mdpi")
    fun `on a phone the cards run to the 16dp gutters, as they did`() {
        val (left, width) = accountRow(411.dp)
        assertThat(width).isWithin(0.5f).of(379f)
        assertThat(left).isWithin(0.5f).of(16f)
    }

    @Test
    fun `a phone's miniature is nine tenths of its option, the whole page in proportion`() {
        val page = miniaturePage(DpSize(411.dp, 914.dp))
        assertThat(page).isEqualTo(DpSize(411.dp, 411.dp * 1.75f))
        val size = miniature(pane = 411.dp, page)
        assertThat(size.width.value).isWithin(0.01f).of(room(411.dp).value * 0.9f)
        assertThat(size.height.value).isWithin(0.01f).of(size.width.value * 1.75f)
        assertThat(size.height).isLessThan(MiniatureMaxHeight)
        // A small phone's is smaller in step, not squeezed.
        val small = miniature(pane = 360.dp, miniaturePage(DpSize(360.dp, 780.dp)))
        assertThat(small.width.value).isWithin(0.01f).of(room(360.dp).value * 0.9f)
        assertThat(small.width).isLessThan(size.width)
    }

    @Test
    fun `a large phone's miniature is no taller than the cap`() {
        val size = miniature(pane = 448.dp, miniaturePage(DpSize(448.dp, 998.dp)))
        assertThat(size.height.value).isWithin(0.01f).of(MiniatureMaxHeight.value)
        assertThat(size.width).isLessThan(room(448.dp) * 0.9f)
    }

    @Test
    fun `a tablet's miniature draws the page's column, no wider than the cap`() {
        val page = miniaturePage(DpSize(1002.dp, 800.dp))
        // The New Chat page's 640dp column and its 16dp gutters, not the 1002dp pane's empty margins.
        assertThat(page).isEqualTo(DpSize(672.dp, 800.dp))
        val size = miniature(pane = 1002.dp, page)
        assertThat(size.width).isEqualTo(MiniatureMaxWidth)
        assertThat(size.height).isLessThan(MiniatureMaxHeight)
        assertReadable(page, size)
        // Upright, the pane is narrower than the column: the page is the pane's, as on a phone, and as tall as the cap.
        val upright = miniaturePage(DpSize(522.dp, 1280.dp))
        assertThat(upright.width).isEqualTo(522.dp)
        val uprightSize = miniature(pane = 522.dp, upright)
        assertThat(uprightSize.height.value).isWithin(0.01f).of(MiniatureMaxHeight.value)
        assertReadable(upright, uprightSize)
    }

    @Test
    fun `an unfolded foldable's miniature is no wider than the cap`() {
        val page = miniaturePage(DpSize(562.dp, 700.dp))
        assertThat(page).isEqualTo(DpSize(562.dp, 700.dp))
        val size = miniature(pane = 562.dp, page)
        assertThat(size.width).isEqualTo(MiniatureMaxWidth)
        assertThat(size.height).isLessThan(MiniatureMaxHeight)
        assertReadable(page, size)
    }

    @Test
    fun `a miniature's corner is a screen's at its scale, between 4dp and 6dp`() {
        assertThat(miniatureRadius(miniature(pane = 411.dp, miniaturePage(DpSize(411.dp, 914.dp))).width).value).isWithin(0.1f).of(4.5f)
        assertThat(miniatureRadius(MiniatureMaxWidth)).isEqualTo(6.dp)
        assertThat(miniatureRadius(40.dp)).isEqualTo(4.dp)
    }

    /** An option's room for its miniature in a pane [pane] wide: a third of the card, less its insets, gaps and ring. */
    private fun room(pane: Dp): Dp = ((minOf(pane - 32.dp, 640.dp) - RowInset * 2 - 12.dp * 2) / 3) - 8.dp

    private fun miniature(pane: Dp, page: DpSize): DpSize = miniatureSize(page, room(pane))

    /** Drawn at no less than four fifths of a phone's scale, so the words in it read about as large. */
    private fun assertReadable(page: DpSize, size: DpSize) {
        val phonePage = miniaturePage(DpSize(411.dp, 914.dp))
        val phone = miniature(pane = 411.dp, phonePage).width / phonePage.width
        assertThat(size.width / page.width).isAtLeast(phone * 0.8f)
    }

    private companion object {
        val USER = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null)
    }
}
