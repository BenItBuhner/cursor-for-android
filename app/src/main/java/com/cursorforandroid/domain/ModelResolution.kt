package com.cursorforandroid.domain

/**
 * Which model a composer opens on, and where the answer came from, for the two composers. Neither ever opens on an
 * empty "Model": what a chat runs on is known from the account's record (Extended mode), from what this device
 * sent, or — failing both — is Auto, the configured default a request without a `model` gets.
 *
 * The documented Cloud Agents API reports no model anywhere (verified against the v1 OpenAPI: `Agent` and
 * `AgentSummary` carry `env`, `repos`, `status` and the run ids and nothing of the model; `GET /v0/agents` and the
 * v0 transcript are text and git state; the run stream's `interaction_update` names a model only inside a `task`
 * tool's arguments; the SDK's `agent.model` is "`undefined` until something sets it" — client memory). The account's
 * `ListBackgroundComposers` record does: `requested_model` and `model_details` (see [AccountModel]).
 */
object ModelResolution {

    /** Where a resolved model came from. */
    enum class Source {
        /** The account's record of the chat — or, for a new chat, of the account's newest chat (Extended mode). */
        ACCOUNT,
        /** What this device sent for the chat, or last launched with or picked for a new chat. */
        DEVICE,
        /** Nothing said: Auto, the configured default a request without a `model` gets. */
        AUTO,
    }

    /**
     * The chat's model for the follow-up composer. [choice] is the catalog's entry when the catalog places the
     * model (shown checked in the picker); [label] always names something for the chip. [source] AUTO is an
     * assumption — the chat may run on anything, and follow-ups keep whatever that is unless a model is picked.
     * [detail] is for a model the catalog cannot place: the parameters its id spells and the id itself, which is what
     * the chat keeps running on.
     */
    data class Current(val choice: ModelChoice?, val label: String, val source: Source, val detail: String? = null) {
        val isAssumed: Boolean get() = source == Source.AUTO
    }

    /**
     * What [agent] runs on, in order: the account's record (`requested_model`, else `model_details`; the desktop's
     * `default` is Auto), what this device recorded at launch or on a switch — by id, else by the label rows kept
     * before ids were — and, with neither, Auto. Every id is read by [ModelSlugs.resolve], so a slug a Project's
     * coordinator or another client wrote (`claude-opus-5-5-max-fast`) lands on the entry and parameters picking it
     * here would give. One the catalog cannot place is named by [ModelSlugs.readableName], never blank, and left
     * unchecked.
     */
    fun forChat(agent: Agent?, models: List<ModelOption>): Current {
        agent?.accountModel?.let { account ->
            val choice = models.choiceFor(account)
            if (choice != null) return Current(choice, choice.label, Source.ACCOUNT)
            val label = ModelSlugs.readableName(models, account.modelId)
            return Current(null, label, Source.ACCOUNT, unplacedDetail(models, account.modelId, label))
        }
        val recorded = agent?.modelId?.let { models.choiceFor(it, agent.modelParams) }
            ?: agent?.modelDisplayName?.let(models::choiceLabelled)
        if (recorded != null) return Current(recorded, recorded.label, Source.DEVICE)
        val id = agent?.modelId
        // A row whose name was never kept is named by its id; one that kept the id as its name reads the same way.
        val recordedLabel = agent?.modelName?.takeIf { it != id } ?: id?.let { ModelSlugs.readableName(models, it) }
        if (recordedLabel != null) return Current(null, recordedLabel, Source.DEVICE, id?.let { unplacedDetail(models, it, recordedLabel) })
        return Current(null, AccountModel.AUTO_LABEL, Source.AUTO)
    }

    /** "Max · Fast · claude-opus-6-max-fast": what an unplaced id spells beyond its name, and the id as it is kept. */
    private fun unplacedDetail(models: List<ModelOption>, id: String, label: String): String? =
        listOfNotNull(ModelSlugs.readableQualifier(models, id), id.trim().takeIf { it.isNotEmpty() && it != label })
            .takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /** One place a new chat's default could come from, dated so the newer word wins. */
    sealed interface Candidate {
        val atMillis: Long
        val source: Source

        /** The model this device last launched with or picked (its preferences, or a draft it is restoring). */
        data class Remembered(val modelId: String, val params: Map<String, String>, override val atMillis: Long) : Candidate {
            override val source: Source get() = Source.DEVICE
        }

        /** The model of the account's newest chat, as its record names it (Extended mode). */
        data class Account(val model: AccountModel, override val atMillis: Long) : Candidate {
            override val source: Source get() = Source.ACCOUNT
        }
    }

    data class Resolved(val choice: ModelChoice, val source: Source)

    /**
     * The model a new chat starts on when nothing was picked on this screen: the newest of [candidates] the catalog
     * places — the choice this device remembers against the account's newest chat's model, whichever is more recent
     * — then Auto (the catalog's Auto row, else its first row).
     *
     * With [settleOnAuto] false the list is a saved copy that may predate the wanted model: a newest candidate the
     * list lacks is left unresolved (null) for the fresh list to restore, rather than stood in for. The fresh list
     * walks every candidate and settles on Auto.
     */
    fun forNewChat(models: List<ModelOption>, candidates: List<Candidate>, settleOnAuto: Boolean): Resolved? {
        if (models.isEmpty()) return null
        for (candidate in candidates.sortedByDescending { it.atMillis }) {
            val choice = models.resolve(candidate)
            if (choice != null) return Resolved(choice, candidate.source)
            if (!settleOnAuto) return null
        }
        if (!settleOnAuto && candidates.isNotEmpty()) return null
        val auto = models.autoOption() ?: models.firstOrNull() ?: return null
        return Resolved(ModelChoice(auto, auto.defaultVariant), Source.AUTO)
    }

    /**
     * The account's newest chat of the account's own that has a recorded model, as a candidate dated by when the
     * chat was started — the moment its model was picked; a running chat's activity does not move it. A Project's
     * workers, side chats and subagents are left out: their models were the coordinator's or the parent's choice.
     */
    fun newestAccountModel(agents: List<Agent>): Candidate.Account? {
        val newest = agents.asSequence()
            .filter { it.scope == AgentScope.PRIMARY && it.accountModel != null && it.createdAtMillis > 0L }
            .maxByOrNull { it.createdAtMillis } ?: return null
        return Candidate.Account(newest.accountModel!!, newest.createdAtMillis)
    }

    /**
     * A remembered choice is matched on its id and its exact parameters — a parameter-less variant is not swapped
     * for the model's default one — and any other spelling of it read by [ModelSlugs.resolve]; an account record
     * through [choiceFor], which reads its id the same way, its variant the nearest to the record's parameters.
     */
    private fun List<ModelOption>.resolve(candidate: Candidate): ModelChoice? = when (candidate) {
        is Candidate.Remembered -> named(candidate.modelId)?.let { ModelChoice(it, it.variantWithParams(candidate.params) ?: it.defaultVariant) }
            ?: ModelSlugs.resolve(this, candidate.modelId, candidate.params.map { (id, value) -> ModelParam(id, value) })
        is Candidate.Account -> choiceFor(candidate.model)
    }
}
