package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.NoSlashCommandApi
import com.cursorforandroid.data.api.SlashCommandApi
import com.cursorforandroid.data.local.SlashCommandCache
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SlashCommand
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Which composer a `/` catalog is for: the repository a new chat starts on, or the agent a follow-up goes to. */
sealed class SlashScope {
    abstract val key: String

    /** A new chat with no repository: only the commands that apply everywhere. */
    data object None : SlashScope() {
        override val key: String get() = "none"
    }

    /** A new chat on [repoUrl] at [ref] (blank or null for the repository's default branch). */
    data class Repo(val repoUrl: String, val ref: String?) : SlashScope() {
        override val key: String get() = "repo:$repoUrl@${ref?.trim().orEmpty()}"
    }

    /** A follow-up to [agentId]; the repository and branch help the server before the agent's machine has reported. */
    data class Agent(val agentId: String, val repoUrl: String?, val ref: String?) : SlashScope() {
        override val key: String get() = "agent:$agentId"
    }
}

/**
 * The `/` catalogs for the composers: what is listed for a repository or an agent, merged over the built-ins, and
 * kept per scope in memory and on disk so the popover has its list the moment `/` is typed and a saved one shows
 * before the network answers. Two sources, one at a time by [capabilities]: in Extended mode the account service
 * ([account], see [SlashCommandApi]), which also knows the team's global commands and what the agent's machine has
 * reported; otherwise the repository's own contents ([repoContents]: its `.cursor/skills` and `.cursor/commands`, as
 * far as its host will show them), which is all a documented-API-only app can know. A catalog is revalidated when it
 * is older than [TTL_MS] — or [PENDING_TTL_MS] while the agent's machine has yet to report its inventory. Neither
 * source is reachable under every key (a service account cannot exchange one for a session; a private repository
 * shows nothing to an anonymous reader), so after a failure the calls rest for [BACKOFF_MS] and the composers keep
 * the built-ins; nothing else about the app depends on them. A catalog is only ever shown under the source that
 * produced it: the mode changing under a fetch drops its answer, and the graph resets this on every change.
 */
