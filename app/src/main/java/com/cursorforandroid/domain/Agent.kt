package com.cursorforandroid.domain

import com.cursorforandroid.data.api.RecordFields
import kotlinx.serialization.Serializable

/**
 * Durable agent lifecycle as reported by `GET /v1/agents`. In practice the server reports `ACTIVE` for every agent
 * that is not archived, whether or not a turn is running — the value never changes when a run finishes, and
 * execution state lives on the runs (`GET /v1/agents/{id}/runs/{runId}`). So `ACTIVE` says nothing about running;
 * `IDLE` (when reported) and `ARCHIVED` do say a turn is not.
 */
enum class AgentLifecycle { ACTIVE, IDLE, ARCHIVED, UNKNOWN;
    /** True when the lifecycle rules out a running turn; false when it is silent on the matter. */
    val excludesRunning: Boolean get() = this == IDLE || this == ARCHIVED

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
@Serializable
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

/**
 * Where a chat was started: `aiserver.v1.BackgroundComposerSource`, the field the account service keeps on every
 * agent and the one behind the Source filter of cursor.com/agents and the desktop Agents window. The public API does
 * not report it (its `env` says where the agent runs, not what launched it), so it is read from the account's list
 * alongside the pins. Every value the proto names is here, by its number; [UNKNOWN] is one this build has not heard
 * of, and null on a row is one the account has not been asked about yet.
 */
enum class AgentSource(val number: Int) {
    UNSPECIFIED(0),
    EDITOR(1),
    SLACK(2),
    WEBSITE(3),
    LINEAR(4),
    IOS_APP(5),
    API(6),
    GITHUB(7),
    CLI(8),
    GITHUB_CI_AUTOFIX(9),
    GITLAB(10),
    ENVIRONMENT_SETUP_WEB(11),
    GRIND_WEB(12),
    BUGBOT_AUTOFIX(13),
    AUTOMATIONS(14),
    GRAPHITE_CHAT_WEB(15),
    GLASS(16),
    GRAPHITE_FULL_SELF_DRIVING(17),
    TEAMS(18),
    LOCAL(19),
    JIRA(20),
    SDK(21),
    FULL_SELF_DRIVING(22),
    QABOT_FRONTEND(23),
    AS_SUBAGENT_FROM_LOCAL(24),
    ENVIRONMENT_SETUP_GLASS(25),
    SAND_CODING_SUBAGENT(26),
    BITBUCKET(27),
    CLOUD_META_AGENT(28),
    AS_SUBAGENT_FROM_CLOUD(29),
    ENVIRONMENT_SETUP_ONBOARDING_AUTO(30),
    ORIGIN(31),
    AS_SIDE_CHAT_FROM_CLOUD(32),
    GROK_BOT(33),
    UNKNOWN(-1);

    /** The enum's name on the wire (proto3 JSON spells enums by their full name). */
    val wireName: String get() = "$WIRE_PREFIX$name"

