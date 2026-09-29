package com.cursorforandroid.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.CursorApp
import com.cursorforandroid.MainActivity
import com.cursorforandroid.data.demo.DemoCursorApi
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.demo.DemoPace
import com.cursorforandroid.data.demo.DemoRunStreamer
import com.cursorforandroid.data.demo.DemoStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystem
import java.io.File
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/**
 * The demo behind `media/spotlight-v2/`: the app on the demo backend at [DemoPace.Realistic] (replies under 100 tokens
 * a second, tool calls and subagents taking seconds), with the real Spotlight and live notification services behind
 * it. The sidebar opens, the Cesium chat is long-pressed and put in the Spotlight, and the shade is pulled down over
 * the app to show what the services posted, drawn by the platform's own notification templates; the chat is opened
 * mid-run, and the shade pulled again to watch the subagents finish and the finished card take over.
 *
 * Capturing a frame costs real time, so the whole world runs [DILATION] times slower than the video: the demo's pace,
 * [AppClock], the main looper and the Compose clock. Each frame goes to `SPOTLIGHT_DEMO_FRAMES` with its video-time
 * offset in `times.txt`, so the assembled video plays at the run's real speed. Skipped without it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SpotlightDemoTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CursorApp = ApplicationProvider.getApplicationContext()
    private val frames = System.getenv("SPOTLIGHT_DEMO_FRAMES")?.takeIf { it.isNotBlank() }?.let(::File)
    private val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val services = mutableMapOf<String, ServiceController<out Service>>()
    private var main: ActivityController<MainActivity>? = null
    private val times = StringBuilder()
    private var frame = 0

    private var clockBase = 0L
    private var realBase = 0L
    private fun videoNow(): Long = AppClock.now() - clockBase

    private var uptimeBase = 0L
    private var composeBase = 0L

    /** Where a finger is, in screen pixels, and from when (video ms); drawn as a soft dot. */
    private var touch: Offset? = null
    private var touchSince = 0L
    private var shadeOpen = false
    private var shadeToggledAt = -10_000L

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
        services.values.forEach { runCatching { it.destroy() } }
        main?.let { runCatching { it.pause().stop().destroy() } }
    }

    @Test
    fun `spotlight a running chat from its long-press menu and watch it live`() {
        assumeTrue(frames != null)
        frames!!.mkdirs()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        realBase = System.currentTimeMillis()
        clockBase = realBase
        AppClock.nowMillis = { realBase + (System.currentTimeMillis() - realBase) / DILATION }

        val store = DemoStore(DemoData.seeds)
        // The seeded live run is dated with its chat, a day old; this one started a few seconds before the recording.
        store.runsOf(CESIUM)?.firstOrNull()?.let { run -> store.updateRun(CESIUM, run.id) { it.copy(createdAt = store.iso(AppClock.now() - 6_000L)) } }
        val pace = DemoPace.Realistic.let {
            it.copy(typeDelayMs = it.typeDelayMs * DILATION, thinkDelayMs = it.thinkDelayMs * DILATION, stepScale = it.stepScale * DILATION)
        }
        CursorApp::class.java.getDeclaredField("graph").apply { isAccessible = true }
            .set(app, AppGraph(app, demo = CursorBackend(DemoCursorApi(store), DemoRunStreamer(store, pace), isDemo = true)))
        runBlocking { app.graph.session.enterDemo() }

        compose.mainClock.autoAdvance = false
        uptimeBase = SystemClock.uptimeMillis()
        composeBase = compose.mainClock.currentTime
        main = Robolectric.buildActivity(MainActivity::class.java).setup()

        val script = mutableListOf<Pair<Long, () -> Unit>>(
            1_400L to { touch(node { compose.onNodeWithContentDescription("Open sidebar") }) },
            1_750L to { compose.onNodeWithContentDescription("Open sidebar").performClick(); release() },
            3_300L to { touch(node { cesiumRow() }) },
            3_800L to { cesiumRow().performTouchInput { longClick() } },
            4_300L to { release() },
            6_400L to { touch(node { compose.onAllNodesWithText("Spotlight").onFirst() }) },
            6_700L to { compose.onAllNodesWithText("Spotlight").onFirst().performClick(); release() },
            7_600L to { shade(true) },
            34_500L to { shade(false) },
            35_700L to { touch(node { cesiumRow() }) },
            36_000L to { cesiumRow().performClick(); release() },
            50_000L to { main!!.get().onBackPressedDispatcher.onBackPressed() },
            51_500L to { shade(true) },
        )
        var finishedAt = -1L
        while (true) {
            val now = videoNow()
            advanceTo(now)
            pumpServices()
            while (script.isNotEmpty() && script.first().first <= now) {
                script.removeAt(0).second()
                pumpServices()
            }
            capture(now)
            val cesiumFinished = manager.allNotifications.any {
                it.channelId == LiveNotifications.CHANNEL_FINISHED && it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Cesium Revenue Strategy"
            }
            if (finishedAt < 0 && cesiumFinished) finishedAt = now
            if (finishedAt >= 0 && now - finishedAt > HOLD_MS && script.isEmpty()) break
            if (now > (System.getenv("SPOTLIGHT_DEMO_MAX_MS")?.toLongOrNull() ?: MAX_MS)) break
        }
        File(frames, "times.txt").writeText(times.toString())
    }

    /** The chat's row in the sidebar, not its card on the home screen behind it. */
    private fun cesiumRow(): SemanticsNodeInteraction = compose.onAllNodes(
        hasText("Cesium Revenue Strategy") and hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Navigation menu")),
    ).onFirst()

    private fun node(find: () -> SemanticsNodeInteraction): Offset {
        val n = find().fetchSemanticsNode()
        val b = n.boundsInWindow
        return Offset((b.left + b.right) / 2f, (b.top + b.bottom) / 2f)
    }

    private fun touch(at: Offset) {
        touch = at
        touchSince = videoNow()
    }

    private fun release() {
        touch = null
    }

    private fun shade(open: Boolean) {
        shadeOpen = open
        shadeToggledAt = videoNow()
    }

    /** Brings the looper and the Compose clock up to [videoMs]; an input that ran them ahead just waits. */
    private fun advanceTo(videoMs: Long) {
        val looperBehind = videoMs - (SystemClock.uptimeMillis() - uptimeBase)
        if (looperBehind > 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(looperBehind))
        val composeBehind = videoMs - (compose.mainClock.currentTime - composeBase)
        if (composeBehind > 0) compose.mainClock.advanceTimeBy(composeBehind)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Services the app asked for, started and stopped as the platform would; the ones that stopped themselves let go. */
    private fun pumpServices() {
        while (true) {
            val intent = shadowOf(app).nextStartedService ?: break
            val name = intent.component?.className ?: continue
            val existing = services[name]
            if (existing != null) {
                existing.withIntent(intent).startCommand(0, frame + 2)
            } else {
                val cls = Class.forName(name).asSubclass(Service::class.java)
                services[name] = Robolectric.buildService(cls, intent).create().startCommand(0, 1)
            }
        }
        while (true) {
            val intent = shadowOf(app).nextStoppedService ?: break
            services.remove(intent.component?.className)?.let { runCatching { it.destroy() } }
        }
        services.entries.removeAll { (_, c) ->
            val stopped = shadowOf(c.get()).isStoppedBySelf
            if (stopped) runCatching { c.destroy() }
            stopped
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun capture(videoMs: Long) {
        val file = File.createTempFile("spotlight-frame", ".png")
        captureScreenRoboImage(file.path, RoborazziOptions(taskType = RoborazziTaskType.Record))
        val screen = checkNotNull(BitmapFactory.decodeFile(file.path)) { "no frame captured" }.also { file.delete() }
        val out = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(screen, 0f, 0f, null)
        drawTouch(canvas, videoMs)
        drawShade(canvas, videoMs, screen.width)
        val scaled = Bitmap.createScaledBitmap(out, OUT_W, OUT_W * out.height / out.width / 2 * 2, true)
        val name = "frame-%05d.jpg".format(frame++)
        File(frames, name).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        times.append(name).append(' ').append(videoMs).append('\n')
    }

    private val density get() = app.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private fun drawTouch(canvas: Canvas, videoMs: Long) {
        val at = touch ?: return
        val grow = min(1f, (videoMs - touchSince) / 180f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb((110 * grow).toInt(), 255, 255, 255) }
        canvas.drawCircle(at.x, at.y, dp(22f) * (0.7f + 0.3f * grow), paint)
    }

    private fun shadeProgress(videoMs: Long): Float {
        val t = min(1f, max(0f, (videoMs - shadeToggledAt) / SHADE_MS.toFloat()))
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        return if (shadeOpen) eased else 1f - eased
    }

    /** The notification shade over the app: the posted notifications, most important first, as the platform draws them. */
    private fun drawShade(canvas: Canvas, videoMs: Long, width: Int) {
        val p = shadeProgress(videoMs)
        if (p <= 0f) return
        canvas.drawColor(Color.argb((150 * p).toInt(), 0, 0, 0))
        val margin = dp(12f)
        val cardWidth = (width - 2 * margin).toInt()
        val cards = manager.allNotifications
            .sortedBy { order(it) }
            .map { card(it, cardWidth) }
        val header = dp(64f)
        val gap = dp(10f)
        val height = header + cards.sumOf { it.height.toDouble() }.toFloat() + gap * cards.size + dp(20f)
        val top = -height * (1f - p)
        canvas.save()
        canvas.translate(0f, top)
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF16171B.toInt() }
        canvas.drawRoundRect(RectF(0f, -dp(40f), width.toFloat(), height), dp(28f), dp(28f), panel)
        val clock = LocalTime.of(20, 6).plusSeconds(videoMs / 1000).format(DateTimeFormatter.ofPattern("H:mm"))
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(26f); typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB7B9C0.toInt(); textSize = dp(13f) }
        canvas.drawText(clock, margin + dp(8f), dp(40f), big)
        canvas.drawText("Tue, Sep 29", margin + dp(8f) + big.measureText(clock) + dp(12f), dp(40f), small)
        var y = header
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2C31.toInt() }
        cards.forEach { bmp ->
            canvas.drawRoundRect(RectF(margin, y, margin + cardWidth, y + bmp.height), dp(22f), dp(22f), cardPaint)
            canvas.drawBitmap(bmp, margin, y, null)
            y += bmp.height + gap
        }
        canvas.restore()
    }

    private fun order(n: Notification): Int = when (n.channelId) {
        LiveNotifications.CHANNEL_SPOTLIGHT -> 0
        LiveNotifications.CHANNEL_FINISHED -> 2
        else -> 1
    }

    private val shadeContext by lazy { ContextThemeWrapper(app, android.R.style.Theme_DeviceDefault) }

    /** Inflated cards by content: inflating decodes the icons again, and Robolectric's decoder does not survive thousands. */
    private val inflated = mutableMapOf<String, FrameLayout>()

    private fun Notification.contentKey(): String = listOf(
        channelId, `when`, actions?.size,
        extras.getCharSequence(Notification.EXTRA_TITLE),
        extras.getCharSequence(Notification.EXTRA_TEXT),
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
    ).joinToString("|")

    private fun card(n: Notification, width: Int): Bitmap {
        val parent = inflated.getOrPut(n.contentKey()) {
            val builder = Notification.Builder.recoverBuilder(app, n)
            val remote = builder.createBigContentView() ?: builder.createContentView()
            FrameLayout(shadeContext).apply {
                val pad = dp(4f).toInt()
                setPadding(pad, pad, pad, pad)
                addView(remote.apply(shadeContext, this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        parent.findChronometer()?.apply {
            stop()
            base = SystemClock.elapsedRealtime() - max(0L, AppClock.now() - n.`when`)
        }
        // The shade's "now" and "5m" read the framework's wall clock, which Robolectric keeps near 1970.
        parent.findDateTimeView()?.let { view ->
            view.javaClass.getMethod("setTime", Long::class.javaPrimitiveType).invoke(view, ShadowSystem.currentTimeMillis() - (AppClock.now() - n.`when`))
        }
        parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST))
        parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
        val bmp = Bitmap.createBitmap(parent.measuredWidth, max(1, parent.measuredHeight), Bitmap.Config.ARGB_8888)
        parent.draw(Canvas(bmp))
        return bmp
    }

    private fun View.findChronometer(): Chronometer? = when (this) {
        is Chronometer -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findChronometer() }
        else -> null
    }

    private fun View.findDateTimeView(): View? = when {
        javaClass.simpleName == "DateTimeView" && visibility == View.VISIBLE -> this
        this is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findDateTimeView() }
        else -> null
    }

    private companion object {
        const val CESIUM = "bc-demo-0003"
        const val DILATION = 6L
        const val SHADE_MS = 380L
        const val HOLD_MS = 4_500L
        const val MAX_MS = 110_000L
        const val OUT_W = 720
    }
}
