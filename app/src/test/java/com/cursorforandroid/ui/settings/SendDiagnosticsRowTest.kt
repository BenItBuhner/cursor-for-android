package com.cursorforandroid.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.DiagnosticsInbox
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

/**
 * Settings › Debug › Send diagnostics to the Cursor for Android Project: one tap writes the exports into the
 * Project's store and the toast names the path; a write that could not be made says why, in the words the store
 * or the mode gave; the demo, with no account, is refused before anything is composed.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SendDiagnosticsRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val graph = AppGraph(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun `the row writes the exports and the toast names the path written`() {
        var sent = 0
        val toasts = mutableListOf<String>()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                SendDiagnosticsRow(graph, send = { sent++; "inbox/diagnostics/20260918T183012Z.txt" }, toast = { toasts += it })
            }
        }
        compose.onNodeWithText(SendDiagnosticsCopy.TITLE).assertIsDisplayed()
        compose.onNodeWithTag(SendDiagnosticsCopy.TAG).performClick()
        compose.waitUntil(10_000) { toasts.isNotEmpty() }
        assertThat(sent).isEqualTo(1)
        assertThat(toasts).containsExactly(SendDiagnosticsCopy.SENT + "inbox/diagnostics/20260918T183012Z.txt")
    }

    @Test
    fun `a write that could not be made is said in the store's words`() {
        val toasts = mutableListOf<String>()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                SendDiagnosticsRow(graph, send = { throw IOException("The account lists no context store for this Project.") }, toast = { toasts += it })
            }
        }
        compose.onNodeWithTag(SendDiagnosticsCopy.TAG).performClick()
        compose.waitUntil(10_000) { toasts.isNotEmpty() }
        assertThat(toasts).containsExactly(SendDiagnosticsCopy.FAILED + "The account lists no context store for this Project.")
    }

    @Test
    fun `the demo has no account to send from`() = runBlocking<Unit> {
        graph.session.enterDemo()
        val demo = runCatching { graph.sendDiagnosticsToProject() }.exceptionOrNull()
        assertThat(demo).hasMessageThat().isEqualTo(DiagnosticsInbox.DEMO_HAS_NO_ACCOUNT)
    }
}
