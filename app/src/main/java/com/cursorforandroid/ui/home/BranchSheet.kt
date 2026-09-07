package com.cursorforandroid.ui.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.BranchOption
import com.cursorforandroid.domain.KnownBranches
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorSheet
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.SheetHeader
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The composer's branch picker: the repository's default branch, then every branch agents in the repository started
 * from or pushed ([branches], see `KnownBranches`), filtered by the search field. The Cloud Agents API cannot list a
 * repository's branches, so a name typed into the field that matches none of them is offered as a row of its own —
 * that is the only way to start from a branch no agent has touched yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BranchSheet(
    repo: Repository?,
    branches: List<BranchOption>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var query by remember { mutableStateOf("") }
    CursorSheet(onDismiss = onDismiss) { dismiss ->
        // Picking a row plays the sheet's hide animation before the selection is applied.
        fun pick(ref: String) {
            onSelect(ref)
            dismiss()
        }
        SheetHeader("Branch")
        SheetSearchField(value = query, onValueChange = { query = it }, placeholder = "Search branches")
        Spacer(Modifier.height(6.dp))
        val typed = query.trim()
        val current = selected.trim()
        fun matches(name: String) = typed.isEmpty() || name.contains(typed, ignoreCase = true)
        val visible = branches.filter { matches(it.name) }
        // The selection always has a row, even when no agent has used it (typed earlier, or restored from the last launch).
        val currentUnlisted = current.isNotEmpty() && branches.none { it.name == current } && matches(current)
        val unlisted = typed.isNotEmpty() && typed != current && branches.none { it.name == typed }
        val offerTyped = unlisted && KnownBranches.isPlausibleRef(typed)
        val nothingToShow = typed.isNotEmpty() && visible.isEmpty() && !currentUnlisted && !offerTyped
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (typed.isEmpty()) {
                item("default") {
                    SheetRow(title = "Default branch", subtitle = "The repository's default branch", checked = current.isEmpty(), icon = CursorIcons.GitBranch) { pick("") }
                    HairlineDivider(Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
            }
            if (currentUnlisted) {
                item("current") {
                    SheetRow(title = current, subtitle = null, checked = true, icon = CursorIcons.GitBranch) { pick(current) }
                }
            }
            items(visible, key = { "branch:${it.name}" }) { branch ->
                SheetRow(title = branch.name, subtitle = branch.description, checked = branch.name == current, icon = CursorIcons.GitBranch) { pick(branch.name) }
            }
            if (offerTyped) {
                item("typed") {
                    SheetRow(title = typed, subtitle = "Use this branch name", checked = false, icon = CursorIcons.Plus) { pick(typed) }
                }
            }
            if (nothingToShow) {
                item("invalid") {
                    Text("\u201C$typed\u201D can't be a branch name", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
            if (repo != null) {
                item("note") {
                    Text(
                        "Cursor's API can't list a repository's branches, so these are the ones your agents started from or pushed in ${repo.shortName}. Search for any other branch by name to use it.",
                        style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
