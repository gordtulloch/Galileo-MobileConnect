package com.galileo.mobileconnect.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galileo.mobileconnect.core.state.displayText
import com.galileo.mobileconnect.core.state.double
import com.galileo.mobileconnect.ui.ActionButton
import com.galileo.mobileconnect.ui.ActionKind
import com.galileo.mobileconnect.ui.Banner
import com.galileo.mobileconnect.ui.ButtonRow
import com.galileo.mobileconnect.ui.LocalApp
import com.galileo.mobileconnect.ui.Note
import com.galileo.mobileconnect.ui.OperationButton
import com.galileo.mobileconnect.ui.OperationForm
import com.galileo.mobileconnect.ui.TextBox
import com.galileo.mobileconnect.ui.ThemedProgress
import com.galileo.mobileconnect.ui.ThemedSlider
import com.galileo.mobileconnect.ui.Txt
import com.galileo.mobileconnect.ui.compactTarget
import com.galileo.mobileconnect.ui.theme.LocalCompact
import com.galileo.mobileconnect.ui.theme.LocalTheme
import kotlinx.serialization.json.JsonArray
import kotlin.math.max

/**
 * Capture, and the current frame as the Master renders it (MAPP-IMG-*). The frame gets the whole screen: the settings
 * slide down over it from the top, and progress is drawn along its bottom edge.
 */
@Composable
fun ImagingScreen() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    var panelOpen by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var stretch by remember { mutableStateOf(50.0) }
    // The live stack rather than the latest exposure, when there is one to show.
    var showStack by remember { mutableStateOf(false) }
    val stackFrames = app.value("imaging.stack.count")?.value.double?.toInt()

    // A stack that has just begun is what the user is waiting to watch; the latest exposure is one tap away.
    LaunchedEffect(stackFrames == null) { showStack = stackFrames != null }

    Box(Modifier.fillMaxSize().background(theme.background).pointerInput(panelOpen) { detectTapGestures { if (panelOpen) panelOpen = false } }) {
        FramePicture(stretch, showStack && stackFrames != null, stackFrames ?: 0)
        Box(Modifier.align(Alignment.TopStart)) { Statistics() }
        Column(Modifier.align(Alignment.BottomCenter), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StackPicker(stackFrames != null, showStack) { showStack = !showStack }
            Progress(stackFrames)
            StretchBar(stretch, { stretch = it })
        }
        Column(Modifier.align(Alignment.TopCenter).padding(if (panelOpen) 0.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            app.workingPier?.let { app.snapshot.unavailableReason(it) }?.let { Banner("⚠", "${app.pier?.name ?: "This Pier"}: $it") }
            if (panelOpen) Panel(more, { more = !more }, { panelOpen = false })
        }
        if (!panelOpen) {
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).width(com.galileo.mobileconnect.ui.minimumTarget)) {
                ActionButton("⚙", tag = "imaging.panel", description = "Capture settings") { panelOpen = true }
            }
        }
    }
}

/** Small, and in the top corner where the settings button isn't. */
@Composable
private fun Statistics() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val stars = app.value("imaging.stats.stars")
    val hfr = app.value("imaging.stats.hfr")
    val fwhm = app.value("imaging.stats.fwhm")
    if (stars == null && hfr == null && fwhm == null) return
    Column(
        Modifier.padding(8.dp).clip(RoundedCornerShape(6.dp)).background(theme.veil(0.6f)).padding(5.dp).testTag("imaging.stats")
            .semantics(mergeDescendants = true) {},
    ) {
        stars?.let { Txt("Stars ${it.value.displayText()}", size = 11.sp, weight = FontWeight.SemiBold) }
        hfr?.value.double?.let { Txt("HFR %.2f".format(it), size = 11.sp, weight = FontWeight.SemiBold) }
        fwhm?.value.double?.let { Txt("FWHM %.2f".format(it), size = 11.sp, weight = FontWeight.SemiBold) }
    }
}

/** A checkbox for the stack over the latest exposure, only while there is a stack to show. */
@Composable
private fun StackPicker(available: Boolean, checked: Boolean, onToggle: () -> Unit) {
    if (!available) return
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
        Row(
            Modifier.heightIn(min = compactTarget).clip(RoundedCornerShape(7.dp)).background(theme.veil(0.6f))
                .clickable(role = Role.Checkbox, onClick = onToggle).padding(horizontal = 10.dp).testTag("imaging.showstack")
                .semantics { contentDescription = "Show stack. Checked shows the live stack; unchecked shows the latest exposure."; stateDescription = if (checked) "checked" else "unchecked" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Txt(if (checked) "☑" else "☐", size = 15.sp)
            Txt("Show stack", size = 13.sp, weight = FontWeight.SemiBold)
        }
    }
}

/**
 * The stretch, along the bottom edge of the picture where it is within reach while looking at it. The Master renders the
 * picture with it, so it agrees with the desktop (MAPP-IMG-030).
 */
@Composable
private fun StretchBar(stretch: Double, onChange: (Double) -> Unit) {
    val theme = LocalTheme.current
    Row(
        Modifier.fillMaxWidth().background(theme.veil(0.6f)).padding(horizontal = 12.dp, vertical = 6.dp).testTag("imaging.stretch"),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt("Stretch: ${stretch.toInt()}", size = 13.sp, color = theme.textSecondary)
            ThemedSlider("Stretch", stretch, 0.0..100.0, onChange)
        }
        CompositionLocalProvider(LocalCompact provides true) {
            Box(Modifier.width(72.dp)) {
                ActionButton("Reset", enabled = stretch.toInt() != 50, tag = "imaging.stretch.reset", description = "Reset stretch") { onChange(50.0) }
            }
        }
    }
}

