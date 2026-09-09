package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.ProfileApi
import com.cursorforandroid.data.api.RunStreamer
import com.cursorforandroid.data.api.toCursorError
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.auth.CursorLogin
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.CredentialInfo
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.SignInMethod
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState

    /** [credential] describes the stored key; it is null for the demo, which has none. */
    data class SignedIn(val user: CursorUser, val isDemo: Boolean, val credential: CredentialInfo? = null) : SessionState
}

/** Where a browser sign-in stands. Held by the session rather than the screen so it survives the trip through the browser. */
sealed interface LoginProgress {
    data object Idle : LoginProgress

    /** The confirmation page is open in the browser; the app is waiting for the user to approve. */
    data class WaitingForBrowser(val loginUrl: String) : LoginProgress

    /** Approved; the key is being issued and checked against the API. */
    data object Finishing : LoginProgress

    data class Failed(val message: String) : LoginProgress
}

/** Pairs an API implementation with the streamer that goes with it (real HTTP or the in-memory demo). */
class CursorBackend(val api: CursorApi, val streamer: RunStreamer, val isDemo: Boolean)

class SessionManager(
    private val keyStore: SecureKeyStore,
    private val prefs: PreferencesStore,
    private val realBackend: CursorBackend,
    private val demoBackend: CursorBackend,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** How long a cold start with no cached account waits for `/v1/me` before showing a degraded signed-in state. */
    private val restoreTimeoutMs: Long = RESTORE_TIMEOUT_MS,
    /** The browser sign-in; absent in tests that only exercise pasted keys. */
    private val browserLogin: CursorLogin? = null,
    /** Name of the key a browser sign-in mints, as listed on cursor.com/dashboard/api. */
    private val mintedKeyName: String = "Cursor for Android",
    private val mintedKeyTtlMs: Long = CursorLogin.API_KEY_TTL_MS,
    /** The account service's view of the user (the profile picture, above all); absent in tests that stop at `/v1/me`. */
    private val profile: ProfileApi? = null,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _backend = MutableStateFlow(realBackend)
    val backend: StateFlow<CursorBackend> = _backend.asStateFlow()

    private val _loginProgress = MutableStateFlow<LoginProgress>(LoginProgress.Idle)
    val loginProgress: StateFlow<LoginProgress> = _loginProgress.asStateFlow()

    private val _signedOutReason = MutableStateFlow<String?>(null)

    /**
     * Why the app is at the sign-in screen when the user did not ask to be: this device's settings or its secure
     * store could not be read, so the stored key is gone or was never readable. Null for an ordinary signed-out state.
     */
    val signedOutReason: StateFlow<String?> = _signedOutReason.asStateFlow()
    private var loginJob: Job? = null
    private val restoreMutex = Mutex()

    val current: CursorBackend get() = _backend.value
    val isDemo: Boolean get() = current.isDemo

    /** Runs on every sign-out, including the automatic one after a rejected key; the app graph wipes its caches here. */
    var onSignedOut: suspend () -> Unit = {}

    /**
     * [restore], unless the session has already been decided. The activity and a widget render can both be the
     * first thing that needs the session in a fresh process; whichever comes second waits for the first instead of
     * restoring again.
     */
    suspend fun restoreIfNeeded() {
        if (_state.value !is SessionState.Loading) return
        restoreMutex.withLock {
            if (_state.value !is SessionState.Loading) return
            try {
                restore()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // The splash screen is held for as long as the session is Loading, so a store that cannot be read
                // has to end somewhere: the sign-in screen, with the reason, rather than a frozen splash or a crash.
                _signedOutReason.value = STORAGE_FAILURE_MESSAGE
                _state.value = SessionState.SignedOut
            }
        }
    }

    /**
     * Called once at startup. Signs in from the stored key or demo flag; tolerates being offline. The cached account
     * makes the session signed-in immediately; the key is then re-validated against `/v1/me` in the background and
     * the wait for it is bounded even when nothing is cached, so a slow network never holds the splash screen.
     */
    suspend fun restore() {
        // A sign-out that could not prove the key was gone left a tombstone behind. The key stays withheld while it
        // stands, and the removal and the preference cleanup it could not finish are retried now.
        if (withContext(Dispatchers.IO) { keyStore.signOutPending() }) {
            finishPendingSignOut()
            return
        }
        if (prefs.demoMode.first()) {
            _backend.value = demoBackend
            _state.value = SessionState.SignedIn(demoUser(), isDemo = true)
            return
        }
        // The encrypted key store initialises the Android Keystore on first access; never on the main thread.
        val key = withContext(Dispatchers.IO) { keyStore.apiKey() }
        if (key.isNullOrBlank()) {
            // No key and not the demo: no account owns the settings a sign-out clears, so a clear that could not be
            // written last time is tried again here rather than being inherited by whoever signs in next.
            prefs.clearSession()
            _signedOutReason.value = when (keyStore.availability.value) {
                SecureKeyStore.Availability.Encrypted -> null
                SecureKeyStore.Availability.Reset -> KEY_STORE_RESET_MESSAGE
                SecureKeyStore.Availability.Unavailable -> KEY_STORE_UNAVAILABLE_MESSAGE
            }
            _state.value = SessionState.SignedOut
            return
        }
        // Keys stored before the method was recorded were all pasted.
        val credential = prefs.credentialInfo.first() ?: CredentialInfo(SignInMethod.ApiKey, expiresAtMs = null)
        if (credential.isExpiredAt(AppClock.now())) {
            // A key the app minted has lapsed; the API would only say 401. Skip the doomed request.
            signOut()
            return
        }
        _backend.value = realBackend
        val cached = prefs.cachedUser.first()
        if (cached != null) _state.value = SessionState.SignedIn(cached, isDemo = false, credential = credential)
        val validation: Job = scope.launch { validateStoredKey(cached, credential) }
        if (cached == null) {
            withTimeoutOrNull(restoreTimeoutMs) { validation.join() }
            // Still loading: offline or slow with no cache. Keep the key and show a degraded signed-in state; the
            // validation keeps running and signs out if the key turns out to be rejected.
            if (_state.value is SessionState.Loading) _state.value = SessionState.SignedIn(placeholderUser(), isDemo = false, credential = credential)
        }
    }

    private suspend fun validateStoredKey(cached: CursorUser?, credential: CredentialInfo) {
        // `/v1/me` knows nothing of the picture; the one shown last time stays until the account service answers.
        runCatching { realBackend.api.me().toUser().copy(profilePictureUrl = cached?.profilePictureUrl) }
            .onSuccess { user ->
                prefs.setCachedUser(user)
                if (_backend.value === realBackend) _state.value = SessionState.SignedIn(user, isDemo = false, credential = credential)
                enrichProfile(user, credential)
            }
            .onFailure { t ->
                val err = t.toCursorError()
                if (err?.isUnauthorized == true) {
                    signOut()
                } else if (cached == null && _state.value is SessionState.Loading) {
                    _state.value = SessionState.SignedIn(placeholderUser(), isDemo = false, credential = credential)
                }
            }
    }

    /** Stores [apiKey] and validates it against `/v1/me`; a rejected key is removed again. */
    suspend fun signIn(
        apiKey: String,
        credential: CredentialInfo = CredentialInfo(SignInMethod.ApiKey, expiresAtMs = null),
    ): Result<CursorUser> {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Paste your Cursor API key first."))
        storeKey(trimmed)
        _backend.value = realBackend
        return runCatching { realBackend.api.me().toUser() }
            .onSuccess { user ->
                prefs.setDemoMode(false)
                prefs.setCachedUser(user)
                prefs.setCredentialInfo(credential)
                _signedOutReason.value = null
                _state.value = SessionState.SignedIn(user, isDemo = false, credential = credential)
                // The picture arrives behind the sign-in rather than holding it up.
                scope.launch { enrichProfile(user, credential) }
            }
            .onFailure {
                storeKey(null)
            }
            .recoverCatching { throw IllegalStateException(it.userMessage(), it) }
    }

    /**
     * Fills in what the public API does not say about the account — the profile picture above all, and a name or
     * email `/v1/me` left blank — from the account service. Best effort: a failure leaves the user as `/v1/me`
     * described it, and an answer that arrives after a sign-out or a backend switch is dropped.
     */
    private suspend fun enrichProfile(user: CursorUser, credential: CredentialInfo) {
        val api = profile ?: return
        val fetched = runCatching { api.profile() }.getOrNull() ?: return
        val enriched = user.copy(
            profilePictureUrl = fetched.profilePictureUrl,
            firstName = user.firstName ?: fetched.firstName,
            lastName = user.lastName ?: fetched.lastName,
            email = user.email ?: fetched.email,
        )
        if (enriched == user) return
        val current = _state.value as? SessionState.SignedIn ?: return
        if (_backend.value !== realBackend || current.isDemo || current.user.userId != user.userId) return
        prefs.setCachedUser(enriched)
        _state.value = SessionState.SignedIn(enriched, isDemo = false, credential = credential)
    }

    /**
     * Signs in with a Cursor account. Returns at once with the confirmation page for the caller to open in a browser;
     * the approval is awaited on the session's own scope, so the app being covered by the browser (or recreated) does
     * not lose the sign-in. The approved session mints an API key for this app, which then goes through [signIn].
     * Progress is published on [loginProgress]; the end state is [SessionState.SignedIn] or [LoginProgress.Failed].
     */
    fun startCursorLogin(): String {
        val login = checkNotNull(browserLogin) { "Browser sign-in is not configured" }
        cancelCursorLogin()
        val handshake = login.startHandshake()
        _loginProgress.value = LoginProgress.WaitingForBrowser(handshake.loginUrl)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val me = coroutineContext.job
            try {
                val tokens = login.awaitTokens(handshake)
                if (loginJob === me) _loginProgress.value = LoginProgress.Finishing
                val expiresAt = AppClock.now() + mintedKeyTtlMs
                val key = login.mintApiKey(tokens.accessToken, mintedKeyName, expiresAt)
                signIn(key, CredentialInfo(SignInMethod.Cursor, expiresAt)).getOrThrow()
                if (loginJob === me) _loginProgress.value = LoginProgress.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (loginJob === me) _loginProgress.value = LoginProgress.Failed(t.message ?: "Couldn't sign in with Cursor.")
            }
        }
        loginJob = job
        job.start()
        return handshake.loginUrl
    }

    /** Abandons a browser sign-in in progress; the confirmation page, if still approved later, releases nothing usable. */
    fun cancelCursorLogin() {
        loginJob?.cancel()
        loginJob = null
        _loginProgress.value = LoginProgress.Idle
    }

    /** Clears a [LoginProgress.Failed] once the screen has shown it. */
    fun dismissLoginError() {
        if (_loginProgress.value is LoginProgress.Failed) _loginProgress.value = LoginProgress.Idle
    }

    suspend fun enterDemo() {
        cancelCursorLogin()
        _signedOutReason.value = null
        prefs.setDemoMode(true)
        // Mirror the reference layout: the three showcase agents start pinned.
        prefs.pinIfNonePinned(listOf("bc-demo-0001", "bc-demo-0002", "bc-demo-0003"))
        _backend.value = demoBackend
        _state.value = SessionState.SignedIn(demoUser(), isDemo = true)
    }

    /**
     * Signs out. False when the sign-out could not be made durable — the key's removal even after the store was
     * started over, or the account's own settings — in which case a tombstone withholds the key, the settings are
     * masked in memory, and [restore] retries both on the next start: a sign-out never reports success while
     * anything of the account could come back.
     */
    suspend fun signOut(): Boolean {
        cancelCursorLogin()
        _signedOutReason.value = null
        // Fail-closed, and before anything else: whatever else goes wrong, the key must not be readable again.
        val cleared = storeKey(null)
        // The repositories bump their account generations and stop what is in flight in here, so nothing of this
        // account can still be about to write when the preferences it owns are cleared.
        runCatching { onSignedOut() }
        val forgotten = prefs.clearSession()
        _backend.value = realBackend
        _state.value = SessionState.SignedOut
        return cleared && forgotten
    }

    /** The removal a previous sign-out could not prove, tried again, together with the cleanup that went with it. */
    private suspend fun finishPendingSignOut() {
        storeKey(null)
        runCatching { onSignedOut() }
        prefs.clearSession()
        _signedOutReason.value = null
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

    /** Offline with no cached account: the key is kept and the UI shows a degraded signed-in state. */
    private fun placeholderUser() = CursorUser("Cursor", null, null, null, null)

    private companion object {
        const val RESTORE_TIMEOUT_MS = 8_000L
        const val STORAGE_FAILURE_MESSAGE = "This device's saved sign-in couldn't be read. Sign in again."
        const val KEY_STORE_RESET_MESSAGE = "This device's secure storage had to be reset, so the saved key was cleared. Sign in again."
        const val KEY_STORE_UNAVAILABLE_MESSAGE =
            "This device can't store a key securely right now, so signing in will only last until the app closes."
    }
}
