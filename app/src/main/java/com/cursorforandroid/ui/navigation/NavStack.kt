package com.cursorforandroid.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import java.util.UUID

/**
 * The destinations of the app. `route` is the string form the stack is saved as. A Cursor Project has no screen of
 * its own: its row opens its coordinator's chat, and the Project's primaries, context and actions are the Project
 * section of that chat's right-side panel.
 */
sealed interface Screen {
    val route: String

    data object Home : Screen {
        override val route: String get() = "home"
    }

    data object Settings : Screen {
        override val route: String get() = "settings"
    }

    /** The installed version's release notes; pushed over Settings' row or the sidebar's card, never a root. */
    data object WhatsNew : Screen {
        override val route: String get() = "whats-new"
    }

    /** The hardware keyboard's shortcuts; pushed over Settings' row, like [WhatsNew]. */
    data object KeyboardShortcuts : Screen {
        override val route: String get() = "keyboard-shortcuts"
    }

    data class Agent(val id: String) : Screen {
        override val route: String get() = "agent/$id"
    }

    companion object {
        /** The route of the Project view builds before 0.3.11 saved; it restores as the coordinator's chat. */
        private const val LEGACY_PROJECT_PREFIX = "project/"

        fun fromRoute(route: String): Screen? = when {
            route == Home.route -> Home
            route == Settings.route -> Settings
            route == WhatsNew.route -> WhatsNew
            route == KeyboardShortcuts.route -> KeyboardShortcuts
            route.startsWith("agent/") -> route.removePrefix("agent/").takeIf { it.isNotBlank() }?.let(::Agent)
            route.startsWith(LEGACY_PROJECT_PREFIX) -> route.removePrefix(LEGACY_PROJECT_PREFIX).takeIf { it.isNotBlank() }?.let(::Agent)
            else -> null
        }
    }
}

/**
 * One position on the back stack. The [id] is what saved state and view models are keyed by, so two visits to the
 * same screen never share state, and an entry keeps its identity while it animates out after being popped.
 */
@Stable
class NavEntry internal constructor(val id: String, val screen: Screen) {
    override fun equals(other: Any?): Boolean = other is NavEntry && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "NavEntry(${screen.route}, $id)"
}

/**
 * The app's back stack: a snapshot-state list of [NavEntry], always at least one deep, with the New Chat pane at the
 * root. Back follows where the reader came from:
 *
 * - [open] is a destination reached from the one on top — a worker's row, a subagent, the panel's Project section,
 *   a side chat, a link, Settings, What's new. It is pushed, so back returns to the screen it was opened from, with
 *   that screen's scroll position, composer draft and open panel as they were left.
 * - [resetTo] is a destination picked from the app's top level — a sidebar row, the New Chat pane's lists, a
 *   notification, a widget, a launcher shortcut, a link from outside. It sits on the root, so back from it is the New
 *   Chat pane, and from there the system's back leaves the app as it would from any home.
 *
 * Mutations apply immediately; [CursorNavHost] observes the top and animates whatever changed, except a change made
 * [instantly], which it shows in place.
 */
@Stable
class NavStack private constructor(initial: List<NavEntry>) {

    constructor(root: Screen) : this(listOf(NavEntry(newId(), root)))

    private val list = mutableStateListOf<NavEntry>().also { it.addAll(initial) }

    val entries: List<NavEntry> get() = list
    val top: NavEntry get() = list.last()
    val canPop: Boolean get() = list.size > 1

    /** The id of the top [instantly] left, until [CursorNavHost] has put it on screen; not saved. */
    internal var instantTop by mutableStateOf<String?>(null)

    /**
     * Makes [change], and has whatever it leaves on top shown in the frame it lands, with no transition: the keyboard's
     * navigation (a chat switched to, a new chat, Settings), which desktop Cursor answers at once. Back, and every
     * other change, still slides.
     */
    fun <T> instantly(change: () -> T): T {
        val before = top.id
        val result = change()
        if (top.id != before) instantTop = top.id
        return result
    }

    /** The id of the top [gliding] left, until [CursorNavHost] has put it on screen; not saved. */
    internal var glideTop by mutableStateOf<String?>(null)

