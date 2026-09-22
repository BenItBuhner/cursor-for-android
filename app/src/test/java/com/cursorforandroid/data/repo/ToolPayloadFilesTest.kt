package com.cursorforandroid.data.repo

import com.cursorforandroid.domain.ToolPayload
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import java.util.Base64

/**
 * What a read, a search or a listing carries of the files it touched, read off the documented stream's payloads
 * (the SDK's proto3 JSON of `ReadToolSuccess`, `GrepSuccess`, `GlobToolSuccess`, `LsSuccess`) so a tap on a file in
 * the row can open it without another request.
 */
class ToolPayloadFilesTest {

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `a read of a range names its first line, and is not the whole file`() {
        val payload = ToolPayloads.from(
            "read",
            json("""{"path":"/workspace/app/Main.kt","offset":12,"limit":3}"""),
            json("""{"success":{"content":"val a = 1\nval b = 2\nval c = 3\n","totalLines":40}}"""),
        ) as ToolPayload.FileContent
        assertThat(payload.startLine).isEqualTo(12)
        assertThat(payload.isWhole).isFalse()
    }

    @Test
    fun `a read from the top to the end is the whole file, and a cut one is not`() {
        val whole = ToolPayloads.from("read_file", json("""{"target_file":"a.kt"}"""), json("""{"success":{"content":"one\ntwo\n","totalLines":2}}""")) as ToolPayload.FileContent
        assertThat(whole.isWhole).isTrue()
        val exceeded = ToolPayloads.from("read", json("""{"path":"a.kt"}"""), json("""{"success":{"content":"one\n","totalLines":900,"exceededLimit":true}}""")) as ToolPayload.FileContent
        assertThat(exceeded.isWhole).isFalse()
        assertThat(exceeded.truncated).isTrue()
    }

    @Test
    fun `a read of a picture keeps the bytes the read tool handed the model`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(40)
        val kept = mutableListOf<Pair<String, String?>>()
        val sink = GeneratedImageSink { callId, bytes, mime -> kept += callId to mime; "file:///kept/$callId.png".also { check(bytes.contentEquals(png)) } }
        val payload = ToolPayloads.from(
            "read",
            json("""{"path":"/workspace/screenshots/45.png"}"""),
            json("""{"success":{"data":"${Base64.getEncoder().encodeToString(png)}","totalLines":0}}"""),
            images = sink,
            callId = "c-read",
        )
        assertThat(payload).isEqualTo(ToolPayload.ReadMedia("/workspace/screenshots/45.png", "file:///kept/c-read.png", "image/png"))
        assertThat(kept).containsExactly("c-read" to "image/png")
    }

    @Test
    fun `a content search names each file with its first matching line, joined to the root it searched`() {
        val payload = ToolPayloads.from(
            "grep",
            json("""{"pattern":"decodeBytes","path":"/workspace"}"""),
            json(
                """{"success":{"pattern":"decodeBytes","path":"/workspace","outputMode":"content","workspaceResults":{"/workspace":{"content":{"matches":[
                  {"file":"app/src/AgentFilesApi.kt","matches":[{"lineNumber":124,"content":"fun decodeBytes(encoded: String?)","isContextLine":false}]},
                  {"file":"/workspace/app/src/Test.kt","matches":[{"lineNumber":3,"content":"ctx","isContextLine":true},{"lineNumber":4,"content":"decodeBytes(x)"}]}
                ],"totalLines":2}}}}}""",
            ),
        ) as ToolPayload.FileHits
        assertThat(payload.hits).containsExactly(
            ToolPayload.FileHits.Hit("/workspace/app/src/AgentFilesApi.kt", 124, "fun decodeBytes(encoded: String?)"),
            ToolPayload.FileHits.Hit("/workspace/app/src/Test.kt", 4, "decodeBytes(x)"),
        ).inOrder()
    }

    @Test
    fun `a files-with-matches search, a count, a glob and a listing name their files`() {
        val files = ToolPayloads.from("grep", json("""{"pattern":"x"}"""), json("""{"success":{"workspaceResults":{"/workspace":{"files":{"files":["a.kt","b/c.kt"],"ripgrepTruncated":true}}}}}""")) as ToolPayload.FileHits
        assertThat(files.hits.map { it.path }).containsExactly("/workspace/a.kt", "/workspace/b/c.kt").inOrder()
        assertThat(files.truncated).isTrue()

        val counts = ToolPayloads.from("grep", json("""{"pattern":"x"}"""), json("""{"success":{"workspaceResults":{"/workspace":{"count":{"counts":[{"file":"a.kt","count":3}]}}}}}""")) as ToolPayload.FileHits
        assertThat(counts.hits.single().path).isEqualTo("/workspace/a.kt")

        val glob = ToolPayloads.from("glob_file_search", json("""{"glob_pattern":"*.png","target_directory":"/workspace/screenshots"}"""), json("""{"success":{"pattern":"*.png","path":"/workspace/screenshots","files":["45.png","/workspace/screenshots/46.png"]}}""")) as ToolPayload.FileHits
        assertThat(glob.hits.map { it.path }).containsExactly("/workspace/screenshots/45.png", "/workspace/screenshots/46.png").inOrder()

        val ls = ToolPayloads.from(
            "ls",
            json("""{"path":"/workspace/app"}"""),
            json("""{"success":{"directoryTreeRoot":{"absPath":"/workspace/app","childrenFiles":[{"name":"build.gradle.kts"}],"childrenDirs":[{"absPath":"/workspace/app/src","childrenFiles":[{"name":"Main.kt"}]}]}}}"""),
        ) as ToolPayload.FileHits
        assertThat(ls.hits.map { it.path }).containsExactly("/workspace/app/build.gradle.kts", "/workspace/app/src/Main.kt").inOrder()
    }
}
