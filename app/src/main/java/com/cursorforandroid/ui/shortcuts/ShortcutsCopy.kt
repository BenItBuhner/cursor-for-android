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

    data class Group(val title: String, val lines: List<Line>)

    private fun ctrl(vararg keys: String) = listOf("Ctrl") + keys

    private fun ShortcutBindings.line(shortcut: Shortcut) =
        Line(chords(shortcut).map { it.keys }, shortcut.label, shortcut.detail, shortcut)

    fun groups(bindings: ShortcutBindings): List<Group> = with(bindings) {
        listOf(
            Group(
                "Go to",
                listOf(
                    line(Shortcut.Search),
                    Line(listOf(ctrl("Tab")), "Switch between recent chats", "Hold Ctrl: Tab steps to older chats, Shift+Tab to newer; let go of Ctrl to open"),
                    Line(listOf(ctrl("1 … 9"), ctrl("0")), "Open one of the first ten sidebar rows", "Ctrl+0 is the tenth; hold Ctrl to see the numbers"),
                    line(Shortcut.NewChat),
                    line(Shortcut.NewProject),
                    line(Shortcut.OpenSettings),
                    line(Shortcut.ShowShortcuts),
                ),
            ),
            Group(
                "Layout",
                listOf(
                    line(Shortcut.ToggleSidebar),
                    line(Shortcut.TogglePanel),
                    line(Shortcut.ExpandComposer),
                    Line(listOf(listOf("Esc")), "Close", "The palette, a sheet, the viewer, the drawer or the panel; else leaves the text field"),
                ),
            ),
            Group(
                "In a chat",
                listOf(
                    line(Shortcut.CatchUp),
                    line(Shortcut.ReloadTranscript),
                ),
            ),
        )
    }
}
