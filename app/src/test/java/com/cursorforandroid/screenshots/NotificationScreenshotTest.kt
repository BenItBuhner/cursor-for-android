package com.cursorforandroid.screenshots

import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
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
        // Chronometer cards count `now - when`. Freeze both so the header is 3m 5s on every machine.
        SystemClock.setCurrentTimeMillis(FIXED_NOW)
    }

    @After
    fun restoreLocale() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @Before
    fun channels() = LiveNotifications.ensureChannels(context)

    @Test
    fun liveCards() {
        capture("28_notif_single_collapsed", singleRunning(), expanded = false)
        capture("29_notif_single_expanded", singleRunning(), expanded = true)
        capture("30_notif_roster_expanded", roster(), expanded = true)
        captureShade("31_notif_shade", listOf(singleRunning(), roster()))
    }

    @Test
    @Config(sdk = [36])
    fun progressStyleApi36() {
        capture("32_notif_single_progress_api36", singleRunning(), expanded = true)
    }

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

    private fun roster(): Notification = LiveNotificationRenderer.live(
        context,
        LiveActivityState(
            listOf(
                TrackedRun(
                    agentId = "bc-demo-0001",
                    runId = "run-1",
                    title = "Codex-Poly-Bot Scaling",
                    status = RunStatus.RUNNING,
                    phase = LivePhase.Running,
                    startedAtMillis = FIXED_NOW - 34 * 60_000,
                    digest = RunDigest(activity = RunDigest.Activity("Running", "redis-cli LLEN catchup:queue")),
                ),
                TrackedRun(
                    agentId = "bc-demo-0003",
                    runId = "run-3",
                    title = "Cesium Revenue Strategy",
                    status = RunStatus.RUNNING,
                    phase = LivePhase.Running,
                    startedAtMillis = FIXED_NOW - 12 * 60_000,
                    digest = RunDigest(activity = RunDigest.Activity.Thinking),
                ),
                TrackedRun(
                    agentId = "bc-demo-0010",
                    runId = "run-10",
                    title = "Hyper-realistic human limbs",
                    status = RunStatus.RUNNING,
                    phase = LivePhase.Stopping,
                    startedAtMillis = FIXED_NOW - 6 * 60_000,
                    digest = RunDigest(activity = RunDigest.Activity("Editing", "rig.py")),
                ),
            ),
            hasReconciled = true,
            runningCount = 5,
        ),
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

    private fun captureShade(name: String, notifications: List<Notification>) {
        lateinit var host: View
        compose.activityRule.scenario.onActivity { activity ->
            val density = activity.resources.displayMetrics.density
            val width = (SHADE_WIDTH_DP * density).toInt()
            val gap = (8 * density).toInt()
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(SHADE)
                setPadding(0, gap, 0, gap)
            }
            notifications.forEachIndexed { index, notification ->
                if (index > 0) {
                    column.addView(View(activity), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gap))
                }
                column.addView(inflate(activity, notification, expanded = true, width), LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            column.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            column.layout(0, 0, column.measuredWidth, column.measuredHeight)
            column.layoutParams = ViewGroup.LayoutParams(column.measuredWidth, column.measuredHeight)
            host = column
            activity.setContentView(host, ViewGroup.LayoutParams(column.measuredWidth, column.measuredHeight))
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
        shade.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
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
        host.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
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
        const val SHADE = 0xFF121212.toInt()
    }
}
