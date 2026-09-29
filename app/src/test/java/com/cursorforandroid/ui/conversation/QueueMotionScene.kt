package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.StagedAttachments
import com.cursorforandroid.domain.ConversationControls
import com.cursorforandroid.domain.QueuePlacement
import kotlinx.coroutines.runBlocking
import com.cursorforandroid.domain.DraftFile
import com.cursorforandroid.domain.DraftImage
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.ComposerAnchor
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.components.PendingAttachment
import com.cursorforandroid.ui.components.PendingFile
import com.cursorforandroid.ui.components.QueueDeliveries
import com.cursorforandroid.ui.components.QueueFlights
import com.cursorforandroid.ui.components.SendFlight
import com.cursorforandroid.ui.components.SendLanding
import com.cursorforandroid.ui.components.SendMotion
import com.cursorforandroid.ui.components.SendMotionHost
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A chat's foot while a run is under way, as the conversation screen lays it out: the transcript's last bubbles, the
 * device's queue card (and the account's, in Extended mode) over the composer, each row an end of the send's flight.
 * The tap and the run's taking a queued message are made the way the screen makes them ([sendQueued], [deliver]), on
 * a clock the test holds.
 */
class QueueMotionScene(private val compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>) {
    val anchor = ComposerAnchor()
    val flights = QueueFlights()
    var composerText by mutableStateOf("")
    val images = mutableStateListOf<PendingAttachment>()
    val files = mutableStateListOf<PendingFile>()
    val messages = mutableStateListOf(
        UserMessage("u-1", "Profile the cold start and tell me where the time goes."),
    )
    val queue = mutableStateListOf<QueuedFollowUp>()
    /** The account's queue as last read (`ListPendingFollowups`): its word on each message, names and counts. */
    val account = mutableStateListOf<PendingFollowup>()
    /** This device's own queued messages (`QueuePlacement.waiting`), with its copies of what each carries. */
    val waiting = mutableStateListOf<PendingFollowup>()
    /** The [waiting] rows whose request is still out. */
    val sending = mutableStateListOf<String>()
    /** The queued rows whose up arrow was tapped: a steer into the running turn, which the run goes on through. */
    val steered = mutableListOf<String>()
    private val thumbnails = mutableStateMapOf<String, ImageBitmap>()
    var scrolledAway = false
    /** The reader's choice for a long queue, as the device keeps it (`PreferencesStore.queueStacked`): stacked unless opened. */
    var stacked by mutableStateOf(true)
    /** Whether the stack's springs run (the system's animator scale above 0). */
    var stackAnimates = true
    /** How the stack moves: its own spring, unless a test holds it part of the way. */
    var stackSpec by mutableStateOf<AnimationSpec<Float>?>(null)
    /** Bumped to compose the queue afresh, as the screen does coming back to the chat. */
    var generation by mutableStateOf(0)
    /** Extended mode: the composer says a send while the agent works queues on the account. */
    var onAccount = false
    val store by lazy { AttachmentStore(compose.activity) }
    private val staged = HashMap<String, StagedAttachments>()

    /** The account's card as the screen draws it: the account's list projected through this device's placement. */
    val accountRows: List<PendingFollowup>
        get() = ConversationControls(queue = account.toList()).placed(QueuePlacement(waiting = waiting.toList(), sendingIds = sending.toSet())).queue

