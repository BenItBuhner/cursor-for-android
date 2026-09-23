package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.CursorServer
import com.cursorforandroid.data.api.CursorServerFiles
import com.cursorforandroid.data.api.CursorServerReadException
import com.cursorforandroid.data.api.DiffDetailsApi
import com.cursorforandroid.data.api.WorkspaceFilesApi
import com.cursorforandroid.domain.AgentDiff
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.RepoContents
import com.cursorforandroid.domain.WorkspaceTree
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException

/**
 * A picture a tool call read outside the workspace (`/tmp/…`, Bennett's frame:
 * `ReadBinaryFile → HTTP 400 invalid_argument "File path must stay within the workspace."`) is read the way Cursor's
 * own app reads it — off the machine's cursor-server, `GetCursorServerUrl` then its remote-resource route — not
 * through `ReadBinaryFile`, which is workspace-only. When the machine has no such server (asleep, gone, default
 * mode, or nothing published), the file read says why, in the words a notice offers a way on from.
 */
class CursorServerReadTest {

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(32)
    private val readPath = "/aiserver.v1.BackgroundComposerService/ReadBinaryFile"

    /** A VM that lists a workspace and answers `ReadBinaryFile`; every read kept. */
    private class Vm(private val listing: () -> WorkspaceTree = { WorkspaceTree(listOf("app/src/Main.kt")) }, private val read: (String) -> ByteArray) : WorkspaceFilesApi, DiffDetailsApi {
        val reads = mutableListOf<String>()
        override suspend fun listFiles(agentId: String) = listing()
        override suspend fun readFile(agentId: String, path: String): ByteArray {
            reads += path
            return read(path)
        }
        override suspend fun diffDetails(agentId: String) = AgentDiff(null, null, emptyList())
    }

    /** A cursor-server that serves [files] by absolute path; its calls kept. */
    private class Server(private val files: Map<String, ByteArray>, private val fail: (() -> Throwable)? = null) : CursorServerFiles {
        val servers = mutableListOf<Pair<String, String>>()
        val reads = mutableListOf<String>()
        override suspend fun server(agentId: String, commit: String, connectionToken: String): CursorServer {
            servers += agentId to commit
            fail?.let { throw it() }
            return CursorServer("pod", 443, connectionToken, emptyList())
        }
        override suspend fun read(server: CursorServer, path: String): ByteArray {
            reads += path
            return files[path] ?: throw CursorServerReadException(404, "GET …/vscode-remote-resource?path=$path → HTTP 404", "no such file")
        }
    }

    private fun files(vm: Vm, server: CursorServerFiles?, capabilities: Capabilities = Capabilities.EXTENDED, agent: com.cursorforandroid.domain.Agent = agentOn("bc-1")) =
        AgentFileRepository(
            WorkspaceRepository(vm, vm, capabilities = { capabilities }),
            repository = { _, _, _ -> Result.failure(IOException("not asked")) },
            agent = { agent },
            cursorServer = server,
            mintToken = { "minted" },
        )

    @Test
    fun `a tmp picture is read off the cursor-server, by its absolute path, and ReadBinaryFile is never asked for it`() = runBlocking<Unit> {
        val vm = Vm { throw ConnectRpcException(400, "invalid_argument", "File path must stay within the workspace.", path = readPath) }
        val server = Server(mapOf("/tmp/v02_mid.png" to png))
        val read = files(vm, server).read("bc-1", "/tmp/v02_mid.png")
        assertThat(read).isInstanceOf(FileRead.Loaded::class.java)
        assertThat((read as FileRead.Loaded).source).isEqualTo(FileRead.Source.Machine)
        assertThat(read.file.bytes).isEqualTo(png)
        // ReadBinaryFile is workspace-only; the /tmp path never reaches it.
        assertThat(vm.reads).isEmpty()
        assertThat(server.servers).containsExactly("bc-1" to CursorServerApiCommit)
        assertThat(server.reads).containsExactly("/tmp/v02_mid.png")
    }

    @Test
    fun `a workspace file is still read through ReadBinaryFile, not the cursor-server`() = runBlocking<Unit> {
        val vm = Vm { if (it == "app/src/Main.kt") png else throw ConnectRpcException(404, "not_found", "x", path = readPath) }
        val server = Server(emptyMap())
        val read = files(vm, server).read("bc-1", "/workspace/app/src/Main.kt") as FileRead.Loaded
        assertThat(read.source).isEqualTo(FileRead.Source.Workspace)
        assertThat(vm.reads).containsExactly("app/src/Main.kt")
        assertThat(server.reads).isEmpty()
    }

    @Test
    fun `the cursor-server not having the file is a missing file, with the request line`() = runBlocking<Unit> {
        val vm = Vm { throw ConnectRpcException(400, "invalid_argument", "File path must stay within the workspace.", path = readPath) }
        val read = files(vm, Server(emptyMap())).read("bc-1", "/tmp/gone.png") as FileRead.Failed
        assertThat(read.reason).isEqualTo(FileRead.Reason.NotFound)
        assertThat(read.asked).contains("vscode-remote-resource?path=/tmp/gone.png → HTTP 404")
    }

    @Test
    fun `no cursor-server wired is the outside-workspace notice, offering the copy ask`() = runBlocking<Unit> {
        val vm = Vm { throw ConnectRpcException(400, "invalid_argument", "File path must stay within the workspace.", path = readPath) }
        val read = files(vm, server = null).read("bc-1", "/tmp/v02_mid.png") as FileRead.Failed
        assertThat(read.reason).isEqualTo(FileRead.Reason.OutsideWorkspace)
        assertThat(read.message).isEqualTo(AgentFileRepository.OUTSIDE_WORKSPACE)
    }

    @Test
    fun `default mode names Extended mode for a tmp picture and never touches the cursor-server`() = runBlocking<Unit> {
        val vm = Vm { png }
        val server = Server(mapOf("/tmp/v02_mid.png" to png))
        val read = files(vm, server, capabilities = Capabilities.DOCUMENTED).read("bc-1", "/tmp/v02_mid.png")
        assertThat(read).isInstanceOf(FileRead.NotReadable::class.java)
        assertThat(server.servers).isEmpty()
        assertThat(server.reads).isEmpty()
    }

    @Test
    fun `a machine asleep is said so before the cursor-server is asked`() = runBlocking<Unit> {
        val vm = Vm(listing = { throw ConnectRpcException(400, "failed_precondition", "pod is not running", path = "/aiserver.v1.BackgroundComposerService/ListWorkspaceFiles") }) { throw ConnectRpcException(400, "failed_precondition", "pod is not running", path = readPath) }
        val server = Server(mapOf("/tmp/v02_mid.png" to png))
        val read = files(vm, server).read("bc-1", "/tmp/v02_mid.png") as FileRead.Failed
        assertThat(read.reason).isEqualTo(FileRead.Reason.MachineAsleep)
        // The listing already said asleep; the cursor-server is not asked.
        assertThat(server.servers).isEmpty()
    }

    private companion object {
        val CursorServerApiCommit = com.cursorforandroid.data.api.CursorServerApi.DESKTOP_COMMIT
    }
}
