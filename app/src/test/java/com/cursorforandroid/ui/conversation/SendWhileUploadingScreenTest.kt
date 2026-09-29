package com.cursorforandroid.ui.conversation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.pressEnter
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The chat's Send, tapped or pressed as a physical keyboard's Enter, while an attached file is still going up (COMP-7).
 * Idle, the send is taken at once and the message finishes its upload on its bubble. Mid-turn the message would wait
 * in the queue, which needs its files up: Send shows disabled and Enter does nothing until the upload is done.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SendWhileUploadingScreenTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val scene = SendWhileUploadingScene(compose)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(compose).around(scene)

    private fun pendingBubbles(): List<UserMessage> =
        scene.graph.conversations.state(SendWhileUploadingScene.AGENT).value.items.filterIsInstance<UserMessage>().filter { it.isPending }

    private fun accountQueue() = scene.graph.steering.state(SendWhileUploadingScene.AGENT).value.queue

    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        scene.waitFor(timeoutMs, describe = {
            val vm = scene.viewModel
            val state = scene.graph.conversations.state(SendWhileUploadingScene.AGENT).value
            "$what: draft='${vm.draftText.value}' files=${vm.pendingFiles.value.map { it.id }} toast=${vm.toastMessage.value} " +
                "statuses=${vm.outgoingStatuses.value} prompts=${state.items.filterIsInstance<UserMessage>().map { "${it.text}${if (it.isPending) "(pending)" else ""}" }} " +
                "queue=${accountQueue().map { it.text }} sent=${scene.account.sent.map { it.text }} calls=${scene.account.calls.get()} presigns=${scene.presigns.get()}"
        }, condition = condition)
    }

    @Test
    fun `idle, Send is enabled mid-upload and a tap puts the message into a bubble`() {
        scene.show(running = false)
        scene.attachUploading("Here is the crash report")

        scene.sendButton.assertIsEnabled()
        scene.sendButton.performClick()

        waitFor("the bubble", 10_000) { pendingBubbles().any { it.text == "Here is the crash report" } }
        compose.runOnIdle {
            assertThat(scene.viewModel.draftText.value).isEmpty()
            assertThat(scene.viewModel.pendingFiles.value).isEmpty()
            assertThat(scene.viewModel.toastMessage.value).isNull()
        }
        assertThat(scene.account.sent).isEmpty()
        scene.finishUpload()
        waitFor("the account filing it") { scene.account.sent.any { it.text == "Here is the crash report" } }
        assertThat(scene.account.sent).hasSize(1)
    }

    @Test
    fun `idle, a physical Enter mid-upload sends as the button does`() {
        scene.show(running = false)
        scene.attachUploading("Here is the crash report")

        assertThat(scene.field.pressEnter()).isTrue()

        waitFor("the bubble", 10_000) { pendingBubbles().any { it.text == "Here is the crash report" } }
        compose.runOnIdle {
            assertThat(scene.viewModel.draftText.value).isEmpty()
            assertThat(scene.viewModel.toastMessage.value).isNull()
        }
        scene.finishUpload()
        waitFor("the account filing it") { scene.account.sent.any { it.text == "Here is the crash report" } }
        assertThat(scene.account.sent).hasSize(1)
    }

    @Test
    fun `mid-turn, Send shows disabled until the file is up, then queues`() {
        scene.show(running = true)
        scene.account.queueNext = true
        scene.attachUploading("Also look at this crash report")

        scene.sendButton.assertIsNotEnabled()

        scene.finishUpload()
        scene.sendButton.assertIsEnabled()
        scene.sendButton.performClick()
        waitFor("on the card") { accountQueue().any { it.text == "Also look at this crash report" } }
        compose.runOnIdle { assertThat(scene.viewModel.draftText.value).isEmpty() }
        assertThat(pendingBubbles()).isEmpty()
    }

    @Test
    fun `mid-turn, a physical Enter does nothing until the file is up`() {
        scene.show(running = true)
        scene.account.queueNext = true
        scene.attachUploading("Also look at this crash report")

        scene.field.pressEnter()
        compose.waitForIdle()

        compose.runOnIdle {
            assertThat(scene.viewModel.draftText.value).isEqualTo("Also look at this crash report")
            assertThat(scene.viewModel.pendingFiles.value.map { it.id }).containsExactly(scene.file.id)
            assertThat(scene.viewModel.toastMessage.value).isNull()
        }
        assertThat(accountQueue()).isEmpty()
        assertThat(scene.account.calls.get()).isEqualTo(0)

        scene.finishUpload()
        assertThat(scene.field.pressEnter()).isTrue()
        waitFor("on the card") { accountQueue().any { it.text == "Also look at this crash report" } }
        compose.runOnIdle { assertThat(scene.viewModel.draftText.value).isEmpty() }
    }
}
