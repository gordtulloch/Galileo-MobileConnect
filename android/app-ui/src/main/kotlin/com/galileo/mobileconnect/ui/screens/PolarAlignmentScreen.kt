package com.galileo.mobileconnect.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galileo.mobileconnect.core.state.double
import com.galileo.mobileconnect.core.state.get
import com.galileo.mobileconnect.core.state.string
import com.galileo.mobileconnect.ui.ActionKind
import com.galileo.mobileconnect.ui.Card
import com.galileo.mobileconnect.ui.LiveRow
import com.galileo.mobileconnect.ui.LocalApp
import com.galileo.mobileconnect.ui.Note
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.galileo.mobileconnect.core.api.ArgumentInfo
import com.galileo.mobileconnect.core.state.bool
import com.galileo.mobileconnect.ui.ActionButton
import com.galileo.mobileconnect.ui.ButtonRow
import com.galileo.mobileconnect.ui.Field
import com.galileo.mobileconnect.ui.OutcomeNote
import com.galileo.mobileconnect.ui.SingleLineChoices
import com.galileo.mobileconnect.ui.Toggle
import com.galileo.mobileconnect.ui.argumentTitle
import com.galileo.mobileconnect.ui.send
import com.galileo.mobileconnect.ui.theme.LocalCompact
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import com.galileo.mobileconnect.ui.ScreenScroll
import com.galileo.mobileconnect.ui.Txt
import com.galileo.mobileconnect.ui.theme.LocalTheme

/**
 * Polar alignment (MAPP-SOL-030, CSURF-145): the error in numbers at the top, the Start and Stop controls, and the latest
 * frame last, small, since the picture is not what the person looks at. The routine measures with three solves and then
 * keeps solving while the altitude and azimuth knobs are turned, so it runs until it is stopped.
 */
@Composable
fun PolarAlignmentScreen() {
    val app = LocalApp.current
    val version = app.value("polar.state.frame.version")?.value.double?.toInt()
    val solveVersion = app.value("solve.frame.version")?.value.double?.toInt()
    ScreenScroll("Polar Alignment") {
        ReadOnlyHint()
        PolarAlignmentReadout()
        PolarControls()
        Card("Latest frame") {
            Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(10.dp))) {
                FramePicture(50.0, false, 0, "${version ?: 0}/${solveVersion ?: 0}", version != null || solveVersion != null)
            }
        }
    }
}

/**
 * The live error as figures: the total, the north-south (altitude) and east-west (azimuth) deviations, and in words what
 * to turn. All of it is the Master's `polar.state`, refreshed on every solve.
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
    @Composable
    fun figure(label: String, value: String, tag: String, colour: Color = theme.text) {
        Row(
            Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = "$label $value" },
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(label, color = theme.textSecondary)
            Txt(value, size = 22.sp, weight = FontWeight.Bold, color = colour)
        }
    }
    Card("Alignment Results") {
        if (text("polar.state.phase") != null) {
            // What it is doing now, then the last few things it did, so a long step is never a silent one.
            text("polar.state.message")?.let { Txt(it, weight = FontWeight.SemiBold) }
            if (app.value("polar.state.running")?.value.bool == true) {
                val log = (app.value("polar.state.log")?.value as? JsonArray)?.mapNotNull { it.string } ?: emptyList()
                log.takeLast(5).dropLast(1).forEach { Note(it) }
            }
        } else {
            Note("Polar alignment hasn't been run.")
        }
        number("polar.state.error.total_arcsec")?.let { figure("Total", polarAngle(it), "polar.total", band) }
        // The Master's sign: altitude positive when the axis is too high, azimuth positive when it is east of the pole.
        number("polar.state.error.altitude_arcsec")?.let {
            figure("N–S (altitude)", "${polarAngle(it)} ${if (it >= 0) "high" else "low"}", "polar.altitude")
        }
        number("polar.state.error.azimuth_arcsec")?.let {
            figure("E–W (azimuth)", "${polarAngle(it)} ${if (it >= 0) "east" else "west"}", "polar.azimuth")
        }
        if (number("polar.state.error.total_arcsec") != null) {
            text("polar.state.error.altitude_advice")?.let { Txt(it) }
            text("polar.state.error.azimuth_advice")?.let { Txt(it) }
        }
    }
}


/**
 * Start and Stop on one line straight under the results, then the parameters they use. The two are separate
 * composables sharing the form's values, so the buttons can sit above the fields.
 */
@Composable
private fun PolarControls() {
    val app = LocalApp.current
    // Remembered before anything can return, so what was set survives a moment without control of the Pier.
    val scope = rememberCoroutineScope()
    val text = remember { mutableStateMapOf<String, String>() }
    val flags = remember { mutableStateMapOf<String, Boolean>() }
    var message by remember { mutableStateOf<String?>(null) }
    if (!app.canOperate) return
    val info = app.operation("polar.start")
    val record = app.record("polar.start")
    val running = record?.state?.isOpen == true
    val shown = listOf("exposure_s", "step_deg", "direction", "refraction", "slew_from_zenith")
        .mapNotNull { name -> info?.arguments?.firstOrNull { it.name == name } }
    ButtonRow(
        {
            ActionButton("Start Polar Alignment", kind = ActionKind.Prominent, busy = running, enabled = info != null && info.available && !running, tag = "polar.start") {
                scope.launch { message = send(app, "polar.start", shown, text, flags) }
            }
        },
        { ActionButton("Stop Polar Alignment", enabled = app.operation("polar.stop") != null, tag = "polar.stop") { scope.launch { message = app.perform("polar.stop") } } },
    )
    when {
        info == null -> Note("This Master doesn't offer this yet.")
        !info.available && !running && info.unavailableBecause != null -> Note(info.unavailableBecause!!)
        message != null -> Note(message!!)
        record != null -> OutcomeNote(record)
    }
    Card("Polar Alignment Parameters") {
        Note("Turns the mount to three positions and solves a frame at each, then follows your adjustments live until you stop it.")
        CompositionLocalProvider(LocalCompact provides true) {
            @Composable
            fun cell(argument: ArgumentInfo) {
                when (argument.kind) {
                    ArgumentInfo.Kind.Enum -> {
                        val selected = text[argument.name] ?: argument.defaultValue.string ?: argument.choices.firstOrNull() ?: ""
                        SingleLineChoices(argument.choices, selected) { text[argument.name] = it }
                    }
                    // The switch right beside its own label, so which one it belongs to is never in doubt.
                    ArgumentInfo.Kind.Bool ->
                        Toggle(argumentTitle(argument.name), flags[argument.name] ?: argument.defaultValue.bool ?: false, { flags[argument.name] = it }, tight = true)
                    else -> Field("polar.start", argument, text, flags)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 700.dp) {
                    // A tablet: every parameter on one line.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                        shown.forEach { argument -> Box(Modifier.weight(if (argument.kind == ArgumentInfo.Kind.Enum) 1.4f else 1f)) { cell(argument) } }
                    }
                } else {
                    // A phone: the numbers and the direction, then the two switches, each on a line that fits.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            shown.filter { it.kind != ArgumentInfo.Kind.Enum && it.kind != ArgumentInfo.Kind.Bool }.forEach { Box(Modifier.weight(1f)) { cell(it) } }
                        }
                        shown.filter { it.kind == ArgumentInfo.Kind.Enum }.forEach { cell(it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                            shown.filter { it.kind == ArgumentInfo.Kind.Bool }.forEach { Box(Modifier.weight(1f)) { cell(it) } }
                        }
                    }
                }
            }
        }
    }
}
