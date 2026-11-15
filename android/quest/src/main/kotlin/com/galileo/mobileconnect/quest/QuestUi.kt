package com.galileo.mobileconnect.quest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galileo.mobileconnect.AppViewModel
import com.galileo.mobileconnect.Route
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
import com.galileo.mobileconnect.ui.PairingView
import com.galileo.mobileconnect.ui.SessionStoppedView
import com.galileo.mobileconnect.ui.Txt
import com.galileo.mobileconnect.ui.WelcomeView
import com.galileo.mobileconnect.ui.screens.EquipmentScreen
import com.galileo.mobileconnect.ui.screens.FocusScreen
import com.galileo.mobileconnect.ui.screens.GuidingScreen
import com.galileo.mobileconnect.ui.screens.ImagingScreen
import com.galileo.mobileconnect.ui.screens.OverviewScreen
import com.galileo.mobileconnect.ui.screens.SafetyScreen
import com.galileo.mobileconnect.ui.screens.SessionsScreen
import com.galileo.mobileconnect.ui.screens.SettingsScreen
import com.galileo.mobileconnect.ui.screens.PolarAlignmentScreen
import com.galileo.mobileconnect.ui.screens.SolveScreen
import com.galileo.mobileconnect.ui.theme.LocalTheme
import com.galileo.mobileconnect.ui.theme.ThemeRoot

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
    DockArea.PolarAlignment -> "⌖"
    DockArea.Guiding -> "⊕"
    DockArea.Sessions -> "☰"
    DockArea.Targets -> "★"
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
fun PaneContent(area: DockArea, targets: TargetsSource? = null, onClose: () -> Unit) {
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
                DockArea.PolarAlignment -> PolarAlignmentScreen()
                DockArea.Guiding -> GuidingScreen()
                DockArea.Sessions -> SessionsScreen()
                DockArea.Targets -> TargetsPaneContent(targets)
                DockArea.Safety -> SafetyScreen()
                DockArea.Settings -> SettingsScreen()
            }
        }
    }
}

/** What the object menu can ask for. The host does the work, so closing the menu cannot cancel it. */
enum class MenuAction { Goto, Centre, Sync, AddToSession }

/**
 * What the Star Atlas offers on an object, in the same order and under the same rules (MAPP-QST-310, MAPP-SKY-100): a Viewer
 * gets the name and the altitude. Pressing any action closes the menu at once (the slew is then followed by the status panel and
 * the telescope marker on the sky). The description is not here: it is the card on the right controller ([ObjectCardContent]).
 */
@Composable
fun ObjectMenuContent(sky: QuestSky, onClose: () -> Unit, onAct: (MenuAction, Selection) -> Unit) {
    val app = LocalApp.current
    val theme = LocalTheme.current
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
        Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Card(selection.name) {
                selection.check?.let { check ->
                    Txt("Altitude now: %.1f°".format(check.altitude), size = 14.sp, weight = FontWeight.SemiBold)
                    check.reason?.let { Note(it) }
                }
                if (app.canOperate) {
                    MenuOperation("Goto", "mount.slew", ActionKind.Prominent, "object.goto") { onAct(MenuAction.Goto, selection) }
                    MenuOperation("Goto and Centre", "solve.centre", ActionKind.Normal, "solve.centre") { onAct(MenuAction.Centre, selection) }
                    MenuOperation("Sync", "mount.sync", ActionKind.Normal, "mount.sync") { onAct(MenuAction.Sync, selection) }
                    ActionButton("Add to Session", tag = "object.addsession") { onAct(MenuAction.AddToSession, selection) }
                }
            }
        }
    }
}

/** An operation button gated the way [OperationButton] gates it, but whose work is done by the host (see [MenuAction]). */
@Composable
private fun MenuOperation(title: String, operation: String, kind: ActionKind, tag: String, onClick: () -> Unit) {
    val app = LocalApp.current
    val info = app.operation(operation)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionButton(title, kind = kind, enabled = info != null && info.available, tag = tag, onClick = onClick)
        when {
            info == null -> Note("This Master doesn't offer this yet.")
            !info.available && info.unavailableBecause != null -> Note(info.unavailableBecause!!)
        }
    }
}

/**
 * The description card that rides on the right controller (as in the atlas spike): name, type, magnitude, size, rise and set,
 * and the description. The left stick scrolls it; the right grip reads it aloud. It is a surface in front of the sky, placed by
 * the host from the controller's pose each frame.
 */
@Composable
fun ObjectCardContent(sky: QuestSky) {
    val theme = LocalTheme.current
    val selection = sky.selection ?: return
    var speaking by remember { mutableStateOf(Narration.speaking) }
    LaunchedEffect(Unit) {
        while (true) {
            speaking = Narration.speaking
            delay(250)
        }
    }
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(10.dp)
    BoxWithConstraints(Modifier.fillMaxSize().clip(shape).background(theme.background).border(2.dp, theme.accent, shape)) {
        Column(
            Modifier.verticalScroll(scroll).padding(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Txt(selection.name, size = 18.sp, weight = FontWeight.SemiBold)
            if (Narration.has(selection.name)) {
                Txt(
                    if (speaking) "■ Reading aloud. Right grip stops." else "🔊 Right grip (second trigger) reads this aloud.",
                    size = 12.sp, color = theme.accent,
                )
            }
            if (selection.details.isEmpty()) Txt("Fetching details…", size = 13.sp, color = theme.textSecondary)
            for (line in selection.details) Txt(line, size = 14.sp)
        }
        if (scroll.maxValue > 0) {
            val view = constraints.maxHeight.toFloat()
            val thumb = view * view / (view + scroll.maxValue)
            val top = (view - thumb) * scroll.value / scroll.maxValue
            Box(
                Modifier.align(Alignment.TopEnd).offset { IntOffset(0, top.toInt()) }.padding(end = 4.dp).width(4.dp)
                    .height(with(LocalDensity.current) { thumb.toDp() }).clip(RoundedCornerShape(2.dp)).background(theme.accent),
            )
        }
    }
}

/** What the head-locked status panel shows: a slew in progress, and the last message from a menu action. */
class StatusState {
    var slew by mutableStateOf<SlewTracker.Status?>(null)
    var hint by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
}

/** Where the telescope is on its way, as a bar: the same numbers as the marker on the sky, in view without turning to the dock. */
@Composable
fun StatusContent(state: StatusState) {
    val theme = LocalTheme.current
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(theme.background).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        state.slew?.let { s ->
            Txt(
                if (s.arrived) "On target: ${s.name}" else "Slewing to ${s.name}  ·  %.1f° to go".format(s.remainingDegrees),
                size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1,
            )
            Box(Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(5.dp)).background(theme.raised)) {
                Box(Modifier.fillMaxWidth(s.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(theme.accent))
            }
            state.hint?.let { Txt(it, size = 12.sp, color = theme.textSecondary, maxLines = 1) }
        }
        state.message?.let { Txt(it, size = 13.sp, maxLines = 2) }
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
