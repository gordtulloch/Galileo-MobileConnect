package com.galileo.mobileconnect.quest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galileo.mobileconnect.AppViewModel
import com.galileo.mobileconnect.Route
import com.galileo.mobileconnect.SessionTarget
import com.galileo.mobileconnect.core.api.Role as ObserverRole
import com.galileo.mobileconnect.core.link.ConnectionState
import com.galileo.mobileconnect.core.session.SessionSnapshot
import com.galileo.mobileconnect.ui.ActionButton
import com.galileo.mobileconnect.ui.ActionKind
import com.galileo.mobileconnect.ui.Card
import com.galileo.mobileconnect.ui.ConnectionBanner
import com.galileo.mobileconnect.ui.Dialog
import com.galileo.mobileconnect.ui.DialogLayer
import com.galileo.mobileconnect.ui.EmergencyPanel
import com.galileo.mobileconnect.ui.LocalApp
import com.galileo.mobileconnect.ui.Note
import com.galileo.mobileconnect.ui.OperationButton
import com.galileo.mobileconnect.ui.OutcomeNote
import com.galileo.mobileconnect.ui.PairingView
import com.galileo.mobileconnect.ui.SessionStoppedView
import com.galileo.mobileconnect.ui.Txt
import com.galileo.mobileconnect.ui.WelcomeView
import com.galileo.mobileconnect.ui.screens.EquipmentScreen
import com.galileo.mobileconnect.ui.screens.FlipScreen
import com.galileo.mobileconnect.ui.screens.FocusScreen
import com.galileo.mobileconnect.ui.screens.GuidingScreen
import com.galileo.mobileconnect.ui.screens.ImagingScreen
import com.galileo.mobileconnect.ui.screens.OverviewScreen
import com.galileo.mobileconnect.ui.screens.SafetyScreen
import com.galileo.mobileconnect.ui.screens.SessionsScreen
import com.galileo.mobileconnect.ui.screens.SettingsScreen
import com.galileo.mobileconnect.ui.screens.SolveScreen
import com.galileo.mobileconnect.ui.theme.LocalTheme
import com.galileo.mobileconnect.ui.theme.ThemeRoot
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** What the dock shows that only the host knows. Plain state: the dock recomposes when the host changes it. */
class DockState {
    var timeLabel by mutableStateOf("Live")
    var zoomLabel by mutableStateOf("")
    var passthrough by mutableStateOf(false)
    var constellations by mutableStateOf(true)
    var openAreas by mutableStateOf<Set<DockArea>>(emptySet())
    var noticePending by mutableStateOf(false)
}

private fun glyph(area: DockArea) = when (area) {
    DockArea.Overview -> "▤"
    DockArea.Equipment -> "⌖"
    DockArea.Imaging -> "◉"
    DockArea.Focus -> "◎"
    DockArea.Solve -> "⌗"
    DockArea.Guiding -> "⊕"
    DockArea.Flip -> "⇄"
    DockArea.Sessions -> "☰"
    DockArea.Safety -> "⚑"
    DockArea.Settings -> "⚙"
}

/** Every surface is the app's own theme root, so Night is red here as everywhere. */
@Composable
fun QuestRoot(app: AppViewModel, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalApp provides app) {
        ThemeRoot(app.theme) { content() }
    }
}

