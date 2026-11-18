package com.galileo.mobileconnect.ui.screens

import androidx.compose.foundation.layout.Arrangement
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galileo.mobileconnect.core.api.Role
import com.galileo.mobileconnect.core.state.bool
import com.galileo.mobileconnect.core.state.double
import com.galileo.mobileconnect.core.state.get
import com.galileo.mobileconnect.core.state.int
import com.galileo.mobileconnect.core.state.string
import com.galileo.mobileconnect.ui.ActionButton
import com.galileo.mobileconnect.ui.ActionKind
import com.galileo.mobileconnect.ui.ButtonRow
import com.galileo.mobileconnect.ui.Card
import com.galileo.mobileconnect.ui.Chart
import com.galileo.mobileconnect.ui.ChartSeries
import com.galileo.mobileconnect.ui.Choices
import com.galileo.mobileconnect.ui.Columns
import com.galileo.mobileconnect.ui.HoldButton
import com.galileo.mobileconnect.ui.LiveRow
import com.galileo.mobileconnect.ui.LocalApp
import com.galileo.mobileconnect.ui.Note
import com.galileo.mobileconnect.ui.OperationButton
import com.galileo.mobileconnect.ui.OperationForm
import com.galileo.mobileconnect.ui.OutcomeNote
import com.galileo.mobileconnect.ui.ScreenScroll
import com.galileo.mobileconnect.ui.ThemedProgress
import com.galileo.mobileconnect.ui.Txt
import com.galileo.mobileconnect.ui.ValueRow
import com.galileo.mobileconnect.ui.explanation
import com.galileo.mobileconnect.ui.minimumTarget
import com.galileo.mobileconnect.ui.theme.LocalTheme
import com.galileo.mobileconnect.ui.theme.ThemeChoice
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Says why there are no controls, when there aren't (MAPP-CTL-010). */
@Composable
fun ReadOnlyHint() {
    val app = LocalApp.current
    val pier = app.workingPier ?: return
    if (!app.canOperate && app.snapshot.unavailableReason(pier) == null) {
        Note(if (app.snapshot.role == Role.Viewer) "This device is a Viewer: it can watch but not operate." else "This Pier is view only from this device.")
    }
}

/** Every device of the working Pier, built from the catalogue (MAPP-DEV-*). */
@Composable
fun EquipmentScreen() {
    ScreenScroll("Equipment") {
        ReadOnlyHint()
        Columns(
            left = {
                MountCard()
                MeridianFlipCard()
            },
            right = {
                CameraCard()
                FilterCard()
                RotatorCard()
                DomeCard()
            },
        )
    }
}

@Composable
private fun ConnectionButtons(device: String, label: String) {
    val args = mapOf("device" to JsonPrimitive(device))
    ButtonRow(
        { OperationButton("Connect $label", "device.connect", args) },
        { OperationButton("Disconnect", "device.disconnect", args) },
    )
}

@Composable
private fun MountCard() {
    val app = LocalApp.current
    Card("Mount") {
        LiveRow("Connected", "mount.connected")
        LiveRow("Tracking", "mount.tracking")
        LiveRow("Slewing", "mount.slewing")
        LiveRow("Parked", "mount.parked")
        LiveRow("Target", "mount.target")
        LiveRow("RA", "mount.position.ra_deg", "°", 3)
        LiveRow("Dec", "mount.position.dec_deg", "°", 3)
        LiveRow("Altitude", "mount.position.alt_deg", "°", 1)
        ConnectionButtons("mount", "mount")
        val tracking = app.value("mount.tracking")?.value.bool ?: false
        ButtonRow(
            { OperationButton(if (tracking) "Stop tracking" else "Start tracking", "mount.track", mapOf("enabled" to JsonPrimitive(!tracking))) },
            { OperationButton("Abort slew", "mount.abort_slew") },
        )
        ButtonRow(
            { OperationButton("Park", "mount.park") },
            { OperationButton("Unpark", "mount.unpark") },
        )
        OperationForm("Slew", "mount.slew", kind = ActionKind.Prominent)
        OperationForm("Sync", "mount.sync")
    }
}

@Composable
private fun CameraCard() {
    Card("Camera") {
        LiveRow("Connected", "camera.connected")
        LiveRow("State", "camera.state")
        LiveRow("Temperature", "camera.temperature", "°C", 1)
        LiveRow("Cooling", "camera.cooling")
        ConnectionButtons("camera", "camera")
        OperationForm("Set temperature", "camera.set_temperature")
    }
}

