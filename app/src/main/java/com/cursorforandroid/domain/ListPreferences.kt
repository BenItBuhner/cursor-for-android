package com.cursorforandroid.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Grouping options offered by the Customize sheet. */
@Serializable
enum class GroupBy(val label: String) { Date("Date"), Repo("Repo"), Status("Status"), None("None") }

@Serializable
enum class SortOrder(val label: String) { Updated("Last updated"), Created("Created"), Name("Name") }

@Serializable
enum class StatusFilter(val label: String) { Read("Read"), Unread("Unread"), Running("Running"), Error("Error"), Archived("Archived"), Snoozed("Snoozed") }

object StatusFilterSetSerializer : LenientEnumSetSerializer<StatusFilter>(StatusFilter.entries)

/**
 * The Git filter: each state a chat's pull request can be in, plus the chats without one (whether or not they pushed
 * a branch). A chat whose PR state is not known yet shows while any of the four states is checked.
 */
@Serializable
enum class GitFilter(val label: String) {
    Open("Open"),
    Draft("Draft"),
    Merged("Merged"),
    Closed("Closed"),
    NoPullRequest("No pull request");

    companion object {
        /** The entries that stand for a pull request state, in the order the sheet lists them. */
        val pullRequestStates: List<GitFilter> = listOf(Open, Draft, Merged, Closed)

        fun of(state: PullRequestState): GitFilter = when (state) {
            PullRequestState.Open -> Open
            PullRequestState.Draft -> Draft
            PullRequestState.Merged -> Merged
            PullRequestState.Closed -> Closed
        }
    }
}

/**
 * Writes a [GitFilter] by name and reads any name a version of the app ever saved. The filter used to be Branch /
 * PullRequest / NoChanges — "PullRequest" stood for every pull request, so a saved one opens all four states, and
 * the other two both meant a chat without one. Names nobody knows decode to nothing rather than failing the whole
 * preferences record, which would reset every other setting along with this one.
 */
object GitFilterSerializer : KSerializer<Set<GitFilter>> {
    private val names = SetSerializer(String.serializer())
    override val descriptor: SerialDescriptor = names.descriptor

    override fun serialize(encoder: Encoder, value: Set<GitFilter>) = names.serialize(encoder, value.mapTo(LinkedHashSet()) { it.name })

    override fun deserialize(decoder: Decoder): Set<GitFilter> = names.deserialize(decoder).flatMapTo(LinkedHashSet()) { stored ->
        when (stored) {
            "PullRequest" -> GitFilter.pullRequestStates
            "Branch", "NoChanges" -> listOf(GitFilter.NoPullRequest)
            else -> listOfNotNull(GitFilter.entries.firstOrNull { it.name == stored })
        }
    }
}

/**
 * Writes a set of enum entries by name and reads back the names still known. A name nobody knows — an entry a later
 * version added, or an earlier one renamed — decodes to nothing rather than failing the whole preferences record,
 * which would reset every other setting along with this one.
 */
open class LenientEnumSetSerializer<E : Enum<E>>(private val entries: List<E>) : KSerializer<Set<E>> {
    private val names = SetSerializer(String.serializer())
    override val descriptor: SerialDescriptor = names.descriptor

    override fun serialize(encoder: Encoder, value: Set<E>) = names.serialize(encoder, value.mapTo(LinkedHashSet()) { it.name })

    override fun deserialize(decoder: Decoder): Set<E> =
        names.deserialize(decoder).mapNotNullTo(LinkedHashSet()) { stored -> entries.firstOrNull { it.name == stored } }
}

/**
 * The Environment filter: where the agent's machine is, as the public API's `env.type` reports it — Cursor's cloud, a
 * self-hosted team pool, or one of the user's own machines. An agent whose environment is not reported counts as cloud.
 */
@Serializable
enum class EnvironmentFilter(val label: String) {
    Cloud("Cloud"),
    Pool("Team pool"),
    Machine("My machine");