    /** The screen's fades of sent bubbles, when [show] was asked for them. */
    var sentFades: SentFades? = null
        private set
    /** Whether the fades wait out the send's flight, as the screen's do; off, one runs under the copy as it once did. */
    var sentFadesSeeFlights = true

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Content(motion: SendMotion, withSentFades: Boolean = false) {
        val fades = if (withSentFades) rememberSentFades("queue-scene", animatorsEnabled = { true }, motion = motion.takeIf { sentFadesSeeFlights }) else null
        fades?.look(messages.toList())
        sentFades = fades
        CursorTheme(mode = ThemeMode.Dark) {
            CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls(), LocalSentFades provides fades) {
                Box(Modifier.testTag(Frame).fillMaxWidth().height(640.dp).background(CursorTheme.colors.canvas)) {
                    SendMotionHost(motion) {
                        Column(Modifier.fillMaxSize().padding(16.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                for (message in messages) key(message.id) { TimelineItemView(message) }
                            }
                            Spacer(Modifier.weight(1f))
                            val card = ConversationControls(queue = account.toList()).placed(QueuePlacement(waiting = waiting.toList(), sendingIds = sending.toSet()))
                            QueueDeliveries(
                                flights = flights,
                                rows = LinkedHashMap<String, String>().apply {
                                    queue.forEach { put(it.id, it.previewText) }
                                    card.queue.forEach { put(it.id, it.previewText) }
                                },
                                transcript = messages.mapTo(HashSet()) { it.id },
                                scrolledAway = { scrolledAway },
                            )
                            val device = queue.toList()
                            val onCard = card.queue
                            if (device.isNotEmpty() || onCard.isNotEmpty()) key(generation) {
                                QueueStack(
                                    keys = device.map { "device:${it.id}" } + onCard.map { "account:${it.id}" },
                                    stacked = stacked,
                                    onStackedChange = { stacked = it },
                                    modifier = Modifier.padding(bottom = 4.dp),
                                    animate = { stackAnimates },
                                    animationSpec = stackSpec ?: StackSpring,
                                ) { index, face ->
                                    if (index < device.size) {
                                        QueuedFollowUpCard(device[index], index + 1, device.size, thumbnails.toMap(), {}, { steered += it.id }, { removed -> flights.dismiss(removed.id); queue.remove(removed) }, flights, face, steers = true)
                                    } else {
                                        val at = index - device.size
                                        AccountQueueCard(onCard[at], at + 1, onCard.size, card.inFlightQueueIds, { steered += it.id }, {}, { _, _ -> }, { _, _ -> }, true, null, flights, face)
                                    }
                                }
                            }
                            ComposerBox(
                                value = composerText,
                                onValueChange = {},
                                placeholder = if (onAccount) "Follow up (queues on your account)…" else "Follow up (sends when the turn ends)…",
                                onSend = {},
                                canSend = composerText.isNotBlank() || images.isNotEmpty() || files.isNotEmpty(),
                                isRunning = true,
                                onStop = {},
                                plusMenu = ComposerMenuActions(onPickMedia = {}, onPickFiles = {}),
                                attachments = images.toList(),
                                onRemoveAttachment = {},
                                files = files.toList(),
                                onRemoveFile = {},
                                modelLabel = "Claude Fable 5.1",
                                onModel = {},
                                modifier = Modifier.fillMaxWidth(),
                                anchor = anchor,
                            )
                        }
                    }
                }
            }
        }
    }

    fun show(motion: SendMotion, withSentFades: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent { Content(motion, withSentFades) }
        frames(64)
    }

    /**
     * The run taking device-queue row [id], as the device's queue sends it: the row leaves the card and the message is
     * shown at once as the sending bubble [bubble], which the send's request later files ([replace]).
     */
    fun deliverSending(id: String, bubble: String) {
        val text = queue.first { it.id == id }.previewText
        compose.runOnUiThread {
            queue.removeAll { it.id == id }
            messages += UserMessage(bubble, text, isPending = true)
        }
        frame()
    }

    /** The bubble [id] replaced, in one frame, by [with]: its filing, or the server's copy taking its place. */
    fun replace(id: String, with: UserMessage) {
        compose.runOnUiThread { messages[messages.indexOfFirst { it.id == id }] = with }
    }

    /**
     * On until a change made on the UI thread is composed, applied and laid out: the first frame only hears of the
     * write (the held clock sends the apply notification with it), the second composes it.
     */
    fun frame() {
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    fun frames(millis: Long) {
        var left = millis
        while (left > 0) {
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            left -= 16
        }
    }

    /**
     * Two pictures (an orange landscape, a blue portrait) and a PDF in the composer, as the pickers leave them; returned
     * as the bubble the message is filed under will list them, their copies written where the transcript keeps them.
     */
    fun attach(): List<MessageAttachment> {
        val dir = File(compose.activity.cacheDir, "sent").apply { mkdirs() }
        val sent = listOf(Triple(160, 120, android.graphics.Color.rgb(214, 108, 52)), Triple(120, 200, android.graphics.Color.rgb(52, 120, 246))).mapIndexed { index, (w, h, color) ->
            val shot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            images += PendingAttachment("img-$index", PromptImage(png(shot), "image/png"), shot.asImageBitmap())
            MessageAttachment(File(dir, "img-$index.png").apply { writeBytes(png(shot)) }.path, w, h)
        }
        val spec = PromptFile(ByteArray(2_400 * 1024), "Q3-header-spec.pdf", "application/pdf")
        files += PendingFile("f-1", spec)
        val copy = File(dir, spec.name).apply { writeBytes(ByteArray(16)) }
        return sent + MessageAttachment(copy.path, 0, 0, name = spec.name, mimeType = spec.mimeType, sizeBytes = spec.sizeBytes.toLong())
    }

    /**
     * The tap while the agent runs, as the screen makes it: the composer's text and chips lifted off for the queue card
     * and the composer emptied. On the device's queue the message is on the card as row [id] in the same frame
     * (enqueueing is synchronous). On the account's ([onAccount], Extended mode: what a phone signed in runs), as
     * `accountRoute(queued = true)` makes it: the composer's attachments are staged off the main thread
     * ([AttachmentStore.stage]) and the message is this device's own row [id] [stagedAfter] ms on, its request still
     * out, with its copies for the row's tiles; the account's list names it later ([accountTakes]).
     */
    fun sendQueued(motion: SendMotion, id: String, onAccount: Boolean = false, stagedAfter: Long = 32L): SendFlight? {
        val text = composerText.trim()
        var flight: SendFlight? = null
        var drafts = emptyList<DraftImage>()
        var attached = emptyList<DraftFile>()
        compose.runOnUiThread {
            val standing = queue.mapTo(HashSet()) { it.id } + accountRows.map { it.id }
            flight = motion.depart(anchor.takeoff(), text, excluded = standing, landing = SendLanding.Queue)
            drafts = images.map { DraftImage(it.id, it.image) }
            images.forEach { image -> image.thumbnail?.let { thumbnails[image.id] = it } }
            attached = files.map { DraftFile(it.id, it.file) }
            if (!onAccount) queue += QueuedFollowUp(id, text, images = drafts, files = attached, queuedAtMillis = 0L)
            composerText = ""
            images.clear()
            files.clear()
        }
        if (onAccount) {
            frame()
            val set = runBlocking { store.stage(drafts.map { it.image }, attached.map { it.file }) }
            staged[id] = set
            frames(stagedAfter)
            compose.runOnUiThread {
                waiting += ownRow(id, text, set.attachments)
                sending += id
            }
        }
        frame()
        return flight
    }

    /** This device's row for a message it queued on the account, as `ConversationRepository.queuePlacement` makes it. */
    fun ownRow(id: String, text: String, carried: List<MessageAttachment>) = PendingFollowup(
        id = id,
        text = text,
        files = carried.filter { it.isFile }.map { com.cursorforandroid.domain.PendingAttachment(it.name ?: "Document", it.mimeType.orEmpty()) },
        imageCount = carried.count { !it.isFile },
        attachments = carried,
    )

    /**
     * On, a frame at a time, until every picture on the account's card has its tile's preview: decoded off the main
     * thread, as on a phone, which a held clock does not wait for.
     */
    fun awaitTilePreviews() {
        val paths = accountRows.flatMap { it.attachments }.filterNot { it.isFile }.map { it.path }
        repeat(400) {
            if (paths.all { AttachmentImages.get(tileThumbnailKey(it)) != null }) {
                frame()
                return
            }
            Thread.sleep(5)
            frames(16)
        }
        error("the tiles' previews were never decoded: $paths")
    }

    /** The account's answer to row [id]'s request, and its list read again: the message named there in the account's words (names and a count). */
    fun accountTakes(id: String) {
        compose.runOnUiThread {
            val own = waiting.first { it.id == id }
            sending.remove(id)
            account += PendingFollowup(id, own.text, files = own.files, imageCount = own.imageCount)
        }
        frame()
    }

    /**
     * The run taking queued row [id]: the row leaves the card, and its bubble [bubble] is filed in the same frame
     * (the account's queue) or, with [filedAfter], that many milliseconds on (the device's, whose bubble waits for the run).
     * A message queued on the account from here is filed with the copies it was staged with, which then move under
     * the run (`commitFiled`: the bubble names them there a frame on, a path read in between following the move);
     * returns what the bubble lists at the end.
     */
    fun deliver(id: String, bubble: String, filedAfter: Long = 0L, attachments: List<MessageAttachment> = emptyList()): List<MessageAttachment> {
        val text = queue.firstOrNull { it.id == id }?.previewText ?: accountRows.first { it.id == id }.previewText
        val set = staged.remove(id)
        val filed = set?.attachments ?: attachments
        compose.runOnUiThread {
            queue.removeAll { it.id == id }
            account.removeAll { it.id == id }
            waiting.removeAll { it.id == id }
            sending.remove(id)
            if (filedAfter == 0L) messages += UserMessage(bubble, text, attachments = filed)
        }
        frame()
        if (filedAfter > 0L) {
            frames(filedAfter)
            compose.runOnUiThread { messages += UserMessage(bubble, text, attachments = filed) }
            frame()
        }
        if (set == null) return filed
        val moved = runBlocking { store.commit(AgentId, bubble, set) }
        compose.runOnUiThread {
            val at = messages.indexOfFirst { it.id == bubble }
            messages[at] = messages[at].copy(attachments = moved)
        }
        frame()
        return moved
    }

    /** The window as drawn now, into [file]: drawn here rather than through captureToImage, which waits on the held clock. */
    fun drawTo(file: File) {
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    companion object {
        const val Frame = "queue_motion_frame"
        const val AgentId = "bc-queue-motion"
    }
}
