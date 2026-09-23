package com.cursorforandroid.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.domain.CredentialInfo
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.domain.ProjectNotificationPrefs
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.SignInMethod
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class CachedUser(
    val apiKeyName: String,
    val email: String?,
    val firstName: String?,
    val lastName: String?,
    val userId: Long?,
    val profilePictureUrl: String? = null,
)

/** Everything that is device-local: theme, list customization, pins, read markers and composer defaults. */
class PreferencesStore(
    context: Context,
    /** Injectable for tests only: a store whose writes fail, so a lost write can be told from one that landed. */
    store: DataStore<Preferences>? = null,
) {

    /**
     * The settings file this process holds: one per file, shared by every [PreferencesStore] built on it (see
     * [SettingsFile]). A second `DataStore` on the same file is what DataStore forbids — it throws on its first
     * read or write, and whatever was in flight (a chat's read marker on open, then its load) dies with it.
     */
    private val file: SettingsFile = store?.let { SettingsFile(it) } ?: SettingsFile.of(context)

    /**
     * What every flow below reads: the store's flow, with this process's own writes preferred (see
     * [SettingsFile.written]). A settings file that cannot be read even after being replaced degrades to defaults
     * rather than throwing into whatever is collecting — a Compose screen, or the session restore the splash screen
     * waits on.
     */
    private val data: Flow<Preferences> = combine(
        file.store.data.catch { t ->
            if (t !is IOException) throw t
            Log.w(TAG, "Settings could not be read; using defaults", t)
            emit(emptyPreferences())
        },
        file.written,
    ) { fromStore, mine -> mine ?: fromStore }.distinctUntilChanged()

    /**
     * A write that cannot reach the disk (a full disk, an unreadable file) loses the value, not the app. False when
     * it was lost, so the callers whose value is the user's own action or the account's cleanup can say so. What a
     * write produced is recorded for the flows (see [SettingsFile.written]).
     */
    private suspend fun edit(transform: (MutablePreferences) -> Unit): Boolean =
        runCatching {
            // The lock is taken and given back on the store's own threads, never held across a hop back to the
            // caller's. A caller on the main thread would otherwise hold it from the store's answer until the main
            // looper got round to resuming it — and while the looper is busy (a frame, a test idling it its own
            // way) every other writer, on any thread, waits behind a lock nobody is using.
            withContext(Dispatchers.IO) { file.writes.withLock { file.written.value = file.store.edit(transform) } }
            true
        }.getOrElse { t ->
            if (t !is IOException) throw t
            Log.w(TAG, "Settings could not be written", t)
            false
        }

    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val oledBlack = booleanPreferencesKey("oled_black")
        val listPrefs = stringPreferencesKey("list_prefs")
        val pinned = stringSetPreferencesKey("pinned_ids")
        val readMarkers = stringPreferencesKey("read_markers")
        val launchedHere = stringSetPreferencesKey("launched_here_ids")
        /** The chats this phone has started or opened, least recently touched first (a JSON list; see [markTouchedHere]). */
        val touchedHere = stringPreferencesKey("touched_here_ids")
        /** Settings › "Unread only for chats from this phone"; absent is on. */
        val unreadOnlyTouchedHere = booleanPreferencesKey("unread_only_touched_here")
        val snoozedUntil = stringPreferencesKey("snoozed_until")
        val snoozedAt = stringPreferencesKey("snoozed_at")
        val demoMode = booleanPreferencesKey("demo_mode")
        val cachedUser = stringPreferencesKey("cached_user")
        val signInMethod = stringPreferencesKey("sign_in_method")
        val apiKeyExpiresAt = longPreferencesKey("api_key_expires_at")
        val lastRepo = stringPreferencesKey("last_repo")
        /** The repository last launched on Cloud; a machine's repository is the machine's, and the composer comes back to this one. */
        val lastCloudRepo = stringPreferencesKey("last_cloud_repo")
        val lastRef = stringPreferencesKey("last_ref")
        val lastModel = stringPreferencesKey("last_model")
        val lastModelParams = stringPreferencesKey("last_model_params")
        /** When [lastModel] was last written, so a pick made here can be dated against the account's newest chat. */
        val lastModelAt = longPreferencesKey("last_model_at")
        val lastEnvType = stringPreferencesKey("last_env_type")
        val lastEnvName = stringPreferencesKey("last_env_name")
        val autoCreatePr = booleanPreferencesKey("auto_create_pr")
        val liveNotifications = booleanPreferencesKey("live_notifications")
        val countProjectAgentsInLive = booleanPreferencesKey("live_count_project_agents")
        val notifyProjectCoordinators = booleanPreferencesKey("notify_project_coordinators")
        val notifyProjectMembers = booleanPreferencesKey("notify_project_members")
        val notificationPermissionAsked = booleanPreferencesKey("notification_permission_asked")
        val recentSkills = stringPreferencesKey("recent_skills")
        val autoUpdate = booleanPreferencesKey("auto_update")
        val includePreReleases = booleanPreferencesKey("update_include_pre_releases")
        val updateLastCheckedAt = longPreferencesKey("update_last_checked_at")
        val pendingUpdateVersionCode = intPreferencesKey("update_pending_version_code")
        val notifiedUpdateVersionCode = intPreferencesKey("update_notified_version_code")
        /** The versionName whose What's new page has been opened on this device. */
        val whatsNewReadVersion = stringPreferencesKey("whats_new_read_version")
        val pinsMigrated = booleanPreferencesKey("pins_migrated")
        val pendingPins = stringPreferencesKey("pending_pin_changes")
        val pinnedModels = stringPreferencesKey("pinned_model_ids")
        val extendedMode = booleanPreferencesKey("extended_mode")
        val extendedModeAcknowledgedAt = longPreferencesKey("extended_mode_acknowledged_at")
        val extendedModeIntroduced = booleanPreferencesKey("extended_mode_introduced")
        val extendedModeNoticePending = booleanPreferencesKey("extended_mode_notice_pending")
        /** Which engine renders transcripts in Extended mode (`stable` / `beta`, see `domain/TranscriptEngine.kt`); absent is Stable. */
        val transcriptEngine = stringPreferencesKey("transcript_engine")
        val crashReports = booleanPreferencesKey("crash_reports")
        val modeChoicePending = booleanPreferencesKey("mode_choice_pending")
        /** The sidebar groups the reader has folded closed, by section key ("projects", "pinned", "date:Today", …). */
        val collapsedSidebarSections = stringSetPreferencesKey("sidebar_collapsed_sections")
        /** Settings › Appearance › Shorten long Projects list; absent reads as on (see [shortenSidebarLists]). */
        val shortenSidebarLists = booleanPreferencesKey("sidebar_shorten_long_lists")
        /** Settings › New chat page: what the New Chat pane lists under its composer (`recent` / `projects`); absent is Recent. */
        val newChatHome = stringPreferencesKey("new_chat_home")
        /** The transcript notices closed over each chat's composer: `agentId -> identities` (see `LoadNotice.identity`). */
        val dismissedNotices = stringPreferencesKey("dismissed_notices")
        /** Settings › Confirm before stopping; absent reads as on (see [confirmStop]). */
        val confirmStop = booleanPreferencesKey("confirm_stop")
    }

    /** What [clearSession] removes: everything here belongs to the account rather than to the device. */
    private val sessionKeys = listOf(
        Keys.demoMode,
        Keys.cachedUser,
        Keys.signInMethod,
        Keys.apiKeyExpiresAt,
        Keys.pinsMigrated,
        Keys.pendingPins,
        Keys.pinned,
        Keys.readMarkers,
        Keys.launchedHere,
        Keys.touchedHere,
        Keys.modeChoicePending,
        Keys.dismissedNotices,
    )

    /**
     * Set when [clearSession]'s transaction could not be written. The values it should have removed are then masked
     * for the rest of this process — otherwise the flows below would go on serving the signed-out account's pins,
     * read markers and identity from the file — and the removal is retried by the next sign-in and by the session
     * restore of the next start, so a failed sign-out cleanup is neither re-exposed nor permanent.
     */
    @Volatile private var sessionClearFailed = false

    /** [data] with a [clearSession] that could not reach the disk applied in memory. */
    private val accountData: Flow<Preferences> = data.map { p ->
        if (!sessionClearFailed) p else p.toMutablePreferences().apply { sessionKeys.forEach { this -= it } }
    }

    // ---- app updates (device-level; deliberately untouched by clearSession) --------------------------------------

    /** Check GitHub for new releases in the background, download them on Wi-Fi and install when the app is idle. On by default. */
    val autoUpdate: Flow<Boolean> = data.map { it[Keys.autoUpdate] ?: true }

    /** Whether `-rc.N` / `-beta.N` releases are offered; null until the user decides (the installed channel then applies). */
    val includePreReleases: Flow<Boolean?> = data.map { it[Keys.includePreReleases] }

    val updateLastCheckedAt: Flow<Long?> = data.map { it[Keys.updateLastCheckedAt] }

    /** versionCode of the update whose install session was committed; equal to the running build once it succeeded. */
    val pendingUpdateVersionCode: Flow<Int?> = data.map { it[Keys.pendingUpdateVersionCode] }

    /** versionCode the "ready to install" notification was last shown for, so it is posted once per release. */
    val notifiedUpdateVersionCode: Flow<Int?> = data.map { it[Keys.notifiedUpdateVersionCode] }

    suspend fun setAutoUpdate(enabled: Boolean) = edit { it[Keys.autoUpdate] = enabled }

    suspend fun setIncludePreReleases(include: Boolean) = edit { it[Keys.includePreReleases] = include }

    // ---- crash reports (device-level; a consent, so it outlives the account and is never assumed) ----------------

    /** Send anonymous crash reports (crash/CrashReporting.kt). Off until the user turns it on; nothing is sent before. */
    val crashReports: Flow<Boolean> = data.map { it[Keys.crashReports] ?: false }

    suspend fun setCrashReports(enabled: Boolean) = edit { it[Keys.crashReports] = enabled }

    suspend fun setUpdateLastCheckedAt(epochMillis: Long) = edit { it[Keys.updateLastCheckedAt] = epochMillis }

    suspend fun setPendingUpdateVersionCode(versionCode: Int?) = edit { p ->
        if (versionCode == null) p.remove(Keys.pendingUpdateVersionCode) else p[Keys.pendingUpdateVersionCode] = versionCode
    }

    suspend fun setNotifiedUpdateVersionCode(versionCode: Int?) = edit { p ->
        if (versionCode == null) p.remove(Keys.notifiedUpdateVersionCode) else p[Keys.notifiedUpdateVersionCode] = versionCode
    }

    /**
     * The version whose What's new page has been opened on this device (`0.3.37`), or null when none has. One value,
     * not a set: the installed version only ever moves on, and the surfaces that lead to the page show while the
     * installed version is not this one — so they come back, by themselves, with the next release installed.
     */
    val whatsNewReadVersion: Flow<String?> = data.map { it[Keys.whatsNewReadVersion] }

    suspend fun setWhatsNewReadVersion(versionName: String) = edit { it[Keys.whatsNewReadVersion] = versionName }

    // ---- chats (device-level; deliberately untouched by clearSession) --------------------------------------------

    /**
     * Whether a tap that would stop, pause or interrupt a running agent asks first (see `RunStopConfirmation`). On by
     * default, and on for every install that predates the setting: only the user turning it off here writes it off.
     */
    val confirmStop: Flow<Boolean> = data.map { it[Keys.confirmStop] ?: true }

    suspend fun setConfirmStop(enabled: Boolean) = edit { it[Keys.confirmStop] = enabled }

    // ---- Extended mode (device-level; deliberately untouched by clearSession) ------------------------------------

    /**
     * Whether the app may call Cursor's undocumented `api2` endpoints (see `domain/Capabilities.kt`). Off by default,
     * and off for every install that predates the setting: it is never inferred from what the app used to do.
     */
    val extendedMode: Flow<Boolean> = data.map { it[Keys.extendedMode] ?: false }

    /** When the user acknowledged what Extended mode involves (epoch millis); null until they have, which is what keeps it off. */
    val extendedModeAcknowledgedAt: Flow<Long?> = data.map { it[Keys.extendedModeAcknowledgedAt] }

    /** True once a build with the Extended mode setting has run on this install, so the one-time upgrade steps run once. */
    val extendedModeIntroduced: Flow<Boolean> = data.map { it[Keys.extendedModeIntroduced] ?: false }

    /** True while the notice about features that now need Extended mode has yet to be shown to an upgraded install. */
    val extendedModeNoticePending: Flow<Boolean> = data.map { it[Keys.extendedModeNoticePending] ?: false }

    /**
     * The transcript engine Extended mode renders with (see `TranscriptEngine`): Stable unless Beta was chosen here —
     * for every install, upgrades included; it is never inferred from what an earlier build did.
     */
    val transcriptEngine: Flow<TranscriptEngine> = data.map { TranscriptEngine.parse(it[Keys.transcriptEngine]) }

    suspend fun setTranscriptEngine(engine: TranscriptEngine) = edit { it[Keys.transcriptEngine] = engine.key }

    suspend fun setExtendedMode(enabled: Boolean) = edit { it[Keys.extendedMode] = enabled }

    suspend fun setExtendedModeAcknowledgedAt(epochMillis: Long) = edit { it[Keys.extendedModeAcknowledgedAt] = epochMillis }

    /** Records that the setting exists on this install now, and whether the upgrade notice is owed. One transaction. */
    suspend fun setExtendedModeIntroduced(noticePending: Boolean) = edit { p ->
        p[Keys.extendedModeIntroduced] = true
        if (noticePending) p[Keys.extendedModeNoticePending] = true else p.remove(Keys.extendedModeNoticePending)
    }

    suspend fun setExtendedModeNoticePending(pending: Boolean) = edit { p ->
        if (pending) p[Keys.extendedModeNoticePending] = true else p.remove(Keys.extendedModeNoticePending)
    }

    // ---- first run (account-level: owed by a sign-in, settled by the choice screen, gone with the account) ------

    /**
     * True while the account signed in on this device has yet to choose between SDK only and Extended mode (see
     * `data/repo/Onboarding.kt`). Set by a sign-in through the sign-in screen and never by a restored session, so an
     * install that already had an account when the choice arrived is not asked; cleared with the rest of the account.
     */
    val modeChoicePending: Flow<Boolean> = accountData.map { it[Keys.modeChoicePending] ?: false }

    suspend fun setModeChoicePending(pending: Boolean): Boolean {
        beginSession()
        return edit { p -> if (pending) p[Keys.modeChoicePending] = true else p.remove(Keys.modeChoicePending) }
    }

    /**
     * Makes the pins this device's own, for when the account can no longer be asked about them: the changes still
     * waiting for the server are folded into the pinned set (they were applied to it when tapped; this only settles
     * a set the server's answer may have written over since) and forgotten, and the account's first sync is owed
     * again, so pins made here while the account was out of reach are pushed up should it come back. One transaction.
     */
    suspend fun settlePinsLocally() = edit { p ->
        val pending = p[Keys.pendingPins]?.let(::decodePendingPins) ?: emptyMap()
        if (pending.isNotEmpty()) {
            val current = p[Keys.pinned] ?: emptySet()
            p[Keys.pinned] = (current + pending.filterValues { it }.keys) - pending.filterValues { !it }.keys
        }
        p.remove(Keys.pendingPins)
        p.remove(Keys.pinsMigrated)
    }

    /** True once this account's first sync has pushed the pins that were made on this device before syncing existed. */
    val pinsMigrated: Flow<Boolean> = accountData.map { it[Keys.pinsMigrated] ?: false }

    suspend fun setPinsMigrated(migrated: Boolean) = edit { it[Keys.pinsMigrated] = migrated }

    /** agentId -> pinned, for pin changes made here that the server has not acknowledged yet (offline, or a failed call). */
    val pendingPinChanges: Flow<Map<String, Boolean>> = accountData.map { p -> p[Keys.pendingPins]?.let(::decodePendingPins) ?: emptyMap() }

    /** Records [pinned] as awaiting the server, or forgets the entry when [pinned] is null. */
    suspend fun setPendingPinChange(agentId: String, pinned: Boolean?) = edit { p ->
        val current = p[Keys.pendingPins]?.let(::decodePendingPins) ?: emptyMap()
        val next = if (pinned == null) current - agentId else current + (agentId to pinned)
        if (next.isEmpty()) p.remove(Keys.pendingPins) else p[Keys.pendingPins] = encodePendingPins(next)
    }

    /**
     * Forgets the pending entries the server has just acknowledged, but only where the recorded wish is still the
     * one that was sent: a change made while the call was in flight is a newer wish and stays for the next round.
     */
    suspend fun clearAcknowledgedPinChanges(acknowledged: Map<String, Boolean>) = edit { p ->
        val current = p[Keys.pendingPins]?.let(::decodePendingPins) ?: emptyMap()
        val next = current.filterNot { (id, pinned) -> acknowledged[id] == pinned }
        if (next.isEmpty()) p.remove(Keys.pendingPins) else p[Keys.pendingPins] = encodePendingPins(next)
    }

    /** Replaces the pinned set wholesale, for adopting the account's pins from the server. */
    suspend fun setPinnedIds(agentIds: Set<String>) = edit { it[Keys.pinned] = agentIds }

    /** Model ids the user pinned in the picker, most recently pinned first, so they stay at the top of the list. */
    val pinnedModelIds: Flow<List<String>> = data.map { p ->
        p[Keys.pinnedModels]?.let { runCatching { CursorJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() } ?: emptyList()
    }

    /** Pins [modelId] to the front of the list, or drops it when it is already pinned. */
    suspend fun togglePinnedModel(modelId: String) {
        edit { p ->
            val current = p[Keys.pinnedModels]?.let { runCatching { CursorJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() } ?: emptyList()
            val next = if (modelId in current) current - modelId else listOf(modelId) + current
            if (next.isEmpty()) p.remove(Keys.pinnedModels) else p[Keys.pinnedModels] = CursorJson.encodeToString(ListSerializer(String.serializer()), next)
        }
    }

    /**
     * The sidebar's groups the reader has folded closed, by section key. A device preference like the theme: it
     * survives restarts and sign-outs alike, since which groups sit closed is about how this reader reads the list,
     * not about the account.
     */
    val collapsedSidebarSections: Flow<Set<String>> = data.map { it[Keys.collapsedSidebarSections] ?: emptySet() }

    /** Folds the sidebar group [sectionKey] closed, or opens it again; idempotent, so a repeated tap settles rather than flips. */
    suspend fun setSidebarSectionCollapsed(sectionKey: String, collapsed: Boolean) = edit { p ->
        val current = p[Keys.collapsedSidebarSections] ?: emptySet()
        val next = if (collapsed) current + sectionKey else current - sectionKey
        if (next.isEmpty()) p.remove(Keys.collapsedSidebarSections) else p[Keys.collapsedSidebarSections] = next
    }

    /**
     * Whether a long Projects or Pinned group lists only its first five rows until "Show N more" is tapped. On by
     * default; a device preference like the folds, kept across sign-outs. Which rows are listed in full is never kept.
     */
    val shortenSidebarLists: Flow<Boolean> = data.map { it[Keys.shortenSidebarLists] ?: true }

    suspend fun setShortenSidebarLists(enabled: Boolean) = edit { it[Keys.shortenSidebarLists] = enabled }

    /**
     * Settings › New chat page: the recent chats under the New Chat composer, or the Projects (see [NewChatHome]).
     * Recent until changed; a device preference, kept across sign-outs like the sidebar's folds.
     */
    val newChatHome: Flow<NewChatHome> = data.map { NewChatHome.parse(it[Keys.newChatHome]) }.distinctUntilChanged()

    suspend fun setNewChatHome(home: NewChatHome) = edit { it[Keys.newChatHome] = home.key }

    /**
     * The notices about a transcript's load the reader has closed, by chat (`agentId -> identities`, see
     * `LoadNotice.identity`): what the dock over that chat's composer leaves out until the notice's words change or
     * its condition clears and comes back (see `NoticeDismissals`). The account's, like its pins and read markers —
     * a closed notice is about the account's chat — so a sign-out takes them with it.
     */
    val dismissedNotices: Flow<Map<String, Set<String>>> = accountData.map { p ->
        p[Keys.dismissedNotices]?.let(::decodeDismissedNotices)?.mapValues { it.value.toSet() } ?: emptyMap()
    }

    /**
     * Records [identity] as closed over [agentId] — or, with [dismissed] false, forgets it — in one transaction
     * against what is stored, so closing two notices in quick succession keeps both. Bounded: a chat keeps its newest
     * [MAX_DISMISSED_NOTICES_PER_CHAT], and only the [MAX_DISMISSED_NOTICE_CHATS] chats most recently written keep
     * any, so a notice whose words change on every read cannot grow the file.
     */
    suspend fun setNoticeDismissed(agentId: String, identity: String, dismissed: Boolean) = edit { p ->
        val current = p[Keys.dismissedNotices]?.let(::decodeDismissedNotices) ?: emptyMap()
        val forChat = current[agentId] ?: emptyList()
        val nextForChat = if (dismissed) (forChat.filterNot { it == identity } + identity).takeLast(MAX_DISMISSED_NOTICES_PER_CHAT) else forChat.filterNot { it == identity }
        // The chat written last goes last, so the oldest chats are the ones the cap drops.
        val next = LinkedHashMap(current - agentId)
        if (nextForChat.isNotEmpty()) next[agentId] = nextForChat
        val bounded = if (next.size > MAX_DISMISSED_NOTICE_CHATS) next.entries.toList().takeLast(MAX_DISMISSED_NOTICE_CHATS).associate { it.key to it.value } else next
        if (bounded.isEmpty()) p.remove(Keys.dismissedNotices) else p[Keys.dismissedNotices] = encodeDismissedNotices(bounded)
    }

    /** Project / synced skill names the user typed into the "+" menu, most recent first, so they stay one tap away. */
    val recentSkills: Flow<List<String>> = data.map { p ->
        p[Keys.recentSkills]?.let { runCatching { CursorJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() } ?: emptyList()
    }

    suspend fun rememberSkill(name: String) = edit { p ->
        val current = p[Keys.recentSkills]?.let { runCatching { CursorJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() } ?: emptyList()
        val next = (listOf(name) + current.filterNot { it == name }).take(MAX_RECENT_SKILLS)
        p[Keys.recentSkills] = CursorJson.encodeToString(ListSerializer(String.serializer()), next)
    }

    /** Live notification for running agents (the Android counterpart of iOS Live Activities). On by default. */
    val liveNotifications: Flow<Boolean> = data.map { it[Keys.liveNotifications] ?: true }

    /** True once the POST_NOTIFICATIONS prompt has been shown, so a refusal is not nagged about. */
    val notificationPermissionAsked: Flow<Boolean> = data.map { it[Keys.notificationPermissionAsked] ?: false }

    suspend fun setLiveNotifications(enabled: Boolean) = edit { it[Keys.liveNotifications] = enabled }

    /** The Project half of Settings › Notifications (see [ProjectNotificationPrefs]): the live count and the cards. */
    val projectNotifications: Flow<ProjectNotificationPrefs> = data.map {
        ProjectNotificationPrefs(
            countProjectAgentsInLive = it[Keys.countProjectAgentsInLive] ?: true,
            notifyProjectCoordinators = it[Keys.notifyProjectCoordinators] ?: false,
            notifyProjectMembers = it[Keys.notifyProjectMembers] ?: false,
        )
    }.distinctUntilChanged()

    suspend fun setCountProjectAgentsInLive(enabled: Boolean) = edit { it[Keys.countProjectAgentsInLive] = enabled }
    suspend fun setNotifyProjectCoordinators(enabled: Boolean) = edit { it[Keys.notifyProjectCoordinators] = enabled }
    suspend fun setNotifyProjectMembers(enabled: Boolean) = edit { it[Keys.notifyProjectMembers] = enabled }

    suspend fun setNotificationPermissionAsked() = edit { it[Keys.notificationPermissionAsked] = true }

    val themeMode: Flow<ThemeMode> = data.map { p ->
        p[Keys.theme]?.let { raw -> ThemeMode.entries.firstOrNull { it.name == raw } } ?: ThemeMode.System
    }

    /** True-black surfaces while the resolved theme is dark (Cursor Dark, or Match system at night). Off by default. */
    val oledBlack: Flow<Boolean> = data.map { it[Keys.oledBlack] ?: false }

    val listPreferences: Flow<ListPreferences> = data.map { it.listPreferences() }

    private fun Preferences.listPreferences(): ListPreferences =
        this[Keys.listPrefs]?.let { ListPreferences.decode(CursorJson, it) } ?: ListPreferences()

    val localAgentState: Flow<LocalAgentState> = accountData.map { p ->
        LocalAgentState(
            pinnedIds = p[Keys.pinned] ?: emptySet(),
            readMarkers = p[Keys.readMarkers]?.let { decodeMarkers(it) } ?: emptyMap(),
            launchedHereIds = p[Keys.launchedHere] ?: emptySet(),
            snoozedUntil = p[Keys.snoozedUntil]?.let { decodeMarkers(it) } ?: emptyMap(),
            snoozedAt = p[Keys.snoozedAt]?.let { decodeMarkers(it) } ?: emptyMap(),
            touchedHereIds = p.touchedHere().toSet(),
            // The demo's backend runs on this phone: every chat in it is this phone's own.
            unreadOnlyTouchedHere = (p[Keys.unreadOnlyTouchedHere] ?: true) && p[Keys.demoMode] != true,
        )
    }

    /**
     * Settings › "Unread only for chats from this phone" (see `LocalAgentState.unreadOnlyTouchedHere`). On by default;
     * the device's, like the theme: it is about what this phone shows, so a sign-out leaves it as it was.
     */
    val unreadOnlyTouchedHere: Flow<Boolean> = data.map { it[Keys.unreadOnlyTouchedHere] ?: true }

    suspend fun setUnreadOnlyTouchedHere(enabled: Boolean) = edit { it[Keys.unreadOnlyTouchedHere] = enabled }

    val demoMode: Flow<Boolean> = accountData.map { it[Keys.demoMode] ?: false }

    val cachedUser: Flow<CursorUser?> = accountData.map { p ->
        p[Keys.cachedUser]?.let { runCatching { CursorJson.decodeFromString(CachedUser.serializer(), it) }.getOrNull() }
            ?.let { CursorUser(it.apiKeyName, it.email, it.firstName, it.lastName, it.userId, it.profilePictureUrl) }
    }

    /** How the stored key was obtained and when it lapses. A key stored before this was recorded counts as pasted. */
    val credentialInfo: Flow<CredentialInfo?> = accountData.map { p ->
        val method = p[Keys.signInMethod]?.let { raw -> SignInMethod.entries.firstOrNull { it.name == raw } }
        method?.let { CredentialInfo(it, p[Keys.apiKeyExpiresAt]) }
    }

    data class ComposerDefaults(
        val repoUrl: String?,
        /** Branch launched from last time; blank is the repository's default branch, null means no launch yet. */
        val ref: String?,
        /** Model launched with last time; null is an older "no model" choice once [modelChosen] is set. */
        val modelId: String?,
        val modelParams: Map<String, String>,
        val autoCreatePr: Boolean,
        /** False until a launch has recorded a model choice, so a null [modelId] can be told apart from "never asked". */
        val modelChosen: Boolean,
        /** Where the last launch ran; [DeviceTarget.Cloud] until a launch has recorded a device. */
        val env: DeviceTarget,
        /**
         * The repository last launched on Cloud (see [Keys.lastCloudRepo]); null until a launch on Cloud has named
         * one. A machine's or pool's repository is the device's own, so this is what Cloud comes back to.
         */
        val cloudRepoUrl: String? = null,
        /** When the model choice was recorded (a launch or a pick), or 0 for a choice from before this was kept. */
        val modelChosenAtMillis: Long = 0L,
    )

    val composerDefaults: Flow<ComposerDefaults> = data.map { p ->
        ComposerDefaults(
            repoUrl = p[Keys.lastRepo],
            ref = p[Keys.lastRef],
            modelId = p[Keys.lastModel]?.ifEmpty { null },
            modelParams = p[Keys.lastModelParams]?.let { decodeStringMap(it) } ?: emptyMap(),
            autoCreatePr = p[Keys.autoCreatePr] ?: false,
            modelChosen = p.contains(Keys.lastModel),
            env = storedDevice(p[Keys.lastEnvType], p[Keys.lastEnvName]),
            cloudRepoUrl = p[Keys.lastCloudRepo],
            modelChosenAtMillis = p[Keys.lastModelAt] ?: 0L,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.theme] = mode.name }

    suspend fun setOledBlack(enabled: Boolean) = edit { it[Keys.oledBlack] = enabled }

    /**
     * Changes the list preferences in one transaction against what is stored, not against a snapshot a screen holds:
     * two quick taps in the filter menu compose, instead of the second being computed from the state before the first
     * had landed and undoing it.
     */
    suspend fun updateListPreferences(transform: (ListPreferences) -> ListPreferences) = edit { p ->
        p[Keys.listPrefs] = CursorJson.encodeToString(ListPreferences.serializer(), transform(p.listPreferences()))
    }

    suspend fun pinIfNonePinned(agentIds: Collection<String>) = edit { p ->
        if ((p[Keys.pinned] ?: emptySet()).isEmpty()) p[Keys.pinned] = agentIds.toSet()
    }

    /**
     * Flips [agentId]'s pin and, when [recordPending] is set, records what the flip produced as the state the server
     * still owes — both in one transaction, so two quick taps cannot each read the state before the other's flip and
     * leave the device and the account disagreeing. Returns whether the agent is pinned now, or null when the
     * transaction could not be written and nothing at all changed.
     */
    suspend fun togglePinnedAwaitingServer(agentId: String, recordPending: Boolean): Boolean? {
        var pinned = false
        val written = edit { p ->
            val current = p[Keys.pinned] ?: emptySet()
            pinned = agentId !in current
            p[Keys.pinned] = if (pinned) current + agentId else current - agentId
            if (recordPending) {
                val pending = p[Keys.pendingPins]?.let(::decodePendingPins) ?: emptyMap()
                p[Keys.pendingPins] = encodePendingPins(pending + (agentId to pinned))
            }
        }
        return pinned.takeIf { written }
    }

    suspend fun markRead(agentId: String, updatedAtMillis: Long) = edit { p ->
        val markers = p[Keys.readMarkers]?.let { decodeMarkers(it) } ?: emptyMap()
        val existing = markers[agentId] ?: 0L
        if (updatedAtMillis > existing) {
            p[Keys.readMarkers] = encodeMarkers(markers + (agentId to updatedAtMillis))
        }
    }

    /**
     * Marks every id in [markers] read at the given stamp, in one write. A stamp is ignored when an older one
     * is already stored for that id, so two overlapping calls compose instead of regressing a chat that was
     * opened in between.
     */
    suspend fun markAllRead(markers: Map<String, Long>) = edit { p ->
        if (markers.isEmpty()) return@edit
        val current = p[Keys.readMarkers]?.let { decodeMarkers(it) } ?: emptyMap()
        val next = current.toMutableMap()
        var changed = false
        for ((id, updatedAtMillis) in markers) {
            val existing = next[id] ?: 0L
            if (updatedAtMillis > existing) {
                next[id] = updatedAtMillis
                changed = true
            }
        }
        if (changed) p[Keys.readMarkers] = encodeMarkers(next)
    }

    /** [agentId] was started from this install; a chat started here is also touched here (see [markTouchedHere]). */
    suspend fun markLaunchedHere(agentId: String) = edit { p ->
        p[Keys.launchedHere] = (p[Keys.launchedHere] ?: emptySet()) + agentId
        p.touch(agentId)
    }

    /**
     * [agentId] was opened on this phone, or started from it by a path that does not go through the launch (a side
     * chat, a Project). The account's, like the read markers, and bounded: the [MAX_TOUCHED_HERE] chats touched most
     * recently are kept, a touch moving the chat to the newest end.
     */
    suspend fun markTouchedHere(agentId: String) = edit { it.touch(agentId) }

    /**
     * The touched chats, least recently touched first. Until the first touch is written the chats launched here stand
     * for them, so an install that predates the list starts with every chat it ever started.
     */
    private fun Preferences.touchedHere(): List<String> =
        this[Keys.touchedHere]?.let(::decodeIdList) ?: this[Keys.launchedHere].orEmpty().toList()

    private fun MutablePreferences.touch(agentId: String) {
        this[Keys.touchedHere] = encodeIdList((touchedHere().filterNot { it == agentId } + agentId).takeLast(MAX_TOUCHED_HERE))
    }

    /** Silences [agentId] on this device until [untilMillis] (`Long.MAX_VALUE` until they unsnooze). */
    suspend fun snooze(agentId: String, untilMillis: Long, nowMillis: Long = AppClock.now()) = edit { p ->
        val until = p[Keys.snoozedUntil]?.let { decodeMarkers(it) } ?: emptyMap()
        val at = p[Keys.snoozedAt]?.let { decodeMarkers(it) } ?: emptyMap()
        p[Keys.snoozedUntil] = encodeMarkers(until + (agentId to untilMillis))
        p[Keys.snoozedAt] = encodeMarkers(at + (agentId to (at[agentId] ?: nowMillis)))
    }

    /** Drops timed snoozes whose clock has run out so listeners see the lift immediately. */
    suspend fun expireSnoozes(nowMillis: Long = AppClock.now()) = edit { p ->
        val until = p[Keys.snoozedUntil]?.let { decodeMarkers(it) } ?: return@edit
        val live = until.filterValues { it == Long.MAX_VALUE || it > nowMillis }
        if (live.size == until.size) return@edit
        val at = (p[Keys.snoozedAt]?.let { decodeMarkers(it) } ?: emptyMap()).filterKeys { it in live }
        if (live.isEmpty()) {
            p.remove(Keys.snoozedUntil)
            p.remove(Keys.snoozedAt)
        } else {
            p[Keys.snoozedUntil] = encodeMarkers(live)
            if (at.isEmpty()) p.remove(Keys.snoozedAt) else p[Keys.snoozedAt] = encodeMarkers(at)
        }
    }

    suspend fun unsnooze(agentId: String) = edit { p ->
        val until = (p[Keys.snoozedUntil]?.let { decodeMarkers(it) } ?: emptyMap()) - agentId
        val at = (p[Keys.snoozedAt]?.let { decodeMarkers(it) } ?: emptyMap()) - agentId
        if (until.isEmpty()) p.remove(Keys.snoozedUntil) else p[Keys.snoozedUntil] = encodeMarkers(until)
        if (at.isEmpty()) p.remove(Keys.snoozedAt) else p[Keys.snoozedAt] = encodeMarkers(at)
    }

    suspend fun setDemoMode(enabled: Boolean): Boolean {
        beginSession()
        return edit { it[Keys.demoMode] = enabled }
    }

    suspend fun setCachedUser(user: CursorUser?): Boolean {
        beginSession()
        return edit { p ->
            if (user == null) {
                p.remove(Keys.cachedUser)
            } else {
                p[Keys.cachedUser] = CursorJson.encodeToString(
                    CachedUser.serializer(),
                    CachedUser(user.apiKeyName, user.email, user.firstName, user.lastName, user.userId, user.profilePictureUrl),
                )
            }
        }
    }

    suspend fun setCredentialInfo(info: CredentialInfo?): Boolean {
        beginSession()
        return edit { p ->
            if (info == null) {
                p.remove(Keys.signInMethod)
                p.remove(Keys.apiKeyExpiresAt)
            } else {
                p[Keys.signInMethod] = info.method.name
                if (info.expiresAtMs == null) p.remove(Keys.apiKeyExpiresAt) else p[Keys.apiKeyExpiresAt] = info.expiresAtMs
            }
        }
    }

    /**
     * Records the model the new-chat picker should open on, without touching the other launch defaults.
     * [modelId] null records an explicit "Default" choice (stored as an empty id), which restores as no model.
     */
    suspend fun rememberModel(modelId: String?, params: Map<String, String> = emptyMap(), nowMillis: Long = AppClock.now()) = edit { p ->
        p[Keys.lastModel] = modelId ?: ""
        p[Keys.lastModelParams] = encodeStringMap(params)
        p[Keys.lastModelAt] = nowMillis
    }

    /** [modelId] null records an explicit "Default" choice (stored as an empty id), which restores as no model. */
    suspend fun setComposerDefaults(
        repoUrl: String?,
        ref: String?,
        modelId: String?,
        params: Map<String, String>,
        autoCreatePr: Boolean,
        env: DeviceTarget = DeviceTarget.Cloud,
        nowMillis: Long = AppClock.now(),
    ) =
        edit { p ->
            if (repoUrl == null) p.remove(Keys.lastRepo) else p[Keys.lastRepo] = repoUrl
            // A launch on a machine or pool ran in that device's checkout; only a launch on Cloud names the Cloud repository.
            if (env.isCloud) {
                if (repoUrl == null) p.remove(Keys.lastCloudRepo) else p[Keys.lastCloudRepo] = repoUrl
            }
            if (ref == null) p.remove(Keys.lastRef) else p[Keys.lastRef] = ref
            p[Keys.lastModel] = modelId ?: ""
            p[Keys.lastModelParams] = encodeStringMap(params)
            p[Keys.lastModelAt] = nowMillis
            p[Keys.autoCreatePr] = autoCreatePr
            p[Keys.lastEnvType] = env.type.name
            if (env.name == null) p.remove(Keys.lastEnvName) else p[Keys.lastEnvName] = env.name
        }

    /**
     * Removes everything the account owns, so the next one starts clean. False when the transaction could not be
     * written; [sessionClearFailed] then keeps those values out of every flow for the rest of the process and the
     * removal is retried, so a sign-out never leaves the previous account's state to be read again.
     *
     * The next account starts its own pin migration and owes the server nothing of this one's pending changes.
     * Pins, read markers and launched-here markers are the account's, not the device's: with pin sync off nothing
     * would ever rewrite them, so they would outlive the account they belong to. The demo re-seeds its own pins on
     * the way back in.
     */
    suspend fun clearSession(): Boolean {
        // Nothing of an account is on disk, so there is nothing to make durable — and the retry the next start makes
        // for a clear that failed must not rewrite the file just to find that out.
        if (!sessionClearFailed && data.first().let { p -> sessionKeys.none { p.contains(it) } }) return true
        val written = edit { p -> sessionKeys.forEach { p -= it } }
        sessionClearFailed = !written
        return written
    }

    /**
     * An account is being recorded. A cleanup the previous sign-out could not write is retried first: the mask that
     * stood in for it is lifted here, and lifting it over a file that still held those values would show them.
     */
    private suspend fun beginSession() {
        if (!sessionClearFailed) return
        if (edit { p -> sessionKeys.forEach { p -= it } }) sessionClearFailed = false
    }

    private fun decodeMarkers(raw: String): Map<String, Long> =
        runCatching { CursorJson.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), raw) }.getOrDefault(emptyMap())

    private fun encodeMarkers(map: Map<String, Long>): String =
        CursorJson.encodeToString(MapSerializer(String.serializer(), Long.serializer()), map)

    private fun decodeIdList(raw: String): List<String> =
        runCatching { CursorJson.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrDefault(emptyList())

    private fun encodeIdList(ids: List<String>): String = CursorJson.encodeToString(ListSerializer(String.serializer()), ids)

    private fun decodePendingPins(raw: String): Map<String, Boolean> =
        runCatching { CursorJson.decodeFromString(MapSerializer(String.serializer(), Boolean.serializer()), raw) }.getOrDefault(emptyMap())

    private fun encodePendingPins(map: Map<String, Boolean>): String =
        CursorJson.encodeToString(MapSerializer(String.serializer(), Boolean.serializer()), map)

    private fun decodeStringMap(raw: String): Map<String, String> =
        runCatching { CursorJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw) }.getOrDefault(emptyMap())

    private fun encodeStringMap(map: Map<String, String>): String =
        CursorJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), map)

    /** Decoded into a map that keeps the file's order, which is the order the chats were last written in. */
    private fun decodeDismissedNotices(raw: String): Map<String, List<String>> =
        runCatching { CursorJson.decodeFromString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), raw) }.getOrDefault(emptyMap())

    private fun encodeDismissedNotices(map: Map<String, List<String>>): String =
        CursorJson.encodeToString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), map)

    private companion object {
        const val MAX_RECENT_SKILLS = 8
        const val MAX_DISMISSED_NOTICES_PER_CHAT = 8
        const val MAX_DISMISSED_NOTICE_CHATS = 200
        /** Weeks of chats opened on a phone; far fewer than an account starts elsewhere, which are never in it. */
        const val MAX_TOUCHED_HERE = 1_000
        const val TAG = "PreferencesStore"

        fun storedDevice(typeName: String?, name: String?): DeviceTarget {
            val type = EnvType.entries.firstOrNull { it.name == typeName } ?: EnvType.CLOUD
            return DeviceTarget.of(if (type == EnvType.UNKNOWN) EnvType.CLOUD else type, name)
        }
    }
}

