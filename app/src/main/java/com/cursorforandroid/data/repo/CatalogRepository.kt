package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.ListPoolsResponseDto
import com.cursorforandroid.data.api.dto.ListWorkersResponseDto
import com.cursorforandroid.data.api.dto.PoolDto
import com.cursorforandroid.data.api.dto.WorkerDto
import com.cursorforandroid.data.local.CatalogCache
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.DeviceOption
import com.cursorforandroid.domain.DeviceSection
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Models, repositories and devices for the composer pickers. Models and repositories are restored from disk so the
 * pickers are populated the moment the composer appears, then revalidated: models once per session, repositories on
 * the TTL below. `/v1/repositories` is severely rate limited (1 request / user / minute) so its fetch time is
 * persisted too and the limit is honoured across restarts. A failed refresh keeps whatever is already shown.
 * Devices are live-only (fleet endpoints often 403 a user key) and merge with the agent list in the composer.
 */
class CatalogRepository(
    private val session: SessionManager,
    private val cache: CatalogCache? = null,
) {

    private val _models = MutableStateFlow<List<ModelOption>>(emptyList())
    val models: StateFlow<List<ModelOption>> = _models.asStateFlow()

    private val _repositories = MutableStateFlow<List<Repository>>(emptyList())
    val repositories: StateFlow<List<Repository>> = _repositories.asStateFlow()

    /**
     * Live self-hosted workers and team pools, when the key can see them. Empty is the common case for a user key;
     * the composer still offers Cloud and anything harvested from the agent list.
     */
    private val _devices = MutableStateFlow<List<DeviceOption>>(emptyList())
    val devices: StateFlow<List<DeviceOption>> = _devices.asStateFlow()

    private var reposFetchedAt = 0L
    /** True while the repository list only holds URLs harvested from the agent list, not a fetched or saved catalog. */
    private var reposSeededOnly = false
    private var modelsFetchedFor: Any? = null
    @Volatile private var restoredFor: CursorBackend? = null
    private val restoreMutex = Mutex()

    /** Shows the catalogs saved by the previous session. Idempotent per backend; a no-op for the demo. */
    suspend fun restoreFromCache() {
        val backend = session.current
        if (cache == null || backend.isDemo || restoredFor === backend) return
        restoreMutex.withLock {
            if (restoredFor === backend) return
            restoredFor = backend
            val models = cache.readModels()
            val repos = cache.readRepositories()
            if (session.current !== backend) return
            models?.takeIf { it.value.isNotEmpty() && _models.value.isEmpty() }?.let { _models.value = it.value }
            repos?.takeIf { it.value.isNotEmpty() && (_repositories.value.isEmpty() || reposSeededOnly) }?.let {
                _repositories.value = it.value
                reposFetchedAt = it.savedAtMillis
                reposSeededOnly = false
            }
        }
    }

    suspend fun loadModels(force: Boolean = false): Result<List<ModelOption>> {
        restoreFromCache()
        val backend = session.current
        if (!force && modelsFetchedFor === backend && _models.value.isNotEmpty()) return Result.success(_models.value)
        return runCatching { backend.api.models().items.map { it.toModel() } }
            .onSuccess {
                _models.value = it
                modelsFetchedFor = backend
                if (!backend.isDemo) cache?.writeModels(it)
            }
            .recoverCatching { t -> _models.value.takeIf { it.isNotEmpty() } ?: throw t }
    }

    suspend fun loadRepositories(force: Boolean = false): Result<List<Repository>> {
        restoreFromCache()
        val backend = session.current
        val now = AppClock.now()
        if (!force && _repositories.value.isNotEmpty() && now - reposFetchedAt < REPO_TTL_MS) return Result.success(_repositories.value)
        if (!force && now - reposFetchedAt < REPO_MIN_INTERVAL_MS) return Result.success(_repositories.value)
        return runCatching { backend.api.repositories().items.map { Repository(it.url) }.distinctBy { it.url } }
            .onSuccess {
                val sorted = it.sortedBy { r -> r.slug.lowercase() }
                _repositories.value = sorted
                reposFetchedAt = now
                reposSeededOnly = false
                if (!backend.isDemo) cache?.writeRepositories(sorted)
            }
            .recoverCatching { t -> _repositories.value.takeIf { it.isNotEmpty() } ?: throw t }
    }

    /** Repositories seen on existing agents are a free, rate-limit-safe source for the picker. */
    fun seedRepositories(urls: Collection<String>) {
        if (_repositories.value.isNotEmpty() || urls.isEmpty()) return
        _repositories.value = urls.distinct().map(::Repository).sortedBy { it.slug.lowercase() }
        reposSeededOnly = true
    }

    /**
     * Connected My Machines and team pools. Each fleet call is best-effort: a 403 from a user key, or a missing
     * endpoint, is "none listed", never a hard failure. The composer merges this with the agent list.
     */
    suspend fun loadDevices(): Result<List<DeviceOption>> {
        val backend = session.current
        val personal = runCatching { backend.api.listWorkers(scope = "personal") }.getOrDefault(ListWorkersResponseDto())
        val team = runCatching { backend.api.listWorkers(scope = "team_pool") }.getOrDefault(ListWorkersResponseDto())
        val pools = runCatching { backend.api.listPools() }.getOrDefault(ListPoolsResponseDto())
        val listed = devicesOf(personal, team, pools)
        _devices.value = listed
        return Result.success(listed)
    }

    fun reset() {
        _models.value = emptyList()
        _repositories.value = emptyList()
        _devices.value = emptyList()
        reposFetchedAt = 0L
        reposSeededOnly = false
        modelsFetchedFor = null
        restoredFor = null
    }

    private companion object {
        const val REPO_TTL_MS = 30 * 60 * 1000L
        const val REPO_MIN_INTERVAL_MS = 65 * 1000L

        fun devicesOf(
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
                )
                poolRows.putIfAbsent(option.key, option)
            }
            return machines + poolRows.values
        }

        fun WorkerDto.toMachineOption(): DeviceOption? {
            val raw = targetName() ?: return null
            val target = DeviceTarget.machine(raw)
            val workspace = raw.substringAfter('#', "").trim().takeIf { it.isNotEmpty() }
            val repo = listOfNotNull(repoOwner, repoName).joinToString("/").takeIf { it.isNotBlank() }
                ?: repoUrl?.let(Agent::repoSlugOf)
            val subtitle = when {
                isInUse && workspace != null -> "Busy · $workspace"
                isInUse && repo != null -> "Busy · $repo"
                isInUse -> "Busy"
                workspace != null -> workspace
                repo != null -> repo
                else -> "Online"
            }
            return DeviceOption(target = target, subtitle = subtitle, online = true, section = DeviceSection.Machines)
        }

        fun PoolDto.toPoolOption(): DeviceOption? {
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
            )
        }
    }
}
