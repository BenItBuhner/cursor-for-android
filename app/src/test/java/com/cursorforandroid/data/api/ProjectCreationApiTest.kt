package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.AccountModel
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.ProjectAppearance
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The desktop's Create Project flow on the wire (Cursor 3.20.21 `workbench.glass.main.js`, `CreateProjectDialog` →
 * `CloudAgentRepository._createAgentReal`): one `StartBackgroundComposerFromSnapshot` whose fields are the bundle's,
 * and `RenameBackgroundComposer {bc_id, new_name}` for the name. Shapes here are read off the bundle's message types
 * (`aiserver.v1.StartBackgroundComposerFromSnapshotRequest` fields 1, 9–11, 14, 18, 22–23, 26, 41, 44, 50, 80, 119,
 * 122; `DevcontainerStartingPoint` 1, 11, 15; `EnvironmentRepoConfig`/`EnvironmentRepoEntry`; `agent.v1.ProjectDetails`;
 * `aiserver.v1.ProjectMetadata`/`ProjectAppearance`) and its `new-project-kickoff.js`.
 */
class ProjectCreationApiTest {

    private val server = MockWebServer()
    private lateinit var api: ConnectProjectCreationApi

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = ConnectProjectCreationApi(ConnectJsonClient(client, base), SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a Project on one repository is started the way the desktop dialog starts it`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"composer":{"bcId":"bc-p1","name":"Billing launch","projectMetadata":{"appearance":{"icon":"rocket","colorId":"brand"}},"status":"BACKGROUND_COMPOSER_STATUS_CREATING","lastMessageActivityAtMs":"1700000000000","createdAtMs":"1700000000000"}}"""))

        val record = api.createProject(ProjectDraft("Billing launch", ProjectAppearance("rocket", "brand"), listOf("https://github.com/Acme/Billing.git"), projectId = "bc-p1"))

        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/StartBackgroundComposerFromSnapshot")
        val body = request.json()
        // The client-minted id, the repository as the snapshot's name (`Hg`: host/owner/repo, lowercased, no .git) and as the starting point (`wF`).
        assertThat(body["bcId"]?.jsonPrimitive?.content).isEqualTo("bc-p1")
        assertThat(body["snapshotNameOrId"]?.jsonPrimitive?.content).isEqualTo("github.com/acme/billing")
        // `wF` keeps the path's case and drops `.git`; `Hg` lowercases the whole name.
        assertThat(body["repoUrl"]?.jsonPrimitive?.content).isEqualTo("https://github.com/Acme/Billing")
        val startingPoint = body["devcontainerStartingPoint"]!!.jsonObject
        assertThat(startingPoint["url"]?.jsonPrimitive?.content).isEqualTo("https://github.com/Acme/Billing")
        assertThat(startingPoint["repoConfig"]).isNull()
        assertThat(startingPoint["environmentPublicId"]).isNull()
        assertThat(body["snapshotWorkspaceRootPath"]?.jsonPrimitive?.content).isEqualTo("/workspace")
        assertThat(body["returnImmediately"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(body["autoBranch"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(body["addInitialMessageToResponses"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(body["source"]?.jsonPrimitive?.content).isEqualTo("BACKGROUND_COMPOSER_SOURCE_API")
        // What makes it a Project: the name, `project_details.name` and `project_metadata.appearance`.
        assertThat(body["name"]?.jsonPrimitive?.content).isEqualTo("Billing launch")
        assertThat(body["projectDetails"]!!.jsonObject["name"]?.jsonPrimitive?.content).isEqualTo("Billing launch")
        val appearance = body["projectMetadata"]!!.jsonObject["appearance"]!!.jsonObject
        assertThat(appearance["icon"]?.jsonPrimitive?.content).isEqualTo("rocket")
        assertThat(appearance["colorId"]?.jsonPrimitive?.content).isEqualTo("brand")
        // The kickoff: "Start this Project." as a simulated PROJECT_KICKOFF user message, in the action and in the history.
        val message = body["conversationAction"]!!.jsonObject["userMessageAction"]!!.jsonObject.let { action ->
            assertThat(action["sendToInteractionListener"]?.jsonPrimitive?.content).isEqualTo("true")
            action["userMessage"]!!.jsonObject
        }
        assertThat(message["text"]?.jsonPrimitive?.content).isEqualTo("Start this Project.")
        assertThat(message["messageId"]?.jsonPrimitive?.content).startsWith("msg-")
        assertThat(message["mode"]?.jsonPrimitive?.content).isEqualTo("AGENT_MODE_AGENT")
        assertThat(message["isSimulatedMsg"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(message["simulatedMsgReason"]?.jsonPrimitive?.content).isEqualTo("SIMULATED_MSG_REASON_PROJECT_KICKOFF")
        assertThat(message["simulatedMessageMetadata"]?.jsonObject).isEmpty()
        assertThat(body["startingMessageType"]?.jsonPrimitive?.content).isEqualTo("STARTING_MESSAGE_TYPE_USER_MESSAGE")
        val history = body["conversationHistory"]!!.jsonArray.single().jsonObject
        assertThat(history["text"]?.jsonPrimitive?.content).isEqualTo("Start this Project.")
        assertThat(history["type"]?.jsonPrimitive?.content).isEqualTo("MESSAGE_TYPE_HUMAN")
        assertThat(history["pastChatsExplicitlySet"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(body["skills"]?.jsonArray).isEmpty()
        assertThat(body["repositoryInfo"]?.jsonObject).isEmpty()
        // No model chosen: the desktop's `default` (Auto) as the one `requested_models` entry, and no legacy `model_details`.
        val requested = body["requestedModels"]!!.jsonArray.map { it.jsonObject }
        assertThat(requested.map { it["modelId"]?.jsonPrimitive?.content }).containsExactly("default")
        assertThat(requested.single()["maxMode"]).isNull()
        assertThat(requested.single()["parameters"]).isNull()
        assertThat(body["modelDetails"]).isNull()
        // The record that comes back is the Project's: flagged, with the look, dated.
        assertThat(record.id).isEqualTo("bc-p1")
        assertThat(record.isProject).isTrue()
        assertThat(record.projectAppearance).isEqualTo(ProjectAppearance("rocket", "brand"))
        assertThat(record.activityAtMillis).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `several repositories go out as the desktop's repo_config, the first of them primary, and a blank name is New Project`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"composer":{"bcId":"bc-p2","projectMetadata":{}}}"""))

        api.createProject(ProjectDraft("   ", ProjectAppearance("flag", "green"), listOf("github.com/acme/app", "git@github.com:acme/web.git", "https://github.com/acme/app/"), projectId = "bc-p2"))

        server.takeRequest()
        val body = server.takeRequest().json()
        assertThat(body["name"]?.jsonPrimitive?.content).isEqualTo("New Project")
        assertThat(body["projectDetails"]!!.jsonObject["name"]?.jsonPrimitive?.content).isEqualTo("New Project")
        assertThat(body["snapshotNameOrId"]?.jsonPrimitive?.content).isEqualTo("github.com/acme/app")
        assertThat(body["repoUrl"]?.jsonPrimitive?.content).isEqualTo("https://github.com/acme/app")
        val startingPoint = body["devcontainerStartingPoint"]!!.jsonObject
        assertThat(startingPoint["url"]?.jsonPrimitive?.content).isEqualTo("https://github.com/acme/app")
        // Spellings of one repository are one entry; the entry carries the desktop's empty `scm_repo_node_id`.
        val repos = startingPoint["repoConfig"]!!.jsonObject["repos"]!!.jsonArray.map { it.jsonObject }
        assertThat(repos.map { it["repoUrl"]?.jsonPrimitive?.content }).containsExactly("https://github.com/acme/app", "https://github.com/acme/web").inOrder()
        // The desktop's `scmRepoNodeId: ""` is the proto's default, which Connect JSON leaves off the wire.
        assertThat(repos.all { (it["scmRepoNodeId"]?.jsonPrimitive?.content ?: "") == "" }).isTrue()
    }

