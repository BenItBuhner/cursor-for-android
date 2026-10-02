package com.cursorforandroid.ui.shortcuts

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The keys each shortcut is on: what is saved and read back, a chord given to a shortcut while another is on it
 * (swapped or replaced, never left on both), resets, and the chords no shortcut can be moved to.
 */
class ShortcutBindingsTest {

    private val defaults = ShortcutBindings.Defaults
    private val ctrlB = KeyChord.ctrl(KeyEvent.KEYCODE_B)
    private val ctrlJ = KeyChord.ctrl(KeyEvent.KEYCODE_J)
    private val altS = KeyChord(KeyEvent.KEYCODE_S, alt = true)

    private fun assertNoChordOnTwo(bindings: ShortcutBindings) {
        val all = Shortcut.entries.flatMap(bindings::chords)
        assertThat(all).containsNoDuplicates()
    }

    @Test
    fun `out of the box every shortcut is on its defaults, and nothing is written`() {
        Shortcut.entries.forEach { assertThat(defaults.chords(it)).isEqualTo(it.defaults) }
        assertThat(defaults.isAllDefault).isTrue()
        assertThat(defaults.encode()).isEmpty()
        assertNoChordOnTwo(defaults)
    }

    @Test
    fun `a chord reads back as it was written`() {
        listOf(ctrlB, altS, KeyChord(KeyEvent.KEYCODE_F5, ctrl = true, shift = true, alt = true)).forEach {
            assertThat(KeyChord.decode(it.encode())).isEqualTo(it)
        }
        listOf("", "ctrl+", "meta+30", "ctrl+ctrl+30", "ctrl+x", "ctrl+0", "ctrl+100000").forEach {
            assertThat(KeyChord.decode(it)).isNull()
        }
    }

    @Test
    fun `only moved shortcuts are written, and they read back the same`() {
        val moved = defaults.assign(Shortcut.ToggleSidebar, ctrlJ).assign(Shortcut.CatchUp, altS)
        assertThat(moved.encode()).isEqualTo("sidebar=ctrl+${KeyEvent.KEYCODE_J};catch_up=alt+${KeyEvent.KEYCODE_S}")
        assertThat(ShortcutBindings.parse(moved.encode())).isEqualTo(moved)
        assertThat(ShortcutBindings.parse(null)).isEqualTo(defaults)
        assertThat(ShortcutBindings.parse("")).isEqualTo(defaults)
    }

    @Test
    fun `a shortcut left on no keys reads back on none`() {
        val replaced = defaults.assign(Shortcut.CatchUp, ctrlB, ConflictResolution.Replace)
        assertThat(replaced.chords(Shortcut.ToggleSidebar)).isEmpty()
        val read = ShortcutBindings.parse(replaced.encode())
        assertThat(read.chords(Shortcut.ToggleSidebar)).isEmpty()
        assertThat(read).isEqualTo(replaced)
    }

    @Test
    fun `unknown shortcuts, unreadable and blocked chords are dropped as it reads`() {
        val read = ShortcutBindings.parse("gone=ctrl+30;sidebar=ctrl+${KeyEvent.KEYCODE_C},junk,ctrl+${KeyEvent.KEYCODE_J};panel=")
        assertThat(read.chords(Shortcut.ToggleSidebar)).containsExactly(ctrlJ)
        assertThat(read.chords(Shortcut.TogglePanel)).isEmpty()
        assertNoChordOnTwo(read)
    }

    @Test
    fun `a chord two shortcuts claim as it reads stays with the one moved to it`() {
        val read = ShortcutBindings.parse("catch_up=ctrl+${KeyEvent.KEYCODE_B}")
        assertThat(read.chords(Shortcut.CatchUp)).containsExactly(ctrlB)
        assertThat(read.chords(Shortcut.ToggleSidebar)).isEmpty()
        assertNoChordOnTwo(read)
    }

    @Test
    fun `a free chord moves the shortcut onto it alone`() {
        assertThat(defaults.check(Shortcut.Search, ctrlJ)).isEqualTo(ChordCheck.Free)
        val moved = defaults.assign(Shortcut.Search, ctrlJ)
        assertThat(moved.chords(Shortcut.Search)).containsExactly(ctrlJ)
        assertThat(moved.owner(KeyChord.ctrl(KeyEvent.KEYCODE_P))).isNull()
        assertThat(moved.owner(KeyChord.ctrl(KeyEvent.KEYCODE_K))).isNull()
        assertThat(moved.isDefault(Shortcut.Search)).isFalse()
        assertThat(moved.isAllDefault).isFalse()
    }

