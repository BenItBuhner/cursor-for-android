package com.cursorforandroid.ui.files

import com.cursorforandroid.data.repo.AgentFileRepository
import com.cursorforandroid.data.repo.FakeVm
import com.cursorforandroid.data.repo.WorkspaceRepository
import com.cursorforandroid.data.repo.agentOn
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.CarriedFile
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.DiffLines
import com.cursorforandroid.domain.FileFormat
import com.cursorforandroid.domain.FileOpenRequest
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException

/**
 * Where the full-file viewer's text comes from, in order: what the transcript carries when it is the whole file;
 * with the workspace readable (Extended mode), the file as it is now — the lines the tapped edit changed, the range
 * a read took or a search's hit marked; otherwise what the transcript carried, and a plain word that the whole file
 * needs Extended mode.
 */
class FullFileResolverTest {

    private val source = (1..30).joinToString("\n") { "line $it" } + "\n"
    private val path = "/workspace/app/src/Main.kt"
    private val edit = ToolPayload.FileDiff(path, "@@ -9,3 +9,4 @@\n line 9\n-old 10\n+line 10\n+line 11\n line 12\n", linesAdded = 2, linesRemoved = 1)

    private fun call(id: String, kind: ToolKind, payload: ToolPayload?) = ToolCall(id, kind.name.lowercase(), kind, ToolCall.STATUS_COMPLETED, "Main.kt", detail = path, payload = payload)
    private fun items(vararg calls: ToolCall) = listOf(ActivityGroup("g", calls.toList()))

    private fun resolver(extended: Boolean, vm: FakeVm = FakeVm(mapOf("app/src/Main.kt" to source.toByteArray()))): Pair<FullFileResolver, FakeVm> {
        val files = AgentFileRepository(WorkspaceRepository(vm, vm, capabilities = { if (extended) Capabilities.EXTENDED else Capabilities.DOCUMENTED }), { _, _, _ -> Result.failure(IOException("not on GitHub")) }, agent = { agentOn(it) })
        return FullFileResolver(files, { extended }) { bytes, name -> "file:///cache/$name".also { check(bytes.isNotEmpty()) } } to vm
    }

    @Test
    fun `an edit's changed lines are the new file's, counted from each hunk`() {
        assertThat(DiffLines.changedLines(edit.diff)).containsExactly(10, 11).inOrder()
        assertThat(DiffLines.changedLines("--- a/x\n+++ b/x\n@@ -1 +1,2 @@\n+first\n kept\n@@ -20,2 +21,2 @@\n-gone\n+here\n x\n")).containsExactly(1, 21).inOrder()
        assertThat(DiffLines.changedLines("+no header\n")).isEmpty()
    }

    @Test
    fun `a whole file the transcript carried is shown as it is, without a read`() = runBlocking<Unit> {
        val written = call("w", ToolKind.Create, ToolPayload.FileContent(path, source, ToolPayload.FileContent.Kind.Written))
        val (resolver, vm) = resolver(extended = true)
        val shown = resolver.resolve("bc-1", FileOpenRequest(path, "w"), CarriedFile.of(items(written), path, "w")) as FullFile.Text
        assertThat(shown.source).isEqualTo("As the agent wrote it")
        assertThat(shown.notice).isNull()
        assertThat(shown.lines).hasSize(30)
        assertThat(vm.reads).isEmpty()
    }

    @Test
    fun `in Extended mode an edit opens the file as it is now with the lines it changed marked`() = runBlocking<Unit> {
        val (resolver, vm) = resolver(extended = true)
        val shown = resolver.resolve("bc-1", FileOpenRequest(path, "e"), CarriedFile.of(items(call("e", ToolKind.Edit, edit)), path, "e")) as FullFile.Text
        assertThat(shown.source).isEqualTo("From the agent's workspace, as it is now")
        assertThat(shown.highlighted).containsExactly(10, 11)
        assertThat(shown.scrollTo).isEqualTo(10)
        assertThat(shown.lines).hasSize(30)
        assertThat(vm.reads).containsExactly("app/src/Main.kt")
    }

