package com.galileo.mobileconnect.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galileo.mobileconnect.core.api.PierInfo
import com.galileo.mobileconnect.core.session.SessionSnapshot
import com.galileo.mobileconnect.core.state.LaneSegment
import com.galileo.mobileconnect.core.state.LivePhase
import com.galileo.mobileconnect.core.state.double
import com.galileo.mobileconnect.core.state.string
import com.galileo.mobileconnect.ui.ActionKind
import com.galileo.mobileconnect.ui.Card
import com.galileo.mobileconnect.ui.Columns
import com.galileo.mobileconnect.ui.LiveRow
import com.galileo.mobileconnect.ui.LocalApp
import com.galileo.mobileconnect.ui.Note
import com.galileo.mobileconnect.ui.OperationButton
import com.galileo.mobileconnect.ui.ButtonRow
import com.galileo.mobileconnect.ui.ScreenScroll
import com.galileo.mobileconnect.ui.Spinner
import com.galileo.mobileconnect.ui.ThemedProgress
import com.galileo.mobileconnect.ui.Txt
import com.galileo.mobileconnect.ui.minimumTarget
import com.galileo.mobileconnect.ui.spacing
import com.galileo.mobileconnect.ui.theme.LocalTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Every Pier, the working Pier's activity, and the alerts (MAPP-OVR-*). */
@Composable
fun OverviewScreen() {
    val app = LocalApp.current
    ScreenScroll("Overview") {
        if (app.snapshot.live.phase != LivePhase.Live) {
            Card {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner()
                    Txt(if (app.snapshot.live.phase == LivePhase.Resyncing) "Catching up with the Master…" else "Loading the observatory…")
                }
            }
        }
        Columns(
            left = {
                PiersCard()
                ActivityCard()
            },
            right = {
                SessionCard()
                AlertsCard()
                NodeHealthCard()
            },
        )
    }
}

@Composable
private fun PiersCard() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    Card("Piers") {
        app.snapshot.piers.forEach { pier ->
            val selected = pier.id == app.workingPier
            val shape = RoundedCornerShape(10.dp)
            Row(
                Modifier.fillMaxWidth().heightIn(min = minimumTarget).clip(shape)
                    .background(if (selected) theme.raised else theme.surface).border(1.dp, theme.border, shape)
                    .clickable(role = Role.Button) { app.workingPier = pier.id }.padding(10.dp).testTag("pier.${pier.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt(pier.name, size = 18.sp, weight = FontWeight.SemiBold)
                    Txt(pier.node, size = 13.sp)
                    Txt(statusLine(app.snapshot, pier), size = 13.sp)
                }
                if (selected) Txt("✓", modifier = Modifier.semantics { contentDescription = "Working Pier" })
            }
        }
    }
}

private fun statusLine(snapshot: SessionSnapshot, pier: PierInfo): String {
    snapshot.unavailableReason(pier.id)?.let { return "Not available: $it" }
    return when (snapshot.access(pier.id)) {
        PierInfo.Access.Control -> "Can be operated"
        PierInfo.Access.View -> "View only"
        PierInfo.Access.None -> "Not granted to this device"
    }
}

@Composable
private fun SessionCard() {
    val app = LocalApp.current
    Card("Session") {
        LiveRow("Name", "session.name")
        LiveRow("State", "session.state")
        LiveRow("Now", "session.block")
        app.value("session.progress")?.value.double?.let { ThemedProgress(it, "Session progress") }
        if (app.value("session.stop_reason") != null) LiveRow("Stopped because", "session.stop_reason")
        LiveRow("Meridian flip", "flip.state")
        LiveRow("Minutes to flip", "flip.minutes")
    }
}

@Composable
private fun AlertsCard() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    Card("Alerts") {
        val list = app.snapshot.live.alerts.take(12)
        if (list.isEmpty()) Note("Nothing to report.")
        list.forEach { alert ->
            Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(
                    when (alert.severity) {
                        "critical" -> "⊘"
                        "warning" -> "⚠"
                        else -> "ⓘ"
                    },
                    modifier = Modifier.semantics { contentDescription = alert.severity },
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt(alert.message, size = 15.sp, weight = if (alert.severity == "critical") FontWeight.Bold else FontWeight.Normal)
                    Txt(formatter.format(alert.time), size = 12.sp, color = theme.textSecondary)
                }
            }
        }
    }
}

