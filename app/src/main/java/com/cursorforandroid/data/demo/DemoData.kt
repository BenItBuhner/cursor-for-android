package com.cursorforandroid.data.demo

import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.AgentEnvDto
import com.cursorforandroid.data.api.dto.RepoConfigDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.RunGitBranchDto
import com.cursorforandroid.data.api.dto.RunGitDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.api.dto.V0SourceDto
import com.cursorforandroid.data.api.dto.V0TargetDto
import com.cursorforandroid.domain.ArtifactPaths
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Sample data modelled on the reference screenshots so the app can be explored without an API key. */
internal object DemoData {

    private val fmt = DateTimeFormatter.ISO_INSTANT
    private fun iso(millis: Long): String = fmt.format(Instant.ofEpochMilli(millis))

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    /** How long the demo keeps a finished run's event log, standing in for the API's stream retention window. */
    const val STREAM_RETENTION_MS: Long = 7 * DAY

    /**
     * One step of a finished seed's retained event log. [Reply] streams the next entry of [Seed.replies], so the
     * replayed trace and the legacy transcript tell the same story.
     */
    sealed interface Step {
        data class Thought(val text: String) : Step
        data class Tool(val name: String, val arg: String) : Step
        data class Delegate(val description: String) : Step
        data object Reply : Step
    }

    private fun thought(text: String) = Step.Thought(text)
    private fun read(path: String) = Step.Tool("read_file", path)
    private fun list(path: String) = Step.Tool("list_dir", path)
    private fun grep(pattern: String) = Step.Tool("grep", pattern)
    private fun search(query: String) = Step.Tool("codebase_search", query)
    private fun web(query: String) = Step.Tool("web_search", query)
    private fun edit(path: String) = Step.Tool("edit_file", path)
    private fun sh(command: String) = Step.Tool("run_terminal_cmd", command)
    private fun delegate(description: String) = Step.Delegate(description)
    private val reply = Step.Reply

    class Seed(
        val id: String,
        val name: String,
        val repo: String?,
        val ref: String = "main",
        val ageMillis: Long,
        val runStatus: String,
        val lifecycle: String = "IDLE",
        val branch: String? = null,
        val prUrl: String? = null,
        val summary: String? = null,
        val env: String = "cloud",
        val envName: String? = null,
        val durationMs: Long? = null,
        val prompt: String,
        val replies: List<String> = emptyList(),
        val autoPr: Boolean = false,
        val liveScript: String? = null,
        /** The finished run's event log; empty for runs that are still live (they record their own) or too old. */
        val trace: List<Step> = emptyList(),
    )

    private const val REPO_CESIUM = "https://github.com/techlitnow/cesium"
    private const val REPO_CODEX = "https://github.com/bennett/codex-poly-bot"
    private const val REPO_VISUAL = "https://github.com/bennett/visual-engine"
    private const val REPO_ANDROID = "https://github.com/bennett/cursor-for-android"
    private const val REPO_ZEN = "https://github.com/bennett/zen-parity"
    private const val REPO_MARKET = "https://github.com/bennett/market-replay"
    private const val ARTIFACT_ROOT = ArtifactPaths.VM_ROOT

    /** A file under the VM's artifacts directory, served from the APK's `assets/demo/` in demo mode. */
    class Artifact(val path: String, val asset: String, val sizeBytes: Long)

    /** Artifacts by agent id, keyed the way `GET /v1/agents/{id}/artifacts` reports them (`artifacts/<name>`). */
    val artifacts: Map<String, List<Artifact>> = mapOf(
        "bc-demo-0012" to listOf(
            Artifact(path = "artifacts/predictive_back_drawer.png", asset = "demo/predictive_back_drawer.png", sizeBytes = 40_040),
            Artifact(path = "artifacts/predictive_back_demo.mp4", asset = "demo/predictive_back_demo.mp4", sizeBytes = 64_479),
        ),
    )

