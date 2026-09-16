package com.cursorforandroid.ui.panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.DesktopFailure
import com.cursorforandroid.domain.DesktopSession
import com.cursorforandroid.domain.DesktopTrace
import com.cursorforandroid.domain.MachineUnavailableReason
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The desktop viewer's screen in each of its states: the steps under way, the failing step with retry and diagnostics, the viewer's own chrome. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class DesktopScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf<DesktopState>(DesktopState.Idle)
    private val asked = mutableListOf<String>()

    private fun show() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                DesktopScreen(
                    state = state,
                    agentName = "Dark theme toggle",
                    onViewOnlyChange = { asked += "viewonly:$it" },
                    onRetry = { asked += "retry:${if (it) "view" else "control"}" },
                    onFail = { asked += "fail:${it.trace.lastFailed?.name}" },
                    onShare = { asked += "share:$it" },
                    onClose = { asked += "close" },
                )
            }
        }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private val opening = DesktopTrace("bc-1")
        .begin(DesktopTrace.GET_MACHINE, 1_000).end(DesktopTrace.GET_MACHINE, 1_300, "pod pod-1 in us-east1 (desktop ticket)")
        .begin("probe t-pod-1-26058.us-east1.cursorvm.com", 1_300)

    @Test
    fun `while the machine is found the steps show by name, the running one first in the status line`() {
        state = DesktopState.Opening(opening, viewOnly = false)
        show()
        compose.onNodeWithTag("desktop-screen").assertIsDisplayed()
        assertThat(compose.onAllNodesWithTag("desktop-step").fetchSemanticsNodes()).hasSize(2)
        assertThat(shown("GetMachine")).isTrue()
        assertThat(shown("pod pod-1 in us-east1")).isTrue()
        assertThat(shown("300 ms")).isTrue()
        compose.onNodeWithTag("desktop-connection").assertIsDisplayed()
        assertThat(shown("probe t-pod-1-26058.us-east1.cursorvm.com…")).isTrue()
        // No viewer yet: no control switch to flip.
        assertThat(compose.onAllNodesWithTag("desktop-control-toggle").fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithContentDescription("Reconnect").performClick()
        assertThat(asked).contains("retry:control")
    }

    @Test
    fun `a failure names the step, quotes the reason, offers a retry where one can help and shares the redacted report`() {
        val trace = opening.end("probe t-pod-1-26058.us-east1.cursorvm.com", 1_800, "HTTP 403", failed = true)
            .begin("probe t-pod-1-6080.us-east1.cursorvm.com", 1_800).end("probe t-pod-1-6080.us-east1.cursorvm.com", 11_800, "timed out", failed = true)
            .withEndpoint("t-pod-1-6080.us-east1.cursorvm.com", null)
        state = DesktopState.Failed(DesktopFailure.Unreachable("The desktop refused this ticket (HTTP 403).", trace))
        show()
        compose.onNodeWithTag("desktop-failure").assertIsDisplayed()
        assertThat(shown("Failed at probe t-pod-1-6080.us-east1.cursorvm.com")).isTrue()
        compose.onNodeWithTag("desktop-failure-message").assertIsDisplayed()
        assertThat(shown("HTTP 403")).isTrue()
        assertThat(shown("Endpoint: t-pod-1-6080.us-east1.cursorvm.com")).isTrue()
        compose.onNodeWithTag("desktop-retry").performClick()
        assertThat(asked).contains("retry:view")
        compose.onNodeWithTag("desktop-share-diagnostics").performClick()
        val shared = asked.first { it.startsWith("share:") }.removePrefix("share:")
        assertThat(shared).contains("Cursor for Android desktop diagnostics")
        assertThat(shared).contains("probe t-pod-1-26058.us-east1.cursorvm.com: HTTP 403 (500 ms) FAILED")
        assertThat(shared).contains("endpoint: t-pod-1-6080.us-east1.cursorvm.com (websockify)")
        assertThat(shared).doesNotContain("network_token")
        compose.onNodeWithText("Close").performClick()
        assertThat(asked).contains("close")
    }

    @Test
    fun `a final refusal has no retry, only the diagnostics`() {
        val trace = DesktopTrace("bc-1").begin(DesktopTrace.GET_MACHINE, 0).end(DesktopTrace.GET_MACHINE, 200, "MACHINE_NOT_PROVISIONED", failed = true)
        state = DesktopState.Failed(DesktopFailure.NoDesktop("The chat has no VM (MACHINE_NOT_PROVISIONED).", trace, MachineUnavailableReason.MACHINE_NOT_PROVISIONED))
        show()
        assertThat(shown("Failed at GetMachine")).isTrue()
        assertThat(compose.onAllNodesWithTag("desktop-retry").fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithTag("desktop-share-diagnostics").assertIsDisplayed()
    }

    @Test
    fun `the viewer's chrome carries the control switch, and its reconnect keeps the mode`() {
        state = DesktopState.Open(DesktopSession("bc-1", "wss://t-pod-1-26058.us-east1.cursorvm.com:443/websockify?network_token=x", viewOnly = false, port = 26058, trace = opening.end("probe t-pod-1-26058.us-east1.cursorvm.com", 1_700, "handshake accepted")))
        show()
        compose.onNodeWithTag("desktop-screen").assertIsDisplayed()
        assertThat(shown("In control")).isTrue()
        // Under Robolectric the page never loads, so the viewer sits on its first step and says so.
        assertThat(shown("Loading the viewer")).isTrue()
        assertThat(shown("Viewer page")).isTrue()
        compose.onNodeWithTag("desktop-control-toggle").performClick()
        assertThat(asked).contains("viewonly:true")
        compose.onNodeWithContentDescription("Reconnect").performClick()
        assertThat(asked).contains("retry:control")
        compose.onNodeWithContentDescription("Close the desktop").performClick()
        assertThat(asked).contains("close")
    }

    @Test
    fun `the page's state is read back as the JSON evaluateJavascript hands over`() {
        val ready = DesktopViewer.parsePageState("\"{\\\"phase\\\":\\\"connected\\\",\\\"detail\\\":\\\"Ubuntu\\\",\\\"frames\\\":3,\\\"ready\\\":true,\\\"error\\\":null}\"")!!
        assertThat(ready.phase).isEqualTo("connected")
        assertThat(ready.detail).isEqualTo("Ubuntu")
        assertThat(ready.frames).isEqualTo(3)
        assertThat(ready.ready).isTrue()
        assertThat(ready.error).isNull()
        assertThat(DesktopViewer.parsePageState("\"none\"")).isNull()
        assertThat(DesktopViewer.parsePageState("null")).isNull()
        val broken = DesktopViewer.parsePageState("\"{\\\"phase\\\":\\\"error\\\",\\\"error\\\":\\\"Failed to fetch dynamically imported module\\\"}\"")!!
        assertThat(broken.phase).isEqualTo("error")
        assertThat(broken.ready).isFalse()
        assertThat(broken.error).contains("imported module")
    }
}
