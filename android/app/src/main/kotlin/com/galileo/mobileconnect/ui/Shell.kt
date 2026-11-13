package com.galileo.mobileconnect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galileo.mobileconnect.core.api.Role as ObserverRole
import com.galileo.mobileconnect.core.control.OperationState
import com.galileo.mobileconnect.core.link.ConnectionState
import com.galileo.mobileconnect.core.state.bool
import com.galileo.mobileconnect.core.state.get
import com.galileo.mobileconnect.core.state.string
import com.galileo.mobileconnect.core.session.SessionSnapshot
import com.galileo.mobileconnect.ui.screens.EquipmentScreen
import com.galileo.mobileconnect.ui.screens.FlipScreen
import com.galileo.mobileconnect.ui.screens.FocusScreen
import com.galileo.mobileconnect.ui.screens.GuidingScreen
import com.galileo.mobileconnect.ui.screens.ImagingScreen
import com.galileo.mobileconnect.ui.screens.OverviewScreen
import com.galileo.mobileconnect.ui.screens.SafetyScreen
import com.galileo.mobileconnect.ui.screens.SessionsScreen
import com.galileo.mobileconnect.ui.screens.SettingsScreen
import com.galileo.mobileconnect.ui.sky.SkyScreen
import com.galileo.mobileconnect.ui.screens.SolveScreen
import com.galileo.mobileconnect.ui.theme.LocalTheme
import kotlinx.serialization.json.JsonArray

enum class Area(val label: String, val glyph: String) {
    Overview("Overview", "▤"),
    Equipment("Equipment", "⌖"),
    Sky("Sky", "✦"),
    Imaging("Imaging", "◉"),
    Focus("Focus", "◎"),
    Solve("Solve", "⌗"),
    Guiding("Guiding", "⊕"),
    Flip("Flip", "⇄"),
    Sessions("Sessions", "☰"),
    Safety("Safety", "⚑"),
    Settings("Settings", "⚙");

    companion object {
        fun of(label: String?) = entries.firstOrNull { it.label == label }
    }
}

/** A-SHELL: what is on every screen is here, so it can't be forgotten on one of them. */
@Composable
fun ShellView() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val wide = isWide()
    var selection by remember { mutableStateOf(Area.Overview) }
    var menuOpen by remember { mutableStateOf(false) }

    val wanted = app.requestedArea
    LaunchedEffect(wanted) {
        Area.of(wanted)?.let {
            selection = it
            app.requestedArea = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(selection, onMenu = { menuOpen = !menuOpen }, showsMenuButton = wide && !app.menuPinned)
        ConnectionBanner()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!wide) {
                Column(Modifier.fillMaxSize()) {
                    AreaStrip(selection) { selection = it }
                    Box(Modifier.weight(1f).fillMaxWidth()) { Detail(selection) }
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    if (app.menuPinned) AreaMenu(selection, onSelect = { selection = it }, onClose = {})
                    Box(Modifier.weight(1f).fillMaxHeight()) { Detail(selection) }
                }
                if (menuOpen && !app.menuPinned) {
                    // Slides over the screen and goes away by itself, so that the screen keeps its width.
                    Box(
                        Modifier.fillMaxSize().background(theme.veil(0.6f)).clickable(
                            role = Role.Button, onClickLabel = "Close menu",
                        ) { menuOpen = false }.testTag("menu.dismiss"),
                    )
                    AreaMenu(selection, onSelect = { selection = it }, onClose = { menuOpen = false })
                }
            }
            DialogLayer()
        }
    }
    if (app.emergencyOpen) EmergencyPanel()
}

@Composable
private fun Detail(area: Area) {
    when (area) {
        Area.Overview -> OverviewScreen()
        Area.Equipment -> EquipmentScreen()
        Area.Sky -> SkyScreen()
        Area.Imaging -> ImagingScreen()
        Area.Focus -> FocusScreen()
        Area.Solve -> SolveScreen()
        Area.Guiding -> GuidingScreen()
        Area.Flip -> FlipScreen()
        Area.Sessions -> SessionsScreen()
        Area.Safety -> SafetyScreen()
        Area.Settings -> SettingsScreen()
    }
}

