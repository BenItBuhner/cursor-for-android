package com.cursorforandroid.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cursorforandroid.CursorApp
import com.cursorforandroid.data.api.dto.CreateRunRequestDto
import com.cursorforandroid.data.api.dto.PromptDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug builds, demo mode only: a turn started in a demo chat "elsewhere" — the demo account's API asked for a run as
 * the desktop asks Cursor's, the screens on this phone told nothing — so the pull to catch up can be shown finding it.
 *
 * `adb shell am broadcast -n com.cursorforandroid.debug/com.cursorforandroid.debug.TurnElsewhereReceiver --es agent bc-demo-0004 --es prompt "…"`
 */
class TurnElsewhereReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val agentId = intent.getStringExtra("agent") ?: return
        val prompt = intent.getStringExtra("prompt") ?: "Started from the desktop."
        val backend = (context.applicationContext as CursorApp).graph.session.current
        if (!backend.isDemo) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                backend.api.createRun(agentId, CreateRunRequestDto(PromptDto(prompt)))
                Log.i(TAG, "started a turn in $agentId elsewhere")
            } catch (t: Throwable) {
                Log.w(TAG, "could not start a turn in $agentId", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "TurnElsewhere"
    }
}