@Composable
private fun FilterCard() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    Card("Filter wheel") {
        LiveRow("Current", "filter.current")
        val names = (app.value("filter.names")?.value as? JsonArray)?.mapNotNull { it.string }
        if (app.canOperate && names != null) {
            Choices(names, app.value("filter.current")?.value.string ?: "", minWidth = 96) { name ->
                scope.launch { app.perform("filter.select", mapOf("name" to JsonPrimitive(name))) }
            }
        }
        app.record("filter.select")?.let { OutcomeNote(it) }
    }
}

@Composable
private fun RotatorCard() {
    Card("Rotator") {
        LiveRow("Angle", "rotator.angle", "°", 1)
        OperationForm("Move", "rotator.move")
    }
}

@Composable
private fun DomeCard() {
    Card("Dome") {
        LiveRow("State", "dome.state")
        LiveRow("Azimuth", "dome.azimuth", "°", 1)
        ButtonRow(
            { OperationButton("Open", "dome.open") },
            { OperationButton("Close", "dome.close") },
            { OperationButton("Stop", "dome.abort") },
        )
    }
}

/** Mount Control: the mount's manual movement on a screen of its own, so Equipment can still be scrolled. */
@Composable
fun MountControlScreen() {
    ScreenScroll("Mount Control") {
        ReadOnlyHint()
        JogPad()
    }
}

/**
 * The mount's manual movement, as large as the screen allows (about one full screen on a phone) so it can be used in
 * the dark: a 3 x 3 pad of the eight directions with a Stop in the middle. Each arrow moves
 * the mount only while it is held (CSURF-330); the diagonals move both axes.
 */
@Composable
private fun JogPad() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val scope = rememberCoroutineScope()
    if (!app.canOperate) return
    val info = app.operation("mount.hold_start")
    if (info == null) {
        Note("Jog: this Master doesn't offer held mount movement yet.")
        return
    }
    var level by remember { mutableStateOf(app.pref("mount.jogLevel", "7").toIntOrNull()?.coerceIn(1, 9) ?: 7) }
    val takesLevel = info.arguments.any { it.name == "level" }
    fun direction(name: String): Map<String, kotlinx.serialization.json.JsonElement> =
        buildMap { put("direction", JsonPrimitive(name)); if (takesLevel) put("level", JsonPrimitive(level)) }
    // The screen less the top bar, the area strip and the screen's padding.
    val height = (LocalConfiguration.current.screenHeightDp - 200 - (if (takesLevel) 96 else 0)).coerceAtLeast(280).dp
    val rows = listOf(
        listOf(Triple("North West", "↖", "northwest"), Triple("North", "↑", "north"), Triple("North East", "↗", "northeast")),
        listOf(Triple("West", "←", "west"), null, Triple("East", "→", "east")),
        listOf(Triple("South West", "↙", "southwest"), Triple("South", "↓", "south"), Triple("South East", "↘", "southeast")),
    )
    Column(Modifier.fillMaxWidth().height(height), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { cell ->
                    if (cell == null) {
                        Box(
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(theme.danger)
                                .clickable(role = androidx.compose.ui.semantics.Role.Button) { scope.launch { app.perform("mount.abort_slew") } }
                                .testTag("mount.abort_slew").semantics { contentDescription = "Stop" },
                            contentAlignment = Alignment.Center,
                        ) { Txt("■", size = 40.sp, weight = FontWeight.Bold, color = theme.onDanger) }
                    } else {
                        HoldButton(cell.first, cell.second, "mount", direction(cell.third), Modifier.weight(1f).fillMaxHeight(), large = true)
                    }
                }
            }
        }
    }
    if (takesLevel) JogSpeed(level) { level = it; app.setPref("mount.jogLevel", it.toString()) }
    val diagonals = info.arguments.firstOrNull { it.name == "direction" }?.choices
    if (diagonals != null && "northeast" !in diagonals) Note("This Master doesn't offer diagonal moves yet: update Galileo on the Master.")
    app.snapshot.hold.explanation?.let { Note(it) }
}

