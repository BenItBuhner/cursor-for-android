package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.DiffDetailsApi
import com.cursorforandroid.data.api.WorkspaceFilesApi
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.domain.AgentDiff
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.domain.WorkspaceTree
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException

/** The gate in front of the agent's VM reads: no call without the capability, the demo's own word, and the named failures. */
class WorkspaceRepositoryTest {

    private var calls = 0
    private var failure: Throwable? = null
    private var now = 1_000_000L

    private val files = object : WorkspaceFilesApi {
        override suspend fun listFiles(agentId: String): WorkspaceTree {
            calls++
            failure?.let { throw it }
            return WorkspaceTree(listOf("a.kt", "dir/b.kt"))
        }

        override suspend fun readFile(agentId: String, path: String): ByteArray {
            calls++
            failure?.let { throw it }
            return "hello $path".toByteArray()
        }
    }
    private val diffs = DiffDetailsApi { agentId ->
        calls++
        failure?.let { throw it }
        AgentDiff("b", "main", emptyList())
    }

    private fun repo(capabilities: Capabilities = Capabilities.EXTENDED, demo: Boolean = false) =
        WorkspaceRepository(files, diffs, capabilities = { capabilities }, isDemo = { demo }, now = { now })

    @Test
    fun `with the capabilities off nothing is called and every read names Extended mode`() = runBlocking<Unit> {
        val repo = repo(Capabilities.DOCUMENTED)

        assertThat(repo.tree("bc-1")).isEqualTo(VmRead.NotAvailable(WorkspaceRepository.NEEDS_EXTENDED_MODE))
        assertThat(repo.file("bc-1", "a.kt")).isEqualTo(VmRead.NotAvailable(WorkspaceRepository.NEEDS_EXTENDED_MODE))
        assertThat(repo.diff("bc-1")).isEqualTo(VmRead.NotAvailable(WorkspaceRepository.NEEDS_EXTENDED_MODE))
        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun `each flag gates its own read`() = runBlocking<Unit> {
        val filesOnly = repo(Capabilities.DOCUMENTED.copy(workspaceFiles = true))
        assertThat(filesOnly.tree("bc-1")).isInstanceOf(VmRead.Loaded::class.java)
        assertThat(filesOnly.diff("bc-1")).isInstanceOf(VmRead.NotAvailable::class.java)
        val diffOnly = repo(Capabilities.DOCUMENTED.copy(diffDetails = true))
        assertThat(diffOnly.diff("bc-1")).isInstanceOf(VmRead.Loaded::class.java)
        assertThat(diffOnly.tree("bc-1")).isInstanceOf(VmRead.NotAvailable::class.java)
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `the demo has no VM and says so without a call, even with the mode on`() = runBlocking<Unit> {
        val repo = repo(demo = true)

        assertThat(repo.tree("bc-1")).isEqualTo(VmRead.NotAvailable(WorkspaceRepository.NO_DEMO_WORKSPACE))
        assertThat(repo.diff("bc-1")).isEqualTo(VmRead.NotAvailable(WorkspaceRepository.NO_DEMO_DIFF))
        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun `listings, files and diffs are read once and kept until forced or stale`() = runBlocking<Unit> {
        val repo = repo()

        val tree = repo.tree("bc-1") as VmRead.Loaded
        assertThat(tree.value.paths).containsExactly("a.kt", "dir/b.kt")
        repo.tree("bc-1")
        assertThat(calls).isEqualTo(1)
        val file = repo.file("bc-1", "/dir/b.kt") as VmRead.Loaded<RepoFile>
        assertThat(file.value.path).isEqualTo("dir/b.kt")
        assertThat(file.value.text).isEqualTo("hello dir/b.kt")
        assertThat(file.value.sizeBytes).isEqualTo(14L)
        repo.file("bc-1", "dir/b.kt")
        assertThat(calls).isEqualTo(2)
        repo.diff("bc-1")
        repo.diff("bc-1")
        assertThat(calls).isEqualTo(3)

        repo.tree("bc-1", force = true)
        assertThat(calls).isEqualTo(4)
        now += WorkspaceRepository.TTL_MS + 1
        repo.tree("bc-1")
        repo.diff("bc-1")
        assertThat(calls).isEqualTo(6)
        // Files are kept longer than listings.
        repo.file("bc-1", "dir/b.kt")
        assertThat(calls).isEqualTo(6)
        repo.reset()
        repo.file("bc-1", "dir/b.kt")
        assertThat(calls).isEqualTo(7)
    }

    @Test
    fun `failures are named, a method Cursor no longer offers as an endpoint change, and none is remembered`() = runBlocking<Unit> {
        val repo = repo()

        failure = ConnectRpcException(404, "unimplemented", "Error")
        assertThat(repo.tree("bc-1")).isEqualTo(VmRead.Failed(WorkspaceRepository.ENDPOINT_CHANGED, endpointChanged = true))
        failure = ConnectRpcException(200, "not_found", "no such file")
        assertThat((repo.file("bc-1", "x") as VmRead.Failed).message).contains("no such file any more")
        failure = ConnectRpcException(200, "failed_precondition", "pod hibernated")
        assertThat((repo.diff("bc-1") as VmRead.Failed).message).contains("isn't running")
        failure = SessionUnavailableException("off", SessionUnavailableException.EXTENDED_MODE_OFF)
        assertThat(repo.tree("bc-1")).isEqualTo(VmRead.Failed(WorkspaceRepository.NEEDS_EXTENDED_MODE))
        failure = IOException("timeout")
        assertThat(repo.tree("bc-1")).isInstanceOf(VmRead.Failed::class.java)
        // Nothing failed was cached: the next read after the failure goes out again.
        failure = null
        assertThat(repo.tree("bc-1")).isInstanceOf(VmRead.Loaded::class.java)
        assertThat(calls).isEqualTo(6)
    }
}
