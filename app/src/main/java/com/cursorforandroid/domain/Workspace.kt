package com.cursorforandroid.domain

/**
 * The files of the agent's live workspace, as `ListWorkspaceFiles` names them: every path relative to the workspace
 * root, flat. The panel walks it as a tree ([list]) without asking again per directory.
 */
data class WorkspaceTree(val paths: List<String>) {
    val isEmpty: Boolean get() = paths.isEmpty()

    /**
     * What sits directly under [directory] ("" for the root): its subdirectories first, then its files, each once,
     * both sorted by name. A path that does not start with the directory is not its business.
     */
    fun list(directory: String): List<RepoEntry> {
        val prefix = directory.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val directories = LinkedHashSet<String>()
        val files = ArrayList<RepoEntry>()
        for (path in paths) {
            val clean = path.trim('/')
            if (clean.isEmpty() || !clean.startsWith(prefix)) continue
            val rest = clean.substring(prefix.length)
            if (rest.isEmpty()) continue
            val slash = rest.indexOf('/')
            if (slash < 0) {
                files += RepoEntry(rest, clean, isDirectory = false)
            } else {
                directories += rest.substring(0, slash)
            }
        }
        return directories.sortedBy { it.lowercase() }.map { RepoEntry(it, prefix + it, isDirectory = true) } +
            files.distinctBy { it.path }.sortedBy { it.name.lowercase() }
    }

    /** Whether [path] names a file the workspace holds. */
    fun contains(path: String): Boolean = paths.any { it.trim('/') == path.trim('/') }
}

/** One file of a branch's diff against its base, as `GetBackgroundComposerDiffDetails` reports it. */
data class AgentDiffFile(
    val path: String,
    val status: ChangedFileStatus,
    val additions: Int,
    val deletions: Int,
    /** The unified diff, rebuilt from the hunks the account sent; null when it sent none (a binary, or a file it only carried whole). */
    val patch: String?,
    /** The file before and after, when the account carried them whole. */
    val originalContent: String? = null,
    val modifiedContent: String? = null,
    val previousPath: String? = null,
) {
    val name: String get() = ToolNames.basename(path)

    /** The same file as the pull request section draws it, so the two lists look alike. */
    fun asChangedFile(): ChangedFile = ChangedFile(path, status, additions, deletions, patch, previousPath)
}

/** The branch's changes against its base, before or beside a pull request. */
data class AgentDiff(
    val branchName: String?,
    val baseBranch: String?,
    val files: List<AgentDiffFile>,
) {
    val isEmpty: Boolean get() = files.isEmpty()
    val additions: Int get() = files.sumOf { it.additions }
    val deletions: Int get() = files.sumOf { it.deletions }
}
