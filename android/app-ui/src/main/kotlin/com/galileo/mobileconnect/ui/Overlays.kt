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
import com.galileo.mobileconnect.ui.theme.LocalTheme
import kotlinx.serialization.json.JsonArray

// Shared by the tablet shell and the Quest panes: the banners and the dialogs that must never be missed.

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
        app.pierPickerOpen -> Dialog("Working Pier") {
            app.snapshot.piers.forEach { pier ->
                ActionButton(
                    "${pier.name} — ${pier.node}", kind = if (pier.id == app.workingPier) ActionKind.Prominent else ActionKind.Normal,
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

