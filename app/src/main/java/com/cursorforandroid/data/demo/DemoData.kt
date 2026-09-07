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
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Sample data modelled on the reference screenshots so the app can be explored without an API key. */
internal object DemoData {

    private val fmt = DateTimeFormatter.ISO_INSTANT
    private fun iso(millis: Long): String = fmt.format(Instant.ofEpochMilli(millis))

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

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
    )

    private const val REPO_CESIUM = "https://github.com/techlitnow/cesium"
    private const val REPO_CODEX = "https://github.com/bennett/codex-poly-bot"
    private const val REPO_VISUAL = "https://github.com/bennett/visual-engine"
    private const val REPO_ANDROID = "https://github.com/bennett/cursor-for-android"
    private const val REPO_ZEN = "https://github.com/bennett/zen-parity"
    private const val REPO_MARKET = "https://github.com/bennett/market-replay"

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
        ),
        Seed(
            id = "bc-demo-0005", name = "House environment overhaul", repo = REPO_VISUAL, ageMillis = 30 * MIN,
            runStatus = "FINISHED", branch = "cursor/house-environment-7b3e", prUrl = "https://github.com/bennett/visual-engine/pull/67",
            summary = "Rebuilt the house environment with modular rooms and baked lighting.", durationMs = 58 * MIN,
            prompt = "Overhaul the house environment: modular rooms, baked lighting, and a day/night cycle. Open a PR when done.",
            replies = listOf("Rebuilt the environment into 9 modular room prefabs with a shared material atlas, added baked lighting for both day and night states and wired the cycle to the existing `WorldClock`. PR is open with 67 files changed."),
            autoPr = true,
        ),
        Seed(
            id = "bc-demo-0006", name = "Market replay engine", repo = REPO_MARKET, ageMillis = 52 * MIN,
            runStatus = "ERROR", durationMs = 9 * MIN,
            prompt = "Build a deterministic replay engine over the tick archive with a 10x fast-forward mode.",
            replies = listOf("Set up the replay clock and the archive reader, but the archive's Parquet footer is truncated for 2024-03-11 and the run's disk quota was exhausted while unpacking it. Needs a fresh archive or a bigger machine."),
        ),
        Seed(
            id = "bc-demo-0007", name = "Latest release process", repo = REPO_ANDROID, ageMillis = 3 * HOUR,
            runStatus = "FINISHED", branch = "cursor/release-process-1a2b", prUrl = "https://github.com/bennett/cursor-for-android/pull/3", durationMs = 22 * MIN,
            prompt = "Document the release process and add a GitHub Action that builds a signed release APK on tags.",
            replies = listOf("Added `.github/workflows/release.yml` that assembles a release build on `v*` tags and uploads the APK as a release asset. Signing uses the `ANDROID_KEYSTORE_B64` secret."),
        ),
        Seed(
            id = "bc-demo-0008", name = "Realistic city apartment scene", repo = REPO_VISUAL, ageMillis = 4 * HOUR,
            runStatus = "FINISHED", branch = "cursor/city-apartment-5d6e", durationMs = 71 * MIN,
            prompt = "Build a realistic city apartment scene with window parallax and evening lighting.",
            replies = listOf("Scene is in with a parallax skyline card behind the windows and a warm evening key light. Pushed to a branch; +4230 −165."),
        ),
        Seed(
            id = "bc-demo-0009", name = "Projector product demo", repo = REPO_VISUAL, ageMillis = 5 * HOUR,
            runStatus = "FINISHED", branch = "cursor/projector-demo-8f9a", prUrl = "https://github.com/bennett/visual-engine/pull/71", durationMs = 33 * MIN,
            prompt = "Create a product demo scene for the projector with a looping showcase animation.",
            replies = listOf("Added the showcase loop with three camera beats and a captioned overlay. PR open."),
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
        ),
        Seed(
            id = "bc-demo-0012", name = "Android mobile experience", repo = REPO_ANDROID, ageMillis = 10 * HOUR,
            runStatus = "FINISHED", branch = "cursor/mobile-experience-4e5f", durationMs = 28 * MIN,
            prompt = "Audit the Android app for gesture navigation gaps and predictive back support.",
            replies = listOf("Predictive back was missing on the drawer and two dialogs; added `enableOnBackInvokedCallback` and migrated to `BackHandler`. Branch pushed."),
        ),
        Seed(
            id = "bc-demo-0013", name = "Zen browser flawless parity", repo = REPO_ZEN, ageMillis = 11 * HOUR,
            runStatus = "FINISHED", branch = "cursor/zen-parity-6a7b", durationMs = 19 * MIN,
            prompt = "Compare our tab-group behaviour against Zen browser and list the parity gaps.",
            replies = listOf("Eleven gaps, four of them trivial. Full list with reproduction steps is on the branch in `docs/parity.md`."),
        ),
        Seed(
            id = "bc-demo-0014", name = "Onboarding copy pass", repo = REPO_CESIUM, ageMillis = DAY + 6 * HOUR,
            runStatus = "FINISHED", branch = "cursor/onboarding-copy-9b8c", prUrl = "https://github.com/techlitnow/cesium/pull/209", durationMs = 15 * MIN,
            prompt = "Tighten the onboarding copy and remove the marketing tone from error states.",
            replies = listOf("Rewrote 23 strings; error states now say what happened and what to do next. PR open."),
        ),
        Seed(
            id = "bc-demo-0015", name = "Flaky transcription test", repo = REPO_CESIUM, ageMillis = 2 * DAY,
            runStatus = "FINISHED", branch = "cursor/flaky-transcription-0d1e", durationMs = 26 * MIN,
            prompt = "Find out why `transcription.spec.ts` flakes on CI and fix it.",
            replies = listOf("The test raced the WebSocket handshake. Awaiting the `open` event before sending audio fixed it across 50 consecutive runs."),
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
