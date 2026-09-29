package com.cursorforandroid.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The window the shell stands in, as the configuration has it: its width, and whether it is wide enough for the rail
 * beside the chat rather than the phone's drawer. A live resize changes the width a dp at a time, a window edge dragged
 * on DeX or a split-screen divider moved; only what depends on the width class reads [wide] in composition, and the
 * panes follow the exact width at layout ([ShellPanes]), so a step recomposes nothing of the shell.
 */
@Stable
internal class ShellWindow {
    /** The configuration's screen width. Not state: what follows it hears it through [follow]. */
    private var width: Dp = Dp.Unspecified

    /** Not a Compact window: the rail stands beside the chat, where a phone has its drawer. */
    var wide: Boolean by mutableStateOf(false)
        private set

    private var onResize: ((Dp) -> Unit)? = null

    fun update(width: Dp, wide: Boolean) {
        this.wide = wide
        if (width == this.width) return
        this.width = width
        onResize?.let { Snapshot.withoutReadObservation { it(width) } }
    }

    /**
     * Hands [onResize] the width now and every width the window changes to from here on, in the composition that sees
     * the change: before any of the shell is composed for it, which it reads in the same pass. Remembered, the
     * returned follower lets go once it is forgotten or its composition abandoned.
     */
    fun follow(onResize: (Dp) -> Unit): RememberObserver {
        this.onResize = onResize
        Snapshot.withoutReadObservation { onResize(width) }
        return Follower(onResize)
    }

    private inner class Follower(private val onResize: (Dp) -> Unit) : RememberObserver {
        override fun onRemembered() = Unit

        override fun onForgotten() {
            if (this@ShellWindow.onResize === onResize) this@ShellWindow.onResize = null
        }

        override fun onAbandoned() = onForgotten()
    }
}

/**
 * Reads the configuration into [window], in a scope of its own so that a change to it recomposes this alone. Composed
 * ahead of the shell, which then sees the new window in the same pass. [wide] decides the layout where a test hands it
 * in; otherwise the window size class is measured from the activity, and without one (a wrapped context) the phone
 * layout stands in rather than the cast bringing the app down.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
internal fun ShellWindowReader(window: ShellWindow, wide: Boolean?) {
    val width = LocalConfiguration.current.screenWidthDp.dp
    val activity = LocalContext.current.findActivity()
    val measuredWide = wide ?: (activity != null && calculateWindowSizeClass(activity).widthSizeClass != WindowWidthSizeClass.Compact)
    window.update(width, measuredWide)
}

/** The hosting activity through any number of wrappers (a themed context, a display context); null outside one. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
