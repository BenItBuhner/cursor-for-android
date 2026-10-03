package com.cursorforandroid.promo

import android.content.Context
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RecordingCanvas
import android.graphics.RenderNode
import android.media.ImageReader
import android.view.View
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Renders a scene of the app's windows as the device's RenderThread would, at the video's size rather than the
 * screen's: display lists (Compose's graphics layers included, which a software canvas cannot draw) recorded into one
 * root node, lit for elevation shadows as the theme lights them, and read back as packed rows. Robolectric's
 * PixelCopy renders a single window this way, at full size (`HardwareRenderingScreenshot`).
 *
 * The host renderer draws in Skia's native order whatever format the reader asks for: the rows are BGRA, as an
 * `ARGB_8888` bitmap's memory is here.
 */
class Compositor(val width: Int, val height: Int) : AutoCloseable {

    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
    private val renderer = HardwareRenderer().also { it.setSurface(reader.surface) }

    // Not `apply`: inside it `width` and `height` would be the new node's own, zero, and the scene would clip to nothing.
    private val scene = RenderNode("promo-scene").also { it.setPosition(0, 0, width, height) }
    private val root = rootNode.get(renderer) as RenderNode
    private var litFor: Pair<Int, Float>? = null
    private var renders = 0

    /** Lights the scene for [context]'s display drawn at [scale], as a window's renderer is lit. */
    fun light(context: Context, scale: Float) {
        val displayWidth = context.resources.displayMetrics.widthPixels
        if (litFor == displayWidth to scale) return
        litFor = displayWidth to scale
        val a = context.obtainStyledAttributes(null, lighting, 0, 0)
        try {
            renderer.setLightSourceGeometry(
                displayWidth / 2f * scale,
                a.getDimension(lightingIndex("lightY"), 0f) * scale,
                a.getDimension(lightingIndex("lightZ"), 600f) * scale,
                a.getDimension(lightingIndex("lightRadius"), 800f) * scale,
            )
            renderer.setLightSourceAlpha(a.getFloat(lightingIndex("ambientShadowAlpha"), 0.039f), a.getFloat(lightingIndex("spotShadowAlpha"), 0.19f))
        } finally {
            a.recycle()
        }
    }

    /**
     * Runs the render-node animators [view]'s window starts (a pressed ripple's) in this renderer's frames from now on.
     * The window's own renderer never draws here: the animators it is handed wait at its root for a frame that never
     * comes, so its root becomes this renderer's. A window with no renderer yet keeps them for its first frame, and
     * this is that frame. Their clock is the frame's, Robolectric's uptime, which the director moves a frame at a time.
     */
    fun adoptAnimators(view: View) {
        val info = attachInfo.get(view) ?: return
        val windowRenderer = threadedRenderer.get(info) as HardwareRenderer?
        if (windowRenderer != null) {
            if (rootNode.get(windowRenderer) !== root) rootNode.set(windowRenderer, root)
            return
        }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingAnimators.get(info) as MutableList<RenderNode>? ?: return
        pending.forEach { registerAnimating.invoke(renderer, it) }
        pending.clear()
    }

    /** Records the scene with [record] and renders it into [into], [width] x [height] BGRA with no row padding. */
    fun render(into: ByteArray, record: (RecordingCanvas) -> Unit) {
        require(into.size == width * height * 4) { "A ${width}x$height frame needs ${width * height * 4} bytes, not ${into.size}" }
        val canvas = scene.beginRecording(width, height)
        try {
            record(canvas)
        } finally {
            scene.endRecording()
        }
        renderer.setContentRoot(scene)
        val sync = renderer.createRenderRequest().syncAndDraw()
        if (renders++ < 3) println("promo: compositor ${width}x$height sync=$sync")
        var image = reader.acquireNextImage()
        var waited = 0
        while (image == null) {
            check(waited++ < IMAGE_TIMEOUT_MS) { "The renderer produced no image in ${IMAGE_TIMEOUT_MS}ms" }
            Thread.sleep(1)
            image = reader.acquireNextImage()
        }
        image.use {
            val plane = it.planes[0]
            val buffer = plane.buffer
            val row = width * 4
            if (plane.rowStride == row) {
                buffer.get(into, 0, row * height)
            } else {
                for (y in 0 until height) {
                    buffer.position(y * plane.rowStride)
                    buffer.get(into, y * row, row)
                }
            }
        }
    }

    override fun close() {
        renderer.destroy()
        scene.discardDisplayList()
        reader.close()
    }

    companion object {
        private const val IMAGE_TIMEOUT_MS = 5_000

        private val styleable: Class<*> by lazy { Class.forName("com.android.internal.R\$styleable") }
        private val lighting: IntArray by lazy { styleable.getField("Lighting").get(null) as IntArray }
        private fun lightingIndex(attr: String): Int = styleable.getField("Lighting_$attr").getInt(null)

        private val attachInfo: Field by lazy { View::class.java.getDeclaredField("mAttachInfo").apply { isAccessible = true } }
        private val attachInfoClass: Class<*> by lazy { Class.forName("android.view.View\$AttachInfo") }
        private val threadedRenderer: Field by lazy { attachInfoClass.getDeclaredField("mThreadedRenderer").apply { isAccessible = true } }
        private val pendingAnimators: Field by lazy { attachInfoClass.getDeclaredField("mPendingAnimatingRenderNodes").apply { isAccessible = true } }
        private val rootNode: Field by lazy { HardwareRenderer::class.java.getDeclaredField("mRootNode").apply { isAccessible = true } }
        private val registerAnimating: Method by lazy { HardwareRenderer::class.java.getMethod("registerAnimatingRenderNode", RenderNode::class.java) }

        private val updateDisplayList: Method by lazy { View::class.java.getMethod("updateDisplayListIfDirty") }
        private val canHaveDisplayList: Method by lazy { View::class.java.getMethod("canHaveDisplayList") }

        /** [view]'s display list brought up to date, as its window's next frame would draw it. */
        fun displayList(view: View): RenderNode {
            check(canHaveDisplayList.invoke(view) as Boolean) { "${view.javaClass.simpleName} is not hardware accelerated; the capture needs NATIVE graphics" }
            return updateDisplayList.invoke(view) as RenderNode
        }
    }
}
