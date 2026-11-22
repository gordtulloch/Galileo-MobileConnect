package com.galileo.mobileconnect.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.layout.widthIn
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
import com.galileo.mobileconnect.ui.theme.LocalTheme
import kotlinx.serialization.json.JsonArray

// Shared by the tablet shell and the Quest panes: the banners and the dialogs that must never be missed.

/** How long an event notice stays up before it closes itself (MAPP-OVR-100). */
const val NOTICE_SECONDS = 10L
private const val NOTICE_VISIBLE = 5

/**
 * Event notices over the top-right corner: each exposure start, slew, solve, focus result or connection the Master
 * reports appears for [NOTICE_SECONDS] seconds, or until its × is tapped (MAPP-OVR-090). Notices already in the state
 * when this first appears are history and are not shown, so reconnecting does not replay old events. Colours come
 * from the theme alone, so Night stays red (ADR-AND-007).
 */
@Composable
fun NoticeOverlay() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val notices = app.snapshot.live.notices
    val known = remember { notices.map { it.id }.toSet() }
    var dismissed by remember { mutableStateOf(emptySet<String>()) }
    val shown = notices.filter { it.id !in known && it.id !in dismissed }.take(NOTICE_VISIBLE).reversed()
    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.TopEnd) {
        Column(Modifier.widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEach { notice ->
                LaunchedEffect(notice.id) {
                    delay(NOTICE_SECONDS * 1000)
                    dismissed = dismissed + notice.id
                }
                val accent = when (notice.severity) {
                    "error" -> theme.danger
                    "warning" -> theme.warning
                    "success" -> theme.good
                    else -> theme.accent
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.raised).testTag("notice.${notice.kind}"),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.width(5.dp).heightIn(min = 44.dp).background(accent))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Txt(notice.title, size = 14.sp, weight = FontWeight.Bold)
                        if (notice.message.isNotEmpty()) Txt(notice.message, size = 13.sp, color = theme.textSecondary)
                    }
                    Box(
                        Modifier.heightIn(min = 44.dp).width(44.dp)
                            .clickable(role = Role.Button, onClickLabel = "Dismiss") { dismissed = dismissed + notice.id }
                            .testTag("notice.close"),
                        contentAlignment = Alignment.Center,
                    ) { Txt("×", size = 20.sp, color = theme.textSecondary) }
                }
            }
        }
    }
}

/** Reconnecting, unreachable, a demo: always said, never implied (MAPP-CON-070, MAPP-CON-110). */
@Composable
fun ConnectionBanner() {
    val app = LocalApp.current
    Column {
        if (app.isDemo) Banner("◐", "DEMO — simulated observatory", Modifier.padding(horizontal = 8.dp).padding(top = 4.dp).testTag("demo.banner"))
        when (app.snapshot.connection) {
            ConnectionState.Reconnecting -> Banner("↻", "Reconnecting. Values shown are not live.", Modifier.padding(8.dp))
            ConnectionState.Unreachable -> Banner("✕", "The Master can't be reached. Still trying. Values shown are not live.", Modifier.padding(8.dp))
            else -> Unit
        }
        app.snapshot.notice?.let { Banner("⚠", it, Modifier.padding(8.dp)) }
        val closing = app.snapshot.live.closingReason
        if (!closing.isNullOrEmpty() && !app.snapshot.isConnected) Banner("✕", "The Master closed the link: $closing", Modifier.padding(8.dp))
    }
}

/**
 * App dialogs are drawn here rather than as system dialogs: a system dialog can't be held to Night, and the emergency
 * button above must stay reachable while a question is open (docs/SDD.md 3.9, risk I5).
 */
@Composable
fun DialogLayer() {
    val app = LocalApp.current
    val question = app.question
    val offer = app.leaseOffer
    when {
        question != null -> Dialog(question.title) {
            if (question.details.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { question.details.forEach { Txt(it) } }
            question.consequence?.let { Txt(it) }
            app.pier?.let { Note("On ${it.name}.") }
            ActionButton("Cancel", tag = "confirm.cancel") { app.question = null }
            ActionButton("Yes, do it", kind = ActionKind.Destructive, tag = "confirm.accept") { app.accept(question) }
        }
        app.preemptFor != null -> {
            val name = app.preemptFor!!
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            Dialog("Pre-empt any upcoming jobs?") {
                Txt("$name will start now. Jobs scheduled for later stay on the queue and start in their own time unless this session is still running.")
                app.pier?.let { Note("On ${it.name}.") }
                ActionButton("Cancel", tag = "preempt.cancel") { app.preemptFor = null }
                ActionButton("Pre-empt", kind = ActionKind.Prominent, tag = "preempt.accept") {
                    app.preemptFor = null
                    scope.launch {
                        app.perform("session.run_saved", mapOf("name" to kotlinx.serialization.json.JsonPrimitive(name), "preempt" to kotlinx.serialization.json.JsonPrimitive(true)))
                    }
                }
            }
        }
        app.pierPickerOpen -> Dialog("Working Pier") {
            app.snapshot.piers.forEach { pier ->
                ActionButton(
                    "${app.label(pier)} — ${pier.node}", kind = if (pier.id == app.workingPier) ActionKind.Prominent else ActionKind.Normal,
                    tag = "pier.choose.${pier.id}",
                ) {
                    app.workingPier = pier.id
                    app.pierPickerOpen = false
                }
            }
            ActionButton("Close") { app.pierPickerOpen = false }
        }
        offer != null -> Dialog("Control is held") {
            Txt(offer.reason)
            ActionButton("Close", tag = "lease.close") { app.leaseOffer = null }
            offer.takeOverToken?.let { token ->
                Note("Taking over stops whatever ${offer.holder ?: "the holder"} is doing by hand.")
                ActionButton("Take over", kind = ActionKind.Destructive, tag = "lease.takeover") {
                    app.leaseOffer = null
                    app.takeLease(token)
                }
            }
        }
    }
}

/** Two interactions from anywhere: open this, then hold (MAPP-EMG-010). The results list each part. */
@Composable
fun EmergencyPanel() {
    val app = LocalApp.current
    val actions = listOf(
        "Stop everything" to "system.stop_everything",
        "Park mount" to "mount.emergency_park",
        "Close dome" to "dome.emergency_close",
    )
    Dialog("Emergency") {
        Note("Press and hold for a second. On ${app.pier?.name ?: "this Pier"}. No control needed.")
        actions.forEach { (title, operation) ->
            HoldToConfirm(title, "emergency.$operation", enabled = app.operation(operation) != null) { app.emergency(operation) }
            app.record(operation)?.let { Outcome(it) }
        }
        ActionButton("Close", tag = "emergency.close") { app.emergencyOpen = false }
    }
}

@Composable
private fun Outcome(record: com.galileo.mobileconnect.core.control.OperationRecord) {
    val state = record.state
    val parts = (state as? OperationState.Done)?.value?.get("parts") as? JsonArray
    if (parts != null) {
        Column(Modifier.testTag("emergency.result").semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            parts.forEach { part ->
                val ok = part["ok"].bool == true
                Txt(
                    (if (ok) "✓ " else "⊘ ") + "${part["name"].string ?: "Part"}: ${if (ok) "done" else "FAILED"}",
                    size = 13.sp, weight = if (ok) FontWeight.Normal else FontWeight.Bold,
                )
            }
        }
    } else {
        OutcomeNote(record)
    }
}