    val seeds: List<Seed> = listOf(
        Seed(
            id = "bc-demo-0001", name = "Codex-Poly-Bot Scaling", repo = REPO_CODEX, ageMillis = 34 * MIN,
            runStatus = "RUNNING", lifecycle = "ACTIVE", env = "machine", envName = "bennett#/home/bennett/projects/codex-poly-bot",
            prompt = "Record the MM2 pre-registration; then scale the poly-bot fleet to 12 workers and verify the catch-up job drains the backlog under 5 minutes.",
            liveScript = "codex",
        ),
        Seed(
            id = "bc-demo-0002", name = "Revenue Scaling Pipeline Research", repo = REPO_CESIUM, ageMillis = 2 * HOUR,
            runStatus = "FINISHED", branch = "cursor/revenue-pipeline-3f2a", prUrl = "https://github.com/techlitnow/cesium/pull/214",
            summary = "Mapped the revenue pipeline surfaces and opened a PR with the metering scaffold.", durationMs = 41 * MIN,
            prompt = "Research how a metering + billing pipeline would attach to the current Convex/Clerk cloud layer and scaffold the entry points.",
            replies = listOf(
                "I inventoried the cloud layer (Convex functions, Clerk identity mapping, the Upstash rendezvous registry) and found three natural metering seams: engine pairing, transcription minutes and first-party agent runs.",
                "Opened a PR with a `usageEvents` table, an internal `recordUsage` mutation and a cron that rolls events into daily aggregates. No billing provider is wired yet — that's a product decision, not a code one.",
            ),
            trace = listOf(
                thought("Metering has to attach where identity and usage already meet: the Convex functions behind pairing, transcription and agent runs. Map those first, then scaffold without picking a billing provider."),
                read("convex/schema.ts"), read("convex/auth.ts"), grep("clerk"), search("where are transcription minutes counted"),
                delegate("Survey Convex functions for usage seams"),
                reply,
                edit("convex/schema.ts"), edit("convex/usage.ts"), edit("convex/crons.ts"), sh("npx convex dev --once"),
                reply,
            ),
        ),
        Seed(
            id = "bc-demo-0003", name = "Cesium Revenue Strategy", repo = REPO_CESIUM, ageMillis = DAY + 4 * HOUR,
            runStatus = "RUNNING", lifecycle = "ACTIVE",
            prompt = "Theorize and strategize as to how we could monetize this as an open-source tool. I am thinking infrastructure, account limitations for servers, potential subsidized LLM usage for the first-party Cesium agent and transcription, and more.\n\nNot quite sure though. Pick this apart so we can make it maximally incentivizing to pay for this and to simultaneously get us a good ROI please.",
            liveScript = "cesium",
        ),
        Seed(
            id = "bc-demo-0004", name = "Cli exploration", repo = REPO_ANDROID, ageMillis = 19 * MIN,
            runStatus = "FINISHED", branch = "cursor/cli-exploration-9c1d", durationMs = 12 * MIN,
            prompt = "Explore how the Cursor CLI resumes cloud agents and summarize the flags worth mirroring in the app.",
            replies = listOf("The CLI resumes with `agent --resume <bc-id>` and only needs the API key. Worth mirroring: `--model`, `--plan`, and `--auto-pr`. I pushed notes to a branch."),
            trace = listOf(
                thought("Read the CLI's own help output before trusting the docs; the resume flags are what the app would mirror."),
                web("cursor cli agent resume cloud agent"), sh("cursor-agent --help"), sh("cursor-agent agent --help"),
                read("app/src/main/java/com/cursorforandroid/ui/compose/NewAgentViewModel.kt"), edit("docs/cli-notes.md"),
                reply,
            ),
        ),
        Seed(
            id = "bc-demo-0005", name = "House environment overhaul", repo = REPO_VISUAL, ageMillis = 30 * MIN,
            runStatus = "FINISHED", branch = "cursor/house-environment-7b3e", prUrl = "https://github.com/bennett/visual-engine/pull/67",
            summary = "Rebuilt the house environment with modular rooms and baked lighting.", durationMs = 58 * MIN,
            prompt = "Overhaul the house environment: modular rooms, baked lighting, and a day/night cycle. Open a PR when done.",
            replies = listOf("Rebuilt the environment into 9 modular room prefabs with a shared material atlas, added baked lighting for both day and night states and wired the cycle to the existing `WorldClock`. PR is open with 67 files changed."),
            autoPr = true,
            trace = listOf(
                thought("The house is one monolithic scene. Split it into room prefabs first, then bake two lighting states and drive the swap from WorldClock instead of a new timer."),
                list("assets/environments/house"), read("src/world/WorldClock.ts"), grep("bakeLighting"), read("assets/environments/house/house.scene"),
                edit("assets/environments/house/rooms/Kitchen.prefab"), edit("assets/environments/house/rooms/Living.prefab"), edit("assets/environments/house/materials/atlas.mat"),
                edit("src/world/lighting/BakedStates.ts"), edit("src/world/WorldClock.ts"), sh("npm run bake -- house --state day,night"), sh("npm run build"),
                reply,
            ),
        ),
        Seed(
            id = "bc-demo-0006", name = "Market replay engine", repo = REPO_MARKET, ageMillis = 52 * MIN,
            runStatus = "ERROR", durationMs = 9 * MIN,
            prompt = "Build a deterministic replay engine over the tick archive with a 10x fast-forward mode.",
            replies = listOf("Set up the replay clock and the archive reader, but the archive's Parquet footer is truncated for 2024-03-11 and the run's disk quota was exhausted while unpacking it. Needs a fresh archive or a bigger machine."),
            trace = listOf(
                thought("Determinism means the clock must be driven by archive timestamps, never wall time. Start with the reader and a virtual clock, then layer fast-forward on top."),
                read("archive/index.json"), list("archive/2024-03"), edit("replay/clock.py"), edit("replay/reader.py"),
                sh("python -m replay.inspect archive/2024-03-11.parquet"), sh("df -h /workspace"),
                reply,
            ),
        ),
        Seed(
            id = "bc-demo-0007", name = "Latest release process", repo = REPO_ANDROID, ageMillis = 3 * HOUR,
            runStatus = "FINISHED", branch = "cursor/release-process-1a2b", prUrl = "https://github.com/bennett/cursor-for-android/pull/3", durationMs = 22 * MIN,
            prompt = "Document the release process and add a GitHub Action that builds a signed release APK on tags.",
            replies = listOf("Added `.github/workflows/release.yml` that assembles a release build on `v*` tags and uploads the APK as a release asset. Signing uses the `ANDROID_KEYSTORE_B64` secret."),
            trace = listOf(
                thought("Check how the existing CI workflow sets up the toolchain so the release workflow reuses it rather than duplicating the SDK steps."),
                list(".github/workflows"), read(".github/workflows/ci.yml"), read("app/build.gradle.kts"), grep("signingConfig"),
                edit(".github/workflows/release.yml"), edit("app/build.gradle.kts"), edit("README.md"), sh("./gradlew :app:assembleRelease --dry-run"),
                reply,
            ),
        ),
        Seed(
            id = "bc-demo-0008", name = "Realistic city apartment scene", repo = REPO_VISUAL, ageMillis = 4 * HOUR,
            runStatus = "FINISHED", branch = "cursor/city-apartment-5d6e", durationMs = 71 * MIN,
            prompt = "Build a realistic city apartment scene with window parallax and evening lighting.",
            replies = listOf("Scene is in with a parallax skyline card behind the windows and a warm evening key light. Pushed to a branch; +4230 −165."),
            trace = listOf(
                thought("Parallax through a window is cheapest as a layered skyline card that shifts with the camera; the evening look comes from one warm key plus cool fill."),
                read("scenes/apartment.scene"), grep("ParallaxLayer"), read("src/render/lights/KeyLight.ts"),
                edit("scenes/apartment.scene"), edit("src/render/ParallaxCard.ts"), edit("assets/skyline/evening.png.meta"), sh("npm run render -- apartment --frames 1"),
                reply,
            ),
        ),
        Seed(
            id = "bc-demo-0009", name = "Projector product demo", repo = REPO_VISUAL, ageMillis = 5 * HOUR,
            runStatus = "FINISHED", branch = "cursor/projector-demo-8f9a", prUrl = "https://github.com/bennett/visual-engine/pull/71", durationMs = 33 * MIN,
            prompt = "Create a product demo scene for the projector with a looping showcase animation.",
            replies = listOf("Added the showcase loop with three camera beats and a captioned overlay. PR open."),
            trace = listOf(
                thought("Three camera beats that loop seamlessly: hero, detail, lens. Reuse the caption overlay from the launch scene."),
                read("scenes/projector.scene"), grep("CaptionOverlay"), edit("scenes/projector.scene"), edit("src/anim/showcase.ts"), sh("npm run preview -- projector"),
                reply,
            ),
        ),
        Seed(
            id = "bc-demo-0010", name = "Hyper-realistic human limbs", repo = REPO_VISUAL, ageMillis = 6 * HOUR,
            runStatus = "RUNNING", lifecycle = "ACTIVE",
            prompt = "Improve limb rigging with realistic muscle deformation on the arm and leg meshes.",
            liveScript = "limbs",
        ),
        Seed(
            id = "bc-demo-0011", name = "Fruit fly brain environment", repo = REPO_MARKET, ageMillis = 8 * HOUR,
            runStatus = "FINISHED", branch = "cursor/fly-brain-2c3d", prUrl = "https://github.com/bennett/market-replay/pull/12", durationMs = 47 * MIN,
            prompt = "Set up a simulation environment for the fruit fly connectome dataset with a Gymnasium-compatible interface.",
            replies = listOf("Environment wraps the connectome graph with a `FlyBrainEnv` Gymnasium class, includes a smoke test and a notebook. PR is open."),
            trace = listOf(
                thought("Gymnasium wants reset/step/observation_space; the connectome graph becomes the state and stimulation the action. Keep the physics out of scope and make the smoke test cheap."),
                web("gymnasium Env interface reset step"), read("data/connectome/README.md"), list("data/connectome"),
                edit("envs/fly_brain.py"), edit("envs/__init__.py"), edit("tests/test_fly_brain.py"), edit("notebooks/fly_brain_tour.ipynb"), sh("pytest tests/test_fly_brain.py -q"),
                reply,
            ),
        ),
        Seed(
            id = "bc-demo-0012", name = "Android mobile experience", repo = REPO_ANDROID, ageMillis = 10 * HOUR,
            runStatus = "FINISHED", branch = "cursor/mobile-experience-4e5f", durationMs = 28 * MIN,
            prompt = "Audit the Android app for gesture navigation gaps and predictive back support.",
            summary = "Predictive back was missing on the drawer and two dialogs; added `enableOnBackInvokedCallback` and migrated to `BackHandler`. Branch pushed.",
            // Written the way a cloud agent reports walkthrough artifacts: raw <img> / <video> tags with VM paths.
            replies = listOf(
                "Predictive back was missing on the drawer and two dialogs; added `enableOnBackInvokedCallback` and migrated to `BackHandler`. Branch pushed.\n\n" +
                    "The drawer now recedes with the gesture instead of snapping shut:\n\n" +
                    "<img alt=\"Sidebar drawer mid-gesture\" src=\"${ARTIFACT_ROOT}predictive_back_drawer.png\" />\n\n" +
                    "<video src=\"${ARTIFACT_ROOT}predictive_back_demo.mp4\"></video>",
            ),
            trace = listOf(
                thought("Anything still overriding onBackPressed opts out of predictive back. Find those first, then check the manifest flag."),
                grep("onBackPressed"), grep("BackHandler"), read("app/src/main/AndroidManifest.xml"),
                edit("app/src/main/AndroidManifest.xml"), edit("app/src/main/java/com/cursorforandroid/ui/agents/Sidebar.kt"), edit("app/src/main/java/com/cursorforandroid/ui/components/CursorSheet.kt"),
                sh("./gradlew :app:lintDebug"),
                reply,
            ),
        ),
        Seed(
            id = "bc-demo-0013", name = "Zen browser flawless parity", repo = REPO_ZEN, ageMillis = 11 * HOUR,
            runStatus = "FINISHED", branch = "cursor/zen-parity-6a7b", durationMs = 19 * MIN,
            prompt = "Compare our tab-group behaviour against Zen browser and list the parity gaps.",
            replies = listOf("Eleven gaps, four of them trivial. Full list with reproduction steps is on the branch in `docs/parity.md`."),
            trace = listOf(
                thought("Compare behaviours, not screenshots: collapse, drag between groups, keyboard cycling, restore on relaunch."),
                web("zen browser tab groups workspaces behaviour"), grep("TabGroup"), read("src/tabs/groups.ts"), read("src/tabs/session-restore.ts"),
                edit("docs/parity.md"),
                reply,
            ),
        ),
        Seed(
            id = "bc-demo-0014", name = "Onboarding copy pass", repo = REPO_CESIUM, ageMillis = DAY + 6 * HOUR,
            runStatus = "FINISHED", branch = "cursor/onboarding-copy-9b8c", prUrl = "https://github.com/techlitnow/cesium/pull/209", durationMs = 15 * MIN,
            prompt = "Tighten the onboarding copy and remove the marketing tone from error states.",
            replies = listOf("Rewrote 23 strings; error states now say what happened and what to do next. PR open."),
            trace = listOf(
                thought("Every error state should answer two questions: what happened, what next. Strip adjectives, keep the action."),
                grep("onboarding"), read("src/i18n/en.json"), grep("Oops"),
                edit("src/i18n/en.json"), edit("src/components/ErrorState.tsx"), edit("src/onboarding/Welcome.tsx"), sh("npm test -- i18n"),
                reply,
            ),
        ),
        Seed(
            id = "bc-demo-0015", name = "Flaky transcription test", repo = REPO_CESIUM, ageMillis = 2 * DAY,
            runStatus = "FINISHED", branch = "cursor/flaky-transcription-0d1e", durationMs = 26 * MIN,
            prompt = "Find out why `transcription.spec.ts` flakes on CI and fix it.",
            replies = listOf("The test raced the WebSocket handshake. Awaiting the `open` event before sending audio fixed it across 50 consecutive runs."),
            trace = listOf(
                thought("A flake that only shows on CI is almost always timing. Reproduce it locally under load before touching the test."),
                read("tests/transcription.spec.ts"), read("src/transcription/socket.ts"), sh("npx playwright test transcription --repeat-each 20"),
                edit("tests/transcription.spec.ts"), sh("npx playwright test transcription --repeat-each 50"),
                reply,
            ),
        ),
        Seed(
            id = "bc-demo-0016", name = "Rendezvous registry cleanup", repo = REPO_CESIUM, ageMillis = 9 * DAY,
            runStatus = "FINISHED", lifecycle = "ARCHIVED", durationMs = 8 * MIN,
            prompt = "Remove the dead code paths in the Upstash rendezvous registry.",
            replies = listOf("Removed 412 lines of unused pairing fallbacks."),
        ),
        Seed(
            id = "bc-demo-0017", name = "Weekly dependency bump", repo = REPO_ANDROID, ageMillis = 16 * DAY,
            runStatus = "FINISHED", branch = "cursor/deps-bump-2f3a", prUrl = "https://github.com/bennett/cursor-for-android/pull/1", durationMs = 6 * MIN,
            prompt = "Bump Gradle plugins and Compose to the latest stable versions.",
            replies = listOf("Bumped AGP, Kotlin and the Compose BOM; build and tests green."),
        ),
    )

