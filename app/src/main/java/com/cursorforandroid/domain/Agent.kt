package com.cursorforandroid.domain

/** Durable agent lifecycle as reported by `GET /v1/agents`. */
enum class AgentLifecycle { ACTIVE, IDLE, ARCHIVED, UNKNOWN;
    companion object {
        fun parse(raw: String?): AgentLifecycle = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: UNKNOWN
    }
}

/** Per-run execution status (`Run.status`, and the legacy v0 agent `status`). */
enum class RunStatus { CREATING, RUNNING, FINISHED, ERROR, CANCELLED, EXPIRED, UNKNOWN;
    val isActive: Boolean get() = this == CREATING || this == RUNNING
    val isTerminal: Boolean get() = this == FINISHED || this == ERROR || this == CANCELLED || this == EXPIRED
    companion object {
        fun parse(raw: String?): RunStatus = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: UNKNOWN
    }
}

/** Where the agent's VM lives: Cursor-hosted cloud, a self-hosted team pool, or one of the user's machines. */
enum class EnvType { CLOUD, POOL, MACHINE, UNKNOWN;
    companion object {
        fun parse(raw: String?): EnvType = when (raw?.lowercase()) {
            "cloud" -> CLOUD
            "pool" -> POOL
            "machine" -> MACHINE
            else -> UNKNOWN
        }
    }
}

data class GitBranch(
    val repoUrl: String,
    val branch: String?,
    val prUrl: String?,
)

data class Agent(
    val id: String,
    val name: String,
    val lifecycle: AgentLifecycle,
    val runStatus: RunStatus?,
    val envType: EnvType,
    val envName: String?,
    val url: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val latestRunId: String?,
    val repoUrl: String?,
    val startingRef: String?,
    val branches: List<GitBranch> = emptyList(),
    val summary: String? = null,
    val autoCreatePr: Boolean? = null,
    val workOnCurrentBranch: Boolean? = null,
    val modelDisplayName: String? = null,
    val durationMs: Long? = null,
) {
    /** `owner/name` derived from the GitHub URL, or null for no-repo agents. */
    val repoSlug: String? get() = repoUrl?.let(::repoSlugOf)
    val repoShortName: String? get() = repoSlug?.substringAfterLast('/')
    val branchName: String? get() = branches.firstOrNull { it.branch != null }?.branch
    val prUrl: String? get() = branches.firstOrNull { it.prUrl != null }?.prUrl
    val hasBranch: Boolean get() = branchName != null
    val hasPullRequest: Boolean get() = prUrl != null
    val isArchived: Boolean get() = lifecycle == AgentLifecycle.ARCHIVED
    /**
     * True while a run is in flight. Trusts v1 `ACTIVE` when the list still carries a lagging
     * FINISHED status (classic follow-up-on-PR desync), but never overrides a terminal error/cancel.
     */
    val isRunning: Boolean
        get() {
            if (isArchived) return false
            if (runStatus?.isActive == true) return true
            if (runStatus == RunStatus.ERROR || runStatus == RunStatus.EXPIRED || runStatus == RunStatus.CANCELLED) return false
            return lifecycle == AgentLifecycle.ACTIVE
        }
    val isError: Boolean get() = runStatus == RunStatus.ERROR || runStatus == RunStatus.EXPIRED

    companion object {
        fun repoSlugOf(url: String): String? {
            val cleaned = url.trim().removeSuffix("/").removeSuffix(".git")
            val marker = "github.com/"
            val idx = cleaned.indexOf(marker)
            val path = if (idx >= 0) cleaned.substring(idx + marker.length) else cleaned.substringAfter("://", cleaned).substringAfter('/', "")
            val parts = path.split('/').filter { it.isNotBlank() }
            return if (parts.size >= 2) "${parts[0]}/${parts[1]}" else parts.firstOrNull()
        }
    }
}

/** Visual state of the leading indicator in the agent list. */
enum class AgentIndicator { Running, Unread, Error, Read, Archived }

data class CursorUser(
    val apiKeyName: String,
    val email: String?,
    val firstName: String?,
    val lastName: String?,
    val userId: Long?,
) {
    val displayName: String
        get() = listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { email ?: apiKeyName }
    val initials: String
        get() {
            val fromName = listOfNotNull(firstName?.firstOrNull(), lastName?.firstOrNull()).joinToString("")
            if (fromName.isNotBlank()) return fromName.uppercase()
            return (email ?: apiKeyName).take(1).uppercase()
        }
}

data class ModelOption(
    val id: String,
    val displayName: String,
    val description: String? = null,
    val variants: List<ModelVariant> = emptyList(),
)

data class ModelVariant(
    val displayName: String,
    val params: List<ModelParam>,
    val isDefault: Boolean,
)

data class ModelParam(val id: String, val value: String)

data class Repository(val url: String) {
    val slug: String get() = Agent.repoSlugOf(url) ?: url
    val shortName: String get() = slug.substringAfterLast('/')
}
