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
     */
    data class Current(val choice: ModelChoice?, val label: String, val source: Source) {
        val isAssumed: Boolean get() = source == Source.AUTO
    }

    /**
     * What [agent] runs on, in order: the account's record (`requested_model`, else `model_details`; the desktop's
     * `default` is Auto), what this device recorded at launch or on a switch — by id, else by the label rows kept
     * before ids were — and, with neither, Auto. A record the catalog cannot place still labels the chip with the
     * record's own name; only the choice is left unchecked.
     */
    fun forChat(agent: Agent?, models: List<ModelOption>): Current {
        agent?.accountModel?.let { account ->
            val choice = models.choiceFor(account)
            return Current(choice, choice?.label ?: account.fallbackLabel, Source.ACCOUNT)
        }
        val recorded = agent?.modelId?.let { models.choiceFor(it, agent.modelParams) }
            ?: agent?.modelDisplayName?.let(models::choiceLabelled)
        val recordedLabel = recorded?.label ?: agent?.modelName
        if (recordedLabel != null) return Current(recorded, recordedLabel, Source.DEVICE)
        return Current(null, AccountModel.AUTO_LABEL, Source.AUTO)
    }

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
     * for the model's default one; an account record on its id or aliases, its variant the nearest to the record's
     * parameters (see [choiceFor]).
     */
    private fun List<ModelOption>.resolve(candidate: Candidate): ModelChoice? = when (candidate) {
        is Candidate.Remembered -> named(candidate.modelId)?.let { ModelChoice(it, it.variantWithParams(candidate.params) ?: it.defaultVariant) }
        is Candidate.Account -> choiceFor(candidate.model)
    }
}
