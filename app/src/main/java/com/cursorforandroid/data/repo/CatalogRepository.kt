package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ApiThrottle
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.api.retryAfterMillis
import com.cursorforandroid.data.api.dto.ListPoolsResponseDto
import com.cursorforandroid.data.api.dto.ListWorkersResponseDto
import com.cursorforandroid.data.api.dto.PoolDto
import com.cursorforandroid.data.api.dto.WorkerDto
import com.cursorforandroid.data.local.CatalogCache
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.DeviceOption
import com.cursorforandroid.domain.DeviceSection
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.MachineWorker
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Models, repositories and devices for the composer pickers. Models and repositories keep one refresh policy
 * ([CatalogRefresher]): restored from disk so the pickers are populated the moment the composer appears, fetched
 * again once they are [CatalogRefresher.STALE_AFTER_MS] old — when a picker opens, and on their own while the app is
 * in the foreground ([keepFresh]) — so a model Cursor has just announced or a repository just connected turns up
 * without a tap. Each fetch time is saved with its list, so a restart does not ask for a list it has just been given,
 * and `/v1/repositories`' limit (1 request / user / minute) is honoured across restarts. A failed refresh keeps
 * whatever is already shown. Devices are live-only (fleet endpoints often 403 a user key) and merge with the agent
 * list in the composer.
 *
 * An endpoint's limit is spent by an attempt, not by a success, and the repositories one is shared with the user's
 * other Cursor clients, so a failed fetch and a forced refresh both wait it out; only the freshness window is a
 * forced refresh's to skip. Each catalog also has its own lock, so concurrent callers — the composer and a widget
 * render, two taps, a background pass — cost one request between them, and the slow repositories fetch never queues
 * the models one behind it. A pause the account service's throttle holds every caller to ([throttlePausedUntil],
 * see `ApiThrottle`) holds these fetches too.
 */
