package com.cursorforandroid.ui.media

import android.view.KeyEvent

/** What a key asks of the open viewer (see [MediaKeys]). */
internal sealed interface MediaKey {
    data object PlayPause : MediaKey

    /** [deltaMs] on in the recording, back when negative. */
    data class Seek(val deltaMs: Long) : MediaKey

    /** The next page ([step] 1) or the previous one (-1). */
    data class Page(val step: Int) : MediaKey

    /** One of [VideoPlayback.KEY_SPEEDS] faster or slower. */
    data class Speed(val faster: Boolean) : MediaKey

    data object Mute : MediaKey
}

/**
 * The open viewer's keys, as the web's players have them: Space or K plays and pauses, J and L go back and on 10 s,
 * the arrows 5 s, Shift+N and Shift+P or Page Down and Page Up turn to the next and the previous page, < and >
 * (Shift+, and Shift+.) step the speed, M mutes. On a picture, which has nothing to seek, the arrows turn the page,
 * the way the pager lays the pages out. Esc is the shell's, which closes the viewer.
 *
 * Plain keys, and Shift, only: a Ctrl, Alt or Meta chord is the app's or the system's. They are read where the
 * viewer holds the focus, so a key typed into a field (the palette's over the viewer) is the field's.
 */
internal object MediaKeys {
    const val SEEK_MS = 10_000L
    const val ARROW_SEEK_MS = 5_000L

    fun action(
        keyCode: Int,
        shift: Boolean,
        ctrl: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
        playable: Boolean,
        rtl: Boolean = false,
        char: Int = 0,
    ): MediaKey? {
        if (ctrl || alt || meta) return null
        when (keyCode) {
            KeyEvent.KEYCODE_PAGE_DOWN -> return MediaKey.Page(1)
            KeyEvent.KEYCODE_PAGE_UP -> return MediaKey.Page(-1)
            KeyEvent.KEYCODE_N -> return if (shift) MediaKey.Page(1) else null
            KeyEvent.KEYCODE_P -> return if (shift) MediaKey.Page(-1) else null
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (shift) return null
                val back = keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                return when {
                    playable -> MediaKey.Seek(if (back) -ARROW_SEEK_MS else ARROW_SEEK_MS)
                    else -> MediaKey.Page(if (back != rtl) -1 else 1)
                }
            }
        }
        if (!playable) return null
        if (char == '<'.code || (shift && keyCode == KeyEvent.KEYCODE_COMMA)) return MediaKey.Speed(faster = false)
        if (char == '>'.code || (shift && keyCode == KeyEvent.KEYCODE_PERIOD)) return MediaKey.Speed(faster = true)
        if (shift) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_K -> MediaKey.PlayPause
            KeyEvent.KEYCODE_J -> MediaKey.Seek(-SEEK_MS)
            KeyEvent.KEYCODE_L -> MediaKey.Seek(SEEK_MS)
            KeyEvent.KEYCODE_M -> MediaKey.Mute
            else -> null
        }
    }

    /** Held down, a seek keeps going as the key repeats; every other key acts once however long it is held. */
    fun repeats(key: MediaKey): Boolean = key is MediaKey.Seek

    /** "+10 s", "−5 s": a seek as the viewer reads it out. */
    fun seekLabel(deltaMs: Long): String = (if (deltaMs < 0) "\u2212" else "+") + "${kotlin.math.abs(deltaMs) / 1_000} s"
}
