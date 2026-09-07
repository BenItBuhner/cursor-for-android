package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.dto.ModelParamDto
import com.cursorforandroid.data.api.dto.ModelRefDto
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.RunStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Following up against a scriptable backend: what `POST /v1/agents/{id}/runs` carries, and what the list row records
 * about the chat's model afterwards. The API never reports an agent's model, so the row is the only place the switch
 * a follow-up made can be remembered.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AgentRepositoryFollowUpTest {

    private val api = FakeCursorApi()
    private lateinit var agents: AgentRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = PreferencesStore(context)
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = true)
        val session = SessionManager(SecureKeyStore(context), prefs, backend, backend)
        session.enterDemo()
        agents = AgentRepository(session, prefs, AttachmentStore(context))
        api.addIdleAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        // Launched here with Auto: the row knows its model, as it would after a launch from this device.
        agents.patch("bc-1") { it.copy(modelDisplayName = "Auto", modelId = "auto-smart") }
    }

    @Test
    fun `without a model the field is left out and the row keeps the model it had`() = runBlocking<Unit> {
        agents.followUp("bc-1", "Again").getOrThrow()

        val body = api.runRequests.single()
        assertThat(body.model).isNull()
        assertThat(body.mode).isNull()
        val row = agents.agent("bc-1")!!
        assertThat(row.modelId).isEqualTo("auto-smart")
        assertThat(row.modelParams).isEmpty()
        assertThat(row.modelDisplayName).isEqualTo("Auto")
        assertThat(row.runStatus).isEqualTo(RunStatus.RUNNING)
    }

    @Test
    fun `a model switch goes out as the variant's id and params and the row records it once accepted`() = runBlocking<Unit> {
        val params = listOf(ModelParam("fast", "false"))
        agents.followUp("bc-1", "Again", planMode = true, modelId = "composer-2", modelParams = params, modelDisplayName = "Composer 2 · Fast off").getOrThrow()

        val body = api.runRequests.single()
        assertThat(body.model).isEqualTo(ModelRefDto("composer-2", listOf(ModelParamDto("fast", "false"))))
        assertThat(body.mode).isEqualTo("plan")
        val row = agents.agent("bc-1")!!
        assertThat(row.modelId).isEqualTo("composer-2")
        assertThat(row.modelParams).isEqualTo(params)
        assertThat(row.modelDisplayName).isEqualTo("Composer 2 · Fast off")
    }

    @Test
    fun `a parameter-less model goes out without params`() = runBlocking<Unit> {
        agents.followUp("bc-1", "Again", planMode = false, modelId = "gemini-3.8-flash", modelDisplayName = "Gemini 3.8 Flash").getOrThrow()

        val body = api.runRequests.single()
        assertThat(body.model).isEqualTo(ModelRefDto("gemini-3.8-flash", params = null))
        assertThat(body.mode).isEqualTo("agent")
        assertThat(agents.agent("bc-1")!!.modelDisplayName).isEqualTo("Gemini 3.8 Flash")
    }

    @Test
    fun `a switch without a label never keeps the old model's label`() = runBlocking<Unit> {
        agents.followUp("bc-1", "Again", modelId = "gpt-5.6").getOrThrow()

        assertThat(agents.agent("bc-1")!!.modelDisplayName).isEqualTo("gpt-5.6")
    }

    @Test
    fun `a rejected follow-up leaves the row's model as it was`() = runBlocking<Unit> {
        api.failCreateRun = true

        val result = agents.followUp("bc-1", "Again", modelId = "composer-2", modelParams = listOf(ModelParam("fast", "true")), modelDisplayName = "Composer 2 · Fast")

        assertThat(result.isFailure).isTrue()
        assertThat(api.runRequests.single().model?.id).isEqualTo("composer-2")
        val row = agents.agent("bc-1")!!
        assertThat(row.modelId).isEqualTo("auto-smart")
        assertThat(row.modelDisplayName).isEqualTo("Auto")
        assertThat(row.runStatus).isEqualTo(RunStatus.FINISHED)
    }
}