/** Manual focus and autofocus, only through the focus module's operations (MAPP-FOC-*). */
@Composable
fun FocusScreen() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    ScreenScroll("Focus") {
        ReadOnlyHint()
        Columns(
            left = {
                Card("Autofocus") {
                    LiveRow("State", "focus.state")
                    LiveRow("Last result", "focus.last_result")
                    LiveRow("Temperature compensation", "focus.compensation")
                    // Not OperationButtons: their notes would be squeezed into half the width, and a run's progress needs all of it.
                    val run = app.operation("focus.autofocus")
                    val record = app.record("focus.autofocus")
                    val running = record?.state?.isOpen == true
                    var focusMessage by remember { mutableStateOf<String?>(null) }
                    if (app.canOperate) {
                        ButtonRow(
                            {
                                ActionButton("Run autofocus", kind = ActionKind.Prominent, busy = running, enabled = run != null && run.available && !running, tag = "focus.autofocus") {
                                    scope.launch { focusMessage = app.perform("focus.autofocus") }
                                }
                            },
                            {
                                ActionButton("Cancel", enabled = app.operation("focus.autofocus_cancel") != null, tag = "focus.autofocus_cancel") {
                                    scope.launch { focusMessage = app.perform("focus.autofocus_cancel") }
                                }
                            },
                        )
                        when {
                            run == null -> Note("This Master doesn't offer autofocus yet.")
                            !run.available && !running && run.unavailableBecause != null -> Note(run.unavailableBecause!!)
                            focusMessage != null -> Note(focusMessage!!)
                            record != null -> OutcomeNote(record)
                        }
                    }
                    VCurve()
                }
            },
            right = {
                Card("Manual focus") {
                    // Double-sized and first, so they can be found and held in the dark.
                    if (app.canOperate && app.operation("focus.hold_start") != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                HoldButton("Hold in", "−", "focus", mapOf("direction" to JsonPrimitive(-1)), minHeight = minimumTarget * 2)
                            }
                            Column(Modifier.weight(1f)) {
                                HoldButton("Hold out", "+", "focus", mapOf("direction" to JsonPrimitive(1)), minHeight = minimumTarget * 2)
                            }
                        }
                        app.snapshot.hold.explanation?.let { Note(it) }
                    }
                    LiveRow("Position", "focus.position")
                    LiveRow("Temperature", "focus.temperature", "°C", 1)
                    LiveRow("Moving", "focus.moving")
                    if (app.canOperate) {
                        listOf(1000, 100, 10).forEach { step ->
                            ButtonRow(
                                { StepButton(-step) },
                                { StepButton(step) },
                            )
                        }
                        app.record("focus.move_by")?.let { OutcomeNote(it) }
                    }
                    OperationButton("Halt", "focus.halt", kind = ActionKind.Destructive)
                    OperationForm("Move to position", "focus.move_to")
                }
            },
        )
    }
}

@Composable
private fun StepButton(steps: Int) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    ActionButton(
        if (steps > 0) "Out +$steps" else "In $steps", enabled = app.operation("focus.move_by") != null, tag = "focus.step.$steps",
    ) { scope.launch { app.perform("focus.move_by", mapOf("steps" to JsonPrimitive(steps.toDouble()))) } }
}

@Composable
private fun VCurve() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val points = app.workingPier?.let { app.snapshot.live.piers[it]?.series?.get("focus.vcurve") }?.takeLast(30) ?: emptyList()
    val samples = points.mapNotNull { point ->
        val position = point.value["position"].double ?: return@mapNotNull null
        val hfr = point.value["hfr"].double ?: return@mapNotNull null
        position to hfr
    }
    if (samples.isEmpty()) {
        Note("The V-curve appears here during a run.")
    } else {
        Chart(listOf(ChartSeries(samples, theme.accent, scatter = true)), "V-curve, ${samples.size} points", height = 180, tag = "vcurve")
    }
}

/** The meridian flip, shown with the Mount on the Equipment screen: its state and time remaining, and running one when the Master allows it (MAPP-FLP-*). */
@Composable
private fun MeridianFlipCard() {
    val app = LocalApp.current
    Card("Meridian flip") {
        LiveRow("State", "flip.state")
        LiveRow("Minutes to the flip", "flip.minutes", digits = 1)
        LiveRow("Automatic flips", "flip.enabled")
        LiveRow("Pointing", "flip.pointing")
        app.value("flip.last.outcome")?.value.string?.let { Note("Last flip: $it.") }
        app.value("flip.why_not")?.value.string?.takeIf { it.isNotEmpty() }?.let { Note(it) }
        OperationButton("Flip now", "flip.now", kind = ActionKind.Prominent)
        OperationButton("Defer the flip", "flip.defer")
    }
}

/**
 * Every input and the overall state. The only action on a safety event is to acknowledge it: nothing here can disable
 * the monitor, clear an unsafe state, resume after a stop or override a limit (MAPP-SAF-*).
 */
