package com.cursorforandroid.domain

import com.cursorforandroid.domain.ModelResolution.Candidate
import com.cursorforandroid.domain.ModelResolution.Source
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The order a composer resolves its model in, on a catalogue shaped like the live one — Auto first, a model with
 * variants, one with aliases, one without parameters — and what happens when a link in the chain is missing.
 */
class ModelResolutionTest {

    private val auto = ModelOption(id = "auto-smart", displayName = "Auto", description = "Cursor picks the model.")
    private val fast = ModelVariant("Composer 2.5", listOf(ModelParam("fast", "true")), isDefault = true)
    private val slow = ModelVariant("Composer 2.5", listOf(ModelParam("fast", "false")), isDefault = false)
    private val composer = ModelOption(id = "composer-2.5", displayName = "Composer 2.5", variants = listOf(fast, slow), aliases = listOf("composer-latest", "composer"))
    private val high = ModelVariant("GPT-5.6 High", listOf(ModelParam("effort", "high")), isDefault = true)
    private val low = ModelVariant("GPT-5.6 Low", listOf(ModelParam("effort", "low")), isDefault = false)
    private val gpt = ModelOption(id = "gpt-5.6", displayName = "GPT-5.6", variants = listOf(high, low))
    private val gemini = ModelOption(id = "gemini-3.8-flash", displayName = "Gemini 3.8 Flash", variants = listOf(ModelVariant("Gemini 3.8 Flash", emptyList(), isDefault = true)))
    private val models = listOf(auto, composer, gpt, gemini)

    private fun agent(
        accountModel: AccountModel? = null,
        modelId: String? = null,
        modelParams: List<ModelParam> = emptyList(),
        modelDisplayName: String? = null,
        scope: AgentScope = AgentScope.PRIMARY,
        createdAtMillis: Long = 1_000L,
        id: String = "bc-1",
    ) = Agent(
        id = id,
        name = "Chat",
        lifecycle = AgentLifecycle.IDLE,
        runStatus = RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = createdAtMillis,
        updatedAtMillis = createdAtMillis + 1,
        latestRunId = null,
        repoUrl = null,
        startingRef = null,
        modelDisplayName = modelDisplayName,
        modelId = modelId,
        modelParams = modelParams,
        accountModel = accountModel,
        isProject = scope == AgentScope.PROJECT_ROOT,
        parent = if (scope == AgentScope.PROJECT_CHILD) AgentParent("bc-root", AgentParentKind.PROJECT_WORKER) else null,
    )

    // -- an existing chat ----------------------------------------------------------------------------------------

    @Test
    fun `the account's record comes first, its variant the nearest to the record's parameters`() {
        val current = ModelResolution.forChat(agent(accountModel = AccountModel("gpt-5.6", listOf(ModelParam("effort", "low"))), modelId = "composer-2.5", modelParams = fast.params, modelDisplayName = "Composer 2.5"), models)
        assertThat(current.choice).isEqualTo(ModelChoice(gpt, low))
        assertThat(current.label).isEqualTo("GPT-5.6")
        assertThat(current.source).isEqualTo(Source.ACCOUNT)
        assertThat(current.isAssumed).isFalse()
    }

    @Test
    fun `the record's default is the catalogue's Auto row`() {
        val current = ModelResolution.forChat(agent(accountModel = AccountModel(AccountModel.AUTO_ID)), models)
        assertThat(current.choice).isEqualTo(ModelChoice(auto, null))
        assertThat(current.label).isEqualTo("Auto")
        assertThat(current.source).isEqualTo(Source.ACCOUNT)
    }

    @Test
    fun `a record naming an alias lands on the model the alias resolves to`() {
        val current = ModelResolution.forChat(agent(accountModel = AccountModel("composer-latest", listOf(ModelParam("fast", "false")))), models)
        assertThat(current.choice).isEqualTo(ModelChoice(composer, slow))
        assertThat(current.label).isEqualTo("Composer 2.5")
    }

