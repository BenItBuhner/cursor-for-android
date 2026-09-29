package com.cursorforandroid.ui.navigation

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.CursorApp
import com.cursorforandroid.MainActivity
import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.RunStreamer
import com.cursorforandroid.data.demo.DemoBackendFactory
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.ui.components.FakeIme
import com.cursorforandroid.ui.components.PhysicalKeyboard
import com.cursorforandroid.ui.components.windowView
import com.cursorforandroid.ui.components.withKeyboard
import com.cursorforandroid.ui.quick.QuickComposerActivity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * A hardware keyboard plugged in or pulled out, the display density changing (the window moved to a DeX monitor, the
 * display size setting) and the font scale changing are taken by the running activities in place: nothing is made
 * again, nothing is fetched again, and what is on screen follows the new configuration — the text at its new size and
 * density, and a newline the IME types read as a hardware Enter exactly while a keyboard is attached.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ConfigChangeInPlaceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CursorApp = ApplicationProvider.getApplicationContext()

    /** Every call the demo backend was asked for, by method name, and every run stream opened ("stream"). */
    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private var main: ActivityController<MainActivity>? = null
    private var quick: ActivityController<QuickComposerActivity>? = null

    @Before
    fun countTheBackend() {
        FakeIme.reset()
        PhysicalKeyboard.forget()
        val (api, streamer) = DemoBackendFactory.create()
        val counted = object : RunStreamer {
            override fun stream(agentId: String, runId: String, lastEventId: String?): Flow<RunStreamEvent> {
                calls += "stream"
                return streamer.stream(agentId, runId, lastEventId)
            }
        }
        // The application's graph has no public setter; the test puts one on the counted backend in its place.
        CursorApp::class.java.getDeclaredField("graph").apply { isAccessible = true }
            .set(app, AppGraph(app, demo = CursorBackend(counting(api), counted, isDemo = true)))
        runBlocking { app.graph.session.enterDemo() }
    }

    @After
    fun tearDown() {
        main?.pause()?.stop()?.destroy()
        quick?.pause()?.stop()?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        FakeIme.reset()
        PhysicalKeyboard.forget()
    }

    private fun counting(api: CursorApi): CursorApi =
        Proxy.newProxyInstance(CursorApi::class.java.classLoader, arrayOf(CursorApi::class.java)) { _, method, args ->
            if (method.declaringClass != Any::class.java) calls += method.name
            try {
                method.invoke(api, *args.orEmpty())
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as CursorApi

    /** The app on a finished chat, opened by its link, with everything it fetched on the way in settled and forgotten. */
    private fun launchOnChat() {
        val launched = Robolectric.buildActivity(MainActivity::class.java).setup().also { main = it }
        compose.waitUntil(30_000) { exists(hasText(HOME_PLACEHOLDER, substring = true)) }
        launched.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://cursor.com/agents/$CHAT_ID")).setClass(app, MainActivity::class.java))
        compose.waitUntil(30_000) { exists(TITLE) && exists(REPLY) && exists(COMPOSER) }
        settle()
    }

    /** The configuration changed under the running [controller] by [change]; answers whether the activity was made again. */
    private fun <A : Activity> reconfigure(controller: ActivityController<A>, change: () -> Unit): Boolean = remade(controller) {
        change()
        controller.configurationChange()
    }

    /** A hardware keyboard attached (exposed) or taken away under the running [controller], as its configuration says. */
    private fun <A : Activity> keyboard(controller: ActivityController<A>, attached: Boolean): Boolean = remade(controller) {
        controller.configurationChange(Configuration(app.resources.configuration).withKeyboard(attached))
    }

    /** Whether [controller]'s activity was made again by [apply]'s configuration change, once everything has settled. */
    private fun <A : Activity> remade(controller: ActivityController<A>, apply: () -> Unit): Boolean {
        val before = controller.get()
        apply()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        return controller.get() !== before
    }

    /**
     * Until the backend has been asked for nothing new for a while: what opening a screen fetches is not all asked at
     * once (a catalogue's second worker list follows its first), and none of it may be counted against a change.
     */
    private fun settle() {
        var seen = -1
        var quietSince = System.nanoTime()
        val deadline = quietSince + 20_000_000_000L
        while (System.nanoTime() - quietSince < 1_000_000_000L && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            if (calls.size != seen) {
                seen = calls.size
                quietSince = System.nanoTime()
            }
            Thread.sleep(50)
        }
        calls.clear()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun layoutOf(matcher: SemanticsMatcher): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNode(matcher).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun draft(): String =
        compose.onNode(COMPOSER).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    /** Binds the stand-in IME to the focused composer, as the system does again once the field's input restarts. */
    private fun bindIme() {
        val view = compose.onNode(COMPOSER).windowView
        compose.runOnIdle { FakeIme.bind(view, FakeIme.Style.PassesKeysOn) }
    }

    private fun tapImeEnter() {
        compose.runOnIdle { FakeIme.tapEnter() }
        compose.waitForIdle()
    }

    @Test
    fun `a hardware keyboard plugged in is taken in place, and from then the IME's newline is its Enter`() {
        launchOnChat()
        compose.onNode(COMPOSER).performTextInput("ship it")
        bindIme()
        tapImeEnter()
        assertThat(draft()).isEqualTo("ship it\n")
        calls.clear()

        assertThat(keyboard(main!!, attached = true)).isFalse()
        assertThat(calls).isEmpty()

        bindIme()
        tapImeEnter()
        assertThat(draft()).isEmpty()
        compose.waitUntil(10_000) { "createRun" in calls }
    }

    @Test
    fun `a hardware keyboard pulled out is taken in place, and the IME's newline is a newline again`() {
        launchOnChat()
        assertThat(keyboard(main!!, attached = true)).isFalse()
        assertThat(keyboard(main!!, attached = false)).isFalse()
        assertThat(calls).isEmpty()

        compose.onNode(COMPOSER).performTextInput("ship it")
        bindIme()
        tapImeEnter()
        assertThat(draft()).isEqualTo("ship it\n")
        assertThat(calls).isEmpty()
    }

    @Test
    fun `a density change is taken in place and the chat is laid out at the new density`() {
        launchOnChat()
        val before = layoutOf(REPLY)
        assertThat(before.layoutInput.density.density).isEqualTo(1f)

        assertThat(reconfigure(main!!) { RuntimeEnvironment.setQualifiers("+hdpi") }).isFalse()
        assertThat(calls).isEmpty()

        val after = layoutOf(REPLY)
        assertThat(after.layoutInput.density.density).isEqualTo(1.5f)
        assertThat(after.size.width.toFloat()).isWithin(2f).of(before.size.width * 1.5f)
        assertThat(after.size.height.toFloat()).isWithin(3f).of(before.size.height * 1.5f)
    }

    @Test
    fun `a font scale change is taken in place and the text grows with it`() {
        launchOnChat()
        val before = layoutOf(REPLY)
        assertThat(before.layoutInput.density.fontScale).isEqualTo(1f)

        assertThat(reconfigure(main!!) { RuntimeEnvironment.setFontScale(1.3f) }).isFalse()
        assertThat(calls).isEmpty()

        val after = layoutOf(REPLY)
        assertThat(after.layoutInput.density.fontScale).isEqualTo(1.3f)
        assertThat(after.size.width).isEqualTo(before.size.width)
        assertThat(after.size.height.toFloat()).isGreaterThan(before.size.height * 1.2f)
    }

    @Test
    fun `the composer over the launcher takes a keyboard, a density and a font scale change in place too`() {
        val launched = Robolectric.buildActivity(QuickComposerActivity::class.java).setup().also { quick = it }
        compose.waitUntil(30_000) { exists(hasSetTextAction()) }
        settle()

        assertThat(keyboard(launched, attached = true)).isFalse()
        assertThat(reconfigure(launched) { RuntimeEnvironment.setQualifiers("+hdpi") }).isFalse()
        assertThat(reconfigure(launched) { RuntimeEnvironment.setFontScale(1.3f) }).isFalse()
        assertThat(calls).isEmpty()
    }

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val CHAT_ID = "bc-demo-0017"
        val TITLE = hasText("Weekly dependency bump") and hasAnyAncestor(hasTestTag("chat-header"))
        val REPLY = hasText("Bumped AGP, Kotlin and the Compose BOM; build and tests green.")
        val COMPOSER = hasSetTextAction() and hasAnyAncestor(hasTestTag("follow-up-composer"))
    }
}

/** A phone at mdpi, so that a pixel is a dp before the density changes. */
private const val PHONE = "w411dp-h914dp-port-night-mdpi"
