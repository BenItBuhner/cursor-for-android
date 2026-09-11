package com.cursorforandroid.data.api

import com.cursorforandroid.data.api.dto.CreateRunRequestDto
import com.cursorforandroid.data.api.dto.PromptDto
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The API's `{ error: { code, message } }` answers as Retrofit delivers them — an [retrofit2.HttpException] whose body
 * is read off the wire — against the helpers every repository decides by.
 */
class CursorApiErrorsTest {

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    /** The exception a follow-up gets when the server answers [status] with [code]. */
    private fun failure(status: Int, code: String, message: String): Throwable = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"$code","message":"$message"}}"""),
        )
        val api = CursorApiFactory.retrofit(OkHttpClient(), server.url("/").toString())
        runCatching { api.createRun("bc-1", CreateRunRequestDto(prompt = PromptDto(text = "Go on"))) }.exceptionOrNull()
            ?: error("The request was expected to fail")
    }

    @Test
    fun `an HTTP error reads the same however many times it is asked`() {
        val busy = failure(409, "agent_busy", "Agent is busy.")

        // The repository decides by the code, then the screen words it: both must see the body the server sent,
        // not an empty one because the first look drained it.
        assertThat(busy.toCursorError()?.code).isEqualTo("agent_busy")
        assertThat(busy.toCursorError()?.code).isEqualTo("agent_busy")
        assertThat(busy.userMessage()).isEqualTo("The agent is still working on the previous prompt.")
        assertThat(busy.toCursorError()?.httpCode).isEqualTo(409)
    }

    @Test
    fun `an error without the API's body still carries the status`() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("Bad Gateway"))
        val api = CursorApiFactory.retrofit(OkHttpClient(), server.url("/").toString())
        val t = runBlocking { runCatching { api.me() }.exceptionOrNull() }!!

        val error = t.toCursorError()!!
        assertThat(error.httpCode).isEqualTo(502)
        assertThat(error.code).isEqualTo("http_502")
        assertThat(error.message).isNotEmpty()
    }

    @Test
    fun `a failure worth another try is told apart from one that is not`() {
        // The agent between turns, the server slow or down, a rate limit: all pass.
        assertThat(failure(409, "agent_stopping", "The agent is stopping.").isTransientFailure()).isTrue()
        assertThat(failure(503, "unavailable", "Try again later.").isTransientFailure()).isTrue()
        assertThat(failure(429, "rate_limited", "Slow down.").isTransientFailure()).isTrue()
        assertThat(SocketTimeoutException("timeout").isTransientFailure()).isTrue()
        assertThat(IOException("unexpected end of stream").isTransientFailure()).isTrue()
        // Being busy is waited out, not retried blindly; the rest will not change by themselves.
        assertThat(failure(409, "agent_busy", "Agent is busy.").isTransientFailure()).isFalse()
        assertThat(failure(409, "agent_archived", "Archived.").isTransientFailure()).isFalse()
        assertThat(failure(409, "run_not_cancellable", "Over.").isTransientFailure()).isFalse()
        assertThat(failure(400, "invalid_request", "Bad prompt.").isTransientFailure()).isFalse()
        assertThat(failure(401, "unauthorized", "No.").isTransientFailure()).isFalse()
        assertThat(failure(404, "agent_not_found", "Gone.").isTransientFailure()).isFalse()
        assertThat(UnknownHostException("api.cursor.com").isTransientFailure()).isFalse()
        assertThat(IllegalStateException("No active run.").isTransientFailure()).isFalse()
    }
}
