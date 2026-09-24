package com.cursorforandroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * How a screen other than a chat fits a pane wider than a phone: [contentColumn] is the phone's 16dp gutters up to
 * a pane of 672dp, and a centred 640dp column past that; a sheet stops at the same width, centred in the window; a
 * dialog keeps Material's 280–560dp. The window is a tablet held sideways, the panes the widths the shell gives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-night-mdpi")
class AdaptiveWidthTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** A pane [pane] wide, a column in it, and where the column should come out. */
    private data class Case(val pane: Dp, val left: Dp, val width: Dp, val gutter: Dp = CursorDimens.pageGutter)

    /** Each case's pane stacked in one composition, each column checked against its own left edge and width. */
    private fun assertColumns(vararg cases: Case) {
        compose.setContent {
            Column {
                cases.forEachIndexed { index, case ->
                    Box(Modifier.width(case.pane)) { Box(Modifier.contentColumn(gutter = case.gutter).height(10.dp).testTag("column-$index")) }
                }
            }
        }
        cases.forEachIndexed { index, case -> assertSpan(compose.onNodeWithTag("column-$index").getUnclippedBoundsInRoot(), case.left, case.width) }
    }

    private fun assertSpan(bounds: DpRect, left: Dp, width: Dp) {
        assertThat(bounds.left.value).isWithin(0.5f).of(left.value)
        assertThat((bounds.right - bounds.left).value).isWithin(0.5f).of(width.value)
    }

    @Test
    fun `a phone's pane is its 16dp gutters, as it was`() = assertColumns(
        Case(411.dp, left = 16.dp, width = 379.dp),
        Case(360.dp, left = 16.dp, width = 328.dp),
    )

    // 840dp less the 278dp sidebar.
    @Test
    fun `an unfolded foldable's pane beside the sidebar is still narrower than the column`() = assertColumns(Case(562.dp, left = 16.dp, width = 530.dp))

    @Test
    fun `from 672dp the column stops at 640dp and centres`() = assertColumns(
        Case(672.dp, left = 16.dp, width = 640.dp),
        Case(700.dp, left = 30.dp, width = 640.dp),
    )

    // 1280dp less the 278dp sidebar, and the whole window for the screens that fill it.
    @Test
    fun `a tablet's pane centres the 640dp column`() = assertColumns(
        Case(1002.dp, left = 181.dp, width = 640.dp),
        Case(1280.dp, left = 320.dp, width = 640.dp),
    )

    @Test
    fun `a block with its own insets takes no gutter and gets the same column`() = assertColumns(
        Case(411.dp, left = 0.dp, width = 411.dp, gutter = 0.dp),
        Case(1280.dp, left = 320.dp, width = 640.dp, gutter = 0.dp),
    )

    @Test
    fun `a sheet over a tablet is the column's width, centred`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CursorSheet(onDismiss = {}) { Box(Modifier.fillMaxWidth().height(80.dp).testTag("sheet-body")) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("sheet-body")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertSpan(compose.onNodeWithTag("sheet-body").getUnclippedBoundsInRoot(), left = 320.dp, width = CursorDimens.sheetMaxWidth)
    }

    @Test
    fun `a dialog over a tablet keeps Material's width`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { RunStopDialog(RunInterruption.Stop, onConfirm = {}, onKeepRunning = {}) }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(RunStopTags.DIALOG)).fetchSemanticsNodes().isNotEmpty() }
        val bounds = compose.onNodeWithTag(RunStopTags.DIALOG).getUnclippedBoundsInRoot()
        assertThat((bounds.right - bounds.left).value).isIn(Range.closed(280f, 560f))
    }
}
