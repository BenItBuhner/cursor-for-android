package com.cursorforandroid.data.demo

import com.cursorforandroid.data.api.AgentStoreApi
import com.cursorforandroid.data.api.PresignedStoreRead
import com.cursorforandroid.data.api.StoreReadTarget
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.domain.AgentStoreKind
import com.cursorforandroid.domain.AgentStoreRef
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The demo's Agent Stores, standing in for `ListAgentStores` / `ListAgentStoreEntries` / `ReadAgentStoreFile`: the
 * demo Project's Context — the folder set cursor.com shows under a Project (`docs`, `inbox`, `internal`, `media`,
 * `archived.md`, `notes.md`), with a `notes.md` in the shape the web's "Project" tab renders — and the user's own
 * store (`handoff`, `preferences.md`). Ages are relative to the clock so the listings' times read the same whenever
 * the demo is opened, and the pictures under `media/` resolve to the demo's bundled asset.
 */
object DemoStores : AgentStoreApi {

    const val PROJECT_STORE_ID = "store-demo-project"
    const val USER_STORE_ID = "store-demo-user"

    val projectStore = AgentStoreRef(PROJECT_STORE_ID, AgentStoreKind.CLOUD, sourceId = DemoData.PROJECT_ID)
    val userStore = AgentStoreRef(USER_STORE_ID, AgentStoreKind.USER)

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    /** One file: its size, how long ago it was written, and its text (a picture has none; its bytes are the asset's). */
    private class DemoFile(val ageMillis: Long, val text: String?, val sizeBytes: Long = text?.length?.toLong() ?: 40_040L)

    private val projectFiles: Map<String, DemoFile> by lazy { mapOf(
        "notes.md" to DemoFile(12 * MIN, NOTES),
        "archived.md" to DemoFile(4 * HOUR, ARCHIVED),
        "docs/project-context.md" to DemoFile(2 * HOUR, PROJECT_CONTEXT),
        "docs/private-edition-feasibility.md" to DemoFile(2 * DAY, PRIVATE_EDITION),
        "docs/readiness-triage.md" to DemoFile(3 * DAY, READINESS),
        "inbox/bennett-2026-09-14.md" to DemoFile(20 * HOUR, INBOX),
        "internal/release-runbook.md" to DemoFile(6 * HOUR, RUNBOOK),
        "internal/reference/web-project-ui.md" to DemoFile(26 * HOUR, "# Web Project UI reference\n\nSix captures of cursor.com's Projects UI; see the parity spec under docs/."),
        "media/panel-decluttered.png" to DemoFile(35 * MIN, null),
        "media/sidebar-desktop-composition.png" to DemoFile(55 * MIN, null),
        "media/transcript-rich-content.png" to DemoFile(3 * HOUR, null),
    ) }

    private val userFiles: Map<String, DemoFile> by lazy { mapOf(
        "preferences.md" to DemoFile(DAY + 2 * HOUR, PREFERENCES),
        "handoff/cesium.md" to DemoFile(5 * DAY, "# Handoff: Cesium\n\nWhere the billing launch stood when the last session ended: webhooks verified, proration undecided."),
    ) }

    override suspend fun storeFor(sourceId: String): String? = PROJECT_STORE_ID.takeIf { sourceId == DemoData.PROJECT_ID }

    override suspend fun stores(): List<AgentStoreRef> = io { listOf(projectStore, userStore) }

    override suspend fun entries(storeId: String, relativePath: String): List<ContextEntry> = io {
        val files = filesOf(storeId) ?: throw IllegalArgumentException("No such store: $storeId")
        val prefix = relativePath.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val now = AppClock.now()
        val directories = LinkedHashMap<String, Long>()
        val entries = ArrayList<ContextEntry>()
        files.forEach { (path, file) ->
            if (!path.startsWith(prefix)) return@forEach
            val rest = path.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash < 0) {
                entries += ContextEntry(path, isDirectory = false, sizeBytes = file.sizeBytes, updatedAtMillis = now - file.ageMillis)
            } else {
                val dir = prefix + rest.substring(0, slash)
                directories[dir] = maxOf(directories[dir] ?: 0L, now - file.ageMillis)
            }
        }
        (directories.map { (dir, at) -> ContextEntry(dir, isDirectory = true, updatedAtMillis = at) } + entries)
            .sortedWith(compareByDescending<ContextEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun readFile(storeId: String, relativePath: String): String = io {
        val file = filesOf(storeId)?.get(relativePath.trim('/')) ?: throw IllegalArgumentException("No such file: $relativePath")
        file.text ?: throw IllegalArgumentException("$relativePath is a picture; open it from Recents.")
    }

    /** A beat of latency off the main thread, as the demo backend's reads have, so the tabs show their loading states. */
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        delay(120)
        block()
    }

