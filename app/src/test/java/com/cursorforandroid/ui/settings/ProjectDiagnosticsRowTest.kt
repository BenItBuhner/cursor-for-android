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
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Advanced › Export Project diagnostics: one tap hands the redacted report to the share sheet. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectDiagnosticsRowTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the row shares the report of the list as it stands, with no chat named in it`() = runBlocking<Unit> {
        val graph = AppGraph(ApplicationProvider.getApplicationContext<Application>())
        graph.session.enterDemo()
        // The pin sync (built on first use) is what folds the demo account's lineage onto the rows after a fetch.
        graph.pins
        graph.agents.refresh()
        withTimeout(10_000) { graph.agents.state.first { list -> list.hasLoaded && list.agents.count { it.isProjectChild } >= 3 } }
        var shared: String? = null

        compose.setContent { CursorTheme(mode = ThemeMode.Dark) { ProjectDiagnosticsRow(graph, share = { shared = it }) } }
        compose.onNodeWithText(ProjectDiagnosticsCopy.TITLE).assertIsDisplayed()
        compose.onNodeWithTag(ProjectDiagnosticsCopy.TAG).performClick()
        compose.waitUntil(10_000) { shared != null }

        val report = shared!!
        assertThat(report).startsWith("Cursor for Android ")
        assertThat(report).contains("Project diagnostics")
        assertThat(report).contains("mode=default")
        // The demo's Project, its two workers and its side chat are placed; nothing is named.
        assertThat(report).contains("roots=1")
        assertThat(report).contains("children=3")
        assertThat(report).contains("${DemoData.PROJECT_ID.takeLast(6)} ROOT")
        assertThat(report).doesNotContain("Cesium billing launch")
        assertThat(report).doesNotContain("Stripe webhook handler")
        assertThat(report).doesNotContain(DemoData.PROJECT_ID)
    }
}
