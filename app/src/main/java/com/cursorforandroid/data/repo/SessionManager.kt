package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.RunStreamer
import com.cursorforandroid.data.api.toCursorError
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.CursorUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val user: CursorUser, val isDemo: Boolean) : SessionState
}

/** Pairs an API implementation with the streamer that goes with it (real HTTP or the in-memory demo). */
class CursorBackend(val api: CursorApi, val streamer: RunStreamer, val isDemo: Boolean)

class SessionManager(
    private val keyStore: SecureKeyStore,
    private val prefs: PreferencesStore,
    private val realBackend: CursorBackend,
    private val demoBackend: CursorBackend,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _backend = MutableStateFlow(realBackend)
    val backend: StateFlow<CursorBackend> = _backend.asStateFlow()

    val current: CursorBackend get() = _backend.value
    val isDemo: Boolean get() = current.isDemo

    /** Called once at startup. Signs in from the stored key or demo flag; tolerates being offline. */
    suspend fun restore() {
        if (prefs.demoMode.first()) {
            _backend.value = demoBackend
            _state.value = SessionState.SignedIn(demoUser(), isDemo = true)
            return
        }
        val key = withContext(Dispatchers.IO) { keyStore.apiKey() }
        if (key.isNullOrBlank()) {
            _state.value = SessionState.SignedOut
            return
        }
        _backend.value = realBackend
        val cached = prefs.cachedUser.first()
        if (cached != null) _state.value = SessionState.SignedIn(cached, isDemo = false)
        val fresh = runCatching { realBackend.api.me().toUser() }
        fresh.onSuccess { user ->
            prefs.setCachedUser(user)
            _state.value = SessionState.SignedIn(user, isDemo = false)
        }.onFailure { t ->
            val err = t.toCursorError()
            if (err?.isUnauthorized == true) {
                signOut()
            } else if (cached == null) {
                // Offline with no cache: keep the key and let the UI show a degraded signed-in state.
                _state.value = SessionState.SignedIn(CursorUser("Cursor", null, null, null, null), isDemo = false)
            }
        }
    }

    suspend fun signIn(apiKey: String): Result<CursorUser> {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Paste your Cursor API key first."))
        storeKey(trimmed)
        _backend.value = realBackend
        return runCatching { realBackend.api.me().toUser() }
            .onSuccess { user ->
                prefs.setDemoMode(false)
                prefs.setCachedUser(user)
                _state.value = SessionState.SignedIn(user, isDemo = false)
            }
            .onFailure {
                storeKey(null)
            }
            .recoverCatching { throw IllegalStateException(it.userMessage(), it) }
    }

    suspend fun enterDemo() {
        prefs.setDemoMode(true)
        // Mirror the reference layout: the three showcase agents start pinned.
        prefs.pinIfNonePinned(listOf("bc-demo-0001", "bc-demo-0002", "bc-demo-0003"))
        _backend.value = demoBackend
        _state.value = SessionState.SignedIn(demoUser(), isDemo = true)
    }

    suspend fun signOut() {
        storeKey(null)
        prefs.clearSession()
        _backend.value = realBackend
        _state.value = SessionState.SignedOut
    }

    /** The encrypted store opens the Android Keystore on first use, which is disk and IPC work, so keep it off Main. */
    private suspend fun storeKey(key: String?) = withContext(Dispatchers.IO) { keyStore.setApiKey(key) }

    private fun demoUser() = CursorUser(
        apiKeyName = "Demo",
        email = "demo@cursor.local",
        firstName = "Demo",
        lastName = "User",
        userId = null,
    )
}
