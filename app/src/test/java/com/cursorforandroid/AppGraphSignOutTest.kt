package com.cursorforandroid

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.conversation.AttachmentImages
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the sign-out wipe has to reach, beyond the caches on disk. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppGraphSignOutTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `signing out drops the attachment pixels decoded for the account`() = runBlocking {
        val graph = app.appGraph
        graph.session.enterDemo()
        AttachmentImages.put("/data/attachments/bc-1/shot.png@128", Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap())
        assertThat(AttachmentImages.get("/data/attachments/bc-1/shot.png@128")).isNotNull()

        graph.signOut()

        assertThat(AttachmentImages.get("/data/attachments/bc-1/shot.png@128")).isNull()
    }
}