    companion object {
        const val WIRE_PREFIX = "BACKGROUND_COMPOSER_SOURCE_"

        /**
         * Reads the value as Connect JSON delivers it: the proto name (`BACKGROUND_COMPOSER_SOURCE_SLACK`), the bare
         * name (`SLACK`), or the number when a server encodes enums that way. Null for nothing at all; [UNKNOWN] for
         * a value this build does not know, which is still an answer (the account did say where the chat came from).
         */
        fun parse(raw: String?): AgentSource? {
            val token = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            token.toIntOrNull()?.let { number -> return entries.firstOrNull { it.number == number } ?: UNKNOWN }
            val name = token.uppercase().removePrefix(WIRE_PREFIX)
            return entries.firstOrNull { it.name == name && it != UNKNOWN } ?: UNKNOWN
        }
    }
}

@Serializable
data class GitBranch(
    val repoUrl: String,
    val branch: String?,
    val prUrl: String?,
)

/**
 * A Cursor Project's icon and colour, as the account records them on the Project's chat
 * (`aiserver.v1.ProjectMetadata.appearance`): [icon] is one of the Agents Window's icon names — the 660 glyphs of
 * its icon font, `lightning` (the picker's starting point), `rocket`, `folder`, `logo-notion`, `file-type-rust`, …,
 * or one of their alias spellings — and [colorId] one of its ten tones (`default`, `green`, `cyan`, `blue`,
 * `purple`, `magenta`, `orange`, `yellow`, `red`, `brand`). Kept as the strings the account uses; `ProjectIcons`
 * and `ProjectPalette` map them to glyphs and colours, and stand in (the cube, the default tone) for names a later
 * build introduces.
 */
@Serializable
data class ProjectAppearance(val icon: String, val colorId: String)

/**
 * How a chat hangs off another one, as the account's list reports it: a worker the Project's coordinator created or
 * adopted (`managerAgentId`), a side chat branched off a chat (`sideChatInfo.parentBcId`), or a cloud subagent the
 * parent spawned for a task or a subscription (`cloudSubagentParent.parentAgentId`).
 */
@Serializable
enum class AgentParentKind { PROJECT_WORKER, SIDE_CHAT, SUBAGENT }

/** The chat another chat belongs to, and in what capacity (see [AgentParentKind]). */
@Serializable
data class AgentParent(val id: String, val kind: AgentParentKind)

/**
 * Where a chat belongs, by the desktop Agents Window's two predicates and nothing else (see [AgentsWindowList]): a
 * [PROJECT_CHILD] is a chat with a parent link (`PJr`: a worker, side chat or cloud subagent, drawn under its parent's
 * row and never among the top-level rows, whatever has become of the parent); a [PROJECT_ROOT] is a top-level chat
 * whose record carries `projectMetadata` (`kf`: a Cursor Project's coordinator); a [PRIMARY] chat is every other
 * top-level chat, one of the account's own.
 */
@Serializable
enum class AgentScope {
    PRIMARY,
    PROJECT_ROOT,
    PROJECT_CHILD,
    ;

    companion object {
        /**
         * The scope a record's facts add up to, as the desktop reads them: a parent link makes a child (`PJr`), the
         * Project flag on a chat with none makes a root (`kf`), everything else — whatever its source — is a chat
         * of the account's own (`mQa`).
         */
        fun of(isProject: Boolean, parent: AgentParent?): AgentScope = when {
            parent != null -> PROJECT_CHILD
            isProject -> PROJECT_ROOT
            else -> PRIMARY
        }
    }
}

/**
 * Which word gave a chat its parent link or its Project flag ([Agent.scopeSignal]), in the desktop's terms (see
 * [AgentsWindowList]): the chat's own record — `cloudSubagentParent`, `sideChatInfo.parentBcId`, `managerAgentId`,
 * `projectMetadata` — ([ACCOUNT_RECORD]); a root's `ListWorkersForManager` answer naming it, the desktop's seeded
 * `managerAgentId` ([MEMBERSHIP]); a root's `ListBackgroundComposerChildren` answer ([CHILDREN_LIST]); an action taken
 * from this app — a worker created, adopted or moved, the desktop's `_stampListedCloudAgentManager` ([ACTION]); or a
 * coordinator's own transcript showing `create_agent` bring the worker into being, the one word default mode has for
 * the `managerAgentId` the record would carry ([COORDINATOR_CREATED]). Nothing else places a chat: not a mention in
 * a transcript, not a source, not a guess. The diagnostics export names the signal per row.
 *
 * [HIDDEN_SOURCE] and [COORDINATOR_TRANSCRIPT] are older builds' words, kept so their disk copies still read; a
 * placement carrying either is dropped on restore and never made again.
 */
@Serializable
enum class LineageSignal {
    ACCOUNT_RECORD,
    MEMBERSHIP,
    CHILDREN_LIST,
    @Deprecated("A source places nothing (the desktop reads the record's parent link alone); kept for older disk copies.")
    HIDDEN_SOURCE,
    @Deprecated("A mention in a coordinator's transcript places nothing; kept for older disk copies.")
    COORDINATOR_TRANSCRIPT,
    ACTION,
    COORDINATOR_CREATED,
    ;

    /** Words of the account service itself or of an action taken against it; a transcript's word yields to a record. */
    val isAuthoritative: Boolean get() = this != COORDINATOR_TRANSCRIPT && this != COORDINATOR_CREATED

    /** A word this build still places by: the desktop's own fields and stamps. The two legacy hints are not. */
    val isPlacing: Boolean get() = this != COORDINATOR_TRANSCRIPT && this != HIDDEN_SOURCE

    /** Positive evidence of lineage: every placing word is; a mention ([COORDINATOR_TRANSCRIPT]) never was. */
    val isPositiveEvidence: Boolean get() = this != COORDINATOR_TRANSCRIPT

