package com.cursorforandroid.ui.media

import androidx.compose.runtime.saveable.SaverScope
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The viewer's model on its own: which thumbnail stands aside, what a tap on an unlisted figure opens, what a saved session keeps. */
class MediaViewerStateTest {

    private val entries = listOf(
        MediaEntry("a.png", MediaEntry.Kind.Image, "A"),
        MediaEntry("b.mp4", MediaEntry.Kind.Video, durationMs = 4_000),
        MediaEntry("c.png", MediaEntry.Kind.Image),
    )

    @Test
    fun `the thumbnail of the page on screen stands aside, and only that one`() {
        val state = MediaViewerState(null)
        val a = state.register("a.png")
        val alsoA = state.register("a.png")
        val b = state.register("b.mp4")
        assertThat(state.isHidden(a)).isFalse()

        state.open("agent", entries, "a.png", slot = a)
        assertThat(state.phase).isEqualTo(MediaViewerState.Phase.Opening)
        assertThat(state.currentIndex).isEqualTo(0)
        // Every thumbnail of the open figure, wherever it is drawn; none of any other.
        assertThat(state.isHidden(a)).isTrue()
        assertThat(state.isHidden(alsoA)).isTrue()
        assertThat(state.isHidden(b)).isFalse()

        state.currentIndex = 1
        assertThat(state.isHidden(a)).isFalse()
        assertThat(state.isHidden(b)).isTrue()

        state.finishClose()
        assertThat(state.isHidden(b)).isFalse()
        assertThat(state.isOpen).isFalse()
    }

    @Test
    fun `a figure not among the conversation's media opens on its own`() {
        val state = MediaViewerState(null)
        state.open("agent", entries, "elsewhere.png", fallback = MediaEntry("elsewhere.png", MediaEntry.Kind.Image, "Elsewhere"))
        assertThat(state.session?.entries).containsExactly(MediaEntry("elsewhere.png", MediaEntry.Kind.Image, "Elsewhere"))
        assertThat(state.currentIndex).isEqualTo(0)
    }

    @Test
    fun `a close asked of the last session does not carry over to the next`() {
        val state = MediaViewerState(null)
        state.open("agent", entries, "a.png")
        state.close()
        assertThat(state.closeRequests).isEqualTo(1)
        state.finishClose()
        state.open("agent", entries, "c.png")
        assertThat(state.closeRequests).isEqualTo(0)
        assertThat(state.isOpen).isTrue()
    }

    @Test
    fun `Esc's close puts the viewer away at once, with no close transform asked for`() {
        val state = MediaViewerState(null)
        val a = state.register("a.png")
        state.open("agent", entries, "a.png", slot = a)
        state.closeNow()
        assertThat(state.isOpen).isFalse()
        assertThat(state.phase).isEqualTo(MediaViewerState.Phase.Closed)
        assertThat(state.closeRequests).isEqualTo(0)
        assertThat(state.isHidden(a)).isFalse()
    }

    @Test
    fun `a second open while one is showing is ignored`() {
        val state = MediaViewerState(null)
        state.open("agent", entries, "c.png")
        state.open("agent", entries, "a.png")
        assertThat(state.currentIndex).isEqualTo(2)
    }

    @Test
    fun `a saved session comes back open on the same page, at rest`() {
        val state = MediaViewerState(null)
        state.open("agent", entries, "b.mp4", autoplay = true)
        state.currentIndex = 2
        val saved = with(MediaViewerState.Saver) { SaverScope { true }.save(state) }
        val restored = MediaViewerState.Saver.restore(saved!!)!!
        assertThat(restored.isOpen).isTrue()
        assertThat(restored.phase).isEqualTo(MediaViewerState.Phase.Open)
        assertThat(restored.progress.value).isEqualTo(1f)
        assertThat(restored.currentIndex).isEqualTo(2)
        assertThat(restored.session?.entries).isEqualTo(entries)
        assertThat(restored.session?.agentId).isEqualTo("agent")
        assertThat(restored.session?.autoplay).isFalse()
    }

    @Test
    fun `nothing open saves nothing`() {
        assertThat(with(MediaViewerState.Saver) { SaverScope { true }.save(MediaViewerState(null)) }).isNull()
    }
}
