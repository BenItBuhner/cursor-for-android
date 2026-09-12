package com.cursorforandroid.ui.panel

import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentDiff
import com.cursorforandroid.domain.AgentDiffFile
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentUsage
import com.cursorforandroid.domain.Artifact
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ChangedFile
import com.cursorforandroid.domain.ChangedFileStatus
import com.cursorforandroid.domain.CheckConclusion
import com.cursorforandroid.domain.CheckRun
import com.cursorforandroid.domain.CheckStatus
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.GitBranch
import com.cursorforandroid.domain.MachineStatus
import com.cursorforandroid.domain.PullRequestDetails
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.PullRequestView
import com.cursorforandroid.domain.RepoContents
import com.cursorforandroid.domain.RepoEntry
import com.cursorforandroid.domain.Review
import com.cursorforandroid.domain.ReviewComment
import com.cursorforandroid.domain.ReviewThread
import com.cursorforandroid.domain.ReviewVerdict
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.RunUsage
import com.cursorforandroid.domain.ScmHost
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.TokenUsage
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptContent
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.domain.WorkspaceTree

/** Synthetic panel states for the Compose and screenshot tests: one agent, its transcript's payloads, a pull request. */
object PanelFixtures {
    const val NOW = 1_736_949_600_000L
    private const val HOUR = 60 * 60_000L

    val agent = Agent(
        id = "bc-demo",
        name = "Dark theme toggle for Settings",
        lifecycle = AgentLifecycle.ACTIVE,
        runStatus = RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/bc-demo",
        createdAtMillis = NOW - 3 * HOUR,
        updatedAtMillis = NOW - HOUR,
        latestRunId = "run-2",
        repoUrl = "https://github.com/bennett/cursor-for-android",
        startingRef = "main",
        branches = listOf(GitBranch("https://github.com/bennett/cursor-for-android", "cursor/theme-toggle-4f2a", "https://github.com/bennett/cursor-for-android/pull/97")),
        modelDisplayName = "Claude Fable 5.1",
        durationMs = 34 * 60_000L,
    )

    val settingsDiff = ToolPayload.FileDiff(
        path = "app/src/main/java/com/cursorforandroid/ui/settings/SettingsScreen.kt",
        diff = "@@ -212,7 +212,9 @@ fun AppearanceSection(prefs: PreferencesStore) {\n     SettingRow(\"Theme\", mode.label) { themeSheet = true }\n-    if (mode == ThemeMode.Dark) {\n-        ToggleRow(\"OLED black\", oledBlack, prefs::setOledBlack)\n+    // The OLED row only makes sense on a dark surface; hide it rather than grey it out.\n+    if (mode != ThemeMode.Light) {\n+        ToggleRow(\"OLED black\", oledBlack, prefs::setOledBlack)\n+        ToggleRow(\"Dim wallpaper\", dimWallpaper, prefs::setDimWallpaper)\n     }\n }",
        linesAdded = 5,
        linesRemoved = 2,
    )
    val themeRead = ToolPayload.FileContent(
        path = "app/src/main/java/com/cursorforandroid/ui/theme/CursorTheme.kt",
        content = "@Composable\nfun CursorTheme(mode: ThemeMode, oledBlack: Boolean = false, content: @Composable () -> Unit) {\n    val colors = when (mode) {\n        ThemeMode.Light -> LightColors\n        else -> if (oledBlack) OledColors else DarkColors\n    }\n}",
        kind = ToolPayload.FileContent.Kind.Read,
        totalLines = 141,
        fileSize = 5_812,
    )
    val toggleWrite = ToolPayload.FileContent(
        path = "app/src/main/java/com/cursorforandroid/ui/settings/ThemeToggle.kt",
        content = "package com.cursorforandroid.ui.settings\n\n@Composable\nfun ThemeToggle(checked: Boolean, onChange: (Boolean) -> Unit) = CursorToggle(checked, onChange)\n",
        kind = ToolPayload.FileContent.Kind.Written,
        totalLines = 4,
    )

    fun call(id: String, kind: ToolKind, name: String, summary: String, detail: String?, payload: ToolPayload?, status: String = ToolCall.STATUS_COMPLETED, added: Int? = null, removed: Int? = null) =
        ToolCall(id, name, kind, status, summary, detail = detail, payload = payload, linesAdded = added, linesRemoved = removed)

