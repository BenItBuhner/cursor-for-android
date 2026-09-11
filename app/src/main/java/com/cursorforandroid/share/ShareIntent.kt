package com.cursorforandroid.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.ui.components.PendingAttachment
import com.cursorforandroid.ui.components.loadAttachment

/**
 * Reads `ACTION_SEND` / `ACTION_SEND_MULTIPLE` the way the system share sheet delivers them: plain text in
 * `EXTRA_TEXT` (and a title in `EXTRA_SUBJECT` when it is not already in the text), images in `EXTRA_STREAM` and
 * in the intent's [android.content.ClipData]. Unsupported streams are skipped; at most [PromptImage.MAX_COUNT]
 * images are kept.
 */
object ShareIntent {

    fun isShare(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        return action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
    }

    /**
     * Strikes the share off [intent], once it has been drafted or dismissed. An activity answers with the intent
     * that launched it for as long as its task lives, so a share left on one is read again the next time the
     * activity is created — after a relaunch from Recents, or a configuration change it does not absorb — by which
     * time the grants on its images are usually gone and what comes back is the same text, degraded.
     */
    fun clear(intent: Intent?) {
        if (!isShare(intent) || intent == null) return
        intent.action = null
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.removeExtra(Intent.EXTRA_SUBJECT)
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.clipData = null
    }

    /** Stable fingerprint of the payload, used to ignore a recreate redelivering the same share. */
    fun token(intent: Intent): String {
        val text = textOf(intent)
        val uris = urisOf(intent).joinToString { it.toString() }
        return "${intent.action}|${intent.type}|$text|$uris"
    }

    /**
     * Turns [intent] into a [ShareDraft] with images already decoded for the composer strip. Null when there is
     * nothing the composers can draft (no text, no readable image).
     */
    fun load(context: Context, intent: Intent): ShareDraft? {
        if (!isShare(intent)) return null
        val text = textOf(intent)
        val uris = urisOf(intent)
        val fallbackMime = intent.type?.lowercase()?.takeIf { it.startsWith("image/") }
        val attachments = ArrayList<PendingAttachment>(minOf(uris.size, PromptImage.MAX_COUNT))
        val failures = ArrayList<String>()
        for (uri in uris) {
            if (attachments.size >= PromptImage.MAX_COUNT) break
            if (!looksLikeImage(context, uri, fallbackMime)) continue
            loadAttachment(context, uri, fallbackMime)
                .onSuccess { attachments += it }
                .onFailure { e -> e.message?.let(failures::add) }
        }
        val overflow = uris.size > PromptImage.MAX_COUNT
        val warning = when {
            overflow -> "Only ${PromptImage.MAX_COUNT} images can be attached to a prompt."
            failures.isNotEmpty() -> failures.first()
            else -> null
        }
        val draft = ShareDraft(
            generation = 0,
            text = text,
            attachments = attachments,
            warning = warning,
        )
        return draft.takeUnless { it.isEmpty }
    }

    fun textOf(intent: Intent): String {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: clipText(intent)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val body = text?.trim().orEmpty()
        return when {
            body.isNotEmpty() && subject.isNotEmpty() && !body.contains(subject) -> "$subject\n\n$body"
            body.isNotEmpty() -> body
            else -> subject
        }
    }

    fun urisOf(intent: Intent): List<Uri> {
        val fromExtra = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamUri(intent))
            Intent.ACTION_SEND_MULTIPLE -> streamUris(intent)
            else -> emptyList()
        }
        val fromClip = intent.clipData?.let { clip ->
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }.orEmpty()
        return (fromExtra + fromClip).distinct()
    }

    private fun clipText(intent: Intent): String? {
        val clip = intent.clipData ?: return null
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val text = item.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    private fun looksLikeImage(context: Context, uri: Uri, fallbackMime: String?): Boolean {
        val mime = context.contentResolver.getType(uri)?.lowercase() ?: fallbackMime
        return mime == null || mime == "*/*" || mime.startsWith("image/")
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamUris(intent: Intent): List<Uri> {
        val list = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
        return list.orEmpty()
    }
}