private val lanes = listOf(
    "session" to "Session", "mount" to "Mount", "flip" to "Flip", "guiding" to "Guiding", "solve" to "Solve",
    "focus" to "Focus", "capture" to "Capture",
)
private const val SPAN_SECONDS = 30 * 60.0
private val idleStates = setOf("Idle", "Stopped", "Parked", "Done")

/** The lanes of the Galileo Dashboard (MAPP-OVR-020), from what the Master has said since connecting. */
@Composable
private fun ActivityCard() {
    val app = LocalApp.current
    val state = app.workingPier?.let { app.snapshot.live.piers[it] }
    Card("Activity, last 30 minutes") {
        lanes.forEach { (lane, title) ->
            val segments = state?.lanes?.get(lane) ?: emptyList()
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = false) { contentDescription = "$title: ${segments.lastOrNull()?.state ?: "unknown"}" },
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(title, size = 12.sp, modifier = Modifier.width(58.dp))
                Bar(segments, app.now, Modifier.weight(1f))
                Txt(segments.lastOrNull()?.state ?: "—", size = 12.sp, weight = FontWeight.SemiBold, align = TextAlign.End, maxLines = 1, modifier = Modifier.width(96.dp))
            }
        }
    }
}

@Composable
private fun Bar(segments: List<LaneSegment>, now: Instant, modifier: Modifier) {
    val theme = LocalTheme.current
    Canvas(modifier.height(16.dp).clip(RoundedCornerShape(3.dp))) {
        drawRect(theme.border.copy(alpha = 0.35f), size = size)
        segments.forEachIndexed { index, segment ->
            val end = if (index + 1 < segments.size) segments[index + 1].start else now
            val from = maxOf(0.0, 1 - (now.toEpochMilli() - segment.start.toEpochMilli()) / 1000.0 / SPAN_SECONDS)
            val to = maxOf(0.0, 1 - (now.toEpochMilli() - end.toEpochMilli()) / 1000.0 / SPAN_SECONDS)
            if (to <= from && index != segments.size - 1) return@forEachIndexed
            val left = minOf((from * size.width).toFloat(), size.width - 2f)
            drawRect(
                theme.accent.copy(alpha = if (segment.state in idleStates) 0.25f else 0.85f),
                topLeft = Offset(left, 0f), size = Size(maxOf(2f, ((to - from) * size.width).toFloat()), size.height),
            )
        }
    }
}

/**
 * How the computer running the Master is doing, with Restart and Shut down (MAPP-OVR-080, CSURF-310). All of it is the
 * Master's `node.health`, published every few seconds; the two buttons are confirm-class, so the app asks first, and the
 * Master refuses them (and says why) while a session is running.
 */
@Composable
private fun NodeHealthCard() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    Card("Master computer") {
        if (app.value("node.health.status") == null) {
            Note("Waiting for the Master's health report…")
        } else {
            LiveRow("CPU", "node.health.cpu_percent", "%", 0)
            LiveRow("Memory", "node.health.memory_percent", "%", 0)
            LiveRow("Disk free", "node.health.disk_free_gb", "GB", 0)
            if (app.value("node.health.temperature_c")?.value.double != null) LiveRow("Temperature", "node.health.temperature_c", "°C", 0)
            app.value("node.health.uptime_s")?.value.double?.let { uptime ->
                Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).semantics(mergeDescendants = true) {}) {
                    Txt("Up", color = theme.textSecondary, modifier = Modifier.weight(1f))
                    Txt(uptimeText(uptime), weight = FontWeight.SemiBold)
                }
            }
            when (app.value("node.health.status")?.value.string) {
                "critical" -> Txt("⊘ A figure is critical — check the Master computer.", size = 15.sp, weight = FontWeight.Bold)
                "warning" -> Txt("⚠ A figure is getting tight.", size = 15.sp)
            }
        }
        ButtonRow(
            { OperationButton("Restart", "node.restart", kind = ActionKind.Destructive) },
            { OperationButton("Shut down", "node.shutdown", kind = ActionKind.Destructive) },
        )
    }
}

/** 3 d 4 h, 4 h 12 m or 12 m: two units are enough to tell a restart from a Pi that has been up for weeks. */
fun uptimeText(seconds: Double): String {
    val total = seconds.toInt()
    val days = total / 86400
    val hours = total % 86400 / 3600
    val minutes = total % 3600 / 60
    if (days > 0) return "$days d $hours h"
    return if (hours > 0) "$hours h $minutes m" else "$minutes m"
}

