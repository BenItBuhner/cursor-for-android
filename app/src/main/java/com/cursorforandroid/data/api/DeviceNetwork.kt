package com.cursorforandroid.data.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the phone has a network at all, by Android's own account: what tells a failure that means being offline
 * from one on a phone that is online — a name lookup the resolver did not answer, a host that would not connect —
 * which was said as "You're offline" with Wi-Fi and cellular both up (Bennett's v0.3.85 frame). Installed once the
 * process starts (see `CursorApp`); a process that has not installed it cannot tell, and never says offline.
 */
object DeviceNetwork {
    @Volatile private var probe: () -> Boolean? = { null }

    fun install(probe: () -> Boolean?) {
        this.probe = probe
    }

    /**
     * Android's connectivity service asked each time: offline only with no default network, or one that neither
     * carries internet traffic nor says it is connected.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        install {
            val manager = app.getSystemService(ConnectivityManager::class.java) ?: return@install null
            val active = manager.activeNetwork ?: return@install false
            val internet = manager.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            @Suppress("DEPRECATION")
            internet || manager.activeNetworkInfo?.isConnected == true
        }
    }

    /** True: a network with internet access is up. False: none is. Null: this process cannot tell. */
    fun isOnline(): Boolean? = runCatching { probe() }.getOrNull()
}
