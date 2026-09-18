package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.McpServer
import com.cursorforandroid.domain.McpTransport
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.PromptImage
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
import java.util.Base64

/**
 * The desktop's ordinary cloud-agent start on the wire (Cursor 3.20.21 `workbench.glass.main.js`,
 * `CloudAgentRepository._createAgentReal` @20.543M → `StartBackgroundComposerFromSnapshot`), for a first prompt that
 * carries files: `conversation_action.user_message_action.user_message.selected_context.selected_documents[]` as
 * `_buildSelectedContextForComposer` (@20.750M) fills it from `WKy`, the images inline beside them, `base_branch` and
 * `devcontainer_starting_point.ref` for the branch, `requested_models` for the model, `auto_create_pr` (field 37) and
 * `mcp_config_json` (field 95) in the desktop's plugin shape.
 */
class AgentStartApiTest {

    private val server = MockWebServer()
    private lateinit var api: ConnectAgentStartApi
    private var noRepoLookups = 0

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = ConnectAgentStartApi(
            ConnectJsonClient(client, base),
            SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }),
            noRepoEnvironment = { noRepoLookups++; "env-public-7" },
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a chat on a repository starts with its files as selected_documents beside the inline images, on the branch picked`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"composer":{"bcId":"bc-f1","name":"Read the spec","status":"BACKGROUND_COMPOSER_STATUS_CREATING","createdAtMs":"1700000000000"}}"""))

        val record = api.start(
            StartRequest(
                agentId = "bc-f1",
                text = "Read the attached spec and implement it",
                images = listOf(PromptImage(byteArrayOf(1, 2), "image/PNG")),
                files = listOf(
                    UploadedFile("spec.pdf", "application/pdf", uploadId = "up-1", uuid = "doc-1"),
                    UploadedFile("notes.txt", "text/plain", uploadId = null, data = byteArrayOf(5), uuid = "doc-2"),
                ),
                repoUrl = "https://github.com/Acme/Billing.git",
                ref = "feature/spec",
                modelId = "claude-4",
                modelParams = listOf(ModelParam("effort", "high")),
                planMode = true,
                autoCreatePr = true,
                name = "Spec work",
                mcpServers = listOf(
                    McpServer("1", "linear", McpTransport.Http, url = "https://mcp.linear.app/mcp", headers = mapOf("Authorization" to "Bearer t")),
                    McpServer("2", "gh", McpTransport.Stdio, command = "npx", args = listOf("-y", "@modelcontextprotocol/server-github"), env = mapOf("GITHUB_TOKEN" to "x")),
                    McpServer("3", "off", McpTransport.Http, url = "https://off", enabled = false),
                ),
            ),
        )

        assertThat(record.id).isEqualTo("bc-f1")
        assertThat(record.name).isEqualTo("Read the spec")
        assertThat(noRepoLookups).isEqualTo(0)
        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/StartBackgroundComposerFromSnapshot")
        val body = request.json()
        assertThat(body["bcId"]?.jsonPrimitive?.content).isEqualTo("bc-f1")
        assertThat(body["snapshotNameOrId"]?.jsonPrimitive?.content).isEqualTo("github.com/acme/billing")
        assertThat(body["repoUrl"]?.jsonPrimitive?.content).isEqualTo("https://github.com/Acme/Billing")
        val startingPoint = body["devcontainerStartingPoint"]!!.jsonObject
        assertThat(startingPoint["url"]?.jsonPrimitive?.content).isEqualTo("https://github.com/Acme/Billing")
        assertThat(startingPoint["ref"]?.jsonPrimitive?.content).isEqualTo("feature/spec")
        assertThat(startingPoint.containsKey("environmentPublicId")).isFalse()
        assertThat(body["baseBranch"]?.jsonPrimitive?.content).isEqualTo("feature/spec")
        assertThat(body["autoBranch"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(body["snapshotWorkspaceRootPath"]?.jsonPrimitive?.content).isEqualTo("/workspace")
        assertThat(body["returnImmediately"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(body["addInitialMessageToResponses"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(body["source"]?.jsonPrimitive?.content).isEqualTo("BACKGROUND_COMPOSER_SOURCE_API")
        assertThat(body["startingMessageType"]?.jsonPrimitive?.content).isEqualTo("STARTING_MESSAGE_TYPE_USER_MESSAGE")
        assertThat(body["name"]?.jsonPrimitive?.content).isEqualTo("Spec work")
        assertThat(body["autoCreatePr"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(body["skills"]!!.jsonArray).isEmpty()
        assertThat(body["repositoryInfo"]!!.jsonObject).isEmpty()
        // Nothing that marks a Project.
        assertThat(body.containsKey("projectDetails")).isFalse()
        assertThat(body.containsKey("projectMetadata")).isFalse()

        // The model: agent.v1.RequestedModel {model_id, parameters[{id, value}]}.
        val model = body["requestedModels"]!!.jsonArray.single().jsonObject
        assertThat(model["modelId"]?.jsonPrimitive?.content).isEqualTo("claude-4")
        assertThat(model["parameters"]!!.jsonArray.single().jsonObject.mapValues { it.value.jsonPrimitive.content }).containsExactly("id", "effort", "value", "high")

        // The prompt: agent.v1.UserMessage in the action, with mode PLAN and the attachments in its selected_context.
        val action = body["conversationAction"]!!.jsonObject["userMessageAction"]!!.jsonObject
        assertThat(action["sendToInteractionListener"]?.jsonPrimitive?.content).isEqualTo("true")
        val message = action["userMessage"]!!.jsonObject
        assertThat(message["text"]?.jsonPrimitive?.content).isEqualTo("Read the attached spec and implement it")
        assertThat(message["richText"]?.jsonPrimitive?.content).isEqualTo("Read the attached spec and implement it")
        assertThat(message["messageId"]?.jsonPrimitive?.content).startsWith("msg-")
        assertThat(message["mode"]?.jsonPrimitive?.content).isEqualTo("AGENT_MODE_PLAN")
        val context = message["selectedContext"]!!.jsonObject
        val images = context["selectedImages"]!!.jsonArray.map { it.jsonObject }
        assertThat(images).hasSize(1)
        assertThat(images[0]["data"]?.jsonPrimitive?.content).isEqualTo(Base64.getEncoder().encodeToString(byteArrayOf(1, 2)))
        assertThat(images[0]["mimeType"]?.jsonPrimitive?.content).isEqualTo("image/png")
        val documents = context["selectedDocuments"]!!.jsonArray.map { it.jsonObject }
        assertThat(documents).hasSize(2)
        assertThat(documents[0]["uuid"]?.jsonPrimitive?.content).isEqualTo("doc-1")
        assertThat(documents[0]["filename"]?.jsonPrimitive?.content).isEqualTo("spec.pdf")
        assertThat(documents[0]["mimeType"]?.jsonPrimitive?.content).isEqualTo("application/pdf")
        assertThat(documents[0]["promptUploadRef"]!!.jsonObject["uploadId"]?.jsonPrimitive?.content).isEqualTo("up-1")
        assertThat(documents[0].containsKey("data")).isFalse()
        assertThat(documents[1]["data"]?.jsonPrimitive?.content).isEqualTo(Base64.getEncoder().encodeToString(byteArrayOf(5)))
        assertThat(documents[1].containsKey("promptUploadRef")).isFalse()

        // The history holds the same text as one human message, as A1n's conversationHistory does.
        val history = body["conversationHistory"]!!.jsonArray.single().jsonObject
        assertThat(history["text"]?.jsonPrimitive?.content).isEqualTo("Read the attached spec and implement it")
        assertThat(history["type"]?.jsonPrimitive?.content).isEqualTo("MESSAGE_TYPE_HUMAN")
        assertThat(history["pastChatsExplicitlySet"]?.jsonPrimitive?.content).isEqualTo("true")

        // The inline servers in the desktop's `{"mcpServers": {name: {...}}}` shape, the disabled one left out.
        val mcp = Json.parseToJsonElement(body["mcpConfigJson"]!!.jsonPrimitive.content).jsonObject["mcpServers"]!!.jsonObject
        assertThat(mcp.keys).containsExactly("linear", "gh")
        assertThat(mcp["linear"]!!.jsonObject["url"]?.jsonPrimitive?.content).isEqualTo("https://mcp.linear.app/mcp")
        assertThat(mcp["linear"]!!.jsonObject["headers"]!!.jsonObject["Authorization"]?.jsonPrimitive?.content).isEqualTo("Bearer t")
        assertThat(mcp["gh"]!!.jsonObject["command"]?.jsonPrimitive?.content).isEqualTo("npx")
        assertThat(mcp["gh"]!!.jsonObject["args"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("-y", "@modelcontextprotocol/server-github").inOrder()
        assertThat(mcp["gh"]!!.jsonObject["env"]!!.jsonObject["GITHUB_TOKEN"]?.jsonPrimitive?.content).isEqualTo("x")
    }

    @Test
    fun `a chat with no repository starts in the account's no-repo environment, on Auto, in agent mode, with nothing it was not asked for`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("{}"))

        val record = api.start(
            StartRequest(
                agentId = "bc-f2",
                text = "See the attached file.",
                files = listOf(UploadedFile("data.csv", "text/csv", uploadId = "up-4", uuid = "doc-4")),
                repoUrl = null,
                ref = "main",
                autoCreatePr = true,
            ),
        )

        // The account answered without the record: the row is stood in under the id the request minted.
        assertThat(record.id).isEqualTo("bc-f2")
        assertThat(noRepoLookups).isEqualTo(1)
        server.takeRequest()
        val body = server.takeRequest().json()
        assertThat(body["snapshotNameOrId"]?.jsonPrimitive?.content).isEqualTo("env|env-public-7")
        assertThat(body["devcontainerStartingPoint"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }).containsExactly("environmentPublicId", "env-public-7")
        assertThat(body.containsKey("repoUrl")).isFalse()
        // A branch and a pull request want a repository: neither goes out without one.
        assertThat(body.containsKey("baseBranch")).isFalse()
        assertThat(body.containsKey("autoCreatePr")).isFalse()
        assertThat(body.containsKey("mcpConfigJson")).isFalse()
        assertThat(body.containsKey("name")).isFalse()
        assertThat(body["requestedModels"]!!.jsonArray.single().jsonObject.mapValues { it.value.jsonPrimitive.content }).containsExactly("modelId", "default")
        val message = body["conversationAction"]!!.jsonObject["userMessageAction"]!!.jsonObject["userMessage"]!!.jsonObject
        assertThat(message["mode"]?.jsonPrimitive?.content).isEqualTo("AGENT_MODE_AGENT")
        val context = message["selectedContext"]!!.jsonObject
        assertThat(context.containsKey("selectedImages")).isFalse()
        assertThat(context["selectedDocuments"]!!.jsonArray).hasSize(1)
    }

    @Test
    fun `an uploaded gallery picture or video starts the chat as a SelectedImage by reference and a video document`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("{}"))

        api.start(
            StartRequest(
                agentId = "bc-f4",
                text = "Watch the clip and match the screenshot",
                repoUrl = "https://github.com/acme/billing",
                files = listOf(
                    UploadedFile("IMG_20260917_074100.jpg", "image/jpeg", uploadId = "up-1", uuid = "img-1"),
                    UploadedFile("VID_20260917_074200.mp4", "video/mp4", uploadId = "up-2", uuid = "vid-2"),
                ),
            ),
        )

        server.takeRequest()
        val context = server.takeRequest().json()["conversationAction"]!!.jsonObject["userMessageAction"]!!.jsonObject["userMessage"]!!.jsonObject["selectedContext"]!!.jsonObject
        val image = context["selectedImages"]!!.jsonArray.single().jsonObject
        assertThat(image["uuid"]?.jsonPrimitive?.content).isEqualTo("img-1")
        assertThat(image["mimeType"]?.jsonPrimitive?.content).isEqualTo("image/jpeg")
        assertThat(image["promptUploadRef"]!!.jsonObject["uploadId"]?.jsonPrimitive?.content).isEqualTo("up-1")
        assertThat(image.containsKey("data")).isFalse()
        val video = context["selectedDocuments"]!!.jsonArray.single().jsonObject
        assertThat(video["filename"]?.jsonPrimitive?.content).isEqualTo("VID_20260917_074200.mp4")
        assertThat(video["mimeType"]?.jsonPrimitive?.content).isEqualTo("video/mp4")
        assertThat(video["promptUploadRef"]!!.jsonObject["uploadId"]?.jsonPrimitive?.content).isEqualTo("up-2")
    }

    @Test
    fun `a named cloud environment goes out as the starting point's environment name`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("{}"))

        api.start(StartRequest(agentId = "bc-f3", text = "go", repoUrl = "https://github.com/acme/billing", environmentName = "staging"))

        server.takeRequest()
        val startingPoint = server.takeRequest().json()["devcontainerStartingPoint"]!!.jsonObject
        assertThat(startingPoint["url"]?.jsonPrimitive?.content).isEqualTo("https://github.com/acme/billing")
        assertThat(startingPoint["environmentName"]?.jsonPrimitive?.content).isEqualTo("staging")
        assertThat(startingPoint.containsKey("ref")).isFalse()
    }

    @Test
    fun `the inline MCP config is the desktop's plugin shape, and none when no server is enabled`() {
        assertThat(ConnectAgentStartApi.mcpConfigJson(emptyList())).isNull()
        assertThat(ConnectAgentStartApi.mcpConfigJson(listOf(McpServer("1", "off", url = "https://x", enabled = false)))).isNull()
        val json = ConnectAgentStartApi.mcpConfigJson(listOf(McpServer("1", "docs", McpTransport.Http, url = "https://docs/mcp")))!!
        assertThat(Json.parseToJsonElement(json).jsonObject["mcpServers"]!!.jsonObject["docs"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }).containsExactly("url", "https://docs/mcp")
    }

    private fun session(token: String) = MockResponse().setBody("""{"accessToken":"$token","refreshToken":"rt"}""")

    private fun RecordedRequest.json() = Json.parseToJsonElement(body.readUtf8()).jsonObject
}
