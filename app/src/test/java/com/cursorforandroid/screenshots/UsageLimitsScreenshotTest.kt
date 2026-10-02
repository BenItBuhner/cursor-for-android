package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.R
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.UsageReset
import com.cursorforandroid.ui.settings.SettingsCopy
import com.cursorforandroid.ui.settings.SettingsScreen
import com.cursorforandroid.ui.settings.SettingsTags
import com.cursorforandroid.ui.settings.UsageCopy
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
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
 * Demo Settings' usage group (included models, API remaining, prepaid credits) and the reset-to-100% notification
 * copy, so the walkthrough goldens are not the only record of either.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class UsageLimitsScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { FIXED_NOW }
        val context = ApplicationProvider.getApplicationContext<Context>()
        graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, appVersion = SCREENSHOT_APP_VERSION)
    }

    @After
    fun restoreClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Test
    fun demoSettings() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    SettingsScreen(graph = graph, user = DEMO_USER, isDemo = true, onOpenSidebar = null, onBack = {})
                }
            }
        }
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag(SettingsTags.USAGE_CARD)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(SettingsCopy.GROUP_USAGE).assertIsDisplayed()
        compose.onNodeWithText(UsageCopy.CURSOR_MODELS).assertIsDisplayed()
        compose.onNodeWithText(UsageCopy.API_USAGE).assertIsDisplayed()
        compose.onNodeWithText(UsageCopy.CREDITS).assertIsDisplayed()
        compose.onNodeWithText(UsageCopy.percent(83)).assertIsDisplayed()
        compose.onNodeWithText(UsageCopy.percent(100)).assertIsDisplayed()
        compose.onNodeWithText("$900.00").assertIsDisplayed()
        capture("1140_usage_settings_demo")
    }

    @Test
    fun resetNotification() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reset = UsageReset(included = true, api = false)
        assertThat(reset.any).isTrue()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ResetNotificationFrame(reset)
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.notif_usage_reset_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.notif_usage_reset_included)).assertIsDisplayed()
        capture("1141_usage_reset_notification")
    }

    private companion object {
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
        val DEMO_USER = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null)
    }
}

/** The reset notification's words on a lock-screen-like canvas, using the same copy the usage channel posts. */
@Composable
private fun ResetNotificationFrame(reset: UsageReset) {
    val context = LocalContext.current
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val text = context.getString(
        when {
            reset.included && reset.api -> R.string.notif_usage_reset_both
            reset.included -> R.string.notif_usage_reset_included
            else -> R.string.notif_usage_reset_api
        },
    )
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.elevated)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_stat_agent),
                    contentDescription = null,
                    tint = Color(0xFF81A1C1),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(context.getString(R.string.app_name), style = type.small, color = colors.textTertiary)
            }
            Text(context.getString(R.string.notif_usage_reset_title), style = type.base, color = colors.textPrimary)
            Text(text, style = type.small, color = colors.textSecondary)
        }
    }
}
