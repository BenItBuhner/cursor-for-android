package com.cursorforandroid

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import com.cursorforandroid.update.UpdateJobService
import com.cursorforandroid.widget.WidgetSync

class CursorApp : Application(), Configuration.Provider {
    lateinit var graph: AppGraph
        private set

    /**
     * WorkManager (which Glance renders inside) hands out `JobScheduler` ids of its own, and left to itself it treats
     * every id as available. It is told a range here so it can never take [UpdateJobService.JOB_ID] and replace the
     * periodic update check with a widget render. Its default initializer is removed from the manifest so that this
     * configuration is the one used, which also means WorkManager starts on first use rather than on every launch.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setJobSchedulerJobIdRange(0, WORK_JOB_ID_MAX)
            .build()

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Placed home-screen widgets follow the list, pins, filters, theme and session for as long as this process lives.
        WidgetSync.start(this, graph)
    }

    private companion object {
        /** Well clear of [UpdateJobService.JOB_ID], and far more concurrent work than this app could ever enqueue. */
        const val WORK_JOB_ID_MAX = 100_000
    }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as CursorApp).graph
