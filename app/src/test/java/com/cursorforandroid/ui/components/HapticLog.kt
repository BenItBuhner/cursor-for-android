package com.cursorforandroid.ui.components

import android.view.ViewGroup
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowViewGroup

/**
 * Every haptic a view group plays, where Robolectric's own shadow remembers only the last: two played in one frame, a
 * double-fire, are two here. Compose's host view is a view group, so in a shell this hears all that [Haptics] plays.
 * A test opts in with `@Config(shadows = [ShadowHapticLog::class])` and clears [HapticLog] where its count begins.
 */
@Implements(ViewGroup::class)
class ShadowHapticLog : ShadowViewGroup() {
    @Implementation
    override fun performHapticFeedback(hapticFeedbackType: Int): Boolean {
        HapticLog.played += hapticFeedbackType
        return super.performHapticFeedback(hapticFeedbackType)
    }
}

/** The [Haptic.constant]s [ShadowHapticLog] has heard played since the last [clear], in order. */
internal object HapticLog {
    val played = mutableListOf<Int>()

    fun clear() = played.clear()
}