    @Test
    fun `a Project without a repository starts in the account's no-repo environment, found or created`() = runBlocking<Unit> {
        // First: an environment fits — personal, no repositories, a blank environment json — and is used.
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"environments":[{"publicId":"env-team","scope":"LOGICAL_ENVIRONMENT_SCOPE_TEAM","repoConfig":{"repos":[]}},{"publicId":"env-repo","scope":"LOGICAL_ENVIRONMENT_SCOPE_PERSONAL","repoConfig":{"repos":[{"repoUrl":"https://github.com/acme/app"}]}},{"publicId":"env-blank","scope":"LOGICAL_ENVIRONMENT_SCOPE_PERSONAL","repoConfig":{"repos":[]},"environmentJson":"{}"}]}"""))
        server.enqueue(MockResponse().setBody("""{"composer":{"bcId":"bc-p3","projectMetadata":{}}}"""))

        api.createProject(ProjectDraft("Scratch", ProjectAppearance("beaker", "cyan"), emptyList(), projectId = "bc-p3"))

        server.takeRequest()
        val list = server.takeRequest()
        assertThat(list.path).isEqualTo("/aiserver.v1.BackgroundComposerService/ListEnvironments")
        assertThat(list.json()["includeEnvironmentJson"]?.jsonPrimitive?.content).isEqualTo("true")
        val start = server.takeRequest()
        assertThat(start.path).isEqualTo("/aiserver.v1.BackgroundComposerService/StartBackgroundComposerFromSnapshot")
        val body = start.json()
        assertThat(body["snapshotNameOrId"]?.jsonPrimitive?.content).isEqualTo("env|env-blank")
        assertThat(body["devcontainerStartingPoint"]!!.jsonObject["environmentPublicId"]?.jsonPrimitive?.content).isEqualTo("env-blank")
        assertThat(body["devcontainerStartingPoint"]!!.jsonObject["url"]).isNull()
        assertThat(body["repoUrl"]).isNull()

        // Then: none fits, so one is written the desktop's way and used.
        server.enqueue(MockResponse().setBody("""{"environments":[]}"""))
        server.enqueue(MockResponse().setBody("""{"environment":{"publicId":"env-new","scope":"LOGICAL_ENVIRONMENT_SCOPE_PERSONAL","repoConfig":{"repos":[]}},"created":true}"""))
        server.enqueue(MockResponse().setBody("""{"composer":{"bcId":"bc-p4","projectMetadata":{}}}"""))

        api.createProject(ProjectDraft("Scratch two", ProjectAppearance("beaker", "cyan"), emptyList(), projectId = "bc-p4"))

        server.takeRequest()
        val write = server.takeRequest()
        assertThat(write.path).isEqualTo("/aiserver.v1.BackgroundComposerService/SetPersonalEnvironmentJson")
        val written = write.json()
        assertThat(written["environmentJson"]?.jsonPrimitive?.content).isEqualTo("{}")
        assertThat(written["repoUrl"]?.jsonPrimitive?.content).isEqualTo("")
        assertThat(written["writeSource"]?.jsonPrimitive?.content).isEqualTo("ENVIRONMENT_WRITE_SOURCE_DASHBOARD")
        assertThat(written["repoConfig"]!!.jsonObject["repos"]?.jsonArray).isEmpty()
        assertThat(server.takeRequest().json()["snapshotNameOrId"]?.jsonPrimitive?.content).isEqualTo("env|env-new")
    }

    /**
     * The account writes proto3 JSON, in which a field at its default is left out: the no-repo environment's own
     * `EnvironmentRepoConfig`, with no repositories, is `"repoConfig": {}` — no `repos` member — and a scope may come as
     * its number. 0.3.41 read the missing member as a missing field and refused the whole list ("Field 'repos' is
     * required … at path: $.environments[27].repoConfig"), so no launch without a repository could get past the
     * lookup on an account that had the environment. The desktop reads the same object as `{repos: []}`
     * (`hasNoRepoConfigIdentity`) and picks it.
     */
    @Test
    fun `the no-repo environment is read in the shape the account sends it, an empty repo config with no repos member`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        val environments = (1..27).joinToString(",") { n -> """{"id":"$n","publicId":"env-$n","name":"app-$n","scope":"LOGICAL_ENVIRONMENT_SCOPE_PERSONAL","repoConfig":{"repos":[{"repoUrl":"https://github.com/acme/app-$n"}]},"environmentJson":"{\"snapshot\":\"default\"}"}""" } +
            """,{"id":"28","publicId":"env-scratch","name":"","scope":1,"repoConfig":{},"environmentJson":"{}"}"""
        server.enqueue(MockResponse().setBody("""{"environments":[$environments]}"""))

        assertThat(api.noRepoEnvironmentPublicId()).isEqualTo("env-scratch")

        server.takeRequest()
        assertThat(server.takeRequest().path).isEqualTo("/aiserver.v1.BackgroundComposerService/ListEnvironments")
        // Found, so nothing was written.
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `an environment of a shape this build cannot read costs nothing but itself, and an unreadable list names the call`() = runBlocking<Unit> {
        // One entry no version of this app knows how to read sits before the no-repo environment.
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"environments":[{"publicId":["not","a","string"],"scope":1},{"publicId":"env-scratch","scope":"LOGICAL_ENVIRONMENT_SCOPE_PERSONAL","repoConfig":{}}]}"""))
        assertThat(api.noRepoEnvironmentPublicId()).isEqualTo("env-scratch")

        // The list itself in a shape that cannot be read at all: the failure names the call, not a field of a DTO.
        server.enqueue(MockResponse().setBody("""{"environments":"none"}"""))
        val unreadable = runCatching { api.noRepoEnvironmentPublicId() }.exceptionOrNull() as ConnectRpcException
        assertThat(unreadable.isUnreadableAnswer).isTrue()
        assertThat(unreadable.message).startsWith("Cursor's answer to ListEnvironments could not be read: ")
    }

    @Test
    fun `the environment pages are followed before a no-repo environment is written`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"environments":[{"publicId":"env-1","scope":1,"repoConfig":{"repos":[{"repoUrl":"https://github.com/acme/app"}]}}],"hasMore":true,"nextPageToken":"page-2"}"""))
        server.enqueue(MockResponse().setBody("""{"environments":[{"publicId":"env-scratch","scope":1,"repoConfig":{}}],"hasMore":false}"""))

        assertThat(api.noRepoEnvironmentPublicId()).isEqualTo("env-scratch")

        server.takeRequest()
        assertThat(server.takeRequest().json()["pageToken"]).isNull()
        assertThat(server.takeRequest().json()["pageToken"]?.jsonPrimitive?.content).isEqualTo("page-2")
        assertThat(server.requestCount).isEqualTo(3)
    }

    /**
     * The account refuses a start that names no model ("At least one model details is required" — what the first
     * build of the sheet was told). The desktop's dialog always names one: `requested_models[0]` is the picked model
     * with its max mode and parameters (`_buildStartRequestModelFields`), or `default` for Auto (`PQp`).
     */
    @Test
    fun `a named model goes out as the desktop's requested_models, with its parameters and max mode`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody(COMPOSER_P6))

        api.createProject(
            ProjectDraft(
                "Tuned", ProjectAppearance("flag", "green"), listOf("https://github.com/acme/app"),
                model = AccountModel("claude-4.5-sonnet", listOf(ModelParam("effort", "high"), ModelParam("", "dropped")), maxMode = true),
                projectId = "bc-p6",
            ),
        )

        server.takeRequest()
        val body = server.takeRequest().json()
        val requested = body["requestedModels"]!!.jsonArray.map { it.jsonObject }
        assertThat(requested).hasSize(1)
        assertThat(requested.single()["modelId"]?.jsonPrimitive?.content).isEqualTo("claude-4.5-sonnet")
        assertThat(requested.single()["maxMode"]?.jsonPrimitive?.content).isEqualTo("true")
        val parameters = requested.single()["parameters"]!!.jsonArray.map { it.jsonObject }
        assertThat(parameters.map { it["id"]?.jsonPrimitive?.content to it["value"]?.jsonPrimitive?.content }).containsExactly("effort" to "high")
        assertThat(body["modelDetails"]).isNull()

        // Auto picked on purpose is the same `default` as nothing picked; a blank id is Auto too.
        server.enqueue(MockResponse().setBody(COMPOSER_P6))
        api.createProject(ProjectDraft("Auto", ProjectAppearance("flag", "green"), listOf("https://github.com/acme/app"), model = AccountModel("default"), projectId = "bc-p7"))
        assertThat(server.takeRequest().json()["requestedModels"]!!.jsonArray.single().jsonObject["modelId"]?.jsonPrimitive?.content).isEqualTo("default")
        server.enqueue(MockResponse().setBody(COMPOSER_P6))
        api.createProject(ProjectDraft("Blank", ProjectAppearance("flag", "green"), listOf("https://github.com/acme/app"), model = AccountModel("  "), projectId = "bc-p8"))
        assertThat(server.takeRequest().json()["requestedModels"]!!.jsonArray.single().jsonObject["modelId"]?.jsonPrimitive?.content).isEqualTo("default")
    }

    @Test
    fun `an answer without the record stands the Project in from what was asked`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("{}"))
        val record = api.createProject(ProjectDraft("Quiet", ProjectAppearance("moon", "purple"), listOf("https://github.com/acme/app"), projectId = "bc-p5"))
        assertThat(record.id).isEqualTo("bc-p5")
        assertThat(record.name).isEqualTo("Quiet")
        assertThat(record.isProject).isTrue()
        assertThat(record.projectAppearance).isEqualTo(ProjectAppearance("moon", "purple"))
        assertThat(record.record?.projectMetadata).isEqualTo("{}")
    }

    @Test
    fun `rename sends bcId and newName and reads the name back`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"name":"Billing launch v2"}"""))
        assertThat(api.renameProject("bc-p1", "  Billing launch v2 ")).isEqualTo("Billing launch v2")
        assertThat(server.takeRequest().path).isEqualTo("/auth/exchange_user_api_key")
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/RenameBackgroundComposer")
        val body = request.json()
        assertThat(body["bcId"]?.jsonPrimitive?.content).isEqualTo("bc-p1")
        assertThat(body["newName"]?.jsonPrimitive?.content).isEqualTo("Billing launch v2")
        // An empty answer keeps the name asked for.
        server.enqueue(MockResponse().setBody("{}"))
        assertThat(api.renameProject("bc-p1", "Third")).isEqualTo("Third")
    }

    @Test
    fun `repository spellings normalise the desktop's way`() {
        assertThat(ConnectProjectCreationApi.canonicalUrl("https://GitHub.com/Acme/App.git/")).isEqualTo("https://github.com/Acme/App")
        assertThat(ConnectProjectCreationApi.canonicalUrl("github.com/acme/app")).isEqualTo("https://github.com/acme/app")
        assertThat(ConnectProjectCreationApi.canonicalUrl("git@github.com:acme/app.git")).isEqualTo("https://github.com/acme/app")
        assertThat(ConnectProjectCreationApi.canonicalUrl("http://gitlab.example.com/group/sub/app")).isEqualTo("http://gitlab.example.com/group/sub/app")
        assertThat(ConnectProjectCreationApi.canonicalUrl("   ")).isEmpty()
        assertThat(ConnectProjectCreationApi.canonicalUrl("github.com")).isEmpty()
        assertThat(ConnectProjectCreationApi.snapshotName("https://GitHub.com/Acme/App.git")).isEqualTo("github.com/acme/app")
        assertThat(ConnectProjectCreationApi.snapshotName("nonsense")).isEmpty()
    }

    private fun session(token: String) = MockResponse().setBody("""{"accessToken":"$token","refreshToken":"rt"}""")

    private companion object {
        const val COMPOSER_P6 = """{"composer":{"bcId":"bc-p6","projectMetadata":{}}}"""
    }

    private fun RecordedRequest.json() = Json.parseToJsonElement(body.readUtf8()).jsonObject
}
