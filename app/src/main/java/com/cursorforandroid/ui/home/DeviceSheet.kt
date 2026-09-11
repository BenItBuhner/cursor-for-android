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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.DeviceOption
import com.cursorforandroid.domain.DeviceSection
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.KnownDevices
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorSheet
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.SheetHeader
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The composer's device picker: Cloud (always, and the default), then My Machines that are online or that past
 * chats ran on, then team pools. There is no "this device" row — an Android client cannot host the agent.
 * A name typed into the field that matches none of the rows is offered as a machine and as a pool, the way the
 * branch picker offers an unseen branch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceSheet(
    devices: List<DeviceOption>,
    selected: DeviceTarget,
    loading: Boolean,
    onSelect: (DeviceTarget) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var query by rememberSaveable { mutableStateOf("") }
    CursorSheet(onDismiss = onDismiss) { dismiss ->
        fun pick(device: DeviceTarget) {
            onSelect(device)
            dismiss()
        }
        SheetHeader("Device") {
            if (loading) SpinnerRing(modifier = Modifier.padding(end = 8.dp)) else FlatIconButton(CursorIcons.Refresh, "Refresh devices", onClick = onRefresh)
        }
        SheetSearchField(value = query, onValueChange = { query = it }, placeholder = "Filter devices")
        Spacer(Modifier.height(6.dp))
        val typed = query.trim()
        fun matches(option: DeviceOption) = typed.isEmpty() ||
            option.target.label.contains(typed, ignoreCase = true) ||
            option.subtitle.orEmpty().contains(typed, ignoreCase = true)
        val visible = devices.filter(::matches)
        val selectedKey = DeviceOption.keyOf(selected)
        val listedKeys = devices.map { it.key }.toSet()
        val currentUnlisted = !selected.isCloud && selectedKey !in listedKeys &&
            (typed.isEmpty() || selected.label.contains(typed, ignoreCase = true))
        val typedAsNew = typed.isNotEmpty() &&
            visible.none { it.target.apiName.equals(typed, ignoreCase = true) } &&
            !selected.apiName.equals(typed, ignoreCase = true)
        val machines = visible.filter { it.section == DeviceSection.Machines }
        val pools = visible.filter { it.section == DeviceSection.Pools }
        val cloudVisible = visible.any { it.section == DeviceSection.Cloud } || (typed.isEmpty() && devices.none { it.section == DeviceSection.Cloud })
        val nothingToShow = typed.isNotEmpty() && visible.isEmpty() && !currentUnlisted && !typedAsNew
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (cloudVisible || typed.isEmpty()) {
                item("cloud") {
                    val cloud = devices.firstOrNull { it.section == DeviceSection.Cloud } ?: KnownDevices.cloud
                    SheetRow(
                        title = cloud.target.label,
                        subtitle = cloud.subtitle,
                        checked = selected.isCloud,
                        icon = CursorIcons.Cloud,
                    ) { pick(DeviceTarget.Cloud) }
                    HairlineDivider(Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
            }
            if (currentUnlisted) {
                item("current") {
                    SheetRow(
                        title = selected.label,
                        subtitle = null,
                        checked = true,
                        icon = deviceIcon(selected),
                    ) { pick(selected) }
                }
            }
            if (machines.isNotEmpty()) {
                item("machines-label") { SheetSectionLabel("My machines") }
                items(machines, key = { it.key }) { option ->
                    SheetRow(
                        title = option.target.label,
                        subtitle = option.subtitle,
                        checked = option.key == selectedKey,
                        icon = CursorIcons.Desktop,
                    ) { pick(option.target) }
                }
            }
            if (pools.isNotEmpty()) {
                item("pools-label") { SheetSectionLabel("Team pools") }
                items(pools, key = { it.key }) { option ->
                    SheetRow(
                        title = option.target.label,
                        subtitle = option.subtitle,
                        checked = option.key == selectedKey,
                        icon = CursorIcons.Layers,
                    ) { pick(option.target) }
                }
            }
            if (typedAsNew) {
                item("typed-machine") {
                    SheetRow(
                        title = typed,
                        subtitle = "Use as a machine",
                        checked = false,
                        icon = CursorIcons.Plus,
                    ) { pick(DeviceTarget.machine(typed)) }
                }
                item("typed-pool") {
                    SheetRow(
                        title = typed,
                        subtitle = "Use as a team pool",
                        checked = false,
                        icon = CursorIcons.Plus,
                    ) { pick(DeviceTarget.pool(typed)) }
                }
            }
            if (nothingToShow) {
                item("no-match") {
                    Text(
                        "No devices match \"$typed\"",
                        style = type.small,
                        color = colors.textQuaternary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
            if (typed.isEmpty()) {
                item("note") {
                    Text(
                        "This phone can't run an agent itself. Cloud is Cursor's hosted VM; a machine or team pool is one you've left online.",
                        style = type.small,
                        color = colors.textQuaternary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

internal fun deviceIcon(target: DeviceTarget): ImageVector = when (target.type) {
    EnvType.MACHINE -> CursorIcons.Desktop
    EnvType.POOL -> CursorIcons.Layers
    else -> CursorIcons.Cloud
}
