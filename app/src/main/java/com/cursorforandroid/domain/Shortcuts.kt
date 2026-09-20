package com.cursorforandroid.domain

/**
 * How the shortcut widget's button is drawn on the home screen. The widget sits on the wallpaper, not on one of
 * the app's surfaces, so each look says what it paints under the glyph rather than borrowing a theme token whole.
 */
enum class ShortcutStyle(
    /** Picker row in the configuration screen. */
    val label: String,
    /** Detail line under the picker row. */
    val detail: String,
) {
    /** A white disc with a dark glyph: the same in every theme and on every wallpaper. */
    White("White", "A white disc, the same on every wallpaper"),
    /** The app's own surface — Cursor Dark's editor grey or Cursor Light's — with its hairline stroke, following the app's theme. */
    Tinted("App tint", "Cursor's surface colour, following the app's theme"),
    /** A translucent disc that lets the wallpaper through: dark glass by day, white glass at night. */
    Glass("Glass", "Translucent, the wallpaper showing through"),
    /** The glyph alone, with nothing painted behind it. */
    IconOnly("Icon only", "Just the glyph, nothing behind it");

    companion object {
        val Default = White

        /** The stored name, tolerating anything a previous build may have written. */
        fun parse(raw: String?): ShortcutStyle = entries.firstOrNull { it.name == raw } ?: Default
    }
}

/**
 * What a shortcut — the widget's button, the compose bar, a launcher shortcut — opens. The two that name a chat
 * carry its title so the widget can say where it leads without the list in memory; the id is what it opens.
 */
sealed interface ShortcutTarget {
    /** The group label the configuration screen lists it under. */
    val label: String

    /** The quick composer over the launcher: a new chat, sent from the home screen. */
    data object NewChat : ShortcutTarget {
        override val label: String get() = "New chat"
    }

    /** The app with the sidebar's search field open. */
    data object Search : ShortcutTarget {
        override val label: String get() = "Search chats"
    }

    /** One chat, by the deep link the app opens it under. */
    data class Chat(val id: String, val name: String) : ShortcutTarget {
        override val label: String get() = name
    }

    /** A Cursor Project — its coordinator's chat, as the sidebar's row opens it. */
    data class Project(val id: String, val name: String) : ShortcutTarget {
        override val label: String get() = name
    }

    /** The id of the chat or Project this leads to; null for the two that open no particular chat. */
    val agentId: String?
        get() = when (this) {
            is Chat -> id
            is Project -> id
            NewChat, Search -> null
        }

    companion object {
        val Default: ShortcutTarget = NewChat

        private const val KIND_NEW_CHAT = "new-chat"
        private const val KIND_SEARCH = "search"
        private const val KIND_CHAT = "chat"
        private const val KIND_PROJECT = "project"

        /** The stored shape: a kind, and for a chat or Project its id and name. */
        fun kindOf(target: ShortcutTarget): String = when (target) {
            NewChat -> KIND_NEW_CHAT
            Search -> KIND_SEARCH
            is Chat -> KIND_CHAT
            is Project -> KIND_PROJECT
        }

        /** [kindOf] read back; anything unknown — or a chat kind without an id — is the default. */
        fun parse(kind: String?, id: String?, name: String?): ShortcutTarget = when (kind) {
            KIND_SEARCH -> Search
            KIND_CHAT -> id?.takeIf { it.isNotBlank() }?.let { Chat(it, name.orEmpty().ifBlank { "Chat" }) } ?: Default
            KIND_PROJECT -> id?.takeIf { it.isNotBlank() }?.let { Project(it, name.orEmpty().ifBlank { "Project" }) } ?: Default
            else -> Default
        }
    }
}

/**
 * Which chats the launcher's long-press menu offers, beside the two fixed entries (New chat, Search): the sidebar's
 * Pinned and Projects groups — the chats the user has said matter — newest activity first, as many as the launcher
 * has room for. Pure, so it is the same decision on every launcher and in the tests.
 */
object LauncherShortcutPicks {

    /** One entry of the menu: what it opens, and the row it was picked from, for its icon and colour. */
    data class Pick(val target: ShortcutTarget, val row: AgentRow)

    fun pick(sections: List<AgentSection>, max: Int): List<Pick> {
        if (max <= 0) return emptyList()
        val projects = sections.firstOrNull { it.key == AgentListOrganizer.PROJECTS_KEY }?.rows.orEmpty()
            .filterNot { it.isPlaceholder || it.agent.isArchived }
            .map { Pick(ShortcutTarget.Project(it.agent.id, it.agent.name), it) }
        val pinned = sections.firstOrNull { it.key == AgentListOrganizer.PINNED_KEY }?.rows.orEmpty()
            .filterNot { it.isPlaceholder || it.agent.isArchived }
            .map { Pick(ShortcutTarget.Chat(it.agent.id, it.agent.name), it) }
        return (projects + pinned)
            .distinctBy { it.row.agent.id }
            .sortedByDescending { it.row.agent.listedAtMillis }
            .take(max)
    }
}
