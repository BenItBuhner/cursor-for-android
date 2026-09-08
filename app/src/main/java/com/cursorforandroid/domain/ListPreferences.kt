package com.cursorforandroid.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Grouping options offered by the Customize sheet. */
@Serializable
enum class GroupBy(val label: String) { Date("Date"), Repo("Repo"), Status("Status"), None("None") }

@Serializable
enum class SortOrder(val label: String) { Updated("Last updated"), Created("Created"), Name("Name") }

@Serializable
enum class StatusFilter(val label: String) { Read("Read"), Unread("Unread"), Running("Running"), Error("Error"), Archived("Archived") }

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
 * Cursor's public API exposes the execution environment (cloud / pool / machine) rather than the launching
 * client. "This device" is tracked locally for agents launched from this app.
 */
@Serializable
enum class SourceFilter(val label: String) { Cloud("Cloud"), Pool("Team pool"), Machine("My machine"), ThisDevice("This device") }

@Serializable
data class ListPreferences(
    val groupBy: GroupBy = GroupBy.Date,
    val sortOrder: SortOrder = SortOrder.Updated,
    /** `null` means every repository ("All"). */
    val repos: Set<String>? = null,
    val statuses: Set<StatusFilter> = setOf(StatusFilter.Read, StatusFilter.Unread, StatusFilter.Running, StatusFilter.Error),
    @Serializable(with = GitFilterSerializer::class)
    val git: Set<GitFilter> = GitFilter.entries.toSet(),
    val sources: Set<SourceFilter> = SourceFilter.entries.toSet(),
    val showWorkspace: Boolean = false,
    val showBranchStatus: Boolean = true,
    val showRuntime: Boolean = false,
) {
    val isDefault: Boolean get() = this == ListPreferences()

    /** Row preview text, e.g. "All", "Read +3", "cursor-for-android". Mirrors the iOS Customize sheet. */
    fun summaryFor(filter: FilterKind): String = when (filter) {
        FilterKind.Repo -> repos?.let { summarize(it.map { slug -> slug.substringAfterLast('/') }.sorted()) } ?: "All"
        FilterKind.Status -> summarize(StatusFilter.entries.filter { it in statuses }.map { it.label })
        FilterKind.Git -> summarize(GitFilter.entries.filter { it in git }.map { it.label })
        FilterKind.Source -> summarize(SourceFilter.entries.filter { it in sources }.map { it.label })
    }

    private fun summarize(selected: List<String>): String = when (selected.size) {
        0 -> "None"
        1 -> selected.first()
        else -> "${selected.first()} +${selected.size - 1}"
    }
}

enum class FilterKind(val label: String) { Repo("Repo"), Status("Status"), Git("Git"), Source("Source") }
