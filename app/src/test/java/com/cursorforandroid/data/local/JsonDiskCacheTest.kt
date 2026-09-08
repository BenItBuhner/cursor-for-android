package com.cursorforandroid.data.local

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Encoder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsonDiskCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Serializable
    private data class Note(val title: String, val tags: List<String> = emptyList())

    private var now = 1_000L
    private fun cache(dir: File = folder.newFolder("cache")) = JsonDiskCache(dir, nowProvider = { now }, dispatcher = Dispatchers.Unconfined)

    @Test
    fun `round-trips a value with the time it was written`() = runBlocking<Unit> {
        val cache = cache()
        now = 42_000L
        assertThat(cache.write("a", Note.serializer(), 1, Note("hello", listOf("x", "y")))).isTrue()
        val entry = cache.read("a", Note.serializer(), 1)!!
        assertThat(entry.value).isEqualTo(Note("hello", listOf("x", "y")))
        assertThat(entry.savedAtMillis).isEqualTo(42_000L)
        assertThat(cache.read("missing", Note.serializer(), 1)).isNull()
    }

    @Test
    fun `a schema version bump reads as a miss and drops the stale file`() = runBlocking<Unit> {
        val dir = folder.newFolder("versioned")
        val cache = cache(dir)
        cache.write("a", Note.serializer(), 1, Note("old"))
        assertThat(cache.read("a", Note.serializer(), 2)).isNull()
        assertThat(dir.listFiles()!!.filter { it.name.endsWith(".json") }).isEmpty()
    }

    @Test
    fun `a corrupt or truncated file reads as a miss instead of crashing`() = runBlocking<Unit> {
        val dir = folder.newFolder("corrupt")
        val cache = cache(dir)
        cache.write("a", Note.serializer(), 1, Note("fine"))
        val file = dir.listFiles()!!.single { it.name.endsWith(".json") }
        file.writeText(file.readText().take(20))
        assertThat(cache.read("a", Note.serializer(), 1)).isNull()
        assertThat(file.exists()).isFalse()
        // Writing again recovers the entry.
        cache.write("a", Note.serializer(), 1, Note("again"))
        assertThat(cache.read("a", Note.serializer(), 1)!!.value.title).isEqualTo("again")
    }

    @Test
    fun `writes never leave temp files behind and replace atomically`() = runBlocking<Unit> {
        val dir = folder.newFolder("atomic")
        val cache = cache(dir)
        repeat(5) { i -> cache.write("k", Int.serializer(), 1, i) }
        assertThat(dir.listFiles()!!.map { it.name }).containsExactly("k.json")
        assertThat(cache.read("k", Int.serializer(), 1)!!.value).isEqualTo(4)
    }

    @Test
    fun `keys are sanitised, listed newest first, and pruned to a bound`() = runBlocking<Unit> {
        val dir = folder.newFolder("prune")
        val cache = cache(dir)
        cache.write("agent/one", Int.serializer(), 1, 1)
        val files = dir.listFiles()!!
        assertThat(files.single().name).isEqualTo("agent_one.json")
        // Age the first entry so the ordering is deterministic on coarse file-system clocks.
        files.single().setLastModified(System.currentTimeMillis() - 60_000)
        cache.write("two", Int.serializer(), 1, 2)
        dir.listFiles()!!.single { it.name == "two.json" }.setLastModified(System.currentTimeMillis() - 30_000)
        cache.write("three", Int.serializer(), 1, 3)
        assertThat(cache.keys()).containsExactly("three", "two", "agent_one").inOrder()
        cache.prune(2)
        assertThat(cache.keys()).containsExactly("three", "two").inOrder()
        cache.remove("two")
        assertThat(cache.keys()).containsExactly("three")
        cache.clear()
        assertThat(cache.keys()).isEmpty()
        assertThat(dir.exists()).isFalse()
    }

    @Test
    fun `a write that is under way when the cache is wiped does not land`() = runBlocking<Unit> {
        val dir = folder.newFolder("wiped")
        val root = cache(dir)
        val conversations = root.child("conversations")
        conversations.write("bc-1", Note.serializer(), 1, Note("account A"))

        // What a sign-out does: close the caches, cancel the work that feeds them, then wipe. A writer already
        // past its own cancellation checks (blocking file IO has none) must not recreate what the wipe removed.
        root.invalidate()
        assertThat(conversations.write("bc-2", Note.serializer(), 1, Note("straggler"))).isFalse()
        root.clear()

        assertThat(dir.exists()).isFalse()
        assertThat(File(dir, "conversations/bc-2.json").exists()).isFalse()
        // The next account writes normally again.
        assertThat(conversations.write("bc-3", Note.serializer(), 1, Note("account B"))).isTrue()
        assertThat(conversations.read("bc-3", Note.serializer(), 1)!!.value.title).isEqualTo("account B")
        assertThat(conversations.read("bc-1", Note.serializer(), 1)).isNull()
    }

    @Test
    fun `a write invalidated after its bytes were staged leaves nothing behind`() = runBlocking<Unit> {
        val dir = folder.newFolder("racing")
        val root = cache(dir)
        // Serializing is where the wipe lands: the temp file is written, then the move is refused.
        val racing = object : KSerializer<Note> by Note.serializer() {
            override fun serialize(encoder: Encoder, value: Note) {
                root.invalidate()
                Note.serializer().serialize(encoder, value)
            }
        }
        assertThat(root.write("k", racing, 1, Note("straggler"))).isFalse()
        assertThat(dir.listFiles()!!.map { it.name }).isEmpty()
    }

    @Test
    fun `pruning sweeps a temp file a killed process left behind`() = runBlocking<Unit> {
        val dir = folder.newFolder("orphans")
        val cache = JsonDiskCache(dir, dispatcher = Dispatchers.Unconfined)
        cache.write("k", Int.serializer(), 1, 1)
        val orphan = File(dir, "gone.json.tmp").apply { writeText("half a value") }
        val live = File(dir, "busy.json.tmp").apply { writeText("being written") }
        orphan.setLastModified(System.currentTimeMillis() - 5 * 60_000)

        cache.prune(10)

        assertThat(orphan.exists()).isFalse()
        assertThat(live.exists()).isTrue()
        assertThat(cache.read("k", Int.serializer(), 1)!!.value).isEqualTo(1)
    }

    @Test
    fun `children live in their own directories under the root`() = runBlocking<Unit> {
        val dir = folder.newFolder("root")
        val root = cache(dir)
        root.child("agents").write("list", Int.serializer(), 1, 1)
        root.child("conversations").write("bc-1", Int.serializer(), 1, 2)
        assertThat(File(dir, "agents/list.json").isFile).isTrue()
        assertThat(File(dir, "conversations/bc-1.json").isFile).isTrue()
        root.clear()
        assertThat(dir.exists()).isFalse()
    }
}
