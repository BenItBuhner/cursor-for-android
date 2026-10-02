package com.cursorforandroid.ui.shortcuts

import android.view.KeyEvent
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/**
 * A key and the modifiers held with it, as a shortcut is bound: by key code, so it is the same key whatever the
 * layout prints on it. Meta is never part of one ([ShortcutRules.blocked]): Android keeps the Meta chords.
 */
@Immutable
data class KeyChord(val keyCode: Int, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false) {

    /** The key caps it is drawn as, modifiers first: Ctrl, Shift, Alt, then the key. */
    val keys: List<String>
        get() = buildList {
            if (ctrl) add("Ctrl")
            if (shift) add("Shift")
            if (alt) add("Alt")
            add(KeyNames.of(keyCode, shift))
        }

    /** "Ctrl+Shift+B". */
    val label: String get() = keys.joinToString("+")

    /** "ctrl+shift+30": the modifiers, then the key code. */
    fun encode(): String = buildString {
        if (ctrl) append("ctrl+")
        if (shift) append("shift+")
        if (alt) append("alt+")
        append(keyCode)
    }

    companion object {
        fun ctrl(keyCode: Int, shift: Boolean = false) = KeyChord(keyCode, ctrl = true, shift = shift)

        /** What [encode] wrote; null for anything else. */
        fun decode(text: String): KeyChord? {
            val parts = text.trim().split('+')
            val code = parts.last().toIntOrNull()?.takeIf { it in 1..MAX_KEY_CODE } ?: return null
            val modifiers = parts.dropLast(1)
            if (modifiers.any { it !in MODIFIERS } || modifiers.size != modifiers.toSet().size) return null
            return KeyChord(code, ctrl = "ctrl" in modifiers, shift = "shift" in modifiers, alt = "alt" in modifiers)
        }

        private val MODIFIERS = setOf("ctrl", "shift", "alt")
        private const val MAX_KEY_CODE = 999
    }
}

/**
 * The shortcuts Settings › Keyboard shortcuts can move to other keys, each with the keys it is on out of the box. The
 * rest stay where they are: Ctrl+Tab (the switcher lives on the held Ctrl), Ctrl+1 … Ctrl+0 and Esc. [action] is what
 * the shell is asked for; null for the composer's own shortcut, which the focused composer reads itself.
 */
enum class Shortcut(val id: String, val label: String, val detail: String?, val action: ShortcutAction?, val defaults: List<KeyChord>) {
    Search("search", "Search chats and Projects", "Titles, repositories and the transcripts kept on this device", ShortcutAction.Search, listOf(KeyChord.ctrl(KeyEvent.KEYCODE_P), KeyChord.ctrl(KeyEvent.KEYCODE_K))),
    NewChat("new_chat", "New chat", null, ShortcutAction.NewChat, listOf(KeyChord.ctrl(KeyEvent.KEYCODE_N))),
    NewProject("new_project", "New Project", "With Extended mode", ShortcutAction.NewProject, listOf(KeyChord.ctrl(KeyEvent.KEYCODE_N, shift = true))),
    OpenSettings("settings", "Settings", null, ShortcutAction.OpenSettings, listOf(KeyChord.ctrl(KeyEvent.KEYCODE_COMMA))),
    ShowShortcuts("shortcuts", "Keyboard shortcuts", null, ShortcutAction.ShowShortcuts, listOf(KeyChord.ctrl(KeyEvent.KEYCODE_SLASH), KeyChord.ctrl(KeyEvent.KEYCODE_SLASH, shift = true))),
    ToggleSidebar("sidebar", "Show or hide the sidebar", null, ShortcutAction.ToggleSidebar, listOf(KeyChord.ctrl(KeyEvent.KEYCODE_B))),
    TogglePanel("panel", "Show or hide the chat's panel", null, ShortcutAction.TogglePanel, listOf(KeyChord.ctrl(KeyEvent.KEYCODE_B, shift = true))),
    ExpandComposer("composer", "Full-screen composer", "Grows the focused composer over the window, or back", null, listOf(KeyChord.ctrl(KeyEvent.KEYCODE_F), KeyChord.ctrl(KeyEvent.KEYCODE_E, shift = true))),
    CatchUp("catch_up", "Check for new messages", "Then says \"Up to date\", or how many are new", ShortcutAction.CatchUp, listOf(KeyChord.ctrl(KeyEvent.KEYCODE_R))),
    ReloadTranscript("reload", "Reload transcript", "Reads the whole chat again, as the menu's Reload transcript does", ShortcutAction.ReloadTranscript, listOf(KeyChord.ctrl(KeyEvent.KEYCODE_R, shift = true))),
    ;

