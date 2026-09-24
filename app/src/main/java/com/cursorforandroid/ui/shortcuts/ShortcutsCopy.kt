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
                Line(listOf(ctrl("Tab")), "Switch between recent chats", "Hold Ctrl and press Tab to step back; Shift+Tab steps forward; let go of Ctrl to open"),
                Line(listOf(ctrl("1"), ctrl("0")), "Open a sidebar row", "Ctrl+1 to Ctrl+9, and Ctrl+0 for the tenth; hold Ctrl to see the numbers"),
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
                Line(listOf(ctrl("R")), "Check for new messages", "Fetches only what is new; says \"Up to date\" or how many are new"),
                Line(listOf(ctrl("Shift", "R")), "Reload transcript", "Reads the whole chat again, as the menu's Reload transcript does"),
            ),
        ),
    )
}
