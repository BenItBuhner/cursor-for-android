package com.cursorforandroid.promo

import android.view.Choreographer
import java.lang.reflect.Proxy

/**
 * The framework's animators (a ripple's pressed layer, say) given their frames by the capture: one each video frame, at
 * the capture's time, as a display would. Robolectric hands over a frame the moment one is asked for, a millisecond of
 * the clock later, so any idle of the main looper ran such an animator to its end and took the app's clock with it: a
 * press put that clock 80 ms ahead of the capture's, and the ripple under the finger stood still while it caught up.
 *
 * The animators' `AnimationHandler` takes its frames from a provider the platform keeps for tests. Both are hidden from
 * the SDK the capture compiles against, so they are reached by name.
 */
class AnimatorPulse(private val frameTimeMs: () -> Long) {
    private val frames = ArrayList<Choreographer.FrameCallback>()
    private val commits = ArrayList<Runnable>()

    /** Makes this the main thread's source of animation frames. */
    fun install() {
        val handler = Class.forName("android.animation.AnimationHandler")
        val provider = Class.forName("android.animation.AnimationHandler\$AnimationFrameCallbackProvider")
        val pulse = Proxy.newProxyInstance(provider.classLoader, arrayOf(provider)) { self, method, args ->
            when (method.name) {
                "postFrameCallback" -> frames.add(args[0] as Choreographer.FrameCallback).let { null }
                "postCommitCallback" -> commits.add(args[0] as Runnable).let { null }
                "getFrameTime" -> frameTimeMs()
                "getFrameDelay" -> VirtualTime.FRAME_MS
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args[0]
                "toString" -> "AnimatorPulse"
                // setFrameDelay: a frame is the capture's.
                else -> null
            }
        }
        handler.getMethod("setProvider", provider).invoke(handler.getMethod("getInstance").invoke(null), pulse)
    }

    /** A frame for every animator waiting on one, then the commit each asked for after it. */
    fun pulse() {
        if (frames.isEmpty() && commits.isEmpty()) return
        val nanos = frameTimeMs() * 1_000_000
        val due = frames.toList().also { frames.clear() }
        due.forEach { it.doFrame(nanos) }
        val committing = commits.toList().also { commits.clear() }
        committing.forEach { it.run() }
    }
}
