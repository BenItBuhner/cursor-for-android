package com.cursorforandroid.data.repo

import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.RepoContents
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.domain.WorkspaceTree
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException

/**
 * A file by the path the agent names it with: its workspace first (Extended mode, `ReadBinaryFile` with the path
 * relative to the workspace root, as the desktop's `cursor-worker://` reads take it), then the repository at the
 * agent's branch, and a named answer with the host's page when neither holds it.
 */
class AgentFileRepositoryTest {

    private val bytes = "fun main() {}\n".toByteArray()

    private fun repo(vm: FakeVm, capabilities: Capabilities, agent: com.cursorforandroid.domain.Agent? = agentOn("bc-1"), repository: suspend (String, String?, String) -> Result<RepoContents> = { _, _, _ -> Result.failure(IOException("GitHub would not show this anonymously.")) }) =
        AgentFileRepository(WorkspaceRepository(vm, vm, capabilities = { capabilities }), repository, agent = { agent })

    @Test
    fun `an absolute VM path is the longest listed path it ends with`() {
        val tree = WorkspaceTree(listOf("Main.kt", "app/src/Main.kt", "docs/a.md"))
        assertThat(AgentFileRepository.relativePath("/workspace/app/src/Main.kt", tree)).isEqualTo("app/src/Main.kt")
        assertThat(AgentFileRepository.relativePath("/home/ubuntu/repo/app/src/Main.kt", tree)).isEqualTo("app/src/Main.kt")
        assertThat(AgentFileRepository.relativePath("app/src/Main.kt", tree)).isEqualTo("app/src/Main.kt")
        assertThat(AgentFileRepository.relativePath("./docs/a.md", tree)).isEqualTo("docs/a.md")
        // Not listed (ignored build output, say): the usual root comes off.
        assertThat(AgentFileRepository.relativePath("/workspace/build/out.log", tree)).isEqualTo("build/out.log")
    }

    @Test
    fun `Extended mode reads the workspace and never asks the repository`() = runBlocking<Unit> {
        val vm = FakeVm(mapOf("app/src/Main.kt" to bytes))
        var askedRepository = false
        val read = repo(vm, Capabilities.EXTENDED, repository = { _, _, _ -> askedRepository = true; Result.failure(IOException()) }).read("bc-1", "/workspace/app/src/Main.kt")
        assertThat(read).isInstanceOf(FileRead.Loaded::class.java)
        assertThat((read as FileRead.Loaded).source).isEqualTo(FileRead.Source.Workspace)
        assertThat(read.file.bytes).isEqualTo(bytes)
        assertThat(vm.reads).containsExactly("app/src/Main.kt")
        assertThat(askedRepository).isFalse()
    }

    @Test
    fun `a file the workspace no longer has is read from the repository at the agent's branch`() = runBlocking<Unit> {
        val vm = FakeVm(mapOf("other.kt" to bytes))
        var asked: Triple<String, String?, String>? = null
        val read = repo(vm, Capabilities.EXTENDED, repository = { url, ref, path ->
            asked = Triple(url, ref, path)
            Result.success(RepoContents.File(RepoFile(path, bytes, bytes.size.toLong())))
        }).read("bc-1", "/workspace/app/src/Main.kt")
        assertThat((read as FileRead.Loaded).source).isEqualTo(FileRead.Source.Repository)
        assertThat(asked).isEqualTo(Triple("https://github.com/acme/app", "cursor/shots", "app/src/Main.kt"))
    }

    @Test
    fun `default mode reads the repository and makes no account call`() = runBlocking<Unit> {
        val vm = FakeVm(mapOf("app/src/Main.kt" to bytes))
        val read = repo(vm, Capabilities.DOCUMENTED, repository = { _, _, path -> Result.success(RepoContents.File(RepoFile(path, bytes, bytes.size.toLong()))) }).read("bc-1", "/workspace/app/src/Main.kt")
        assertThat((read as FileRead.Loaded).source).isEqualTo(FileRead.Source.Repository)
        assertThat(vm.reads).isEmpty()
        assertThat(vm.lists).isEqualTo(0)
    }

    @Test
    fun `with neither readable the answer names Extended mode and links the file on its host`() = runBlocking<Unit> {
        val read = repo(FakeVm(emptyMap()), Capabilities.DOCUMENTED, agent = agentOn("bc-1", repoUrl = "https://gitlab.com/acme/app")).read("bc-1", "/workspace/app/src/Main.kt")
        assertThat(read).isEqualTo(FileRead.NotReadable(WorkspaceRepository.NEEDS_EXTENDED_MODE, null))
        val onGitHub = repo(FakeVm(emptyMap()), Capabilities.DOCUMENTED).read("bc-1", "/workspace/app/src/Main.kt")
        assertThat(onGitHub).isInstanceOf(FileRead.Failed::class.java)
        assertThat((onGitHub as FileRead.Failed).webUrl).isEqualTo("https://github.com/acme/app/blob/cursor/shots/app/src/Main.kt")
    }
}
