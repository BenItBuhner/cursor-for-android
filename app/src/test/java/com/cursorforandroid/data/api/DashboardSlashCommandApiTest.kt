package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.SlashCommand
import com.cursorforandroid.domain.SlashCommand.Kind
import com.cursorforandroid.domain.SlashCommand.Origin
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The `DashboardService` slash-command RPCs over Connect JSON, as the web composer's `/` popover reads them. */
class DashboardSlashCommandApiTest {

    private val server = MockWebServer()
    private lateinit var api: DashboardSlashCommandApi

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = DashboardSlashCommandApi(ConnectJsonClient(client, base), SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }))
        server.enqueue(MockResponse().setBody("""{"accessToken":"session-1","refreshToken":"rt"}"""))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a repository's skills come with their descriptions and origins, name-only fields fill the gaps`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody(
                """{"skillNames":["review","deploy","legacy-only"],"commandNames":[],"skills":[
                  {"name":"review","description":"","sourcePath":"/home/ubuntu/.cursor/skills-cursor/review/SKILL.md","environments":[],"disabledEnvironments":[]},
                  {"name":"deploy","description":"Deploy the web app","sourcePath":".cursor/skills/deploy/SKILL.md"},
                  {"name":"chat-sdk","description":"Vercel Chat SDK expert guidance","sourcePath":"/home/ubuntu/.cursor/plugins/cache/vercel/skills/chat-sdk/SKILL.md","displayName":"Chat SDK"},
                  {"name":"canvas","description":"Draw","disabledEnvironments":["cloud"]}
                ]}""",
            ),
        )

        val catalog = api.forRepository("https://github.com/acme/web", "main")

        server.takeRequest() // the exchange
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.DashboardService/GetRepoSlashCommands")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer session-1")
        assertThat(request.getHeader("Connect-Protocol-Version")).isEqualTo("1")
        assertThat(request.body.readUtf8()).isEqualTo("""{"repoUrl":"https://github.com/acme/web","gitRef":"main","includeProjectPluginSkills":true}""")

        assertThat(catalog.pending).isFalse()
        assertThat(catalog.entries).containsExactly(
            SlashCommand("review", "", Kind.Skill, Origin.BuiltIn, sourcePath = "/home/ubuntu/.cursor/skills-cursor/review/SKILL.md"),
            SlashCommand("deploy", "Deploy the web app", Kind.Skill, Origin.Project, sourcePath = ".cursor/skills/deploy/SKILL.md"),
            SlashCommand("chat-sdk", "Vercel Chat SDK expert guidance", Kind.Skill, Origin.Plugin, sourcePath = "/home/ubuntu/.cursor/plugins/cache/vercel/skills/chat-sdk/SKILL.md"),
            SlashCommand("legacy-only", "", Kind.Skill, Origin.Unknown),
        ).inOrder()
    }

    @Test
    fun `a blank ref is left out of the request`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{}"""))

        val catalog = api.forRepository("https://github.com/acme/web", "")

        server.takeRequest()
        assertThat(server.takeRequest().body.readUtf8()).isEqualTo("""{"repoUrl":"https://github.com/acme/web","includeProjectPluginSkills":true}""")
        assertThat(catalog.entries).isEmpty()
    }

    @Test
    fun `an agent's list adds the machine's commands and says when the inventory is still pending`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody(
                """{"skillNames":[],"commandNames":["release"],"skills":[{"name":"autopilot","description":"Monitor a PR"}],
                    "commands":[{"name":"release","description":"Cut a release","sourcePath":"/workspace/.cursor/commands/release.md"},{"name":"triage","sourcePath":"/workspace/.cursor/commands/triage.md"}],
                    "machineInventoryPending":true}""",
            ),
        )

        val catalog = api.forAgent("bc-1", "https://github.com/acme/web", null)

        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.DashboardService/GetBackgroundComposerSlashCommands")
        assertThat(request.body.readUtf8()).isEqualTo("""{"bcId":"bc-1","repoUrl":"https://github.com/acme/web"}""")
        assertThat(catalog.pending).isTrue()
        assertThat(catalog.entries).containsExactly(
            SlashCommand("release", "Cut a release", Kind.Command, Origin.Project, sourcePath = "/workspace/.cursor/commands/release.md"),
            SlashCommand("triage", "", Kind.Command, Origin.Project, sourcePath = "/workspace/.cursor/commands/triage.md"),
            SlashCommand("autopilot", "Monitor a PR", Kind.Skill, Origin.Unknown),
        ).inOrder()
    }

    @Test
    fun `global commands are asked for the Agents window's surface and the local-only or switched-off ones are dropped`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody(
                """{"commands":[
                  {"name":"goal","content":"...","description":"Set a goal that Cursor will pursue to completion","availability":"GLOBAL_COMMAND_AVAILABILITY_ALL_AGENTS","argumentHint":"<objective>","disabledSurfaces":[]},
                  {"name":"standup","content":"...","description":"Team standup notes","availability":"GLOBAL_COMMAND_AVAILABILITY_CLOUD_ONLY"},
                  {"name":"local-fix","content":"...","availability":"GLOBAL_COMMAND_AVAILABILITY_LOCAL_ONLY"},
                  {"name":"hidden","content":"...","availability":1,"disabledSurfaces":["GLOBAL_COMMAND_SURFACE_GLASS"]},
                  {"name":"numeric","content":"...","availability":3},
                  {"name":"cli-off","content":"...","disabledSurfaces":[3]}
                ]}""",
            ),
        )

        val commands = api.global()

        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.DashboardService/GetGlobalCommands")
        assertThat(request.body.readUtf8()).isEqualTo("""{"surface":"GLOBAL_COMMAND_SURFACE_GLASS"}""")
        assertThat(commands).containsExactly(
            SlashCommand("goal", "Set a goal that Cursor will pursue to completion", Kind.Command, Origin.Unknown, argumentHint = "<objective>"),
            SlashCommand("standup", "Team standup notes", Kind.Command, Origin.Unknown),
            SlashCommand("cli-off", "", Kind.Command, Origin.Unknown),
        ).inOrder()
    }

    @Test
    fun `a refusal is reported as a Connect error`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"permission_denied","message":"Error"}"""))

        val error = runCatching { api.global() }.exceptionOrNull() as ConnectRpcException

        assertThat(error.httpCode).isEqualTo(403)
        assertThat(error.code).isEqualTo("permission_denied")
    }
}