class SlashCommandRepository(
    private val session: SessionManager,
    private val account: SlashCommandApi,
    private val cache: SlashCommandCache? = null,
    private val demo: (SlashScope) -> SlashCatalog = { DemoSlashCommands.catalog(it) },
    /** What the repository's contents say, for Extended mode off; nothing beyond the built-ins unless the graph supplies a reader. */
    private val repoContents: SlashCommandApi = NoSlashCommandApi,
    /** Which source may be asked (Extended mode). The account, for tests of the account path; the graph passes the setting. */
    private val capabilities: suspend () -> Capabilities = { Capabilities.EXTENDED },
) {
    /** Where a catalog came from; a catalog is never shown, or read from disk, under the other source. */
    enum class Source {
        /** `aiserver.v1.DashboardService`, in Extended mode. Its disk entries keep the keys they always had. */
        Account,
        /** The repository's own files, read from its host. */
        Repository;

        fun cacheKey(scope: SlashScope): String = if (this == Account) scope.key else "repo/${scope.key}"
    }

    private class Loaded(val catalog: SlashCatalog, val expiresAtMillis: Long, val backend: CursorBackend, val source: Source)

    /** A fetched catalog; [complete] is false when the scope's own list could not be had and only the rest is in it. */
    private class Fetched(val catalog: SlashCatalog, val complete: Boolean)

    private val loaded = MutableStateFlow<Map<String, Loaded>>(emptyMap())
    private val locks = HashMap<String, Mutex>()
    private val locksGuard = Mutex()

    /** The global commands, fetched once per backend (they do not depend on the repository); null until they have been. */
    private var global: List<SlashCommand>? = null
    private var globalFor: CursorBackend? = null
    private var globalFailedAt = 0L
    private var scopedFailedAt = 0L

    /** The catalog for [scope] as it stands: the built-ins until [load] has published anything better. */
    fun catalog(scope: SlashScope): Flow<SlashCatalog> = loaded.map { it[scope.key]?.catalog ?: SlashCatalog.BUILT_IN }.distinctUntilChanged()

    fun current(scope: SlashScope): SlashCatalog = loaded.value[scope.key]?.catalog ?: SlashCatalog.BUILT_IN

    /** The source the setting allows right now. */
    private suspend fun source(): Source = if (capabilities().accountSlashCommands) Source.Account else Source.Repository

    /**
     * Publishes the best catalog for [scope] and returns it: the saved copy first, if any, then the source's answer
     * unless one fresh enough is already in hand. Never throws — a refused or offline call leaves what is published
     * as it is, and the built-ins at the very least.
     */
    suspend fun load(scope: SlashScope, force: Boolean = false): SlashCatalog {
        val backend = session.current
        if (backend.isDemo) {
            val catalog = SlashCatalog.BUILT_IN.mergedWith(demo(scope))
            publish(scope, catalog, backend, Source.Account)
            return catalog
        }
        val source = source()
        return lockFor(scope.key).withLock {
            val now = AppClock.now()
            val known = loaded.value[scope.key]?.takeIf { it.backend === backend && it.source == source }
            if (known != null && !force && now < known.expiresAtMillis) return@withLock known.catalog

            if (known == null && cache != null) {
                cache.read(source.cacheKey(scope))?.let { saved ->
                    val expires = saved.savedAtMillis + ttlOf(saved.value)
                    if (session.current === backend && source() == source) loaded.update { it + (scope.key to Loaded(saved.value, expires, backend, source)) }
                    if (!force && now < expires) return@withLock saved.value
                }
            }

            // Nothing could be fetched: what is published for this source stands (the saved copy, however old, above).
            val fetched = fetch(scope, backend, now, source)
                ?: return@withLock loaded.value[scope.key]?.takeIf { it.backend === backend && it.source == source }?.catalog ?: SlashCatalog.BUILT_IN
            // A backend switch or a mode change while the call was out: the answer is not this composer's to show.
            if (session.current !== backend || source() != source) return@withLock fetched.catalog
            // A catalog missing the scope's own list is a stand-in until the source is tried again.
            publish(scope, fetched.catalog, backend, source, now + if (fetched.complete) ttlOf(fetched.catalog) else BACKOFF_MS)
            if (fetched.complete && scope !is SlashScope.None) cache?.write(source.cacheKey(scope), fetched.catalog)
            fetched.catalog
        }
    }

    /**
     * The global commands (the account's alone) and the scope's own list, asked for together, over the built-ins.
     * Null when nothing could be fetched; a scope list that fails while the global one succeeds still yields a catalog
     * (and the other way round), so one refused call does not cost the other's answer.
     */
    private suspend fun fetch(scope: SlashScope, backend: CursorBackend, now: Long, source: Source): Fetched? = coroutineScope {
        val api = if (source == Source.Account) account else repoContents
        val globalDeferred = async { if (source == Source.Account) globalCommands(backend, now) else emptyList() }
        val scoped = when {
            scope is SlashScope.None -> Result.success(SlashCatalog())
            now - scopedFailedAt < BACKOFF_MS -> Result.failure(IllegalStateException("resting"))
            else -> runCatching {
                when (scope) {
                    is SlashScope.Repo -> api.forRepository(scope.repoUrl, scope.ref)
                    is SlashScope.Agent -> api.forAgent(scope.agentId, scope.repoUrl, scope.ref)
                    SlashScope.None -> SlashCatalog()
                }
            }.onFailure { scopedFailedAt = now }.onSuccess { scopedFailedAt = 0L }
        }
        val globals = globalDeferred.await()
        val scopedCatalog = scoped.getOrNull()
        if (globals == null && scopedCatalog == null) return@coroutineScope null
        var catalog = SlashCatalog.BUILT_IN
        if (globals != null) catalog = catalog.mergedWith(SlashCatalog(globals))
        if (scopedCatalog != null) catalog = catalog.mergedWith(scopedCatalog)
        Fetched(catalog, complete = scopedCatalog != null)
    }

    private suspend fun globalCommands(backend: CursorBackend, now: Long): List<SlashCommand>? {
        global?.takeIf { globalFor === backend }?.let { return it }
        if (now - globalFailedAt < BACKOFF_MS) return null
        return runCatching { account.global() }
            .onSuccess { global = it; globalFor = backend; globalFailedAt = 0L }
            .onFailure { globalFailedAt = now }
            .getOrNull()
    }

    private fun publish(scope: SlashScope, catalog: SlashCatalog, backend: CursorBackend, source: Source, expiresAtMillis: Long = Long.MAX_VALUE) {
        loaded.update { it + (scope.key to Loaded(catalog, expiresAtMillis, backend, source)) }
    }

    private fun ttlOf(catalog: SlashCatalog): Long = if (catalog.pending) PENDING_TTL_MS else TTL_MS

    private suspend fun lockFor(key: String): Mutex = locksGuard.withLock { locks.getOrPut(key) { Mutex() } }

    /** On sign-out, a backend switch, or Extended mode changing: nothing loaded so far belongs to what comes next. */
    fun reset() {
        loaded.value = emptyMap()
        global = null
        globalFor = null
        globalFailedAt = 0L
        scopedFailedAt = 0L
    }

    companion object {
        /** Skills change when someone commits one; ten minutes keeps a browsing session on one fetch per repository. */
        const val TTL_MS = 10 * 60 * 1000L
        /** While the agent's machine has not reported yet, the next `/` asks again soon. */
        const val PENDING_TTL_MS = 15 * 1000L
        /** How long the account service is left alone after refusing or failing a call. */
        const val BACKOFF_MS = 5 * 60 * 1000L
    }
}

/**
 * What the demo account's repositories and agents offer beyond the built-ins: a project skill, a plugin skill and a
 * machine command, so the popover can be seen doing what it does on a real account without one.
 */
object DemoSlashCommands {
    val projectSkills: List<SlashCommand> = listOf(
        SlashCommand("deploy-web", "Build the web app and deploy it to the preview environment", origin = SlashCommand.Origin.Project, sourcePath = ".cursor/skills/deploy-web/SKILL.md"),
        SlashCommand("chat-sdk", "Vercel Chat SDK expert guidance. Use when building multi-platform chat bots — Slack, Telegram, Microsoft Teams, Discord, Google Chat", origin = SlashCommand.Origin.Plugin, sourcePath = "/home/ubuntu/.cursor/plugins/cache/vercel/skills/chat-sdk/SKILL.md"),
        SlashCommand("release-notes", "Draft release notes from the commits since the last tag", origin = SlashCommand.Origin.Personal, sourcePath = "/home/ubuntu/.cursor/skills/release-notes/SKILL.md"),
    )

    val machineCommands: List<SlashCommand> = listOf(
        SlashCommand("triage", "Label and prioritise the open issues", kind = SlashCommand.Kind.Command, origin = SlashCommand.Origin.Project, sourcePath = "/workspace/.cursor/commands/triage.md"),
    )

    fun catalog(scope: SlashScope): SlashCatalog = when (scope) {
        SlashScope.None -> SlashCatalog()
        is SlashScope.Repo -> SlashCatalog(projectSkills)
        is SlashScope.Agent -> SlashCatalog(projectSkills + machineCommands)
    }
}
