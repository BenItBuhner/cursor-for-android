package com.cursorforandroid.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.domain.CredentialInfo
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.SignInMethod
import com.cursorforandroid.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

@Serializable
private data class CachedUser(
    val apiKeyName: String,
    val email: String?,
    val firstName: String?,
    val lastName: String?,
    val userId: Long?,
)

/** Everything that is device-local: theme, list customization, pins, read markers and composer defaults. */
class PreferencesStore(context: Context) {

    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { context.applicationContext.preferencesDataStoreFile("cursor_settings") },
    )

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
        val autoCreatePr = booleanPreferencesKey("auto_create_pr")
        val liveNotifications = booleanPreferencesKey("live_notifications")
        val notificationPermissionAsked = booleanPreferencesKey("notification_permission_asked")
        val recentSkills = stringPreferencesKey("recent_skills")
        val pinSync = booleanPreferencesKey("pin_sync")
        val pinsMigrated = booleanPreferencesKey("pins_migrated")
        val pendingPins = stringPreferencesKey("pending_pin_changes")
    }

    /** Pins follow the Cursor account (the desktop Agents window and the iOS app) instead of staying on this device. On by default. */
    val pinSyncEnabled: Flow<Boolean> = store.data.map { it[Keys.pinSync] ?: true }

    suspend fun setPinSyncEnabled(enabled: Boolean) = store.edit { it[Keys.pinSync] = enabled }

    /** True once this account's first sync has pushed the pins that were made on this device before syncing existed. */
    val pinsMigrated: Flow<Boolean> = store.data.map { it[Keys.pinsMigrated] ?: false }

    suspend fun setPinsMigrated(migrated: Boolean) = store.edit { it[Keys.pinsMigrated] = migrated }

    /** agentId -> pinned, for pin changes made here that the server has not acknowledged yet (offline, or a failed call). */
    val pendingPinChanges: Flow<Map<String, Boolean>> = store.data.map { p -> p[Keys.pendingPins]?.let(::decodePendingPins) ?: emptyMap() }

    /** Records [pinned] as awaiting the server, or forgets the entry when [pinned] is null. */
    suspend fun setPendingPinChange(agentId: String, pinned: Boolean?) = store.edit { p ->
        val current = p[Keys.pendingPins]?.let(::decodePendingPins) ?: emptyMap()
        val next = if (pinned == null) current - agentId else current + (agentId to pinned)
        if (next.isEmpty()) p.remove(Keys.pendingPins) else p[Keys.pendingPins] = encodePendingPins(next)
    }

    suspend fun clearPendingPinChanges(agentIds: Collection<String>) = store.edit { p ->
        val next = (p[Keys.pendingPins]?.let(::decodePendingPins) ?: emptyMap()) - agentIds
        if (next.isEmpty()) p.remove(Keys.pendingPins) else p[Keys.pendingPins] = encodePendingPins(next)
    }

    /** Replaces the pinned set wholesale, for adopting the account's pins from the server. */
    suspend fun setPinnedIds(agentIds: Set<String>) = store.edit { it[Keys.pinned] = agentIds }

    /** Project / synced skill names the user typed into the "+" menu, most recent first, so they stay one tap away. */
    val recentSkills: Flow<List<String>> = store.data.map { p ->
        p[Keys.recentSkills]?.let { runCatching { CursorJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() } ?: emptyList()
    }

    suspend fun rememberSkill(name: String) = store.edit { p ->
        val current = p[Keys.recentSkills]?.let { runCatching { CursorJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() } ?: emptyList()
        val next = (listOf(name) + current.filterNot { it == name }).take(MAX_RECENT_SKILLS)
        p[Keys.recentSkills] = CursorJson.encodeToString(ListSerializer(String.serializer()), next)
    }

    /** Live notification for running agents (the Android counterpart of iOS Live Activities). On by default. */
    val liveNotifications: Flow<Boolean> = store.data.map { it[Keys.liveNotifications] ?: true }

    /** True once the POST_NOTIFICATIONS prompt has been shown, so a refusal is not nagged about. */
    val notificationPermissionAsked: Flow<Boolean> = store.data.map { it[Keys.notificationPermissionAsked] ?: false }

    suspend fun setLiveNotifications(enabled: Boolean) = store.edit { it[Keys.liveNotifications] = enabled }

    suspend fun setNotificationPermissionAsked() = store.edit { it[Keys.notificationPermissionAsked] = true }

    val themeMode: Flow<ThemeMode> = store.data.map { p ->
        p[Keys.theme]?.let { raw -> ThemeMode.entries.firstOrNull { it.name == raw } } ?: ThemeMode.System
    }

    /** True-black surfaces while the resolved theme is dark (Cursor Dark, or Match system at night). Off by default. */
    val oledBlack: Flow<Boolean> = store.data.map { it[Keys.oledBlack] ?: false }

    val listPreferences: Flow<ListPreferences> = store.data.map { p ->
        p[Keys.listPrefs]?.let { runCatching { CursorJson.decodeFromString(ListPreferences.serializer(), it) }.getOrNull() }
            ?: ListPreferences()
    }

    val localAgentState: Flow<LocalAgentState> = store.data.map { p ->
        LocalAgentState(
            pinnedIds = p[Keys.pinned] ?: emptySet(),
            readMarkers = p[Keys.readMarkers]?.let { decodeMarkers(it) } ?: emptyMap(),
            launchedHereIds = p[Keys.launchedHere] ?: emptySet(),
        )
    }

    val demoMode: Flow<Boolean> = store.data.map { it[Keys.demoMode] ?: false }

    val cachedUser: Flow<CursorUser?> = store.data.map { p ->
        p[Keys.cachedUser]?.let { runCatching { CursorJson.decodeFromString(CachedUser.serializer(), it) }.getOrNull() }
            ?.let { CursorUser(it.apiKeyName, it.email, it.firstName, it.lastName, it.userId) }
    }

    /** How the stored key was obtained and when it lapses. A key stored before this was recorded counts as pasted. */
    val credentialInfo: Flow<CredentialInfo?> = store.data.map { p ->
        val method = p[Keys.signInMethod]?.let { raw -> SignInMethod.entries.firstOrNull { it.name == raw } }
        method?.let { CredentialInfo(it, p[Keys.apiKeyExpiresAt]) }
    }

    data class ComposerDefaults(
        val repoUrl: String?,
        /** Branch launched from last time; blank is the repository's default branch, null means no launch yet. */
        val ref: String?,
        /** Model launched with last time; null is "Default" (Cursor's configured model) once [modelChosen] is set. */
        val modelId: String?,
        val modelParams: Map<String, String>,
        val autoCreatePr: Boolean,
        /** False until a launch has recorded a model choice, so a null [modelId] can be told apart from "never asked". */
        val modelChosen: Boolean,
    )

    val composerDefaults: Flow<ComposerDefaults> = store.data.map { p ->
        ComposerDefaults(
            repoUrl = p[Keys.lastRepo],
            ref = p[Keys.lastRef],
            modelId = p[Keys.lastModel]?.ifEmpty { null },
            modelParams = p[Keys.lastModelParams]?.let { decodeStringMap(it) } ?: emptyMap(),
            autoCreatePr = p[Keys.autoCreatePr] ?: false,
            modelChosen = p.contains(Keys.lastModel),
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[Keys.theme] = mode.name }

    suspend fun setOledBlack(enabled: Boolean) = store.edit { it[Keys.oledBlack] = enabled }

    suspend fun setListPreferences(prefs: ListPreferences) = store.edit {
        it[Keys.listPrefs] = CursorJson.encodeToString(ListPreferences.serializer(), prefs)
    }

    suspend fun pinIfNonePinned(agentIds: Collection<String>) = store.edit { p ->
        if ((p[Keys.pinned] ?: emptySet()).isEmpty()) p[Keys.pinned] = agentIds.toSet()
    }

    suspend fun togglePinned(agentId: String) = store.edit { p ->
        val current = p[Keys.pinned] ?: emptySet()
        p[Keys.pinned] = if (agentId in current) current - agentId else current + agentId
    }

    suspend fun markRead(agentId: String, updatedAtMillis: Long) = store.edit { p ->
        val markers = p[Keys.readMarkers]?.let { decodeMarkers(it) } ?: emptyMap()
        val existing = markers[agentId] ?: 0L
        if (updatedAtMillis > existing) {
            p[Keys.readMarkers] = encodeMarkers(markers + (agentId to updatedAtMillis))
        }
    }

    suspend fun markLaunchedHere(agentId: String) = store.edit { p ->
        p[Keys.launchedHere] = (p[Keys.launchedHere] ?: emptySet()) + agentId
    }

    suspend fun setDemoMode(enabled: Boolean) = store.edit { it[Keys.demoMode] = enabled }

    suspend fun setCachedUser(user: CursorUser?) = store.edit { p ->
        if (user == null) {
            p.remove(Keys.cachedUser)
        } else {
            p[Keys.cachedUser] = CursorJson.encodeToString(
                CachedUser.serializer(),
                CachedUser(user.apiKeyName, user.email, user.firstName, user.lastName, user.userId),
            )
        }
    }

    suspend fun setCredentialInfo(info: CredentialInfo?) = store.edit { p ->
        if (info == null) {
            p.remove(Keys.signInMethod)
            p.remove(Keys.apiKeyExpiresAt)
        } else {
            p[Keys.signInMethod] = info.method.name
            if (info.expiresAtMs == null) p.remove(Keys.apiKeyExpiresAt) else p[Keys.apiKeyExpiresAt] = info.expiresAtMs
        }
    }

    /** [modelId] null records an explicit "Default" choice (stored as an empty id), which restores as no model. */
    suspend fun setComposerDefaults(repoUrl: String?, ref: String?, modelId: String?, params: Map<String, String>, autoCreatePr: Boolean) =
        store.edit { p ->
            if (repoUrl == null) p.remove(Keys.lastRepo) else p[Keys.lastRepo] = repoUrl
            if (ref == null) p.remove(Keys.lastRef) else p[Keys.lastRef] = ref
            p[Keys.lastModel] = modelId ?: ""
            p[Keys.lastModelParams] = encodeStringMap(params)
            p[Keys.autoCreatePr] = autoCreatePr
        }

    suspend fun clearSession() = store.edit { p ->
        p.remove(Keys.demoMode)
        p.remove(Keys.cachedUser)
        p.remove(Keys.signInMethod)
        p.remove(Keys.apiKeyExpiresAt)
        // The next account starts its own migration and owes the server nothing of this one's pending changes.
        p.remove(Keys.pinsMigrated)
        p.remove(Keys.pendingPins)
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
    }
}
