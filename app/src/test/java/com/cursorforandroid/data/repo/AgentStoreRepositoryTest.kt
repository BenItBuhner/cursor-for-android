package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.AgentStoreApi
import com.cursorforandroid.data.api.PresignedStoreRead
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.demo.DemoStores
import com.cursorforandroid.domain.AgentStoreKind
import com.cursorforandroid.domain.AgentStoreRef
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ContextEntry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The gate in front of the Context reads — no call without the capability, the demo's stores standing in — and what
 * the reads make of the stores: which is the Project's and which the user's, the notes at the root, the newest files
 * with a thumbnail where the account presigns one, and a listing kept rather than asked for twice.
 */
class AgentStoreRepositoryTest {

    private var calls = 0
    private var failure: Throwable? = null
    private var now = 1_000_000L

    private val project = AgentStoreRef("st-project", AgentStoreKind.CLOUD, sourceId = "bc-root", lastFileWriteAtMillis = 900_000L)
    private val user = AgentStoreRef("st-user", AgentStoreKind.USER)

    private val api = object : AgentStoreApi {
        override suspend fun storeFor(sourceId: String): String? = project.storeId.takeIf { sourceId == project.sourceId }
        override suspend fun stores(): List<AgentStoreRef> {
            calls++
            failure?.let { throw it }
            return listOf(user, project)
        }
        override suspend fun entries(storeId: String, relativePath: String): List<ContextEntry> {
            calls++
            failure?.let { throw it }
            return when (storeId to relativePath) {
                project.storeId to "" -> listOf(
                    ContextEntry("docs", isDirectory = true, updatedAtMillis = 700_000L),
                    ContextEntry("media", isDirectory = true, updatedAtMillis = 950_000L),
                    ContextEntry("notes.md", isDirectory = false, sizeBytes = 120, updatedAtMillis = 900_000L),
                )
                project.storeId to "docs" -> listOf(ContextEntry("docs/spec.md", isDirectory = false, sizeBytes = 900, updatedAtMillis = 700_000L))
                project.storeId to "media" -> listOf(ContextEntry("media/shot.png", isDirectory = false, sizeBytes = 40_000, updatedAtMillis = 950_000L))
                user.storeId to "" -> listOf(ContextEntry("preferences.md", isDirectory = false, sizeBytes = 80, updatedAtMillis = 500_000L))
                else -> emptyList()
            }
        }
        override suspend fun readFile(storeId: String, relativePath: String): String {
            calls++
            failure?.let { throw it }
            return "# $relativePath"
        }
        override suspend fun presignRead(requesterId: String, storeId: String, relativePath: String): PresignedStoreRead? =
            PresignedStoreRead(relativePath, "https://presigned.example/$storeId/$relativePath", null)
    }

    private fun repo(capabilities: Capabilities = Capabilities.EXTENDED, demo: Boolean = false) =
        AgentStoreRepository(api, capabilities = { capabilities }, isDemo = { demo }, demo = DemoStores, now = { now })

    @Test
    fun `with the capability off nothing is called and every read names Extended mode`() = runBlocking<Unit> {
        val repo = repo(Capabilities.DOCUMENTED)
        assertThat(repo.stores()).isEqualTo(VmRead.NotAvailable(AgentStoreRepository.NEEDS_EXTENDED_MODE))
        assertThat(repo.storesFor("bc-root", null)).isEqualTo(VmRead.NotAvailable(AgentStoreRepository.NEEDS_EXTENDED_MODE))
        assertThat(repo.notes(project)).isEqualTo(VmRead.NotAvailable(AgentStoreRepository.NEEDS_EXTENDED_MODE))
        assertThat(repo.recents("bc-root", listOf(project))).isEqualTo(VmRead.NotAvailable(AgentStoreRepository.NEEDS_EXTENDED_MODE))
        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun `the Project's store is the one its coordinator owns and the user's is the USER store`() = runBlocking<Unit> {
        val stores = (repo().storesFor("bc-worker", projectId = "bc-root") as VmRead.Loaded).value
        assertThat(stores.project).isEqualTo(project)
        assertThat(stores.user).isEqualTo(user)
        // A chat of the account's own with no store: the user's alone.
        val own = (repo().storesFor("bc-lonely", projectId = null) as VmRead.Loaded).value
        assertThat(own.project).isNull()
        assertThat(own.user).isEqualTo(user)
    }

    @Test
    fun `notes are the root's notes md, and a store without one says so rather than failing`() = runBlocking<Unit> {
        val repo = repo()
        val notes = (repo.notes(project) as VmRead.Loaded).value
        assertThat(notes?.path).isEqualTo("notes.md")
        assertThat(notes?.text).isEqualTo("# notes.md")
        assertThat(notes?.isMarkdown).isTrue()
        assertThat((repo.notes(user) as VmRead.Loaded).value).isNull()
    }

    @Test
    fun `recents are the newest files across the stores, pictures with the URL the account presigned`() = runBlocking<Unit> {
        val recents = (repo().recents("bc-root", listOf(project, user)) as VmRead.Loaded).value
        assertThat(recents.map { it.entry.relativePath }).containsExactly("media/shot.png", "notes.md", "docs/spec.md", "preferences.md").inOrder()
        assertThat(recents.first().thumbnailUrl).isEqualTo("https://presigned.example/st-project/media/shot.png")
        assertThat(recents[1].thumbnailUrl).isNull()
        assertThat(recents[1].isImage).isFalse()
    }

    @Test
    fun `a listing is kept for its TTL and re-read after it or on force`() = runBlocking<Unit> {
        val repo = repo()
        repo.entries(project, "")
        repo.entries(project, "")
        assertThat(calls).isEqualTo(1)
        now += AgentStoreRepository.TTL_MS + 1
        repo.entries(project, "")
        assertThat(calls).isEqualTo(2)
        repo.entries(project, "", force = true)
        assertThat(calls).isEqualTo(3)
    }

    @Test
    fun `a method Cursor no longer offers is named as such`() = runBlocking<Unit> {
        failure = ConnectRpcException(404, "unimplemented", "gone")
        val read = repo().stores()
        assertThat(read).isEqualTo(VmRead.Failed(AgentStoreRepository.ENDPOINT_CHANGED, endpointChanged = true))
    }

    @Test
    fun `the demo answers from its own stores without the account`() = runBlocking<Unit> {
        val repo = repo(Capabilities.DOCUMENTED, demo = true)
        val stores = (repo.storesFor(DemoData.PROJECT_ID, DemoData.PROJECT_ID) as VmRead.Loaded).value
        assertThat(stores.project?.storeId).isEqualTo(DemoStores.PROJECT_STORE_ID)
        assertThat(stores.user?.kind).isEqualTo(AgentStoreKind.USER)
        val notes = (repo.notes(stores.project!!) as VmRead.Loaded).value
        assertThat(notes?.text).contains("Cesium billing launch")
        val root = (repo.entries(stores.project!!, "") as VmRead.Loaded).value
        assertThat(root.map { it.name }).containsExactly("docs", "inbox", "internal", "media", "archived.md", "notes.md").inOrder()
        val recents = (repo.recents(DemoData.PROJECT_ID, stores.all) as VmRead.Loaded).value
        assertThat(recents).isNotEmpty()
        assertThat(recents.first { it.isImage }.thumbnailUrl).isNotNull()
        assertThat(calls).isEqualTo(0)
    }
}