    fun agentDto(seed: Seed, now: Long, latestRunId: String): AgentDto = AgentDto(
        id = seed.id,
        name = seed.name,
        status = seed.lifecycle,
        env = AgentEnvDto(type = seed.env, name = seed.envName),
        url = "https://cursor.com/agents/${seed.id}",
        createdAt = iso(now - seed.ageMillis - (seed.durationMs ?: (6 * MIN))),
        updatedAt = iso(now - seed.ageMillis),
        latestRunId = latestRunId,
        repos = seed.repo?.let { listOf(RepoConfigDto(url = it, startingRef = seed.ref)) } ?: emptyList(),
        workOnCurrentBranch = false,
        autoCreatePR = seed.autoPr,
    )

    fun v0Dto(seed: Seed, now: Long): V0AgentDto = V0AgentDto(
        id = seed.id,
        name = seed.name,
        status = seed.runStatus,
        source = seed.repo?.let { V0SourceDto(repository = it, ref = seed.ref) },
        target = V0TargetDto(branchName = seed.branch, url = "https://cursor.com/agents/${seed.id}", prUrl = seed.prUrl, autoCreatePr = seed.autoPr),
        summary = seed.summary ?: seed.replies.lastOrNull(),
        createdAt = iso(now - seed.ageMillis - (seed.durationMs ?: (6 * MIN))),
    )