    /** A transcript with a read, an edit, a write, a generated image and a recording. */
    fun items(imageSrc: String? = null): List<TimelineItem> = listOf(
        UserMessage("u1", "Add a dark theme toggle to the settings panel."),
        ActivityGroup(
            "g1",
            listOf(
                call("r1", ToolKind.Read, "read_file", "CursorTheme.kt", themeRead.path, themeRead),
                call("e1", ToolKind.Edit, "edit_file", "SettingsScreen.kt", settingsDiff.path, settingsDiff, added = 5, removed = 2),
                call("w1", ToolKind.Create, "write", "ThemeToggle.kt", toggleWrite.path, toggleWrite, added = 4),
                call("d1", ToolKind.Delete, "delete_file", "OldToggle.kt", "app/src/main/java/com/cursorforandroid/ui/settings/OldToggle.kt", null),
                call("i1", ToolKind.Image, "generate_image", "The new toggle", "The new toggle", ToolPayload.GeneratedImage("/workspace/docs/theme-toggle.png", "The new toggle", src = imageSrc)),
                call("v1", ToolKind.Other, "record_screen", "", null, ToolPayload.Recording("/opt/cursor/artifacts/theme-toggle.mp4", 8_000)),
            ),
        ),
    )

    val pullRequest = PullRequestView(
        details = PullRequestDetails(
            url = "https://github.com/bennett/cursor-for-android/pull/97",
            host = ScmHost.GitHub,
            number = 97,
            title = "Settings: a dark theme toggle beside the theme row",
            body = "## Summary\n\nAdds `ThemeToggle` to the Appearance section and hides the OLED row on the light theme.\n\n- New `ThemeToggle.kt`\n- `SettingsScreen.kt` gains the row\n\n## Test plan\n\n- [x] `SettingsScreenTest` covers both themes",
            state = PullRequestState.Open,
            author = "cursor[bot]",
            headRef = "cursor/theme-toggle-4f2a",
            baseRef = "main",
            additions = 61,
            deletions = 12,
            changedFiles = 3,
            commits = 2,
            createdAtMillis = NOW - 2 * HOUR,
            updatedAtMillis = NOW - HOUR,
            mergeableState = "clean",
            labels = listOf("ui"),
        ),
        files = listOf(
            ChangedFile("app/src/main/java/com/cursorforandroid/ui/settings/SettingsScreen.kt", ChangedFileStatus.Modified, 9, 2, settingsDiff.diff),
            ChangedFile("app/src/main/java/com/cursorforandroid/ui/settings/ThemeToggle.kt", ChangedFileStatus.Added, 52, 0, "@@ -0,0 +1,4 @@\n+package com.cursorforandroid.ui.settings\n+\n+@Composable\n+fun ThemeToggle(checked: Boolean, onChange: (Boolean) -> Unit) = CursorToggle(checked, onChange)"),
            ChangedFile("app/src/main/java/com/cursorforandroid/ui/settings/OldToggle.kt", ChangedFileStatus.Removed, 0, 10, null),
        ),
        checks = listOf(
            CheckRun("Build & lint", CheckStatus.Completed, CheckConclusion.Success, "https://github.com/bennett/cursor-for-android/actions/runs/1", "GitHub Actions"),
            CheckRun("Unit tests", CheckStatus.Completed, CheckConclusion.Success, "https://github.com/bennett/cursor-for-android/actions/runs/2", "GitHub Actions"),
            CheckRun("Screenshot tests", CheckStatus.InProgress, null, "https://github.com/bennett/cursor-for-android/actions/runs/3", "GitHub Actions"),
        ),
        threads = listOf(
            ReviewThread(
                path = "app/src/main/java/com/cursorforandroid/ui/settings/SettingsScreen.kt",
                line = 214,
                comments = listOf(
                    ReviewComment(1, "bennett", "Should the widget follow the toggle too?", NOW - 90 * 60_000L),
                    ReviewComment(2, "cursor[bot]", "It reads the same preference, so yes, on its next refresh.", NOW - 80 * 60_000L),
                ),
            ),
        ),
        reviews = listOf(Review(1, "bennett", ReviewVerdict.Commented, "One question inline.", NOW - 90 * 60_000L)),
    )

    val artifacts = listOf(
        Artifact("artifacts/screenshots/settings-dark.png", 182_400, NOW - 70 * 60_000L),
        Artifact("artifacts/theme-toggle.mp4", 4_100_000, NOW - 65 * 60_000L),
        Artifact("artifacts/notes.md", 2_048, NOW - 60 * 60_000L),
    )

    val usage = AgentUsage(
        total = TokenUsage(inputTokens = 184_200, outputTokens = 21_900, cacheWriteTokens = 12_000, cacheReadTokens = 96_400, totalTokens = 314_500),
        runs = listOf(RunUsage("run-2", TokenUsage(60_000, 8_000, 0, 40_000, 108_000)), RunUsage("run-1", TokenUsage(124_200, 13_900, 12_000, 56_400, 206_500))),
    )

    val repoRoot = RepoContents.Directory(
        "",
        listOf(
            RepoEntry(".github", ".github", isDirectory = true),
            RepoEntry("app", "app", isDirectory = true),
            RepoEntry("gradle", "gradle", isDirectory = true),
            RepoEntry("screenshots", "screenshots", isDirectory = true),
            RepoEntry("README.md", "README.md", isDirectory = false, sizeBytes = 9_812),
            RepoEntry("build.gradle.kts", "build.gradle.kts", isDirectory = false, sizeBytes = 1_204),
            RepoEntry("settings.gradle.kts", "settings.gradle.kts", isDirectory = false, sizeBytes = 612),
        ),
    )

