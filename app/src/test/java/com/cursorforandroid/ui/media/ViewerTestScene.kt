package com.cursorforandroid.ui.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.ui.components.AudioChip
import com.cursorforandroid.ui.components.ImageBlock
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.components.VideoBlock
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import okhttp3.OkHttpClient
import java.io.File

/** Pictures on this device for the viewer's tests, and the scene that shows them as a transcript would. */
internal object ViewerFixtures {
    const val AGENT = "bc-viewer-test"

    /** A [width] x [height] PNG under the app's files, a flat colour with a lighter disc, as a `file://` reference. */
    fun png(name: String, width: Int, height: Int, rgb: Int): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(rgb)
        val paint = Paint().apply { isAntiAlias = true; color = AndroidColor.argb(255, 235, 235, 235) }
        canvas.drawCircle(width * 0.3f, height * 0.4f, minOf(width, height) * 0.18f, paint)
        paint.color = AndroidColor.argb(255, 40, 40, 40)
        canvas.drawRect(width * 0.55f, height * 0.55f, width * 0.9f, height * 0.85f, paint)
        val file = File(context.filesDir, "viewer-test/$name").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "file://${file.absolutePath}"
    }

    /** A file that stands for a recording: the fake player never reads it, the loader only needs a path to name. */
    fun mp4(name: String): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "viewer-test/$name").apply { parentFile?.mkdirs() }
        file.writeBytes(ByteArray(64))
        return "file://${file.absolutePath}"
    }

    fun loader(): MediaLoader {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return MediaLoader(context, OkHttpClient(), ArtifactRepository(api = { FakeCursorApi() }))
    }
}

/**
 * The viewer hosted over a column of figures, the way the app hosts it over the shell: each entry as the block the
 * transcript would draw for it, in the theme, with ripples off so a frame is reproducible. [autoHide] null keeps
 * the chrome until it is tapped away; the player is [playerFactory]'s.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewerScene(
    state: MediaViewerState,
    loader: MediaLoader,
    entries: List<MediaEntry>,
    autoHide: Long? = null,
    playerFactory: ((Context) -> Player)? = null,
    agentId: String = ViewerFixtures.AGENT,
    canReadStores: Boolean = false,
    content: @Composable (() -> Unit)? = null,
) {
    val media = remember(loader, entries, agentId, canReadStores) { MarkdownMediaContext(agentId, loader, canReadStores = canReadStores, entries = { entries }) }
    CursorTheme(mode = ThemeMode.Dark) {
        CompositionLocalProvider(
            LocalRippleConfiguration provides null,
            LocalVideoPlayerFactory provides (playerFactory ?: LocalVideoPlayerFactory.current),
        ) {
            MediaViewerHost(state, loader, autoHideControlsMillis = autoHide) {
                CompositionLocalProvider(LocalMarkdownMedia provides media) {
                    if (content != null) {
                        content()
                    } else {
                        Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            entries.forEach { entry ->
                                when (entry.kind) {
                                    MediaEntry.Kind.Image -> ImageBlock(entry.src, entry.caption, heightCap = 150.dp)
                                    MediaEntry.Kind.Video -> VideoBlock(entry.src, poster = null, heightCap = 150.dp)
                                    MediaEntry.Kind.Audio -> AudioChip(entry.src, entry.fileName, subtitle = "Audio")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
