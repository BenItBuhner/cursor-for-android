package com.cursorforandroid.promo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.PixelCopy
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.CursorApp
import com.cursorforandroid.DeferredStartup
import com.cursorforandroid.MainActivity
import com.cursorforandroid.data.demo.DemoStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.ReviewRepository
import com.cursorforandroid.ui.shortcuts.PaletteTags
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** [Screen.FoldCover] and [Screen.Desktop] as `@Config` needs them, as constants; [stage] checks they still agree. */
private const val FOLD_COVER = "w411dp-h797dp-port-night-420dpi"
private const val DESKTOP = "w1920dp-h1032dp-land-night-mdpi-keysexposed-qwerty"

/**
 * The launch video's footage: the app itself, on the demo account with the capture's scripted backend behind it,
 * driven a frame at a time by a [Director] and filmed to `promo/capture/out`. Each test is one continuous take on
 * one window, which may fold, unfold and grow under it as a device's does; the video is cut from the takes.
 *
 * Run through `promo/capture/run.sh <test>`: the harness is not part of the app's build.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = FOLD_COVER)
class LaunchVideoCapture {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CursorApp = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<MainActivity>? = null
    private var sink: FrameSink? = null
    private var director: Director? = null

    private val clock = AppClock.nowMillis
    private val settle = DeferredStartup.settleMs
    private val zone = TimeZone.getDefault()
    private val locale = Locale.getDefault()

    @Before
    fun stage() {
        check(Screen.FoldCover.qualifiers == FOLD_COVER && Screen.Desktop.qualifiers == DESKTOP) { "The @Config qualifiers no longer match the screens" }
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        VirtualTime.reset()
        PacingLog.reset()
        AppClock.nowMillis = VirtualTime::wallMillis
        // The notification channels, catalog refresh and background sync it starts are nothing the takes show.
        DeferredStartup.settleMs = Long.MAX_VALUE
    }

    @After
    fun strike() {
        director?.printTimings()
        runCatching { director?.endSegment() }
        runCatching { sink?.close() }
        runCatching { controller?.pause()?.stop()?.destroy() }
        shadowOf(Looper.getMainLooper()).idle()
        AppClock.nowMillis = clock
        DeferredStartup.settleMs = settle
        TimeZone.setDefault(zone)
        Locale.setDefault(locale)
    }

    /**
     * The app launched on [screen] as someone who has been using it: the demo account, the Cesium repository and the
     * model chosen in the composer, nothing new to read, the notification question already answered.
     */
    private fun launch(screen: Screen, name: String): Director {
        val store = DemoStore()
        val script = PromoScript.load()
        val backend = CursorBackend(PromoCursorApi(store), PromoRunStreamer(store, script), isDemo = true)
        val graph = AppGraph(app, SecureKeyStore(app) { app.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = backend)
        CursorApp::class.java.getDeclaredField("graph").apply { isAccessible = true }.set(app, graph)
        ReviewRepository::class.java.getDeclaredField("demo").apply { isAccessible = true }.set(graph.reviews, PromoReview(script))
        runBlocking {
            graph.prefs.setComposerDefaults(repoUrl = CESIUM_REPO, ref = "main", modelId = "composer-2.5", params = mapOf("fast" to "true"), autoCreatePr = false)
            graph.prefs.setWhatsNewReadVersion(graph.appVersion)
            graph.prefs.setNotificationPermissionAsked()
            graph.session.enterDemo()
        }
        val launched = Robolectric.buildActivity(MainActivity::class.java).setup().also { controller = it }
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(HOME_PLACEHOLDER, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val frames = FrameSink(File(Promo.out, name), Promo.preview).also { sink = it }
        return Director(compose, launched, frames, screen).also { director = it }
    }

    private fun finish(name: String) {
        director?.endSegment()
        PacingLog.write(File(Promo.out, "$name/pacing.json"))
        PacingLog.check()
    }

    /** A short take through everything the real ones lean on, with the screen's semantics and a still at each step. */
    @Test
    fun probe() {
        val d = launch(Screen.FoldCover, "probe")
        try {
            d.segment("probe", 0.5f, largest = Screen.Tablet)
            d.hold(0.5)
            d.dump("probe-home")
            d.still("probe-home", 0.5f)
            d.tap(d.node(hasText("Composer 2.5") and hasClickAction()), label = "model")
            d.hold(1.2)
            d.dump("probe-models")
            d.still("probe-models", 0.5f)
            d.back()
            d.hold(0.8)
            val field = d.node(hasSetTextAction())
            d.tap(field, label = "composer")
            d.hold(0.3)
            d.type(field, HERO_PROMPT)
            d.hold(0.4)
            d.dump("probe-typed")
            d.still("probe-typed", 0.5f)
            d.tap(d.node(hasTestTag("composer-main")), label = "send")
            d.until("the chat", 10.0) { d.exists(hasTestTag("chat-header")) }
            d.hold(4.0)
            d.dump("probe-running")
            d.still("probe-running", 0.5f)
            val followUp = d.node(hasSetTextAction() and hasAnyAncestor(hasTestTag("follow-up-composer")))
            d.tap(followUp, label = "follow-up")
            d.hold(0.3)
            d.type(followUp, QUEUED_PROMPT)
            d.hold(0.3)
            d.tap(d.node(hasTestTag("composer-main")), label = "queue")
            d.hold(1.0)
            d.dump("probe-queued")
            d.still("probe-queued", 0.5f)
            d.tap(d.node(hasContentDescription("Open panel")), label = "panel")
            d.hold(1.0)
            d.dump("probe-sheet")
            d.still("probe-sheet", 0.5f)
            d.mark("unfold")
            d.window(Screen.FoldInner)
            d.hold(1.0)
            d.dump("probe-inner")
            d.still("probe-inner", 0.5f)
            val steps = 40
            for (i in 1..steps) {
                d.window(Screen.FoldInner.toward(Screen.Tablet, i / steps.toFloat()))
                d.frame()
            }
            d.hold(3.0)
            d.dump("probe-tablet")
            d.still("probe-tablet", 0.5f)
        } catch (_: EnoughFrames) {
            d.still("probe-last", 0.5f)
        }
        finish("probe")
    }

    /**
     * Take A, the video's spine, on one window from start to end. On the Fold's cover screen: the model sheet, the task
     * typed and sent, its tools and subagents opened to watch, a follow-up queued behind the turn and the details
     * opened. Then the unfold, the details pinned beside the chat, and the window growing to the tablet's, where the
     * edits land in the panel, the tests run, the answer streams and the queued follow-up opens the PR.
     */
    @Test
    fun hero() {
        val d = launch(Screen.FoldCover, "hero")
        try {
            d.segment("phone", 0.75f)
            d.hold(1.2)
            d.tap(d.node(hasText("Composer 2.5") and hasClickAction()), label = "model")
            d.hold(2.2)
            d.tapAt(540f, 440f, label = "dismiss")
            d.hold(0.8)
            val field = d.node(hasSetTextAction())
            d.tap(field, label = "composer")
            d.hold(0.35)
            d.type(field, HERO_PROMPT)
            d.hold(0.6)
            d.tap(d.node(hasTestTag("composer-main")), label = "send")
            d.until("the chat", 10.0) { d.exists(hasTestTag("chat-header")) }
            // The home list's and the panel's "Working" are the chat's, not the stretch of tools this opens.
            val tools = hasText("Working") and hasAnyAncestor(hasTestTag("stretch"))
            d.until("the tools", 12.0) { d.exists(tools) }
            d.hold(0.45)
            d.tap(d.node(tools), label = "tools")
            val agents = hasText("2 agents") and hasAnyAncestor(hasTestTag("stretch"))
            d.until("the subagents", 15.0) { d.exists(agents) }
            d.hold(0.7)
            d.tap(d.node(agents), label = "subagents")
            d.hold(1.6)
            val followUp = d.node(hasSetTextAction() and hasAnyAncestor(hasTestTag("follow-up-composer")))
            d.tap(followUp, label = "follow-up")
            d.hold(0.35)
            d.type(followUp, QUEUED_PROMPT)
            d.hold(0.45)
            d.tap(d.node(hasTestTag("composer-main")), label = "queue")
            d.hold(1.4)
            d.tap(d.node(hasContentDescription("Open panel")), label = "panel")
            d.hold(1.3)
            d.dump("hero-phone-end")

            d.window(Screen.FoldInner)
            // Finer than the video shows it whole: the cut pushes in on the panel and the answer.
            d.segment("large", 0.75f, largest = Screen.Tablet)
            d.mark("unfold")
            d.hold(2.6)
            d.dump("hero-inner")
            d.mark("grow")
            val steps = 42
            for (i in 1..steps) {
                val t = i / steps.toFloat()
                d.window(Screen.FoldInner.toward(Screen.Tablet, t * t * (3 - 2 * t)))
                d.frame()
            }
            d.mark("tablet")
            d.hold(0.7)
            // Opening the stretches pinned the transcript where they opened; the turn goes on below it.
            d.tap(d.node(hasContentDescription("Scroll to latest")), label = "latest")
            d.until("the answer", 40.0) { d.exists(said("Added a usage meter")) }
            d.dump("hero-answer")
            d.until("the pull request", 20.0) { d.exists(said("Opened")) }
            d.hold(3.5)
            d.dump("hero-end")
            d.still("hero-end", 0.5f)
        } catch (_: EnoughFrames) {
            d.still("hero-last", 0.5f)
        }
        finish("hero")
    }

    /**
     * Take B, on a desktop-mode window with a hardware keyboard, from the keys alone: Ctrl held numbers the sidebar's
     * rows, Ctrl+1 opens the Project at the top, Ctrl+Shift+B its panel, Ctrl+K finds one of its workers by name and
     * Enter opens it, and Ctrl+Tab goes back to the Project.
     */
    @Test
    @Config(qualifiers = DESKTOP)
    fun desktop() {
        val d = launch(Screen.Desktop, "desktop")
        try {
            // A desktop window's mdpi text is small on a monitor seen whole; the cut pushes in on the palette.
            d.segment("desktop", 1.5f)
            d.hold(1.4)
            d.dump("desktop-home")
            d.ctrlDown()
            d.mark("numbers")
            d.hold(1.6)
            d.dump("desktop-numbers")
            d.press(KeyEvent.KEYCODE_1, ctrl = true, label = "project")
            d.hold(0.25)
            d.ctrlUp()
            d.until("the Project", 10.0) { d.exists(hasTestTag("chat-header") and hasContentDescription(PROJECT)) }
            d.hold(1.8)
            d.dump("desktop-project")
            d.chord(KeyEvent.KEYCODE_B, shift = true, label = "panel")
            d.until("the panel", 5.0) { d.exists(hasTestTag("conversation-panel")) }
            d.hold(2.6)
            d.dump("desktop-panel")
            d.chord(KeyEvent.KEYCODE_K, label = "search")
            d.until("the palette", 5.0) { d.exists(hasTestTag(PaletteTags.FIELD)) }
            d.hold(0.6)
            d.type(d.node(hasTestTag(PaletteTags.FIELD)), "webhook")
            d.until("the worker", 10.0) { d.exists(hasTestTag(PaletteTags.result(0)) and hasText(WORKER, substring = true)) }
            d.hold(1.2)
            d.dump("desktop-search")
            d.press(KeyEvent.KEYCODE_ENTER, label = "open")
            d.until("the worker's chat", 10.0) { d.exists(hasTestTag("chat-header") and hasContentDescription(WORKER)) }
            d.hold(2.2)
            d.dump("desktop-worker")
            d.ctrlDown()
            d.frames(3)
            d.press(KeyEvent.KEYCODE_TAB, ctrl = true, label = "switch")
            d.hold(1.1)
            d.dump("desktop-switcher")
            d.ctrlUp()
            d.until("the Project again", 10.0) { d.exists(hasTestTag("chat-header") and hasContentDescription(PROJECT)) }
            d.hold(2.0)
            d.dump("desktop-end")
            d.still("desktop-end", 0.5f)
        } catch (_: EnoughFrames) {
            d.still("desktop-last", 0.5f)
        }
        finish("desktop")
    }

    /**
     * The compositor against Robolectric's own PixelCopy of the window, which renders it the same way at full size,
     * and its byte order through to a PNG: a red fill has to come back red.
     */
    @Test
    fun renderCheck() {
        val d = launch(Screen.FoldCover, "render-check")
        d.segment("render-check", 0.5f)
        d.hold(0.2)
        val decor = d.activity.window.decorView
        val copy = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        var result = -1
        PixelCopy.request(d.activity.window, copy, { result = it }, Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        check(result == PixelCopy.SUCCESS) { "PixelCopy failed ($result)" }
        val expected = ByteArray(copy.byteCount).also { copy.copyPixelsToBuffer(ByteBuffer.wrap(it)) }
        val actual = ByteArray(decor.width * decor.height * 4)
        Compositor(decor.width, decor.height).use { c ->
            c.light(d.activity, 1f)
            c.render(actual) { it.drawRenderNode(Compositor.displayList(decor)) }
        }
        val off = (expected.indices step 4).count { i -> (0 until 4).any { abs((expected[i + it].toInt() and 255) - (actual[i + it].toInt() and 255)) > 2 } }
        println("promo: compositor vs PixelCopy: $off of ${expected.size / 4} pixels differ")
        check(off * 1000 < expected.size / 4) { "The compositor drew the window unlike PixelCopy: $off pixels differ" }
        val red = ByteArray(8 * 8 * 4)
        Compositor(8, 8).use { c -> c.render(red) { it.drawColor(Color.RED) } }
        val png = File(Promo.out, "render-check/red.png")
        FrameSink.png(red, 8, 8, png)
        val decoded = BitmapFactory.decodeFile(png.path).getPixel(4, 4)
        check(decoded == Color.RED) { "A red fill came back as #%08x".format(decoded) }
        finish("render-check")
    }

    /** [text] said in the chat itself, not in the panel or the sidebar beside it. */
    private fun said(text: String) = hasText(text, substring = true) and hasAnyAncestor(hasTestTag("transcript"))

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val HERO_PROMPT = "Add a usage meter to the account page"
        const val QUEUED_PROMPT = "Then open a PR"
        const val PROJECT = "Cesium billing launch"
        const val WORKER = "Stripe webhook handler"
    }
}
