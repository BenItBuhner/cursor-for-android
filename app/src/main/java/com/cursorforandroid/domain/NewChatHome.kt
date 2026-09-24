package com.cursorforandroid.domain

/**
 * What the New Chat pane lists under its composer — Settings › New chat page, a device preference kept across
 * sign-outs.
 *
 *  - [RECENT], the default: the most recently updated chats, whatever state they are in, as the pane always has.
 *  - [PROJECTS]: the account's Projects as shortcuts — each in its own icon and colour, saying when it is working —
 *    that open the Project. Projects are Extended mode's (the demo has one of its own); without them the pane says so
 *    and lists the recent chats under the note, so it is never left empty.
 */
enum class NewChatHome {
    RECENT,
    PROJECTS,
    ;

    /** How the preference spells it. */
    val key: String get() = name.lowercase()

    companion object {
        val DEFAULT = RECENT

        fun parse(raw: String?): NewChatHome = entries.firstOrNull { it.key == raw } ?: DEFAULT
    }
}
