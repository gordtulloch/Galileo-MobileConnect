package com.galileo.mobileconnect.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galileo.mobileconnect.core.state.bool
import com.galileo.mobileconnect.core.state.displayText
import com.galileo.mobileconnect.core.state.double
import com.galileo.mobileconnect.core.state.get
import com.galileo.mobileconnect.core.state.string
import com.galileo.mobileconnect.ui.ActionButton
import com.galileo.mobileconnect.ui.ActionKind
import com.galileo.mobileconnect.ui.Banner
import com.galileo.mobileconnect.ui.Card
import com.galileo.mobileconnect.ui.LiveRow
import com.galileo.mobileconnect.ui.LocalApp
import com.galileo.mobileconnect.ui.Note
import com.galileo.mobileconnect.ui.OperationButton
import com.galileo.mobileconnect.ui.OperationForm
import com.galileo.mobileconnect.ui.Spinner
import com.galileo.mobileconnect.ui.TextBox
import com.galileo.mobileconnect.ui.Txt
import com.galileo.mobileconnect.ui.isWide
import com.galileo.mobileconnect.ui.minimumTarget
import com.galileo.mobileconnect.ui.spacing
import com.galileo.mobileconnect.ui.theme.LocalTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.max

/** The panes beside the preview on the Solve screen, in the order they stack. */
private enum class SolvePane { Control, Result, Polar, Error }

/**
 * Plate solving: solve where the mount is or sync it (MAPP-SOL-010, 020); centring on a position is the Sky screen's Goto
 * and Centre, and polar alignment (MAPP-SOL-030). The latest frame is on the left and the panes are stacked on the right,
 * which can be hidden as a whole, so that the picture can have the screen to itself.
 */
@Composable
fun SolveScreen() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    var menuHidden by remember { mutableStateOf(app.pref("solve.menuHidden", "false") == "true") }
    val shown = if (menuHidden) emptyList() else SolvePane.entries

    /** The frame polar alignment last solved. Each refresh is a new frame, so each is a new picture. */
    val polarVersion = app.value("polar.state.frame.version")?.value.double?.toInt()
    val polarAdjusting = app.value("polar.state.running")?.value.bool == true && app.value("polar.state.phase")?.value.string == "adjusting"
    /** The frame a solve has just captured, announced as it arrives, so it is on screen while it is being solved. */
    val solveFrameVersion = app.value("solve.frame.version")?.value.double?.toInt()
    val solvedCount = (app.value("solve.last.solutions")?.value as? JsonArray)?.size ?: 0
    /** The frame being solved, once there is one, rather than the latest exposure: this screen is about the solve's frame. */
    val showsSolveFrame = polarVersion != null || solveFrameVersion != null || solvedCount > 0
    /** A new solve or a new polar-alignment refresh means a new picture, fetched at once: the loop is the point. */
    val refresh = "${app.value("solve.running")?.value?.displayText() ?: ""}/$solvedCount/${polarVersion ?: 0}/${solveFrameVersion ?: 0}"

    fun list(key: String) = (app.value(key)?.value as? JsonArray)?.mapNotNull { it.double }
    val marks: PolarMarks? = run {
        if (!polarAdjusting || polarVersion == null) return@run null
        val width = app.value("polar.state.frame.width")?.value.double ?: return@run null
        val height = app.value("polar.state.frame.height")?.value.double ?: return@run null
        val axis = list("polar.state.frame.axis_px")?.takeIf { it.size == 2 } ?: return@run null
        val pole = list("polar.state.frame.pole_px")?.takeIf { it.size == 2 } ?: return@run null
        val scale = app.value("polar.state.frame.arcmin_per_px")?.value.double ?: return@run null
        PolarMarks(refresh, width, height, Offset(axis[0].toFloat(), axis[1].toFloat()), Offset(pole[0].toFloat(), pole[1].toFloat()), scale)
    }

    val picture: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            FramePicture(50.0, false, 0, refresh, showsSolveFrame, marks)
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).width(minimumTarget)) {
                ActionButton(
                    if (menuHidden) "◧" else "◨", tag = "solve.menu", description = if (menuHidden) "Show menu" else "Hide menu",
                ) {
                    menuHidden = !menuHidden
                    app.setPref("solve.menuHidden", menuHidden.toString())
                }
            }
        }
    }
    val column: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxSize().background(theme.background).verticalScroll(rememberScrollState()).padding(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            ReadOnlyHint()
            shown.forEach { pane ->
                when (pane) {
                    SolvePane.Control -> SolverControl()
                    SolvePane.Result -> SolutionCoordinates()
                    SolvePane.Polar -> PolarAlignmentControls()
                    SolvePane.Error -> PolarAlignmentReadout()
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(theme.background)) {
        app.workingPier?.let { app.snapshot.unavailableReason(it) }?.let { Banner("⚠", "${app.pier?.name ?: "This Pier"}: $it", Modifier.padding(8.dp)) }
        if (isWide()) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { picture() }
                if (shown.isNotEmpty()) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(theme.border))
                    Box(Modifier.width(400.dp).fillMaxHeight()) { column() }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(if (shown.isEmpty()) Modifier.weight(1f).fillMaxWidth() else Modifier.height(260.dp).fillMaxWidth()) { picture() }
                if (shown.isNotEmpty()) Box(Modifier.weight(1f).fillMaxWidth()) { column() }
            }
        }
    }
}

