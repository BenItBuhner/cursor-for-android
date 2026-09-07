package com.cursorforandroid.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
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
        val listPrefs = stringPreferencesKey("list_prefs")
        val pinned = stringSetPreferencesKey("pinned_ids")
        val readMarkers = stringPreferencesKey("read_markers")
        val launchedHere = stringSetPreferencesKey("launched_here_ids")
        val demoMode = booleanPreferencesKey("demo_mode")
        val cachedUser = stringPreferencesKey("cached_user")
        val lastRepo = stringPreferencesKey("last_repo")
        val lastRef = stringPreferencesKey("last_ref")
        val lastModel = stringPreferencesKey("last_model")
        val lastModelParams = stringPreferencesKey("last_model_params")
        val autoCreatePr = booleanPreferencesKey("auto_create_pr")
    }

    val themeMode: Flow<ThemeMode> = store.data.map { p ->
        p[Keys.theme]?.let { raw -> ThemeMode.entries.firstOrNull { it.name == raw } } ?: ThemeMode.System
    }

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

    data class ComposerDefaults(
        val repoUrl: String?,
        val ref: String?,
        val modelId: String?,
        val modelParams: Map<String, String>,
        val autoCreatePr: Boolean,
    )

    val composerDefaults: Flow<ComposerDefaults> = store.data.map { p ->
        ComposerDefaults(
            repoUrl = p[Keys.lastRepo],
            ref = p[Keys.lastRef],
            modelId = p[Keys.lastModel],
            modelParams = p[Keys.lastModelParams]?.let { decodeStringMap(it) } ?: emptyMap(),
            autoCreatePr = p[Keys.autoCreatePr] ?: false,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[Keys.theme] = mode.name }

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

    suspend fun setComposerDefaults(repoUrl: String?, ref: String?, modelId: String?, params: Map<String, String>, autoCreatePr: Boolean) =
        store.edit { p ->
            if (repoUrl == null) p.remove(Keys.lastRepo) else p[Keys.lastRepo] = repoUrl
            if (ref == null) p.remove(Keys.lastRef) else p[Keys.lastRef] = ref
            if (modelId == null) p.remove(Keys.lastModel) else p[Keys.lastModel] = modelId
            p[Keys.lastModelParams] = encodeStringMap(params)
            p[Keys.autoCreatePr] = autoCreatePr
        }

    suspend fun clearSession() = store.edit { p ->
        p.remove(Keys.demoMode)
        p.remove(Keys.cachedUser)
    }

    private fun decodeMarkers(raw: String): Map<String, Long> =
        runCatching { CursorJson.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), raw) }.getOrDefault(emptyMap())

    private fun encodeMarkers(map: Map<String, Long>): String =
        CursorJson.encodeToString(MapSerializer(String.serializer(), Long.serializer()), map)

    private fun decodeStringMap(raw: String): Map<String, String> =
        runCatching { CursorJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw) }.getOrDefault(emptyMap())

    private fun encodeStringMap(map: Map<String, String>): String =
        CursorJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), map)
}