    companion object {
        fun byId(id: String): Shortcut? = entries.firstOrNull { it.id == id }
    }
}

/** What becomes of the other shortcut when a chord it is on is given to another. */
enum class ConflictResolution {
    /** It takes the keys the one given the chord had. */
    Swap,

    /** It keeps whatever else it is on, or is left on none. */
    Replace,
}

/** Whether a chord can go on a shortcut (see [ShortcutBindings.check]). */
sealed interface ChordCheck {
    data object Free : ChordCheck

    /** Already the shortcut's own. */
    data object Unchanged : ChordCheck

    /** Another shortcut is on it: the user picks a [ConflictResolution], or another chord. */
    data class Taken(val owner: Shortcut) : ChordCheck

    /** Android's, a text field's, or one of the app's fixed chords; [reason] says which. */
    data class Blocked(val reason: String) : ChordCheck
}

/**
 * The keys every [Shortcut] is on. No chord is ever on two shortcuts: [assign] and [reset] take it off the other one
 * as the [ConflictResolution] says, and [parse] drops the second claim to one. A shortcut can be on no keys at all,
 * after its chord was given to another with [ConflictResolution.Replace].
 */
@Immutable
class ShortcutBindings private constructor(private val chords: Map<Shortcut, List<KeyChord>>) {

    fun chords(shortcut: Shortcut): List<KeyChord> = chords[shortcut] ?: shortcut.defaults

    fun isDefault(shortcut: Shortcut): Boolean = chords(shortcut) == shortcut.defaults

    val isAllDefault: Boolean get() = Shortcut.entries.all(::isDefault)

    /** The shortcut on [chord], if any. */
    fun owner(chord: KeyChord): Shortcut? = Shortcut.entries.firstOrNull { chord in chords(it) }

    /** Whether the key with these modifiers is [shortcut]'s. */
    fun matches(shortcut: Shortcut, keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean): Boolean =
        !meta && KeyChord(keyCode, ctrl, shift, alt) in chords(shortcut)

    fun check(target: Shortcut, chord: KeyChord): ChordCheck {
        ShortcutRules.blocked(chord.keyCode, chord.ctrl, chord.shift, chord.alt, meta = false)?.let { return ChordCheck.Blocked(it) }
        return when (val owner = owner(chord)) {
            null -> ChordCheck.Free
            target -> if (chords(target) == listOf(chord)) ChordCheck.Unchanged else ChordCheck.Free
            else -> ChordCheck.Taken(owner)
        }
    }

    /** [target] on [chord] alone; a shortcut already on it is dealt with as [resolution] says. A blocked chord changes nothing. */
    fun assign(target: Shortcut, chord: KeyChord, resolution: ConflictResolution = ConflictResolution.Replace): ShortcutBindings =
        if (check(target, chord) is ChordCheck.Blocked) this else put(target, listOf(chord), resolution)

    /** The shortcuts on any of [target]'s default chords but [target] itself, each with the chord it holds. */
    fun resetConflicts(target: Shortcut): List<Pair<KeyChord, Shortcut>> =
        target.defaults.mapNotNull { chord -> owner(chord)?.takeIf { it != target }?.let { chord to it } }

    /** [target] back on its defaults, taking them back from whichever shortcut holds one as [resolution] says. */
    fun reset(target: Shortcut, resolution: ConflictResolution = ConflictResolution.Replace): ShortcutBindings =
        put(target, target.defaults, resolution)