/** The areas on a tablet, exactly as wide as its longest label at the current text size, so nothing clips. */
@Composable
fun AreaMenu(selection: Area, onSelect: (Area) -> Unit, onClose: () -> Unit) {
    val app = LocalApp.current
    val theme = LocalTheme.current
    Column(
        Modifier.width(IntrinsicSize.Max).fillMaxHeight().background(theme.surface).verticalScroll(rememberScrollState()).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Area.entries.forEach { area ->
            val selected = area == selection
            Row(
                Modifier.fillMaxWidth().heightIn(min = minimumTarget).clip(RoundedCornerShape(8.dp))
                    .background(if (selected) theme.accent else theme.surface)
                    .clickable(role = Role.Tab) {
                        onSelect(area)
                        if (!app.menuPinned) onClose()
                    }
                    .padding(horizontal = 12.dp)
                    .testTag("area.${area.label}")
                    .semantics { stateDescription = if (selected) "selected" else "" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Txt(area.glyph, color = if (selected) theme.onAccent else theme.text)
                Txt(area.label, color = if (selected) theme.onAccent else theme.text, maxLines = 1)
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = minimumTarget).padding(top = 8.dp).clickable(role = Role.Button) {
                app.pinMenu(!app.menuPinned)
                onClose()
            }.padding(horizontal = 12.dp).testTag("menu.pin"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(if (app.menuPinned) "Auto-hide" else "Keep open", size = 13.sp, color = theme.textSecondary)
        }
    }
    Box(Modifier.width(1.dp).fillMaxHeight().background(theme.border))
}

/** The areas on a phone: a strip across the top, so that every area is one tap from any other. */
@Composable
fun AreaStrip(selection: Area, onSelect: (Area) -> Unit) {
    val theme = LocalTheme.current
    Row(
        Modifier.fillMaxWidth().background(theme.surface).horizontalScroll(rememberScrollState()).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Area.entries.forEach { area ->
            val selected = area == selection
            Row(
                Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (selected) theme.accent else theme.raised)
                    .clickable(role = Role.Tab) { onSelect(area) }
                    .padding(horizontal = 12.dp).testTag("area.${area.label}")
                    .semantics { stateDescription = if (selected) "selected" else "" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Txt(area.glyph, size = 15.sp, color = if (selected) theme.onAccent else theme.text)
                Txt(area.label, size = 15.sp, weight = FontWeight.SemiBold, color = if (selected) theme.onAccent else theme.text, maxLines = 1)
            }
        }
    }
}

/** The working Pier, connection, role and lease, safety, and the emergency button (docs/SDD.md 3.1). */
@Composable
fun TopBar(area: Area, onMenu: () -> Unit, showsMenuButton: Boolean) {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val configuration = LocalConfiguration.current
    // A phone on its side has the width for one row and not the height for two.
    val oneRow = configuration.screenWidthDp >= 600 || configuration.screenHeightDp < 480
    Column(Modifier.fillMaxWidth().background(theme.surface)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            if (oneRow) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (showsMenuButton) MenuButton(area, onMenu)
                    PierPicker()
                    ConnectionLabel()
                    RoleLabel()
                    Box(Modifier.weight(1f))
                    LeaseControl()
                    SafetyIndicator()
                    EmergencyButton()
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PierPicker()
                        Box(Modifier.weight(1f))
                        EmergencyButton()
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ConnectionLabel()
                        SafetyIndicator()
                        Box(Modifier.weight(1f))
                        LeaseControl()
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(theme.border))
    }
}

@Composable
private fun MenuButton(area: Area, onMenu: () -> Unit) {
    val theme = LocalTheme.current
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).background(theme.raised)
            .clickable(role = Role.Button, onClick = onMenu).padding(horizontal = 10.dp).testTag("menu")
            .semantics { contentDescription = "Menu, showing ${area.label}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt("☰", weight = FontWeight.SemiBold)
        Txt(area.label, size = 18.sp, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun PierPicker() {
    val app = LocalApp.current
    // A dialog rather than a menu: a system menu can't be held to Night.
    Row(
        Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { app.pierPickerOpen = true }.testTag("pier.picker")
            .semantics { contentDescription = "Working Pier: ${app.pier?.name ?: "none"}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Txt(app.pier?.name ?: "No Pier", size = 18.sp, weight = FontWeight.SemiBold)
        Txt("⇅", size = 14.sp)
    }
}

@Composable
private fun ConnectionLabel() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val (glyph, text) = when (app.snapshot.connection) {
        ConnectionState.Connecting -> "…" to "Connecting"
        ConnectionState.Connected -> "✓" to "Connected"
        ConnectionState.Reconnecting -> "↻" to "Reconnecting"
        ConnectionState.Unreachable -> "✕" to "Unreachable"
    }
    Txt(
        "$glyph $text", size = 15.sp, color = if (app.snapshot.isConnected) theme.text else theme.warning,
        modifier = Modifier.testTag("connection").semantics { contentDescription = "Connection: $text" },
    )
}

@Composable
private fun RoleLabel() {
    val role = LocalApp.current.snapshot.role ?: return
    Txt(role.wire, size = 15.sp, color = LocalTheme.current.textSecondary)
}

@Composable
private fun LeaseControl() {
    val app = LocalApp.current
    val pier = app.workingPier ?: return
    val lease = app.snapshot.lease(pier)
    if (app.snapshot.role == ObserverRole.Operator && app.canOperate) {
        when (lease) {
            SessionSnapshot.Lease.Mine -> LeaseButton("Control: yours", "Gives up control", "lease.release") { app.releaseLease() }
            SessionSnapshot.Lease.Free -> LeaseButton("Take control", "", "lease.take") { app.takeLease() }
            is SessionSnapshot.Lease.Other -> LeaseButton("Control: ${lease.holder}", "Asks to take over", "lease.ask") { app.takeLease() }
        }
    } else if (lease is SessionSnapshot.Lease.Other) {
        Txt("Control: ${lease.holder}", size = 15.sp)
    }
}

@Composable
private fun LeaseButton(text: String, hint: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick).testTag(tag)
            .semantics { if (hint.isNotEmpty()) contentDescription = "$text. $hint" },
        verticalAlignment = Alignment.CenterVertically,
    ) { Txt(text, size = 15.sp) }
}

@Composable
private fun SafetyIndicator() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val overall = app.overallSafety
    val unsafe = overall == "unsafe"
    Txt(
        (if (unsafe) "⊘ " else "✓ ") + (overall?.replaceFirstChar { it.uppercase() } ?: "Safety unknown"),
        size = 15.sp, weight = if (unsafe) FontWeight.Bold else FontWeight.Normal,
        color = if (unsafe) theme.onDanger else theme.text,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (unsafe) theme.danger else theme.surface)
            .padding(horizontal = if (unsafe) 8.dp else 0.dp, vertical = if (unsafe) 4.dp else 0.dp)
            .testTag("safety.indicator").semantics { contentDescription = "Safety: ${overall ?: "unknown"}" },
    )
}

@Composable
private fun EmergencyButton() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    if (app.snapshot.role != ObserverRole.Operator) return
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).background(theme.danger)
            .clickable(role = Role.Button) { app.emergencyOpen = true }.padding(horizontal = 12.dp).testTag("emergency"),
        verticalAlignment = Alignment.CenterVertically,
    ) { Txt("⊘ Emergency", size = 15.sp, weight = FontWeight.Bold, color = theme.onDanger) }
}

/** The connected app: the shell, or the reason the session ended. */
@Composable
fun ConnectedRoot() {
    val app = LocalApp.current
    val stop = app.snapshot.stop
    if (stop != null) SessionStoppedView(stop) else ShellView()
}
