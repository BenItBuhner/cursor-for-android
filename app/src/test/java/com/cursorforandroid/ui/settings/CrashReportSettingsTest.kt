package com.cursorforandroid.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A test build is made without a DSN, which is exactly the build the row has to be honest about. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class CrashReportSettingsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a build with no project to report to says so instead of offering a switch`() {
        val graph = AppGraph(ApplicationProvider.getApplicationContext<Context>())
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                SettingsScreen(graph, CursorUser("key", "a@b.com", "Demo", "User", 1), isDemo = true, onOpenSidebar = null, onBack = null)
            }
        }
        // The whole screen is composed (a scrolling Column, not a lazy list), so existence is the question: which copy.
        compose.onNodeWithText(CrashReportCopy.TITLE).assertExists()
        compose.onNodeWithText(CrashReportCopy.UNAVAILABLE).assertExists()
        compose.onNodeWithText(CrashReportCopy.OFF).assertDoesNotExist()
        compose.onNodeWithText(CrashReportCopy.ON).assertDoesNotExist()
    }
}
