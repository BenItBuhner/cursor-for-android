package com.cursorforandroid.domain

/**
 * What the New Chat pane lists under its composer — Settings › New chat page, a device preference kept across
 * sign-outs. Until one is chosen there the pane picks for itself ([automatic]): Projects for an account that has any,
 * the recent chats for one that has none.
 *
 *  - [RECENT], the default without Projects: the most recently updated chats, whatever state they are in.
 *  - [PROJECTS]: the account's Projects as shortcuts — each in its own icon and colour, saying when it is working —
 *    that open the Project. Projects are Extended mode's (the demo has one of its own); without them the pane says so
 *    and lists the recent chats under the note, so it is never left empty.
 *  - [PROJECTS_RECENT]: both, one under the other — the Project shortcuts as [PROJECTS] lays them out, then the recent
 *    chats as [RECENT] does — on the one page, which scrolls as one. Without Projects it is what [PROJECTS] is.
 *  - [COMPOSER]: nothing; the composer alone, in the middle of the pane. The chats are the sidebar's.
 */
enum class NewChatHome {
    RECENT,
    PROJECTS,
    PROJECTS_RECENT,
    COMPOSER,
    ;

    /** How the preference spells it (`recent`, `projects`, `projects_recent`, `composer`). */
    val key: String get() = name.lowercase()

    companion object {
        val DEFAULT = RECENT

        fun parse(raw: String?): NewChatHome = entries.firstOrNull { it.key == raw } ?: DEFAULT

        /** The layout the reader chose in Settings, or null when [raw] names none (never chosen, or no longer offered). */
        fun chosen(raw: String?): NewChatHome? = entries.firstOrNull { it.key == raw }

        /**
         * The layout the pane opens on while none is chosen: Projects when the account has any, else [DEFAULT]. Null
         * while that cannot be told without being taken back — Extended mode's switch not read yet
         * ([projectsAvailable] null), or no Projects known and the list not [settled] (the disk's copy of an account
         * may lack a Project made elsewhere since). Without Projects to have at all the answer is [DEFAULT] at once.
         */
        fun automatic(projectsAvailable: Boolean?, hasProjects: Boolean, settled: Boolean): NewChatHome? = when {
            projectsAvailable == null -> null
            !projectsAvailable -> DEFAULT
            hasProjects -> PROJECTS
            settled -> DEFAULT
            else -> null
        }
    }
}

/** Settings › New chat page as read: the layout the reader chose there, or null while nothing has been chosen. */
data class NewChatHomeChoice(val chosen: NewChatHome?)
