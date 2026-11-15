package com.galileo.mobileconnect.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.galileo.mobileconnect.ui.OperationButton
import com.galileo.mobileconnect.ui.OperationForm
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
        Card("Polar alignment") {
            Note("Turns the mount to three positions and solves a frame at each, then follows your adjustments live until you stop it.")
            OperationForm(
                "Start polar alignment", "polar.start",
                fields = listOf("exposure_s", "step_deg", "direction", "refraction", "slew_from_zenith"), kind = ActionKind.Prominent,
            )
            OperationButton("Stop polar alignment", "polar.stop")
        }
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
    Card("Polar error") {
        if (text("polar.state.phase") != null) {
            LiveRow("Phase", "polar.state.phase")
            text("polar.state.message")?.let { Note(it) }
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
