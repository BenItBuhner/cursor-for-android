package com.cursorforandroid.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
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
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.demo.DemoCursorApi
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.demo.DemoPace
import com.cursorforandroid.data.demo.DemoRunStreamer
import com.cursorforandroid.data.demo.DemoStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.GeneratedImageStore
import com.cursorforandroid.data.repo.LiveRunHub
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
 * The Spotlight demos: the app on the demo backend at [DemoPace.Realistic] (replies under 100 tokens a second, tool
 * calls and subagents taking seconds), with the real Spotlight and live notification services behind it. A shade
 * pulled down over the app shows what the services posted, drawn by the platform's own notification templates, and
 * the card's taps go through its real intents: the body's content intent, the Stop action's service intent, and a
 * swipe's delete intent after the card is dismissed as the system dismisses it.
 *
 *  - [chatLifecycleDark] / [chatLifecycleLight]: a running chat spotlit from its long-press menu, followed live
 *    through its steps and subagents, opened mid-run, and handed over to the finished card.
 *  - [interactionsDark]: tapping the card opens the chat; Stop Spotlight on the card, in the menu, and a swipe away.
 *  - [projectTablet]: a Project spotlit on a tablet, its running chats as bullets, until it has nothing running.
 *
 * Capturing a frame costs real time, so the whole world runs [DILATION] times slower than the video: the demo's pace,
 * [AppClock], the main looper and the Compose clock. Each scenario writes its frames to a folder of its own under
 * `SPOTLIGHT_DEMO_FRAMES`, with their video-time offsets in `times.txt`, so the assembled video plays at the run's
 * real speed. Skipped without it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_DARK)
class SpotlightDemoTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CursorApp = ApplicationProvider.getApplicationContext()
    private val root = System.getenv("SPOTLIGHT_DEMO_FRAMES")?.takeIf { it.isNotBlank() }?.let(::File)
    private lateinit var frames: File
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
    private var captions: List<Pair<Long, String>> = emptyList()

    /** The Spotlight card being swiped away, and since when. */
    private var swipeSince: Long? = null

    /** Where the last frame drew each shade card, and its action buttons, in screen pixels. */
    private class Hit(val notification: Notification, val bounds: RectF, val actions: Map<String, RectF>)
    private val hits = mutableMapOf<String, Hit>()

    private val night get() = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val tablet get() = app.resources.configuration.smallestScreenWidthDp >= 600

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
        services.values.forEach { runCatching { it.destroy() } }
        main?.let { runCatching { it.pause().stop().destroy() } }
    }

    @Test
    fun chatLifecycleDark() = record("chat-lifecycle-phone-dark", chatLifecycle(), chatCaptions, maxMs = 110_000L) { cesiumFinishedFor(HOLD_MS) }

    @Test
    @Config(qualifiers = PHONE_LIGHT)
    fun chatLifecycleLight() = record("chat-lifecycle-phone-light", chatLifecycle(), chatCaptions, maxMs = 110_000L) { cesiumFinishedFor(HOLD_MS) }

    @Test
    fun interactionsDark() = record("interactions-phone-dark", interactions(), interactionCaptions, maxMs = 58_000L) { false }

    @Test
    @Config(qualifiers = TABLET_DARK)
    fun projectTablet() = record("project-tablet-dark", project(), projectCaptions, maxMs = 95_000L, setup = ::runProject) { projectEndedFor(3_000L) }

    private fun chatLifecycle(): List<Pair<Long, () -> Unit>> = listOf(
        1_400L to { touch(node { compose.onNodeWithContentDescription("Open sidebar") }) },
        1_750L to { compose.onNodeWithContentDescription("Open sidebar").performClick(); release() },
        3_300L to { touch(node { row(CESIUM_NAME) }) },
        3_800L to { row(CESIUM_NAME).performTouchInput { longClick() } },
        4_300L to { release() },
        6_400L to { touch(node { compose.onAllNodesWithText("Spotlight").onFirst() }) },
        6_700L to { compose.onAllNodesWithText("Spotlight").onFirst().performClick(); release() },
        7_600L to { shade(true) },
        34_500L to { shade(false) },
        35_700L to { touch(node { row(CESIUM_NAME) }) },
        36_000L to { row(CESIUM_NAME).performClick(); release() },
        50_000L to { back() },
        51_500L to { shade(true) },
    )

    private val chatCaptions = listOf(
        0L to "A chat is running. Open the sidebar",
        3_000L to "Long-press the running chat",
        5_600L to "Tap Spotlight",
        7_400L to "Its own live card. The regular live notification leaves it out",
        14_000L to "The card follows the run: the current step, then a tally",
        24_000L to "Subagents show as bullets while they run",
        35_000L to "Open the chat: the card and the transcript agree",
        51_000L to "Subagents finish one by one, then the reply is written",
        66_000L to "When the run ends, the finished card takes over",
    )

    private fun interactions(): List<Pair<Long, () -> Unit>> = listOf(
        1_200L to { compose.onNodeWithContentDescription("Open sidebar").performClick() },
        2_400L to { touch(node { row(CESIUM_NAME) }) },
        2_800L to { row(CESIUM_NAME).performTouchInput { longClick() } },
        3_200L to { release() },
        4_600L to { touch(node { compose.onAllNodesWithText("Spotlight").onFirst() }) },
        4_900L to { compose.onAllNodesWithText("Spotlight").onFirst().performClick(); release() },
        5_800L to { shade(true) },
        10_500L to { touchCard() },
        10_900L to { tapCard(); release() },
        17_000L to { back() },
        18_500L to { shade(true) },
        21_500L to { touchAction(STOP) },
        21_900L to { tapAction(STOP); release() },
        27_500L to { shade(false) },
        28_600L to { compose.onNodeWithContentDescription("Open sidebar").performClick() },
        29_800L to { row(CESIUM_NAME).performTouchInput { longClick() } },
        31_000L to { compose.onAllNodesWithText("Spotlight").onFirst().performClick() },
        32_600L to { touch(node { row(CESIUM_NAME) }) },
        33_000L to { row(CESIUM_NAME).performTouchInput { longClick() } },
        33_400L to { release() },
        35_400L to { touch(node { compose.onAllNodesWithText(STOP).onFirst() }) },
        35_700L to { compose.onAllNodesWithText(STOP).onFirst().performClick(); release() },
        36_400L to { shade(true) },
        39_400L to { shade(false) },
        40_400L to { row(CESIUM_NAME).performTouchInput { longClick() } },
        41_600L to { compose.onAllNodesWithText("Spotlight").onFirst().performClick() },
        42_600L to { shade(true) },
        46_000L to { startSwipe() },
        46_000L + SWIPE_MS to { finishSwipe() },
        51_500L to { shade(false) },
    )

    private val interactionCaptions = listOf(
        0L to "Spotlight a running chat from its long-press menu",
        5_800L to "The Spotlight card is live in the shade",
        10_300L to "Tap the card: it opens that chat",
        17_000L to "Back out, pull the shade down again",
        21_200L to "Stop Spotlight on the card",
        22_400L to "The card goes; the live notification takes the chat back",
        28_400L to "Spotlight it again, then long-press once more",
        34_800L to "The menu now offers Stop Spotlight",
        36_400L to "Stopped: only the live notification is left",
        40_000L to "Spotlight it once more",
        45_600L to "Swipe the card away: that stops it too",
        49_000L to "The live notification takes the chat back",
    )

    private fun project(): List<Pair<Long, () -> Unit>> = listOf(
        2_400L to { touch(node { row(PROJECT_NAME) }) },
        2_800L to { row(PROJECT_NAME).performTouchInput { longClick() } },
        3_200L to { release() },
        5_000L to { touch(node { compose.onAllNodesWithText("Spotlight").onFirst() }) },
        5_300L to { compose.onAllNodesWithText("Spotlight").onFirst().performClick(); release() },
        6_200L to { shade(true) },
    )

    private val projectCaptions = listOf(
        0L to "A Project with its coordinator and three chats running",
        2_200L to "Long-press the Project, tap Spotlight",
        6_000L to "The coordinator's step, its running chats as bullets, and a count",
        13_000L to "As chats finish they drop off the card",
        44_000L to "With nothing running it waits 30 s for the next turn, then ends",
    )

    /** The Project's coordinator and its three chats each start a turn just before the recording. */
    private fun runProject(store: DemoStore) {
        listOf(
            PROJECT to ("/multitask Plan the checkout track while the workers finish." to "multitask"),
            "bc-demo-0019" to ("Backfill the last 90 days instead of 30." to "generic"),
            "bc-demo-0020" to ("/multitask Rebase onto the merged aggregation and read the invoice amounts from usageDaily." to "multitask"),
            "bc-demo-0021" to ("Tighten the pricing copy to one line per tier." to "generic"),
        ).forEachIndexed { i, (id, turn) ->
            val agent = checkNotNull(store.agent(id))
            val at = store.iso(AppClock.now() - 8_000L + i * 1_000L)
            store.addRun(agent, RunDto(id = store.nextId("run"), agentId = id, status = "RUNNING", createdAt = at, updatedAt = at), turn.first, turn.second, null)
            store.setV0Status(id, "RUNNING")
        }
    }

    private fun record(
        name: String,
        script: List<Pair<Long, () -> Unit>>,
        captions: List<Pair<Long, String>>,
        maxMs: Long,
        setup: (DemoStore) -> Unit = {},
        done: () -> Boolean,
    ) {
        assumeTrue(root != null)
        frames = File(root, name).apply { deleteRecursively(); mkdirs() }
        this.captions = captions
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        realBase = System.currentTimeMillis()
        clockBase = realBase
        AppClock.nowMillis = { realBase + (System.currentTimeMillis() - realBase) / DILATION }

        val store = DemoStore(DemoData.seeds)
        // The seeded live run is dated with its chat, a day old; this one started a few seconds before the recording.
        store.runsOf(CESIUM)?.firstOrNull()?.let { run -> store.updateRun(CESIUM, run.id) { it.copy(createdAt = store.iso(AppClock.now() - 6_000L)) } }
        setup(store)
        val pace = DemoPace.Realistic.let {
            it.copy(typeDelayMs = it.typeDelayMs * DILATION, thinkDelayMs = it.thinkDelayMs * DILATION, stepScale = it.stepScale * DILATION)
        }
        val graph = AppGraph(app, demo = CursorBackend(DemoCursorApi(store), DemoRunStreamer(store, pace), isDemo = true))
        // The hub's stall watch counts wall time, so slowed down it would take each of the demo's longer steps for a
        // stalled connection; and the demo's stream answers a resume by playing its script again from the start.
        AppGraph::class.java.getDeclaredField("lazyLiveRuns").apply { isAccessible = true }.set(
            graph,
            lazy {
                LiveRunHub(
                    graph.session, graph.agents,
                    images = GeneratedImageStore { agentId, callId, bytes, mimeType -> graph.generatedMedia.save(agentId, callId, bytes, mimeType) },
                    parking = graph.caches.liveRuns,
                    stallTimeoutMs = 30_000L * DILATION,
                    stallMaxMs = 300_000L * DILATION,
                )
            },
        )
        CursorApp::class.java.getDeclaredField("graph").apply { isAccessible = true }.set(app, graph)
        runBlocking { app.graph.session.enterDemo() }

        compose.mainClock.autoAdvance = false
        uptimeBase = SystemClock.uptimeMillis()
        composeBase = compose.mainClock.currentTime
        main = Robolectric.buildActivity(MainActivity::class.java).setup()

        val pending = script.sortedBy { it.first }.toMutableList()
        while (true) {
            val now = videoNow()
            advanceTo(now)
            pumpServices()
            while (pending.isNotEmpty() && pending.first().first <= now) {
                pending.removeAt(0).second()
                pumpServices()
            }
            capture(now)
            if (pending.isEmpty() && done()) break
            if (now > (System.getenv("SPOTLIGHT_DEMO_MAX_MS")?.toLongOrNull() ?: maxMs)) break
        }
        File(frames, "times.txt").writeText(times.toString())
    }

    private var cesiumFinishedAt = -1L
    private fun cesiumFinishedFor(ms: Long): Boolean {
        val finished = manager.allNotifications.any {
            it.channelId == LiveNotifications.CHANNEL_FINISHED && it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == CESIUM_NAME
        }
        if (cesiumFinishedAt < 0 && finished) cesiumFinishedAt = videoNow()
        return cesiumFinishedAt >= 0 && videoNow() - cesiumFinishedAt > ms
    }

    private var spotlightSeen = false
    private var projectEndedAt = -1L
    private fun projectEndedFor(ms: Long): Boolean {
        val showing = manager.allNotifications.any { it.channelId == LiveNotifications.CHANNEL_SPOTLIGHT }
        if (showing) spotlightSeen = true
        if (spotlightSeen && !showing && projectEndedAt < 0) projectEndedAt = videoNow()
        return projectEndedAt >= 0 && videoNow() - projectEndedAt > ms
    }

    /** The chat's row in the sidebar: in the drawer's pane on a phone, the leftmost match beside the content on a tablet. */
    private fun row(name: String): SemanticsNodeInteraction {
        val inDrawer = compose.onAllNodes(hasText(name) and hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Navigation menu")))
        if (inDrawer.fetchSemanticsNodes().isNotEmpty()) return inDrawer.onFirst()
        val all = compose.onAllNodes(hasText(name))
        val leftmost = all.fetchSemanticsNodes().withIndex().minBy { it.value.boundsInWindow.left }.index
        return all[leftmost]
    }

    private fun back() = main!!.get().onBackPressedDispatcher.onBackPressed()

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

    private fun spotlightHit(): Hit = checkNotNull(hits[LiveNotifications.CHANNEL_SPOTLIGHT]) { "no Spotlight card in the shade" }

    private fun touchCard() {
        val b = spotlightHit().bounds
        touch(Offset(b.centerX(), b.top + b.height() * 0.3f))
    }

    /** The body's tap: the shade folds away and the card's content intent reaches the activity. */
    private fun tapCard() {
        spotlightHit().notification.contentIntent.send()
        shadeOpen = false
        shadeToggledAt = videoNow() - SHADE_MS
        val intent = checkNotNull(shadowOf(app).nextStartedActivity) { "the card's tap started nothing" }
        main!!.newIntent(intent)
    }

    private fun touchAction(title: String) {
        val b = checkNotNull(spotlightHit().actions[title]) { "no $title button" }
        touch(Offset(b.centerX(), b.centerY()))
    }

    private fun tapAction(title: String) {
        spotlightHit().notification.actions.first { it.title.toString() == title }.actionIntent.send()
    }

    private fun startSwipe() {
        swipeSince = videoNow()
    }

    /** What the system does when a card is swiped off: removes it, then sends its delete intent. */
    private fun finishSwipe() {
        val n = spotlightHit().notification
        swipeSince = null
        app.getSystemService(NotificationManager::class.java).cancel(SpotlightRenderer.SPOTLIGHT_ID)
        n.deleteIntent.send()
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
        val band = dp(CAPTION_BAND_DP).toInt()
        val out = Bitmap.createBitmap(screen.width, screen.height + band, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(screen, 0f, 0f, null)
        canvas.save()
        canvas.clipRect(0, 0, screen.width, screen.height)
        drawShade(canvas, videoMs, screen.width)
        drawTouch(canvas, videoMs)
        canvas.restore()
        drawCaption(canvas, videoMs, screen.width, screen.height, band)
        val outW = if (tablet) OUT_W_TABLET else OUT_W
        val scaled = Bitmap.createScaledBitmap(out, outW, outW * out.height / out.width / 2 * 2, true)
        val name = "frame-%05d.jpg".format(frame++)
        File(frames, name).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        times.append(name).append(' ').append(videoMs).append('\n')
        // Native pixels go back only when a finalizer runs, which a small heap seldom asks for; thousands of frames need them freed now.
        listOf(screen, out, scaled).distinct().forEach(Bitmap::recycle)
    }

    private val density get() = app.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private fun drawTouch(canvas: Canvas, videoMs: Long) {
        val at = touch ?: return
        val grow = min(1f, (videoMs - touchSince) / 180f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) Color.argb((110 * grow).toInt(), 255, 255, 255) else Color.argb((90 * grow).toInt(), 0, 0, 0) }
        canvas.drawCircle(at.x, at.y, dp(22f) * (0.7f + 0.3f * grow), paint)
    }

    /** The step being shown, under the screen: what the viewer is looking at, in a few words. */
    private fun drawCaption(canvas: Canvas, videoMs: Long, width: Int, top: Int, band: Int) {
        canvas.drawRect(0f, top.toFloat(), width.toFloat(), (top + band).toFloat(), Paint().apply { color = if (night) 0xFF0B0B0D.toInt() else 0xFFE6E7EB.toInt() })
        val (since, text) = captions.lastOrNull { it.first <= videoMs } ?: return
        val alpha = (min(1f, (videoMs - since) / 250f) * 255).toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (night) Color.argb(alpha, 240, 240, 242) else Color.argb(alpha, 24, 25, 28)
            textSize = dp(if (tablet) 20f else 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(text, width / 2f, top + band / 2f + paint.textSize * 0.35f, paint)
    }

    private fun shadeProgress(videoMs: Long): Float {
        val t = min(1f, max(0f, (videoMs - shadeToggledAt) / SHADE_MS.toFloat()))
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        return if (shadeOpen) eased else 1f - eased
    }

    /** The notification shade over the app: the posted notifications, most important first, as the platform draws them. */
    private fun drawShade(canvas: Canvas, videoMs: Long, width: Int) {
        hits.clear()
        val p = shadeProgress(videoMs)
        if (p <= 0f) return
        canvas.drawColor(Color.argb(((if (night) 150 else 110) * p).toInt(), 0, 0, 0))
        val panelWidth = if (tablet) min(width.toFloat(), dp(560f)) else width.toFloat()
        val left = if (tablet) width - panelWidth - dp(16f) else 0f
        val margin = dp(12f)
        val cardWidth = (panelWidth - 2 * margin).toInt()
        val posted = manager.allNotifications.sortedBy { order(it) }
        val cards = posted.map { it to card(it, cardWidth) }
        val header = dp(64f)
        val gap = dp(10f)
        val height = header + cards.sumOf { it.second.bitmap.height.toDouble() }.toFloat() + gap * cards.size + dp(20f)
        val top = -height * (1f - p)
        canvas.save()
        canvas.translate(left, top)
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) 0xFF16171B.toInt() else 0xFFF0F1F5.toInt() }
        canvas.drawRoundRect(RectF(0f, -dp(40f), panelWidth, height), dp(28f), dp(28f), panel)
        val clock = LocalTime.of(20, 6).plusSeconds(videoMs / 1000).format(DateTimeFormatter.ofPattern("H:mm"))
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) Color.WHITE else 0xFF1B1C1F.toInt(); textSize = dp(26f); typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) 0xFFB7B9C0.toInt() else 0xFF5E6168.toInt(); textSize = dp(13f) }
        canvas.drawText(clock, margin + dp(8f), dp(40f), big)
        canvas.drawText("Thu, Oct 1", margin + dp(8f) + big.measureText(clock) + dp(12f), dp(40f), small)
        var y = header
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) 0xFF2A2C31.toInt() else Color.WHITE }
        cards.forEach { (n, card) ->
            val bmp = card.bitmap
            val swiped = n.channelId == LiveNotifications.CHANNEL_SPOTLIGHT && swipeSince != null
            val shift = if (swiped) {
                val t = min(1f, (videoMs - swipeSince!!) / SWIPE_MS.toFloat())
                t * t * (panelWidth + margin)
            } else {
                0f
            }
            canvas.save()
            canvas.translate(shift, 0f)
            canvas.drawRoundRect(RectF(margin, y, margin + cardWidth, y + bmp.height), dp(22f), dp(22f), cardPaint)
            canvas.drawBitmap(bmp, margin, y, null)
            if (swiped) {
                val finger = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) Color.argb(110, 255, 255, 255) else Color.argb(90, 0, 0, 0) }
                canvas.drawCircle(margin + cardWidth * 0.35f, y + bmp.height * 0.4f, dp(22f), finger)
            }
            canvas.restore()
            val x0 = left + margin + shift
            val y0 = top + y
            hits.putIfAbsent(
                n.channelId,
                Hit(n, RectF(x0, y0, x0 + cardWidth, y0 + bmp.height), card.actions.mapValues { (_, r) -> RectF(r).apply { offset(x0, y0) } }),
            )
            y += bmp.height + gap
            bmp.recycle()
        }
        canvas.restore()
    }

    private fun order(n: Notification): Int = when (n.channelId) {
        LiveNotifications.CHANNEL_SPOTLIGHT -> 0
        LiveNotifications.CHANNEL_FINISHED -> 2
        else -> 1
    }

    private val shadeContext by lazy {
        ContextThemeWrapper(app, if (night) android.R.style.Theme_DeviceDefault else android.R.style.Theme_DeviceDefault_Light)
    }

    private class Card(val bitmap: Bitmap, val actions: Map<String, RectF>)

    /** Inflated cards by content: inflating decodes the icons again, and Robolectric's decoder does not survive thousands. */
    private val inflated = mutableMapOf<String, FrameLayout>()

    private fun Notification.contentKey(): String = listOf(
        channelId, `when`, actions?.size,
        extras.getCharSequence(Notification.EXTRA_TITLE),
        extras.getCharSequence(Notification.EXTRA_TEXT),
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
    ).joinToString("|")

    private fun card(n: Notification, width: Int): Card {
        val parent = inflated.getOrPut(n.contentKey()) {
            val builder = Notification.Builder.recoverBuilder(shadeContext, n).setSmallIcon(smallIcon(n))
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
        val actions = n.actions.orEmpty().mapNotNull { action ->
            val title = action.title?.toString() ?: return@mapNotNull null
            parent.findText(title)?.let { title to it.boundsIn(parent) }
        }.toMap()
        return Card(bmp, actions)
    }

    /**
     * The small icon decoded once and handed to the template as a bitmap: a resource icon is decoded again on every
     * inflation, and Robolectric's native decoder crashes after a few thousand.
     */
    private val icons = mutableMapOf<Int, Icon>()

    private fun smallIcon(n: Notification): Icon = icons.getOrPut(n.smallIcon.resId) {
        val drawable = checkNotNull(n.smallIcon.loadDrawable(app))
        val size = dp(24f).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bmp))
        Icon.createWithBitmap(bmp)
    }

    private fun View.boundsIn(ancestor: View): RectF {
        var x = 0f
        var y = 0f
        var v: View = this
        while (v !== ancestor) {
            x += v.left - v.scrollX + v.translationX
            y += v.top - v.scrollY + v.translationY
            v = v.parent as? View ?: break
        }
        return RectF(x, y, x + width, y + height)
    }

    private fun View.findText(text: String): View? = when {
        this is TextView && this.text?.toString().equals(text, ignoreCase = true) && visibility == View.VISIBLE && width > 0 -> this
        this is ViewGroup && visibility == View.VISIBLE -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findText(text) }
        else -> null
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
        const val CESIUM_NAME = "Cesium Revenue Strategy"
        const val PROJECT = DemoData.PROJECT_ID
        const val PROJECT_NAME = "Cesium billing launch"
        const val STOP = "Stop Spotlight"
        const val DILATION = 6L
        const val SHADE_MS = 380L
        const val SWIPE_MS = 320L
        const val HOLD_MS = 4_500L
        const val CAPTION_BAND_DP = 44f
        const val OUT_W = 720
        const val OUT_W_TABLET = 1280
    }
}

private const val PHONE_DARK = "w411dp-h914dp-night-420dpi"
private const val PHONE_LIGHT = "w411dp-h914dp-notnight-420dpi"
private const val TABLET_DARK = "w1280dp-h800dp-land-night-160dpi"
