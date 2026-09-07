package com.cursorforandroid.util

/**
 * The wall clock behind every timestamp and "now"-relative label in the app. Production reads the system clock; the
 * screenshot tests pin [nowMillis] so the demo data, date headers and relative ages render identically on every run.
 */
object AppClock {
    @Volatile
    var nowMillis: () -> Long = System::currentTimeMillis

    fun now(): Long = nowMillis()
}
