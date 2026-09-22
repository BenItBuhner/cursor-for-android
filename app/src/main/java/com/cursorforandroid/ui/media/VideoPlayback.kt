package com.cursorforandroid.ui.media

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

/**
 * How the viewer gets a [Player] for a recording. The app's builds an ExoPlayer that takes audio focus (so a
 * recording ducks the user's music and pauses when another app takes over) and stops when the headphones come out;
 * a test hands in a stand-in that reports whatever state the frame under test needs.
 */
val LocalVideoPlayerFactory = staticCompositionLocalOf<(Context) -> Player> { ::defaultVideoPlayer }

private fun defaultVideoPlayer(context: Context): Player = ExoPlayer.Builder(context)
    .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(), true)
    .setHandleAudioBecomingNoisy(true)
    .build()

/**
 * A recording's or a sound's playback as the page and the chrome read it: the [player] wrapped with snapshot state
 * for what it reports, so the controls recompose on what changes and nothing polls the player from the composition.
 * The position is sampled once a frame while playing ([tick]).
 */
@Stable
class VideoPlayback(val player: Player) : Player.Listener {
    var isPlaying by mutableStateOf(player.isPlaying)
        private set
    var playbackState by mutableIntStateOf(player.playbackState)
        private set
    var durationMs by mutableLongStateOf(player.duration.takeIf { it != C.TIME_UNSET } ?: 0L)
        private set
    var positionMs by mutableLongStateOf(player.currentPosition)
        private set
    var bufferedMs by mutableLongStateOf(player.bufferedPosition)
        private set
    var videoSize by mutableStateOf(player.videoSize.toIntSize())
        private set
    /** The surface has its first frame: the poster underneath can go. */
    var firstFrameRendered by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** The reader is dragging the scrubber: the thumb follows the finger, not the player, until it is let go. */
    var scrubbingToMs by mutableStateOf<Long?>(null)
        private set
    /** The rate it plays at: 1 unless the reader picked another ([cycleSpeed]). */
    var speed by mutableFloatStateOf(player.playbackParameters.speed)
        private set

    val isEnded: Boolean get() = playbackState == Player.STATE_ENDED
    val isBuffering: Boolean get() = playbackState == Player.STATE_BUFFERING
    /** What the scrubber shows: the finger's position while scrubbing, the player's otherwise. */
    val shownPositionMs: Long get() = scrubbingToMs ?: positionMs

    init {
        player.addListener(this)
    }

    fun load(url: String, playWhenReady: Boolean, muted: Boolean) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.volume = if (muted) 0f else 1f
        player.prepare()
        player.playWhenReady = playWhenReady
        tick()
    }

    fun togglePlay() {
        when {
            isEnded -> {
                player.seekTo(0)
                player.play()
            }
            player.isPlaying || player.playWhenReady -> player.pause()
            else -> player.play()
        }
        tick()
    }

    fun pause() {
        player.pause()
        tick()
    }

    fun setMuted(muted: Boolean) {
        player.volume = if (muted) 0f else 1f
    }

    /** The next of [SPEEDS] after the one it plays at, round to the start again. */
    fun cycleSpeed() {
        val next = SPEEDS[(SPEEDS.indexOfFirst { it == speed } + 1).mod(SPEEDS.size)]
        player.setPlaybackSpeed(next)
        speed = next
    }

    fun scrubTo(fraction: Float) {
        val duration = durationMs.takeIf { it > 0L } ?: return
        scrubbingToMs = (fraction.coerceIn(0f, 1f) * duration).toLong()
    }

    fun endScrub() {
        val target = scrubbingToMs ?: return
        player.seekTo(target)
        scrubbingToMs = null
        positionMs = target
    }

    /** Samples what the player reports; the page calls it once a frame while playing and on every event otherwise. */
    fun tick() {
        positionMs = player.currentPosition.coerceAtLeast(0L)
        bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
        player.duration.takeIf { it != C.TIME_UNSET && it > 0L }?.let { durationMs = it }
        isPlaying = player.isPlaying
        playbackState = player.playbackState
    }

    fun release() {
        player.removeListener(this)
        player.release()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        this.isPlaying = isPlaying
        tick()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        this.playbackState = playbackState
        tick()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = tick()

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = tick()

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        this.videoSize = videoSize.toIntSize()
    }

    override fun onRenderedFirstFrame() {
        firstFrameRendered = true
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        speed = playbackParameters.speed
    }

    override fun onPlayerError(error: PlaybackException) {
        this.error = when (error.errorCode) {
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            -> "This format doesn't play on this device."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "This file couldn't be read; it may be damaged or cut short."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The file isn't there any more."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "The file couldn't be fetched. Check your connection."
            else -> "Couldn't play this file."
        }
    }

    companion object {
        /** The rates the speed button steps through. */
        val SPEEDS = listOf(1f, 1.5f, 2f, 0.75f)

        /** "1×", "1.5×", "0.75×": a rate as the button shows it. */
        fun speedLabel(speed: Float): String = (if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()) + "\u00D7"

        private fun VideoSize.toIntSize(): IntSize =
            if (width > 0 && height > 0) IntSize((width * pixelWidthHeightRatio).toInt().coerceAtLeast(1), height) else IntSize.Zero
    }
}
