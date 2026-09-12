package com.cursorforandroid.data.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The probe that tells which websockify URL serves the desktop before a WebView is pointed at it. */
class DesktopProbeTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun url(path: String) = server.url(path).toString().replaceFirst("http://", "ws://")

    private fun upgrade(): MockResponse = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) = Unit
    })

    @Test
    fun `a URL whose handshake completes is reachable, and the socket is closed at once`() = runBlocking<Unit> {
        server.enqueue(upgrade())
        val probe = DesktopProbe({ client }, origin = "https://desktop.cursor-for-android.invalid", timeoutMs = 5_000)

        assertThat(probe.probe(url("/websockify?network_token=t"))).isEqualTo(ProbeResult.Reachable)
        val request = server.takeRequest()
        assertThat(request.getHeader("Upgrade")).isEqualTo("websocket")
        assertThat(request.getHeader("Origin")).isEqualTo("https://desktop.cursor-for-android.invalid")
        assertThat(request.getHeader("Sec-WebSocket-Protocol")).isEqualTo("binary")
        assertThat(request.path).isEqualTo("/websockify?network_token=t")
    }

    @Test
    fun `a refused handshake names the status, and the first reachable candidate wins with the failures before it`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(upgrade())
        val probe = DesktopProbe({ client }, timeoutMs = 5_000)

        val (winner, failures) = probe.firstReachable(listOf(url("/first"), url("/second"), url("/third")))

        assertThat(winner).isEqualTo(url("/second"))
        assertThat(failures).containsExactly(ProbeResult.Refused(403, "The desktop endpoint answered HTTP 403."))
        // The third candidate was never asked for.
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `no candidate answering is no winner, with every failure kept in order`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(401))
        val probe = DesktopProbe({ client }, timeoutMs = 5_000)

        val (winner, failures) = probe.firstReachable(listOf(url("/a"), url("/b")))

        assertThat(winner).isNull()
        assertThat(failures.map { (it as ProbeResult.Refused).httpCode }).containsExactly(404, 401).inOrder()
    }

    @Test
    fun `a host that does not answer is unreachable, not an exception`() = runBlocking<Unit> {
        val gone = MockWebServer()
        gone.start()
        val dead = gone.url("/").toString().replaceFirst("http://", "ws://")
        gone.shutdown()
        val probe = DesktopProbe({ client }, timeoutMs = 3_000)

        val result = probe.probe(dead)

        assertThat(result).isInstanceOf(ProbeResult.Unreachable::class.java)
    }
}
