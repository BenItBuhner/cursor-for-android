package com.cursorforandroid.ui.shortcuts

/**
 * The shortcuts as the reader is told them, in the cheat sheet (Ctrl+/) and on Settings › Keyboard shortcuts alike, so
 * the two never disagree with each other or with [ShortcutKeymap].
 */
object ShortcutsCopy {
    const val TITLE = "Keyboard shortcuts"
    const val SETTINGS_DETAIL = "For a hardware keyboard: search, switch chats, toggle the sidebar and panel."
    const val HARDWARE_ONLY = "These work from a hardware keyboard, with the composer focused too. The on-screen keyboard is left alone."
    const val TEXT_EDITING = "Ctrl+A, C, V, X and Z keep editing text as usual."

    /** One line of the list: the chords that do it (any one of them), and what it does. */
    data class Line(val chords: List<List<String>>, val label: String, val detail: String? = null)

    data class Group(val title: String, val lines: List<Line>)

    private fun ctrl(vararg keys: String) = listOf("Ctrl") + keys

    val groups: List<Group> = listOf(
        Group(
            "Go to",
            listOf(
                Line(listOf(ctrl("F"), ctrl("K")), "Search chats and Projects", "Titles, repositories and the transcripts kept on this device"),
                Line(listOf(ctrl("Tab")), "Switch between recent chats", "Hold Ctrl: Tab steps to older chats, Shift+Tab to newer; let go of Ctrl to open"),
                Line(listOf(ctrl("1 … 9"), ctrl("0")), "Open one of the first ten sidebar rows", "Ctrl+0 is the tenth; hold Ctrl to see the numbers"),
                Line(listOf(ctrl("N")), "New chat"),
                Line(listOf(ctrl("Shift", "N")), "New Project", "With Extended mode"),
                Line(listOf(ctrl(",")), "Settings"),
                Line(listOf(ctrl("/"), ctrl("Shift", "?")), "Keyboard shortcuts"),
            ),
        ),
        Group(
            "Layout",
            listOf(
                Line(listOf(ctrl("B")), "Show or hide the sidebar"),
                Line(listOf(ctrl("Shift", "B")), "Show or hide the chat's panel"),
                Line(listOf(listOf("Esc")), "Close", "The palette, a sheet, the viewer, the drawer or the panel; else leaves the text field"),
            ),
        ),
        Group(
            "In a chat",
            listOf(
                Line(listOf(ctrl("R")), "Check for new messages", "Then says \"Up to date\", or how many are new"),
                Line(listOf(ctrl("Shift", "R")), "Reload transcript", "Reads the whole chat again, as the menu's Reload transcript does"),
            ),
        ),
    )
}