    private fun put(target: Shortcut, wanted: List<KeyChord>, resolution: ConflictResolution): ShortcutBindings {
        val next = Shortcut.entries.associateWith(::chords).toMutableMap()
        // What the target lets go of: a swap hands it to the first shortcut the new chords are taken from.
        var given = chords(target).filter { it !in wanted }
        next[target] = wanted
        for (other in Shortcut.entries) {
            if (other == target) continue
            val held = next.getValue(other)
            if (held.none { it in wanted }) continue
            val kept = held.filter { it !in wanted }
            next[other] = if (resolution == ConflictResolution.Swap) (kept + given).distinct() else kept
            if (resolution == ConflictResolution.Swap) given = emptyList()
        }
        return ShortcutBindings(next)
    }

    /**
     * The shortcuts moved off their defaults, as `id=chord,chord` joined by `;` (an empty list for a shortcut on no
     * keys); defaults are not written, so a default that changes in a later release reaches whoever never moved it.
     */
    fun encode(): String = Shortcut.entries.filterNot(::isDefault).joinToString(";") { s ->
        "${s.id}=" + chords(s).joinToString(",") { it.encode() }
    }

    override fun equals(other: Any?): Boolean = other is ShortcutBindings && Shortcut.entries.all { chords(it) == other.chords(it) }

    override fun hashCode(): Int = Shortcut.entries.map(::chords).hashCode()

    override fun toString(): String = "ShortcutBindings(${encode()})"

    companion object {
        val Defaults = ShortcutBindings(Shortcut.entries.associateWith { it.defaults })

        /**
         * What [encode] wrote. Unknown shortcuts, unreadable and blocked chords are dropped; a chord two shortcuts claim
         * (a moved shortcut, and a default that came to be on the same keys since) stays with the one moved to it.
         */
        fun parse(text: String?): ShortcutBindings {
            if (text.isNullOrBlank()) return Defaults
            val moved = LinkedHashMap<Shortcut, List<KeyChord>>()
            for (entry in text.split(';')) {
                val id = entry.substringBefore('=', missingDelimiterValue = "")
                val shortcut = Shortcut.byId(id.trim()) ?: continue
                moved[shortcut] = entry.substringAfter('=').split(',').filter { it.isNotBlank() }.mapNotNull(KeyChord::decode)
                    .filter { ShortcutRules.blocked(it.keyCode, it.ctrl, it.shift, it.alt, meta = false) == null }
                    .distinct()
            }
            val claimed = HashSet<KeyChord>()
            val result = LinkedHashMap<Shortcut, List<KeyChord>>()
            (moved.keys + Shortcut.entries).distinct().forEach { s ->
                result[s] = (moved[s] ?: s.defaults).filter { claimed.add(it) }
            }
            return ShortcutBindings(result)
        }
    }
}

/**
 * The chords no shortcut can be moved to, each with the sentence the capture shows. A shortcut needs Ctrl or Alt, so
 * typing is never taken; Meta chords, Alt+Tab and the like, and Android's own keys are the system's; Ctrl+A, C, V, X,
 * Z and Y and the caret keys are the text field's; Ctrl+Tab and Ctrl+1 … Ctrl+0 are the app's fixed ones.
 */
object ShortcutRules {
    fun blocked(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean): String? {
        val label = KeyChord(keyCode, ctrl, shift, alt).label
        return when {
            keyCode in SYSTEM_KEYS -> "Android keeps ${KeyNames.of(keyCode, shift = false)} for itself."
            meta -> "Android keeps shortcuts with the Meta key."
            !ctrl && !alt -> "Hold Ctrl or Alt with the key, so typing is left alone."
            isSystemChord(keyCode, ctrl, alt) -> "Android keeps $label for itself."
            keyCode in CARET_KEYS -> "$label is kept for editing text."
            ctrl && !alt && (keyCode in TEXT_KEYS || (shift && keyCode in TEXT_SHIFT_KEYS)) -> "$label is kept for editing text."
            ctrl && !alt && keyCode == KeyEvent.KEYCODE_TAB -> "Ctrl+Tab switches between recent chats, and stays on it."
            ctrl && !alt && !shift && isDigit(keyCode) -> "Ctrl+1 … Ctrl+0 open the sidebar's rows, and stay on them."
            else -> null
        }
    }