    /**
     * Whether a complete membership or children answer that no longer names the chat releases the stamp. A
     * membership's, a children list's or a transcript's word is; the chat's own record naming its parent is not (the
     * record is the chat's, the membership the Project's), nor is an action taken here a moment ago, which the
     * account may not list yet.
     */
    val isRetractable: Boolean get() = this == MEMBERSHIP || this == CHILDREN_LIST || this == COORDINATOR_TRANSCRIPT || this == COORDINATOR_CREATED

    /**
     * The one word that names a Project: the chat's own record carrying `projectMetadata` ([ACCOUNT_RECORD]) — the
     * desktop's `isProject`. A membership answer, an action, a children list, a transcript or a source never makes a
     * Project of a chat: a chat that manages workers without the flag is an ordinary chat with rows nested under it,
     * as it is in the Agents Window.
     */
    val isRootEvidence: Boolean get() = this == ACCOUNT_RECORD
}

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
    /**
     * When the public API last saw the chat change: `GET /v1/agents` `updatedAt`, raised by the latest run's
     * `updatedAt` and by this device's own sends within a grace (see `reconcileUpdatedAt`). It moves with anything
     * the service does to the row — a status sweep, a pull-request check, a VM expiring — so it is not what the
     * sidebar dates a chat by once the account's record has spoken (see [listedAtMillis]).
     */
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
    /**
     * The model the chat runs on as the account's record names it (see [AccountModel]) — the word of the server,
     * which outranks what this device remembers sending; null until the account's list has said (Extended mode),
     * and always null in default mode, where nothing documented carries an agent's model.
     */
    val accountModel: AccountModel? = null,
    val durationMs: Long? = null,
    /**
     * Where the chat was started, as the account service has it (see [AgentSource]); null until the account's list
     * has said. Kept across refreshes like the model: the public list never carries it.
     */
    val source: AgentSource? = null,
    /**
     * True when the account marks the chat as a Cursor Project: the coordinator chat that plans a body of work,
     * delegates it to worker agents and keeps the Project's shared context. It is what the Agents Window calls
     * `isProject` — the account's record carries `projectMetadata` — and, like [source], only the account's list
     * says so; the public API has no notion of Projects. A worker under a Project is never one itself.
     */
    val isProject: Boolean = false,
    /** The Project's icon and colour; null for a chat that is not a Project, or a Project that has not set them. */
    val projectAppearance: ProjectAppearance? = null,
    /**
     * The chat this one hangs off — the desktop's `subagentParentId`: the agent that spawned it as a cloud subagent
     * (`cloudSubagentParent.parentAgentId`), the chat it branched from as a side chat (`sideChatInfo.parentBcId`), or
     * the Project whose coordinator manages it (`managerAgentId`), in that precedence — as the account's record
     * reports it, or as a membership answer, an action here or a coordinator's `create_agent` stamped it (see
     * [LineageSignal]); null for a chat of its own. The one fact the sidebar nests by (see [AgentsWindowList]).
     */
    val parent: AgentParent? = null,
    /**
     * The agent has asked a question and is waiting on the answer (`hasPendingInteraction` on the account's record):
     * a Project's view marks such a primary as needing input. Only the account says; false until it has.
     */
    val hasPendingInteraction: Boolean = false,
    /** Which word gave the row its parent link or Project flag (see [LineageSignal]); null while nothing has. */
    val scopeSignal: LineageSignal? = null,
    /**
     * The raw values of the fields the desktop's predicates read, as the chat's account record last carried them
     * (see `RecordFields`); null while no account record has been read for the row — a row the public API alone
     * gave, which in Extended mode is fetched by id so the record can place it (see `AgentRepository`).
     */
    val record: RecordFields? = null,
    /**
     * When the chat was last active by the account's record — the field the desktop Agents Window sorts and buckets
     * by (Cursor 3.20.21: the cloud agent's `updatedAt` is `lastMessageActivityAtMs ?? updatedAtMs`; the header's
     * `lastUpdatedAt` is that, else `createdAt`; `rUm` orders by it and `f3v` buckets by it). Set from the record
     * alone (see `AgentRepository`), verbatim — never raised to the public row's `updatedAt`, never to the time the
     * app happened to refresh — and moved forward only by a message sent or finished on this device until the next
     * record read says. Null until a record has been read: in default mode always, the public API having no such
     * field.
     */
    val activityAtMillis: Long? = null,
) {
    /**
     * The time the sidebar lists the chat by — its section and its order (see `AgentListOrganizer`), its read
     * marker, its relative-time label: the desktop's `lastUpdatedAt`. The record's word when it has been read, else
     * the public API's [updatedAtMillis] (the one time the public API gives; it is what the desktop reads too when a
     * record carries no message activity).
     */
    val listedAtMillis: Long get() = activityAtMillis ?: updatedAtMillis

    /**
     * A message sent to or finished by the chat on this device at [atMillis]: the chat is active now, whatever the
     * record last said; the record's next read replaces the stamp with the account's own. Never moves the listed
     * time backwards.
     */
    fun touched(atMillis: Long): Agent = copy(
        updatedAtMillis = maxOf(updatedAtMillis, atMillis),
        activityAtMillis = activityAtMillis?.let { maxOf(it, atMillis) },
    )

    /**
     * Where the chat belongs (see [AgentScope]): the desktop's two predicates, in the desktop's order — a parent link
     * makes a child (`PJr`), the Project flag on a chat with none makes a root (`kf`), everything else is a chat of
     * the account's own (`mQa`). Nothing else is read: not the source, not what an older build once decided.
     */
    val scope: AgentScope get() = AgentScope.of(isProject, parent)
    /**
     * A Project at the top of its tree: the chat the Projects group lists. The Agents Window's rule as well — a
     * Project that is itself somebody's subagent or side chat is shown where its parent is.
     */
    val isProjectRoot: Boolean get() = scope == AgentScope.PROJECT_ROOT
    /** A worker, side chat or subagent: shown inside its parent's surface, never among the primary rows. */
    val isProjectChild: Boolean get() = scope == AgentScope.PROJECT_CHILD
    /**
     * A Project's coordinator or anything spawned inside a Project: what the Project's own view is for. No
     * notification is posted for it, and no primary surface beyond the sidebar's tree (recents, the widget) lists it.
     */
    val isProjectScoped: Boolean get() = scope != AgentScope.PRIMARY
    /**
     * [isProjectScoped]: every word that places a chat now is positive evidence (the record's own fields, a
     * membership or children answer, an action taken here, a coordinator's `create_agent`), so the two are one. Kept
     * under the name the notification and widget code reads.
     */
    val isProjectScopedByEvidence: Boolean get() = isProjectScoped
    /** Drawn as a Project — with its icon and colour: the desktop's `kf`, a top-level chat the record flags. */
    val looksLikeProject: Boolean get() = isProjectRoot
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
     * Only the latest run's status can say a turn is going. The lifecycle cannot: the server reports `ACTIVE` for
     * every unarchived agent, finished or not (see [AgentLifecycle]), so a row whose run status is unknown is shown
     * at rest until a run-level source — the legacy list, the run record, its stream — reports otherwise. Guessing
     * "running" from the lifecycle is what put spinners on, and then "updated just now" under, chats from weeks ago.
     */
    val isRunning: Boolean get() = !isArchived && runStatus?.isActive == true
    val isError: Boolean get() = runStatus == RunStatus.ERROR || runStatus == RunStatus.EXPIRED
    /** The row has no run-level word on its latest turn yet: nothing remembered, or nothing this build recognises. */
    val runStatusUnknown: Boolean get() = runStatus == null || runStatus == RunStatus.UNKNOWN

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
enum class AgentIndicator { Running, Unread, Error, Read, Archived, Snoozed }

data class CursorUser(
    val apiKeyName: String,
    val email: String?,
    val firstName: String?,
    val lastName: String?,
    val userId: Long?,
    /** The account's picture, from the account service; the public API's `/v1/me` has none. Null shows the initials. */
    val profilePictureUrl: String? = null,
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
    /** `aliases` from `GET /v1/models`: "Alternate IDs that resolve to the same model" (`composer-latest`, `composer`, …). */
    val aliases: List<String> = emptyList(),
) {
    /** The catalog's Auto row: the one Cursor's router picks the model for, named "Auto" (the desktop's `default`). */
    val isAuto: Boolean get() = displayName.equals(AccountModel.AUTO_LABEL, ignoreCase = true) || id == AccountModel.AUTO_ID || AccountModel.AUTO_ID in aliases
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
 * The model a chat runs on as the account's record names it: `aiserver.v1.BackgroundComposer.requested_model`
 * (`agent.v1.RequestedModel {model_id, parameters[{id, value}], max_mode}` — what the desktop, the web and the iOS
 * app send when they start or follow up a chat; Cursor 3.20.21) or, on records from before it, `model_details
 * {model_name, max_mode}`. The ids are the desktop's vocabulary, which `GET /v1/models` shares (`id` and `aliases`);
 * Auto is `default` there (the desktop's own `modelNameDisplayLookup`: `"default"` → "Auto"). The documented API
 * carries nothing of the kind — an agent record has no model, the v0 transcript has none, the SDK's `agent.model`
 * is client memory — so only the account's list (Extended mode) fills this.
 */
@Serializable
data class AccountModel(
    val modelId: String,
    val params: List<ModelParam> = emptyList(),
    val maxMode: Boolean = false,
) {
    /** The desktop's `default`: Cursor's router picks the model. */
    val isAuto: Boolean get() = modelId == AUTO_ID

    /**
     * What to call the model before any catalog has answered: "Auto" for `default`, else the name [modelId] spells
     * (`claude-opus-5-5-max-fast` → "Claude Opus 5.5"; see [ModelSlugs.readableName]). The id itself is what requests carry.
     */
    val fallbackLabel: String get() = ModelSlugs.readableName(emptyList(), modelId)

    companion object {
        const val AUTO_ID = "default"
        const val AUTO_LABEL = "Auto"

        /** [modelId] blank is no model; the account leaves the field out rather than empty, but a blank is read the same way. */
        fun of(modelId: String?, params: List<ModelParam> = emptyList(), maxMode: Boolean = false): AccountModel? =
            modelId?.trim()?.takeIf { it.isNotEmpty() }?.let { AccountModel(it, params, maxMode) }
    }
}

/**
 * The picker entry a request with `model.id` [id] and `model.params` [params] was built from, or null when this
 * catalog cannot place it. An id or alias the catalog lists decides: it names the model whatever has happened to its
 * parameters since, so the variant is the nearest one to [params] (see [ModelOption.variantNearest]). Any other
 * spelling — a legacy slug, a variant string — is read by [ModelSlugs.resolve].
 */
fun List<ModelOption>.choiceFor(id: String, params: List<ModelParam>): ModelChoice? = ModelSlugs.resolve(this, id, params)

/**
 * The picker entry the account's record names (see [AccountModel]): its `model_id` read by [ModelSlugs.resolve] —
 * the catalog row with that id or alias, the Auto row for `default`, or the model and variant a slug such as
 * `claude-opus-5-5-max-fast` spells — the record's own parameters over the ones the id spells. Null when the catalog
 * cannot place it: [ModelSlugs.readableName] then names it.
 */
fun List<ModelOption>.choiceFor(model: AccountModel): ModelChoice? = ModelSlugs.resolve(this, model.modelId, model.params)

/** The catalog's Auto row (see [ModelOption.isAuto]), or null when this catalog offers none. */
fun List<ModelOption>.autoOption(): ModelOption? = firstOrNull { it.isAuto }

/**
 * The catalog row [id] names. A miss is null — never the first row. The live catalogue leads with Auto, and
 * substituting that for a last-used model is what made the new-chat picker look reset on every cold start.
 */
fun List<ModelOption>.named(id: String?): ModelOption? = id?.let { wanted -> firstOrNull { it.id == wanted } }

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

/**
 * The picker's order: the selected model first so tapping one lifts it to the top with its options, then any other
 * pinned models (most recently pinned first), then the rest of the catalog in the API's order.
 */
fun List<ModelOption>.arrangedForPicker(pinnedIds: List<String>, selectedId: String?): List<ModelOption> {
    if (isEmpty()) return this
    val index = associateBy { it.id }
    val selected = selectedId?.let { index[it] }
    val pinned = pinnedIds.mapNotNull { index[it] }.filter { it.id != selected?.id }
    val seen = buildSet {
        selected?.id?.let(::add)
        pinned.forEach { add(it.id) }
    }
    return listOfNotNull(selected) + pinned + filter { it.id !in seen }
}

@Serializable
data class Repository(val url: String) {
    val slug: String get() = Agent.repoSlugOf(url) ?: url
    val shortName: String get() = slug.substringAfterLast('/')

    /**
     * True when [url] names this repository however it is spelled — with or without the scheme or `.git`, in any
     * case: the fleet endpoints, the agent list and `GET /v1/repositories` do not spell a URL the same way.
     */
    fun isAt(url: String): Boolean = slug.equals(Repository(url).slug, ignoreCase = true)
}
