package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.ui.CursorRoot
import com.cursorforandroid.ui.auth.SignInCopy
import com.cursorforandroid.ui.onboarding.ModeChoiceCopy
import com.cursorforandroid.ui.onboarding.ModeChoiceTags
import com.cursorforandroid.ui.settings.ExtendedModeCopy
import com.cursorforandroid.ui.settings.ExtendedModeTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The first run, frame by frame, through the real root against a scripted `/v1/me`: the sign-in screen as a fresh
 * install meets it, the choice screen it leads to with SDK only selected, and the same screen after the warning was
 * declined, with the one-line note. Same device qualifiers as [AppScreenshotTest]; nothing here shows a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class OnboardingScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun waitFor(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        compose.waitUntil(30_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun firstRun() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric has no Android Keystore; an ordinary private file stands in, as in AppScreenshotTest.
        val keyStore = SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }
        val graph = AppGraph(context, keyStore, real = CursorBackend(FakeCursorApi(), FakeRunStreamer(), isDemo = false))
        runBlocking { graph.session.restoreIfNeeded() }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    CursorRoot(graph = graph, deepLinkAgentId = null, onDeepLinkConsumed = {})
                }
            }
        }

        // A fresh install: the sign-in screen, which says what the app is in the line under the title.
        waitFor(hasText("Continue with Cursor"))
        compose.onNodeWithText(SignInCopy.UNOFFICIAL).assertIsDisplayed()
        capture("72_onboarding_sign_in")

        // Signed in with a pasted key: the choice, SDK only selected, before anything of the app.
        compose.onNodeWithText("Use an API key instead").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("key_pasted")
        compose.onNodeWithText("Continue").performClick()
        waitFor(hasTestTag(ModeChoiceTags.SCREEN))
        compose.onNodeWithTag(ModeChoiceTags.SDK_ONLY).assertIsSelected()
        compose.onNodeWithText(ModeChoiceCopy.EXTENDED_BODY).assertIsDisplayed()
        capture("73_onboarding_mode_choice")

        // Extended mode asked for and its warning declined: SDK only again, and the note under the options.
        compose.onNodeWithTag(ModeChoiceTags.EXTENDED).performClick()
        compose.onNodeWithTag(ModeChoiceTags.CONTINUE).performClick()
        waitFor(hasTestTag(ExtendedModeTags.DIALOG))
        compose.onNodeWithText(ExtendedModeCopy.DIALOG_CANCEL).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(ExtendedModeTags.DIALOG)).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag(ModeChoiceTags.SDK_ONLY).assertIsSelected()
        compose.onNodeWithText(ModeChoiceCopy.DECLINED_NOTE).assertIsDisplayed()
        capture("74_onboarding_mode_choice_declined")
    }
}
