package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.update.WhatsNewFixtures
import com.cursorforandroid.domain.ChatsWidgetSettings
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.FileOpenRequest
import com.cursorforandroid.domain.ModelChoice
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.ModelVariant
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.share.ShareDraft
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.Sidebar
import com.cursorforandroid.ui.agents.SidebarCallbacks
import com.cursorforandroid.ui.agents.SidebarDestination
import com.cursorforandroid.ui.auth.SignInScreen
import com.cursorforandroid.ui.components.RunInterruption
import com.cursorforandroid.ui.components.RunStopDialog
import com.cursorforandroid.ui.components.RunStopTags
import com.cursorforandroid.ui.files.FullFile
import com.cursorforandroid.ui.files.FullFileScreen
import com.cursorforandroid.ui.home.NewChatHomeFixtures
import com.cursorforandroid.ui.navigation.SidebarRail
import com.cursorforandroid.ui.onboarding.ModeChoiceCopy
import com.cursorforandroid.ui.onboarding.ModeChoiceScreen
import com.cursorforandroid.ui.projects.ProjectEditorSheet
import com.cursorforandroid.ui.projects.ProjectEditorTarget
import com.cursorforandroid.ui.settings.NewChatHomePickerCopy
import com.cursorforandroid.ui.settings.NewChatHomePickerTags
import com.cursorforandroid.ui.settings.SettingsScreen
import com.cursorforandroid.ui.settings.WhatsNewScreen
import com.cursorforandroid.ui.settings.WhatsNewTags
import com.cursorforandroid.ui.share.ShareDestinationScreen
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.widget.ChatsWidgetOptions
import com.cursorforandroid.widget.WidgetConfigureScreen
import com.cursorforandroid.widget.WidgetData
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * Every screen other than a chat on a phone, an unfolded foldable (840dp) and a tablet held sideways (1280dp, the
 * Galaxy Tab S8's window) — and Settings upright on that tablet too: Settings from the top and at its New chat page
 * picker, What's new, the widget's settings, the share picker, the full-file viewer's failure, the Project sheet, the
 * stop question, and sign-in and the mode choice. Where the shell puts the sidebar beside the pane (600dp and up)
 * the pane's screens are drawn beside it; the screens that fill the window — the widget's settings, the share picker,
 * the file viewer, onboarding — fill it here too. The account is [NewChatHomeFixtures]' over the demo session, as in
 * [NewChatHomeScreenshotTest].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class TabletLayoutsScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var demo: AppGraph? = null

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { NewChatHomeFixtures.NOW }
    }

    @After
    fun tearDown() {
        demo?.let { runBlocking { it.drafts.clear() } }
        AppClock.nowMillis = System::currentTimeMillis
    }

    // Robolectric has no Android Keystore; an ordinary private file stands in, as in AppScreenshotTest.
    private fun keyStore() = SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }

    /** The demo session with the catalogue loaded, so the picker's miniatures read the same chips from their first frame. */
    private fun demoGraph(): AppGraph = AppGraph(context, keyStore(), appVersion = SCREENSHOT_APP_VERSION).also { graph ->
        runBlocking {
            graph.session.enterDemo()
            graph.drafts.clear()
            graph.catalog.loadRepositories()
            graph.catalog.loadModels()
            graph.agents.refresh()
        }
        demo = graph
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Composable
    private fun Scene(content: @Composable () -> Unit) {
        CursorTheme(mode = ThemeMode.Dark) {
            // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) { content() }
            }
        }
    }

    /** The pane as the shell lays it out: beside the sidebar from 600dp (the shell's "wide"), the whole window below it. */
    @Composable
    private fun Pane(destination: SidebarDestination?, pane: @Composable (modifier: Modifier, wide: Boolean) -> Unit) {
        if (LocalConfiguration.current.screenWidthDp < 600) {
            pane(Modifier.fillMaxSize(), false)
            return
        }
        Row(Modifier.fillMaxSize()) {
            SidebarRail(expanded = true) {
                Sidebar(
                    state = NewChatHomeFixtures.list(),
                    user = DEMO_USER,
                    isDemo = true,
                    selectedAgentId = null,
                    selectedDestination = destination,
                    onQueryChange = {},
                    callbacks = SidebarCallbacks(
                        onNewChat = {},
                        onSettings = {},
                        onCustomize = {},
                        onToggleSidebar = {},
                        onRefresh = {},
                        rowActions = ROW_ACTIONS,
                        onNewProject = {},
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            pane(Modifier.weight(1f).fillMaxHeight(), true)
        }
    }

    private fun showSettings() {
        val graph = demoGraph()
        val list = NewChatHomeFixtures.list()
        compose.setContent {
            Scene {
                Pane(SidebarDestination.Settings) { modifier, wide ->
                    // A phone's Settings has the back chevron, and the pane's header over the miniatures; a wide window's has neither.
                    Box(modifier) {
                        SettingsScreen(graph, DEMO_USER, isDemo = true, onOpenSidebar = if (wide) null else ({}), onBack = if (wide) null else ({}), newChatList = list)
                    }
                }
            }
        }
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag(NewChatHomePickerTags.of(NewChatHome.RECENT)) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun settings(frame: String) {
        showSettings()
        capture(frame)
    }

    private fun picker(frame: String) {
        showSettings()
        compose.scrollSettingsGroupToTop(NewChatHomePickerCopy.GROUP)
        capture(frame)
    }

    private fun whatsNew(frame: String) {
        val version = WhatsNewFixtures.VERSION
        val notes = WhatsNewFixtures.repository(PreferencesStore(context), folder.newFolder(), versionName = version, notes = WhatsNewFixtures.notes(version))
        val graph = AppGraph(context, keyStore(), releaseNotes = notes, appVersion = version)
        compose.setContent { Scene { Pane(null) { modifier, _ -> WhatsNewScreen(graph = graph, onBack = {}, modifier = modifier) } } }
        compose.waitUntil(30_000) { tagged(WhatsNewTags.NOTES) }
        capture(frame)
    }

    private fun widgetConfigure(frame: String) {
        val graph = demoGraph()
        val snapshot = runBlocking { WidgetData.snapshot(graph) }.copy(theme = ThemeMode.Dark)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                WidgetConfigureScreen(title = "Chats widget", subtitle = "What it shows and how it looks", onDone = {}, onClose = {}) {
                    ChatsWidgetOptions(settings = ChatsWidgetSettings(), snapshot = snapshot, onChange = {})
                }
            }
        }
        compose.waitForIdle()
        // The preview composes its RemoteViews a moment after the first frame.
        compose.mainClock.advanceTimeBy(500)
        capture(frame)
    }

    private fun share(frame: String) {
        compose.setContent {
            Scene {
                ShareDestinationScreen(
                    listState = NewChatHomeFixtures.list(),
                    draft = ShareDraft(generation = 1, text = "Shared from Chrome", attachments = emptyList()),
                    onNewChat = {},
                    onPickChat = {},
                    onRefresh = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(30_000) { onScreen(NewChatHomeFixtures.NEWEST_CHAT) }
        capture(frame)
    }

    private fun fullFileFailed(frame: String) {
        val failed = FullFile.Failed("The agent's machine is asleep", retryable = true, detail = "Wake it and the file is read again.", wakeable = true)
        compose.setContent { Scene { FullFileScreen(FileOpenRequest("web/lib/session.ts"), load = { failed }, onClose = {}) } }
        compose.waitUntil(30_000) { tagged("full-file-failed") }
        capture(frame)
    }

    private fun projectSheet(frame: String) {
        val auto = ModelOption("default", "Auto")
        val sonnet = ModelOption("claude-4.5-sonnet", "Claude 4.5 Sonnet", variants = listOf(ModelVariant("Default", emptyList(), isDefault = true), ModelVariant("High effort", listOf(ModelParam("effort", "high")), isDefault = false)))
        compose.setContent {
            Scene {
                ProjectEditorSheet(
                    target = ProjectEditorTarget.Create,
                    initialName = "",
                    initialAppearance = ProjectAppearance("code", "blue"),
                    repositories = listOf(Repository("https://github.com/acme/billing"), Repository("https://github.com/acme/web")),
                    ownedRepoUrls = emptyList(),
                    repositoriesLoading = false,
                    busy = false,
                    error = null,
                    onRefreshRepositories = {},
                    onConfirm = {},
                    onDismiss = {},
                    models = listOf(auto, sonnet, ModelOption("gpt-5.6", "GPT-5.6")),
                    defaultModel = ModelChoice(sonnet, sonnet.variants.last()),
                )
            }
        }
        compose.waitUntil(10_000) { onScreen("New Project") }
        capture(frame)
    }

    @Test
    fun settingsPhone() = settings("360_tablet_layouts_settings_phone")

    @Test
    @Config(sdk = [35], qualifiers = FOLDABLE)
    fun settingsFoldable() = settings("361_tablet_layouts_settings_foldable")

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun settingsTablet() = settings("362_tablet_layouts_settings_tablet")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_PORTRAIT)
    fun settingsTabletPortrait() = settings("363_tablet_layouts_settings_tablet_portrait")

    @Test
    fun pickerPhone() = picker("364_tablet_layouts_picker_phone")

    @Test
    @Config(sdk = [35], qualifiers = FOLDABLE)
    fun pickerFoldable() = picker("365_tablet_layouts_picker_foldable")

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun pickerTablet() = picker("366_tablet_layouts_picker_tablet")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_PORTRAIT)
    fun pickerTabletPortrait() = picker("367_tablet_layouts_picker_tablet_portrait")

    @Test
    fun whatsNewPhone() = whatsNew("368_tablet_layouts_whats_new_phone")

    @Test
    @Config(sdk = [35], qualifiers = FOLDABLE)
    fun whatsNewFoldable() = whatsNew("369_tablet_layouts_whats_new_foldable")

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun whatsNewTablet() = whatsNew("370_tablet_layouts_whats_new_tablet")

    @Test
    fun widgetConfigurePhone() = widgetConfigure("371_tablet_layouts_widget_configure_phone")

    @Test
    @Config(sdk = [35], qualifiers = FOLDABLE)
    fun widgetConfigureFoldable() = widgetConfigure("372_tablet_layouts_widget_configure_foldable")

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun widgetConfigureTablet() = widgetConfigure("373_tablet_layouts_widget_configure_tablet")

    @Test
    fun sharePhone() = share("374_tablet_layouts_share_phone")

    @Test
    @Config(sdk = [35], qualifiers = FOLDABLE)
    fun shareFoldable() = share("375_tablet_layouts_share_foldable")

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun shareTablet() = share("376_tablet_layouts_share_tablet")

    @Test
    fun fullFileFailedPhone() = fullFileFailed("377_tablet_layouts_full_file_failed_phone")

    @Test
    @Config(sdk = [35], qualifiers = FOLDABLE)
    fun fullFileFailedFoldable() = fullFileFailed("378_tablet_layouts_full_file_failed_foldable")

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun fullFileFailedTablet() = fullFileFailed("379_tablet_layouts_full_file_failed_tablet")

    @Test
    fun projectSheetPhone() = projectSheet("380_tablet_layouts_project_sheet_phone")

    @Test
    @Config(sdk = [35], qualifiers = FOLDABLE)
    fun projectSheetFoldable() = projectSheet("381_tablet_layouts_project_sheet_foldable")

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun projectSheetTablet() = projectSheet("382_tablet_layouts_project_sheet_tablet")

    /** Material's dialog keeps to its own 280–560dp on any window: the stop question, as a chat asks it. */
    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun stopDialogTablet() {
        compose.setContent { Scene { RunStopDialog(RunInterruption.Stop, onConfirm = {}, onKeepRunning = {}) } }
        compose.waitUntil(10_000) { tagged(RunStopTags.DIALOG) }
        capture("383_tablet_layouts_stop_dialog_tablet")
    }

    /** Sign-in and the mode choice: a single form's column, centred in the window both ways. */
    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun signInTablet() {
        val graph = AppGraph(context, keyStore())
        compose.setContent { Scene { SignInScreen(graph) } }
        compose.waitUntil(30_000) { onScreen("Continue with Cursor") }
        capture("384_tablet_layouts_sign_in_tablet")
    }

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun modeChoiceTablet() {
        val graph = AppGraph(context, keyStore())
        compose.setContent { Scene { ModeChoiceScreen(graph) } }
        compose.waitUntil(30_000) { onScreen(ModeChoiceCopy.TITLE) }
        capture("385_tablet_layouts_mode_choice_tablet")
    }

    private companion object {
        /** The demo session's own account, as the shell shows it. */
        val DEMO_USER = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null)
        val ROW_ACTIONS = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
    }
}

private const val PHONE = "w411dp-h914dp-night-420dpi"

/** An unfolded book-style foldable, held as it opens: Material's 840dp expanded width. */
private const val FOLDABLE = "w840dp-h700dp-night-320dpi"

/** A 11" tablet held sideways (SM-X700: 2560×1600 at xhdpi), then upright. */
private const val TABLET = "w1280dp-h800dp-night-320dpi"
private const val TABLET_PORTRAIT = "w800dp-h1280dp-night-320dpi"
