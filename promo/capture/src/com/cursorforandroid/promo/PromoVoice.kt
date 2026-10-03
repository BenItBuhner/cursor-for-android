package com.cursorforandroid.promo

import android.media.MediaRecorder
import com.cursorforandroid.data.api.Transcription
import com.cursorforandroid.data.api.TranscriptionApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaRecorder
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The microphone a dictation in the capture records from: the app's own recorder, on Robolectric's stand-in for the
 * platform's, hearing someone say the hero's task. Its meter reads [PromoVoice.amplitude] from the moment it starts,
 * on the capture's clock, and what it records is a few bytes of file for the app to read back and send.
 */
@Implements(MediaRecorder::class)
class PromoMediaRecorder : ShadowMediaRecorder() {
    private var startedAt = 0L

    @Implementation
    override fun start() {
        super.start()
        startedAt = VirtualTime.nowMs
    }

    @Implementation
    protected fun getMaxAmplitude(): Int = PromoVoice.amplitude(VirtualTime.nowMs - startedAt)

    @Implementation
    override fun stop() {
        super.stop()
        outputPath?.let { File(it).writeBytes(PromoVoice.CLIP) }
    }
}

/**
 * Cursor's `TranscribeAudio` as the capture has it: whatever was recorded comes back, after a moment on the capture's
 * clock, as [words].
 */
internal class PromoTranscription(private val words: String) : TranscriptionApi {
    override suspend fun transcribe(audio: ByteArray, mimeType: String, language: String?): Transcription = withContext(Dispatchers.IO) {
        VirtualTime.scripted { VirtualTime.sleep(PromoVoice.TRANSCRIBE_MS) }
        Transcription(words, PromoVoice.TRANSCRIBE_MS)
    }
}

internal object PromoVoice {
    /** How long the service takes to answer. */
    const val TRANSCRIBE_MS = 650L

    /** What the recorder leaves in its file: a WebM header's first bytes, enough to be a clip rather than nothing. */
    val CLIP = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x9F.toByte(), 0x42, 0x86.toByte(), 0x81.toByte(), 0x01)

    /** "Add dark mode to the dashboard", a syllable at a time: when each starts and how long it lasts (ms), and its peak. */
    private val SYLLABLES = listOf(
        Triple(340L, 170L, 13_500), // add
        Triple(560L, 230L, 18_500), // dark
        Triple(830L, 250L, 16_000), // mode
        Triple(1_120L, 120L, 7_500), // to
        Triple(1_270L, 110L, 6_000), // the
        Triple(1_430L, 220L, 17_500), // dash
        Triple(1_690L, 320L, 15_000), // board
    )

    private const val ROOM = 180

    /**
     * The microphone's peak amplitude (0 to 32767, as `MediaRecorder.getMaxAmplitude` reports it) [ms] into the
     * recording: a quiet room, and each syllable rising and falling over it, with a little unevenness of its own.
     */
    fun amplitude(ms: Long): Int {
        val tick = ms / 80
        var level = ROOM + (Random(tick).nextDouble() * 90).toInt()
        for ((start, length, peak) in SYLLABLES) {
            val into = ms - start
            if (into < 0 || into > length) continue
            val shape = sin(PI * into / length)
            level += (peak * shape * shape * (0.82 + 0.18 * Random(tick + start).nextDouble())).toInt()
        }
        return level.coerceIn(0, 32_767)
    }
}
