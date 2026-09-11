package com.cursorforandroid.data.api

import com.google.common.truth.Truth.assertThat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * The server's `{ error: { code, message } }` has to survive every reader of the same failure: the launch path asks
 * for the code, and the composer then asks for the message.
 */
class CursorErrorTest {

    private fun httpFailure(code: Int, body: String): HttpException =
        HttpException(Response.error<Unit>(code, body.toResponseBody("application/json".toMediaType())))

    @Test
    fun `converting the same failure twice reads the server's code and message both times`() {
        val failure = httpFailure(403, """{"error":{"code":"usage_limit_exceeded","message":"Your monthly limit is up."}}""")

        val first = failure.toCursorError()!!
        val second = failure.toCursorError()!!

        assertThat(first.code).isEqualTo("usage_limit_exceeded")
        assertThat(first.message).isEqualTo("Your monthly limit is up.")
        assertThat(second.code).isEqualTo(first.code)
        assertThat(second.message).isEqualTo(first.message)
    }

    @Test
    fun `a message asked for after the code still comes from the server`() {
        val failure = httpFailure(400, """{"error":{"code":"validation_error","message":"repos[0].url isn't a repository you can use."}}""")

        // What AgentRepository.launch does before ChatLauncher turns the same throwable into a reason.
        assertThat(failure.toCursorError()?.code).isNotEqualTo("agent_id_conflict")

        assertThat(failure.userMessage()).isEqualTo("repos[0].url isn't a repository you can use.")
    }

    @Test
    fun `a code the app has its own wording for wins over the server's message`() {
        val failure = httpFailure(429, """{"error":{"code":"rate_limited","message":"Too many requests."}}""")

        assertThat(failure.toCursorError()?.isRateLimited).isTrue()
        assertThat(failure.userMessage()).isEqualTo("Rate limited by Cursor. Try again in a moment.")
    }

    @Test
    fun `a failure with no usable body falls back to the status`() {
        val failure = httpFailure(500, "<html>gateway</html>")

        val error = failure.toCursorError()!!
        assertThat(error.code).isEqualTo("http_500")
        assertThat(error.httpCode).isEqualTo(500)
        assertThat(failure.toCursorError()!!.code).isEqualTo("http_500")
    }
}
