package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.api.dto.CreateAgentRequestDto
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.ModelParam
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The target half of the Create An Agent body — `repos`, `env`, `autoCreatePR` — as it goes out on the wire, checked
 * against the API reference: a repository is the target; a no-repo agent is started by sending neither `repos` nor
 * `env`; a pool, a machine or a named cloud environment is `env` with its `type`.
 */
class CreateAgentEncodingTest {

    private val request = LaunchRequest(
        prompt = "Add a README with setup instructions",
        repoUrl = "https://github.com/your-org/your-repo",
        ref = "main",
        modelId = "composer-2",
        modelParams = listOf(ModelParam("fast", "true")),
        autoCreatePr = true,
        planMode = false,
        agentId = "bc-00000000-0000-0000-0000-000000000001",
    )

    private fun encode(request: LaunchRequest): String = CursorJson.encodeToString(CreateAgentRequestDto.serializer(), request.toCreateAgentDto())

    @Test
    fun `a repository is the target, and env is left to the default`() {
        assertThat(encode(request)).isEqualTo(
            """{"prompt":{"text":"Add a README with setup instructions"},""" +
                """"agentId":"bc-00000000-0000-0000-0000-000000000001",""" +
                """"model":{"id":"composer-2","params":[{"id":"fast","value":"true"}]},""" +
                """"repos":[{"url":"https://github.com/your-org/your-repo","startingRef":"main"}],""" +
                """"autoCreatePR":true}""",
        )
    }

    @Test
    fun `no repository sends neither repos nor env, and no autoCreatePR either`() {
        // What the reference asks for: "Omit both repos and env to start a no-repo agent." `{ "env": {} }` — the cloud
        // default with its type dropped on the wire — is what the server used to answer with a 400.
        val noRepo = request.copy(repoUrl = null, ref = null)
        val body = encode(noRepo)
        assertThat(body).isEqualTo(
            """{"prompt":{"text":"Add a README with setup instructions"},""" +
                """"agentId":"bc-00000000-0000-0000-0000-000000000001",""" +
                """"model":{"id":"composer-2","params":[{"id":"fast","value":"true"}]}}""",
        )
        assertThat(body).doesNotContain("\"env\"")
        assertThat(body).doesNotContain("\"repos\"")
        assertThat(body).doesNotContain("autoCreatePR")
    }

    @Test
    fun `a blank or remembered branch never reaches a no-repo request`() {
        // The composer keeps its last branch across launches; without a repository there is nothing for it to refer to.
        assertThat(encode(request.copy(repoUrl = null, ref = "main"))).doesNotContain("startingRef")
        assertThat(encode(request.copy(ref = ""))).contains(""""repos":[{"url":"https://github.com/your-org/your-repo"}]""")
    }

    @Test
    fun `a pool or machine goes out as env with its type, with or without a repository`() {
        assertThat(encode(request.copy(env = DeviceTarget.pool("sandbox"), repoUrl = null, ref = null)))
            .contains(""""env":{"type":"pool","name":"sandbox"}""")
        assertThat(encode(request.copy(env = DeviceTarget.machine("bennett#/home/bennett/app"))))
            .contains(""""env":{"type":"machine","name":"bennett"},"repos":[{"url":"https://github.com/your-org/your-repo","startingRef":"main"}]""")
    }

    @Test
    fun `a named cloud environment keeps its type on the wire`() {
        val named = encode(request.copy(env = DeviceTarget.of(EnvType.CLOUD, "staging"), repoUrl = null, ref = null))
        assertThat(named).contains(""""env":{"type":"cloud","name":"staging"}""")
        assertThat(named).doesNotContain("\"repos\"")
    }

    @Test
    fun `plan mode and an unset model or id are encoded as the reference shows`() {
        val bare = encode(request.copy(modelId = null, modelParams = emptyList(), agentId = null, autoCreatePr = false, planMode = true))
        assertThat(bare).isEqualTo(
            """{"prompt":{"text":"Add a README with setup instructions"},""" +
                """"repos":[{"url":"https://github.com/your-org/your-repo","startingRef":"main"}],"mode":"plan"}""",
        )
    }
}