/**
 * The settings file as this process holds it: the `DataStore`, and beside it what this process last wrote and the
 * lock the writes take. One per file for the process's lifetime, whichever `PreferencesStore` — the app's graph, a
 * widget's, a test's — is built on it: DataStore allows a single instance per file and throws "There are multiple
 * DataStores active for the same file" at the second one's first use, which is what a screen driven by a graph of
 * its own saw while the application's own graph held the file.
 */
private class SettingsFile(val store: DataStore<Preferences>) {
    /**
     * The settings as this process last wrote them, published beside the store's own flow. DataStore 1.1's `data`
     * can drop the push of an update to a collector whose collection raced the write (b/431787506, fixed upstream
     * only from 1.3.0-alpha03): the collector keeps the value it had while a fresh read returns the new one. Every
     * write goes through `PreferencesStore.edit`, which records the settings it produced, and the flows prefer that
     * record to the store's — so a pin, a read marker or a toggle written by this process reaches every collector,
     * always. Nothing else writes the file: the store is this process's alone.
     */
    val written = MutableStateFlow<Preferences?>(null)

    /**
     * Serialises the writes with the recording of what they produced: the store orders the writes on its own, but two
     * callers finishing out of order would otherwise record an older snapshot over a newer one and hold every flow
     * at the older until the next write.
     */
    val writes = Mutex()

    companion object {
        private val files = ConcurrentHashMap<String, SettingsFile>()


        fun of(context: Context): SettingsFile {
            val path = context.applicationContext.preferencesDataStoreFile("cursor_settings")
            return files.computeIfAbsent(path.absolutePath) {
                SettingsFile(
                    PreferenceDataStoreFactory.create(
                        // A file left half-written by a kill (or a bad OEM restore) would otherwise throw on every read
                        // for the rest of the install's life; everything here is device-local and re-derivable, so it
                        // starts over instead.
                        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                        produceFile = { path },
                    ),
                )
            }
        }
    }
}
