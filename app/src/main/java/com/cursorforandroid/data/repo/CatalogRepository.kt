package com.cursorforandroid.data.repo

import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Models and repositories for the composer pickers. `/v1/repositories` is severely rate limited
 * (1 request / user / minute) so it is cached for the whole session and refreshed only on demand.
 */
class CatalogRepository(private val session: SessionManager) {

    private val _models = MutableStateFlow<List<ModelOption>>(emptyList())
    val models: StateFlow<List<ModelOption>> = _models.asStateFlow()

    private val _repositories = MutableStateFlow<List<Repository>>(emptyList())
    val repositories: StateFlow<List<Repository>> = _repositories.asStateFlow()

    private var reposFetchedAt = 0L
    private var modelsFetchedFor: Any? = null

    suspend fun loadModels(force: Boolean = false): Result<List<ModelOption>> {
        val backend = session.current
        if (!force && modelsFetchedFor === backend && _models.value.isNotEmpty()) return Result.success(_models.value)
        return runCatching { backend.api.models().items.map { it.toModel() } }
            .onSuccess {
                _models.value = it
                modelsFetchedFor = backend
            }
    }

    suspend fun loadRepositories(force: Boolean = false): Result<List<Repository>> {
        val now = System.currentTimeMillis()
        if (!force && _repositories.value.isNotEmpty() && now - reposFetchedAt < REPO_TTL_MS) return Result.success(_repositories.value)
        if (!force && now - reposFetchedAt < REPO_MIN_INTERVAL_MS) return Result.success(_repositories.value)
        return runCatching { session.current.api.repositories().items.map { Repository(it.url) } }
            .onSuccess {
                _repositories.value = it.sortedBy { r -> r.slug.lowercase() }
                reposFetchedAt = now
            }
    }

    /** Repositories seen on existing agents are a free, rate-limit-safe source for the picker. */
    fun seedRepositories(urls: Collection<String>) {
        if (_repositories.value.isNotEmpty()) return
        _repositories.value = urls.distinct().map(::Repository).sortedBy { it.slug.lowercase() }
    }

    fun reset() {
        _models.value = emptyList()
        _repositories.value = emptyList()
        reposFetchedAt = 0L
        modelsFetchedFor = null
    }

    private companion object {
        const val REPO_TTL_MS = 30 * 60 * 1000L
        const val REPO_MIN_INTERVAL_MS = 65 * 1000L
    }
}
