package com.cursorforandroid.data.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import kotlin.math.ln

/** A finished dictation clip: the encoded bytes, the MIME type the transcription service is told, and how long it ran. */
class RecordedClip(val audio: ByteArray, val mimeType: String, val durationMillis: Long)

/**
 * One dictation's microphone: [start] opens it, [level] is read while it runs, and [stop] or [cancel] closes it. Each
 * instance records once; a new dictation asks for a new one. Not thread-safe: the voice input drives it from one place.
 */
interface AudioCapture {
    /** Opens the microphone and starts encoding; throws when the device will not hand it over. */
    fun start()

    /** The loudness since the previous call, 0 (silence) to 1 (clipping), on a log scale that reads like a meter. */
    fun level(): Float

    /** Ends the recording and returns it, or null when nothing usable was captured. */
    fun stop(): RecordedClip?

    /** Ends the recording and throws it away. */
    fun cancel()
}

/**
 * [AudioCapture] on the platform [MediaRecorder], encoding what Cursor's `TranscribeAudio` reads: WebM/Opus
 * (`audio/webm`), the desktop's first choice, on Android 10 and later, and AAC in MPEG-4 (`audio/mp4`), its fallback,
 * before that. Mono speech bitrates keep a five-minute clip a little over a megabyte, under the request cap either way.
 * The file lives in the cache only while the clip is being read back.
 */
class MediaRecorderCapture(private val context: Context, private val now: () -> Long = System::currentTimeMillis) : AudioCapture {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val webm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun start() {
        val out = File.createTempFile("dictation", if (webm) ".webm" else ".m4a", context.cacheDir)
        file = out
        val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        recorder = next
        try {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            if (webm) {
                next.setOutputFormat(MediaRecorder.OutputFormat.WEBM)
                next.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                next.setAudioSamplingRate(48_000)
                next.setAudioEncodingBitRate(32_000)
            } else {
                next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                next.setAudioSamplingRate(44_100)
                next.setAudioEncodingBitRate(64_000)
            }
            next.setAudioChannels(1)
            next.setOutputFile(out.path)
            next.prepare()
            next.start()
            startedAt = now()
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    override fun level(): Float {
        val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        return normalizedLevel(amplitude)
    }

    override fun stop(): RecordedClip? {
        val active = recorder ?: return null
        val duration = now() - startedAt
        // MediaRecorder.stop throws when no frame was written yet (a tap straight after start): nothing was captured.
        val stopped = runCatching { active.stop() }.isSuccess
        val out = file
        release(keepFile = true)
        return try {
            if (!stopped || out == null || !out.exists()) null
            else out.readBytes().takeIf { it.isNotEmpty() }?.let { RecordedClip(it, if (webm) "audio/webm" else "audio/mp4", duration) }
        } finally {
            out?.delete()
            file = null
        }
    }

    override fun cancel() {
        recorder?.let { runCatching { it.stop() } }
        release()
    }

    private fun release(keepFile: Boolean = false) {
        recorder?.let { runCatching { it.reset() }; it.release() }
        recorder = null
        if (!keepFile) {
            file?.delete()
            file = null
        }
    }

    companion object {
        /**
         * [MediaRecorder.getMaxAmplitude]'s 0–32767 as a 0–1 meter reading: the range from a quiet room (about 100)
         * to a shout is compressed logarithmically, so ordinary speech moves the bars through their middle.
         */
        fun normalizedLevel(amplitude: Int): Float {
            if (amplitude <= QuietAmplitude) return 0f
            val span = ln(MaxAmplitude / QuietAmplitude)
            return (ln(amplitude / QuietAmplitude) / span).toFloat().coerceIn(0f, 1f)
        }

        private const val QuietAmplitude = 100.0
        private const val MaxAmplitude = 32_767.0
    }
}