    @Test
    fun `in default mode an edit is its diff, and the viewer says the whole file needs Extended mode`() = runBlocking<Unit> {
        val (resolver, vm) = resolver(extended = false)
        val shown = resolver.resolve("bc-1", FileOpenRequest(path, "e"), CarriedFile.of(items(call("e", ToolKind.Edit, edit)), path, "e")) as FullFile.Diff
        assertThat(shown.diffs).containsExactly(edit)
        assertThat(shown.notice).isEqualTo(FullFileResolver.NEEDS_EXTENDED)
        assertThat(shown.webUrl).isEqualTo("https://github.com/acme/app/blob/cursor/shots/app/src/Main.kt")
        assertThat(vm.reads).isEmpty()
    }

    @Test
    fun `a read of a range is numbered as the file's in default mode, and marks its range once the whole file is read`() = runBlocking<Unit> {
        val ranged = call("r", ToolKind.Read, ToolPayload.FileContent(path, "line 12\nline 13\nline 14\n", ToolPayload.FileContent.Kind.Read, totalLines = 30, startLine = 12))
        val (plain, _) = resolver(extended = false)
        val partial = plain.resolve("bc-1", FileOpenRequest(path, "r"), CarriedFile.of(items(ranged), path, "r")) as FullFile.Text
        assertThat(partial.firstLine).isEqualTo(12)
        assertThat(partial.source).isEqualTo("The part the agent read")
        assertThat(partial.notice).isEqualTo(FullFileResolver.NEEDS_EXTENDED)

        val (extended, _) = resolver(extended = true)
        val whole = extended.resolve("bc-1", FileOpenRequest(path, "r"), CarriedFile.of(items(ranged), path, "r")) as FullFile.Text
        assertThat(whole.firstLine).isEqualTo(1)
        assertThat(whole.highlighted).containsExactly(12, 13, 14)
        assertThat(whole.scrollTo).isEqualTo(12)
    }

    @Test
    fun `a search hit opens at its line`() = runBlocking<Unit> {
        val (resolver, _) = resolver(extended = true)
        val shown = resolver.resolve("bc-1", FileOpenRequest(path, line = 22), CarriedFile.of(emptyList(), path, null)) as FullFile.Text
        assertThat(shown.highlighted).containsExactly(22)
        assertThat(shown.scrollTo).isEqualTo(22)
    }

    @Test
    fun `a file that is not text is kept and handed on`() = runBlocking<Unit> {
        val pdf = "%PDF-1.7\n1 0 obj\n".toByteArray()
        val (resolver, _) = resolver(extended = true, vm = FakeVm(mapOf("docs/spec.pdf" to pdf)))
        val shown = resolver.resolve("bc-1", FileOpenRequest("/workspace/docs/spec.pdf"), CarriedFile.of(emptyList(), "/workspace/docs/spec.pdf", null)) as FullFile.Binary
        assertThat(shown.format).isEqualTo(FileFormat.PDF)
        assertThat(shown.keptPath).isEqualTo("/cache/spec.pdf")
    }

    @Test
    fun `nothing carried and nothing readable says so plainly`() = runBlocking<Unit> {
        val (resolver, _) = resolver(extended = false)
        val shown = resolver.resolve("bc-1", FileOpenRequest(path), CarriedFile.of(emptyList(), path, null)) as FullFile.Failed
        assertThat(shown.message).contains("needs Extended mode")
        assertThat(shown.retryable).isFalse()
    }

    @Test
    fun `a file named absolutely and relatively is one file`() {
        assertThat(CarriedFile.samePath("/workspace/app/src/Main.kt", "app/src/Main.kt")).isTrue()
        assertThat(CarriedFile.samePath("./app/src/Main.kt", "app/src/Main.kt")).isTrue()
        assertThat(CarriedFile.samePath("/workspace/app/src/Main.kt", "src/Other.kt")).isFalse()
    }
}
