package com.cursorforandroid.ui.panel

import android.graphics.Rect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.FoldingFeature
import androidx.window.testing.layout.TestWindowLayoutInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Which folds the shell keeps content off, read from what the platform reports. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HingeTest {

    /** A Fold's inner screen at 2x: 841 x 701dp. */
    private val window = Rect(0, 0, 1682, 1402)

    private fun hinge(vararg features: FoldingFeature) = Hinge.of(TestWindowLayoutInfo(features.toList()), density = 2f)

    @Test
    fun `a vertical fold held half open splits the window at its crease`() {
        val fold = FoldingFeature(window, center = 841, size = 0, state = FoldingFeature.State.HALF_OPENED, orientation = FoldingFeature.Orientation.VERTICAL)
        assertThat(hinge(fold)).isEqualTo(Hinge(420.5.dp, 420.5.dp))
    }

    @Test
    fun `a hinge that hides part of the screen is taken whole, even lying flat`() {
        val hidden = FoldingFeature(window, center = 841, size = 40, state = FoldingFeature.State.FLAT, orientation = FoldingFeature.Orientation.VERTICAL)
        assertThat(hidden.isSeparating).isTrue()
        assertThat(hinge(hidden)).isEqualTo(Hinge(410.5.dp, 430.5.dp))
    }

    @Test
    fun `a Fold lying flat is one screen`() {
        val flat = FoldingFeature(window, center = 841, size = 0, state = FoldingFeature.State.FLAT, orientation = FoldingFeature.Orientation.VERTICAL)
        assertThat(hinge(flat)).isNull()
        assertThat(hinge()).isNull()
    }

    @Test
    fun `a horizontal fold is the tabletop posture, not a split between panes`() {
        val tabletop = FoldingFeature(window, center = 701, size = 0, state = FoldingFeature.State.HALF_OPENED, orientation = FoldingFeature.Orientation.HORIZONTAL)
        assertThat(hinge(tabletop)).isNull()
    }

    @Test
    fun `measured from the start edge, a hinge is mirrored under RTL`() {
        val off = Hinge(400.dp, 420.dp)
        assertThat(off.fromStart(1000.dp, rtl = false)).isEqualTo(off)
        assertThat(off.fromStart(1000.dp, rtl = true)).isEqualTo(Hinge(580.dp, 600.dp))
    }
}
