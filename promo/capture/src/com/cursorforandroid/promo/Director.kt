package com.cursorforandroid.promo

import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.cursorforandroid.MainActivity
import com.github.takahirom.roborazzi.fetchRobolectricWindowRoots
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowChoreographer
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToInt

/**
 * A screen the capture poses the app on: its window, and the system bars a device would report around it. The
 * capture has no system UI of its own, so the app is told those bars' insets, lays itself out around them as on the
 * device, and the video draws the bars in the room it left. The app follows the system's dark theme, [night].
 */
data class Screen(
    val name: String,
    val widthDp: Int,
    val heightDp: Int,
    val dpi: Int,
    val statusBarDp: Int,
    val navBarDp: Int,
    val keyboard: Boolean = false,
    val night: Boolean = true,
) {
    val density: Float get() = dpi / 160f
    val widthPx: Int get() = (widthDp * density).roundToInt()
    val heightPx: Int get() = (heightDp * density).roundToInt()

    /** The same screen with the system in its light theme. */
    val light: Screen get() = copy(night = false)

    val qualifiers: String
        get() = buildString {
            append("w${widthDp}dp-h${heightDp}dp-")
            append(if (widthDp > heightDp) "land" else "port")
            append(if (night) "-night-" else "-notnight-")
            append(if (dpi == 160) "mdpi" else "${dpi}dpi")
            if (keyboard) append("-keysexposed-qwerty")
        }

    companion object {
        /** Pixel 9: the 1080x2424 screen. */
        val Phone = Screen("phone", 411, 923, 420, statusBarDp = 40, navBarDp = 24)

        /** Pixel 9 Pro Fold, open: the 2076x2152 inner screen, held upright. */
        val Foldable = Screen("foldable", 791, 820, 420, statusBarDp = 32, navBarDp = 20)

        /** Pixel Tablet: the 2560x1600 screen on its side. */
        val Tablet = Screen("tablet", 1280, 800, 320, statusBarDp = 28, navBarDp = 20)
    }
}

/** Ends a capture early, at [Promo.frameLimit], as a quick look at the start of a scene. */
class EnoughFrames : RuntimeException("promo.frames reached")

/**
 * Drives the running app one video frame at a time and films it. A frame moves the capture's clock ([VirtualTime], the
 * scripted backend's) and Compose's (recomposition, animations, `LaunchedEffect` delays) on by 16 ms, brings the main
 * looper's (the view system, `Dispatchers.Main`, a ripple, the recording's timer) up to the capture's, and gives the
 * framework's animators their frame ([AnimatorPulse]). The looper's clock can only run ahead between frames, by the
 * millisecond Robolectric takes for each frame the view system asks for, and each frame lets the capture's catch up.
 * Between them it waits for what the app does on its own threads with the wall clock (the repository publishes a
 * stream's burst up to 80 ms after it lands), so each frame shows what a device would show that instant. Then every
 * window the app has open is drawn, in order, where the window manager has it: the activity, a sheet's dialog, a
 * menu's popup.
 */
