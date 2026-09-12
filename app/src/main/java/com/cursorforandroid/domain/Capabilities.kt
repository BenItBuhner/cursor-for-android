package com.cursorforandroid.domain

/**
 * Which of the app's features may call Cursor's undocumented `api2.cursor.sh` endpoints — the `aiserver.v1` RPCs
 * behind cursor.com and the first-party apps, and the session exchange that feeds them. None of them is part of the
 * documented Cloud Agents API, so all of them follow one setting, Extended mode, which is off by default; each flag
 * names the surface it stands for so a call site says what it needs rather than testing the setting itself.
 *
 * With every flag off the app speaks to `api.cursor.com` (v1 plus the two legacy v0 endpoints), to `cursor.com` /
 * `api2.cursor.sh` only for the browser sign-in Cursor's own SDK and CLI perform, and to GitHub's REST API for what
 * a GitHub-hosted repository can say about itself.
 */
data class Capabilities(
    /** `POST /auth/exchange_user_api_key`: the account session every other flag's call is authenticated with. */
    val accountSession: Boolean,
    /** `DashboardService/GetMe`: the profile picture (and the team name). Off: `/v1/me` and the initials. */
    val accountProfile: Boolean,
    /** `BackgroundComposerService/{List,Pin,Unpin}BackgroundComposers`. Off: pins are kept on this device only. */
    val pinSync: Boolean,
    /** `ArchiveBackgroundComposer` / `RenameBackgroundComposer`. Off: the public archive only, and no rename. */
    val accountLifecycle: Boolean,
    /** `DashboardService/Get{Repo,BackgroundComposer}SlashCommands` and `GetGlobalCommands`. Off: the built-ins plus what the repository itself holds. */
    val accountSlashCommands: Boolean,
    /** `GetPullRequestMergeStatus` and the list's `prStatus`. Off: GitHub's REST API for GitHub-hosted repositories, nothing for the rest. */
    val accountPullRequests: Boolean,
    /**
     * Cursor Projects on the account service: the lineage reads (`ListWorkersForManager`, `ListBackgroundComposerChildren`,
     * the list's workers and subagents), the coordinator's actions (`CreateProjectWorker`, `SetWorkerManager`,
     * `ClearWorkerManager`, `ReparentBackgroundComposer`, `UpdateProjectAppearance`, `StartSideChatBackgroundComposer`)
     * and the Project's shared context (`ListAgentStore*`, `ReadAgentStoreFile`). Off: a Project is told apart only
     * by what its own transcript says, and its view offers no action.
     */
    val projects: Boolean,
    /**
     * Steering a running chat (`InjectBackgroundComposerContext`), holding it (`PauseBackgroundComposer` /
     * `ResumeBackgroundComposer`), stopping one of its tool calls (`CancelBackgroundComposerToolCall`) and waking its
     * machine (`WakeBackgroundComposer`). Off: the public cancel only.
     */
    val steering: Boolean,
    /** The agent's live workspace: `ListWorkspaceFiles` and `ReadBinaryFile`. Off: only the files the stream carried, and the repository's tree. */
    val workspaceFiles: Boolean,
    /** The branch's diff against its base before a pull request exists: `GetBackgroundComposerDiffDetails`. Off: the edits the stream carried. */
    val diffDetails: Boolean,
    /**
     * A pull request on any host Cursor connects, read through the account (`SCMService/GetPullRequest`,
     * `GetPullRequestDiff`, `GetDetailedPullRequestStatus`, `GetPullRequestDiscussions`) and opened from here
     * (`MakePRBackgroundComposer` / `OpenPRBackgroundComposer`). Off: GitHub's REST API for GitHub-hosted repositories, the browser for the rest.
     */
    val scmPullRequests: Boolean,
    /** The agent's VM desktop over noVNC: `GetMachine` for the pod and its ticket. Off: nothing; the machine's own state stays public. */
    val remoteDesktop: Boolean,
    /** Answering an agent's `ask_question` from here (`SubmitInteractionResponseBackgroundComposer`). Off: the question is read-only, answered on cursor.com. */
    val interactions: Boolean,
    /**
     * The account's follow-up queue (`AddAsyncFollowupBackgroundComposer`, `ListPendingFollowups`, `UpdatePendingFollowup`,
     * `DeletePendingFollowup`, `ReorderPendingFollowup`, `SubmitPendingFollowupNow`, `MarkFollowupEditing`): the queue
     * the desktop, the web and the iOS app share. Off: follow-ups sent mid-turn wait on this device and go out when the turn ends.
     */
    val accountQueue: Boolean,
    /** Ask and Debug modes for a follow-up, which only the account's follow-up RPC can carry (`agent.v1.AgentMode`). Off: agent and plan. */
    val agentModes: Boolean,
) {
    /** True when any private surface is on: what the persistent indicator and the default-mode explanations go by. */
    val anyExtended: Boolean
        get() = accountSession || accountProfile || pinSync || accountLifecycle || accountSlashCommands || accountPullRequests || projects || steering ||
            workspaceFiles || diffDetails || scmPullRequests || remoteDesktop || interactions || accountQueue || agentModes

    companion object {
        /** The default: the documented API only. */
        val DOCUMENTED = Capabilities(
            accountSession = false,
            accountProfile = false,
            pinSync = false,
            accountLifecycle = false,
            accountSlashCommands = false,
            accountPullRequests = false,
            projects = false,
            steering = false,
            workspaceFiles = false,
            diffDetails = false,
            scmPullRequests = false,
            remoteDesktop = false,
            interactions = false,
            accountQueue = false,
            agentModes = false,
        )

        /** Extended mode: every private surface, exactly as the app used them before the setting existed. */
        val EXTENDED = Capabilities(
            accountSession = true,
            accountProfile = true,
            pinSync = true,
            accountLifecycle = true,
            accountSlashCommands = true,
            accountPullRequests = true,
            projects = true,
            steering = true,
            workspaceFiles = true,
            diffDetails = true,
            scmPullRequests = true,
            remoteDesktop = true,
            interactions = true,
            accountQueue = true,
            agentModes = true,
        )

        fun of(extendedMode: Boolean): Capabilities = if (extendedMode) EXTENDED else DOCUMENTED
    }
}
