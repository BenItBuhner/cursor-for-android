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
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.SignInMethod
import com.cursorforandroid.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.IOException

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
    private val store: DataStore<Preferences> = settingsStore(context),
) {

    /**
     * What every flow below reads. A settings file that cannot be read even after being replaced degrades to
     * defaults rather than throwing into whatever is collecting — a Compose screen, or the session restore the
     * splash screen waits on.
     */
    private val data: Flow<Preferences> = store.data.catch { t ->
        if (t !is IOException) throw t
        Log.w(TAG, "Settings could not be read; using defaults", t)
        emit(emptyPreferences())
    }

    /**
     * A write that cannot reach the disk (a full disk, an unreadable file) loses the value, not the app. False when
     * it was lost, so the callers whose value is the user's own action or the account's cleanup can say so.
     */
    private suspend fun edit(transform: (MutablePreferences) -> Unit): Boolean =
        runCatching { store.edit(transform); true }.getOrElse { t ->
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
        val demoMode = booleanPreferencesKey("demo_mode")
        val cachedUser = stringPreferencesKey("cached_user")
        val signInMethod = stringPreferencesKey("sign_in_method")
        val apiKeyExpiresAt = longPreferencesKey("api_key_expires_at")
        val lastRepo = stringPreferencesKey("last_repo")
        val lastRef = stringPreferencesKey("last_ref")
        val lastModel = stringPreferencesKey("last_model")
        val lastModelParams = stringPreferencesKey("last_model_params")
        val lastEnvType = stringPreferencesKey("last_env_type")
        val lastEnvName = stringPreferencesKey("last_env_name")
        val autoCreatePr = booleanPreferencesKey("auto_create_pr")
        val liveNotifications = booleanPreferencesKey("live_notifications")
        val notificationPermissionAsked = booleanPreferencesKey("notification_permission_asked")
        val recentSkills = stringPreferencesKey("recent_skills")
        val autoUpdate = booleanPreferencesKey("auto_update")
        val includePreReleases = booleanPreferencesKey("update_include_pre_releases")
        val updateLastCheckedAt = longPreferencesKey("update_last_checked_at")
        val pendingUpdateVersionCode = intPreferencesKey("update_pending_version_code")
        val notifiedUpdateVersionCode = intPreferencesKey("update_notified_version_code")
        val pinSync = booleanPreferencesKey("pin_sync")
        val pinsMigrated = booleanPreferencesKey("pins_migrated")
        val pendingPins = stringPreferencesKey("pending_pin_changes")
        val pinnedModels = stringPreferencesKey("pinned_model_ids")
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

    suspend fun setUpdateLastCheckedAt(epochMillis: Long) = edit { it[Keys.updateLastCheckedAt] = epochMillis }

    suspend fun setPendingUpdateVersionCode(versionCode: Int?) = edit { p ->
        if (versionCode == null) p.remove(Keys.pendingUpdateVersionCode) else p[Keys.pendingUpdateVersionCode] = versionCode
    }

    suspend fun setNotifiedUpdateVersionCode(versionCode: Int?) = edit { p ->
        if (versionCode == null) p.remove(Keys.notifiedUpdateVersionCode) else p[Keys.notifiedUpdateVersionCode] = versionCode
    }

    /** Pins follow the Cursor account (the desktop Agents window and the iOS app) instead of staying on this device. On by default. */
    val pinSyncEnabled: Flow<Boolean> = data.map { it[Keys.pinSync] ?: true }

    suspend fun setPinSyncEnabled(enabled: Boolean) = edit { it[Keys.pinSync] = enabled }

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
    val pinnedModelIds: Flow<List<String>> = store.data.map { p ->
        p[Keys.pinnedModels]?.let { runCatching { CursorJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() } ?: emptyList()
    }

    /** Pins [modelId] to the front of the list, or drops it when it is already pinned. */
    suspend fun togglePinnedModel(modelId: String) = store.edit { p ->
        val current = p[Keys.pinnedModels]?.let { runCatching { CursorJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() } ?: emptyList()
        val next = if (modelId in current) current - modelId else listOf(modelId) + current
        if (next.isEmpty()) p.remove(Keys.pinnedModels) else p[Keys.pinnedModels] = CursorJson.encodeToString(ListSerializer(String.serializer()), next)
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
        )
    }

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

    suspend fun markLaunchedHere(agentId: String) = edit { p ->
        p[Keys.launchedHere] = (p[Keys.launchedHere] ?: emptySet()) + agentId
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

    /** [modelId] null records an explicit "Default" choice (stored as an empty id), which restores as no model. */
    suspend fun setComposerDefaults(
        repoUrl: String?,
        ref: String?,
        modelId: String?,
        params: Map<String, String>,
        autoCreatePr: Boolean,
        env: DeviceTarget = DeviceTarget.Cloud,
    ) =
        edit { p ->
            if (repoUrl == null) p.remove(Keys.lastRepo) else p[Keys.lastRepo] = repoUrl
            if (ref == null) p.remove(Keys.lastRef) else p[Keys.lastRef] = ref
            p[Keys.lastModel] = modelId ?: ""
            p[Keys.lastModelParams] = encodeStringMap(params)
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

    private fun decodePendingPins(raw: String): Map<String, Boolean> =
        runCatching { CursorJson.decodeFromString(MapSerializer(String.serializer(), Boolean.serializer()), raw) }.getOrDefault(emptyMap())

    private fun encodePendingPins(map: Map<String, Boolean>): String =
        CursorJson.encodeToString(MapSerializer(String.serializer(), Boolean.serializer()), map)

    private fun decodeStringMap(raw: String): Map<String, String> =
        runCatching { CursorJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw) }.getOrDefault(emptyMap())

    private fun encodeStringMap(map: Map<String, String>): String =
        CursorJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), map)

    private companion object {
        const val MAX_RECENT_SKILLS = 8
        const val TAG = "PreferencesStore"

        fun storedDevice(typeName: String?, name: String?): DeviceTarget {
            val type = EnvType.entries.firstOrNull { it.name == typeName } ?: EnvType.CLOUD
            return DeviceTarget.of(if (type == EnvType.UNKNOWN) EnvType.CLOUD else type, name)
        }
    }
}

private fun settingsStore(context: Context): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    // A file left half-written by a kill (or a bad OEM restore) would otherwise throw on every read for the rest of
    // the install's life; everything here is device-local and re-derivable, so it starts over instead.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    produceFile = { context.applicationContext.preferencesDataStoreFile("cursor_settings") },
)