@Composable
fun SafetyScreen() {
    val app = LocalApp.current
    ScreenScroll("Safety") {
        Columns(
            left = {
                Card("Overall") {
                    LiveRow("State", "safety.overall")
                    if (app.value("session.stop_reason") != null) LiveRow("Session stopped because", "session.stop_reason")
                    if (app.value("safety.acknowledged") != null) LiveRow("Acknowledged", "safety.acknowledged")
                    // No lease is needed; the Master classes this as lease-free.
                    if (app.snapshot.role == Role.Operator) AcknowledgeButton()
                }
            },
            right = {
                Card("Inputs") {
                    val inputs = app.children("safety.inputs")
                    if (inputs.isEmpty()) Note("The Master reports no inputs for this Pier.")
                    inputs.forEach { LiveRow(it, "safety.inputs.$it") }
                }
                Card("Weather") {
                    LiveRow("Temperature", "weather.temperature_c", "°C", 1)
                    LiveRow("Humidity", "weather.humidity_pct", "%")
                    LiveRow("Wind", "weather.wind_kmh", "km/h")
                }
            },
        )
    }
}

@Composable
private fun AcknowledgeButton() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionButton(
            "Acknowledge", kind = if (app.overallSafety == "unsafe") ActionKind.Prominent else ActionKind.Normal,
            enabled = app.operation("safety.acknowledge") != null, tag = "safety.acknowledge",
        ) { scope.launch { message = app.perform("safety.acknowledge") } }
        val record = app.record("safety.acknowledge")
        when {
            message != null -> Note(message!!)
            record != null -> OutcomeNote(record)
        }
    }
}

/**
 * The app's own preferences and pairings. The observatory is configured on the desktop, and nothing here can change it
 * (MAPP-CFG-010); what's shown of it is read-only (MAPP-CFG-020).
 */
@Composable
fun SettingsScreen() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    ScreenScroll("Settings") {
        Columns(
            left = {
                Card("Theme") {
                    ButtonRow(
                        *ThemeChoice.entries.map { choice ->
                            @Composable {
                                ActionButton(
                                    choice.label, kind = if (app.theme == choice) ActionKind.Prominent else ActionKind.Normal,
                                    tag = "theme.${choice.name}", description = if (app.theme == choice) "${choice.label}, selected" else choice.label,
                                ) { app.chooseTheme(choice) }
                            }
                        }.toTypedArray(),
                    )
                    if (app.theme == ThemeChoice.Night) {
                        Note("Lower the screen brightness as well. System keyboards can't be made red: Android's colour correction or a red screen filter (Settings › Accessibility) can turn the whole screen red.")
                    }
                }
                Card("This Master") {
                    ValueRow("Name", app.masterName)
                    ValueRow("Galileo", app.snapshot.galileoVersion ?: "—")
                    ValueRow("This device", app.snapshot.role?.wire ?: "—")
                    if (app.isDemo) {
                        ActionButton("Leave the demo", kind = ActionKind.Prominent, tag = "demo.leave") { app.backToWelcome() }
                    } else {
                        UnpairButton()
                    }
                }
            },
            right = {
                Card("Piers") {
                    app.snapshot.piers.forEach { pier ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Txt(pier.name, size = 15.sp, weight = FontWeight.SemiBold)
                            Txt(pier.node + (pier.observatory?.let { " · $it" } ?: ""), size = 13.sp, color = theme.textSecondary)
                        }
                    }
                    Note("Equipment, Piers and limits are set on the desktop.")
                }
                Card("About") {
                    ValueRow("App version", com.galileo.mobileconnect.AppViewModel.APP_VERSION)
                    Note("This app collects nothing. It talks only to your own Master.")
                }
            },
        )
    }
}

@Composable
private fun UnpairButton() {
    val app = LocalApp.current
    var asking by remember { mutableStateOf(false) }
    if (asking) {
        Note("This device will forget the Master. Remove the pairing on the Master as well.")
        ButtonRow(
            { ActionButton("Keep") { asking = false } },
            { ActionButton("Unpair", kind = ActionKind.Destructive, tag = "unpair.confirm") { app.unpairCurrent() } },
        )
    } else {
        ActionButton("Unpair", tag = "unpair") { asking = true }
    }
}

