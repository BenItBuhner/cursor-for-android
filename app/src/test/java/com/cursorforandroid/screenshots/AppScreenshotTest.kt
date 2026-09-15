package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.EnvironmentFilter
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.SourceFilter
import com.cursorforandroid.domain.StatusFilter
import com.cursorforandroid.ui.CursorRoot
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * Drives the real app (demo backend) through Robolectric's native renderer and writes PNGs to `screenshots/`.
 * Run with `./gradlew :app:recordRoborazziDebug --tests '*AppScreenshotTest*'`; CI verifies against the committed
 * PNGs with `verifyRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class AppScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    // The demo data is generated relative to "now" and the lists render "4m" / "3h"-style ages from it, so the clock,
    // zone and locale are pinned to keep every pixel reproducible across machines and times of day.
    @Before
    fun pinClock() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { FIXED_NOW }
    }

    @After
    fun restoreClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    /**
     * Robolectric has no Android Keystore, so the real [SecureKeyStore] would report itself unavailable and every
     * capture would carry the warning a device never shows. An ordinary private file stands in for the encrypted one.
     */
    private fun appGraph(): AppGraph {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) })
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App(graph: AppGraph) {
        var pending by remember { mutableStateOf<String?>(null) }
        val mode by graph.prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.Dark)
        val oledBlack by graph.prefs.oledBlack.collectAsStateWithLifecycle(initialValue = false)
        CursorTheme(mode = if (mode == ThemeMode.System) ThemeMode.Dark else mode, oledBlack = oledBlack) {
            // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                CursorRoot(graph = graph, deepLinkAgentId = pending, onDeepLinkConsumed = { pending = null })
            }
        }
    }

    /**
     * Builds the app graph, decides the session, then composes the app. The session is decided first on purpose: the
     * test rule runs effects on an unconfined dispatcher, so `CursorRoot`'s `LaunchedEffect { restoreIfNeeded() }`
     * would flip the session to signed-out on the IO worker it returns from — while the first composition is still being
     * applied — and about one run in five Compose never saw that write: the sign-in screen stayed blank and the run's
     * first `waitForText` timed out. With the session already decided, the sign-in screen is composed on the first pass
     * (as it is in a process whose session was decided before its activity) and `restoreIfNeeded()` is a no-op.
     */
    private fun launchApp(): AppGraph {
        val graph = appGraph()
        runBlocking { graph.session.restoreIfNeeded() }
        compose.setContent { App(graph) }
        return graph
    }

    /**
     * The sidebar's sections are organized off the main thread from the list; a frame taken as soon as the rows are
     * there can predate the Projects group. Its header is the last thing to appear, so it is what a capture waits on.
     */
    /** The sidebar's list, told from the recent list by the section headers only it has. */
    private val sidebarList: SemanticsMatcher = hasScrollToNodeAction() and hasAnyDescendant(hasText("Pinned") or hasText("Today"))

    /**
     * The sidebar's sections are organized off the main thread from the list, and the list keeps the row that was
     * first in view where it is when rows land above it — the Projects group arrives with the publish that completes
     * the load, above "Pinned" — so a sidebar composed while the list was still loading can sit past its own top.
     * The frames show the top: put the list there, then wait for the header that lands last.
     */
    private fun waitForSidebarSections() {
        compose.waitUntil(30_000) { compose.onAllNodes(sidebarList).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(sidebarList).onFirst().performScrollToIndex(0)
        waitForText("Projects", 30_000)
    }

    private fun waitForText(text: String, timeoutMillis: Long = 20_000) {
        compose.waitUntil(timeoutMillis) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * The home screen's list of recent chats. The sidebar's list is composed as well — closed, or permanent on a
     * tablet, where it comes first — and scrolling that one instead moved its Projects group out of the frames that
     * show it; the sidebar is the list with section headers, the recent list has none.
     */
    private val homeList: SemanticsMatcher =
        hasScrollToNodeAction() and !hasAnyDescendant(hasText("Projects") or hasText("Pinned") or hasText("Today") or hasText("Yesterday") or hasText("Older"))

    private fun scrollListTo(text: String, timeoutMillis: Long = 30_000) {
        compose.waitUntil(timeoutMillis) { compose.onAllNodes(homeList).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(timeoutMillis) {
            runCatching { compose.onAllNodes(homeList).onFirst().performScrollToNode(hasText(text, substring = true)) }.isSuccess
        }
        waitForText(text, timeoutMillis)
    }

    private fun enterDemo(graph: AppGraph, repoChip: String = "codex-poly-bot") {
        waitForText("Try the demo")
        compose.onNodeWithText("Try the demo").performClick()
        compose.waitUntil(20_000) { graph.session.state.value is SessionState.SignedIn }
        waitForText("Ask Cursor to build, fix bugs, explore", 30_000)
        // Repositories and models load in parallel; both selectors must have settled before a capture. The model chip
        // reads the model's name alone (its variant's parameters live in the picker) — the account's newest chat's
        // model, Claude Fable 5.1, since nothing was picked on this install; the repo chip is matched exactly because
        // the slug also occurs inside a workspace name in the list.
        waitForText("Claude Fable 5.1", 30_000)
        compose.waitUntil(30_000) { compose.onAllNodesWithText(repoChip).fetchSemanticsNodes().isNotEmpty() }
        // Rows are published page by page; what the demo's account list says about them — which chats are Projects,
        // which hang off which — lands with the publish that completes the fetch.
        compose.waitUntil(30_000) { graph.agents.state.value.let { it.hasLoaded && !it.isRefreshing } }
        // A pinned chat, so it sits near the top of the sidebar's list whether the drawer is open or not; nothing is
        // scrolled to find it — the recent list has it a day down, and the home frames start at the composer.
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Cesium Revenue Strategy").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun phoneWalkthrough() {
        val graph = launchApp()

        // The account sign-in is the one primary action; the pasted-key field sits folded behind "Use an API key instead".
        waitForText("Continue with Cursor")
        capture("01_sign_in")

        // Home: composer on top, recent chats below.
        enterDemo(graph)
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Ask Cursor to build, fix bugs, explore"))
        compose.waitForIdle()
        capture("02_home")

        // Device picker: Cloud is the default; My machines and team pools sit under it. This phone is never a row.
        compose.onNodeWithText("Cloud").performClick()
        waitForText("My machines")
        waitForText("bennett")
        capture("24_device_picker")
        Espresso.pressBack()
        compose.waitForIdle()

        // The composer's "+" menu (Multitask / Files / Skills / MCP Servers) and its Skills page.
        compose.onNodeWithContentDescription("Add to prompt").performClick()
        waitForText("Orchestrate multiple subagents in parallel")
        capture("12_composer_menu")
        compose.onNodeWithText("Skills").performClick()
        waitForText("/autopilot")
        capture("13_composer_skills")
        Espresso.pressBack() // dismiss the popup
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("/autopilot")).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()

        // Sidebar drawer: the header's "+" is the new-chat button.
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("New chat")).fetchSemanticsNodes().isNotEmpty() }
        waitForSidebarSections()
        capture("03_sidebar")

        // Chats filter sheet from the header's filter icon. The root page is 04 (chatsFilterReadAll).
        compose.onNodeWithContentDescription("Filter and group chats").performClick()
        waitForText("Grouping")
        compose.onNodeWithText("Status").performClick()
        waitForText("Archived")
        capture("05_status_filter")
        Espresso.pressBack()
        waitForText("Grouping")
        // Source: where each chat was started (the account's word), as on cursor.com/agents; Environment: where it runs.
        compose.onNodeWithText("Source").performClick()
        waitForText("Grok Bot")
        capture("25_source_filter")
        Espresso.pressBack()
        waitForText("Grouping")
        compose.onNodeWithText("Environment").performClick()
        waitForText("Team pool")
        capture("26_environment_filter")
        Espresso.pressBack()
        waitForText("Grouping")
        // The Source filter at work: only the chats started from Slack, the CLI and Grok Bot are left among the
        // sidebar's own chats — the pinned ones stay whatever the filter says, the Editor-started machine chat among them.
        runBlocking { graph.prefs.updateListPreferences { it.copy(sources = setOf(SourceFilter.Slack, SourceFilter.Cli, SourceFilter.GrokBot)) } }
        waitForText("CLI +2")
        Espresso.pressBack() // dismiss the sheet
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("House environment overhaul").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Zen browser flawless parity").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Codex-Poly-Bot Scaling").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        capture("27_sidebar_source_filtered")
        // The Status filter without Running and the Environment filter without the user's machines: the running chat
        // of the account's own leaves the list; the pinned ones running — the one on the user's machine among them —
        // do not. A pin is shown regardless of paging, source, environment or state.
        runBlocking { graph.prefs.updateListPreferences { it.copy(sources = SourceFilter.entries.toSet(), statuses = ListPreferences().statuses - StatusFilter.Running, environments = setOf(EnvironmentFilter.Cloud, EnvironmentFilter.Pool)) } }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Hyper-realistic human limbs").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("House environment overhaul").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Codex-Poly-Bot Scaling").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        capture("60_sidebar_pinned_and_running")
        runBlocking { graph.prefs.updateListPreferences { it.copy(statuses = ListPreferences().statuses, environments = EnvironmentFilter.entries.toSet()) } }
        waitForText("Hyper-realistic human limbs")
        Espresso.pressBack() // close the drawer
        compose.waitForIdle()

        // Conversation with a live-streamed run.
        scrollListTo("Cesium Revenue Strategy")
        compose.onAllNodesWithText("Cesium Revenue Strategy").onFirst().performClick()
        waitForText("Follow up")
        val cesiumId = graph.agents.state.value.agents.first { it.name == "Cesium Revenue Strategy" }.id
        compose.waitUntil(90_000) { graph.conversations.state(cesiumId).value.items.any { it is RunFooter } }
        compose.waitForIdle()
        capture("06_conversation")

        // A finished reply that embeds a screenshot and a recording by their /opt/cursor/artifacts paths: the image
        // is fetched through the (demo) artifact download endpoint, the video shows its poster card.
        Espresso.pressBack()
        compose.waitForIdle()
        scrollListTo("Android mobile experience")
        compose.onAllNodesWithText("Android mobile experience").onFirst().performClick()
        waitForText("Follow up")
        // The finished run's trace is replayed a beat after the transcript; wait for it too, so what is captured is
        // the settled list rather than whichever of the trace and the media happened to land first.
        val mobileId = graph.agents.state.value.agents.first { it.name == "Android mobile experience" }.id
        compose.waitUntil(30_000) { graph.conversations.state(mobileId).value.items.any { it is ActivityGroup } }
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("Sidebar drawer mid-gesture")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("Video: predictive_back_demo.mp4")).fetchSemanticsNodes().isNotEmpty() }
        // The reply is taller than the viewport; line its first paragraph up with the top so the figure is in frame.
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Predictive back was missing", substring = true))
        compose.waitForIdle()
        capture("11_conversation_media")

        // Settings + light theme.
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        waitForText("Demo User")
        compose.onNodeWithText("Demo User").performClick()
        waitForText("Appearance")
        compose.onNodeWithText("Cursor Light").performClick()
        // Light hides the OLED row. Wait on that, not DataStore: runBlocking { prefs.first() } inside waitUntil
        // hops off the main thread on Robolectric's unconfined effects and tears the slot table.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("OLED black", substring = true)).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        capture("07_settings_light")
        compose.onNodeWithContentDescription("Back").performClick()
        scrollListTo("Ask Cursor to build, fix bugs, explore")
        capture("08_home_light")

        // Last, because opening it lets its live run finish, which would reorder the home list captured above. Back
        // in the dark theme: a chat with a goal on it. The turns Cursor started by itself — the goal picked up again,
        // a subagent reporting back — reach the transcript as user messages made of markup; each is one row that
        // opens onto the objective or the report. The live run plays out first, and the earlier turns' traces are
        // replayed alongside it.
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        waitForText("Demo User")
        compose.onNodeWithText("Demo User").performClick()
        waitForText("Appearance")
        compose.onNodeWithText("Cursor Dark").performClick()
        waitForText("True-black surfaces instead of Cursor Dark's charcoal.")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back").performClick()

        // Typing `/` opens the slash-command popover under the cursor: "/go" narrows it to the goal command and the
        // demo's plugin skill whose description mentions Google Chat, as on cursor.com/agents. Picking a row completes
        // the token and closes the popover. Done here, with no capture of this composer to follow: typing leaves the
        // field focused, and its focus ring would otherwise show in the later home captures.
        scrollListTo("Ask Cursor to build, fix bugs, explore")
        val composerField = compose.onAllNodes(hasSetTextAction()).onFirst()
        composerField.performTextInput("/go")
        waitForText("Set a goal that Cursor will pursue")
        waitForText("/chat-sdk")
        capture("24_composer_slash")
        compose.onNodeWithText("/goal").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Set a goal that Cursor will pursue")).fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("/goal ")).fetchSemanticsNodes().isNotEmpty() }
        composerField.performTextClearance()
        compose.waitForIdle()

        scrollListTo("Hyper-realistic human limbs")
        compose.onAllNodesWithText("Hyper-realistic human limbs").onFirst().performClick()
        waitForText("Follow up")
        val limbsId = graph.agents.state.value.agents.first { it.name == "Hyper-realistic human limbs" }.id
        compose.waitUntil(90_000) {
            val items = graph.conversations.state(limbsId).value.items
            items.count { it is RunFooter } == 4 && items.count { it is ActivityGroup } == 4
        }
        compose.waitForIdle()
        compose.onNodeWithText("Goal continued").performClick()
        waitForText("In the Verity photoreal engine")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Goal continued"))
        compose.waitForIdle()
        capture("23_conversation_notifications")
        // Leave the chat, so nothing of it is still streaming when the next test brings up its own app.
        Espresso.pressBack()
        compose.waitForIdle()
    }

    /**
     * The device drives the repository: the composer opens on the repository last launched on Cloud, and picking the
     * machine "bennett" — checked out at bennett/codex-poly-bot per `GET /v0/private-workers` — moves the repository
     * chip there with the branch list; the repository picker then explains whose the repository is. The model chip
     * reads the account's newest chat's model the whole time, never a bare "Model".
     */
    @Test
    fun deviceDrivesRepository() {
        val graph = launchApp()
        // The last launch on Cloud was against cursor-for-android: that is what the composer opens on.
        runBlocking { graph.prefs.setComposerDefaults(repoUrl = "https://github.com/bennett/cursor-for-android", ref = "", modelId = null, params = emptyMap(), autoCreatePr = false) }
        enterDemo(graph, repoChip = "cursor-for-android")

        compose.onNodeWithText("Cloud").performClick()
        waitForText("My machines")
        compose.onNodeWithText("bennett").performClick()
        // The sheet closes and the repository chip follows the machine's checkout.
        compose.waitUntil(20_000) { compose.onAllNodesWithText("My machines").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("codex-poly-bot").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Ask Cursor to build, fix bugs, explore"))
        compose.waitForIdle()
        capture("69_composer_device_repo")

        compose.onNodeWithText("codex-poly-bot").performClick()
        waitForText("bennett is checked out at bennett/codex-poly-bot", 20_000)
        capture("70_composer_device_repo_sheet")
        Espresso.pressBack()
        compose.waitForIdle()

        // Back on Cloud, the repository chosen there is back.
        compose.onNodeWithText("bennett").performClick()
        waitForText("Cursor-hosted VM")
        compose.onNodeWithText("Cloud").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Cursor-hosted VM").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("cursor-for-android").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun chatsFilterReadAll() {
        val graph = launchApp()
        enterDemo(graph)
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("New chat")).fetchSemanticsNodes().isNotEmpty() }
        waitForSidebarSections()
        compose.onNodeWithContentDescription("Filter and group chats").performClick()
        // The Actions card leads the sheet: Read all, with the unread count under it.
        waitForText("Read all")
        waitForText("unread chats")
        capture("04_chats_filter")

        compose.onNodeWithText("Read all").performClick()
        // The row's own word is the UI's word that the write landed. Do not read DataStore from waitUntil:
        // that blocks the main thread and the mark-all coroutine never finishes.
        waitForText("Nothing unread", 10_000)
        compose.waitForIdle()
        // Nothing left to read: the action stays listed, dimmed, so the sheet reads the same either way.
        capture("58_chats_filter_all_read")
        Espresso.pressBack()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Grouping").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        capture("28_sidebar_all_read")
    }

    @Test
    fun oledBlack() {
        val graph = launchApp()
        enterDemo(graph)
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        waitForText("Demo User")
        compose.onNodeWithText("Demo User").performClick()
        waitForText("Appearance")
        compose.onNodeWithText("Cursor Dark").performClick()
        // The preference is one thing, the screen having recomposed from it another: wait for the copy that only the
        // dark theme shows, or a slow runner captures "Match system" still checked.
        waitForText("True-black surfaces instead of Cursor Dark's charcoal.")
        capture("20_settings_dark")
        compose.onNodeWithText("OLED black").performClick()
        // The switch in the OLED row (its row merges the label into its semantics) reads on once the screen has caught up.
        compose.waitUntil(10_000) {
            compose.onAllNodes(isOn() and hasAnyAncestor(hasText("OLED black", substring = true))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        capture("21_settings_oled")
        compose.onNodeWithContentDescription("Back").performClick()
        scrollListTo("Ask Cursor to build, fix bugs, explore")
        capture("22_home_oled")
    }

    @Test
    fun projectView() {
        val graph = launchApp()
        enterDemo(graph)
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("New chat")).fetchSemanticsNodes().isNotEmpty() }
        waitForSidebarSections()
        // The Project's row in the sidebar (the recent card behind the drawer names it too) opens the coordinator's
        // chat: a Project coordinator's transcript, whose steps are the workers it created (a card each), the status
        // check, its message to a worker, its own words to the user, and the worker's completion notice that
        // started the turn. There is no Project view in between.
        compose.onNode(hasText("Cesium billing launch") and hasAnyAncestor(sidebarList)).performClick()
        // The coordinator's SendMessage update reads as a plain reply: its own text is what says the chat is open.
        waitForText("PR #215 (usage aggregation) is", 30_000)
        compose.waitUntil(30_000) { graph.conversations.state(DemoData.PROJECT_ID).value.items.count { it is ActivityGroup } >= 2 }
        // Opening the row marked the Project read (AppNavHost's row actions); wait for that write so nothing about the
        // frames depends on catching it. Observed from a background collector: a runBlocking read inside waitUntil
        // would hold the looper the store's emission needs.
        val markers = AtomicReference<Map<String, Long>>(emptyMap())
        val watching = CoroutineScope(Dispatchers.IO).launch { graph.prefs.localAgentState.collect { markers.set(it.readMarkers) } }
        try {
            compose.waitUntil(20_000) { markers.get().containsKey(DemoData.PROJECT_ID) }
        } finally {
            watching.cancel()
        }
        // The earlier turn's worker cards sit above the current turn; bring the first of them into the frame.
        compose.onAllNodes(hasScrollToNodeAction() and hasAnyDescendant(hasText("PR #215 (usage aggregation) is", substring = true))).onFirst().performScrollToNode(hasTestTag("worker-card"))
        compose.waitForIdle()
        capture("39_project_coordinator_transcript")
        // The Project itself is the chat's panel. A coordinator's opens on the Project panel (the notes, as on
        // cursor.com); the chat's own sections are the panel's other surface, reached from the Agents pill, with the
        // Project section open by default right under the Overview — the primaries with their status and menus, New
        // primary and Adopt a chat (the demo stands in for the account, so its actions are offered), and the shared
        // context a tap away.
        compose.onNodeWithContentDescription("Open panel").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("project-notes-tab")).fetchSemanticsNodes().isNotEmpty() }
        waitForText("Shipping", 30_000)
        // The chat's own sections are the panel's other surface: the Agents pill above the composer opens them on the
        // Project section, as the web's pill opens the Project's agents.
        compose.onNodeWithContentDescription("Close panel").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("conversation-panel")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("pill-agents").performClick()
        waitForText("Stripe webhook handler")
        waitForText("New primary")
        waitForText("Show shared context")
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("section-Header"))
        compose.waitForIdle()
        capture("38_project_panel_section")
        Espresso.pressBack()
        compose.waitForIdle()
        Espresso.pressBack()
        compose.waitForIdle()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h720dp-night-320dpi")
    fun tabletTwoPane() {
        val graph = launchApp()
        enterDemo(graph)
        waitForSidebarSections()
        capture("09_tablet_home")
        compose.onAllNodesWithText("Revenue Scaling Pipeline Research").onFirst().performClick()
        waitForText("Worked", 30_000)
        // The finished run's thinking / tool / subagent trace is replayed from its retained stream a beat after the
        // transcript; capture once it has been spliced in.
        val revenueId = graph.agents.state.value.agents.first { it.name == "Revenue Scaling Pipeline Research" }.id
        compose.waitUntil(30_000) { graph.conversations.state(revenueId).value.items.any { it is ActivityGroup } }
        compose.waitForIdle()
        capture("10_tablet_conversation")
    }

    private companion object {
        /** Wednesday 2025-01-15 14:00 UTC; demo ages are whole minutes, so rendered times land on exact minutes. */
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
    }
}