    /** Alt+Tab, Alt+F4, Ctrl or Alt with Esc, the IME's Ctrl+Space, Ctrl+Alt+Delete. */
    private fun isSystemChord(keyCode: Int, ctrl: Boolean, alt: Boolean): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_F4 -> alt && !ctrl
        KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_SPACE -> true
        KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> ctrl && alt
        else -> false
    }

    private fun isDigit(keyCode: Int): Boolean =
        keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 || keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9

    private val TEXT_KEYS = setOf(
        KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Y,
    )

    /** With Ctrl+Shift: redo, and paste as plain text. */
    private val TEXT_SHIFT_KEYS = setOf(KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_V)

    private val CARET_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL,
    )

    private val SYSTEM_KEYS = setOf(
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL, KeyEvent.KEYCODE_CAMERA,
        KeyEvent.KEYCODE_BRIGHTNESS_UP, KeyEvent.KEYCODE_BRIGHTNESS_DOWN, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_SYSRQ, KeyEvent.KEYCODE_NOTIFICATION, KeyEvent.KEYCODE_ALL_APPS, KeyEvent.KEYCODE_ASSIST,
        KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MUTE, KeyEvent.KEYCODE_LANGUAGE_SWITCH,
    )
}

/** The name a key is printed with on a US keyboard: "B", ",", "Tab", "F5". */
object KeyNames {
    fun of(keyCode: Int, shift: Boolean): String = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (keyCode - KeyEvent.KEYCODE_0).toString()
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> "Num ${keyCode - KeyEvent.KEYCODE_NUMPAD_0}"
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${keyCode - KeyEvent.KEYCODE_F1 + 1}"
        // Ctrl+Shift+? is Ctrl+Shift+/ on the layouts that put ? over /.
        KeyEvent.KEYCODE_SLASH -> if (shift) "?" else "/"
        KeyEvent.KEYCODE_COMMA -> ","
        KeyEvent.KEYCODE_PERIOD -> "."
        KeyEvent.KEYCODE_SEMICOLON -> ";"
        KeyEvent.KEYCODE_APOSTROPHE -> "'"
        KeyEvent.KEYCODE_LEFT_BRACKET -> "["
        KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
        KeyEvent.KEYCODE_BACKSLASH -> "\\"
        KeyEvent.KEYCODE_MINUS -> "-"
        KeyEvent.KEYCODE_EQUALS -> "="
        KeyEvent.KEYCODE_GRAVE -> "`"
        KeyEvent.KEYCODE_SPACE -> "Space"
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_ENTER -> "Enter"
        KeyEvent.KEYCODE_ESCAPE -> "Esc"
        KeyEvent.KEYCODE_DEL -> "Backspace"
        KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
        KeyEvent.KEYCODE_INSERT -> "Insert"
        KeyEvent.KEYCODE_MOVE_HOME -> "Home"
        KeyEvent.KEYCODE_MOVE_END -> "End"
        KeyEvent.KEYCODE_PAGE_UP -> "Page Up"
        KeyEvent.KEYCODE_PAGE_DOWN -> "Page Down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
        KeyEvent.KEYCODE_DPAD_UP -> "Up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
        else -> KeyEvent.keyCodeToString(keyCode)?.removePrefix("KEYCODE_")?.takeUnless { it.isEmpty() || it.all(Char::isDigit) }
            ?.split('_')?.joinToString(" ") { part -> part.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) } }
            ?: "Key $keyCode"
    }
}

/** The keys the shortcuts are on, as saved in Settings; the defaults where nothing provides them (previews, most tests). */
val LocalShortcutBindings = staticCompositionLocalOf { ShortcutBindings.Defaults }
