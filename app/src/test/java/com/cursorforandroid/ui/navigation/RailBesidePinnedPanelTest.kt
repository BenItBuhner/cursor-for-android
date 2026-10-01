package com.cursorforandroid.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.ui.panel.ChatMinWidth
import com.cursorforandroid.ui.panel.PaneWidthClass
import com.cursorforandroid.ui.theme.CursorTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The rail beside a panel the device kept pinned open, on a Fold's inner screen at 2x, with a chat on top as what the
 * device kept is read: the read lands off the main thread and is heard there, as a test's coroutines have it where they
 * resume, and however the chat's pane is laid out meanwhile the rail takes the room the panel leaves it, the chat
 * keeping its narrowest width between them.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w840dp-h700dp-night-xhdpi")
class RailBesidePinnedPanelTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Read by the chat's pane as it is laid out, so a write to it lays that pane out again and nothing else. */
    private var detailPass by mutableIntStateOf(0)

    @Volatile
    private var loader: Thread? = null

    private val heard = CountDownLatch(1)

    private val release = CountDownLatch(1)

    /**
     * Registered ahead of the window's own, so what [loader] applies waits here, before the window hears of it, until
     * the chat's pane has been laid out again on the main thread.
     */
    private val hold: ObserverHandle = Snapshot.registerApplyObserver { _, _ ->
        if (Thread.currentThread() === loader && heard.count > 0) {
            heard.countDown()
            release.await(RELEASE_WAIT_SECONDS, TimeUnit.SECONDS)
        }
    }

    @After
    fun releaseHold() {
        release.countDown()
        hold.dispose()
    }

    private fun widthOf(tag: String) = with(compose.density) { compose.onNodeWithTag(tag).fetchSemanticsNode().size.width.toDp() }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `the rail makes way for the panel the device kept open, heard off the main thread as the chat's pane is laid out`() {
        val prefs = PreferencesStore(compose.activity)
        runBlocking {
            prefs.setPanelOpen(PaneWidthClass.Expanded, true)
            prefs.setPanelWidthFraction(280f / 840f)
        }
        lateinit var panes: ShellPanes
        lateinit var root: ViewRootForTest
        compose.setContent {
            val scope = rememberCoroutineScope()
            panes = remember { ShellPanes(prefs, scope, railExpanded = { true }, chatOnTop = { true }) }
            root = LocalView.current as ViewRootForTest
            CursorTheme {
                WidePanes(
                    panes = panes,
                    railShown = true,
                    railSlides = false,
                    pinnable = true,
                    rail = { Box(Modifier.fillMaxSize().testTag(RAIL)) },
                    detail = { modifier ->
                        Box(
                            modifier
                                .testTag(DETAIL)
                                .layout { measurable, constraints ->
                                    detailPass
                                    panes.width
                                    val placeable = measurable.measure(constraints)
                                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                                },
                        )
                    },
                )
            }
        }
        compose.waitForIdle()
        assertThat(widthOf(RAIL)).isEqualTo(278.dp)

        val loading = thread(start = false) { Snapshot.withMutableSnapshot { runBlocking { panes.load() } } }
        loader = loading
        loading.start()
        compose.waitUntil(10_000) { heard.count == 0L }
        compose.runOnUiThread {
            detailPass++
            Snapshot.sendApplyNotifications()
            root.measureAndLayoutForTest()
        }
        release.countDown()
        loading.join()
        compose.waitForIdle()

        assertThat(panes.widths.panelShown).isTrue()
        assertThat(widthOf(RAIL)).isEqualTo(panes.widths.rail)
        assertThat(widthOf(DETAIL) - panes.width).isAtLeast(ChatMinWidth)
    }

    private companion object {
        const val RAIL = "rail"
        const val DETAIL = "detail"
        const val RELEASE_WAIT_SECONDS = 10L
    }
}
