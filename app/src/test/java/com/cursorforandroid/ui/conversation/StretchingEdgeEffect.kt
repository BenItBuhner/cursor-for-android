package com.cursorforandroid.ui.conversation

import android.widget.EdgeEffect
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowEdgeEffect
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector

/**
 * Android 12's stretch overscroll, which Robolectric's own shadow turns off — every `EdgeEffect` there behaves as on a
 * phone with animations off, never drawn and taking no drag. With this one it stretches, and takes the drag, as on a
 * phone: the transcript's platform overscroll is Android's own, and [deepest] notes how far any stretch was pulled
 * (a fraction of its container) since the last [reset].
 */
@Implements(EdgeEffect::class, minSdk = 31)
class StretchingEdgeEffect : ShadowEdgeEffect() {
    @RealObject private lateinit var real: EdgeEffect

    override fun getCurrentEdgeEffectBehavior(): Int = TYPE_STRETCH

    @Implementation
    protected fun onPullDistance(deltaDistance: Float, displacement: Float): Float =
        reflector(Real::class.java, real).onPullDistance(deltaDistance, displacement).also { deepest = maxOf(deepest, real.distance) }

    @ForType(EdgeEffect::class)
    private interface Real {
        @Direct
        fun onPullDistance(deltaDistance: Float, displacement: Float): Float
    }

    companion object {
        /** `EdgeEffect.TYPE_STRETCH`, which the compile SDK's stubs leave out. */
        private const val TYPE_STRETCH = 1

        var deepest = 0f
            private set

        fun reset() {
            deepest = 0f
        }
    }
}