    fun initialRun(seed: Seed, now: Long): RunDto {
        val created = now - seed.ageMillis - (seed.durationMs ?: (6 * MIN))
        val terminal = seed.runStatus != "RUNNING" && seed.runStatus != "CREATING"
        return RunDto(
            id = "run-${seed.id.removePrefix("bc-")}-1",
            agentId = seed.id,
            status = seed.runStatus,
            createdAt = iso(created),
            updatedAt = iso(now - seed.ageMillis),
            durationMs = if (terminal) seed.durationMs else null,
            result = if (terminal) seed.replies.lastOrNull() else null,
            git = if (seed.branch != null || seed.prUrl != null) RunGitDto(listOf(RunGitBranchDto(repoUrl = seed.repo?.removePrefix("https://") ?: "", branch = seed.branch, prUrl = seed.prUrl))) else null,
        )
    }

    fun transcript(seed: Seed): List<V0ConversationMessageDto> = buildList {
        add(V0ConversationMessageDto(id = "${seed.id}-u1", type = "user_message", text = seed.prompt))
        seed.replies.forEachIndexed { i, reply -> add(V0ConversationMessageDto(id = "${seed.id}-a${i + 1}", type = "assistant_message", text = reply)) }
    }

    fun isoNow(now: Long) = iso(now)
}
