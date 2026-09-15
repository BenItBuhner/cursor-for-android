package com.cursorforandroid.domain

import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Paths into an Agent Store as a coordinator writes them (see [CoordinatorFixtures], `store_paths_message.json`:
 * the coordinator's own link and code-span shapes, the image line Bennett quoted, a recording), read into the store
 * they name and the file in it, and resolved against the chat they are read in.
 */
class StorePathTest {

    private val fixture = CoordinatorFixtures.json("store_paths_message.json")
    private val store = fixture.getValue("storeId").jsonPrimitive.content
    private val markdown = fixture.getValue("markdown").jsonPrimitive.content
    private val imagePath = fixture.getValue("imagePath").jsonPrimitive.content

    @Test
    fun `a store path names its store and the file in it, under the spellings agents use`() {
        val path = StorePath.parse(imagePath)!!
        assertThat(path.mount).isEqualTo(store)
        assertThat(path.agentId).isEqualTo(store)
        assertThat(path.relativePath).isEqualTo("media/ui-parity/tab-landscape-icon-only-project.png")
        assertThat(path.fileName).isEqualTo("tab-landscape-icon-only-project.png")
        assertThat(path.isImage).isTrue()
        assertThat(path.isMarkdown).isFalse()
        assertThat(path.text).isEqualTo(imagePath)
        // Behind a file scheme, without the leading slash, percent-encoded, with a fragment: the same file.
        assertThat(StorePath.parse("file://$imagePath")).isEqualTo(path)
        assertThat(StorePath.parse(imagePath.removePrefix("/"))).isEqualTo(path)
        assertThat(StorePath.parse(imagePath.replace("ui-parity", "ui%2Dparity") + "#top")).isEqualTo(path)
        assertThat(StorePath.parse("/cursor/stores/$store/docs/my%20spec.md")!!.relativePath).isEqualTo("docs/my spec.md")
        val doc = StorePath.parse("/cursor/stores/$store/docs/project-ui-parity-spec.md")!!
        assertThat(doc.isMarkdown).isTrue()
        assertThat(doc.isImage).isFalse()
        assertThat(StorePath.parse("/cursor/stores/$store/internal/reference/keyboard-jank-recording.mp4")!!.isVideo).isTrue()
    }

    @Test
    fun `self is the chat's own store, and a mount naming no agent is nobody's`() {
        val self = StorePath.parse("/cursor/stores/self/media/board.png")!!
        assertThat(self.isSelf).isTrue()
        assertThat(self.agentId).isNull()
        assertThat(self.ownerId("bc-chat")).isEqualTo("bc-chat")
        assertThat(self.ownerId(null)).isNull()
        val user = StorePath.parse("/cursor/stores/user/notes.md")!!
        assertThat(user.ownerId("bc-chat")).isNull()
        assertThat(StorePath.parse("/cursor/stores/$store/docs/spec.md")!!.ownerId("bc-other")).isEqualTo(store)
    }

    @Test
    fun `what is not a store path is not one`() {
        assertThat(StorePath.parse("/opt/cursor/artifacts/demo.png")).isNull()
        assertThat(StorePath.parse("https://cursor.com/agents/bc-1")).isNull()
        assertThat(StorePath.parse("/cursor/stores/")).isNull()
        assertThat(StorePath.parse("/cursor/stores/$store")).isNull()
        assertThat(StorePath.parse("/cursor/stores/$store/../other/secret.md")).isNull()
        assertThat(StorePath.parse("")).isNull()
        assertThat(StorePath.parse("app/src/main/Markdown.kt")).isNull()
    }

    @Test
    fun `a bare path in prose ends at the sentence, not in it`() {
        val text = "The full path, for the record: /cursor/stores/$store/docs/project-ui-parity-spec.md. Then more."
        val at = text.indexOf("/cursor/stores/")
        assertThat(StorePath.findBare(text, at)).isEqualTo("/cursor/stores/$store/docs/project-ui-parity-spec.md")
        assertThat(StorePath.findBare("see (/cursor/stores/$store/docs/a.md) now", 5)).isEqualTo("/cursor/stores/$store/docs/a.md")
        assertThat(StorePath.findBare("not here", 0)).isNull()
        assertThat(StorePath.findBare("/cursor/stores/only-a-mount", 0)).isNull()
    }

    @Test
    fun `the coordinator's message resolves every store reference in it against the chat`() {
        val media = MediaMarkup.split(markdown)
        val images = media.filterIsInstance<MediaSegment.Image>()
        val videos = media.filterIsInstance<MediaSegment.Video>()
        assertThat(images.map { it.src }).containsExactly(imagePath, "/cursor/stores/self/media/board.png").inOrder()
        assertThat(videos.map { it.src }).containsExactly("/cursor/stores/$store/internal/reference/keyboard-jank-recording.mp4")

        // In the coordinator's own chat: the Project's store by id, and `self` is the coordinator too.
        val inCoordinator = MediaRef.parse(imagePath, store) as MediaRef.Store
        assertThat(inCoordinator.ownerId).isEqualTo(store)
        assertThat(inCoordinator.requesterId).isEqualTo(store)
        assertThat(inCoordinator.relativePath).isEqualTo("media/ui-parity/tab-landscape-icon-only-project.png")
        assertThat(inCoordinator.cacheKey).isEqualTo("store:$store:media/ui-parity/tab-landscape-icon-only-project.png")
        assertThat(inCoordinator.label).isEqualTo("tab-landscape-icon-only-project.png")
        assertThat(inCoordinator.webUrl).isEqualTo("https://cursor.com/agents/$store")
        val self = MediaRef.parse("/cursor/stores/self/media/board.png", store) as MediaRef.Store
        assertThat(self.ownerId).isEqualTo(store)
        // In a worker's chat the Project's path still names the Project's store, read as the worker; `self` is the worker's.
        val inWorker = MediaRef.parse(imagePath, "bc-worker") as MediaRef.Store
        assertThat(inWorker.ownerId).isEqualTo(store)
        assertThat(inWorker.requesterId).isEqualTo("bc-worker")
        assertThat((MediaRef.parse("/cursor/stores/self/media/board.png", "bc-worker") as MediaRef.Store).ownerId).isEqualTo("bc-worker")
        // Outside a chat, or in a mount naming nobody, there is no store to read.
        assertThat(MediaRef.parse("/cursor/stores/self/media/board.png", null)).isInstanceOf(MediaRef.Unavailable::class.java)
        assertThat(MediaRef.parse("/cursor/stores/user/media/board.png", store)).isInstanceOf(MediaRef.Unavailable::class.java)
        // An artifact path is still an artifact's.
        assertThat(MediaRef.parse("/opt/cursor/artifacts/demo.png", store)).isInstanceOf(MediaRef.Artifact::class.java)
    }
}
