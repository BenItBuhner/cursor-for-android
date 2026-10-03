package com.cursorforandroid.promo

import android.Manifest
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.CursorApp
import com.cursorforandroid.DeferredStartup
import com.cursorforandroid.MainActivity
import com.cursorforandroid.R
import com.cursorforandroid.data.demo.DemoStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.ExtendedMode
import com.cursorforandroid.data.repo.ReviewRepository
import com.cursorforandroid.data.repo.RunMonitor
import com.cursorforandroid.data.repo.SessionManager
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.notifications.LiveNotificationRenderer
import com.cursorforandroid.notifications.LiveNotificationRenderer.LiveLook
import com.cursorforandroid.ui.conversation.QueueGlyphs
import com.cursorforandroid.ui.home.NewChatHomeTags
import com.cursorforandroid.ui.home.ProjectShortcutCopy
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
import kotlin.math.roundToInt

/** The [Screen]s as `@Config` needs them, as constants; [stage] checks they still agree. */
private const val PHONE = "w411dp-h923dp-port-night-420dpi"
private const val FOLDABLE = "w791dp-h820dp-port-night-420dpi"
private const val TABLET = "w1280dp-h800dp-land-night-320dpi"
private const val PHONE_LIGHT = "w411dp-h923dp-port-notnight-420dpi"
private const val FOLDABLE_LIGHT = "w791dp-h820dp-port-notnight-420dpi"
private const val TABLET_LIGHT = "w1280dp-h800dp-land-notnight-320dpi"

