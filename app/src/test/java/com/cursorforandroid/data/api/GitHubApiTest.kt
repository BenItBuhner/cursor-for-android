package com.cursorforandroid.data.api

import com.cursorforandroid.data.repo.GitHubPullRequestSource
import com.cursorforandroid.data.repo.PullRequestLookup
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.SlashCommand
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The anonymous GitHub reads that stand in for the account service with Extended mode off, against a fake api.github.com. */
class GitHubApiTest {

    private val server = MockWebServer()
    private var now = 1_800_000_000_000L
    private lateinit var gitHub: GitHubApi

    @Before
    fun setUp() {
        server.start()
        gitHub = GitHubApi(OkHttpClient(), baseUrl = server.url("/").toString(), now = { now })
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `repository and pull request URLs are recognised in the forms the API and git use, and nothing else`() {
        assertThat(GitHubRepo.parse("https://github.com/acme/app")).isEqualTo(GitHubRepo("acme", "app"))
        assertThat(GitHubRepo.parse("https://github.com/acme/app.git/")).isEqualTo(GitHubRepo("acme", "app"))
        assertThat(GitHubRepo.parse("git@github.com:acme/my-app.git")).isEqualTo(GitHubRepo("acme", "my-app"))
        assertThat(GitHubRepo.parse("github.com/acme/app")).isEqualTo(GitHubRepo("acme", "app"))
        assertThat(GitHubRepo.parse("https://gitlab.com/acme/app")).isNull()
        assertThat(GitHubRepo.parse("https://github.com/acme")).isNull()
        assertThat(GitHubRepo.parse(null)).isNull()

        assertThat(GitHubPullRequestRef.parse("https://github.com/acme/app/pull/42")).isEqualTo(GitHubPullRequestRef(GitHubRepo("acme", "app"), 42))
        assertThat(GitHubPullRequestRef.parse("https://github.com/acme/app/pull/42/files?x=1#top")).isEqualTo(GitHubPullRequestRef(GitHubRepo("acme", "app"), 42))
        assertThat(GitHubPullRequestRef.parse("https://github.com/acme/app/issues/42")).isNull()
        assertThat(GitHubPullRequestRef.parse("https://gitlab.com/acme/app/-/merge_requests/3")).isNull()
    }

    @Test
    fun `a pull request is read with GitHub's headers and mapped to the four states`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"state":"open","merged":false,"draft":false,"title":"x"}"""))
        server.enqueue(MockResponse().setBody("""{"state":"open","merged":false,"draft":true}"""))
        server.enqueue(MockResponse().setBody("""{"state":"closed","merged":true,"draft":false}"""))
        server.enqueue(MockResponse().setBody("""{"state":"closed","merged":false,"draft":false}"""))
        val ref = GitHubPullRequestRef(GitHubRepo("acme", "app"), 42)

        val states = List(4) { gitHub.pullRequest(ref).toPullRequestState() }

        assertThat(states).containsExactly(PullRequestState.Open, PullRequestState.Draft, PullRequestState.Merged, PullRequestState.Closed).inOrder()
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/repos/acme/app/pulls/42")
        assertThat(request.getHeader("Accept")).isEqualTo("application/vnd.github+json")
        assertThat(request.getHeader("X-GitHub-Api-Version")).isEqualTo(GitHubApi.API_VERSION)
        assertThat(request.getHeader("Authorization")).isNull()
    }

    @Test
    fun `the source answers unreadable for what GitHub cannot show and for other hosts, failed for the rest`() = runBlocking<Unit> {
        val source = GitHubPullRequestSource(gitHub)
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setBody("""{"state":"open","merged":false,"draft":false}"""))

        assertThat(source.lookup("https://github.com/acme/private/pull/1")).isEqualTo(PullRequestLookup.Unreadable)
        assertThat(source.lookup("https://github.com/acme/app/pull/2")).isEqualTo(PullRequestLookup.Failed)
        assertThat(source.lookup("https://github.com/acme/app/pull/3")).isEqualTo(PullRequestLookup.Found(PullRequestState.Open))
        assertThat(source.lookup("https://gitlab.com/acme/app/-/merge_requests/3")).isEqualTo(PullRequestLookup.Unreadable)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `an exhausted rate limit is remembered until GitHub says it lifts, and nothing is asked meanwhile`() = runBlocking<Unit> {
        val resetAt = now / 1000 + 1800
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("X-RateLimit-Remaining", "0")
                .setHeader("X-RateLimit-Reset", resetAt.toString())
                .setBody("""{"message":"API rate limit exceeded"}"""),
        )
        server.enqueue(MockResponse().setBody("""{"state":"open","merged":false,"draft":false}"""))
        val ref = GitHubPullRequestRef(GitHubRepo("acme", "app"), 1)

        val first = runCatching { gitHub.pullRequest(ref) }.exceptionOrNull() as GitHubApiException
        assertThat(first.isRateLimited).isTrue()
        assertThat(first.rateLimitResetAtMs).isEqualTo(resetAt * 1000)
        assertThat(gitHub.rateLimitedUntil).isEqualTo(resetAt * 1000)

        val second = runCatching { gitHub.pullRequest(ref) }.exceptionOrNull() as GitHubApiException
        assertThat(second.isRateLimited).isTrue()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(GitHubPullRequestSource(gitHub).lookup("https://github.com/acme/app/pull/1")).isEqualTo(PullRequestLookup.Failed)

        now = resetAt * 1000 + 1
        assertThat(gitHub.rateLimitedUntil).isNull()
        assertThat(gitHub.pullRequest(ref).state).isEqualTo("open")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a secondary limit without a reset time pauses for the default, and a plain 403 is not a limit`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        val ref = GitHubPullRequestRef(GitHubRepo("acme", "app"), 1)
        val limited = runCatching { gitHub.pullRequest(ref) }.exceptionOrNull() as GitHubApiException
        assertThat(limited.rateLimitResetAtMs).isEqualTo(now + GitHubApi.DEFAULT_RATE_LIMIT_PAUSE_MS)

        now += GitHubApi.DEFAULT_RATE_LIMIT_PAUSE_MS + 1
        server.enqueue(MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "41").setBody("{}"))
        val forbidden = runCatching { gitHub.pullRequest(ref) }.exceptionOrNull() as GitHubApiException
        assertThat(forbidden.isRateLimited).isFalse()
        assertThat(forbidden.httpCode).isEqualTo(403)
        assertThat(gitHub.rateLimitedUntil).isNull()
    }

    @Test
    fun `a repository's tree yields its project skills and commands, and a private one yields the built-ins alone`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody(
                """{"sha":"abc","truncated":false,"tree":[
                    {"path":".cursor","type":"tree"},
                    {"path":".cursor/skills/deploy-web/SKILL.md","type":"blob"},
                    {"path":".cursor/skills/release/SKILL.md","type":"blob"},
                    {"path":".cursor/skills/release/scripts/tag.sh","type":"blob"},
                    {"path":".cursor/commands/triage.md","type":"blob"},
                    {"path":"README.md","type":"blob"}
                ]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        val api = GitHubSlashCommandApi(gitHub)

        val catalog = api.forRepository("https://github.com/acme/app", "main")
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/repos/acme/app/git/trees/main?recursive=1")
        assertThat(catalog.entries.map { it.name }).containsExactly("triage", "deploy-web", "release").inOrder()
        assertThat(catalog.byName("triage")).isEqualTo(SlashCommand("triage", kind = SlashCommand.Kind.Command, origin = SlashCommand.Origin.Project, sourcePath = ".cursor/commands/triage.md"))
        assertThat(catalog.byName("deploy-web")?.kind).isEqualTo(SlashCommand.Kind.Skill)
        assertThat(catalog.byName("deploy-web")?.sourcePath).isEqualTo(".cursor/skills/deploy-web/SKILL.md")

        assertThat(api.forRepository("https://github.com/acme/private", null)).isEqualTo(com.cursorforandroid.domain.SlashCatalog())
        assertThat(server.takeRequest().path).isEqualTo("/repos/acme/private/git/trees/HEAD?recursive=1")

        // Another host, or no repository at all: nothing to read, and nothing asked.
        assertThat(api.forRepository("https://gitlab.com/acme/app", "main").entries).isEmpty()
        assertThat(api.forAgent("bc-1", null, null).entries).isEmpty()
        assertThat(api.global()).isEmpty()
        assertThat(server.requestCount).isEqualTo(2)
    }
}
