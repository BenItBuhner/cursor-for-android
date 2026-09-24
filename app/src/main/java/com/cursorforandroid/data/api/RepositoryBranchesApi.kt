package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import kotlinx.serialization.Serializable

/** One of a repository's branches as the account lists it (`GetRepositoryBranchesResponse.Branch {name, is_default}`). */
data class AccountBranch(val name: String, val isDefault: Boolean)

/** A repository's branches as the account lists them for the desktop's branch picker (Extended mode). */
fun interface RepositoryBranchesApi {
    suspend fun branches(repoUrl: String): List<AccountBranch>
}

/**
 * `BackgroundComposerService/GetRepositoryBranches {repo_url, page}` as the desktop's branch query asks for its first
 * page (`use-repository-branches-query`: `initialPageParam: 1`, no search): `{branches[{name, is_default}], has_more,
 * page, is_empty_repo, bootstrap_branch}`. The account lists what it can reach of the repository; one it cannot is its
 * refusal, which the caller reads as no branches.
 */
class ConnectRepositoryBranchesApi(private val rpc: ConnectJsonClient, private val tokens: SessionTokenProvider) : RepositoryBranchesApi {

    override suspend fun branches(repoUrl: String): List<AccountBranch> {
        val response = rpc.unaryWithSession(BackgroundComposerApi.SERVICE, "GetRepositoryBranches", tokens, RequestDto(repoUrl, page = 1), RequestDto.serializer(), ResponseDto.serializer())
        return response.branches.mapNotNull { branch -> branch.name.trim().takeIf { it.isNotEmpty() }?.let { AccountBranch(it, branch.isDefault) } }.distinctBy { it.name }
    }

    @Serializable
    private data class RequestDto(val repoUrl: String, val page: Int)

    @Serializable
    private data class ResponseDto(val branches: List<BranchDto> = emptyList(), val hasMore: Boolean = false, val page: Int = 0, val isEmptyRepo: Boolean = false, val bootstrapBranch: String = "")

    @Serializable
    private data class BranchDto(val name: String = "", val isDefault: Boolean = false)
}
