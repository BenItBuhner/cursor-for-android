package com.cursorforandroid.data.repo

import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.McpServer
import com.cursorforandroid.domain.McpTransport
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.PromptImage
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LaunchIdempotencyTest {

    private val png = PromptImage(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "image/png")

    private val request = LaunchRequest(
        prompt = "Fix the flaky login test",
        images = listOf(png),
        repoUrl = "https://github.com/acme/app",
        ref = "main",
        modelId = "claude-fable-5.1-thinking",
        modelParams = listOf(ModelParam("effort", "high")),
        autoCreatePr = true,
        planMode = false,
    )

    @Test
    fun `an unchanged draft keeps its id so a retry adopts the first attempt`() {
        val first = LaunchIdempotency.agentId(request, "nonce-1")
        val second = LaunchIdempotency.agentId(request.copy(), "nonce-1")
        assertThat(second).isEqualTo(first)
        assertThat(LaunchIdempotency.isValid(first)).isTrue()
    }

    @Test
    fun `any edit to the draft mints a new id`() {
        val base = LaunchIdempotency.agentId(request, "nonce-1")
        val edits = listOf(
            request.copy(prompt = "Fix the flaky login test!"),
            request.copy(images = emptyList()),
            request.copy(images = listOf(PromptImage(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x48), "image/png"))),
            request.copy(images = listOf(PromptImage(png.bytes, "image/webp"))),
            request.copy(repoUrl = null),
            request.copy(ref = "develop"),
            request.copy(modelId = "composer-2.5"),
            request.copy(modelParams = listOf(ModelParam("effort", "max"))),
            request.copy(autoCreatePr = false),
            request.copy(planMode = true),
            request.copy(name = "Login fix"),
            request.copy(env = DeviceTarget.machine("studio")),
            request.copy(env = DeviceTarget.pool("gpu")),
        )
        val ids = edits.map { LaunchIdempotency.agentId(it, "nonce-1") }
        assertThat(ids).containsNoDuplicates()
        assertThat(ids).doesNotContain(base)
        ids.forEach { assertThat(LaunchIdempotency.isValid(it)).isTrue() }
    }

    @Test
    fun `the enabled MCP servers are part of the draft`() {
        val linear = McpServer(id = "1", name = "linear", url = "https://mcp.linear.app/mcp", headers = mapOf("Authorization" to "Bearer k"))
        val github = McpServer(id = "2", name = "github", transport = McpTransport.Stdio, command = "npx", args = listOf("-y", "srv"), env = mapOf("TOKEN" to "t"))
        val base = LaunchIdempotency.agentId(request, "n")
        val withServers = LaunchIdempotency.agentId(request.copy(mcpServers = listOf(linear, github)), "n")
        assertThat(withServers).isNotEqualTo(base)
        // Only what goes out counts: a disabled server hashes like an absent one, an unchanged retry keeps the id.
        assertThat(LaunchIdempotency.agentId(request.copy(mcpServers = listOf(linear.copy(enabled = false))), "n")).isEqualTo(base)
        assertThat(LaunchIdempotency.agentId(request.copy(mcpServers = listOf(linear.copy(), github.copy())), "n")).isEqualTo(withServers)
        val edits = listOf(
            request.copy(mcpServers = listOf(linear)),
            request.copy(mcpServers = listOf(linear.copy(headers = mapOf("Authorization" to "Bearer other")), github)),
            request.copy(mcpServers = listOf(linear, github.copy(args = listOf("-y", "other")))),
            request.copy(mcpServers = listOf(linear, github.copy(env = emptyMap()))),
        ).map { LaunchIdempotency.agentId(it, "n") }
        assertThat(edits).containsNoDuplicates()
        assertThat(edits).containsNoneOf(base, withServers)
    }

    @Test
    fun `a fresh nonce separates identical drafts sent on purpose`() {
        assertThat(LaunchIdempotency.agentId(request, "nonce-1")).isNotEqualTo(LaunchIdempotency.agentId(request, LaunchIdempotency.newNonce()))
    }

    @Test
    fun `field boundaries are unambiguous`() {
        // "ab" + "c" must not hash like "a" + "bc", and null must differ from any string, including the empty one.
        val a = LaunchIdempotency.agentId(request.copy(ref = "ab", modelId = "c"), "n")
        val b = LaunchIdempotency.agentId(request.copy(ref = "a", modelId = "bc"), "n")
        assertThat(a).isNotEqualTo(b)
        assertThat(LaunchIdempotency.agentId(request.copy(ref = null), "n")).isNotEqualTo(LaunchIdempotency.agentId(request.copy(ref = ""), "n"))
        assertThat(LaunchIdempotency.agentId(request.copy(ref = null), "n")).isNotEqualTo(LaunchIdempotency.agentId(request.copy(ref = "null"), "n"))
    }
}
