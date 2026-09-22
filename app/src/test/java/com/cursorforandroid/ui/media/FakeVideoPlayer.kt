package com.cursorforandroid.ui.media

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.VideoSize
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A [Player] for the viewer's tests: reports a ready recording of [durationMs] at whatever position it is told,
 * plays and pauses on request, seeks, takes a volume and a surface, and never decodes a frame. What ExoPlayer
 * would do on a phone, minus the phone.
 */
class FakeVideoPlayer(private val durationMs: Long = 12_000L, private val width: Int = 1280, private val height: Int = 720) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var items: List<MediaItem> = emptyList()
    var playWhenReadyFlag = false
        private set
    var positionMs = 0L
        private set
    var volumeSet = 1f
        private set
    var prepared = false
        private set
    var released = false
        private set
    var surfaces = 0
        private set
    var renderedFrame = false
    var speed = 1f
        private set

    override fun getState(): State {
        val builder = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlayWhenReady(playWhenReadyFlag, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (items.isEmpty() || !prepared) Player.STATE_IDLE else if (positionMs >= durationMs) Player.STATE_ENDED else Player.STATE_READY)
            .setPlaylist(items.map { item -> MediaItemData.Builder(item.mediaId.ifEmpty { item.toString() }).setMediaItem(item).setDurationUs(durationMs * 1_000).setIsSeekable(true).build() })
            .setVolume(volumeSet)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setVideoSize(VideoSize(width, height))
            .setContentPositionMs(positionMs)
            .setContentBufferedPositionMs(PositionSupplier.getConstant(durationMs))
        if (items.isNotEmpty()) builder.setCurrentMediaItemIndex(0)
        if (renderedFrame) {
            builder.setNewlyRenderedFirstFrame(true)
            renderedFrame = false
        }
        return builder.build()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        items = mediaItems.toList()
        positionMs = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        prepared = true
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playWhenReadyFlag = playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        this.positionMs = positionMs.coerceIn(0L, durationMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        volumeSet = volume
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        surfaces++
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        surfaces--
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        return Futures.immediateVoidFuture()
    }

    /** The playback moved on its own (a frame decoded): what a running ExoPlayer reports as time passes. */
    fun advanceTo(positionMs: Long, firstFrame: Boolean = false) {
        this.positionMs = positionMs.coerceIn(0L, durationMs)
        if (firstFrame) renderedFrame = true
        invalidateState()
    }
}
