package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** `DashboardService/GetMe` over Connect JSON: the profile picture the public API has no field for. */
class AccountApiTest {

    private val server = MockWebServer()
    private lateinit var api: AccountApi

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = AccountApi(ConnectJsonClient(client, base), SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `asks the dashboard about the key's own user with an empty message and reads the picture`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"accessToken":"session-1","refreshToken":"rt"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"authId":"auth0|u","userId":42,"email":"alex@example.com","firstName":"Alex","lastName":"Rivera","teamName":"Acme","profilePictureUrl":"https://lh3.googleusercontent.com/a/pic=s96-c","isTeamAdmin":true}""",
            ),
        )

        val profile = api.profile()

        assertThat(profile).isEqualTo(AccountProfile("https://lh3.googleusercontent.com/a/pic=s96-c", "alex@example.com", "Alex", "Rivera", "Acme"))
        server.takeRequest() // the exchange
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/aiserver.v1.DashboardService/GetMe")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer session-1")
        assertThat(request.getHeader("Connect-Protocol-Version")).isEqualTo("1")
        assertThat(request.getHeader("Content-Type")).isEqualTo("application/json")
        assertThat(request.body.readUtf8()).isEqualTo("{}")
    }

    @Test
    fun `an account without a picture, or with blank fields, reads as nulls`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"accessToken":"s","refreshToken":"rt"}"""))
        server.enqueue(MockResponse().setBody("""{"userId":7,"email":"","firstName":"","profilePictureUrl":""}"""))

        assertThat(api.profile()).isEqualTo(AccountProfile(null, null, null, null, null))
    }

    @Test
    fun `a refusal is reported as a Connect error`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"accessToken":"s","refreshToken":"rt"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"permission_denied","message":"Error"}"""))

        val error = runCatching { api.profile() }.exceptionOrNull() as ConnectRpcException

        assertThat(error.httpCode).isEqualTo(403)
        assertThat(error.code).isEqualTo("permission_denied")
    }
}
