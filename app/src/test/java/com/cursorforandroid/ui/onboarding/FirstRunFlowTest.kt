package com.cursorforandroid.ui.onboarding

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
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
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.domain.CredentialInfo
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.SignInMethod
import com.cursorforandroid.ui.CursorRoot
import com.cursorforandroid.ui.auth.SignInCopy
import com.cursorforandroid.ui.settings.ExtendedModeCopy
import com.cursorforandroid.ui.settings.ExtendedModeTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The first run through the real root — sign-in screen, choice screen, app — against a scripted `/v1/me`: who is
 * asked (a fresh sign-in, every sign-in), who is not (an install that already had its account, the demo), and that
 * the app is never on screen before the choice is made.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class FirstRunFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCursorApi()

    /** Robolectric has no Android Keystore; an ordinary private file stands in, and keeps the key across "processes". */
    private val keyStore = SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }

    private fun graph(): AppGraph = AppGraph(context, keyStore, real = CursorBackend(api, FakeRunStreamer(), isDemo = false)).also {
        // Turning the mode on would reach for the account (its picture, the pins' first round); there is none here.
        it.extendedMode.onEnabled = {}
    }

    /** What an install that signed in before this build looks like: a stored key, the account it described, the setting introduced. */
    private fun installWithAccount(): AppGraph {
        keyStore.setApiKey("key_stored")
        val graph = graph()
        runBlocking {
            graph.prefs.setCachedUser(USER)
            graph.prefs.setCredentialInfo(CredentialInfo(SignInMethod.ApiKey, expiresAtMs = null))
            graph.prefs.setExtendedModeIntroduced(noticePending = false)
        }
        return graph
    }

    /**
     * The session is decided before the root is composed, as it is in a process whose session was decided before its
     * activity (see AppScreenshotTest.launchApp for why the restore is not left to the root's own effect here).
     */
    private fun launch(graph: AppGraph) {
        runBlocking { graph.session.restoreIfNeeded() }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { CursorRoot(graph = graph, deepLinkAgentId = null, onDeepLinkConsumed = {}) }
        }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForSignIn() = compose.waitUntil(30_000) { onScreen(CONTINUE_WITH_CURSOR) }

    private fun waitForChoice() = compose.waitUntil(30_000) { tagged(ModeChoiceTags.SCREEN) }

    private fun waitForHome() = compose.waitUntil(30_000) { onScreen(HOME_PLACEHOLDER) }

    /** The pasted-key path of the sign-in screen, against the fake `/v1/me`. */
    private fun signInWithKey() {
        compose.onNodeWithText("Use an API key instead").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("key_pasted")
        compose.onNodeWithText("Continue").performClick()
    }

    private fun pending() = graph.onboarding.modeChoicePending.value

    private lateinit var graph: AppGraph

    @Test
    fun `a fresh install signs in, is asked once, and reaches the app on SDK only`() {
        graph = graph()
        launch(graph)

        // The first screen says what the app is before it asks for anything.
        waitForSignIn()
        compose.onNodeWithText(SignInCopy.UNOFFICIAL).assertIsDisplayed()
        assertThat(tagged(ModeChoiceTags.SCREEN)).isFalse()
        assertThat(onScreen(HOME_PLACEHOLDER)).isFalse()

        signInWithKey()

        // Signed in, and asked: the choice, with SDK only selected, and nothing of the app behind it.
        waitForChoice()
        assertThat(graph.session.state.value).isInstanceOf(SessionState.SignedIn::class.java)
        assertThat(pending()).isTrue()
        assertThat(runBlocking { graph.prefs.modeChoicePending.first() }).isTrue()
        compose.onNodeWithTag(ModeChoiceTags.SDK_ONLY).assertIsSelected()
        compose.onAllNodes(hasText(HOME_PLACEHOLDER, substring = true)).assertCountEquals(0)
        assertThat(onScreen(CONTINUE_WITH_CURSOR)).isFalse()

        compose.onNodeWithTag(ModeChoiceTags.CONTINUE).performClick()

        waitForHome()
        assertThat(tagged(ModeChoiceTags.SCREEN)).isFalse()
        assertThat(pending()).isFalse()
        assertThat(runBlocking { graph.extendedMode.isEnabled() }).isFalse()
        assertThat(runBlocking { graph.extendedMode.acknowledgedAt.first() }).isNull()
        assertThat(api.meCalls).isEqualTo(1)
    }

    @Test
    fun `declining the warning on the way in lands in the app on SDK only`() {
        graph = graph()
        launch(graph)
        waitForSignIn()
        signInWithKey()
        waitForChoice()

        compose.onNodeWithTag(ModeChoiceTags.EXTENDED).performClick()
        compose.onNodeWithTag(ModeChoiceTags.CONTINUE).performClick()
        compose.waitUntil(10_000) { tagged(ExtendedModeTags.DIALOG) }
        compose.onNodeWithText(ExtendedModeCopy.DIALOG_CANCEL).performClick()
        compose.waitUntil(10_000) { !tagged(ExtendedModeTags.DIALOG) }

        // Fallen back, said so, still asking; the app is not behind the dialog.
        compose.onNodeWithTag(ModeChoiceTags.SDK_ONLY).assertIsSelected()
        compose.onNodeWithText(ModeChoiceCopy.DECLINED_NOTE).assertIsDisplayed()
        assertThat(pending()).isTrue()
        assertThat(onScreen(HOME_PLACEHOLDER)).isFalse()

        compose.onNodeWithTag(ModeChoiceTags.CONTINUE).performClick()

        waitForHome()
        assertThat(pending()).isFalse()
        assertThat(runBlocking { graph.extendedMode.isEnabled() }).isFalse()
        assertThat(runBlocking { graph.extendedMode.acknowledgedAt.first() }).isNull()
    }

    // Accepting the warning is driven in ModeChoiceScreenTest: the shell it would lead to reads the account's list
    // in Extended mode with the key the sign-in stored, and nothing here should knock on api2 for real.

    @Test
    fun `an install that already had an account is not asked`() {
        graph = installWithAccount()
        launch(graph)

        waitForHome()
        assertThat(tagged(ModeChoiceTags.SCREEN)).isFalse()
        assertThat(pending()).isFalse()
        assertThat(runBlocking { graph.prefs.modeChoicePending.first() }).isFalse()
        assertThat((graph.session.state.value as SessionState.SignedIn).isDemo).isFalse()
    }

    @Test
    fun `signing out and back in is asked again`() {
        graph = installWithAccount()
        launch(graph)
        waitForHome()
        assertThat(tagged(ModeChoiceTags.SCREEN)).isFalse()

        // Off the main thread: the sign-out's wipe must not wait on a looper the screen is holding.
        CoroutineScope(Dispatchers.IO).launch { graph.signOut() }
        compose.waitUntil(30_000) { graph.session.state.value is SessionState.SignedOut }
        waitForSignIn()
        assertThat(pending()).isFalse()

        signInWithKey()

        waitForChoice()
        assertThat(pending()).isTrue()
        assertThat(onScreen(HOME_PLACEHOLDER)).isFalse()
    }

    @Test
    fun `the demo is never asked`() {
        graph = graph()
        launch(graph)
        waitForSignIn()

        compose.onNodeWithText("Try the demo").performClick()

        waitForHome()
        assertThat(tagged(ModeChoiceTags.SCREEN)).isFalse()
        assertThat(pending()).isFalse()
        assertThat((graph.session.state.value as SessionState.SignedIn).isDemo).isTrue()
    }

    @Test
    fun `a process that died between the sign-in and the choice asks on its next start`() {
        graph = installWithAccount()
        // What the sign-in wrote before the process went: the flag, still standing.
        runBlocking { graph.prefs.setModeChoicePending(true) }
        launch(graph)

        waitForChoice()
        assertThat(onScreen(HOME_PLACEHOLDER)).isFalse()
        compose.onNodeWithTag(ModeChoiceTags.SDK_ONLY).assertIsSelected()
    }

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val CONTINUE_WITH_CURSOR = "Continue with Cursor"
        val USER = CursorUser("Cursor for Android (Pixel 9)", "alex@example.com", "Alex", "Rivera", 7L)
    }
}