/** Running saved sessions, and the job queue (MAPP-SES-010 to 030, MAPP-SCH-010). Authoring is P2. */
@Composable
fun SessionsScreen() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    ScreenScroll("Sessions") {
        ReadOnlyHint()
        Columns(
            left = {
                Card("Running") {
                    LiveRow("Session", "session.name")
                    LiveRow("State", "session.state")
                    LiveRow("Now", "session.block")
                    val index = app.value("session.block_index")?.value.int
                    val total = app.value("session.block_total")?.value.int
                    if (index != null && total != null && total > 0) {
                        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                            Txt("Block", color = theme.textSecondary, modifier = Modifier.weight(1f))
                            Txt("$index of $total", weight = FontWeight.SemiBold)
                        }
                    }
                    app.value("session.message")?.value.string?.let { Note(it) }
                    app.value("session.progress")?.value.double?.let { ThemedProgress(it, "Session progress") }
                    if (app.value("session.frames_total")?.value.double != null) {
                        LiveRow("Frames done", "session.frames_done")
                        LiveRow("Of", "session.frames_total")
                    }
                    if (app.value("session.stop_reason")?.value.string != null) LiveRow("Stopped because", "session.stop_reason")
                    ButtonRow(
                        { OperationButton("Pause", "session.pause") },
                        { OperationButton("Resume", "session.resume") },
                    )
                    ButtonRow(
                        { OperationButton("Skip step", "session.skip") },
                        { OperationButton("Stop", "session.stop", kind = ActionKind.Destructive) },
                    )
                }
                SavedSessions()
            },
            right = { JobQueue() },
        )
    }
}

@Composable
private fun SavedSessions() {
    val app = LocalApp.current
    Card("Saved sessions") {
        val names = (app.value("session.templates")?.value as? JsonArray)?.mapNotNull { it.string } ?: emptyList()
        if (names.isEmpty()) Note("The Master reports no saved sessions.")
        app.sessionTarget?.let { target ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Txt("Target from the sky: ${target.name}", weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Column(Modifier.heightIn(min = 0.dp)) { ActionButton("Clear", tag = "session.target.clear") { app.sessionTarget = null } }
            }
        }
        names.forEach { name ->
            if (app.canOperate) {
                val args = mutableMapOf<String, kotlinx.serialization.json.JsonElement>("template" to JsonPrimitive(name))
                app.sessionTarget?.let {
                    args["target_name"] = JsonPrimitive(it.name)
                    args["ra_deg"] = JsonPrimitive(it.raDeg)
                    args["dec_deg"] = JsonPrimitive(it.decDeg)
                }
                OperationButton("Run $name", "session.run", args)
            } else {
                Txt(name)
            }
        }
    }
}

@Composable
private fun JobQueue() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    Card("Job queue") {
        val jobs = (app.value("scheduler.jobs")?.value as? JsonArray) ?: JsonArray(emptyList())
        if (jobs.isEmpty()) Note("Nothing queued on this Pier.")
        app.value("scheduler.next")?.value.string?.let { next ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Txt("Next", color = theme.textSecondary, modifier = Modifier.weight(1f))
                Txt(next, weight = FontWeight.SemiBold)
            }
        }
        jobs.forEach { job ->
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(job["name"].string ?: "Job", size = 15.sp, weight = FontWeight.SemiBold)
                Txt(jobDetail(job), size = 13.sp, color = theme.textSecondary)
            }
        }
    }
}

private fun jobDetail(job: kotlinx.serialization.json.JsonElement): String {
    val parts = mutableListOf<String>()
    job["run_state"].string?.let { parts += it.replaceFirstChar { c -> c.uppercase() } }
    val done = job["frames_captured"].int
    val total = job["total_required"].int
    if (done != null && total != null && total > 0) parts += "$done of $total frames"
    return parts.joinToString(", ")
}

/** The jog speed slider, under the pad: a stepped track drawn here (a system slider can't be held to Night). */
@Composable
private fun JogSpeed(level: Int, onLevel: (Int) -> Unit) {
    val theme = LocalTheme.current
    val thumb = 36.dp
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // No rate in deg/s: each telescope slews at its own speeds, so the level is all that is true of every mount.
        Txt("Speed $level", weight = FontWeight.SemiBold)
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxWidth().height(44.dp)
                .testTag("mount.speed")
                .semantics { contentDescription = "Jog speed"; stateDescription = "$level of 9" }
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = {}, onDrag = { change, _ ->
                        change.consume()
                        val width = size.width.toFloat()
                        val fraction = ((change.position.x - thumb.toPx() / 2) / (width - thumb.toPx())).coerceIn(0f, 1f)
                        onLevel(1 + (fraction * 8).roundToInt())
                    })
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val width = size.width.toFloat()
                        val fraction = ((offset.x - thumb.toPx() / 2) / (width - thumb.toPx())).coerceIn(0f, 1f)
                        onLevel(1 + (fraction * 8).roundToInt())
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val travel = maxWidth - thumb
            val position = travel * ((level - 1) / 8f)
            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(theme.raised))
            Box(Modifier.width(position + thumb / 2).height(14.dp).clip(RoundedCornerShape(7.dp)).background(theme.accent))
            Box(Modifier.padding(start = position).size(thumb).clip(androidx.compose.foundation.shape.CircleShape).background(theme.text))
        }
    }
}
