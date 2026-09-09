package com.cursorforandroid.domain

import kotlinx.serialization.Serializable

/**
 * Where a pull request stands, as the Cursor account reports it. The Cloud Agents API only ever names a PR (`prUrl`),
 * so this is read from the account service and remembered on the device; it drives the Git filter and the
 * pull-request pills in the list.
 */
@Serializable
enum class PullRequestState(val label: String) {
    Open("Open"),
    Draft("Draft"),
    Merged("Merged"),
    Closed("Closed"),
}

/**
 * What the device last learned about one pull request. [state] is null when the account would not say — a PR it has
 * no record of, or one that is gone — which is remembered too, so the same doomed request is not repeated on every
 * refresh. [live] tells a state the account read from the SCM for this one PR when asked (`GetPullRequestMergeStatus`)
 * from one lifted off the account's agent list, whose stored status can lag the SCM; a fresh live answer outranks the
 * list until it is due to be re-read.
 */
@Serializable
data class PullRequestStatus(
    val state: PullRequestState?,
    val checkedAtMillis: Long,
    val live: Boolean = false,
)