/** The dock (MAPP-QST-330): the Pier, connection and lease, the areas, passthrough and Emergency. It is never covered by a pane. */
@Composable
fun DockContent(state: DockState, onArea: (DockArea) -> Unit, onPassthrough: () -> Unit, onRecentre: () -> Unit) {
    val app = LocalApp.current
    val theme = LocalTheme.current
    Row(
        Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(theme.surface).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(98.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Txt(app.pier?.name ?: "No Pier", size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
            val (glyph, text) = when (app.snapshot.connection) {
                ConnectionState.Connecting -> "…" to "Connecting"
                ConnectionState.Connected -> "✓" to "Connected"
                ConnectionState.Reconnecting -> "↻" to "Reconnecting"
                ConnectionState.Unreachable -> "✕" to "Unreachable"
            }
            Txt("$glyph $text", size = 11.sp, color = if (app.snapshot.isConnected) theme.text else theme.warning, maxLines = 1)
            val overall = app.overallSafety
            val unsafe = overall == "unsafe"
            Txt(
                (if (unsafe) "⊘ " else "✓ ") + (overall?.replaceFirstChar { it.uppercase() } ?: "Safety ?"), size = 11.sp,
                weight = if (unsafe) FontWeight.Bold else FontWeight.Normal, color = if (unsafe) theme.danger else theme.text, maxLines = 1,
            )
        }
        DockArea.entries.forEach { area ->
            val open = area in state.openAreas
            Column(
                Modifier.width(65.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                    .background(if (open) theme.accent else theme.raised)
                    .clickable(role = Role.Button) { onArea(area) }.testTag("dock.${area.label}"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Txt(glyph(area), size = 22.sp, color = if (open) theme.onAccent else theme.text)
                Txt(area.label, size = 10.sp, color = if (open) theme.onAccent else theme.text, maxLines = 1)
            }
        }
        Column(Modifier.width(236.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                DockButton("Passthrough: ${if (state.passthrough) "on" else "off"}", Modifier.weight(1f), "dock.passthrough", onPassthrough)
                DockButton("Recentre", Modifier.weight(1f), "dock.recentre", onRecentre)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Lease(Modifier.weight(1f))
                if (app.snapshot.role == ObserverRole.Operator) {
                    Row(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(8.dp)).background(theme.danger)
                            .clickable(role = Role.Button) { app.emergencyOpen = true }.testTag("dock.emergency"),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) { Txt("⊘ Emergency", size = 12.sp, weight = FontWeight.Bold, color = theme.onDanger, maxLines = 1) }
                }
            }
            Txt(
                "${state.timeLabel}   ${state.zoomLabel}", size = 11.sp, color = theme.textSecondary, maxLines = 1,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun DockButton(text: String, modifier: Modifier, tag: String, onClick: () -> Unit) {
    val theme = LocalTheme.current
    Row(
        modifier.height(40.dp).clip(RoundedCornerShape(8.dp)).background(theme.raised).clickable(role = Role.Button, onClick = onClick).testTag(tag),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) { Txt(text, size = 11.sp, maxLines = 1) }
}

@Composable
private fun Lease(modifier: Modifier) {
    val app = LocalApp.current
    val pier = app.workingPier
    if (pier == null || app.snapshot.role != ObserverRole.Operator) {
        Box(modifier.height(40.dp))
        return
    }
    when (val lease = app.snapshot.lease(pier)) {
        SessionSnapshot.Lease.Mine -> DockButton("Control: yours", modifier, "dock.lease") { app.releaseLease() }
        SessionSnapshot.Lease.Free -> DockButton("Take control", modifier, "dock.lease") { app.takeLease() }
        is SessionSnapshot.Lease.Other -> DockButton("Control: ${lease.holder}", modifier, "dock.lease") { app.takeLease() }
    }
}

/** A tablet screen in a floating pane: a title bar with Close, and the screen below it (MAPP-QST-340). */
@Composable
fun PaneContent(area: DockArea, onClose: () -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(theme.background)) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).background(theme.surface).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(area.label, size = 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Row(
                Modifier.height(34.dp).clip(RoundedCornerShape(8.dp)).background(theme.raised)
                    .clickable(role = Role.Button, onClick = onClose).padding(horizontal = 14.dp).testTag("pane.close.${area.label}"),
                verticalAlignment = Alignment.CenterVertically,
            ) { Txt("✕ Close", size = 14.sp) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (area) {
                DockArea.Overview -> OverviewScreen()
                DockArea.Equipment -> EquipmentScreen()
                DockArea.Imaging -> ImagingScreen()
                DockArea.Focus -> FocusScreen()
                DockArea.Solve -> SolveScreen()
                DockArea.Guiding -> GuidingScreen()
                DockArea.Flip -> FlipScreen()
                DockArea.Sessions -> SessionsScreen()
                DockArea.Safety -> SafetyScreen()
                DockArea.Settings -> SettingsScreen()
            }
        }
    }
}

/** What the Star Atlas offers on an object, in the same order and under the same rules (MAPP-QST-310, MAPP-SKY-100): a Viewer gets the name and the altitude. */
@Composable
fun ObjectMenuContent(sky: QuestSky, onClose: () -> Unit) {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val scope = rememberCoroutineScope()
    val selection = sky.selection
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(theme.background)) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).background(theme.surface).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt("Object", size = 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Row(
                Modifier.height(34.dp).clip(RoundedCornerShape(8.dp)).background(theme.raised)
                    .clickable(role = Role.Button, onClick = onClose).padding(horizontal = 14.dp).testTag("object.close"),
                verticalAlignment = Alignment.CenterVertically,
            ) { Txt("✕ Close", size = 14.sp) }
        }
        if (selection == null) return@Column
        var goToMessage by remember(selection.name) { mutableStateOf<String?>(null) }
        val args: Map<String, JsonElement> = mapOf("ra_deg" to JsonPrimitive(selection.raDeg), "dec_deg" to JsonPrimitive(selection.decDeg))
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(selection.name) {
                if (selection.details.isNotEmpty()) Note(selection.details.joinToString(" · "))
                selection.check?.let { check ->
                    Txt("Altitude now: %.1f°".format(check.altitude), size = 14.sp, weight = FontWeight.SemiBold)
                    check.reason?.let { Note(it) }
                }
                if (app.canOperate) {
                    ActionButton("Goto", kind = ActionKind.Prominent, tag = "object.goto") {
                        scope.launch { goToMessage = app.goTo(selection.name, selection.raDeg, selection.decDeg) }
                    }
                    val record = app.record("mount.slew")
                    when {
                        goToMessage != null -> Note(goToMessage!!)
                        record != null -> OutcomeNote(record)
                    }
                    OperationButton("Goto and Centre", "solve.centre", args + ("name" to JsonPrimitive(selection.name)))
                    OperationButton("Sync", "mount.sync", args)
                    ActionButton("Add to Session", tag = "object.addsession") {
                        app.sessionTarget = SessionTarget(selection.name, selection.raDeg, selection.decDeg)
                        app.requestedArea = "Sessions"
                    }
                }
            }
        }
    }
}

