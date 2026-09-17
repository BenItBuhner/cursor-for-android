package com.cursorforandroid.ui.media

import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Artifact
import com.cursorforandroid.domain.ArtifactPaths
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.UserMessage
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The media the viewer pages through: everything the transcript shows, once each, in the order it is shown. */
class ConversationMediaTest {

    private fun call(id: String, kind: ToolKind, payload: ToolPayload) = ToolCall(id, kind.name.lowercase(), kind, ToolCall.STATUS_COMPLETED, "", payload = payload)

    @Test
    fun `media is listed in transcript order, from every kind of row`() {
        val items = listOf(
            UserMessage(
                "u1", "Here is what it looks like",
                attachments = listOf(
                    MessageAttachment("/data/attachments/a/shot.png", 1080, 2400),
                    MessageAttachment.file("/data/attachments/a/clip.mp4", "clip.mp4", "video/mp4", 10_000),
                    MessageAttachment.file("/data/attachments/a/notes.pdf", "notes.pdf", "application/pdf", 2_000),
                ),
            ),
            ActivityGroup(
                "g1",
                listOf(
                    call("i1", ToolKind.Image, ToolPayload.GeneratedImage(path = "/workspace/docs/mock.png", description = "The mock", src = "file:///data/generated/mock.png")),
                    call("r1", ToolKind.Other, ToolPayload.Recording(path = ArtifactPaths.VM_ROOT + "walk.mp4", durationMs = 12_000)),
                ),
            ),
            AssistantMessage("a1", "Done.\n\n![Before](${ArtifactPaths.VM_ROOT}before.png)\n\n<video src=\"${ArtifactPaths.VM_ROOT}demo.mp4\"></video>\n\n- a list with ![Inside a list](https://example.com/list.png)"),
            AssistantMessage("a2", "A reply with no figure at all."),
        )
        val entries = ConversationMedia.of(items)
        assertThat(entries.map { it.src }).containsExactly(
            "file:///data/attachments/a/shot.png",
            "file:///data/attachments/a/clip.mp4",
            "file:///data/generated/mock.png",
            ArtifactPaths.VM_ROOT + "walk.mp4",
            ArtifactPaths.VM_ROOT + "before.png",
            ArtifactPaths.VM_ROOT + "demo.mp4",
            "https://example.com/list.png",
        ).inOrder()
        assertThat(entries.map { it.kind }).containsExactly(
            MediaEntry.Kind.Image, MediaEntry.Kind.Video, MediaEntry.Kind.Image, MediaEntry.Kind.Video, MediaEntry.Kind.Image, MediaEntry.Kind.Video, MediaEntry.Kind.Image,
        ).inOrder()
        // The generated image is titled by its description and named by its path; the recording knows its length.
        assertThat(entries[2].caption).isEqualTo("The mock")
        assertThat(entries[2].fileName).isEqualTo("mock.png")
        assertThat(entries[3].durationMs).isEqualTo(12_000)
        assertThat(entries[4].title).isEqualTo("Before")
        assertThat(entries[5].title).isEqualTo("demo.mp4")
        // The attached recording keeps the name and type the prompt carried.
        assertThat(entries[1].fileName).isEqualTo("clip.mp4")
        assertThat(entries[1].mimeType).isEqualTo("video/mp4")
    }

    @Test
    fun `a source shown twice is one page, where it first appears`() {
        val src = ArtifactPaths.VM_ROOT + "shot.png"
        val items = listOf(
            AssistantMessage("a1", "![First](${src})"),
            AssistantMessage("a2", "![Again](${src})"),
        )
        val artifacts = listOf(Artifact("artifacts/shot.png", 1_000, null), Artifact("artifacts/extra.png", 1_000, null), Artifact("artifacts/notes.md", 500, null))
        val entries = ConversationMedia.of(items, artifacts)
        assertThat(entries.map { it.src }).containsExactly(src, ArtifactPaths.VM_ROOT + "extra.png").inOrder()
        assertThat(entries[0].caption).isEqualTo("First")
        // A published picture no row shows is a page after the transcript's; a note is not.
        assertThat(entries[1].fileName).isEqualTo("extra.png")
    }

    @Test
    fun `a coordinator's message to the user carries its figures like a reply`() {
        val items = listOf(
            ActivityGroup("g1", listOf(call("m1", ToolKind.Coordinator, ToolPayload.CoordinatorMessage("Here it is:\n\n![Board](https://example.com/board.png)")))),
        )
        assertThat(ConversationMedia.of(items).map { it.src }).containsExactly("https://example.com/board.png")
    }

    @Test
    fun `nothing to page through gives nothing`() {
        assertThat(ConversationMedia.of(listOf(AssistantMessage("a1", "Plain words.")))).isEmpty()
        assertThat(ConversationMedia.of(emptyList())).isEmpty()
    }
}
