package com.cursorforandroid.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.ui.components.VoiceInput
import com.cursorforandroid.ui.components.rememberVoiceInput

/**
 * The composer's dictation wherever it can be transcribed ([AppGraph.voiceInput]: in Extended mode, outside the demo);
 * null otherwise, which leaves the send slot as it always was. Shared by every real composer.
 */
@Composable
fun rememberComposerVoice(graph: AppGraph): VoiceInput? {
    val on by graph.voiceInput.collectAsStateWithLifecycle(initialValue = false)
    return rememberVoiceInput(if (on) graph.transcription else null)
}
