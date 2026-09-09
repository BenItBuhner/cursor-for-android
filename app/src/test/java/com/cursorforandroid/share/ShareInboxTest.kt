package com.cursorforandroid.share

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ShareInboxTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a text share is offered without a target until one is picked, then consumed`() = runBlocking {
        val inbox = ShareInbox(context)
        inbox.receive(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Look at this"))
        awaitUntil { inbox.offer.value != null && !inbox.loading.value }

        val first = inbox.offer.value!!
        assertThat(first.text).isEqualTo("Look at this")
        assertThat(first.target).isNull()
        assertThat(first.attachments).isEmpty()

        inbox.setTarget(ShareTarget.NewChat)
        assertThat(inbox.offer.value?.target).isEqualTo(ShareTarget.NewChat)

        inbox.consume(first.generation)
        assertThat(inbox.offer.value).isNull()
    }

    @Test
    fun `the same intent is not offered again while it is still pending`() = runBlocking {
        val inbox = ShareInbox(context)
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Once")
        inbox.receive(intent)
        awaitUntil { inbox.offer.value != null }
        val generation = inbox.offer.value!!.generation
        inbox.receive(Intent(intent))
        delay(50)
        assertThat(inbox.offer.value!!.generation).isEqualTo(generation)
    }

    @Test
    fun `an identical share is accepted again after the first one is dismissed`() = runBlocking {
        val inbox = ShareInbox(context)
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Again")
        inbox.receive(intent)
        awaitUntil { inbox.offer.value != null }
        val first = inbox.offer.value!!.generation
        inbox.clear()
        inbox.receive(Intent(intent))
        awaitUntil { inbox.offer.value != null && inbox.offer.value!!.generation != first }
        assertThat(inbox.offer.value!!.text).isEqualTo("Again")
    }

    @Test
    fun `picking an existing chat records that destination`() = runBlocking {
        val inbox = ShareInbox(context)
        inbox.receive(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Follow up"))
        awaitUntil { inbox.offer.value != null }
        inbox.setTarget(ShareTarget.Chat("bc-demo-0002"))
        assertThat(inbox.offer.value?.target).isEqualTo(ShareTarget.Chat("bc-demo-0002"))
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }
}