/**
 * Starting and stopping polar alignment (MAPP-SOL-030, CSURF-145). The routine measures with three solves and then keeps
 * solving while the altitude and azimuth knobs are turned, so it runs until it is stopped.
 */
@Composable
private fun PolarAlignmentControls() {
    Card("Polar alignment") {
        Note("Turns the mount to three positions and solves a frame at each, then follows your adjustments live until you stop it.")
        OperationForm(
            "Start polar alignment", "polar.start",
            fields = listOf("exposure_s", "step_deg", "direction", "refraction", "slew_from_zenith"), kind = ActionKind.Prominent,
        )
        OperationButton("Stop polar alignment", "polar.stop")
    }
}

/**
 * The live adjustment display: the error as large figures with a trend, in words what to turn, and where the pole lies
 * against the axis in the latest frame. All of it is the Master's `polar.state`, refreshed on every solve.
 */
@Composable
private fun PolarAlignmentReadout() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    fun number(key: String) = app.value(key)?.value.double
    fun text(key: String) = app.value(key)?.value.string?.takeIf { it.isNotEmpty() }
    val band = when (text("polar.state.error.quality")) {
        "good" -> theme.good
        "fair" -> theme.warning
        else -> theme.danger
    }
    Card("Polar error") {
        if (text("polar.state.phase") != null) {
            LiveRow("Phase", "polar.state.phase")
            text("polar.state.message")?.let { Note(it) }
        } else {
            Note("Polar alignment hasn't been run.")
        }
        val total = number("polar.state.error.total_arcsec")
        if (total != null) {
            Txt(
                polarAngle(total), size = 44.sp, weight = FontWeight.Bold, color = band,
                modifier = Modifier.testTag("polar.total").semantics { contentDescription = "Polar error ${polarAngle(total)}" },
            )
            text("polar.state.error.altitude_advice")?.let { Txt(it) }
            text("polar.state.error.azimuth_advice")?.let { Txt(it) }
            val offset = (app.value("polar.state.target_offset_arcmin")?.value as? JsonArray)?.mapNotNull { it.double }
            PolarTargetPlot(offset, Modifier.fillMaxWidth().height(220.dp))
            val trend = (app.value("polar.state.error.trend")?.value as? JsonArray)?.mapNotNull { it.double }
            if (trend != null && trend.size > 1) {
                PolarTrend(trend, band, Modifier.fillMaxWidth().height(60.dp))
                Note(if (trend.last() < trend.first()) "The error is falling." else "The error is not falling yet.")
            }
        }
    }
}

