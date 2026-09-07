package com.cursorforandroid.domain

import kotlinx.serialization.Serializable

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

@Serializable
data class GitBranch(
    val repoUrl: String,
    val branch: String?,
    val prUrl: String?,
)

/** The list row. Serializable so the last known list can be restored from disk before the network answers. */
@Serializable
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
    val isRunning: Boolean get() = !isArchived && (runStatus?.isActive == true || (runStatus == null && lifecycle == AgentLifecycle.ACTIVE))
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

@Serializable
data class ModelOption(
    val id: String,
    val displayName: String,
    val description: String? = null,
    val variants: List<ModelVariant> = emptyList(),
    /** The `parameters` definitions from `GET /v1/models`: what each variant's `params` mean in words. */
    val parameters: List<ModelParameter> = emptyList(),
) {
    /**
     * The variant's parameters in words — "Fast", "Fast off", "1M context · Max effort" — resolved through
     * [parameters] where the API provides display names; null for a variant without parameters.
     */
    fun qualifier(variant: ModelVariant): String? {
        if (variant.params.isEmpty()) return null
        return variant.params.joinToString(" · ") { param ->
            val definition = parameters.firstOrNull { it.id == param.id }
            val name = definition?.displayName?.takeIf { it.isNotBlank() } ?: humanize(param.id)
            val valueName = definition?.values?.firstOrNull { it.value == param.value }?.displayName?.takeIf { it.isNotBlank() }
            when {
                valueName != null -> if (valueName.equals(name, ignoreCase = true)) valueName else "$valueName ${name.lowercase()}"
                param.value.equals("true", ignoreCase = true) -> name
                param.value.equals("false", ignoreCase = true) -> "$name off"
                else -> "${humanize(param.value)} ${name.lowercase()}"
            }
        }
    }

    /**
     * What the composer chip and the picker call [variant]. `GET /v1/models` reuses the model's name for every
     * variant of a model ("Composer 2" with `fast` on and off), so the parameters are appended whenever the name
     * alone does not single the variant out among its siblings.
     */
    fun labelFor(variant: ModelVariant?): String {
        if (variant == null) return displayName
        val ambiguous = variants.count { it.displayName == variant.displayName } > 1
        val qualifier = qualifier(variant)
        return if (ambiguous && qualifier != null) "${variant.displayName} · $qualifier" else variant.displayName
    }

    /** The variant a fresh selection of this model starts with: the API's default, else the first one. */
    val defaultVariant: ModelVariant? get() = variants.firstOrNull { it.isDefault } ?: variants.firstOrNull()

    /** The variant whose parameters are exactly [params] (an empty map matches a parameter-less variant). */
    fun variantWithParams(params: Map<String, String>): ModelVariant? =
        variants.firstOrNull { v -> v.params.associate { it.id to it.value } == params }

    companion object {
        private fun humanize(raw: String): String = raw.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.uppercaseChar() }
    }
}

@Serializable
data class ModelParameter(
    val id: String,
    val displayName: String? = null,
    val values: List<ModelParameterValue> = emptyList(),
)

@Serializable
data class ModelParameterValue(val value: String, val displayName: String? = null)

@Serializable
data class ModelVariant(
    val displayName: String,
    val params: List<ModelParam>,
    val isDefault: Boolean,
    val description: String? = null,
)

@Serializable
data class ModelParam(val id: String, val value: String)

@Serializable
data class Repository(val url: String) {
    val slug: String get() = Agent.repoSlugOf(url) ?: url
    val shortName: String get() = slug.substringAfterLast('/')
}
