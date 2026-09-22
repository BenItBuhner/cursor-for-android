package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.DiffDetailsApi
import com.cursorforandroid.data.api.WorkspaceFilesApi
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentDiff
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.GitBranch
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.WorkspaceTree

/** An agent's VM for the file reads' tests: the files it holds by relative path, and every read asked of it. */
internal class FakeVm(private val files: Map<String, ByteArray>) : WorkspaceFilesApi, DiffDetailsApi {
    val reads = mutableListOf<String>()
    var lists = 0
    override suspend fun listFiles(agentId: String): WorkspaceTree {
        lists++
        return WorkspaceTree(files.keys.toList())
    }
    override suspend fun readFile(agentId: String, path: String): ByteArray {
        reads += path
        return files[path] ?: throw ConnectRpcException(404, "not_found", "no such file")
    }
    override suspend fun diffDetails(agentId: String) = AgentDiff(null, null, emptyList())
}

/** A cloud chat on [repoUrl] at [branch], as the list row the file reads look the repository up on. */
internal fun agentOn(id: String, repoUrl: String? = "https://github.com/acme/app", branch: String? = "cursor/shots") = Agent(
    id = id,
    name = "Agent",
    lifecycle = AgentLifecycle.IDLE,
    runStatus = RunStatus.FINISHED,
    envType = EnvType.CLOUD,
    envName = null,
    url = "https://cursor.com/agents/$id",
    createdAtMillis = 1_000L,
    updatedAtMillis = 2_000L,
    latestRunId = "run-$id",
    repoUrl = repoUrl,
    startingRef = "main",
    branches = if (branch != null && repoUrl != null) listOf(GitBranch(repoUrl, branch, null)) else emptyList(),
)
