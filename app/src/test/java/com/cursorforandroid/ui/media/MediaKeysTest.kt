package com.cursorforandroid.ui.media

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The viewer's keys as [MediaKeys] reads them: what each asks for on a recording and on a picture, and what it leaves alone. */
class MediaKeysTest {

    private fun video(code: Int, shift: Boolean = false, char: Int = 0) = MediaKeys.action(code, shift, playable = true, char = char)
    private fun picture(code: Int, shift: Boolean = false, rtl: Boolean = false) = MediaKeys.action(code, shift, playable = false, rtl = rtl)

    @Test
    fun `on a recording, Space and K play, J and L seek 10 s, the arrows 5 s, M mutes`() {
        assertThat(video(KeyEvent.KEYCODE_SPACE)).isEqualTo(MediaKey.PlayPause)
        assertThat(video(KeyEvent.KEYCODE_K)).isEqualTo(MediaKey.PlayPause)
        assertThat(video(KeyEvent.KEYCODE_J)).isEqualTo(MediaKey.Seek(-10_000L))
        assertThat(video(KeyEvent.KEYCODE_L)).isEqualTo(MediaKey.Seek(10_000L))
        assertThat(video(KeyEvent.KEYCODE_DPAD_LEFT)).isEqualTo(MediaKey.Seek(-5_000L))
        assertThat(video(KeyEvent.KEYCODE_DPAD_RIGHT)).isEqualTo(MediaKey.Seek(5_000L))
        assertThat(video(KeyEvent.KEYCODE_M)).isEqualTo(MediaKey.Mute)
    }

    @Test
    fun `less-than and greater-than step the speed, by their keys or by the characters a layout puts elsewhere`() {
        assertThat(video(KeyEvent.KEYCODE_COMMA, shift = true)).isEqualTo(MediaKey.Speed(faster = false))
        assertThat(video(KeyEvent.KEYCODE_PERIOD, shift = true)).isEqualTo(MediaKey.Speed(faster = true))
        assertThat(video(KeyEvent.KEYCODE_BACKSLASH, char = '<'.code)).isEqualTo(MediaKey.Speed(faster = false))
        assertThat(video(KeyEvent.KEYCODE_BACKSLASH, shift = true, char = '>'.code)).isEqualTo(MediaKey.Speed(faster = true))
        // A plain comma or period is nothing.
        assertThat(video(KeyEvent.KEYCODE_COMMA)).isNull()
        assertThat(video(KeyEvent.KEYCODE_PERIOD)).isNull()
    }

    @Test
    fun `Shift+N and Shift+P, Page Down and Page Up turn the page on any page, apart from the seek keys`() {
        for (playable in listOf(true, false)) {
            assertThat(MediaKeys.action(KeyEvent.KEYCODE_N, shift = true, playable = playable)).isEqualTo(MediaKey.Page(1))
            assertThat(MediaKeys.action(KeyEvent.KEYCODE_P, shift = true, playable = playable)).isEqualTo(MediaKey.Page(-1))
            assertThat(MediaKeys.action(KeyEvent.KEYCODE_PAGE_DOWN, shift = false, playable = playable)).isEqualTo(MediaKey.Page(1))
            assertThat(MediaKeys.action(KeyEvent.KEYCODE_PAGE_UP, shift = false, playable = playable)).isEqualTo(MediaKey.Page(-1))
            assertThat(MediaKeys.action(KeyEvent.KEYCODE_N, shift = false, playable = playable)).isNull()
            assertThat(MediaKeys.action(KeyEvent.KEYCODE_P, shift = false, playable = playable)).isNull()
        }
    }

    @Test
    fun `on a picture the arrows turn the page the way the pager lays them out, and the player's keys are nothing`() {
        assertThat(picture(KeyEvent.KEYCODE_DPAD_RIGHT)).isEqualTo(MediaKey.Page(1))
        assertThat(picture(KeyEvent.KEYCODE_DPAD_LEFT)).isEqualTo(MediaKey.Page(-1))
        assertThat(picture(KeyEvent.KEYCODE_DPAD_RIGHT, rtl = true)).isEqualTo(MediaKey.Page(-1))
        assertThat(picture(KeyEvent.KEYCODE_DPAD_LEFT, rtl = true)).isEqualTo(MediaKey.Page(1))
        listOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_M).forEach {
            assertThat(picture(it)).isNull()
        }
        assertThat(picture(KeyEvent.KEYCODE_PERIOD, shift = true)).isNull()
    }

    @Test
    fun `a Ctrl, Alt or Meta chord is never the viewer's, so the app's Ctrl+K, Ctrl+P and the rest stay the app's`() {
        listOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_DOWN).forEach { code ->
            assertThat(MediaKeys.action(code, shift = false, ctrl = true, playable = true)).isNull()
            assertThat(MediaKeys.action(code, shift = true, ctrl = true, playable = true)).isNull()
            assertThat(MediaKeys.action(code, shift = false, alt = true, playable = true)).isNull()
            assertThat(MediaKeys.action(code, shift = false, meta = true, playable = true)).isNull()
        }
    }

    @Test
    fun `Shift with a player key, Esc and every other key are left alone`() {
        listOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_DPAD_LEFT).forEach {
            assertThat(video(it, shift = true)).isNull()
        }
        listOf(KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_UP).forEach {
            assertThat(video(it)).isNull()
        }
    }

    @Test
    fun `only a seek goes on as its key repeats`() {
        assertThat(MediaKeys.repeats(MediaKey.Seek(5_000L))).isTrue()
        assertThat(MediaKeys.repeats(MediaKey.PlayPause)).isFalse()
        assertThat(MediaKeys.repeats(MediaKey.Page(1))).isFalse()
        assertThat(MediaKeys.repeats(MediaKey.Speed(true))).isFalse()
        assertThat(MediaKeys.repeats(MediaKey.Mute)).isFalse()
    }

    @Test
    fun `a seek reads out signed, in seconds`() {
        assertThat(MediaKeys.seekLabel(10_000L)).isEqualTo("+10 s")
        assertThat(MediaKeys.seekLabel(-5_000L)).isEqualTo("\u22125 s")
    }
}
