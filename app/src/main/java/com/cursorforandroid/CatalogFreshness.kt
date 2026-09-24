package com.cursorforandroid

import android.content.Context
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.cursorforandroid.widget.deviceRefreshBudget
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps the composer's model and repository lists fresh while the app is on screen: each time it comes forward the
 * catalogs that went stale while it was away are fetched again, and then each one as it comes due
 * ([com.cursorforandroid.data.repo.CatalogRepository.keepFresh]) until the app leaves the screen. Nothing goes out
 * without a connection, on a low battery, or while the device is saving power. Bound from [DeferredStartup], so
 * from [MainActivity] only, and once the first screen has settled.
 */
object CatalogFreshness {

    private var installed = false
    private var job: Job? = null

    fun bind(activity: ComponentActivity, graph: AppGraph) {
        if (installed) return
        installed = true
        val app = activity.applicationContext
        val process = ProcessLifecycleOwner.get()
        // Process-level: a rotation restarts the activity, which is not the app coming forward.
        process.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        job?.cancel()
                        job = process.lifecycleScope.launch { graph.catalog.keepFresh { mayRefresh(app) } }
                    }
                    Lifecycle.Event.ON_STOP -> {
                        job?.cancel()
                        job = null
                    }
                    else -> Unit
                }
            },
        )
    }

    private fun mayRefresh(context: Context): Boolean =
        deviceRefreshBudget(context).allowsRefresh && context.getSystemService(PowerManager::class.java)?.isPowerSaveMode != true
}
