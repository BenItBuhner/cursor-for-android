package com.cursorforandroid.domain

/** How the API key on this device came to be. */
enum class SignInMethod {
    /** Pasted by the user from cursor.com/dashboard/api. */
    ApiKey,

    /** Minted for this app by a browser sign-in with the user's Cursor account. */
    Cursor,
}

/**
 * The non-secret facts about the stored API key. [expiresAtMs] is set for keys the app minted itself (the browser
 * sign-in issues them with a lifetime); a pasted key's expiry is unknown here.
 */
data class CredentialInfo(val method: SignInMethod, val expiresAtMs: Long?) {
    fun isExpiredAt(nowMs: Long): Boolean = expiresAtMs != null && nowMs >= expiresAtMs
}