    @Test
    fun `the keys a shortcut is on already are unchanged, and one of its two keys is free to keep alone`() {
        assertThat(defaults.check(Shortcut.ToggleSidebar, ctrlB)).isEqualTo(ChordCheck.Unchanged)
        assertThat(defaults.check(Shortcut.Search, KeyChord.ctrl(KeyEvent.KEYCODE_K))).isEqualTo(ChordCheck.Free)
    }

    @Test
    fun `a chord another shortcut is on is taken by it`() {
        assertThat(defaults.check(Shortcut.CatchUp, ctrlB)).isEqualTo(ChordCheck.Taken(Shortcut.ToggleSidebar))
    }

    @Test
    fun `replace leaves the other shortcut on whatever else it had`() {
        val next = defaults.assign(Shortcut.CatchUp, KeyChord.ctrl(KeyEvent.KEYCODE_K), ConflictResolution.Replace)
        assertThat(next.chords(Shortcut.CatchUp)).containsExactly(KeyChord.ctrl(KeyEvent.KEYCODE_K))
        assertThat(next.chords(Shortcut.Search)).containsExactly(KeyChord.ctrl(KeyEvent.KEYCODE_P))
        assertNoChordOnTwo(next)
    }

    @Test
    fun `swap hands the other shortcut the keys the moved one gave up`() {
        val next = defaults.assign(Shortcut.CatchUp, ctrlB, ConflictResolution.Swap)
        assertThat(next.chords(Shortcut.CatchUp)).containsExactly(ctrlB)
        assertThat(next.chords(Shortcut.ToggleSidebar)).containsExactly(KeyChord.ctrl(KeyEvent.KEYCODE_R))
        assertNoChordOnTwo(next)
    }

    @Test
    fun `a blocked chord changes nothing`() {
        listOf(
            KeyChord.ctrl(KeyEvent.KEYCODE_C),
            KeyChord.ctrl(KeyEvent.KEYCODE_TAB),
            KeyChord.ctrl(KeyEvent.KEYCODE_3),
            KeyChord(KeyEvent.KEYCODE_J),
            KeyChord(KeyEvent.KEYCODE_TAB, alt = true),
        ).forEach { chord ->
            assertThat(defaults.check(Shortcut.NewChat, chord)).isInstanceOf(ChordCheck.Blocked::class.java)
            assertThat(defaults.assign(Shortcut.NewChat, chord)).isEqualTo(defaults)
        }
    }

    @Test
    fun `reset puts one shortcut back and leaves the rest as they are`() {
        val moved = defaults.assign(Shortcut.ToggleSidebar, ctrlJ).assign(Shortcut.CatchUp, altS)
        assertThat(moved.resetConflicts(Shortcut.ToggleSidebar)).isEmpty()
        val reset = moved.reset(Shortcut.ToggleSidebar)
        assertThat(reset.isDefault(Shortcut.ToggleSidebar)).isTrue()
        assertThat(reset.chords(Shortcut.CatchUp)).containsExactly(altS)
    }

    @Test
    fun `reset takes its defaults back from a shortcut moved onto one, as swap or replace says`() {
        val moved = defaults.assign(Shortcut.ToggleSidebar, ctrlJ).assign(Shortcut.CatchUp, ctrlB)
        assertThat(moved.resetConflicts(Shortcut.ToggleSidebar)).containsExactly(ctrlB to Shortcut.CatchUp)

        val replaced = moved.reset(Shortcut.ToggleSidebar, ConflictResolution.Replace)
        assertThat(replaced.chords(Shortcut.ToggleSidebar)).containsExactly(ctrlB)
        assertThat(replaced.chords(Shortcut.CatchUp)).isEmpty()
        assertNoChordOnTwo(replaced)

        val swapped = moved.reset(Shortcut.ToggleSidebar, ConflictResolution.Swap)
        assertThat(swapped.chords(Shortcut.ToggleSidebar)).containsExactly(ctrlB)
        assertThat(swapped.chords(Shortcut.CatchUp)).containsExactly(ctrlJ)
        assertNoChordOnTwo(swapped)
    }

