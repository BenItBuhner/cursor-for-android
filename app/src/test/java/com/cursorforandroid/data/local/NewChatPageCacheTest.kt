package com.cursorforandroid.data.local

import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.domain.NewChatHomeChoice
import com.cursorforandroid.ui.home.NewChatHomeFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NewChatPageCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val alex = CursorUser("Key", "alex@example.com", "Alex", "Rivera", null)
    private val sam = CursorUser("Other key", "sam@example.com", "Sam", "Lee", null)

    private var now = 1_000L
    private val dir: File by lazy { folder.newFolder("cursor") }
    private val root: JsonDiskCache by lazy { JsonDiskCache(dir, nowProvider = { now }, dispatcher = Dispatchers.Unconfined) }

    /** A launch's cache over the same files: nothing held in memory from the one before. */
    private fun launch() = NewChatPageCache(root.child("newchat"), scope = CoroutineScope(Dispatchers.Unconfined))

    private val pageFile: File get() = File(dir, "newchat/page.json")

    private fun snapshot(user: CursorUser = alex, choice: NewChatHome? = null, projects: List<AgentRow> = NewChatHomeFixtures.list().projectRows) =
        NewChatPageSnapshot.of(user, NewChatHomeChoice(choice), extendedMode = true, projects = projects)

    @Test
    fun `the page saved by one launch is the next launch's seed, shortcut for shortcut`() = runBlocking<Unit> {
        val rows = NewChatHomeFixtures.list().projectRows
        launch().save(snapshot(choice = NewChatHome.PROJECTS, projects = rows))

        val next = launch().apply { warm() }
        val seed = next.seed(alex)!!
        assertThat(seed.choice).isEqualTo(NewChatHomeChoice(NewChatHome.PROJECTS))
        assertThat(seed.extendedMode).isTrue()
        val seeded = seed.projectRows()
        assertThat(seeded.map { it.agent.id }).isEqualTo(rows.map { it.agent.id })
        assertThat(seeded.map { it.agent.name }).isEqualTo(rows.map { it.agent.name })
        assertThat(seeded.map { it.agent.projectAppearance }).isEqualTo(rows.map { it.agent.projectAppearance })
        assertThat(seeded.map { it.indicator }).isEqualTo(rows.map { it.indicator })
        assertThat(seeded.map { it.children.map { child -> child.agent.id } }).isEqualTo(rows.map { it.children.map { child -> child.agent.id } })
        assertThat(seeded.map { it.shownCount }).isEqualTo(rows.map { it.shownCount })
    }

    /** The stacked layout opens on its Projects from the first frame as the Projects layout does: its choice seeds the page. */
    @Test
    fun `a page laid out as Projects + Recent seeds the next launch with that choice and its shortcuts`() = runBlocking<Unit> {
        val rows = NewChatHomeFixtures.list().projectRows
        launch().save(snapshot(choice = NewChatHome.PROJECTS_RECENT, projects = rows))
        assertThat(pageFile.readText()).contains("\"chosenHome\":\"projects_recent\"")

        val seed = launch().apply { warm() }.seed(alex)!!
        assertThat(seed.choice).isEqualTo(NewChatHomeChoice(NewChatHome.PROJECTS_RECENT))
        assertThat(seed.projectRows().map { it.agent.id }).isEqualTo(rows.map { it.agent.id })
    }

    @Test
    fun `what each shortcut's corner shows comes back as it was`() = runBlocking<Unit> {
        val rows = NewChatHomeFixtures.shortcutStates()
        launch().save(snapshot(projects = rows))

        val seeded = launch().seed(alex)!!.projectRows()
        assertThat(seeded.map { it.workingCount }).isEqualTo(rows.map { it.workingCount })
        assertThat(seeded.map { it.workingCount }).containsAtLeast(1, 2, 12)
        assertThat(seeded.map { it.isUnread }).isEqualTo(rows.map { it.isUnread })
        assertThat(seeded.map { it.indicator }).isEqualTo(rows.map { it.indicator })
    }

    @Test
    fun `the Projects hidden from the page are kept with it, beside the rest`() = runBlocking<Unit> {
        val rows = NewChatHomeFixtures.list().projectRows
        val hidden = setOf(rows[1].agent.id, rows[3].agent.id)
        launch().save(NewChatPageSnapshot.of(alex, NewChatHomeChoice(null), extendedMode = true, projects = rows, hidden = hidden + "bc-not-a-project"))

        val seed = launch().seed(alex)!!
        assertThat(seed.hiddenProjectIds).isEqualTo(hidden)
        assertThat(seed.projects.map { it.agent.id }).isEqualTo(rows.map { it.agent.id })
        assertThat(seed.projectRows().map { it.agent.id }).isEqualTo(rows.map { it.agent.id })
    }

    @Test
    fun `an automatic layout is saved as none chosen`() = runBlocking<Unit> {
        launch().save(snapshot(choice = null))
        assertThat(launch().seed(alex)!!.choice).isEqualTo(NewChatHomeChoice(null))
    }

    @Test
    fun `the chats inside a Project keep only what their state is drawn from`() = runBlocking<Unit> {
        val rows = NewChatHomeFixtures.list().projectRows
        val billing = rows.first { it.agent.id == NewChatHomeFixtures.BILLING }
        assertThat(billing.children.any { it.agent.branches.isNotEmpty() }).isTrue()
        launch().save(snapshot(projects = rows))

        val seeded = launch().seed(alex)!!.projectRows().first { it.agent.id == NewChatHomeFixtures.BILLING }
        assertThat(seeded.agent).isEqualTo(billing.agent.copy(record = null))
        seeded.children.forEach { child ->
            assertThat(child.agent.branches).isEmpty()
            assertThat(child.agent.summary).isNull()
            assertThat(child.agent.record).isNull()
            assertThat(child.agent.parent).isNotNull()
        }
        assertThat(seeded.children.map { it.agent.runStatus }).isEqualTo(billing.children.map { it.agent.runStatus })
    }

    @Test
    fun `another account's page is never a seed`() = runBlocking<Unit> {
        launch().save(snapshot(user = alex))
        assertThat(launch().seed(sam)).isNull()
        assertThat(launch().seed(null)).isNull()
        assertThat(launch().seed(alex)).isNotNull()
    }

    @Test
    fun `nothing saved, or a file that cannot be read, seeds nothing`() = runBlocking<Unit> {
        assertThat(launch().seed(alex)).isNull()
        pageFile.parentFile!!.mkdirs()
        pageFile.writeText("{ not json")
        assertThat(launch().seed(alex)).isNull()
    }

    @Test
    fun `within one process the last page saved is the seed, without the file`() = runBlocking<Unit> {
        val cache = launch()
        cache.save(snapshot(projects = NewChatHomeFixtures.list().projectRows))
        val fewer = NewChatHomeFixtures.list().projectRows.drop(2)
        cache.save(snapshot(projects = fewer))
        pageFile.delete()
        assertThat(cache.seed(alex)!!.projectRows().map { it.agent.id }).isEqualTo(fewer.map { it.agent.id })
    }

    @Test
    fun `a sign-out forgets the page, and a save from before its wipe lands nowhere`() = runBlocking<Unit> {
        val cache = launch()
        cache.save(snapshot())
        cache.forget()
        assertThat(cache.seed(alex)).isNull()

        root.invalidate()
        cache.save(snapshot(projects = NewChatHomeFixtures.list().projectRows.take(1)))
        root.clear()
        assertThat(pageFile.exists()).isFalse()
        assertThat(launch().seed(alex)).isNull()
    }

    @Test
    fun `the page unchanged is not written again`() = runBlocking<Unit> {
        now = 1_000L
        launch().save(snapshot())
        val written = pageFile.readText()

        now = 9_000L
        val next = launch().apply { warm() }
        next.save(snapshot())
        assertThat(pageFile.readText()).isEqualTo(written)

        next.save(snapshot(projects = NewChatHomeFixtures.list().projectRows.drop(1)))
        assertThat(pageFile.readText()).isNotEqualTo(written)
        assertThat(launch().seed(alex)!!.projects).hasSize(NewChatHomeFixtures.list().projectRows.size - 1)
    }
}
