package com.cursorforandroid.ui.components

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Where [CursorMenu] puts its surface, at a density of 1 so pixels read as dp: 4 between the anchor and the menu,
 * 12 of shadow room around the surface (so the popup's offset is the surface's less 12 on each axis), and a 400 × 800
 * window whose usable area is 12 in from every edge, less the keyboard when it is up.
 */
class CursorMenuPlacementTest {

    private val window = IntSize(400, 800)
    private var area = IntRect(12, 12, 388, 788)
    private var placed: MenuPlacement? = null

    private fun place(anchor: IntRect, surface: IntSize, offset: DpOffset = DpOffset.Zero, direction: LayoutDirection = LayoutDirection.Ltr): IntOffset {
        val provider = MenuPositionProvider(Density(1f), offset, area = { area }, onPlaced = { placed = it })
        return provider.calculatePosition(anchor, window, direction, IntSize(surface.width + 24, surface.height + 24))
    }

    @Test
    fun `below its anchor and in line with its start where it fits`() {
        val at = place(IntRect(20, 100, 60, 140), IntSize(200, 150))
        assertThat(at).isEqualTo(IntOffset(20 - 12, 144 - 12))
        assertThat(placed!!.below).isTrue()
    }

    @Test
    fun `in line with its anchor's end when its start would run past the window's edge`() {
        // A chat's "More" at the top right: the menu's right edge meets the button's.
        val at = place(IntRect(340, 40, 380, 80), IntSize(200, 300))
        assertThat(at).isEqualTo(IntOffset(180 - 12, 84 - 12))
    }

    @Test
    fun `held the margin from the edge when neither side of the anchor fits`() {
        val at = place(IntRect(0, 40, 380, 80), IntSize(390, 100))
        assertThat(at.x + 12).isEqualTo(12)
    }

    @Test
    fun `above its anchor when there is no room below, as over the docked composer`() {
        val at = place(IntRect(24, 740, 60, 776), IntSize(280, 250))
        assertThat(at).isEqualTo(IntOffset(24 - 12, 740 - 4 - 250 - 12))
        assertThat(placed!!.below).isFalse()
        // It grows from its bottom edge, where the anchor is.
        assertThat(placed!!.origin.pivotFractionY).isEqualTo((12f + 250f) / 274f)
    }

    @Test
    fun `the keyboard coming up moves it above an anchor it opened below`() {
        val row = IntRect(0, 300, 400, 340)
        val surface = IntSize(200, 180)
        assertThat(place(row, surface).y + 12).isEqualTo(344)
        area = area.copy(bottom = 488)
        assertThat(place(row, surface).y + 12).isEqualTo(300 - 4 - 180)
        assertThat(placed!!.below).isFalse()
    }

    @Test
    fun `too tall for either side, it takes the roomier one and stays inside the window`() {
        val at = place(IntRect(0, 380, 400, 420), IntSize(200, 600))
        assertThat(at.y + 12).isEqualTo(788 - 600)
    }

    @Test
    fun `right to left, its start is the anchor's right edge and the offset is mirrored`() {
        val at = place(IntRect(200, 100, 300, 140), IntSize(180, 100), offset = DpOffset(8.dp, 0.dp), direction = LayoutDirection.Rtl)
        assertThat(at.x + 12).isEqualTo(300 - 180 - 8)
    }
}