class Director(
    private val compose: ComposeTestRule,
    private val controller: ActivityController<MainActivity>,
    private val sink: FrameSink,
    screen: Screen,
) {
    val activity: MainActivity get() = controller.get()

    var screen: Screen = screen
        private set

    private val looper = shadowOf(Looper.getMainLooper())

    init {
        // Robolectric moves the clock by this much each time the view system asks for a frame, before it gives it one:
        // at its default of 15 ms, the frames Compose and the test rule's idling ask for ran the app's clock seconds
        // ahead of the capture's, and a ripple or the recording's timer with it. At a millisecond, the next frame
        // makes up what they add.
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))
    }

    /** The main looper's clock when the capture's read zero: each frame brings it up to the capture's. */
    private val uptimeBase = SystemClock.uptimeMillis() - VirtualTime.nowMs

    private val animators = AnimatorPulse { uptimeBase + VirtualTime.nowMs }.also { it.install() }

    private var scale = 1f
    private var compositor: Compositor? = null
    private val marks = ArrayList<String>()
    private var finger: Finger? = null
    private var releaseFrames = 0
    private var emitsSeen = VirtualTime.emits
    private var framesThisRun = 0
    private var pressed: Pressed? = null

    /** What else each frame's metadata carries about the device around the app, as the take reads it. */
    var extra: (JsonObjectBuilder.() -> Unit)? = null

    private class Finger(val x: Float, val y: Float, val down: Boolean)

    /** A finger held down by [press]: the window it went down in, and where that window's top left is on the screen. */
    private class Pressed(val root: SemanticsNodeInteraction, val origin: Offset)

    private val timings = LinkedHashMap<String, Long>()

    private inline fun <T> timed(phase: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            timings[phase] = (timings[phase] ?: 0L) + (System.nanoTime() - start)
        }
    }

    /** Starts a clip whose canvas holds [largest] at [scale]; the app draws at its top left, however large it is now. */
    fun segment(name: String, scale: Float, largest: Screen = screen) {
        this.scale = scale
        val width = even(largest.widthPx * scale)
        val height = even(largest.heightPx * scale)
        sink.open(name, width, height)
        compositor?.close()
        compositor = Compositor(width, height)
    }

    fun endSegment() {
        sink.close()
        compositor?.close()
        compositor = null
    }

    fun mark(name: String) {
        marks += name
    }

    fun frame() {
        if (framesThisRun >= Promo.frameLimit) throw EnoughFrames()
        framesThisRun++
        VirtualTime.advance(VirtualTime.FRAME_MS)
        timed("settle") { settle() }
        timed("looper") { looper.idleFor(Duration.ofMillis((uptimeBase + VirtualTime.nowMs - SystemClock.uptimeMillis()).coerceAtLeast(0))) }
        animators.pulse()
        applyInsets()
        timed("compose") {
            compose.mainClock.advanceTimeBy(VirtualTime.FRAME_MS)
            looper.idle()
        }
        timed("capture") { capture() }
        if (releaseFrames > 0 && --releaseFrames == 0) finger = null
    }

    fun frames(n: Int) = repeat(n) { frame() }

    /** How far the main looper's clock and the realtime clock the app reads stand ahead of the capture's, in ms. */
    fun clockDrift(): Pair<Long, Long> =
        (SystemClock.uptimeMillis() - uptimeBase - VirtualTime.nowMs) to (SystemClock.elapsedRealtime() - uptimeBase - VirtualTime.nowMs)

    fun hold(seconds: Double) = frames((seconds * 60).roundToInt())

    /**
     * Films frames until the capture's clock reads [ms], so that takes on different screens do what they do at the
     * same moment of the same run whatever each did in between.
     */
    fun at(ms: Long) {
        check(VirtualTime.nowMs <= ms) { "Already ${VirtualTime.nowMs - ms}ms past $ms" }
        while (VirtualTime.nowMs < ms) frame()
    }

    /** Films frames until [condition] holds, failing after [seconds] with the screen's semantics and a still left behind. */
    fun until(what: String, seconds: Double = 15.0, condition: () -> Boolean) {
        val limit = (seconds * 60).roundToInt()
        var waited = 0
        while (!condition()) {
            if (waited++ >= limit) {
                dump("timeout-${slug(what)}")
                still("timeout-${slug(what)}")
                error("Waited ${seconds}s for $what")
            }
            frame()
        }
    }

    /**
     * Waits for scripted work the last clock tick woke to be waiting again, then, if any of it said something, for
     * the app to have taken it in: its stream collectors and the repository's coalesced publication run on the wall
     * clock, on their own threads.
     */
    private fun settle() {
        val deadline = System.nanoTime() + SETTLE_TIMEOUT_MS * 1_000_000
        while (!VirtualTime.quiet()) {
            check(System.nanoTime() < deadline) { "The scripted backend was still busy after ${SETTLE_TIMEOUT_MS}ms" }
            Thread.sleep(1)
        }
        val emits = VirtualTime.emits
        if (emits == emitsSeen) return
        emitsSeen = emits
        val since = VirtualTime.wallMsSinceEmit() ?: 0L
        if (since < LANDING_MS) Thread.sleep(LANDING_MS - since)
    }

    // ---- Windows and insets ----------------------------------------------------------------------------------------

    /** Makes the window [next] under the running activity, as a fold, an unfold or a window resized in place does. */
    fun window(next: Screen) {
        screen = next
        RuntimeEnvironment.setQualifiers(next.qualifiers)
        controller.configurationChange()
        looper.idle()
        applyInsets()
    }

    /**
     * Tells every Compose view the app has open where the system bars are. Robolectric reports none, and it applies
     * its own again as a window lays out, so this runs each frame, between the looper and the frame: an unchanged
     * value costs nothing.
     */
    private fun applyInsets() {
        val density = screen.density
        val status = (screen.statusBarDp * density).roundToInt()
        val nav = (screen.navBarDp * density).roundToInt()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, status, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, nav))
            .setVisible(WindowInsetsCompat.Type.statusBars(), status > 0)
            .setVisible(WindowInsetsCompat.Type.navigationBars(), nav > 0)
            .build()
        for (root in fetchRobolectricWindowRoots()) {
            composeViews(root.decorView).forEach { ViewCompat.dispatchApplyWindowInsets(it, insets) }
        }
    }

    private fun composeViews(view: View, into: MutableList<View> = ArrayList()): List<View> {
        if (view.javaClass.name == COMPOSE_VIEW) into += view
        else if (view is ViewGroup) for (i in 0 until view.childCount) composeViews(view.getChildAt(i), into)
        return into
    }

    /** Where a window's top left is on the screen: a full-screen one at the origin, a popup where it was placed. */
    private fun windowOrigin(view: View, params: WindowManager.LayoutParams?): Pair<Float, Float> {
        if (params == null || (params.width == ViewGroup.LayoutParams.MATCH_PARENT && params.height == ViewGroup.LayoutParams.MATCH_PARENT)) return 0f to 0f
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        if (location[0] != 0 || location[1] != 0) return location[0].toFloat() to location[1].toFloat()
        return params.x.toFloat() to params.y.toFloat()
    }

    // ---- Capture ---------------------------------------------------------------------------------------------------

    private fun capture() {
        val meta = meta()
        marks.clear()
        if (!sink.wants()) {
            sink.skip(meta)
            return
        }
        val target = checkNotNull(compositor) { "No segment open" }
        val pixels = sink.buffer()
        render(target, scale, pixels, animate = true)
        sink.write(pixels, meta)
    }

    /**
     * Every window the app has open, bottom to top, where the window manager put it, over the dim a dialog asks for.
     * With [animate], [target] runs the windows' render-node animations from here on (see [Compositor.adoptAnimators]):
     * the segment's does, a still's short-lived one must not.
     */
    private fun render(target: Compositor, scale: Float, into: ByteArray, animate: Boolean) {
        target.light(activity, scale)
        target.render(into) { canvas ->
            canvas.drawColor(Color.BLACK)
            canvas.scale(scale, scale)
            for (root in fetchRobolectricWindowRoots()) {
                val view = root.decorView
                if (!view.isAttachedToWindow || view.visibility != View.VISIBLE || view.width == 0 || view.height == 0) continue
                // Brought up to date first: drawing it is what starts a press's ripple, whose animators are adopted with it.
                val displayList = Compositor.displayList(view)
                if (animate) target.adoptAnimators(view)
                val params = root.windowLayoutParams.orNull()
                if (params != null && params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                    canvas.drawColor(Color.argb((params.dimAmount * 255).roundToInt(), 0, 0, 0))
                }
                val (x, y) = windowOrigin(view, params)
                canvas.save()
                canvas.translate(x, y)
                canvas.drawRenderNode(displayList)
                canvas.restore()
            }
        }
    }

    private fun meta(): JsonObject = buildJsonObject {
        put("f", sink.frame)
        put("t", VirtualTime.nowMs)
        put("clock", CLOCK.format(Instant.ofEpochMilli(VirtualTime.wallMillis()).atOffset(ZoneOffset.UTC)))
        put("screen", screen.name)
        val decor = activity.window.decorView
        putJsonArray("content") {
            add(0)
            add(0)
            add((decor.width * scale).roundToInt())
            add((decor.height * scale).roundToInt())
        }
        put("statusBar", (screen.statusBarDp * screen.density * scale).roundToInt())
        put("navBar", (screen.navBarDp * screen.density * scale).roundToInt())
        finger?.let { f ->
            putJsonObject("touch") {
                put("x", (f.x * scale).roundToInt())
                put("y", (f.y * scale).roundToInt())
                put("down", f.down)
            }
        }
        if (marks.isNotEmpty()) putJsonArray("marks") { marks.forEach { add(it) } }
        extra?.invoke(this)
    }

    /** The screen as it is now, at full size, for looking at rather than filming. */
    fun still(name: String, scale: Float = 1f) {
        val decor = activity.window.decorView
        val width = even(decor.width * scale)
        val height = even(decor.height * scale)
        val pixels = ByteArray(width * height * 4)
        Compositor(width, height).use { render(it, scale, pixels, animate = false) }
        FrameSink.png(pixels, width, height, File(Promo.out, "$name.png"))
    }

    // ---- Input -----------------------------------------------------------------------------------------------------

    fun exists(matcher: SemanticsMatcher, unmerged: Boolean = false): Boolean =
        compose.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()

    fun node(matcher: SemanticsMatcher, unmerged: Boolean = false): SemanticsNodeInteraction =
        compose.onAllNodes(matcher, useUnmergedTree = unmerged).onFirst()

    private fun center(node: SemanticsNode): Pair<Float, Float> {
        val at = node.positionOnScreen
        return at.x + node.size.width / 2f to at.y + node.size.height / 2f
    }

    /**
     * [block]'s pointer input, handed to the app as a device hands it: what its handlers wake on the main thread runs
     * once they have returned, not under them. The test rule resumes its effects in place, so a queued send's handler
     * would already count its own new row among those standing, and its text would never land in it.
     */
    private fun <T> delivered(block: () -> T): T {
        var result: Result<T>? = null
        CoroutineScope(Dispatchers.Unconfined).launch { result = runCatching(block) }
        return checkNotNull(result) { "The input was not delivered" }.getOrThrow()
    }

    /** A finger down on [target], held [holdFrames] frames (long enough for the press to show) and lifted. */
    fun tap(target: SemanticsNodeInteraction, holdFrames: Int = 6, label: String? = null) {
        val (x, y) = center(target.fetchSemanticsNode())
        label?.let(::mark)
        finger = Finger(x, y, down = true)
        releaseFrames = 0
        delivered { target.performTouchInput { down(center) } }
        frames(holdFrames)
        delivered { target.performTouchInput { up() } }
        finger = Finger(x, y, down = false)
        releaseFrames = RELEASE_FRAMES
    }

    /**
     * A finger down on [target], held [holdFrames] frames and then cancelled rather than lifted, as a gesture the
     * system takes over is: the press shows and nothing is clicked. For a control whose action the capture takes itself.
     */
    fun touch(target: SemanticsNodeInteraction, holdFrames: Int = 6, label: String? = null) {
        val (x, y) = center(target.fetchSemanticsNode())
        label?.let(::mark)
        finger = Finger(x, y, down = true)
        releaseFrames = 0
        delivered { target.performTouchInput { down(center) } }
        frames(holdFrames)
        delivered { target.performTouchInput { cancel() } }
        finger = Finger(x, y, down = false)
        releaseFrames = RELEASE_FRAMES
    }

    /**
     * A finger down at ([x], [y]) on the screen, into the topmost window's content there (a sheet's scrim, say, which
     * has no node of its own to aim at), held [holdFrames] frames and lifted.
     */
    fun tapAt(x: Float, y: Float, holdFrames: Int = 6, label: String? = null) {
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        val nodes = roots.fetchSemanticsNodes()
        val top = nodes.indices.last { i ->
            val at = nodes[i].positionOnScreen
            x >= at.x && y >= at.y && x < at.x + nodes[i].size.width && y < at.y + nodes[i].size.height
        }
        val origin = nodes[top].positionOnScreen
        val local = Offset(x - origin.x, y - origin.y)
        label?.let(::mark)
        finger = Finger(x, y, down = true)
        releaseFrames = 0
        delivered { roots[top].performTouchInput { down(local) } }
        frames(holdFrames)
        delivered { roots[top].performTouchInput { up() } }
        finger = Finger(x, y, down = false)
        releaseFrames = RELEASE_FRAMES
    }

    /**
     * A finger put down at ([x], [y]) on the screen, into the window whose root [root] matches, and kept down: [moveTo]
     * carries it and [lift] lifts it. A hold that turns into a drag, which a node's own input cannot do once the node
     * it started on moves under the finger.
     */
    fun press(root: SemanticsMatcher, x: Float, y: Float, label: String? = null) {
        check(pressed == null) { "A finger is already down" }
        val window = compose.onNode(root, useUnmergedTree = true)
        val origin = window.fetchSemanticsNode().positionOnScreen
        label?.let(::mark)
        finger = Finger(x, y, down = true)
        releaseFrames = 0
        delivered { window.performTouchInput { down(Offset(x, y) - origin) } }
        pressed = Pressed(window, origin)
    }

    /** The finger [press] put down, moved to ([x], [y]) on the screen before the next frame. */
    fun moveTo(x: Float, y: Float) {
        val held = checkNotNull(pressed) { "No finger is down" }
        finger = Finger(x, y, down = true)
        delivered { held.root.performTouchInput { moveTo(Offset(x, y) - held.origin) } }
    }

    fun lift() {
        val held = checkNotNull(pressed) { "No finger is down" }
        val at = checkNotNull(finger)
        delivered { held.root.performTouchInput { up() } }
        pressed = null
        finger = Finger(at.x, at.y, down = false)
        releaseFrames = RELEASE_FRAMES
    }

    /** Where [node] is on the screen: its centre. */
    fun centerOf(node: SemanticsNodeInteraction): Offset = center(node.fetchSemanticsNode()).let { (x, y) -> Offset(x, y) }

    /** [text] typed into [field] a key at a time, at a quick typist's uneven pace (about 14 characters a second). */
    fun type(field: SemanticsNodeInteraction, text: String) {
        text.forEachIndexed { i, c ->
            field.performTextInput(c.toString())
            frames(TYPING_GAPS[i % TYPING_GAPS.size] + if (c == ' ') 2 else 0)
        }
    }

    /** The back gesture's key, to whichever window is on top: a sheet's dialog before the activity under it. */
    fun back(holdFrames: Int = 3) {
        val top = fetchRobolectricWindowRoots().last { it.decorView.isAttachedToWindow && it.decorView.visibility == View.VISIBLE }.decorView
        val now = SystemClock.uptimeMillis()
        top.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
        frames(holdFrames)
        top.dispatchKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
    }

    // ---- Looking -----------------------------------------------------------------------------------------------------

    /** Every node of every window, text, descriptions, tags and bounds on screen, for finding what to tap. */
    fun dump(name: String) {
        val out = StringBuilder()
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEachIndexed { i, root ->
            out.appendLine("# root $i")
            walk(root, 0, out)
        }
        File(Promo.out, "$name.semantics.txt").writeText(out.toString())
    }

    private fun walk(node: SemanticsNode, depth: Int, out: StringBuilder) {
        val at = node.positionOnScreen
        val facts = node.config.joinToString(" ") { (key, value) ->
            val shown = value.toString().replace('\n', ' ')
            "${key.name}=${if (shown.length > 80) shown.take(80) + "…" else shown}"
        }
        out.append("  ".repeat(depth))
            .append("[${at.x.roundToInt()},${at.y.roundToInt()} ${node.size.width}x${node.size.height}] ")
            .appendLine(facts)
        node.children.forEach { walk(it, depth + 1, out) }
    }

    fun printTimings() {
        val total = timings.values.sum().coerceAtLeast(1)
        println("promo: $framesThisRun frames, " + timings.entries.joinToString { (phase, ns) -> "$phase ${ns / 1_000_000 / framesThisRun.coerceAtLeast(1)}ms/frame (${ns * 100 / total}%)" })
    }

    private companion object {
        const val COMPOSE_VIEW = "androidx.compose.ui.platform.AndroidComposeView"
        const val SETTLE_TIMEOUT_MS = 5_000L

        /** Wall time an emission is given to be published: the repository coalesces a burst for up to 80 ms. */
        const val LANDING_MS = 150L

        /** Frames a lifted finger stays in the metadata, for the video to let its touch mark fade. */
        const val RELEASE_FRAMES = 12
        val TYPING_GAPS = intArrayOf(4, 3, 5, 4, 3, 4, 6, 3, 4, 5, 3, 4)
        val CLOCK: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("H:mm")

        fun even(value: Float): Int = (value.roundToInt() / 2) * 2
        fun even(value: Int): Int = (value / 2) * 2
        fun slug(text: String) = text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }
}
