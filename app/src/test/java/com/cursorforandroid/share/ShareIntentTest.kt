package com.cursorforandroid.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.PromptImage
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ShareIntentTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `plain text is the draft`() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Fix the login timeout")
        assertThat(ShareIntent.isShare(intent)).isTrue()
        val draft = ShareIntent.load(context, intent)!!
        assertThat(draft.text).isEqualTo("Fix the login timeout")
        assertThat(draft.attachments).isEmpty()
        assertThat(draft.summary()).isEqualTo("Fix the login timeout")
    }

    @Test
    fun `a subject that is not already in the text is kept as a title`() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Login timeout")
            .putExtra(Intent.EXTRA_TEXT, "https://github.com/acme/app/issues/12")
        assertThat(ShareIntent.textOf(intent)).isEqualTo("Login timeout\n\nhttps://github.com/acme/app/issues/12")
    }

    @Test
    fun `a subject already in the body is not repeated`() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Hello")
            .putExtra(Intent.EXTRA_TEXT, "Hello world")
        assertThat(ShareIntent.textOf(intent)).isEqualTo("Hello world")
    }

    @Test
    fun `an image share becomes a composer attachment`() {
        val uri = pngUri()
        val intent = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "What is in this screenshot?")
        val draft = ShareIntent.load(context, intent)!!
        assertThat(draft.text).isEqualTo("What is in this screenshot?")
        assertThat(draft.attachments).hasSize(1)
        assertThat(PromptImage.isSupported(draft.attachments.single().image.mimeType)).isTrue()
        assertThat(draft.summary()).isEqualTo("What is in this screenshot? · 1 image")
    }

    @Test
    fun `multiple images are attached up to the prompt limit`() {
        val uris = ArrayList<Uri>(6)
        repeat(6) { uris += pngUri("shot-$it.png") }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("image/png")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        val draft = ShareIntent.load(context, intent)!!
        assertThat(draft.attachments).hasSize(PromptImage.MAX_COUNT)
        assertThat(draft.warning).isEqualTo("Only ${PromptImage.MAX_COUNT} images can be attached to a prompt.")
        assertThat(draft.summary()).isEqualTo("${PromptImage.MAX_COUNT} images")
    }

    @Test
    fun `clip data uris are picked up when EXTRA_STREAM is empty`() {
        val uri = pngUri()
        val intent = Intent(Intent.ACTION_SEND).setType("image/png")
        intent.clipData = ClipData.newUri(context.contentResolver, "image", uri)
        val draft = ShareIntent.load(context, intent)!!
        assertThat(draft.attachments).hasSize(1)
    }

    @Test
    fun `a launcher or new-chat intent is not a share`() {
        assertThat(ShareIntent.isShare(Intent(Intent.ACTION_MAIN))).isFalse()
        assertThat(ShareIntent.isShare(Intent("com.cursorforandroid.action.NEW_CHAT"))).isFalse()
        assertThat(ShareIntent.load(context, Intent(Intent.ACTION_MAIN))).isNull()
    }

    @Test
    fun `an empty share is ignored`() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
        assertThat(ShareIntent.load(context, intent)).isNull()
    }

    @Test
    fun `mergeText appends under an existing draft and ignores a blank incoming`() {
        assertThat(ShareDraft.mergeText("", "Hello")).isEqualTo("Hello")
        assertThat(ShareDraft.mergeText("Already writing", "shared")).isEqualTo("Already writing\n\nshared")
        assertThat(ShareDraft.mergeText("Keep me", "   ")).isEqualTo("Keep me")
    }

    @Test
    fun `clearing a share leaves nothing on the intent to read again`() {
        val uri = pngUri("cleared.png")
        val intent = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_TEXT, "Look")
            .putExtra(Intent.EXTRA_SUBJECT, "A title")
            .putExtra(Intent.EXTRA_STREAM, uri)
        intent.clipData = ClipData.newUri(context.contentResolver, "image", uri)

        ShareIntent.clear(intent)

        assertThat(ShareIntent.isShare(intent)).isFalse()
        assertThat(ShareIntent.load(context, intent)).isNull()
        assertThat(ShareIntent.textOf(intent)).isEmpty()
        assertThat(ShareIntent.urisOf(intent)).isEmpty()
    }

    @Test
    fun `clearing leaves an intent that is not a share alone`() {
        val intent = Intent(Intent.ACTION_VIEW).setData("https://cursor.com/agents/bc-1".toUri())
        ShareIntent.clear(intent)
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
    }

    @Test
    fun `the token is stable for the same payload`() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Hi")
        assertThat(ShareIntent.token(intent)).isEqualTo(ShareIntent.token(Intent(intent)))
    }

    private fun pngUri(name: String = "share.png"): Uri {
        val file = File(context.cacheDir, name)
        file.outputStream().use { out ->
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        }
        return file.toUri()
    }
}
