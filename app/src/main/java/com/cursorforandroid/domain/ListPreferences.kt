package com.cursorforandroid.domain

import kotlinx.serialization.Serializable

/** Grouping options offered by the Customize sheet. */
@Serializable
enum class GroupBy(val label: String) { Date("Date"), Repo("Repo"), Status("Status"), None("None") }

@Serializable
enum class SortOrder(val label: String) { Updated("Last updated"), Created("Created"), Name("Name") }

@Serializable
enum class StatusFilter(val label: String) { Read("Read"), Unread("Unread"), Running("Running"), Error("Error"), Archived("Archived") }

@Serializable
enum class GitFilter(val label: String) { Branch("Branch"), PullRequest("Pull request"), NoChanges("No changes") }

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