    @Test
    fun `resetting everything is the defaults`() {
        val moved = defaults.assign(Shortcut.ToggleSidebar, ctrlJ).assign(Shortcut.CatchUp, ctrlB, ConflictResolution.Swap)
        val reset = Shortcut.entries.fold(moved) { acc, s -> acc.reset(s) }
        assertThat(reset).isEqualTo(defaults)
        assertThat(reset.encode()).isEmpty()
    }

    @Test
    fun `no sequence of moves ever leaves a chord on two shortcuts`() {
        val chords = listOf(ctrlB, ctrlJ, altS, KeyChord.ctrl(KeyEvent.KEYCODE_F), KeyChord.ctrl(KeyEvent.KEYCODE_R, shift = true))
        var bindings = defaults
        var step = 0
        repeat(40) {
            val target = Shortcut.entries[step % Shortcut.entries.size]
            val chord = chords[(step * 7) % chords.size]
            val resolution = if (step % 2 == 0) ConflictResolution.Swap else ConflictResolution.Replace
            bindings = if (step % 5 == 4) bindings.reset(target, resolution) else bindings.assign(target, chord, resolution)
            assertNoChordOnTwo(bindings)
            assertThat(ShortcutBindings.parse(bindings.encode())).isEqualTo(bindings)
            step++
        }
    }

    @Test
    fun `the rules keep typing, the system's, a text field's and the fixed chords off every shortcut`() {
        fun blocked(code: Int, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false, meta: Boolean = false) =
            ShortcutRules.blocked(code, ctrl, shift, alt, meta)
        assertThat(blocked(KeyEvent.KEYCODE_J)).contains("Hold Ctrl or Alt")
        assertThat(blocked(KeyEvent.KEYCODE_J, shift = true)).contains("Hold Ctrl or Alt")
        assertThat(blocked(KeyEvent.KEYCODE_J, ctrl = true, meta = true)).contains("Meta")
        assertThat(blocked(KeyEvent.KEYCODE_TAB, alt = true)).isEqualTo("Android keeps Alt+Tab for itself.")
        assertThat(blocked(KeyEvent.KEYCODE_F4, alt = true)).isEqualTo("Android keeps Alt+F4 for itself.")
        assertThat(blocked(KeyEvent.KEYCODE_SPACE, ctrl = true)).isEqualTo("Android keeps Ctrl+Space for itself.")
        assertThat(blocked(KeyEvent.KEYCODE_VOLUME_UP, ctrl = true)).contains("Android keeps")
        assertThat(blocked(KeyEvent.KEYCODE_V, ctrl = true)).isEqualTo("Ctrl+V is kept for editing text.")
        assertThat(blocked(KeyEvent.KEYCODE_Z, ctrl = true, shift = true)).isEqualTo("Ctrl+Shift+Z is kept for editing text.")
        assertThat(blocked(KeyEvent.KEYCODE_DPAD_LEFT, alt = true)).isEqualTo("Alt+Left is kept for editing text.")
        assertThat(blocked(KeyEvent.KEYCODE_TAB, ctrl = true, shift = true)).contains("Ctrl+Tab")
        assertThat(blocked(KeyEvent.KEYCODE_7, ctrl = true)).contains("sidebar's rows")

        assertThat(blocked(KeyEvent.KEYCODE_J, ctrl = true)).isNull()
        assertThat(blocked(KeyEvent.KEYCODE_S, alt = true)).isNull()
        assertThat(blocked(KeyEvent.KEYCODE_7, ctrl = true, shift = true)).isNull()
        assertThat(blocked(KeyEvent.KEYCODE_C, ctrl = true, alt = true)).isNull()
        Shortcut.entries.flatMap { it.defaults }.forEach {
            assertThat(blocked(it.keyCode, it.ctrl, it.shift, it.alt)).isNull()
        }
    }

    @Test
    fun `keys are named as a US keyboard prints them`() {
        assertThat(KeyChord.ctrl(KeyEvent.KEYCODE_SLASH, shift = true).label).isEqualTo("Ctrl+Shift+?")
        assertThat(KeyChord.ctrl(KeyEvent.KEYCODE_COMMA).label).isEqualTo("Ctrl+,")
        assertThat(KeyChord(KeyEvent.KEYCODE_F5, ctrl = true, shift = true, alt = true).label).isEqualTo("Ctrl+Shift+Alt+F5")
    }
}
