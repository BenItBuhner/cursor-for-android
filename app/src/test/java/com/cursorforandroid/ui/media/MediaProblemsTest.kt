package com.cursorforandroid.ui.media

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a figure that cannot be drawn says, inline and in the viewer: the problem in the reader's words — never the
 * decoder's "…not encoded as a valid image format" — and the way on, here the page the link really is.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class MediaProblemsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "text/html").setBody("<!DOCTYPE html><html><head><title>shot.png · GitHub</title></head></html>")
        }
    }
    private val state = MediaViewerState(null)
    private val opened = mutableListOf<String>()
    private val uris = object : UriHandler {
        override fun openUri(uri: String) {
            opened += uri
        }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun settle(condition: () -> Boolean) = compose.waitUntil(30_000) {
        compose.waitForIdle()
        condition()
    }

    @Test
    fun `a web page behind an image link is named inline and in the viewer, with the page a tap away`() {
        val page = server.url("/acme/app/blob/main/shot.png").toString()
        val good = ViewerFixtures.png("good.png", 200, 120, 0xFF334455.toInt())
        val entries = listOf(MediaEntry(good, MediaEntry.Kind.Image, "Good"), MediaEntry(page, MediaEntry.Kind.Image, "Shot"))
        val loader = ViewerFixtures.loader()
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uris) { ViewerScene(state, loader, entries) }
        }
        settle { exists("media-problem") }
        compose.onNodeWithText("This link is a web page, not an image").assertExists()
        compose.onAllNodes(hasText("valid image format", substring = true)).fetchSemanticsNodes().let { assertThat(it).isEmpty() }
        compose.onNodeWithText("Open in browser").performClick()
        assertThat(opened).containsExactly(page)

        // The viewer, on the page among its neighbours, says the same and offers the same.
        compose.runOnUiThread { state.open(ViewerFixtures.AGENT, entries, page) }
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-error") }
        assertThat(compose.onNodeWithTag("viewer-error-title").fetchSemanticsNode().config.toString()).contains("This link is a web page, not an image")
        settle { exists("viewer-open-browser") }
        compose.onNodeWithTag("viewer-open-browser").performClick()
        assertThat(opened).containsExactly(page, page)
    }
}