    @Test
    fun `a record the catalogue cannot place still labels the chip with the record's own name, nothing checked`() {
        val current = ModelResolution.forChat(agent(accountModel = AccountModel("claude-9-preview"), modelId = "gpt-5.6", modelDisplayName = "GPT-5.6"), models)
        assertThat(current.choice).isNull()
        assertThat(current.label).isEqualTo("claude-9-preview")
        assertThat(current.source).isEqualTo(Source.ACCOUNT)
        assertThat(current.isAssumed).isFalse()

        // Auto the catalogue does not list is still Auto by name.
        val noAuto = ModelResolution.forChat(agent(accountModel = AccountModel(AccountModel.AUTO_ID)), models - auto)
        assertThat(noAuto.choice).isNull()
        assertThat(noAuto.label).isEqualTo("Auto")
        assertThat(noAuto.isAssumed).isFalse()
    }

    @Test
    fun `without a record what this device sent comes second, by id then by the label older rows kept`() {
        val byId = ModelResolution.forChat(agent(modelId = "composer-2.5", modelParams = slow.params, modelDisplayName = "Composer 2.5"), models)
        assertThat(byId.choice).isEqualTo(ModelChoice(composer, slow))
        assertThat(byId.source).isEqualTo(Source.DEVICE)

        val byLabel = ModelResolution.forChat(agent(modelDisplayName = "GPT-5.6 High"), models)
        assertThat(byLabel.choice).isEqualTo(ModelChoice(gpt, high))
        assertThat(byLabel.label).isEqualTo("GPT-5.6")

        val gone = ModelResolution.forChat(agent(modelId = "claude-legacy-3", modelDisplayName = "Claude Legacy 3 · Fast"), models)
        assertThat(gone.choice).isNull()
        assertThat(gone.label).isEqualTo("Claude Legacy 3")
        assertThat(gone.source).isEqualTo(Source.DEVICE)
    }

    @Test
    fun `with nothing said Auto is assumed, for a row not read yet too`() {
        val assumed = ModelResolution.forChat(agent(), models)
        assertThat(assumed.choice).isNull()
        assertThat(assumed.label).isEqualTo("Auto")
        assertThat(assumed.source).isEqualTo(Source.AUTO)
        assertThat(assumed.isAssumed).isTrue()

        val unread = ModelResolution.forChat(null, models)
        assertThat(unread.label).isEqualTo("Auto")
        assertThat(unread.isAssumed).isTrue()

        // Before the catalogue: the same assumption, and a record's name still shows.
        assertThat(ModelResolution.forChat(agent(), emptyList()).label).isEqualTo("Auto")
        assertThat(ModelResolution.forChat(agent(accountModel = AccountModel("gpt-5.6")), emptyList()).label).isEqualTo("gpt-5.6")
    }

    // -- a new chat ----------------------------------------------------------------------------------------------

    private val remembered = Candidate.Remembered("gpt-5.6", mapOf("effort" to "low"), atMillis = 2_000L)
    private val account = Candidate.Account(AccountModel("composer-2.5", listOf(ModelParam("fast", "false"))), atMillis = 3_000L)

    @Test
    fun `the newer of the device's choice and the account's newest chat wins, whichever it is`() {
        val accountNewer = ModelResolution.forNewChat(models, listOf(remembered, account), settleOnAuto = true)!!
        assertThat(accountNewer.choice).isEqualTo(ModelChoice(composer, slow))
        assertThat(accountNewer.source).isEqualTo(Source.ACCOUNT)

        val deviceNewer = ModelResolution.forNewChat(models, listOf(remembered.copy(atMillis = 4_000L), account), settleOnAuto = true)!!
        assertThat(deviceNewer.choice).isEqualTo(ModelChoice(gpt, low))
        assertThat(deviceNewer.source).isEqualTo(Source.DEVICE)
    }

