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
    /**
     * The model the chat runs on, as far as this device knows. The API never reports an agent's model, so these are
     * what was last sent from here — at launch, or with a follow-up that switched it — and stay null for chats
     * started elsewhere. [modelDisplayName] is the model's name as the catalog gave it (read it through [modelName]);
     * [modelId] and [modelParams] are the request's `model.id` and `model.params`, so the picker can find the same
     * entry again.
     */
    val modelDisplayName: String? = null,
    val modelId: String? = null,
    val modelParams: List<ModelParam> = emptyList(),
    val durationMs: Long? = null,
) {
    /**
     * The name of the chat's model, for the composer chip and the list row. Rows recorded by earlier versions carry
     * the variant's parameters after the name ("Composer 2 · Fast", "Claude 5 · 1M context · Max effort"); only the
     * name is wanted, so anything from the first separator on is dropped.
     */
    val modelName: String? get() = modelDisplayName?.substringBefore(LEGACY_LABEL_SEPARATOR)?.trim()?.takeIf { it.isNotEmpty() }
    /** `owner/name` derived from the GitHub URL, or null for no-repo agents. */
    val repoSlug: String? get() = repoUrl?.let(::repoSlugOf)
    val repoShortName: String? get() = repoSlug?.substringAfterLast('/')
    val branchName: String? get() = branches.firstOrNull { it.branch != null }?.branch
    val prUrl: String? get() = branches.firstOrNull { it.prUrl != null }?.prUrl
    val hasBranch: Boolean get() = branchName != null
    val hasPullRequest: Boolean get() = prUrl != null
    val isArchived: Boolean get() = lifecycle == AgentLifecycle.ARCHIVED
    /**
     * A known run status decides; without one — or with one this build does not recognise — the lifecycle does, as
     * `ACTIVE` means a turn is running, about to start, or waiting on background work.
     */
    val isRunning: Boolean
        get() = !isArchived && (runStatus?.isActive == true || ((runStatus == null || runStatus == RunStatus.UNKNOWN) && lifecycle == AgentLifecycle.ACTIVE))
    val isError: Boolean get() = runStatus == RunStatus.ERROR || runStatus == RunStatus.EXPIRED

    companion object {
        /** What earlier versions put between a model's name and its parameters in the label they recorded. */
        const val LEGACY_LABEL_SEPARATOR = " · "

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
    /** A parameter's name in words — "Effort", "Fast" — from the API's definition when it has one. */
    fun parameterName(parameterId: String): String =
        parameters.firstOrNull { it.id == parameterId }?.displayName?.takeIf { it.isNotBlank() } ?: humanize(parameterId)

    /** A value's name in words — "High", "1M", "Fast" — from the API's definition when it has one. */
    fun valueName(parameterId: String, value: String): String = declaredValueName(parameterId, value) ?: humanize(value)

    private fun declaredValueName(parameterId: String, value: String): String? =
        parameters.firstOrNull { it.id == parameterId }?.values?.firstOrNull { it.value == value }?.displayName?.takeIf { it.isNotBlank() }

    /**
     * The variant's parameters in words — "Fast", "Fast off", "1M context · Max effort" — resolved through
     * [parameters] where the API provides display names; null for a variant without parameters.
     */
    fun qualifier(variant: ModelVariant): String? {
        if (variant.params.isEmpty()) return null
        return variant.params.joinToString(" · ") { param ->
            val name = parameterName(param.id)
            val valueName = declaredValueName(param.id, param.value)
            when {
                valueName != null -> if (valueName.equals(name, ignoreCase = true)) valueName else "$valueName ${name.lowercase()}"
                param.value.equals("true", ignoreCase = true) -> name
                param.value.equals("false", ignoreCase = true) -> "$name off"
                else -> "${humanize(param.value)} ${name.lowercase()}"
            }
        }
    }

    /**
     * The knobs the picker offers for this model — "Effort", "Fast", "Context" — one per parameter that takes more
     * than one value across [variants]. Parameters keep the order and names of the API's definitions, with any the
     * variants use but the definitions omit appended; a value no variant carries is left out, so every choice the
     * picker offers resolves to a combination the API accepts. A parameter every variant agrees on is not a knob
     * (the row's subtitle still spells it out).
     */
    val axes: List<ModelAxis>
        get() {
            val ids = (parameters.map { it.id } + variants.flatMap { v -> v.params.map { it.id } }).distinct()
            return ids.mapNotNull { id ->
                val used = variants.mapNotNull { it.param(id) }.distinct()
                if (used.size < 2) return@mapNotNull null
                val declared = parameters.firstOrNull { it.id == id }?.values?.map { it.value }.orEmpty()
                val ordered = declared.filter { it in used } + used.filter { it !in declared }
                ModelAxis(id, parameterName(id), ordered.map { ModelAxisValue(it, valueName(id, it)) })
            }
        }

    /**
     * The variant to move to when [parameterId] is set to [value] while [current] is selected: the one that differs
     * from [current] in that parameter alone when the API lists it, else the closest one that has the value (fewest
     * other parameters changed, then the one nearest the API's default); null when no variant has the value at all.
     */
    fun variantWith(current: ModelVariant?, parameterId: String, value: String): ModelVariant? {
        fun ModelVariant.others() = params.filterNot { it.id == parameterId }.toSet()
        fun distance(a: Set<ModelParam>, b: Set<ModelParam>) = (a - b).size + (b - a).size
        val kept = current?.others() ?: emptySet()
        val preferred = defaultVariant?.others() ?: emptySet()
        return variants
            .filter { it.param(parameterId) == value }
            .minWithOrNull(compareBy<ModelVariant> { distance(it.others(), kept) }.thenBy { distance(it.others(), preferred) })
    }

    /** The variant a fresh selection of this model starts with: the API's default, else the first one. */
    val defaultVariant: ModelVariant? get() = variants.firstOrNull { it.isDefault } ?: variants.firstOrNull()

    /** The variant whose parameters are exactly [params] (an empty map matches a parameter-less variant). */
    fun variantWithParams(params: Map<String, String>): ModelVariant? =
        variants.firstOrNull { v -> v.params.associate { it.id to it.value } == params }

    /**
     * The variant a request with `model.params` [params] stands for in this catalog: the one with exactly those
     * parameters when the API still lists it, else the nearest — fewest parameters set differently, the API's default
     * ahead of equally near ones — since a parameter the API has renamed, added or dropped since the request was made
     * does not change which model the chat runs on. Null for a model without variants.
     */
    fun variantNearest(params: List<ModelParam>): ModelVariant? {
        variantWithParams(params.associate { it.id to it.value })?.let { return it }
        val wanted = params.toSet()
        fun distance(v: ModelVariant): Int = (v.params.toSet() - wanted).size + (wanted - v.params.toSet()).size
        return variants.minWithOrNull(compareBy<ModelVariant> { distance(it) }.thenBy { !it.isDefault })
    }

    /**
     * The legacy chip label of [variant]: the variant's name with its parameters appended when siblings shared the
     * name — what earlier versions recorded on a chat's row, and all they recorded (see [choiceLabelled]).
     */
    internal fun legacyLabelFor(variant: ModelVariant): String {
        val ambiguous = variants.count { it.displayName == variant.displayName } > 1
        val qualifier = qualifier(variant)
        return if (ambiguous && qualifier != null) "${variant.displayName}${Agent.LEGACY_LABEL_SEPARATOR}$qualifier" else variant.displayName
    }

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
) {
    /** The value this variant sets for [parameterId], or null when it leaves the parameter unset. */
    fun param(parameterId: String): String? = params.firstOrNull { it.id == parameterId }?.value
}

/**
 * One tunable parameter of a model as the picker shows it — "Effort" with Low / Medium / High, "Fast" on or off —
 * limited to the values the model's variants actually use.
 */
data class ModelAxis(
    val id: String,
    val displayName: String,
    val values: List<ModelAxisValue>,
) {
    /** A `true` / `false` parameter, shown as a toggle rather than a row of choices. */
    val isSwitch: Boolean get() = values.size == 2 && values.map { it.value.lowercase() }.toSet() == setOf("true", "false")

    /** The value that switches an [isSwitch] parameter on, as the API spells it. */
    val onValue: String get() = values.first { it.value.equals("true", ignoreCase = true) }.value

    /** The value that switches an [isSwitch] parameter off, as the API spells it. */
    val offValue: String get() = values.first { it.value.equals("false", ignoreCase = true) }.value
}

data class ModelAxisValue(val value: String, val displayName: String)

@Serializable
data class ModelParam(val id: String, val value: String)

/** One entry of the model picker: a model and, when it has variants, the variant — what a request's `model` is built from. */
data class ModelChoice(val model: ModelOption, val variant: ModelVariant?) {
    /**
     * What the composer chip calls the choice: the model's name, nothing else. Its parameters — effort, speed,
     * context window, thinking — are the picker's business, where they show under the model as its pickers.
     */
    val label: String get() = model.displayName
    val params: List<ModelParam> get() = variant?.params.orEmpty()
}

/**
 * The picker entry a request with `model.id` [id] and `model.params` [params] was built from, or null when this
 * catalog no longer lists the model. The id decides: it names the model whatever has happened to its parameters
 * since, so the variant is the nearest one to [params] (see [ModelOption.variantNearest]) rather than an exact
 * match or nothing.
 */
fun List<ModelOption>.choiceFor(id: String, params: List<ModelParam>): ModelChoice? {
    val model = firstOrNull { it.id == id } ?: return null
    return ModelChoice(model, model.variantNearest(params))
}

/**
 * The picker entry recorded as [label] on a chat's row before the id and parameters were kept alongside it. The
 * label is the model's name, or — from versions before that — the variant's name with its parameters appended
 * when siblings shared it; a bare model name lands on the model's default variant.
 */
fun List<ModelOption>.choiceLabelled(label: String): ModelChoice? {
    firstOrNull { it.displayName == label }?.let { return ModelChoice(it, it.defaultVariant) }
    return firstNotNullOfOrNull { model ->
        model.variants.firstOrNull { model.legacyLabelFor(it) == label }?.let { ModelChoice(model, it) }
    }
}

@Serializable
data class Repository(val url: String) {
    val slug: String get() = Agent.repoSlugOf(url) ?: url
    val shortName: String get() = slug.substringAfterLast('/')
}
