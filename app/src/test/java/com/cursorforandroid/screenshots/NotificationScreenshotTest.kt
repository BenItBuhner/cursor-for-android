package com.cursorforandroid.screenshots

import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.LiveActivityState
import com.cursorforandroid.domain.LivePhase
import com.cursorforandroid.domain.RunDigest
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TrackedRun
import com.cursorforandroid.notifications.LiveNotificationRenderer
import com.cursorforandroid.notifications.LiveNotifications
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * Inflates the live notifications through Android's own templates (`Notification.Builder.recoverBuilder` →
 * `createContentView` / `createBigContentView`) and writes the RemoteViews the shade would apply. This is the
 * closest the walkthrough can get to SystemUI without a device: same layouts, no hand-drawn stand-in.
 *
 * There is one live notification however many agents run: the conversation card for one, the `BigTextStyle` roster
 * (a spanned row per agent) for several. Both are Android's templates, so both inflate here.
 *
 * Run with `./gradlew :app:recordRoborazziDebug --tests '*NotificationScreenshotTest*'`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class NotificationScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun pinClock() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        // Chronometer cards count `now - when`, roster rows render `now - startedAt`. Freeze both so the header is
        // 3m 5s and the rows read 34m on every machine.
        SystemClock.setCurrentTimeMillis(FIXED_NOW)
        AppClock.nowMillis = { FIXED_NOW }
    }

    @After
    fun restoreClocks() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        AppClock.nowMillis = System::currentTimeMillis
    }

    @Before
    fun channels() = LiveNotifications.ensureChannels(context)

    @Test
    fun liveCards() {
        capture("28_notif_single_collapsed", singleRunning(), expanded = false)
        capture("29_notif_single_expanded", singleRunning(), expanded = true)
        capture("30_notif_roster_collapsed", roster(), expanded = false)
        capture("31_notif_roster_expanded", roster(), expanded = true)
    }

    @Test
    @Config(sdk = [36])
    fun progressStyleApi36() {
        capture("32_notif_single_progress_api36", singleRunning().promoted(), expanded = true)
        capture("33_notif_roster_api36", roster().promoted(), expanded = true)
    }

    /**
     * Marks the notification the way the system does once it accepts it as a Live Update. Robolectric has no
     * promotion pipeline, so without this Android 16's template renders the demoted fallback (all styling stripped)
     * rather than what the shade shows for a promoted card (bold kept, colour spans stripped).
     */
    private fun Notification.promoted(): Notification = apply { flags = flags or Notification.FLAG_PROMOTED_ONGOING }

    private fun singleRunning(): Notification = LiveNotificationRenderer.live(
        context,
        LiveActivityState(
            listOf(
                TrackedRun(
                    agentId = "bc-1",
                    runId = "run-bc-1",
                    title = "Update quick action pills interaction and styling",
                    status = RunStatus.RUNNING,
                    phase = LivePhase.Running,
                    startedAtMillis = FIXED_NOW - 185_000,
                    digest = RunDigest(filesEdited = 1, activity = RunDigest.Activity("Editing", "Composer.kt")),
                ),
            ),
            hasReconciled = true,
        ),
    )

    /** Eleven agents running, the eight the monitor tracks with a row each — the load the roster card is built for. */
    private fun roster(): Notification = LiveNotificationRenderer.live(
        context,
        LiveActivityState(
            listOf(
                tracked("bc-demo-0010", "Hyper-realistic human limbs", 6, RunDigest.Activity("Editing", "rig.py"), LivePhase.Stopping),
                tracked("bc-demo-0001", "Codex-Poly-Bot Scaling", 34, RunDigest.Activity("Running", "redis-cli LLEN catchup:queue")),
                tracked("bc-demo-0003", "Cesium Revenue Strategy", 12, RunDigest.Activity.Thinking),
                tracked("bc-demo-0004", "Cli exploration", 19, RunDigest.Activity("Reading", "cli/main.rs")),
                tracked("bc-demo-0005", "House environment overhaul", 30, RunDigest.Activity.Writing),
                tracked("bc-demo-0006", "Market replay engine", 52, RunDigest.Activity("Delegating to", "3 subagents")),
                tracked("bc-demo-0011", "Fruit fly brain environment", 3, RunDigest.Activity.Starting, LivePhase.Starting),
                tracked("bc-demo-0012", "Android mobile experience", 41, RunDigest.Activity("Editing", "ChatScreen.kt")),
            ),
            hasReconciled = true,
            runningCount = 11,
        ),
    )

    private fun tracked(id: String, title: String, minutesAgo: Int, activity: RunDigest.Activity, phase: LivePhase = LivePhase.Running) = TrackedRun(
        agentId = id,
        runId = "run-$id",
        title = title,
        status = RunStatus.RUNNING,
        phase = phase,
        startedAtMillis = FIXED_NOW - minutesAgo * 60_000L,
        digest = RunDigest(activity = activity),
    )

    private fun capture(name: String, notification: Notification, expanded: Boolean) {
        lateinit var host: View
        compose.activityRule.scenario.onActivity { activity ->
            val density = activity.resources.displayMetrics.density
            val width = (SHADE_WIDTH_DP * density).toInt()
            val card = inflate(activity, notification, expanded, width)
            host = padOnShade(activity, card, width)
            activity.setContentView(host, ViewGroup.LayoutParams(host.layoutParams.width, host.layoutParams.height))
        }
        compose.waitForIdle()
        host.captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun padOnShade(activity: ComponentActivity, card: View, width: Int): View {
        val density = activity.resources.displayMetrics.density
        val pad = (8 * density).toInt()
        val shade = FrameLayout(activity).apply { setBackgroundColor(SHADE) }
        shade.setPadding(0, pad, 0, pad)
        shade.addView(card, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        val maxHeight = (MAX_NOTIFICATION_HEIGHT_DP * density).toInt() + 2 * pad
        shade.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
        shade.layout(0, 0, shade.measuredWidth, shade.measuredHeight)
        shade.layoutParams = ViewGroup.LayoutParams(shade.measuredWidth, shade.measuredHeight)
        return shade
    }

    private fun inflate(activity: ComponentActivity, notification: Notification, expanded: Boolean, width: Int): View {
        val remote = remoteViews(activity, notification, expanded)
        val host = FrameLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
        }
        val child = remote.apply(activity, host)
            ?: error("RemoteViews.apply returned null (sdk=${Build.VERSION.SDK_INT}, expanded=$expanded)")
        host.addView(child, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        // SystemUI measures a notification against its maximum height, and the big-text body sizes its line count
        // from that budget — an unbounded spec reads as zero lines. Same budget here.
        val maxHeight = (MAX_NOTIFICATION_HEIGHT_DP * activity.resources.displayMetrics.density).toInt()
        host.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
        host.layout(0, 0, host.measuredWidth, host.measuredHeight)
        check(host.measuredHeight > 0) {
            "Android produced an empty notification view (sdk=${Build.VERSION.SDK_INT}, expanded=$expanded, " +
                "style=${notification.extras.getString(Notification.EXTRA_TEMPLATE)})"
        }
        return host
    }

    private fun remoteViews(context: Context, notification: Notification, expanded: Boolean): android.widget.RemoteViews {
        notification.contentView?.takeIf { !expanded }?.let { return it }
        notification.bigContentView?.takeIf { expanded }?.let { return it }
        val recovered = Notification.Builder.recoverBuilder(context, notification)
        val generated = if (expanded) {
            recovered.createBigContentView() ?: recovered.createContentView()
        } else {
            recovered.createContentView() ?: recovered.createBigContentView()
        }
        return generated
            ?: error("Notification.Builder produced no RemoteViews (sdk=${Build.VERSION.SDK_INT}, expanded=$expanded)")
    }

    private companion object {
        /** Wednesday 2025-01-15 14:00 UTC, as in [AppScreenshotTest]. */
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
        const val SHADE_WIDTH_DP = 379
        /** `notification_max_height` (SystemUI) for an expanded notification's body. */
        const val MAX_NOTIFICATION_HEIGHT_DP = 400
        const val SHADE = 0xFF121212.toInt()
    }
}
