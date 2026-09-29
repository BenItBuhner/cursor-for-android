package com.cursorforandroid.ui.components

import androidx.compose.ui.unit.IntRect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The caption bar's geometry: when the window counts as having one the app draws into, and how far a row standing in
 * it is held in from each end by the system's controls, wherever the row is in the window.
 */
class CaptionBarTest {

    private val appMenu = IntRect(0, 0, 160, 40)
    private val windowControls = IntRect(1000, 0, 1200, 40)
    private val bar = CaptionBar(40, listOf(appMenu, windowControls))

    @Test
    fun `a row across the whole window clears the app menu at its start and the window controls at its end`() {
        assertThat(bar.clearance(0f, 1200f)).isEqualTo(CaptionBar.Clearance(left = 160f, right = 200f))
    }

    @Test
    fun `a rail's header at the start clears only the app menu`() {
        assertThat(bar.clearance(0f, 300f)).isEqualTo(CaptionBar.Clearance(left = 160f, right = 0f))
    }

    @Test
    fun `a chat header beside the rail clears only the window controls`() {
        assertThat(bar.clearance(300f, 1200f)).isEqualTo(CaptionBar.Clearance(left = 0f, right = 200f))
    }

    @Test
    fun `a chat header between the rail and a pinned panel clears nothing`() {
        assertThat(bar.clearance(300f, 900f)).isEqualTo(CaptionBar.Clearance(left = 0f, right = 0f))
    }

    @Test
    fun `a row the controls only partly reach is held in by the part they reach`() {
        assertThat(bar.clearance(100f, 1100f)).isEqualTo(CaptionBar.Clearance(left = 60f, right = 100f))
    }

    @Test
    fun `no caption, a caption under the status bar, or no controls reported leave the headers where they were`() {
        assertThat(CaptionBar.of(captionTopPx = 0, statusTopPx = 0, controls = listOf(windowControls))).isNull()
        assertThat(CaptionBar.of(captionTopPx = 40, statusTopPx = 24, controls = listOf(windowControls))).isNull()
        assertThat(CaptionBar.of(captionTopPx = 40, statusTopPx = 0, controls = emptyList())).isNull()
        assertThat(CaptionBar.of(captionTopPx = 40, statusTopPx = 0, controls = listOf(windowControls))).isEqualTo(CaptionBar(40, listOf(windowControls)))
    }
}
