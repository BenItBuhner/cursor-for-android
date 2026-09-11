package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.SlashCommandApi
import com.cursorforandroid.data.local.SlashCommandCache
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
 * The `/` catalogs for the composers: what the account service lists for a repository or an agent (see
 * [SlashCommandApi]), merged over the built-ins, and kept per scope in memory and on disk so the popover has its list
 * the moment `/` is typed and a saved one shows before the network answers. A catalog is revalidated when it is older
 * than [TTL_MS] — or [PENDING_TTL_MS] while the agent's machine has yet to report its inventory. The account service
 * is not reachable under every key (a service account cannot exchange one for a session), so after a failure the
 * calls rest for [BACKOFF_MS] and the composers keep the built-ins; nothing else about the app depends on them.
 */
class SlashCommandRepository(
    private val session: SessionManager,
    private val api: SlashCommandApi,
    private val cache: SlashCommandCache? = null,
    private val demo: (SlashScope) -> SlashCatalog = { DemoSlashCommands.catalog(it) },
) {
    private class Loaded(val catalog: SlashCatalog, val expiresAtMillis: Long, val backend: CursorBackend)

    /** A fetched catalog; [complete] is false when the scope's own list could not be had and only the rest is in it. */
    private class Fetched(val catalog: SlashCatalog, val complete: Boolean)

    /**
     * What every scope's load shares, held as one value so a fetch cannot be credited to the wrong backend: writing
     * the commands and the backend they came from as two stores let a load finishing under one account claim
     * another's answer.
     */
    private data class Shared(
        /** The global commands, fetched once per backend (they do not depend on the repository). */
        val globals: List<SlashCommand>? = null,
        val globalsFor: CursorBackend? = null,
        val globalFailedAt: Long = 0L,
        /** When the account service last refused a scope's own list, by scope key: one bad agent rests only itself. */
        val scopedFailedAt: Map<String, Long> = emptyMap(),
    )

    private val loaded = MutableStateFlow<Map<String, Loaded>>(emptyMap())
    private val locks = ConcurrentHashMap<String, Mutex>()

    @Volatile private var shared = Shared()

    /**
     * Bumped by [reset]. A catalog is fetched in the caller's scope, so a sign-out cannot cancel a request already
     * out; what it can do is keep the answer from being published, written or shared under the next account.
     */
    private val generation = AtomicInteger()

    /** Serializes the check with the publication it guards, and both with [reset]. */
    private val publishLock = Any()

    private fun token(): Int = generation.get()

    /** Applies [apply] only while what it would write still belongs to [startedIn] and to [backend]. */
    private fun publish(startedIn: Int, backend: CursorBackend, apply: () -> Unit): Boolean = synchronized(publishLock) {
        if (generation.get() != startedIn || session.current !== backend) return false
        apply()
        true
    }

    /** The catalog for [scope] as it stands: the built-ins until [load] has published anything better. */
    fun catalog(scope: SlashScope): Flow<SlashCatalog> = loaded.map { it[scope.key]?.catalog ?: SlashCatalog.BUILT_IN }.distinctUntilChanged()

    fun current(scope: SlashScope): SlashCatalog = loaded.value[scope.key]?.catalog ?: SlashCatalog.BUILT_IN

    /**
     * Publishes the best catalog for [scope] and returns it: the saved copy first, if any, then the account service's
     * answer unless one fresh enough is already in hand. Never throws — a refused or offline call leaves what is
     * published as it is, and the built-ins at the very least.
     */
    suspend fun load(scope: SlashScope, force: Boolean = false): SlashCatalog {
        val backend = session.current
        val startedIn = token()
        // Taken before the call goes out: sampled at the write instead, it would pass for current on the far side
        // of a sign-out that has already finished wiping.
        val cacheToken = cache?.token() ?: 0
        if (backend.isDemo) {
            val catalog = SlashCatalog.BUILT_IN.mergedWith(demo(scope))
            publishCatalog(scope, catalog, backend, startedIn)
            return catalog
        }
        return lockFor(scope.key).withLock {
            val now = AppClock.now()
            val known = loaded.value[scope.key]?.takeIf { it.backend === backend }
            if (known != null && !force && now < known.expiresAtMillis) return@withLock known.catalog

            if (known == null && cache != null) {
                cache.read(scope.key)?.let { saved ->
                    val expires = saved.savedAtMillis + ttlOf(saved.value)
                    publishCatalog(scope, saved.value, backend, startedIn, expires)
                    if (!force && now < expires) return@withLock saved.value
                }
            }

            val fetched = fetch(scope, backend, now, startedIn) ?: return@withLock current(scope)
            // A catalog missing the scope's own list is a stand-in until the account service is tried again.
            val expires = now + if (fetched.complete) ttlOf(fetched.catalog) else BACKOFF_MS
            if (!publishCatalog(scope, fetched.catalog, backend, startedIn, expires)) return@withLock fetched.catalog
            if (fetched.complete && scope !is SlashScope.None) cache?.write(scope.key, fetched.catalog, cacheToken)
            fetched.catalog
        }
    }

    /**
     * The global commands and the scope's own list, asked for together, over the built-ins. Null when nothing could
     * be fetched; a scope list that fails while the global one succeeds still yields a catalog (and the other way
     * round), so one refused call does not cost the other's answer.
     */
    private suspend fun fetch(scope: SlashScope, backend: CursorBackend, now: Long, startedIn: Int): Fetched? = coroutineScope {
        val globalDeferred = async { globalCommands(backend, now, startedIn) }
        val scoped = when {
            scope is SlashScope.None -> Result.success(SlashCatalog())
            now - (shared.scopedFailedAt[scope.key] ?: 0L) < BACKOFF_MS -> Result.failure(IllegalStateException("resting"))
            else -> runCatching {
                when (scope) {
                    is SlashScope.Repo -> api.forRepository(scope.repoUrl, scope.ref)
                    is SlashScope.Agent -> api.forAgent(scope.agentId, scope.repoUrl, scope.ref)
                    SlashScope.None -> SlashCatalog()
                }
            }
                // Only this scope rests: the account service is reachable under some keys and not others, and a
                // repository nobody can see says nothing about the next one. Keys whose rest is over are dropped.
                .onFailure { share(startedIn, backend) { copy(scopedFailedAt = scopedFailedAt.filterValues { now - it < BACKOFF_MS } + (scope.key to now)) } }
                .onSuccess { share(startedIn, backend) { copy(scopedFailedAt = scopedFailedAt - scope.key) } }
        }
        val globals = globalDeferred.await()
        val scopedCatalog = scoped.getOrNull()
        if (globals == null && scopedCatalog == null) return@coroutineScope null
        var catalog = SlashCatalog.BUILT_IN
        if (globals != null) catalog = catalog.mergedWith(SlashCatalog(globals))
        if (scopedCatalog != null) catalog = catalog.mergedWith(scopedCatalog)
        Fetched(catalog, complete = scopedCatalog != null)
    }

    private suspend fun globalCommands(backend: CursorBackend, now: Long, startedIn: Int): List<SlashCommand>? {
        val held = shared
        held.globals?.takeIf { held.globalsFor === backend }?.let { return it }
        if (now - held.globalFailedAt < BACKOFF_MS) return null
        return runCatching { api.global() }
            .onSuccess { commands -> share(startedIn, backend) { copy(globals = commands, globalsFor = backend, globalFailedAt = 0L) } }
            .onFailure { share(startedIn, backend) { copy(globalFailedAt = now) } }
            .getOrNull()
    }

    private fun publishCatalog(
        scope: SlashScope,
        catalog: SlashCatalog,
        backend: CursorBackend,
        startedIn: Int,
        expiresAtMillis: Long = Long.MAX_VALUE,
    ): Boolean = publish(startedIn, backend) {
        loaded.update { it + (scope.key to Loaded(catalog, expiresAtMillis, backend)) }
    }

    private fun share(startedIn: Int, backend: CursorBackend, transform: Shared.() -> Shared) {
        publish(startedIn, backend) { shared = shared.transform() }
    }

    private fun ttlOf(catalog: SlashCatalog): Long = if (catalog.pending) PENDING_TTL_MS else TTL_MS

    private fun lockFor(key: String): Mutex = locks.computeIfAbsent(key) { Mutex() }

    fun reset() {
        synchronized(publishLock) {
            generation.incrementAndGet()
            loaded.value = emptyMap()
            shared = Shared()
        }
        // The keys are the signed-out account's agents and repositories; a load still holding one of these is on its
        // way out and cannot publish anything now.
        locks.clear()
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
