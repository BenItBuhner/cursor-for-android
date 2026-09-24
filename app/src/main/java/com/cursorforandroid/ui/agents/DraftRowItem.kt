package com.cursorforandroid.ui.agents

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.data.local.DraftStore
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorMenu
import com.cursorforandroid.ui.components.CursorMenuItem
import com.cursorforandroid.ui.components.Dot
import com.cursorforandroid.ui.components.ModePills
import com.cursorforandroid.ui.components.Haptic
import com.cursorforandroid.ui.components.rememberHaptics
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.TimeFormat

/** A new chat written and not sent, as the sidebar lists it above its groups. */
data class DraftRow(
    val id: String,
    /** What was written, from its first line; for an attachment-only draft, what it carries. */
    val title: String,
    val repoShortName: String?,
    val updatedAtMillis: Long,
    /** Its launch did not go through: the glyph carries the error dot, as a failed chat's row does. */
    val failed: Boolean,
) {
    companion object {
        fun of(record: DraftStore.Record): DraftRow = DraftRow(
            id = record.id,
            title = titleOf(record),
            repoShortName = record.repoUrl?.takeUnless { record.noRepo }?.let { Repository(it).shortName },
            updatedAtMillis = record.updatedAtMillis,
            failed = record.error != null,
        )

        /**
         * The drafts the sidebar lists, most recent first: written into, not on their way out as a chat, and not the
         * one [open] in the New Chat composer while that composer is on screen — that one is being written, not left.
         */
        fun listed(records: List<DraftStore.Record>, open: String?): List<DraftRow> =
            records.filter { it.hasContent && it.launchedAs == null && it.id != open }.sortedByDescending { it.updatedAtMillis }.map(::of)

        private fun titleOf(record: DraftStore.Record): String {
            // `/multitask` is the composer's pill, not words the reader wrote.
            val line = ModePills.present(record.prompt).text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            if (line != null) return line
            val images = record.images.size
            val files = record.files.size
            return when {
                images > 0 && files > 0 -> "${images + files} attachments"
                files == 1 -> record.files.single().name
                files > 1 -> "$files files"
                images == 1 -> "Image"
                else -> "$images images"
            }
        }
    }
}

/**
 * A draft's row in the sidebar, built as a chat's row is ([AgentRowItem]): the same height, insets, selection and
 * trailing metadata (the repository, the age), the state slot holding the draft glyph — with the error dot when its
 * launch did not go through — so a draft reads as a chat that has not been sent. Tapping it opens it in the New Chat
 * composer; long-pressing it offers what a draft can be asked, which is to be deleted.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DraftRowItem(
    row: DraftRow,
    prefs: ListPreferences,
    onOpen: (DraftRow) -> Unit,
    onDelete: (DraftRow) -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = AppClock.now(),
    selected: Boolean = false,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.base
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Box(modifier.fillMaxWidth().padding(horizontal = CursorDimens.selectionInset).testTag("draft-row")) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (selected) colors.fillSoft else Color.Transparent, shape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = ripple(color = colors.base),
                    onClick = { onOpen(row) },
                    onLongClick = { haptics.perform(Haptic.LongPress); menuOpen = true },
                )
                .height(CursorDimens.sidebarRow)
                .padding(start = 8.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DraftGlyph(failed = row.failed)
            Spacer(Modifier.width(10.dp))
            Text(row.title, style = type.row, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val trailing = buildList {
                if (prefs.showWorkspace) row.repoShortName?.let { add(it) }
                if (prefs.showRuntime) add(TimeFormat.relativeShort(row.updatedAtMillis, nowMillis))
            }
            if (trailing.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(trailing.joinToString(" · "), style = type.base, color = colors.textQuaternary, maxLines = 1)
            }
        }
        CursorMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            CursorMenuItem("Delete draft", CursorIcons.Trash) { menuOpen = false; onDelete(row) }
        }
    }
}

/** The state slot's glyph for a draft: the pencil, in the branch glyph's tone; the error dot on it after a failed launch. */
@Composable
private fun DraftGlyph(failed: Boolean) {
    val colors = CursorTheme.colors
    Box(Modifier.size(CursorDimens.glyph), contentAlignment = Alignment.Center) {
        Icon(CursorIcons.Draft, "Draft", tint = colors.iconTertiary, modifier = Modifier.size(16.dp))
        if (failed) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(8.dp).background(colors.sidebar, CircleShape).padding(1.5.dp)) {
                Dot(colors.red, size = 5.dp)
            }
        }
    }
}
