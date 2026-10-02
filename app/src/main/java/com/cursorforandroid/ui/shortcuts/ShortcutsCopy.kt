package com.cursorforandroid.ui.shortcuts

/**
 * The shortcuts as the reader is told them, in the cheat sheet (Ctrl+/) and on Settings › Keyboard shortcuts alike,
 * from the keys they are on ([ShortcutBindings]), so the two never disagree with each other or with [ShortcutKeymap].
 */
object ShortcutsCopy {
    const val TITLE = "Keyboard shortcuts"
    const val HARDWARE_ONLY = "These work from a hardware keyboard, with the composer focused too. The on-screen keyboard is left alone."
    const val CHANGE_IN_SETTINGS = "Change them in Settings › Keyboard shortcuts."
    const val TEXT_EDITING = "Ctrl+A, C, V, X and Z keep editing text as usual."
    const val NOT_SET = "Not set"

    /**
     * One line of the list: the chords that do it (any one of them), and what it does. [shortcut] is the one it
     * shows when its keys can be changed; the fixed lines have none.
     */
    data class Line(val chords: List<List<String>>, val label: String, val detail: String? = null, val shortcut: Shortcut? = null)

    /** A group of [lines] under [title], with [note] under the title where its keys only work somewhere. */
    data class Group(val title: String, val lines: List<Line>, val note: String? = null)

    private fun ctrl(vararg keys: String) = listOf("Ctrl") + keys

    private fun ShortcutBindings.line(shortcut: Shortcut) =
        Line(chords(shortcut).map { it.keys }, shortcut.label, shortcut.detail, shortcut)

    fun groups(bindings: ShortcutBindings): List<Group> = with(bindings) {
        listOf(
            Group(
                GENERAL,
                listOf(
                    line(Shortcut.Search),
                    Line(listOf(ctrl("Tab")), "Switch between recent chats", "Hold Ctrl: Tab steps to older chats, Shift+Tab to newer; let go of Ctrl to open"),
                    Line(listOf(ctrl("1 … 9"), ctrl("0")), "Open one of the first ten sidebar rows", "Ctrl+0 is the tenth; hold Ctrl to see the numbers"),
                    line(Shortcut.NewChat),
                    line(Shortcut.NewProject),
                    line(Shortcut.ToggleSidebar),
                    line(Shortcut.TogglePanel),
                    line(Shortcut.CatchUp),
                    line(Shortcut.ReloadTranscript),
                    line(Shortcut.OpenSettings),
                    line(Shortcut.ShowShortcuts),
                    Line(listOf(listOf("Esc")), "Close", "The palette, a sheet, the viewer, the drawer or the panel; else leaves the text field"),
                ),
            ),
            Group(
                COMPOSER,
                listOf(
                    line(Shortcut.ExpandComposer),
                    Line(listOf(listOf("Enter")), "Send", null),
                    Line(listOf(listOf("Shift", "Enter")), "New line", null),
                    Line(listOf(listOf("Shift", "Tab")), "Cycle the mode", "Through the modes the composer offers, then off"),
                    Line(listOf(listOf("↑"), listOf("↓")), "Move through the / menu", "Enter or Tab picks; Ctrl+N and Ctrl+P move too"),
                ),
            ),
            Group(
                MEDIA,
                listOf(
                    Line(listOf(listOf("Space"), listOf("K")), "Play or pause"),
                    Line(listOf(listOf("J"), listOf("L")), "Back or forward 10 seconds"),
                    Line(listOf(listOf("←"), listOf("→")), "Back or forward 5 seconds", "On a picture, the previous or next one"),
                    Line(listOf(listOf("Shift", "P"), listOf("Shift", "N")), "Previous or next item", "Page Up and Page Down too"),
                    Line(listOf(listOf("<"), listOf(">")), "Slower or faster", "0.25× to 2×, a quarter at a time"),
                    Line(listOf(listOf("M")), "Mute or unmute"),
                    Line(listOf(listOf("Esc")), "Close the viewer"),
                ),
                note = MEDIA_NOTE,
            ),
        )
    }

    const val GENERAL = "General"
    const val COMPOSER = "Composer"
    const val MEDIA = "Media"

    /** Under the Media group: where its keys work. */
    const val MEDIA_NOTE = "In the media viewer, while no text field has the focus."
}
