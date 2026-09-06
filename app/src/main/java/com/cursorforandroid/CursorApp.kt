package com.cursorforandroid

import android.app.Application
import android.content.Context

class CursorApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as CursorApp).graph