    /** The state of a finished chat with a pull request, everything loaded. */
    fun loaded(imageSrc: String? = null): PanelState = PanelState(
        agentId = agent.id,
        agent = agent,
        runStatus = RunStatus.FINISHED,
        content = TranscriptContent.of(items(imageSrc)),
        capabilities = Capabilities.DOCUMENTED,
        pullRequest = RemoteLoad.Loaded(pullRequest),
        artifacts = RemoteLoad.Loaded(artifacts),
        usage = RemoteLoad.Loaded(usage),
        browser = RepoBrowserState(repoUrl = agent.repoUrl, ref = agent.branchName, host = ScmHost.GitHub, path = "", listing = RemoteLoad.Loaded(repoRoot)),
    )

    /** The same chat before its reads have answered. */
    fun loading(): PanelState = loaded().copy(
        pullRequest = RemoteLoad.Loading,
        artifacts = RemoteLoad.Loading,
        usage = RemoteLoad.Loading,
        browser = RepoBrowserState(repoUrl = agent.repoUrl, ref = agent.branchName, host = ScmHost.GitHub, listing = RemoteLoad.Loading),
    )

    /** A chat with nothing yet: no tool calls, no pull request, no repository. */
    fun empty(): PanelState = PanelState(
        agentId = "bc-empty",
        agent = agent.copy(id = "bc-empty", name = "New chat", branches = emptyList(), repoUrl = null, runStatus = RunStatus.RUNNING),
        runStatus = RunStatus.RUNNING,
        isStreaming = true,
    )

    // ---- Extended mode: the agent's VM and the account -----------------------------------------------------------

    /** The agent's workspace as `ListWorkspaceFiles` would list it. */
    val workspaceTree = WorkspaceTree(
        listOf(
            ".github/workflows/ci.yml",
            "app/build.gradle.kts",
            "app/src/main/java/com/cursorforandroid/ui/settings/SettingsScreen.kt",
            "app/src/main/java/com/cursorforandroid/ui/settings/ThemeToggle.kt",
            "app/src/main/java/com/cursorforandroid/ui/theme/CursorTheme.kt",
            "app/src/main/res/values/strings.xml",
            "gradle/libs.versions.toml",
            "screenshots/01_home.png",
            "README.md",
            "build.gradle.kts",
            "settings.gradle.kts",
        ),
    )

    /** The branch's diff against main as `GetBackgroundComposerDiffDetails` would report it. */
    val branchDiff = AgentDiff(
        branchName = "cursor/theme-toggle-4f2a",
        baseBranch = "main",
        files = listOf(
            AgentDiffFile("app/src/main/java/com/cursorforandroid/ui/settings/SettingsScreen.kt", ChangedFileStatus.Modified, 5, 2, settingsDiff.diff),
            AgentDiffFile("app/src/main/java/com/cursorforandroid/ui/settings/ThemeToggle.kt", ChangedFileStatus.Added, 4, 0, "@@ -0,0 +1,4 @@\n+package com.cursorforandroid.ui.settings\n+\n+@Composable\n+fun ThemeToggle(checked: Boolean, onChange: (Boolean) -> Unit) = CursorToggle(checked, onChange)", modifiedContent = toggleWrite.content),
            AgentDiffFile("app/src/main/java/com/cursorforandroid/ui/settings/OldToggle.kt", ChangedFileStatus.Removed, 0, 10, null, originalContent = "// gone\n"),
            AgentDiffFile("app/src/main/res/drawable/toggle.png", ChangedFileStatus.Added, 0, 0, null),
        ),
    )

    /** The same chat in Extended mode, its workspace listed and its branch diff read, before a pull request exists. */
    fun extended(): PanelState = loaded().copy(
        agent = agent.copy(branches = listOf(GitBranch(agent.repoUrl!!, "cursor/theme-toggle-4f2a", prUrl = null))),
        capabilities = Capabilities.EXTENDED,
        pullRequest = RemoteLoad.Idle,
        diff = RemoteLoad.Loaded(branchDiff),
        workspace = WorkspaceBrowserState(path = "", tree = RemoteLoad.Loaded(workspaceTree)),
    )

    /** A Remote Control chat: one that runs on the user's own machine through Cursor's managed worker. */
    fun remoteControl(): PanelState = loaded().copy(
        agent = agent.copy(id = "bc-machine", envType = EnvType.MACHINE, envName = "studio-mac#/Users/bennett/app", runStatus = RunStatus.RUNNING),
        agentId = "bc-machine",
        runStatus = RunStatus.RUNNING,
        machine = RemoteLoad.Loaded(MachineStatus("studio-mac", connected = true, isInUse = true, activeAgentId = "bc-machine")),
    )
}
