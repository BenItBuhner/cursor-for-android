package com.cursorforandroid.data.repo

import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.ToolPayloadLimits
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test
import java.util.Base64

/**
 * What a tool call produced, read off the shapes the SDK-shape update and the simplified event each use: the SDK
 * wraps a success in `{status, value}`, the public stream in `{success}`, and the typed fields sit inside either.
 */
class ToolPayloadsTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun payload(name: String, args: String? = null, result: String? = null, images: GeneratedImageSink? = null): ToolPayload? =
        ToolPayloads.from(name, args?.let(::json), result?.let(::json), images, callId = "call-1")

    @Test
    fun `an edit's diff comes with its path and line counts, from either wrapper`() {
        val sdk = payload("edit", """{"path":"app/Main.kt"}""", """{"status":"success","value":{"linesAdded":2,"linesRemoved":1,"diffString":"@@ -1 +1,2 @@\n-a\n+b\n+c"}}""") as ToolPayload.FileDiff
        assertThat(sdk.path).isEqualTo("app/Main.kt")
        assertThat(sdk.linesAdded).isEqualTo(2)
        assertThat(sdk.linesRemoved).isEqualTo(1)
        assertThat(sdk.lines).containsExactly("@@ -1 +1,2 @@", "-a", "+b", "+c").inOrder()
        assertThat(sdk.truncated).isFalse()

        val public = payload("edit_file", """{"target_file":"README.md"}""", """{"success":{"diffString":"+hello","linesAdded":1,"linesRemoved":0}}""") as ToolPayload.FileDiff
        assertThat(public.path).isEqualTo("README.md")
        assertThat(public.diff).isEqualTo("+hello")

        // Counts alone are the row's stats, not a diff to open.
        assertThat(payload("edit", """{"path":"x"}""", """{"status":"success","value":{"linesAdded":2,"linesRemoved":1}}""")).isNull()
        // A failed edit produced nothing.
        assertThat(payload("edit", """{"path":"x"}""", """{"status":"error","error":"no such file"}""")).isNull()
    }

    @Test
    fun `a write keeps the file as it ended up, or as it was asked for`() {
        val after = payload("write", """{"path":"notes.md","fileText":"# Draft"}""", """{"status":"success","value":{"path":"notes.md","linesCreated":2,"fileSize":15,"fileContentAfterWrite":"# Notes\nfinal"}}""") as ToolPayload.FileContent
        assertThat(after.kind).isEqualTo(ToolPayload.FileContent.Kind.Written)
        assertThat(after.content).isEqualTo("# Notes\nfinal")
        assertThat(after.totalLines).isEqualTo(2)
        assertThat(after.fileSize).isEqualTo(15L)

        // Before the result (the call is running), what was asked to be written is the file's text.
        val asked = payload("write", """{"path":"notes.md","fileText":"# Draft"}""") as ToolPayload.FileContent
        assertThat(asked.content).isEqualTo("# Draft")
        assertThat(asked.lineCount).isEqualTo(1)
    }

    @Test
    fun `a read keeps what the agent saw`() {
        val read = payload("read", """{"path":"README.md"}""", """{"status":"success","value":{"content":"# Project\nline 2","totalLines":2,"fileSize":16}}""") as ToolPayload.FileContent
        assertThat(read.kind).isEqualTo(ToolPayload.FileContent.Kind.Read)
        assertThat(read.content).isEqualTo("# Project\nline 2")
        assertThat(read.totalLines).isEqualTo(2)
        // The OpenAPI example for the simplified event.
        val public = payload("read_file", """{"path":"README.md"}""", """{"success":{"content":"# Project","totalLines":1,"fileSize":9,"path":"README.md"}}""") as ToolPayload.FileContent
        assertThat(public.content).isEqualTo("# Project")
        // A read whose result carries no text (truncated, or a shape this build does not know) yields nothing.
        assertThat(payload("read_file", """{"path":"README.md"}""")).isNull()
    }

    @Test
    fun `long text is clipped and says so`() {
        val long = "x".repeat(ToolPayloadLimits.MAX_TEXT_CHARS + 100)
        val read = payload("read", """{"path":"big.txt"}""", """{"status":"success","value":{"content":"$long","totalLines":1}}""") as ToolPayload.FileContent
        assertThat(read.content).hasLength(ToolPayloadLimits.MAX_TEXT_CHARS)
        assertThat(read.truncated).isTrue()
    }

    @Test
    fun `a generated image is handed to the store, and its file stands in for the pixels`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 1, 2, 3)
        val base64 = Base64.getEncoder().encodeToString(png)
        var saved: Triple<String, ByteArray, String?>? = null
        val sink = GeneratedImageSink { callId, bytes, mimeType -> saved = Triple(callId, bytes, mimeType); "file:///data/generated/bc-1/$callId.png" }

        val image = payload("generateImage", """{"description":"a cat","filePath":"/workspace/cat.png"}""", """{"status":"success","value":{"filePath":"/workspace/cat.png","imageData":"$base64"}}""", sink) as ToolPayload.GeneratedImage
        assertThat(image.src).isEqualTo("file:///data/generated/bc-1/call-1.png")
        assertThat(image.path).isEqualTo("/workspace/cat.png")
        assertThat(image.description).isEqualTo("a cat")
        assertThat(saved!!.first).isEqualTo("call-1")
        assertThat(saved!!.second).isEqualTo(png)
        assertThat(saved!!.third).isEqualTo("image/png")
    }

    @Test
    fun `without a store a small image stays inline and a large one is noted but not kept`() {
        val small = payload("generate_image", """{"prompt":"a dog"}""", """{"success":{"filePath":"dog.png","imageData":"data:image/png;base64,AAAA"}}""") as ToolPayload.GeneratedImage
        assertThat(small.src).isEqualTo("data:image/png;base64,AAAA")
        assertThat(small.description).isEqualTo("a dog")

        val bare = payload("generateImage", "{}", """{"status":"success","value":{"filePath":"x.png","imageData":"QUJD"}}""") as ToolPayload.GeneratedImage
        assertThat(bare.src).isEqualTo("data:image/png;base64,QUJD")

        val huge = "A".repeat(ToolPayloadLimits.MAX_INLINE_IMAGE_CHARS + 4)
        val large = payload("generateImage", "{}", """{"status":"success","value":{"filePath":"big.png","imageData":"$huge"}}""") as ToolPayload.GeneratedImage
        assertThat(large.src).isNull()
        assertThat(large.path).isEqualTo("big.png")

        // While the call runs there is no image yet, only what was asked for.
        val running = payload("generateImage", """{"description":"a dog","filePath":"dog.png"}""") as ToolPayload.GeneratedImage
        assertThat(running.src).isNull()
        assertThat(running.path).isEqualTo("dog.png")
    }

    @Test
    fun `a saved recording names its artifact path and length`() {
        val recording = payload("recordScreen", """{"mode":"SAVE_RECORDING"}""", """{"status":"success","value":{"path":"/opt/cursor/artifacts/demo.mp4","recordingDurationMs":12500}}""") as ToolPayload.Recording
        assertThat(recording.path).isEqualTo("/opt/cursor/artifacts/demo.mp4")
        assertThat(recording.durationMs).isEqualTo(12_500L)
        // Starting a recording saves nothing yet.
        assertThat(payload("record_screen", """{"mode":"START_RECORDING"}""", """{"success":{}}""")).isNull()
    }

    @Test
    fun `a subagent keeps its transcript and its cloud id`() {
        val task = payload(
            "task",
            """{"description":"Audit the tests","prompt":"Go through…","subagentType":{"kind":"builtin","name":"explore"}}""",
            """{"status":"success","value":{"agentId":"bc-sub-1","transcriptPath":"/home/u/.cursor/projects/w/agent-transcripts/x.json","durationMs":61000,"isBackground":false}}""",
        ) as ToolPayload.Subagent
        assertThat(task.description).isEqualTo("Audit the tests")
        assertThat(task.agentId).isEqualTo("bc-sub-1")
        assertThat(task.isCloudAgent).isTrue()
        assertThat(task.transcriptPath).endsWith("x.json")
        assertThat(task.durationMs).isEqualTo(61_000L)
        assertThat(task.subagentType).isEqualTo("explore")
        assertThat(task.path).isEqualTo(task.transcriptPath)
    }

    @Test
    fun `a question keeps its prompts and options, then its answers`() {
        val args = """{"title":"Before I start","questions":[
            {"id":"q1","prompt":"Which theme?","options":[{"id":"a","label":"Dark"},{"id":"b","label":"Light"}],"allowMultiple":false},
            {"id":"q2","prompt":"Anything else?"}
        ]}"""
        val pending = payload("ask_question", args) as ToolPayload.Question
        assertThat(pending.title).isEqualTo("Before I start")
        assertThat(pending.questions.map { it.prompt }).containsExactly("Which theme?", "Anything else?").inOrder()
        assertThat(pending.questions[0].options.map { it.label }).containsExactly("Dark", "Light").inOrder()
        assertThat(pending.isAnswered).isFalse()
        assertThat(pending.answerFor(pending.questions[0])).isNull()

        val answered = payload("ask_question", args, """{"success":{"answers":[{"questionId":"q1","selectedOptionIds":["b"]},{"questionId":"q2","freeformText":"No"}]}}""") as ToolPayload.Question
        assertThat(answered.isAnswered).isTrue()
        assertThat(answered.answerFor(answered.questions[0])).isEqualTo("Light")
        assertThat(answered.answerFor(answered.questions[1])).isEqualTo("No")

        // Other clients' spellings: bare strings for options, `question` for the prompt.
        val loose = payload("ask_question", """{"questions":[{"question":"Continue?","options":["Yes","No"]}]}""") as ToolPayload.Question
        assertThat(loose.questions.single().options.map { it.label }).containsExactly("Yes", "No").inOrder()
        assertThat(loose.questions.single().id).isEqualTo("0")
    }

    @Test
    fun `tools without a payload yield nothing`() {
        assertThat(payload("shell", """{"command":"ls"}""", """{"status":"success","value":{"stdout":"a\nb","exitCode":0}}""")).isNull()
        assertThat(payload("grep", """{"pattern":"x"}""", """{"success":{"count":3}}""")).isNull()
        assertThat(payload("delete", """{"path":"old.txt"}""", """{"status":"success","value":{"fileSize":3}}""")).isNull()
    }
}