    /**
     * Makes [change], and has whatever it leaves on top fade in where it stands as the screen it covers fades out,
     * rather than slide in from the side: a chat started from the New Chat composer, whose prompt and composer carry on into the
     * chat (see [com.cursorforandroid.ui.components.SendMotion]) and would be carried off sideways by a slide. Back
     * from it slides as ever.
     */
    fun <T> gliding(change: () -> T): T {
        val before = top.id
        val result = change()
        if (top.id != before) glideTop = top.id
        return result
    }

    /** The entry a back gesture would reveal, or null at the root. */
    val underTop: NavEntry? get() = list.getOrNull(list.size - 2)

    fun contains(entry: NavEntry): Boolean = list.any { it.id == entry.id }

    /**
     * Puts [screen] on top, unless it already is: two entries in a row for one screen would make back look as if it
     * did nothing. Past [MAX_DEPTH] the oldest entry above the root makes room, so a long chain of chats opened one
     * from another cannot hold on to every one of them; the root, and the newest trail back from the top, stay.
     */
    fun push(screen: Screen) {
        if (top.screen == screen) return
        list += NavEntry(newId(), screen)
        while (list.size > MAX_DEPTH) list.removeAt(1)
    }

    /**
     * Opens [screen] from the destination on top, so back returns there: pushed, unless it is already on top (nothing
     * happens), or it is the entry just beneath the top — the chat this one was opened from, reached again through the
     * way back to it (a primary's link to its coordinator), which is going back: that entry is popped to, as it was
     * left, rather than stacked a second time above its own child. Decided from the stack as it is *now*, not from
     * what a caller saw when it was composed — a callback created while a chat was on top may well be invoked after
     * that chat has been popped.
     */
    fun open(screen: Screen) {
        when (screen) {
            top.screen -> Unit
            underTop?.screen -> pop()
            else -> push(screen)
        }
    }

    /** [open] for a chat — a Project's coordinator included. */
    fun openAgent(id: String) = open(Screen.Agent(id))

    fun pop(): Boolean {
        if (!canPop) return false
        list.removeAt(list.lastIndex)
        return true
    }

    /**
     * Top-level navigation: everything above [screen] goes, and [screen] sits on the root (or is the root). An entry
     * already showing it is kept rather than replaced, so tapping the destination you are on is a no-op instead of
     * a rebuild that clears its view model and loses the scroll position, and a chat picked again from the sidebar
     * comes back as it was left.
     */
    fun resetTo(screen: Screen) {
        while (list.size > 1 && list.last().screen != screen) list.removeAt(list.lastIndex)
        if (list.last().screen != screen) list += NavEntry(newId(), screen)
    }

    companion object {
        /**
         * How deep the stack goes, the root included. Every entry below the top keeps its view models and its saved
         * state (a transcript's scroll, each dropdown it opened) for as long as it is on the stack, and all of it is
         * written into the activity's saved state; nine steps back from the New Chat pane is more than anyone
         * retraces, and keeps that bounded.
         */
        const val MAX_DEPTH = 10

        private fun newId(): String = UUID.randomUUID().toString()

        /**
         * Saves the stack as alternating id / route strings so it survives process death. What comes back is held to
         * the same rules as what is built: the New Chat pane at the root, no screen twice in a row, [MAX_DEPTH] deep.
         */
        val Saver: Saver<NavStack, Any> = listSaver<NavStack, String>(
            save = { stack -> stack.list.flatMap { listOf(it.id, it.screen.route) } },
            restore = { flat -> NavStack(restored(flat)) },
        )

        internal fun restored(flat: List<String>): List<NavEntry> {
            val entries = flat.chunked(2).mapNotNull { pair ->
                if (pair.size != 2) return@mapNotNull null
                Screen.fromRoute(pair[1])?.let { NavEntry(pair[0], it) }
            }
            val rooted = if (entries.firstOrNull()?.screen == Screen.Home) entries else listOf(NavEntry(newId(), Screen.Home)) + entries
            val distinct = rooted.filterIndexed { index, entry -> index == 0 || rooted[index - 1].screen != entry.screen }
            return if (distinct.size <= MAX_DEPTH) distinct else listOf(distinct.first()) + distinct.takeLast(MAX_DEPTH - 1)
        }
    }
}