    companion object {
        fun of(env: EnvType): EnvironmentFilter = when (env) {
            EnvType.CLOUD, EnvType.UNKNOWN -> Cloud
            EnvType.POOL -> Pool
            EnvType.MACHINE -> Machine
        }
    }
}

object EnvironmentFilterSerializer : LenientEnumSetSerializer<EnvironmentFilter>(EnvironmentFilter.entries)

/**
 * The Source filter: where a chat was started, the way the Source filter of cursor.com/agents and the desktop Agents
 * window cut the same list — the Cursor apps, the chat and issue-tracker integrations, source-control comments, the
 * API and SDK, Cursor's own bots. Each entry stands for one or more of the account service's [AgentSource] values
 * (see [of]); [Other] takes the sources that are Cursor's internals (subagents, environment setup, the meta agent)
 * and a chat whose source the account has not reported. [ThisDevice] is the one entry the account does not know:
 * chats launched from this app reach the server as [AgentSource.API] like any other API client's, and are told apart
 * by the launches this device remembers.
 */
@Serializable
enum class SourceFilter(val label: String) {
    ThisDevice("This device"),
    Desktop("Cursor desktop"),
    Web("Cursor web"),
    Mobile("Mobile app"),
    Cli("CLI"),
    Slack("Slack"),
    Teams("Microsoft Teams"),
    Linear("Linear"),
    Jira("Jira"),
    SourceControl("Source control"),
    Api("API"),
    Sdk("SDK"),
    Automations("Automations"),
    Bugbot("Bugbot"),
    GrokBot("Grok Bot"),
    Other("Other");

    companion object {
        /** The entry a chat started from [source] falls under; [Other] for none reported. */
        fun of(source: AgentSource?): SourceFilter = when (source) {
            AgentSource.EDITOR, AgentSource.GLASS, AgentSource.LOCAL, AgentSource.AS_SUBAGENT_FROM_LOCAL -> Desktop
            AgentSource.WEBSITE, AgentSource.GRIND_WEB, AgentSource.GRAPHITE_CHAT_WEB -> Web
            AgentSource.IOS_APP -> Mobile
            AgentSource.CLI -> Cli
            AgentSource.SLACK -> Slack
            AgentSource.TEAMS -> Teams
            AgentSource.LINEAR -> Linear
            AgentSource.JIRA -> Jira
            AgentSource.GITHUB, AgentSource.GITLAB, AgentSource.BITBUCKET, AgentSource.ORIGIN -> SourceControl
            AgentSource.API -> Api
            AgentSource.SDK -> Sdk
            AgentSource.AUTOMATIONS -> Automations
            AgentSource.BUGBOT_AUTOFIX, AgentSource.GITHUB_CI_AUTOFIX -> Bugbot
            AgentSource.GROK_BOT -> GrokBot
            AgentSource.UNSPECIFIED, AgentSource.UNKNOWN, null,
            AgentSource.ENVIRONMENT_SETUP_WEB, AgentSource.ENVIRONMENT_SETUP_GLASS, AgentSource.ENVIRONMENT_SETUP_ONBOARDING_AUTO,
            AgentSource.GRAPHITE_FULL_SELF_DRIVING, AgentSource.FULL_SELF_DRIVING, AgentSource.QABOT_FRONTEND,
            AgentSource.SAND_CODING_SUBAGENT, AgentSource.CLOUD_META_AGENT, AgentSource.AS_SUBAGENT_FROM_CLOUD, AgentSource.AS_SIDE_CHAT_FROM_CLOUD,
            -> Other
        }
    }
}

object SourceFilterSerializer : LenientEnumSetSerializer<SourceFilter>(SourceFilter.entries)

