package com.cursorforandroid

import android.app.Application
import android.content.Context
import com.cursorforandroid.widget.WidgetSync

class CursorApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Placed home-screen widgets follow the list, pins, filters, theme and session for as long as this process lives.
        WidgetSync.start(this, graph)
    }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as CursorApp).graph
