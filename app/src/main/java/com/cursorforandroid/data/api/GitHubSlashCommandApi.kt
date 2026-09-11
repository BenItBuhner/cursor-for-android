package com.cursorforandroid.data.api

import com.cursorforandroid.domain.RepositorySlashCommands
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SlashCommand

/**
 * [SlashCommandApi] over what a GitHub-hosted repository itself contains: the `.cursor/skills` and `.cursor/commands`
 * in its tree (see [RepositorySlashCommands]), read anonymously from GitHub's REST API. This is what a cloud agent
 * loads from the repository too, so the popover offers the same project skills the agent will find — by name only,
 * since reading each `SKILL.md` for its description would cost a request apiece. Repositories on other hosts, and
 * private ones (which an anonymous reader is told do not exist), add nothing, so the composer keeps the built-ins.
 * There is no account to ask for global commands or for what an agent's machine has reported.
 */
class GitHubSlashCommandApi(private val gitHub: GitHubApi) : SlashCommandApi {

    override suspend fun forRepository(repoUrl: String, ref: String?): SlashCatalog = catalogOf(repoUrl, ref)

    override suspend fun forAgent(agentId: String, repoUrl: String?, ref: String?): SlashCatalog =
        if (repoUrl.isNullOrBlank()) SlashCatalog() else catalogOf(repoUrl, ref)

    override suspend fun global(): List<SlashCommand> = emptyList()

    private suspend fun catalogOf(repoUrl: String, ref: String?): SlashCatalog {
        val repo = GitHubRepo.parse(repoUrl) ?: return SlashCatalog()
        val tree = try {
            gitHub.tree(repo, ref?.trim().orEmpty().ifEmpty { "HEAD" })
        } catch (e: GitHubApiException) {
            // Not there for an anonymous reader: a complete answer with nothing in it, not a failure to retry.
            if (e.isNotFound) return SlashCatalog() else throw e
        }
        return RepositorySlashCommands.fromPaths(tree.paths)
    }
}
