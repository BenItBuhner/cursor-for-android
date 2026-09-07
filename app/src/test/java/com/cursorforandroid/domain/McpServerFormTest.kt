package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class McpServerFormTest {

    private val linear = McpServer(id = "1", name = "linear", transport = McpTransport.Http, url = "https://mcp.linear.app/mcp")

    @Test
    fun `headers and env parse one pair per line and round-trip through the formatters`() {
        val headers = McpServerForm.parseHeaders("Authorization: Bearer abc:def\n\n  X-Team : cursor  \n").getOrThrow()
        assertThat(headers).containsExactly("Authorization", "Bearer abc:def", "X-Team", "cursor").inOrder()
        assertThat(McpServerForm.formatHeaders(headers)).isEqualTo("Authorization: Bearer abc:def\nX-Team: cursor")

        val env = McpServerForm.parseEnv("GITHUB_TOKEN=ghp_x=y\nDEBUG=1").getOrThrow()
        assertThat(env).containsExactly("GITHUB_TOKEN", "ghp_x=y", "DEBUG", "1").inOrder()
        assertThat(McpServerForm.formatEnv(env)).isEqualTo("GITHUB_TOKEN=ghp_x=y\nDEBUG=1")

        assertThat(McpServerForm.parseArgs("-y\n\n @modelcontextprotocol/server-github \n")).containsExactly("-y", "@modelcontextprotocol/server-github").inOrder()
        assertThat(McpServerForm.parseHeaders("").getOrThrow()).isEmpty()
    }

    @Test
    fun `lines without a separator are rejected with a hint`() {
        assertThat(McpServerForm.parseHeaders("Authorization Bearer x").exceptionOrNull()?.message).contains("Name: value")
        assertThat(McpServerForm.parseEnv("=value").exceptionOrNull()?.message).contains("NAME=value")
    }

    @Test
    fun `validation covers name, uniqueness and the transport's required field`() {
        assertThat(McpServerForm.validate(linear, emptyList())).isNull()
        assertThat(McpServerForm.validate(linear.copy(name = " "), emptyList())).isEqualTo("Give the server a name.")
        assertThat(McpServerForm.validate(linear.copy(name = "my server"), emptyList())).contains("spaces")
        assertThat(McpServerForm.validate(linear.copy(id = "2", name = "Linear"), listOf(linear))).contains("already have")
        // Editing the same server keeps its own name.
        assertThat(McpServerForm.validate(linear.copy(url = "https://example.com/mcp"), listOf(linear))).isNull()

        assertThat(McpServerForm.validate(linear.copy(url = ""), emptyList())).isEqualTo("Enter the server URL.")
        assertThat(McpServerForm.validate(linear.copy(url = "mcp.linear.app"), emptyList())).contains("http or https")
        assertThat(McpServerForm.validate(linear.copy(url = "ftp://mcp.linear.app"), emptyList())).contains("http or https")
        assertThat(McpServerForm.validate(linear.copy(url = "https://user:pw@mcp.linear.app/mcp"), emptyList())).contains("username or password")
        assertThat(McpServerForm.validate(linear.copy(url = "https://bad url"), emptyList())).contains("http or https")

        val stdio = McpServer(id = "3", name = "github", transport = McpTransport.Stdio, command = "")
        assertThat(McpServerForm.validate(stdio, emptyList())).contains("command")
        assertThat(McpServerForm.validate(stdio.copy(command = "npx"), emptyList())).isNull()
    }

    @Test
    fun `summary shows the host for http and the command line for stdio`() {
        assertThat(linear.summary).isEqualTo("mcp.linear.app/mcp")
        assertThat(McpServer(id = "3", name = "gh", transport = McpTransport.Stdio, command = "npx", args = listOf("-y", "pkg")).summary).isEqualTo("npx -y pkg")
    }
}
