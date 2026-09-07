package com.cursorforandroid.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secrets, encrypted with an Android Keystore-backed master key: the Cursor API key and the MCP server definitions
 * (their headers and environment variables hold credentials).
 */
class SecureKeyStore(private val context: Context) {

    private val prefs: SharedPreferences by lazy { create() }

    private fun create(): SharedPreferences = try {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "cursor_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        // Keystore can be unavailable on some emulators / broken devices. Never crash the app over it.
        Log.w("SecureKeyStore", "Encrypted prefs unavailable, falling back to private prefs", t)
        context.getSharedPreferences("cursor_prefs_fallback", Context.MODE_PRIVATE)
    }

    @Volatile
    private var cached: String? = null

    fun apiKey(): String? = cached ?: prefs.getString(KEY_API_KEY, null)?.also { cached = it }

    fun setApiKey(key: String?) {
        cached = key
        prefs.edit().apply {
            if (key.isNullOrBlank()) remove(KEY_API_KEY) else putString(KEY_API_KEY, key)
        }.apply()
    }

    /** The MCP server list as JSON; the shape is owned by [McpServerStore]. */
    fun mcpServersJson(): String? = prefs.getString(KEY_MCP_SERVERS, null)

    fun setMcpServersJson(json: String?) {
        prefs.edit().apply {
            if (json.isNullOrBlank()) remove(KEY_MCP_SERVERS) else putString(KEY_MCP_SERVERS, json)
        }.apply()
    }

    private companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_MCP_SERVERS = "mcp_servers"
    }
}
