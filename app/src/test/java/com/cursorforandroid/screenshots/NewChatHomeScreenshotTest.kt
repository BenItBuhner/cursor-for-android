package com.cursorforandroid.screenshots

import android.content.Context
import android.view.ViewConfiguration
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.Sidebar
import com.cursorforandroid.ui.agents.SidebarCallbacks
import com.cursorforandroid.ui.agents.SidebarDestination
import com.cursorforandroid.ui.home.HomeScreen
import com.cursorforandroid.ui.home.NewChatHomeCopy
import com.cursorforandroid.ui.home.NewChatHomeFixtures
import com.cursorforandroid.ui.home.NewChatHomeTags
import com.cursorforandroid.ui.navigation.SidebarRail
import com.cursorforandroid.ui.settings.NewChatHomePickerCopy
import com.cursorforandroid.ui.settings.NewChatHomePickerTags
import com.cursorforandroid.ui.settings.SettingsScreen
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * The New chat page setting: the New Chat pane under each layout — the recent chats, the Projects pinned as
 * shortcuts, and the composer alone — on a phone and beside the sidebar on a tablet, dark and light, what does not
 * fill the pane in its middle; the Projects layout's two notes; the recent chats' cards and the shortcuts inset alike
 * from the composer's sides (drawn as guides); a shortcut's long-press menu, and the shortcuts being arranged; and
 * Settings' picker with each layout chosen, its three miniatures drawn from the same account, on both widths and in
 * both themes, then with Extended mode off. The account is [NewChatHomeFixtures]' (five Projects and the chats of its
 * own) over the demo session, whose catalogue fills the composer's chips on the page and in the miniatures alike.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_DARK)
class NewChatHomeScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { NewChatHomeFixtures.NOW }
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric has no Android Keystore; an ordinary private file stands in, as in AppScreenshotTest.
        graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, appVersion = SCREENSHOT_APP_VERSION)
        // The composer's chips — the page's own and the miniatures' — settle on the catalogue and the account's newest
        // chat's model; with all three loaded first, both read the same from their first frame.
        runBlocking {
            graph.session.enterDemo()
            graph.drafts.clear()
            graph.catalog.loadRepositories()
            graph.catalog.loadModels()
            graph.agents.refresh()
        }
    }

    @After
    fun tearDown() {
        runBlocking { graph.drafts.clear() }
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Composable
    private fun Scene(mode: ThemeMode, content: @Composable () -> Unit) {
        CursorTheme(mode = mode) {
            // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) { content() }
            }
        }
    }

    /** The sidebar beside the pane, as the shell lays a wide window out. */
    @Composable
    private fun Tablet(destination: SidebarDestination, list: AgentListUiState, pane: @Composable (Modifier) -> Unit) {
        Row(Modifier.fillMaxSize()) {
            SidebarRail(expanded = true) {
                Sidebar(
                    state = list,
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
            pane(Modifier.weight(1f).fillMaxHeight())
        }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun shortcuts() = compose.onAllNodes(hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT)).fetchSemanticsNodes().size

    private fun showPage(
        home: NewChatHome,
        mode: ThemeMode = ThemeMode.Dark,
        tablet: Boolean = false,
        list: AgentListUiState = NewChatHomeFixtures.list(),
        projectsAvailable: Boolean = true,
        rowActions: AgentRowActions = ROW_ACTIONS,
        onReorderProjects: ((List<String>) -> Unit)? = null,
        overlay: @Composable () -> Unit = {},
    ) {
        compose.setContent {
            Scene(mode) {
                val page: @Composable (Modifier, Boolean) -> Unit = { modifier, withHeader ->
                    HomeScreen(
                        graph = graph,
                        listState = list,
                        onOpenSidebar = if (withHeader) ({}) else null,
                        onOpenAgent = {},
                        onLaunchOpen = {},
                        rowActions = rowActions,
                        modifier = modifier,
                        home = home,
                        projectsAvailable = projectsAvailable,
                        onNewProject = {},
                        onOpenSettings = {},
                        onReorderProjects = onReorderProjects,
                    )
                }
                if (tablet) Tablet(SidebarDestination.NewChat, list) { page(it, false) } else page(Modifier.fillMaxSize(), true)
                overlay()
            }
        }
        // The first composition in a cold sandbox loads the native renderer and the fonts.
        compose.waitUntil(30_000) { onScreen(NewChatHomeCopy.PLACEHOLDER) }
        compose.waitUntil(30_000) { onScreen(MODEL_CHIP) }
    }

    private fun projectsPage(mode: ThemeMode, tablet: Boolean, frame: String) {
        showPage(NewChatHome.PROJECTS, mode, tablet)
        compose.waitUntil(10_000) { shortcuts() == 5 }
        // The account's own chats are the other layout's (a tablet's sidebar lists them beside the pane).
        if (!tablet) assertThat(onScreen(NewChatHomeFixtures.NEWEST_CHAT)).isFalse()
        capture(frame)
    }

    private fun recentPage(mode: ThemeMode, tablet: Boolean, frame: String) {
        showPage(NewChatHome.RECENT, mode, tablet)
        compose.waitUntil(10_000) { onScreen("Revenue Scaling Pipeline Research") }
        assertThat(shortcuts()).isEqualTo(0)
        capture(frame)
    }

    /** The composer alone, in the middle of the pane; the chats are the sidebar's (beside it on a tablet). */
    private fun composerPage(mode: ThemeMode, tablet: Boolean, frame: String) {
        showPage(NewChatHome.COMPOSER, mode, tablet)
        assertThat(shortcuts()).isEqualTo(0)
        if (!tablet) assertThat(onScreen(NewChatHomeFixtures.NEWEST_CHAT)).isFalse()
        capture(frame)
    }

    /** The page's list of Project shortcuts, which the touches go to (a tablet's sidebar is a list too). */
    private fun projectList() = compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT)))

    /** The centre of [name]'s shortcut (not its row in a tablet's sidebar), in [projectList]'s coordinates. */
    private fun shortcutCentre(name: String): Offset =
        compose.onNode(hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT) and hasText(name, substring = true)).fetchSemanticsNode().boundsInRoot.center -
            projectList().fetchSemanticsNode().boundsInRoot.topLeft

    /** Lets [millis] pass a frame at a time, the page laid out after each as it would be on screen. */
    private fun pass(millis: Long) {
        var left = millis
        while (left > 0) {
            compose.mainClock.advanceTimeBy(minOf(left, FRAME_MILLIS))
            compose.waitForIdle()
            left -= FRAME_MILLIS
        }
    }

    /** A Project shortcut held for the long-press timeout and let go: its menu, the one its sidebar row has. */
    private fun projectMenu(mode: ThemeMode, frame: String) {
        showPage(NewChatHome.PROJECTS, mode, rowActions = PROJECT_ROW_ACTIONS, onReorderProjects = {})
        compose.waitUntil(10_000) { shortcuts() == 5 }
        compose.mainClock.autoAdvance = false
        val at = shortcutCentre(NewChatHomeFixtures.SHIPYARD_NAME)
        projectList().performTouchInput { down(at) }
        pass(LONG_PRESS + 100)
        projectList().performTouchInput { up() }
        pass(600)
        assertThat(onScreen(EDIT_PROJECT)).isTrue()
        capture(frame)
    }

    /**
     * Shipyard held twice as long, lifted and carried most of the way to Cursor for Android's slot: the others have
     * made room, its own slot is marked where it would be dropped, and every shortcut shows its grip.
     */
    private fun projectsReordering(mode: ThemeMode, tablet: Boolean, frame: String) {
        showPage(NewChatHome.PROJECTS, mode, tablet, rowActions = PROJECT_ROW_ACTIONS, onReorderProjects = {})
        compose.waitUntil(10_000) { shortcuts() == 5 }
        compose.mainClock.autoAdvance = false
        val start = shortcutCentre(NewChatHomeFixtures.SHIPYARD_NAME)
        val carried = lerp(start, shortcutCentre("Cursor for Android"), 0.8f)
        projectList().performTouchInput { down(start) }
        pass(2 * LONG_PRESS + 100)
        projectList().performTouchInput { for (step in 1..8) moveTo(lerp(start, carried, step / 8f)) }
        pass(800)
        assertThat(onScreen(EDIT_PROJECT)).isFalse()
        capture(frame)
        projectList().performTouchInput { up() }
    }

    /** Where the composer's sides are (grey), and where the list under it starts, [CursorDimens.recentRowInset] inside them (accent). */
    @Composable
    private fun InsetGuides() {
        val colors = CursorTheme.colors
        Canvas(Modifier.fillMaxSize()) {
            for ((inset, color) in listOf(PAGE_SIDE to colors.textTertiary, PAGE_SIDE + CursorDimens.recentRowInset to colors.accent)) {
                val x = inset.toPx()
                for (at in listOf(x, size.width - x)) drawLine(color, Offset(at, 0f), Offset(at, size.height), strokeWidth = 1.dp.toPx())
            }
        }
    }

    @Test
    fun recentPhoneDark() = recentPage(ThemeMode.Dark, tablet = false, "263_new_chat_recent_phone_dark")

    @Test
    @Config(sdk = [35], qualifiers = PHONE_LIGHT)
    fun recentPhoneLight() = recentPage(ThemeMode.Light, tablet = false, "264_new_chat_recent_phone_light")

    @Test
    fun projectsPhoneDark() = projectsPage(ThemeMode.Dark, tablet = false, "265_new_chat_projects_phone_dark")

    @Test
    @Config(sdk = [35], qualifiers = PHONE_LIGHT)
    fun projectsPhoneLight() = projectsPage(ThemeMode.Light, tablet = false, "266_new_chat_projects_phone_light")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_DARK)
    fun recentTabletDark() = recentPage(ThemeMode.Dark, tablet = true, "267_new_chat_recent_tablet_dark")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_LIGHT)
    fun recentTabletLight() = recentPage(ThemeMode.Light, tablet = true, "268_new_chat_recent_tablet_light")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_DARK)
    fun projectsTabletDark() = projectsPage(ThemeMode.Dark, tablet = true, "269_new_chat_projects_tablet_dark")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_LIGHT)
    fun projectsTabletLight() = projectsPage(ThemeMode.Light, tablet = true, "270_new_chat_projects_tablet_light")

    @Test
    fun composerPhoneDark() = composerPage(ThemeMode.Dark, tablet = false, "339_new_chat_composer_phone_dark")

    @Test
    @Config(sdk = [35], qualifiers = PHONE_LIGHT)
    fun composerPhoneLight() = composerPage(ThemeMode.Light, tablet = false, "340_new_chat_composer_phone_light")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_DARK)
    fun composerTabletDark() = composerPage(ThemeMode.Dark, tablet = true, "341_new_chat_composer_tablet_dark")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_LIGHT)
    fun composerTabletLight() = composerPage(ThemeMode.Light, tablet = true, "342_new_chat_composer_tablet_light")

    /** The recent chats' cards and the Project shortcuts start the same way inside the composer's sides. */
    @Test
    fun recentInsetGuides() {
        showPage(NewChatHome.RECENT, overlay = { InsetGuides() })
        compose.waitUntil(10_000) { onScreen("Revenue Scaling Pipeline Research") }
        capture("347_new_chat_inset_guides_recent")
    }

    @Test
    fun projectsInsetGuides() {
        showPage(NewChatHome.PROJECTS, overlay = { InsetGuides() })
        compose.waitUntil(10_000) { shortcuts() == 5 }
        capture("348_new_chat_inset_guides_projects")
    }

    @Test
    fun projectMenuPhoneDark() = projectMenu(ThemeMode.Dark, "349_new_chat_project_menu_phone_dark")

    @Test
    @Config(sdk = [35], qualifiers = PHONE_LIGHT)
    fun projectMenuPhoneLight() = projectMenu(ThemeMode.Light, "350_new_chat_project_menu_phone_light")

    @Test
    fun projectsReorderingPhoneDark() = projectsReordering(ThemeMode.Dark, tablet = false, "351_new_chat_projects_reordering_phone_dark")

    @Test
    @Config(sdk = [35], qualifiers = PHONE_LIGHT)
    fun projectsReorderingPhoneLight() = projectsReordering(ThemeMode.Light, tablet = false, "352_new_chat_projects_reordering_phone_light")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_DARK)
    fun projectsReorderingTabletDark() = projectsReordering(ThemeMode.Dark, tablet = true, "353_new_chat_projects_reordering_tablet_dark")

    /** Projects chosen with Extended mode off: the note and its way to Settings, the recent chats under it. */
    @Test
    fun projectsNeedExtendedMode() {
        showPage(NewChatHome.PROJECTS, projectsAvailable = false)
        compose.waitUntil(10_000) { onScreen(NewChatHomeCopy.NEEDS_EXTENDED_TITLE) && onScreen(NewChatHomeFixtures.NEWEST_CHAT) }
        assertThat(shortcuts()).isEqualTo(0)
        capture("271_new_chat_projects_needs_extended")
    }

    /** Projects chosen before the first Project: the note offering one, the recent chats under it. */
    @Test
    fun projectsNoneYet() {
        showPage(NewChatHome.PROJECTS, list = NewChatHomeFixtures.withoutProjects())
        compose.waitUntil(10_000) { onScreen(NewChatHomeCopy.NO_PROJECTS_TITLE) && onScreen(NewChatHomeFixtures.NEWEST_CHAT) }
        assertThat(shortcuts()).isEqualTo(0)
        capture("272_new_chat_projects_none_yet")
    }

    private fun showSettings(mode: ThemeMode = ThemeMode.Dark, tablet: Boolean = false, isDemo: Boolean = true) {
        val list = NewChatHomeFixtures.list()
        compose.setContent {
            Scene(mode) {
                if (tablet) {
                    Tablet(SidebarDestination.Settings, list) { modifier ->
                        Box(modifier) { SettingsScreen(graph, DEMO_USER, isDemo = isDemo, onOpenSidebar = null, onBack = null, newChatList = list) }
                    }
                } else {
                    // As on a phone: the back chevron, and the pane's header over the miniatures.
                    SettingsScreen(graph, DEMO_USER, isDemo = isDemo, onOpenSidebar = {}, onBack = {}, newChatList = list)
                }
            }
        }
        compose.waitUntil(30_000) { chosen(NewChatHome.RECENT) }
        compose.scrollSettingsGroupToTop(NewChatHomePickerCopy.GROUP)
    }

    private fun chosen(home: NewChatHome) = compose.onAllNodes(hasTestTag(NewChatHomePickerTags.of(home)) and isSelected()).fetchSemanticsNodes().isNotEmpty()

    private fun choose(home: NewChatHome) {
        compose.onNodeWithTag(NewChatHomePickerTags.of(home)).performClick()
        compose.waitUntil(10_000) { runBlocking { graph.prefs.newChatHome.first() } == home }
        compose.waitUntil(10_000) { chosen(home) }
    }

    private fun recentChosen(mode: ThemeMode, tablet: Boolean, frame: String) {
        showSettings(mode, tablet)
        assertThat(runBlocking { graph.prefs.newChatHome.first() }).isEqualTo(NewChatHome.RECENT)
        capture(frame)
    }

    private fun layoutChosen(home: NewChatHome, mode: ThemeMode, tablet: Boolean, frame: String) {
        showSettings(mode, tablet)
        choose(home)
        capture(frame)
    }

    private fun projectsChosen(mode: ThemeMode, tablet: Boolean, frame: String) = layoutChosen(NewChatHome.PROJECTS, mode, tablet, frame)

    @Test
    fun settingsRecentPhoneDark() = recentChosen(ThemeMode.Dark, tablet = false, "273_settings_new_chat_recent_phone_dark")

    @Test
    @Config(sdk = [35], qualifiers = PHONE_LIGHT)
    fun settingsRecentPhoneLight() = recentChosen(ThemeMode.Light, tablet = false, "274_settings_new_chat_recent_phone_light")

    @Test
    fun settingsProjectsPhoneDark() = projectsChosen(ThemeMode.Dark, tablet = false, "275_settings_new_chat_projects_phone_dark")

    @Test
    @Config(sdk = [35], qualifiers = PHONE_LIGHT)
    fun settingsProjectsPhoneLight() = projectsChosen(ThemeMode.Light, tablet = false, "276_settings_new_chat_projects_phone_light")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_DARK)
    fun settingsRecentTabletDark() = recentChosen(ThemeMode.Dark, tablet = true, "277_settings_new_chat_recent_tablet_dark")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_LIGHT)
    fun settingsRecentTabletLight() = recentChosen(ThemeMode.Light, tablet = true, "278_settings_new_chat_recent_tablet_light")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_DARK)
    fun settingsProjectsTabletDark() = projectsChosen(ThemeMode.Dark, tablet = true, "279_settings_new_chat_projects_tablet_dark")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_LIGHT)
    fun settingsProjectsTabletLight() = projectsChosen(ThemeMode.Light, tablet = true, "280_settings_new_chat_projects_tablet_light")

    @Test
    fun settingsComposerPhoneDark() = layoutChosen(NewChatHome.COMPOSER, ThemeMode.Dark, tablet = false, "343_settings_new_chat_composer_phone_dark")

    @Test
    @Config(sdk = [35], qualifiers = PHONE_LIGHT)
    fun settingsComposerPhoneLight() = layoutChosen(NewChatHome.COMPOSER, ThemeMode.Light, tablet = false, "344_settings_new_chat_composer_phone_light")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_DARK)
    fun settingsComposerTabletDark() = layoutChosen(NewChatHome.COMPOSER, ThemeMode.Dark, tablet = true, "345_settings_new_chat_composer_tablet_dark")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_LIGHT)
    fun settingsComposerTabletLight() = layoutChosen(NewChatHome.COMPOSER, ThemeMode.Light, tablet = true, "346_settings_new_chat_composer_tablet_light")

    /** Outside the demo with Extended mode off: Projects chosen, its miniature the note over the recent chats, and the row saying why. */
    @Test
    fun settingsProjectsNeedExtendedMode() {
        showSettings(isDemo = false)
        choose(NewChatHome.PROJECTS)
        compose.onNodeWithText(NewChatHomePickerCopy.NEEDS_MODE).performScrollTo()
        compose.waitForIdle()
        capture("281_settings_new_chat_needs_extended")
    }

    private companion object {
        /** The demo session's own account, as the shell shows it. */
        val DEMO_USER = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null)
        val ROW_ACTIONS = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})

        /** As the shell hands the page its rows' actions: a Project's menu leads with Edit Project. */
        val PROJECT_ROW_ACTIONS = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, onEditProject = {})
        const val EDIT_PROJECT = "Edit Project"
        const val FRAME_MILLIS = 16L
        val LONG_PRESS = ViewConfiguration.getLongPressTimeout().toLong()

        /** The pane's list stands this far in from its sides; the composer fills what is left. */
        val PAGE_SIDE = 16.dp

        /** The chip the demo account's newest chat puts on a new chat's composer: the page has settled once it reads this. */
        const val MODEL_CHIP = "Claude Fable 5.1"
    }
}

private const val PHONE_DARK = "w411dp-h914dp-night-420dpi"
private const val PHONE_LIGHT = "w411dp-h914dp-notnight-420dpi"
private const val TABLET_DARK = "w1000dp-h720dp-night-320dpi"
private const val TABLET_LIGHT = "w1000dp-h720dp-notnight-320dpi"