/** The pole (a cross) against the point the axis aims at (the circle at the centre): bring the cross into the circle. */
@Composable
private fun PolarTargetPlot(offset: List<Double>?, modifier: Modifier) {
    val theme = LocalTheme.current
    Canvas(modifier.clip(RoundedCornerShape(10.dp)).background(theme.raised)) {
        val centre = Offset(size.width / 2, size.height / 2)
        val half = minOf(size.width, size.height) / 2 - 12.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        drawLine(theme.border, Offset(0f, centre.y), Offset(size.width, centre.y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        drawLine(theme.border, Offset(centre.x, 0f), Offset(centre.x, size.height), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        drawCircle(theme.accent, radius = 14.dp.toPx(), center = centre, style = Stroke(2.dp.toPx()))
        if (offset == null || offset.size != 2) return@Canvas
        val range = max(5.0, max(abs(offset[0]) * 1.2, abs(offset[1]) * 1.2)) // never zoomed in tighter than 5′
        val at = Offset(centre.x + (offset[0] / range * half).toFloat(), centre.y + (offset[1] / range * half).toFloat())
        val arm = 9.dp.toPx()
        drawLine(theme.danger, Offset(at.x - arm, at.y - arm), Offset(at.x + arm, at.y + arm), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        drawLine(theme.danger, Offset(at.x - arm, at.y + arm), Offset(at.x + arm, at.y - arm), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** The recent totals as a line, so the person can see whether turning the knob is helping. */
@Composable
private fun PolarTrend(values: List<Double>, colour: Color, modifier: Modifier) {
    val theme = LocalTheme.current
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(theme.raised)) {
        val high = values.max()
        if (high <= 0) return@Canvas
        val line = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.size - 1)
            val y = size.height - (size.height - 8.dp.toPx()) * (value / high).toFloat() - 4.dp.toPx()
            if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        drawPath(line, colour, style = Stroke(2.dp.toPx()))
    }
}

/** What to do with the answer, as the desktop's Solver Action: raw values are what `solve.capture` takes. */
private enum class SolverAction(val raw: String, val title: String, val detail: String) {
    Sync("sync", "Sync", "Tell the mount where it is really pointing."),
    Slew("slew", "Slew to Target", "Slew to the target, then solve, sync and slew again until within the accuracy."),
    Nothing("nothing", "Nothing", "Only solve. The mount is not touched."),
}

/**
 * The desktop's Solver Control and Solver Action (MAPP-SOL-010, 020): the bullseye of where each solution landed against
 * its target, the exposure, Capture & Solve, Load & Slew and Stop, and what to do with the answer.
 */
@Composable
private fun SolverControl() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val scope = rememberCoroutineScope()
    // Kept on the device: Slew to Target unless the person chose otherwise.
    var action by remember { mutableStateOf(SolverAction.entries.firstOrNull { it.raw == app.pref("solve.action", "slew") } ?: SolverAction.Slew) }
    var exposure by remember { mutableStateOf(app.pref("solve.exposure", "5")) }
    var choosing by remember { mutableStateOf(false) }
    var frames by remember { mutableStateOf<List<JsonElement>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    Card("Solver Control") {
        val points = (app.value("solve.points")?.value as? JsonArray)?.mapNotNull { point ->
            val pair = (point as? JsonArray)?.mapNotNull { it.double }
            if (pair != null && pair.size == 2) pair[0] to pair[1] else null
        } ?: emptyList()
        val ring = app.value("solve.accuracy_arcsec")?.value.double ?: 30.0
        SolveBullseye(points, ring, Modifier.fillMaxWidth().height(220.dp).testTag("solve.bullseye").semantics { contentDescription = "Where each solution landed against its target, ${points.size} shown" })
        // Not while a solve is running: the Master's reason is then "a plate solve is already running", which is this one.
        if (app.value("solve.running")?.value.bool != true) app.value("solve.why_not")?.value.string?.takeIf { it.isNotEmpty() }?.let { Note(it) }
        if (app.canOperate) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = minimumTarget).clip(RoundedCornerShape(8.dp)).background(theme.raised).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt("Exposure (s)", color = theme.textSecondary)
                TextBox(
                    exposure, { exposure = it; app.setPref("solve.exposure", it) }, "5", keyboard = KeyboardType.Decimal,
                    label = "Exposure in seconds", tag = "solve.exposure", align = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
            // Whatever was typed, else the default; the Master's range check names a bad value.
            val exposureValue: JsonElement = exposure.trim().toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(exposure)
            OperationButton(
                "Capture & Solve", "solve.capture", mapOf("exposure_s" to exposureValue, "action" to JsonPrimitive(action.raw)), ActionKind.Prominent,
            )
            ActionButton("Load & Slew…", enabled = app.operation("solve.load")?.available == true, tag = "solve.load.choose") {
                choosing = !choosing
                if (choosing) {
                    frames = null
                    scope.launch { frames = (app.read("solve.frames")?.get("frames") as? JsonArray)?.toList() ?: emptyList() }
                }
            }
            if (app.operation("solve.load") == null) Note("This Master doesn't offer Load & Slew yet.")
            if (choosing) FrameList(frames, message, onChoose = { id ->
                choosing = false
                scope.launch { message = app.perform("solve.load", mapOf("frame_id" to JsonPrimitive(id))) }
            })
            OperationButton("Stop", "solve.stop")
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Heading("Solver Action")
                SolverAction.entries.forEach { choice -> Radio(choice, choice == action) { action = choice; app.setPref("solve.action", choice.raw) } }
                Note(action.detail)
            }
        }
    }
}

@Composable
private fun Heading(text: String) = com.galileo.mobileconnect.ui.Heading(text, size = 15.sp)

/** A radio button: not a system picker or toggle, which can't be held to Night. */
@Composable
private fun Radio(choice: SolverAction, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = minimumTarget).clickable(role = Role.RadioButton, onClick = onClick).testTag("solve.action.${choice.raw}")
            .semantics { stateDescription = if (on) "selected" else "not selected" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Txt(if (on) "◉" else "○")
        Txt(choice.title, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

/** Recent Library frames on the Master, one tap to solve it and slew there. */
@Composable
private fun FrameList(frames: List<JsonElement>?, message: String?, onChoose: (String) -> Unit) {
    val theme = LocalTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Note("Solve a frame from the Library and slew to where it points.")
        if (frames != null) {
            if (frames.isEmpty()) Note("The Library has no frames to choose from.")
            frames.forEachIndexed { index, frame ->
                val id = frame["id"].double?.let { it.toLong().toString() } ?: frame["id"].string
                Column(
                    Modifier.fillMaxWidth().heightIn(min = minimumTarget).clip(RoundedCornerShape(8.dp)).background(theme.raised)
                        .clickable(role = Role.Button) { id?.let(onChoose) }.padding(horizontal = 10.dp, vertical = 6.dp).testTag("solve.load.$index"),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Txt(frame["name"].string ?: "Frame", size = 15.sp, weight = FontWeight.SemiBold)
                    Txt(listOfNotNull(frame["object"].string, frame["filter"].string, frame["date"].string).filter { it.isNotEmpty() }.joinToString(" · "), size = 12.sp, color = theme.textSecondary)
                }
            }
        } else {
            Spinner()
        }
        message?.let { Note(it) }
    }
}

/**
 * The desktop's bullseye: each solution's offset from its target (dRA across, dDE up) in arcseconds, with rings at one,
 * two and three times the accuracy asked for, the newest solution brightest.
 */
@Composable
private fun SolveBullseye(points: List<Pair<Double, Double>>, ring: Double, modifier: Modifier) {
    val theme = LocalTheme.current
    val measurer = rememberTextMeasurer()
    Canvas(modifier.clip(RoundedCornerShape(10.dp)).background(theme.raised)) {
        val side = minOf(size.width - 40.dp.toPx(), size.height - 46.dp.toPx()) // room under the plot for the two caption lines
        if (side <= 40.dp.toPx()) return@Canvas
        val left = (size.width - side) / 2 + 8.dp.toPx()
        val top = 4.dp.toPx()
        val centre = Offset(left + side / 2, top + side / 2)
        val reach = max(ring * 3.3, (points.maxOfOrNull { max(abs(it.first), abs(it.second)) } ?: 0.0) * 1.15)
        val k = (side / 2 / reach).toFloat()
        drawLine(theme.border, Offset(left, centre.y), Offset(left + side, centre.y), strokeWidth = 1.dp.toPx())
        drawLine(theme.border, Offset(centre.x, top), Offset(centre.x, top + side), strokeWidth = 1.dp.toPx())
        for ((multiple, colour) in listOf(3.0 to theme.danger, 2.0 to theme.warning, 1.0 to theme.good)) {
            val radius = (multiple * ring).toFloat() * k
            drawCircle(colour.copy(alpha = 0.14f), radius = radius, center = centre)
            drawCircle(colour, radius = radius, center = centre, style = Stroke(1.4.dp.toPx()))
        }
        points.forEachIndexed { index, (dx, dy) ->
            val age = (index + 1).toFloat() / points.size
            drawCircle(theme.text.copy(alpha = 0.3f + 0.7f * age), radius = 3.dp.toPx(), center = Offset(centre.x + dx.toFloat() * k, centre.y - dy.toFloat() * k))
        }
        val style = TextStyle(color = theme.textSecondary, fontSize = 11.sp)
        val a = measurer.measure("dRA (arcsec) →   dDE ↑", style)
        drawText(a, topLeft = Offset(left + side / 2 - a.size.width / 2f, top + side + 4.dp.toPx()))
        val b = measurer.measure("rings ${if (ring == Math.rint(ring)) ring.toLong().toString() else ring.toString()}″ × 1, 2, 3", style)
        drawText(b, topLeft = Offset(left + side / 2 - b.size.width / 2f, top + side + 19.dp.toPx()))
    }
}

/**
 * The desktop's Solution Coordinates (JNow) for the latest solved frame: where it points now, and how that compares with
 * the target and with the optics set up.
 */
@Composable
private fun SolutionCoordinates() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    Card("Latest result") {
        LiveRow("Solving now", "solve.running")
        val list = (app.value("solve.last.solutions")?.value as? JsonArray) ?: JsonArray(emptyList())
        val solved = list.lastOrNull { it["success"].bool == true }
        if (solved != null) {
            com.galileo.mobileconnect.ui.Heading("Solution Coordinates (JNow)", size = 15.sp)
            Row2("RA", solved["jnow_ra_hours"].double?.let(::hms))
            Row2("DE", solved["jnow_dec_deg"].double?.let(::dms))
            Row2("Err", errorText(solved))
            Row2("Pix", solved["scale_arcsec_px"].double?.let { "%.2f″".format(it) })
            Row2("PA", solved["rotation_deg"].double?.let { "%.2f°".format(it) })
            Row2("FOV", fovText(solved))
            Row2("R", solved["scale_ratio"].double?.let { "%.2fx".format(it) })
            Row2("FL", pair(solved["focal_mm"].double, solved["expected_focal_mm"].double))
            Box(Modifier.testTag("solve.solution")) { Row2("F/", pair(solved["focal_ratio"].double, solved["expected_focal_ratio"].double)) }
        } else if (list.lastOrNull()?.get("success").bool == false) {
            Txt("⚠ " + (list.last()["failure_reason"].string ?: "This frame didn't solve."), size = 15.sp, color = theme.warning)
        } else {
            Note("Nothing solved yet.")
        }
    }
}

@Composable
private fun Row2(label: String, value: String?) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Txt(label, color = theme.textSecondary, modifier = Modifier.weight(1f))
        Txt(value ?: "—", weight = FontWeight.SemiBold, align = TextAlign.End, modifier = Modifier.padding(start = 12.dp))
    }
}

private fun errorText(solution: JsonElement): String? {
    val total = solution["error_arcsec"].double ?: return null
    var text = "%.1f″".format(total)
    val ra = solution["d_ra_arcsec"].double
    val dec = solution["d_dec_arcsec"].double
    if (ra != null && dec != null) text += "  (dRA %+.1f″, dDE %+.1f″)".format(ra, dec)
    return text
}

private fun fovText(solution: JsonElement): String? {
    val size = (solution["fov_arcmin"] as? JsonArray)?.mapNotNull { it.double } ?: return null
    return if (size.size == 2) "%.1f′ × %.1f′".format(size[0], size[1]) else null
}

/** The solved value, and in brackets what the optics set up predict, as the desktop does. */
private fun pair(solved: Double?, expected: Double?): String? {
    if (solved == null) return null
    return "%.1f".format(solved) + (expected?.let { " (%.1f)".format(it) } ?: "")
}

fun hms(hours: Double): String {
    val total = hours * 3600
    return "%02dh %02dm %04.1fs".format(((total / 3600).toInt()) % 24, ((total / 60).toInt()) % 60, total % 60)
}

fun dms(degrees: Double): String {
    val total = abs(degrees) * 3600
    return "%s%02d° %02d′ %02d″".format(if (degrees < 0) "−" else "+", (total / 3600).toInt(), ((total / 60).toInt()) % 60, Math.round(total).toInt() % 60)
}

