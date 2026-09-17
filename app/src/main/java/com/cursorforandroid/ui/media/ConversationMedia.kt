package com.cursorforandroid.ui.media

import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Artifact
import com.cursorforandroid.domain.ArtifactPaths
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.PromptFileKind
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.MarkdownParser
import com.cursorforandroid.ui.components.MdBlock

/**
 * One picture or recording of a conversation, as the viewer pages through them. [src] is the reference exactly as
 * it is written where the media appears (a `file://` copy, an artifact's VM path, a URL, a store path): it is what
 * the thumbnail was drawn from, what [com.cursorforandroid.domain.MediaRef.parse] takes, and the key that ties a
 * thumbnail to its page.
 */
data class MediaEntry(
    val src: String,
    val kind: Kind,
    /** The alt text or description the media was shown with, when it had one. */
    val caption: String? = null,
    /** The file's own name, shown with the caption and used for a saved copy. */
    val fileName: String = ArtifactPaths.fileName(src),
    val mimeType: String? = null,
    /** A recording's length, when the transcript already knows it; the player reports it otherwise. */
    val durationMs: Long? = null,
) {
    enum class Kind { Image, Video }

    val isVideo: Boolean get() = kind == Kind.Video

    /** What the viewer titles the page with: the caption if there is one, else the file's name. */
    val title: String get() = caption?.takeIf { it.isNotBlank() } ?: fileName
}

/**
 * Everything a conversation shows that the viewer can page through, in the order it appears in the transcript: the
 * images and videos attached to prompts, the figures in replies (and in a coordinator's messages to the user), the
 * images the agent generated and the recordings it made, then the pictures and recordings the agent published as
 * artifacts that no row shows. Each source appears once, at its first appearance.
 */
object ConversationMedia {

    fun of(items: List<TimelineItem>, artifacts: List<Artifact> = emptyList()): List<MediaEntry> {
        val collector = Collector()
        items.forEach { item ->
            when (item) {
                is UserMessage -> item.attachments.forEach(collector::attachment)
                is AssistantMessage -> collector.markdown(item.markdown)
                is ActivityGroup -> item.calls.forEach { call ->
                    when (val payload = call.payload) {
                        is ToolPayload.GeneratedImage -> payload.src?.let { src ->
                            collector.add(MediaEntry(src, MediaEntry.Kind.Image, caption = payload.description, fileName = payload.path?.let(ArtifactPaths::fileName) ?: ArtifactPaths.fileName(src)))
                        }
                        is ToolPayload.Recording -> collector.add(MediaEntry(payload.path, MediaEntry.Kind.Video, durationMs = payload.durationMs))
                        is ToolPayload.CoordinatorMessage -> collector.markdown(payload.message)
                        else -> Unit
                    }
                }
                else -> Unit
            }
        }
        artifacts.forEach { artifact ->
            when (artifact.kind) {
                Artifact.Kind.Image -> collector.add(MediaEntry(artifact.vmPath, MediaEntry.Kind.Image, fileName = artifact.name))
                Artifact.Kind.Video -> collector.add(MediaEntry(artifact.vmPath, MediaEntry.Kind.Video, fileName = artifact.name))
                else -> Unit
            }
        }
        return collector.entries
    }

    private class Collector {
        val entries = ArrayList<MediaEntry>()
        private val seen = HashSet<String>()

        fun add(entry: MediaEntry) {
            if (seen.add(entry.src)) entries += entry
        }

        /** The images and videos of a prompt: its pictures, and the files of either type attached in Extended mode. */
        fun attachment(attachment: MessageAttachment) {
            val src = "file://${attachment.path}"
            when {
                !attachment.isFile -> add(MediaEntry(src, MediaEntry.Kind.Image, mimeType = attachment.mimeType, fileName = ArtifactPaths.fileName(attachment.path)))
                attachment.kind == PromptFileKind.Image -> add(MediaEntry(src, MediaEntry.Kind.Image, fileName = attachment.name ?: ArtifactPaths.fileName(attachment.path), mimeType = attachment.mimeType))
                attachment.kind == PromptFileKind.Video -> add(MediaEntry(src, MediaEntry.Kind.Video, fileName = attachment.name ?: ArtifactPaths.fileName(attachment.path), mimeType = attachment.mimeType))
            }
        }

        /** The figures of a reply, found the way the renderer finds them, so every figure drawn is a page and nothing else is. */
        fun markdown(text: String) {
            if (text.isEmpty() || !MEDIA_HINT.containsMatchIn(text)) return
            blocks(MarkdownParser.parse(text))
        }

        private fun blocks(blocks: List<MdBlock>) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Image -> add(MediaEntry(block.src, MediaEntry.Kind.Image, caption = block.alt))
                    is MdBlock.Video -> add(MediaEntry(block.src, MediaEntry.Kind.Video))
                    is MdBlock.Quote -> blocks(block.blocks)
                    is MdBlock.Bullets -> block.items.forEach { blocks(it.blocks) }
                    else -> Unit
                }
            }
        }
    }

    /** A reply with none of these has no figure to find; the parse is skipped for it. */
    private val MEDIA_HINT = Regex("""<img\b|<video\b|!\[""", RegexOption.IGNORE_CASE)
}