@Serializable
data class ListPreferences(
    val groupBy: GroupBy = GroupBy.Date,
    val sortOrder: SortOrder = SortOrder.Updated,
    /** `null` means every repository ("All"). */
    val repos: Set<String>? = null,
    @Serializable(with = StatusFilterSetSerializer::class)
    val statuses: Set<StatusFilter> = setOf(StatusFilter.Read, StatusFilter.Unread, StatusFilter.Running, StatusFilter.Error),
    @Serializable(with = GitFilterSerializer::class)
    val git: Set<GitFilter> = GitFilter.entries.toSet(),
    @Serializable(with = SourceFilterSerializer::class)
    val sources: Set<SourceFilter> = SourceFilter.entries.toSet(),
    @Serializable(with = EnvironmentFilterSerializer::class)
    val environments: Set<EnvironmentFilter> = EnvironmentFilter.entries.toSet(),
    val showWorkspace: Boolean = false,
    val showBranchStatus: Boolean = true,
    val showRuntime: Boolean = false,
) {
    val isDefault: Boolean get() = this == ListPreferences()

    /** Row preview text, e.g. "All", "Read +3", "cursor-for-android". Mirrors the iOS Customize sheet. */
    fun summaryFor(filter: FilterKind): String = when (filter) {
        FilterKind.Repo -> repos?.let { summarize(it.map { slug -> slug.substringAfterLast('/') }.sorted(), total = null) } ?: "All"
        FilterKind.Status -> summarize(StatusFilter.entries.filter { it in statuses }.map { it.label }, StatusFilter.entries.size)
        FilterKind.Git -> summarize(GitFilter.entries.filter { it in git }.map { it.label }, GitFilter.entries.size)
        FilterKind.Source -> summarize(SourceFilter.entries.filter { it in sources }.map { it.label }, SourceFilter.entries.size)
        FilterKind.Environment -> summarize(EnvironmentFilter.entries.filter { it in environments }.map { it.label }, EnvironmentFilter.entries.size)
    }

    /** Every one of [total] entries checked reads "All"; a Repo selection has no total (every repository is `null`). */
    private fun summarize(selected: List<String>, total: Int?): String = when (selected.size) {
        0 -> "None"
        total -> "All"
        1 -> selected.first()
        else -> "${selected.first()} +${selected.size - 1}"
    }

    companion object {
        /**
         * Reads a stored record, whichever version of the app wrote it. Until the Source filter meant the launching
         * platform, its entries were the execution environments (`Cloud`, `Pool`, `Machine`) and `ThisDevice`: a
         * record with those under `sources` moves the environments to the Environment filter and keeps the source
         * choice it could express — whether this device's own chats show. A record that cannot be read at all
         * yields the defaults, as it always has.
         */
        fun decode(json: Json, stored: String): ListPreferences =
            runCatching { json.decodeFromJsonElement(serializer(), migrate(json.parseToJsonElement(stored))) }.getOrDefault(ListPreferences())

        internal fun migrate(element: JsonElement): JsonElement {
            val record = element as? JsonObject ?: return element
            val sources = (record["sources"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return element
            val legacyEnvironments = sources.filter { it in LEGACY_ENVIRONMENT_NAMES }
            if (legacyEnvironments.isEmpty()) return element
            val environments = legacyEnvironments.map { JsonPrimitive(it) }
            val migratedSources = SourceFilter.entries
                .filter { it != SourceFilter.ThisDevice || SourceFilter.ThisDevice.name in sources }
                .map { JsonPrimitive(it.name) }
            return JsonObject(record + ("sources" to JsonArray(migratedSources)) + ("environments" to JsonArray(environments)))
        }

        /** What `sources` held before the Environment filter existed; the names are the [EnvironmentFilter]'s still. */
        private val LEGACY_ENVIRONMENT_NAMES = EnvironmentFilter.entries.map { it.name }.toSet()
    }
}

enum class FilterKind(val label: String) { Repo("Repo"), Status("Status"), Git("Git"), Source("Source"), Environment("Environment") }