/**
 * The launch video's footage: the app itself, signed in to the capture's account with its scripted backend behind
 * it, driven a frame at a time by a [Director] and filmed to `promo/capture/out`. Each device's take is one
 * continuous take of the same run (see [Take]), each step at the same moment of the capture's clock, so the video
 * can cut between the devices and set them side by side at any moment of it.
 *
 * Run through `promo/capture/run.sh <test>`: the harness is not part of the app's build.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE, shadows = [PromoMediaRecorder::class])
class LaunchVideoCapture {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CursorApp = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<MainActivity>? = null
    private var sink: FrameSink? = null
    private var director: Director? = null
    private lateinit var graph: AppGraph
    private lateinit var streamer: PromoRunStreamer
    private var posted: Pair<LiveLook, Notification>? = null

    private val clock = AppClock.nowMillis
    private val settle = DeferredStartup.settleMs
    private val zone = TimeZone.getDefault()
    private val locale = Locale.getDefault()

    @Before
    fun stage() {
        check(
            Screen.Phone.qualifiers == PHONE && Screen.Foldable.qualifiers == FOLDABLE && Screen.Tablet.qualifiers == TABLET &&
                Screen.Phone.light.qualifiers == PHONE_LIGHT && Screen.Foldable.light.qualifiers == FOLDABLE_LIGHT && Screen.Tablet.light.qualifiers == TABLET_LIGHT,
        ) { "The @Config qualifiers no longer match the screens" }
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
        runCatching { if (::graph.isInitialized) graph.runMonitor.stop() }
        runCatching { controller?.pause()?.stop()?.destroy() }
        shadowOf(Looper.getMainLooper()).idle()
        AppClock.nowMillis = clock
        DeferredStartup.settleMs = settle
        TimeZone.setDefault(zone)
        Locale.setDefault(locale)
    }

    /**
     * The app launched on [screen] as someone who has been using it for a while, in Extended mode: their week of
     * chats on the Cesium repository, two of them pinned, their Projects on the New Chat page, and the repository and
     * model chosen in the composer; nothing new to read, the microphone and notifications allowed. The account is
     * the demo's, which the app runs as it runs any other, under the capture's name for its owner rather than the
     * demo's. The live notification's run monitor follows what runs, as the notification's service would.
     */
    private fun launch(screen: Screen, name: String): Director {
        val store = DemoStore(PromoSeeds.seeds)
        val script = PromoScript.load()
        val steering = PromoSteering(store)
        val review = PromoReview(script)
        streamer = PromoRunStreamer(store, script, steering, review)
        val backend = CursorBackend(PromoCursorApi(store), streamer, isDemo = true)
        graph = AppGraph(
            app,
            SecureKeyStore(app) { app.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) },
            demo = backend,
            transcriptionApi = PromoTranscription(HERO_PROMPT),
        )
        CursorApp::class.java.getDeclaredField("graph").apply { isAccessible = true }.set(app, graph)
        ReviewRepository::class.java.getDeclaredField("demo").apply { isAccessible = true }.set(graph.reviews, review)
        extend(graph)
        PromoSeeds.install(graph)
        steering.install(graph.followUps)
        steering.queueRead = { agentId -> graph.conversations.noteAccountQueue(agentId, emptyList()) }
        steering.placed = { agentId -> graph.followUps.state(agentId).value.queue.isEmpty() }
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        runBlocking {
            graph.prefs.setComposerDefaults(repoUrl = CESIUM_REPO, ref = "main", modelId = "composer-2.5", params = mapOf("fast" to "true"))
            graph.prefs.setWhatsNewReadVersion(graph.appVersion)
            graph.prefs.setNotificationPermissionAsked()
            graph.prefs.setProjectOrder(PromoSeeds.projectOrder)
            // Before the demo is entered, which pins its own showcase chats only while nothing is pinned.
            graph.prefs.pinIfNonePinned(PromoSeeds.pinned)
            graph.session.enterDemo()
            // Every seeded chat and Project already read, so no Project on the New Chat page wears an unread dot.
            graph.prefs.markAllRead(PromoSeeds.seeds.associate { it.id to AppClock.now() })
            // The same demo backend under it, which is what the app asks about; only the account's owner is someone else.
            @Suppress("UNCHECKED_CAST")
            val session = SessionManager::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(graph.session) as MutableStateFlow<SessionState>
            session.value = SessionState.SignedIn(USER, isDemo = false)
            graph.onboarding.load()
        }
        val launched = Robolectric.buildActivity(MainActivity::class.java).setup().also { controller = it }
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(HOME_PLACEHOLDER, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        // Its periodic refresh runs on the wall clock, which a take spends minutes of; nothing new would come of it.
        RunMonitor::class.java.getDeclaredField("refreshIntervalMs").apply { isAccessible = true }.setLong(graph.runMonitor, Long.MAX_VALUE)
        graph.runMonitor.start()
        val frames = FrameSink(File(Promo.out, name), Promo.preview).also { sink = it }
        return Director(compose, launched, frames, screen).also {
            director = it
            it.extra = { live() }
        }
    }

    /**
     * Extended mode, as far as the screens go: the Projects on the New Chat page and dictation in the composer. What
     * the mode lets the app do on the account stays as the setting has it, off (see [ExtendedMode.capabilities]), so
     * nothing reaches for the account service the demo does not have.
     */
    private fun extend(graph: AppGraph) {
        ExtendedMode::class.java.getDeclaredField("enabled").apply { isAccessible = true }.set(graph.extendedMode, flowOf(true))
        AppGraph::class.java.getDeclaredField("voiceInput").apply { isAccessible = true }.set(graph, flowOf(true))
    }

    /**
     * The live notification as the app would post it this frame, for the video to show in the shade: what
     * [LiveNotificationRenderer] builds for the run monitor's state, with its chronometer's reading.
     */
    private fun JsonObjectBuilder.live() {
        val look = LiveNotificationRenderer.look(app, graph.runMonitor.state.value)
        if (look == LiveLook.Connecting) return
        val notification = posted?.takeIf { it.first == look }?.second ?: LiveNotificationRenderer.live(app, look).also { posted = look to it }
        val extras = notification.extras
        putJsonObject("live") {
            put("app", app.getString(R.string.app_name))
            put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            put("text", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            put("sub", extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
            if (extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) put("elapsedMs", VirtualTime.wallMillis() - notification.`when`)
            put("indeterminate", extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
            putJsonArray("actions") { notification.actions.orEmpty().forEach { add(it.title.toString()) } }
        }
    }

    private fun finish(name: String) {
        director?.endSegment()
        PacingLog.write(File(Promo.out, "$name/pacing.json"))
        PacingLog.check()
    }

    private fun film(screen: Screen, name: String, scale: Float) {
        val d = launch(screen, name)
        d.segment(name, scale)
        Take(d, name).play()
        finish(name)
    }

    /** The phone's take at half size, with the screen's semantics and a still at each step, going on past a step that finds nothing to do. */
    @Test
    fun probe() {
        val d = launch(Screen.Phone, "probe")
        d.segment("probe", 0.5f)
        Take(d, "probe", probing = true).play()
        finish("probe")
    }

    /** [probe] with the system in its light theme. */
    @Test
    @Config(qualifiers = PHONE_LIGHT)
    fun probeLight() {
        val d = launch(Screen.Phone.light, "probe-light")
        d.segment("probe-light", 0.5f)
        Take(d, "probe-light", probing = true).play()
        finish("probe-light")
    }

    /** Pixel 9: the video's spine. */
    @Test
    fun phone() = film(Screen.Phone, "phone", 1f)

    /** Pixel 9 Pro Fold, open: the chat with its details pinned beside it. */
    @Test
    @Config(qualifiers = FOLDABLE)
    fun foldable() = film(Screen.Foldable, "foldable", 0.75f)

    /** Pixel Tablet on its side: the sidebar, the chat and its details. */
    @Test
    @Config(qualifiers = TABLET)
    fun tablet() = film(Screen.Tablet, "tablet", 0.75f)

    @Test
    @Config(qualifiers = PHONE_LIGHT)
    fun phoneLight() = film(Screen.Phone.light, "phone-light", 1f)

    @Test
    @Config(qualifiers = FOLDABLE_LIGHT)
    fun foldableLight() = film(Screen.Foldable.light, "foldable-light", 0.75f)

    @Test
    @Config(qualifiers = TABLET_LIGHT)
    fun tabletLight() = film(Screen.Tablet.light, "tablet-light", 0.75f)

    /**
     * One take of the run on [d]'s screen, each step at the same moment of the capture's clock on every device: the
     * last of the Projects on the New Chat page held until it lifts and carried to the front; the task said into the
     * composer's microphone, transcribed into it and sent; the first edit opened while it is written, its diff landing
     * in view, and closed again, and the transcript taken back to its end to follow the run again; a follow-up typed
     * while the agent works, queued behind the turn and steered into it; and once the run has opened its pull request
     * and answered, the pull request's section of the details. A wide window pins the details beside the chat as soon
     * as it opens, where the edits come in as they are made; a phone opens them at the end. With [probing], every step
     * is looked at (semantics, a still and the moment it came, in the log), and one that finds nothing to do is noted
     * and passed over.
     */
    private inner class Take(private val d: Director, private val name: String, private val probing: Boolean = false) {
        private var began = 0L
        private var sent = 0L
        private val wide = d.screen.widthDp >= WIDE_DP

        fun play() {
            try {
                began = VirtualTime.nowMs
                before(HOLD_AT)
                look("home")
                arrange()
                look("arranged")

                before(MIC_AT)
                tap("mic", MAIN)
                before(STOP_AT)
                look("listening")
                tap("stop", MAIN)
                look("transcribing")
                until("the words", 4.0) { fieldText(hasSetTextAction()) == HERO_PROMPT }
                look("dictated")
                before(SEND_AT)
                tap("send", MAIN)
                sent = VirtualTime.nowMs
                if (probing) println("promo: sent at ${sent - began}ms into the take")
                until("the chat", 10.0) { d.exists(hasTestTag("chat-header")) }
                if (wide) {
                    at(PANEL_AT)
                    tap("panel", hasContentDescription("Open panel"))
                }

                // The first edit opened while it is written, so its diff lands where it is being watched, and closed
                // again before the next edit makes the two a stretch.
                until("the edit", 4.0) { d.exists(editing("theme.css"), unmerged = true) }
                look("editing")
                d.hold(0.2)
                tap("edit", editing("theme.css"), unmerged = true)
                look("opened")
                until("the diff", 4.0) { d.exists(editLine("theme.css"), unmerged = true) }
                look("diff")
                at(FOLD_AT)
                tap("fold", editLine("theme.css"), unmerged = true)
                look("folded")
                // Opening the edit left the transcript where it was read; back to its end, it follows again.
                at(JUMP_AT)
                tap("jump", LATEST)
                look("following")
                until("the stretch", 3.0) { d.exists(WORKING) }
                look("working")

                at(FOLLOW_UP_AT)
                val followUp = hasSetTextAction() and hasAnyAncestor(hasTestTag("follow-up-composer"))
                tap("follow-up", followUp)
                d.hold(0.25)
                d.type(d.node(followUp), STEER_PROMPT)
                d.hold(0.25)
                tap("queue", MAIN)
                at(STEER_AT)
                look("queued")
                steer()
                until("the steer", 3.0) { d.exists(hasText(STEERED, substring = true)) }
                look("steered")
                if (probing && d.exists(LATEST)) println("promo: the transcript is not following at +${elapsed()}ms")
                until("the steer's message", 6.0) { d.exists(said(STEER_PROMPT)) && graph.followUps.state(heroAgent()).value.queue.isEmpty() }
                look("filed")
                until("the change of plan", 6.0) { d.exists(said(ADAPTED)) }
                look("adapted")

                until("the answer", 20.0) { d.exists(said(OPENED)) }
                look("answer")
                at(SHIP_AT)
                if (!wide) {
                    tap("details", hasContentDescription("Open panel"))
                    at(SHIP_AT + 700)
                    look("details")
                }
                at(PULL_REQUEST_AT)
                tap("pull request", hasText("Pull request") and hasAnyAncestor(hasTestTag("conversation-panel")))
                at(PULL_REQUEST_AT + 900)
                look("pull-request")
                at(END_AT)
                look("end")
            } catch (_: EnoughFrames) {
                d.still("$name-last", 0.5f)
            }
        }

        /**
         * The Project that is last on the page lifted and carried to the front: pressed and held through the menu the
         * hold opens, until the shortcut lifts under the finger; carried over the others, which make room, to the
         * first slot at a hand's pace; and set down there.
         */
        private fun arrange() {
            val moved = SHORTCUT and hasText(PromoSeeds.MOVED_PROJECT)
            if (!d.exists(moved)) {
                check(probing) { "No ${PromoSeeds.MOVED_PROJECT} shortcut to move" }
                println("promo: no ${PromoSeeds.MOVED_PROJECT} shortcut at ${elapsedInTake()}ms")
                d.dump("$name-missing-shortcut")
                return
            }
            val first = compose.onAllNodes(SHORTCUT).fetchSemanticsNodes().minWith(compareBy<SemanticsNode>({ it.positionOnScreen.y }, { it.positionOnScreen.x }))
            val from = d.centerOf(d.node(moved))
            val to = first.positionOnScreen.let { at -> androidx.compose.ui.geometry.Offset(at.x + first.size.width / 2f, at.y + first.size.height / 2f) }
            d.press(isRoot() and hasAnyDescendant(SHORTCUT), from.x, from.y, label = "hold")
            until("the menu", 2.0) { d.exists(hasText("Copy link")) }
            look("menu")
            until("the lift", 2.0) { d.exists(ARRANGING) }
            if (probing) println("promo: lifted at ${elapsedInTake()}ms")
            d.frames(LIFT_FRAMES)
            look("lifted")
            for (i in 1..CARRY_FRAMES) {
                val t = smooth(i / CARRY_FRAMES.toFloat())
                d.moveTo(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
                d.frame()
            }
            d.frames(DROP_FRAMES)
            d.lift()
            d.mark("dropped")
            if (probing) println("promo: dropped at ${elapsedInTake()}ms")
        }

        private fun elapsed() = VirtualTime.nowMs - sent

        private fun elapsedInTake() = VirtualTime.nowMs - began

        private fun heroAgent(): String = checkNotNull(streamer.heroAgent) { "The run has not started" }

        private fun look(step: String) {
            if (!probing) return
            val (uptime, realtime) = d.clockDrift()
            println("promo: $step at ${elapsedInTake()}ms into the take" + (if (sent > 0) " (+${elapsed()}ms)" else "") + ", the looper ${uptime}ms and realtime ${realtime}ms ahead")
            d.dump("$name-$step")
            d.still("$name-$step", 0.5f)
        }

        /** Films until [ms] into the take, before the send; a probe that is already past it says by how much. */
        private fun before(ms: Long) {
            if (probing && elapsedInTake() > ms) {
                println("promo: already ${elapsedInTake() - ms}ms past ${ms}ms into the take")
                return
            }
            d.at(began + ms)
        }

        /** Films until [ms] after the send; a probe that is already past it says by how much. */
        private fun at(ms: Long) {
            if (probing && elapsed() > ms) {
                println("promo: already ${elapsed() - ms}ms past +${ms}ms")
                return
            }
            d.at(sent + ms)
        }

        private fun until(what: String, seconds: Double, condition: () -> Boolean) {
            try {
                d.until(what, seconds, condition)
                d.mark(what)
                if (probing) println("promo: $what at ${elapsedInTake()}ms into the take" + if (sent > 0) " (+${elapsed()}ms)" else "")
            } catch (e: IllegalStateException) {
                if (!probing) throw e
                println("promo: never saw $what")
            }
        }

        private fun tap(what: String, matcher: SemanticsMatcher, unmerged: Boolean = false) {
            if (!d.exists(matcher, unmerged)) {
                check(probing) { "Nothing to tap for $what" }
                println("promo: nothing to tap for $what at ${elapsedInTake()}ms")
                d.dump("$name-missing-${what.replace(' ', '-')}")
                return
            }
            d.tap(d.node(matcher, unmerged), label = what)
        }

        private fun fieldText(matcher: SemanticsMatcher): String? {
            if (!d.exists(matcher)) return null
            return d.node(matcher).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
        }

        /**
         * The queued card's arrow pressed, and the steer it asks for asked for: the demo has no account to steer
         * through, so the app does not offer one itself (see [PromoSteering]). Off the test's thread, which films the
         * frames the steer's steps wait on.
         */
        private fun steer() {
            val arrow = hasContentDescription(QueueGlyphs.STEER)
            if (!d.exists(arrow)) {
                check(probing) { "No queued card to steer" }
                println("promo: no queued card to steer at +${elapsed()}ms")
                d.dump("$name-missing-steer")
                return
            }
            d.touch(d.node(arrow), label = "steer")
            val agentId = heroAgent()
            val followUps = graph.followUps
            val queued = followUps.state(agentId).value.queue.first()
            CoroutineScope(Dispatchers.IO).launch { followUps.steerNow(agentId, queued.id) }
        }
    }

    /**
     * The compositor against Robolectric's own PixelCopy of the window, which renders it the same way at full size,
     * and its byte order through to a PNG: a red fill has to come back red.
     */
    @Test
    fun renderCheck() {
        val d = launch(Screen.Phone, "render-check")
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

    /** An edit's line, by its verb: the file's name on it opens the file rather than the diff. */
    private fun editLine(file: String, verb: String = "Edited") = hasText(verb) and hasAnyAncestor(hasClickAction() and hasAnyDescendant(hasText(file)))

    /** An edit's line while the edit is being written. */
    private fun editing(file: String) = editLine(file, "Editing")

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val HERO_PROMPT = "Add dark mode to the dashboard"
        const val STEER_PROMPT = "Follow the system theme too"
        const val STEERED = "Steered"
        const val ADAPTED = "Got it."
        const val OPENED = "Opened"

        /** Where the shell lays a window out wide, with the sidebar and the details beside the chat (ShellWindow). */
        const val WIDE_DP = 600

        val USER = CursorUser(apiKeyName = "Android", email = "alex@example.com", firstName = "Alex", lastName = "Rivera", userId = null)

        /** The composer's main disc: the microphone while the composer is empty, the stop while it records, then the send. */
        val MAIN = hasTestTag("composer-main")
        val SHORTCUT = hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT)
        val ARRANGING = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, ProjectShortcutCopy.ARRANGING)

        /** The stretch the run is working in, rather than the home list's or the panel's "Working". */
        val WORKING = hasText("Working") and hasAnyAncestor(hasTestTag("stretch"))
        val LATEST = hasContentDescription("Scroll to latest")

        /** Frames the lifted shortcut is held still before it is carried, carried for, and held on its new slot before it is let go. */
        const val LIFT_FRAMES = 8
        const val CARRY_FRAMES = 40
        const val DROP_FRAMES = 5

        // The steps before the send, in ms into the take: the same on every device, as the video cuts between them.
        const val HOLD_AT = 900L
        const val MIC_AT = 3_500L
        const val STOP_AT = 6_100L
        const val SEND_AT = 7_300L

        // Each step's moment after it, in ms after the send: the run is scripted to the millisecond from there, so
        // these are the same on every device, and every take shows the same thing at the same moment.
        const val PANEL_AT = 900L
        /**
         * Almost two seconds after the first diff lands, leaving its collapse time to finish before the next edit
         * starts (see [PromoRunStreamer]'s hero), which would otherwise cut it off.
         */
        const val FOLD_AT = 4_400L
        const val JUMP_AT = 5_000L
        const val FOLLOW_UP_AT = 5_900L
        const val STEER_AT = 9_000L
        const val SHIP_AT = 22_400L
        const val PULL_REQUEST_AT = 23_300L
        const val END_AT = 25_800L

        /** A hand's move from rest to rest: the minimum-jerk curve, 0 to 1. */
        fun smooth(t: Float): Float = t * t * t * (10 + t * (-15 + 6 * t))
    }
}