    @Test
    fun `a remembered choice is matched on its exact parameters, a parameter-less variant included`() {
        val bare = ModelResolution.forNewChat(models, listOf(Candidate.Remembered("gemini-3.8-flash", emptyMap(), 1L)), settleOnAuto = true)!!
        assertThat(bare.choice).isEqualTo(ModelChoice(gemini, gemini.variants.single()))

        // Parameters the catalogue no longer combines that way land on the model's default variant.
        val stale = ModelResolution.forNewChat(models, listOf(Candidate.Remembered("gpt-5.6", mapOf("effort" to "ultra"), 1L)), settleOnAuto = true)!!
        assertThat(stale.choice).isEqualTo(ModelChoice(gpt, high))
    }

    @Test
    fun `a newest candidate the saved list lacks waits for the fresh list, which walks on to the next and then Auto`() {
        val missing = listOf(Candidate.Remembered("claude-9-preview", emptyMap(), atMillis = 9_000L), account)
        assertThat(ModelResolution.forNewChat(models, missing, settleOnAuto = false)).isNull()

        val fresh = ModelResolution.forNewChat(models, missing, settleOnAuto = true)!!
        assertThat(fresh.choice).isEqualTo(ModelChoice(composer, slow))
        assertThat(fresh.source).isEqualTo(Source.ACCOUNT)

        val nothingPlaces = ModelResolution.forNewChat(models, listOf(Candidate.Remembered("claude-9-preview", emptyMap(), 9_000L)), settleOnAuto = true)!!
        assertThat(nothingPlaces.choice).isEqualTo(ModelChoice(auto, null))
        assertThat(nothingPlaces.source).isEqualTo(Source.AUTO)
    }

    @Test
    fun `with no candidate at all the default is Auto, or the first row of a catalogue without one`() {
        val withAuto = ModelResolution.forNewChat(models, emptyList(), settleOnAuto = true)!!
        assertThat(withAuto.choice).isEqualTo(ModelChoice(auto, null))
        assertThat(withAuto.source).isEqualTo(Source.AUTO)
        // The saved list settles on Auto too when nothing at all was ever chosen: there is nothing to wait for.
        assertThat(ModelResolution.forNewChat(models, emptyList(), settleOnAuto = false)?.choice).isEqualTo(ModelChoice(auto, null))

        val noAutoRow = ModelResolution.forNewChat(listOf(composer, gpt), emptyList(), settleOnAuto = true)!!
        assertThat(noAutoRow.choice).isEqualTo(ModelChoice(composer, fast))
        assertThat(noAutoRow.source).isEqualTo(Source.AUTO)

        assertThat(ModelResolution.forNewChat(emptyList(), listOf(remembered), settleOnAuto = true)).isNull()
    }

    @Test
    fun `the account's newest chat is the newest of the account's own with a record, dated by its start`() {
        val agents = listOf(
            agent(id = "old", accountModel = AccountModel("gpt-5.6"), createdAtMillis = 1_000L),
            agent(id = "new", accountModel = AccountModel("composer-2.5"), createdAtMillis = 5_000L),
            agent(id = "worker", accountModel = AccountModel("gemini-3.8-flash"), createdAtMillis = 9_000L, scope = AgentScope.PROJECT_CHILD),
            agent(id = "root", accountModel = AccountModel("gemini-3.8-flash"), createdAtMillis = 8_000L, scope = AgentScope.PROJECT_ROOT),
            agent(id = "stand-in", accountModel = AccountModel("gemini-3.8-flash"), createdAtMillis = 0L),
            agent(id = "no-record", createdAtMillis = 7_000L),
        )
        assertThat(ModelResolution.newestAccountModel(agents)).isEqualTo(Candidate.Account(AccountModel("composer-2.5"), 5_000L))
        assertThat(ModelResolution.newestAccountModel(listOf(agent(id = "no-record", createdAtMillis = 7_000L)))).isNull()
        assertThat(ModelResolution.newestAccountModel(emptyList())).isNull()
    }
}
