package com.cursorforandroid.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import java.util.UUID

/** The four destinations of the app. `route` is the string form the stack is saved as. */
sealed interface Screen {
    val route: String

    data object Home : Screen {
        override val route: String get() = "home"
    }

    data object Settings : Screen {
        override val route: String get() = "settings"
    }

    data class Agent(val id: String) : Screen {
        override val route: String get() = "agent/$id"
    }

    /** A Cursor Project's view: its coordinator, primaries, side chats and shared context. */
    data class Project(val id: String) : Screen {
        override val route: String get() = "project/$id"
    }

    companion object {
        fun fromRoute(route: String): Screen? = when {
            route == Home.route -> Home
            route == Settings.route -> Settings
            route.startsWith("agent/") -> route.removePrefix("agent/").takeIf { it.isNotBlank() }?.let(::Agent)
            route.startsWith("project/") -> route.removePrefix("project/").takeIf { it.isNotBlank() }?.let(::Project)
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
 * The app's back stack: a snapshot-state list of [NavEntry], always at least one deep. Mutations apply immediately;
 * [CursorNavHost] observes the top and animates whatever changed.
 */
@Stable
class NavStack private constructor(initial: List<NavEntry>) {

    constructor(root: Screen) : this(listOf(NavEntry(newId(), root)))

    private val list = mutableStateListOf<NavEntry>().also { it.addAll(initial) }

    val entries: List<NavEntry> get() = list
    val top: NavEntry get() = list.last()
    val canPop: Boolean get() = list.size > 1

    /** The entry a back gesture would reveal, or null at the root. */
    val underTop: NavEntry? get() = list.getOrNull(list.size - 2)

    fun contains(entry: NavEntry): Boolean = list.any { it.id == entry.id }

    fun push(screen: Screen) {
        list += NavEntry(newId(), screen)
    }

    /**
     * Swaps the top entry for [screen] without growing the stack (opening one chat from another). The root is never
     * swapped out: with nothing above it, [screen] is pushed instead, so there is always a home to go back to.
     */
    fun replaceTop(screen: Screen) {
        if (!canPop) {
            push(screen)
            return
        }
        list[list.lastIndex] = NavEntry(newId(), screen)
    }

    /**
     * Opens a chat: over anything that is not a chat, or in place of the chat on top, so chats never pile up when one
     * is opened from another; nothing happens when it is already on top. Decided from the stack as it is *now*, not
     * from what a caller saw when it was composed — a callback created while a chat was on top may well be invoked
     * after that chat has been popped.
     */
    fun openAgent(id: String) {
        val current = top.screen
        when {
            current is Screen.Agent && current.id == id -> Unit
            current is Screen.Agent -> replaceTop(Screen.Agent(id))
            else -> push(Screen.Agent(id))
        }
    }

    /**
     * Opens a Project's view: in place of a Project view on top (one Project from another), over anything else — a
     * chat included, so a primary opened from the Project goes back to it. Nothing happens when it is already on top.
     */
    fun openProject(id: String) {
        val current = top.screen
        when {
            current is Screen.Project && current.id == id -> Unit
            current is Screen.Project -> replaceTop(Screen.Project(id))
            else -> push(Screen.Project(id))
        }
    }

    fun pop(): Boolean {
        if (!canPop) return false
        list.removeAt(list.lastIndex)
        return true
    }

    /**
     * Top-level navigation: everything above [screen] goes, and [screen] sits on the root (or is the root). An entry
     * already showing it is kept rather than replaced, so tapping the destination you are on is a no-op instead of
     * a rebuild that clears its view model and loses the scroll position.
     */
    fun resetTo(screen: Screen) {
        while (list.size > 1 && list.last().screen != screen) list.removeAt(list.lastIndex)
        if (list.last().screen != screen) list += NavEntry(newId(), screen)
    }

    companion object {
        private fun newId(): String = UUID.randomUUID().toString()

        /** Saves the stack as alternating id / route strings so it survives process death. */
        val Saver: Saver<NavStack, Any> = listSaver<NavStack, String>(
            save = { stack -> stack.list.flatMap { listOf(it.id, it.screen.route) } },
            restore = { flat ->
                val restored = flat.chunked(2).mapNotNull { pair ->
                    if (pair.size != 2) return@mapNotNull null
                    Screen.fromRoute(pair[1])?.let { NavEntry(pair[0], it) }
                }
                NavStack(restored.ifEmpty { listOf(NavEntry(newId(), Screen.Home)) })
            },
        )
    }
}