class CatalogRepository(
    private val session: SessionManager,
    private val cache: CatalogCache? = null,
    /** Until when the account service's throttle holds every call (`ApiThrottle.pausedUntil`); null while it holds none. */
    private val throttlePausedUntil: () -> Long? = { null },
) {

    private val _models = MutableStateFlow<List<ModelOption>>(emptyList())
    val models: StateFlow<List<ModelOption>> = _models.asStateFlow()

    private val _repositories = MutableStateFlow<List<Repository>>(emptyList())
    val repositories: StateFlow<List<Repository>> = _repositories.asStateFlow()

    /** The two catalogs under the one policy: what each fetches, saves and says when its endpoint may not be asked yet. */
    private val modelCatalog = Catalog(
        items = _models,
        policy = CatalogRefresher(CatalogRefresher.STALE_AFTER_MS, CatalogRefresher.MODELS_MIN_INTERVAL_MS),
        fetch = { backend -> backend.api.models().items.map { it.toModel() } },
        save = { models, token -> cache?.writeModels(models, token) },
        tooSoon = { IllegalStateException("The model list was asked for a moment ago. Try again in a few seconds.") },
    )
    private val repoCatalog = Catalog(
        items = _repositories,
        policy = CatalogRefresher(CatalogRefresher.STALE_AFTER_MS, CatalogRefresher.REPOSITORIES_MIN_INTERVAL_MS),
        fetch = { backend -> backend.api.repositories().items.map { Repository(it.url) }.distinctBy { it.url }.sortedBy { r -> r.slug.lowercase() } },
        save = { repos, token -> cache?.writeRepositories(repos, token) },
        tooSoon = ::rateLimited,
        onLanded = { reposSeededOnly = false },
    )

    /**
     * Live self-hosted workers and team pools, when the key can see them. Empty is the common case for a user key;
     * the composer still offers Cloud and anything harvested from the agent list.
     */
    private val _devices = MutableStateFlow<List<DeviceOption>>(emptyList())
    val devices: StateFlow<List<DeviceOption>> = _devices.asStateFlow()

    /** True while the repository list only holds URLs harvested from the agent list, not a fetched or saved catalog. */
    private var reposSeededOnly = false
    @Volatile private var restoredFor: CursorBackend? = null
    /** Each machine's worker as last listed, by [DeviceOption.key]: kept when a later listing leaves the machine out. */
    private val lastSeenWorkers = HashMap<String, MachineWorker>()
    @Volatile private var workersRestoredFor: CursorBackend? = null
    private val restoreMutex = Mutex()
    /**
     * Bumped by [reset]. The catalogs are fetched in the caller's scope, so a sign-out cannot cancel a request
     * already out; what it can do is make sure the answer belongs to the account that asked for it. Real accounts
     * share the one backend object, so identity alone does not tell A from B.
     */
    private val generation = AtomicInteger()
    /** Serializes the check with the publication it guards, and both with [reset]. */
    private val publishLock = Any()

    private fun token(): Int = generation.get()

    /** Applies [apply] only while the catalogs still belong to [startedIn] (and to [backend], when given). */
    private fun publish(startedIn: Int, backend: CursorBackend?, apply: () -> Unit): Boolean = synchronized(publishLock) {
        if (generation.get() != startedIn) return false
        if (backend != null && session.current !== backend) return false
        apply()
        true
    }

    /** Shows the catalogs saved by the previous session. Idempotent per backend; a no-op for the demo. */
    suspend fun restoreFromCache() {
        val backend = session.current
        if (cache == null || backend.isDemo || restoredFor === backend) return
        restoreMutex.withLock {
            if (restoredFor === backend) return
            val startedIn = token()
            restoredFor = backend
            val models = cache.readModels()
            val repos = cache.readRepositories()
            val landed = publish(startedIn, backend) {
                models?.takeIf { it.value.isNotEmpty() && _models.value.isEmpty() }?.let {
                    _models.value = it.value
                    modelCatalog.policy.restored(it.savedAtMillis, backend)
                }
                repos?.takeIf { it.value.isNotEmpty() && (_repositories.value.isEmpty() || reposSeededOnly) }?.let {
                    _repositories.value = it.value
                    repoCatalog.policy.restored(it.savedAtMillis, backend)
                    reposSeededOnly = false
                }
            }
            // The disk copy read here is the previous account's; the current one has still to be restored.
            if (!landed) restoredFor = null
        }
    }

    /** The model list: the one shown while it is fresh, else fetched (see [CatalogRefresher]); [force] skips only the freshness. */
    suspend fun loadModels(force: Boolean = false): Result<List<ModelOption>> = load(modelCatalog, force)

    /** The repository list, by the same policy as [loadModels]. */
    suspend fun loadRepositories(force: Boolean = false): Result<List<Repository>> = load(repoCatalog, force)

    private suspend fun <T> load(catalog: Catalog<T>, force: Boolean): Result<List<T>> {
        val startedIn = token()
        val cacheToken = cache?.token() ?: 0
        restoreFromCache()
        if (!force && catalog.isFresh(AppClock.now())) return Result.success(catalog.items.value)
        return catalog.mutex.withLock {
            // A caller that was waiting here may have just fetched it.
            if (!force && catalog.isFresh(AppClock.now())) return@withLock Result.success(catalog.items.value)
            awaitThrottlePause()
            val now = AppClock.now()
            if (!catalog.policy.mayAsk(now)) {
                return@withLock catalog.items.value.takeIf { it.isNotEmpty() }?.let { Result.success(it) }
                    ?: Result.failure(catalog.tooSoon())
            }
            val backend = session.current
            publish(startedIn, backend) { catalog.policy.asked(now) }
            runCatching { catalog.fetch(backend) }
                .onFailure { t -> if (t is CancellationException) throw t }
                .mapCatching { fetched ->
                    val landed = publish(startedIn, backend) {
                        catalog.items.value = fetched
                        catalog.policy.landed(now, backend)
                        catalog.onLanded()
                    }
                    if (!landed) return@mapCatching catalog.items.value
                    if (!backend.isDemo) catalog.save(fetched, cacheToken)
                    fetched
                }
                .onFailure { t -> publish(startedIn, backend) { catalog.policy.failed(now, t.retryAfterMillis()) } }
                .recoverCatching { t -> catalog.items.value.takeIf { it.isNotEmpty() } ?: throw t }
        }
    }

    /**
     * Keeps both catalogs fresh for as long as it runs — while the app is in the foreground (see `CatalogFreshness`):
     * a pass now, catching up on whatever went stale while the app was away, then one each time a catalog comes due
     * ([CatalogRefresher.dueAt]). Nothing goes out while both are fresh, while [allowed] says no (no connection, or
     * the battery low or saving itself), or while the account service's throttle holds every caller; the loop wakes
     * no more often than [MIN_CHECK_MS] however soon something is due. Never returns; cancelled when the app leaves
     * the screen.
     */
    suspend fun keepFresh(allowed: () -> Boolean = { true }): Nothing {
        while (true) {
            if (allowed()) revalidateDue()
            delay(nextPassIn(AppClock.now()))
        }
    }

    /**
     * One background pass: each catalog that has come due is fetched, the two side by side — a revalidation, not a
     * forced refresh, so a list another caller has just brought is left alone. Nothing while signed out or while the
     * account service's throttle holds every caller.
     */
    suspend fun revalidateDue() {
        if (session.state.value !is SessionState.SignedIn) return
        val now = AppClock.now()
        if ((throttlePausedUntil() ?: 0L) > now) return
        coroutineScope {
            for (catalog in listOf(modelCatalog, repoCatalog)) {
                if (catalog.dueAt() <= now) launch { load(catalog, force = false) }
            }
        }
    }

    /** How long [keepFresh] sleeps before its next pass: until the first catalog is due or the throttle's pause ends. */
    internal fun nextPassIn(now: Long): Long {
        val due = minOf(modelCatalog.dueAt(), repoCatalog.dueAt())
        val at = maxOf(due, throttlePausedUntil() ?: 0L)
        return (at - now).coerceIn(MIN_CHECK_MS, CatalogRefresher.STALE_AFTER_MS)
    }

    /** A pause the account service's throttle holds every caller to is waited out before a fetch, up to the longest one it asks. */
    private suspend fun awaitThrottlePause() {
        val wait = (throttlePausedUntil() ?: return) - AppClock.now()
        if (wait > 0) delay(wait.coerceAtMost(ApiThrottle.MAX_PAUSE_MS))
    }

    /** Repositories seen on existing agents are a free, rate-limit-safe source for the picker. */
    fun seedRepositories(urls: Collection<String>) {
        if (urls.isEmpty()) return
        synchronized(publishLock) {
            if (_repositories.value.isNotEmpty()) return
            _repositories.value = urls.distinct().map(::Repository).sortedBy { it.slug.lowercase() }
            reposSeededOnly = true
        }
    }

    /**
     * Connected My Machines and team pools. Each fleet call is best-effort: a 403 from a user key, or a missing
     * endpoint, is "none listed", never a hard failure. The composer merges this with the agent list.
     */
    suspend fun loadDevices(): Result<List<DeviceOption>> {
        val backend = session.current
        val startedIn = token()
        val cacheToken = cache?.token() ?: 0
        restoreWorkers(backend, startedIn)
        val personal = runCatching { backend.api.listWorkers(scope = "personal") }.getOrDefault(ListWorkersResponseDto())
        val team = runCatching { backend.api.listWorkers(scope = "team_pool") }.getOrDefault(ListWorkersResponseDto())
        val pools = runCatching { backend.api.listPools() }.getOrDefault(ListPoolsResponseDto())
        val listed = devicesOf(personal, team, pools)
        val seen = listed.mapNotNull { option -> option.worker?.let { option.key to it } }.toMap()
        // A sign-out or a backend swap while the fleet calls were out means this answer belongs to nobody.
        if (!publish(startedIn, backend) { _devices.value = listed; lastSeenWorkers.putAll(seen) }) return Result.success(emptyList())
        if (seen.isNotEmpty() && !backend.isDemo) cache?.writeWorkers(synchronized(publishLock) { HashMap(lastSeenWorkers) }, cacheToken)
        return Result.success(listed)
    }

    /**
     * [target]'s worker as the fleet endpoint last listed it, this session or an earlier one: what a machine that has
     * gone offline is still asked for by (`selected_private_worker_id`), as the desktop keeps its recent machines.
     */
    fun lastSeenWorker(target: DeviceTarget): MachineWorker? = synchronized(publishLock) { lastSeenWorkers[DeviceOption.keyOf(target)] }

    private suspend fun restoreWorkers(backend: CursorBackend, startedIn: Int) {
        if (cache == null || backend.isDemo || workersRestoredFor === backend) return
        val saved = cache.readWorkers().orEmpty()
        publish(startedIn, backend) {
            saved.forEach { (key, worker) -> lastSeenWorkers.putIfAbsent(key, worker) }
            workersRestoredFor = backend
        }
    }

    fun reset() {
        synchronized(publishLock) {
            generation.incrementAndGet()
            _models.value = emptyList()
            _repositories.value = emptyList()
            _devices.value = emptyList()
            modelCatalog.policy.reset()
            repoCatalog.policy.reset()
            reposSeededOnly = false
            restoredFor = null
            lastSeenWorkers.clear()
            workersRestoredFor = null
        }
    }

    /** One catalog under the policy: its list, its [CatalogRefresher], and how it is fetched and saved. */
    private inner class Catalog<T>(
        val items: MutableStateFlow<List<T>>,
        val policy: CatalogRefresher,
        val fetch: suspend (CursorBackend) -> List<T>,
        val save: suspend (List<T>, Int) -> Unit,
        /** What a caller hears when the endpoint may not be asked yet and nothing is shown to stand in. */
        val tooSoon: () -> Throwable,
        /** Run with the fetched list's publication, under the same lock. */
        val onLanded: () -> Unit = {},
    ) {
        val mutex = Mutex()

        fun isFresh(now: Long): Boolean = policy.isFresh(now, session.current)

        fun dueAt(): Long = policy.dueAt(session.current)
    }

    private fun rateLimited() = CursorApiException(
        httpCode = 429,
        code = "rate_limited",
        message = "Cursor allows one repository refresh a minute.",
    )

    companion object {
        /** The least time between two of [keepFresh]'s passes: a pass that finds nothing due costs nothing, but it still wakes the process. */
        const val MIN_CHECK_MS = 60 * 1000L

        private fun devicesOf(
            personal: ListWorkersResponseDto,
            team: ListWorkersResponseDto,
            pools: ListPoolsResponseDto,
        ): List<DeviceOption> {
            val machines = personal.listed().mapNotNull { it.toMachineOption() }
            val poolRows = LinkedHashMap<String, DeviceOption>()
            for (pool in pools.listed()) {
                pool.toPoolOption()?.let { poolRows.putIfAbsent(it.key, it) }
            }
            // Pool workers are targeted by pool name, not worker name; a label still teaches us a pool the list missed.
            for (worker in team.listed()) {
                val poolName = worker.labels.firstOrNull { it.key.equals("pool", ignoreCase = true) }?.value
                    ?: continue
                val option = DeviceOption(
                    target = DeviceTarget.pool(poolName),
                    subtitle = if (worker.isInUse) "Busy" else "Online",
                    online = true,
                    section = DeviceSection.Pools,
                    repoUrl = worker.repositoryUrl(),
                )
                poolRows.putIfAbsent(option.key, option)
            }
            return machines + poolRows.values
        }

        /** The worker's primary repository (`repoUrl`, else `repoOwner`/`repoName`); null for an any-repo worker. */
        private fun WorkerDto.repositoryUrl(): String? = DeviceOption.repositoryUrl(repoUrl, repoOwner, repoName)

        private fun WorkerDto.toMachineOption(): DeviceOption? {
            val raw = targetName() ?: return null
            val target = DeviceTarget.machine(raw)
            val workspace = raw.substringAfter('#', "").trim().takeIf { it.isNotEmpty() } ?: workspaceRootPath?.trim()?.takeIf { it.isNotEmpty() }
            val repoUrl = repositoryUrl()
            val repo = repoUrl?.let(Agent::repoSlugOf)
            val subtitle = when {
                isInUse && workspace != null -> "Busy · $workspace"
                isInUse && repo != null -> "Busy · $repo"
                isInUse -> "Busy"
                workspace != null -> workspace
                repo != null -> repo
                else -> "Online"
            }
            return DeviceOption(target = target, subtitle = subtitle, online = true, section = DeviceSection.Machines, repoUrl = repoUrl, worker = machineWorker(raw))
        }

        /** The desktop's `RRe`: the id, the `name` label else the listed name else the id (`Ael`), `repoOwner/repoName`, the owner. */
        private fun WorkerDto.machineWorker(listedName: String): MachineWorker? {
            val id = (workerId ?: id)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val nameLabel = labels.firstOrNull { it.key == "name" }?.value?.trim()?.takeIf { it.isNotEmpty() }
            val owner = repoOwner?.trim().orEmpty()
            val repo = repoName?.trim().orEmpty()
            return MachineWorker(
                workerId = id,
                name = nameLabel ?: name?.trim()?.takeIf { it.isNotEmpty() } ?: listedName.ifBlank { id },
                repoLabel = if (owner.isNotEmpty() && repo.isNotEmpty()) "$owner/$repo" else null,
                ownerUserId = userId?.takeIf { it > 0 },
            )
        }

        private fun PoolDto.toPoolOption(): DeviceOption? {
            val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val connected = connectedWorkerCount ?: 0
            val inUse = inUseWorkerCount
            val subtitle = when {
                connected <= 0 -> "No workers connected"
                inUse != null -> "$connected connected · $inUse in use"
                else -> "$connected connected"
            }
            return DeviceOption(
                target = DeviceTarget.pool(n),
                subtitle = subtitle,
                online = connected > 0,
                section = DeviceSection.Pools,
                // "Repository metadata when the pool is tied to a repo. Omitted for any-repo pools."
                repoUrl = DeviceOption.repositoryUrl(repoUrl, repoOwner, repoName),
            )
        }
    }
}