/**
 * Whatever must be answered or seen before anything else (docs/quest/SDD.md Q-SAFE): connecting, a confirmation, the Emergency
 * panel, the Pier picker, a lease offer, the first-run notice. It is one surface in front of everything; while it is up the
 * dock and the other panes are not touchable, and the sky is not picked.
 */
@Composable
fun AlertContent(state: DockState, onNoticeSeen: () -> Unit) {
    val app = LocalApp.current
    val theme = LocalTheme.current
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(theme.background)) {
        val stop = app.snapshot.stop
        when {
            app.route == Route.Welcome -> WelcomeView()
            app.route == Route.Pairing -> PairingView()
            stop != null -> SessionStoppedView(stop)
        }
        Column(Modifier.fillMaxWidth()) { ConnectionBanner() }
        DialogLayer()
        if (app.emergencyOpen) EmergencyPanel()
        if (state.noticePending) {
            Dialog("Before you start") {
                Txt("You are controlling a real telescope. Clear the space around you before you put the headset on.")
                Txt("Held moves (slew, focus, guiding) go on only while you hold the trigger, and stop the moment you let go, lose tracking, or take the headset off.")
                Txt("The Emergency button is always on the dock.", align = TextAlign.Start)
                ActionButton("I understand", kind = ActionKind.Prominent, tag = "notice.ok") { onNoticeSeen() }
            }
        }
    }
}