    /** Every picture the demo store lists resolves to the one bundled screenshot. */
    override suspend fun presignRead(target: StoreReadTarget, relativePath: String): PresignedStoreRead? =
        if (relativePath.endsWith(".png")) PresignedStoreRead(relativePath, MediaLoader.ASSET_PREFIX + "demo/predictive_back_drawer.png", null) else null

    private fun filesOf(storeId: String): Map<String, DemoFile>? = when (storeId) {
        PROJECT_STORE_ID -> projectFiles
        USER_STORE_ID -> userFiles
        else -> null
    }

    private val NOTES = """
        # Cesium billing launch

        ## Shipping

        - [ ] Usage events aggregation — primary `Usage events aggregation` on `cursor/usage-aggregation-3c7d`; PR #215 open, needs the hourly rollup decision
        - [x] Stripe webhook handler — primary `Stripe webhook handler` shipped the HTTP action on `cursor/stripe-webhooks-8e1f`; idempotent on the event id
        - [ ] Proration on mid-cycle upgrades — decision pending (Bennett), see inbox
        - [x] Pricing page copy — side chat drafted the three tiers in the product's voice

        ## Deferred features (Extended mode)

        - [ ] Metered billing dashboard — after the aggregation lands
        - [ ] Team seats and invoices — needs the Clerk organizations map
        - [ ] Convex cron for the monthly close — blocked on the rollup schema

        ## Direction and reference

        - [x] **Project context** — living: decisions, release state, scope
        - [x] **Monetization teardown** — final: the cloud, inference and distribution surfaces
        - [ ] **Readiness triage** — living: what blocks a public launch
        - [x] **Private edition feasibility** — SDK-only now; full features via Extended mode

        Older items: [archived](archived.md)
    """.trimIndent()

    private val ARCHIVED = """
        # Archived

        - Explorers' inventory of the cloud layer (Convex + Clerk production, self-hosted engines, quick tunnels) — folded into the teardown
        - First draft of the tiers — superseded by the pricing page copy
    """.trimIndent()

    private val PROJECT_CONTEXT = """
        # Cesium — Project context

        Stable goals, constraints and decisions. Progress lives in `notes.md`.

        ## What this is

        - A billing launch for Cesium: usage metering, Stripe, and a pricing page, built by primaries the coordinator spawns.
        - Zero billing and zero metering existed before this Project; the repo carried a BYOK agent and OAuth only.

        ## Decisions

        - 2026-09-12: webhooks are idempotent on the Stripe event id before anything touches an account.
        - 2026-09-13: proration on mid-cycle upgrades is Bennett's call; the handler ships without it.
    """.trimIndent()

    private val PRIVATE_EDITION = """
        # Private Edition — Feasibility and Risk

        Repo: `techlitnow/cesium` @ `9120e41` (main). Scope: read-only research and assessment. No code, branches, repo files, or pull requests were created or changed.

        ## Summary

        A private edition that talks to the undocumented account service is feasible today and carries the API-terms exposure the public edition avoids. The recommendation on record is one public app with an opt-in Extended mode behind an explicit warning, never a separate branch or flavor.

        ## Surfaces only the account service offers

        | Surface | Endpoint | Risk |
        | --- | --- | --- |
        | Steering a running turn | `InjectBackgroundComposerContext` | private |
        | Ask and Debug modes | `AddAsyncFollowupBackgroundComposer` | private |
        | Projects | `CreateProjectWorker`, `ListWorkersForManager` | private, beta |
        | Shared context | `ListAgentStores`, `ReadAgentStoreFile` | private |

        ## Verdict

        Ship documented-API-only by default; gate the rest behind consent.
    """.trimIndent()

    private val READINESS = """
        # Readiness triage

        What blocks a public launch, by severity.

        1. **Signing** — the release key must be loaded into the repository's secrets before a tag can build.
        2. **Metering** — no usage events are aggregated yet; the dashboard has nothing to show.
        3. **Copy** — the pricing page copy is drafted; the tiers are not final.
    """.trimIndent()

    private val INBOX = """
        # From Bennett — 2026-09-14

        Proration: let's not prorate in the first cut; charge the new tier from the next cycle and say so on the upgrade sheet.
    """.trimIndent()

    private val RUNBOOK = """
        # Release runbook

        1. `npx convex deploy` from a green main.
        2. Rotate the Stripe webhook secret and set it in the Convex dashboard.
        3. Smoke-test one upgrade and one cancellation in test mode.
    """.trimIndent()

    private val PREFERENCES = """
        # Preferences

        - Reply in prose; lists only when the content is multifaceted.
        - Never create markdown files in a repository unless asked.
        - Workers run on Claude Fable 5.1 Max.
    """.trimIndent()
}