/** Exposure and live stack, as bars along the bottom of the picture and nothing more. */
@Composable
private fun Progress(stackFrames: Int?) {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val exposing = app.value("imaging.exposure_progress")?.value.double
    val series = app.value("imaging.progress")?.value.double
    val stacked = app.value("imaging.stack.progress")?.value.double
    if (exposing == null && series == null && stacked == null && !app.liveLoopRunning) return
    Column(
        Modifier.fillMaxWidth().background(theme.veil(0.6f)).padding(horizontal = 12.dp, vertical = 8.dp).testTag("imaging.progress"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (exposing != null) {
            val seconds = app.value("imaging.exposure_s")?.value.double
            Bar("Exposure", seconds?.let { "${if (it == Math.rint(it)) it.toLong().toString() else "%.1f".format(it)} s" }, exposing)
        }
        if (series != null) {
            val frame = app.value("imaging.frame")?.value?.displayText()
            val count = app.value("imaging.count")?.value?.displayText()
            Bar("Series", if (frame != null && count != null) "frame $frame of $count" else null, series)
        } else if (exposing == null && app.liveLoopRunning) {
            Txt("Live loop", size = 11.sp, weight = FontWeight.SemiBold)
        }
        if (stacked != null) Bar("Live stack", stackText(stackFrames, app.value("imaging.stack.total_exposure_s")?.value.double), stacked)
    }
}

private fun stackText(frames: Int?, total: Double?): String? {
    if (frames == null) return null
    var text = "$frames frame${if (frames == 1) "" else "s"}"
    if (total != null) {
        val t = total.toInt()
        text += ", " + listOfNotNull(
            (t / 3600).takeIf { it > 0 }?.let { "${it}h" }, (t % 3600 / 60).takeIf { it > 0 }?.let { "${it}m" }, (t % 60).takeIf { it > 0 || t == 0 }?.let { "${it}s" },
        ).joinToString(" ")
    }
    return text
}

@Composable
private fun Bar(label: String, detail: String?, value: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt(listOfNotNull(label, detail).joinToString(" · "), size = 11.sp, weight = FontWeight.SemiBold)
        ThemedProgress(value, label)
    }
}

/** The settings that matter every time, small; the rest one tap further. */
@Composable
private fun Panel(more: Boolean, onMore: () -> Unit, onHide: () -> Unit) {
    val theme = LocalTheme.current
    CompositionLocalProvider(LocalCompact provides true) {
        Column(
            Modifier.fillMaxWidth().background(theme.surface).border(1.dp, theme.border).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ReadOnlyHint()
            OperationForm("Start", "imaging.capture", fields = listOf("seconds", "count", "frame_type", "gain", "offset", "live_stack"), kind = ActionKind.Prominent)
            ButtonRow(
                { OperationButton("Stop", "imaging.stop") },
                { ActionButton(if (more) "Less" else "More", tag = "imaging.more", onClick = onMore) },
                { ActionButton("Hide", tag = "imaging.panel.close", onClick = onHide) },
            )
            if (more) {
                Histogram()
                OperationButton("Abort exposure", "camera.abort")
                OperationForm("Single exposure", "camera.expose", fields = listOf("seconds", "binning"))
                LiveLoop()
            }
        }
    }
}

/** MAPP-IMG-020: one exposure after another, each shown as it arrives. */
@Composable
private fun LiveLoop() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    var seconds by remember { mutableStateOf("2") }
    var message by remember { mutableStateOf<String?>(null) }
    if (!app.canOperate || app.operation("imaging.capture") == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = compactTarget).clip(RoundedCornerShape(7.dp)).background(theme.raised).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt("Loop (s)", size = 13.sp, color = theme.textSecondary)
                TextBox(seconds, { seconds = it }, "2", keyboard = KeyboardType.Decimal, label = "Live loop seconds", tag = "liveloop.seconds", align = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
            Box(Modifier.weight(1f)) {
                ActionButton(
                    if (app.liveLoopRunning) "Stop loop" else "Live loop", kind = if (app.liveLoopRunning) ActionKind.Destructive else ActionKind.Normal, tag = "liveloop",
                ) {
                    val value = seconds.toDoubleOrNull()
                    when {
                        app.liveLoopRunning -> app.stopLiveLoop()
                        value != null && value > 0 -> {
                            message = null
                            app.startLiveLoop(value)
                        }
                        else -> message = "Seconds must be a number above zero."
                    }
                }
            }
        }
        (message ?: app.liveLoopMessage)?.let { Note(it) }
    }
}

/** From the Master's numbers, not computed here (MAPP-IMG-040). */
@Composable
private fun Histogram() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val bins = (app.value("imaging.histogram")?.value as? JsonArray)?.mapNotNull { it.double } ?: return
    if (bins.isEmpty()) return
    val highest = max(1.0, bins.max())
    Canvas(Modifier.fillMaxWidth().height(56.dp).testTag("histogram").semantics { contentDescription = "Histogram" }) {
        val width = size.width / bins.size
        bins.forEachIndexed { index, bin ->
            val height = (size.height * (bin / highest)).toFloat()
            drawRect(theme.accent, topLeft = Offset(index * width, size.height - height), size = Size(max(1f, width - 1f), height))
        }
    }
}

/** A progress bar for a value the Master gives as 0 to 1, dimmed when it is not live. */
@Composable
fun ProgressRow(key: String, label: String) {
    val app = LocalApp.current
    val view = app.value(key) ?: return
    val value = view.value.double ?: return
    Box(Modifier.alpha(if (view.stale) 0.4f else 1f)) { ThemedProgress(value, label) }
}
