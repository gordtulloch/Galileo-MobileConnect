package com.galileo.mobileconnect.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import com.galileo.mobileconnect.ui.Txt
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.galileo.mobileconnect.core.link.PreviewSource
import com.galileo.mobileconnect.core.state.bool
import com.galileo.mobileconnect.core.state.double
import com.galileo.mobileconnect.core.state.displayText
import com.galileo.mobileconnect.core.state.get
import com.galileo.mobileconnect.core.state.string
import com.galileo.mobileconnect.ui.ActionKind
import com.galileo.mobileconnect.ui.ButtonRow
import com.galileo.mobileconnect.ui.Card
import com.galileo.mobileconnect.ui.Chart
import com.galileo.mobileconnect.ui.ChartSeries
import com.galileo.mobileconnect.ui.Columns
import com.galileo.mobileconnect.ui.LiveRow
import com.galileo.mobileconnect.ui.LocalApp
import com.galileo.mobileconnect.ui.Note
import com.galileo.mobileconnect.ui.OperationButton
import com.galileo.mobileconnect.ui.ActionButton
import com.galileo.mobileconnect.ui.ScreenScroll
import com.galileo.mobileconnect.ui.Spinner
import com.galileo.mobileconnect.ui.theme.LocalTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.min
import kotlin.math.pow

/** The guider service and its error trace (MAPP-GUI-*). */
@Composable
fun GuidingScreen() {
    ScreenScroll("Guiding") {
        ReadOnlyHint()
        Columns(
            left = {
                Card("Guide error") { GuideTrace() }
            },
            right = {
                Card("Control") {
                    ButtonRow(
                        { OperationButton("Connect", "guiding.connect") },
                        { OperationButton("Disconnect", "guiding.disconnect") },
                    )
                    ButtonRow(
                        { OperationButton("Loop", "guiding.loop") },
                        { OperationButton("Start guiding", "guiding.start", kind = ActionKind.Prominent) },
                        { OperationButton("Stop", "guiding.stop") },
                    )
                    ButtonRow(
                        { OperationButton("Dither", "guiding.dither") },
                        { OperationButton("Recalibrate", "guiding.recalibrate") },
                    )
                    OperationButton("Find star", "guiding.find_star")
                }
            },
        )
        Card("Guide star") { GuidePicture() }
    }
}

/** The state, RMS error and star SNR on one small line, laid over the foot of the trace. */
@Composable
private fun GuideReadout(modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val theme = LocalTheme.current
    fun number(key: String, digits: Int): String? =
        app.value(key)?.value.double?.let { "%.${digits}f".format(it) }
    val parts = listOfNotNull(
        app.value("guiding.state")?.value?.displayText(),
        number("guiding.rms_ra", 2)?.let { "RA $it″" },
        number("guiding.rms_dec", 2)?.let { "Dec $it″" },
        number("guiding.star_snr", 0)?.let { "SNR $it" },
    )
    Txt(
        parts.joinToString("   ").ifEmpty { "No guide data yet." },
        modifier.background(theme.surface.copy(alpha = 0.75f)).padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("guide.readout"),
        size = 11.sp, color = theme.textSecondary,
    )
}

@Composable
private fun GuideTrace() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val points = app.workingPier?.let { app.snapshot.live.piers[it]?.series?.get("guiding.error") }?.takeLast(120) ?: emptyList()
    val start = points.firstOrNull()?.asOf?.toEpochMilli() ?: 0L
    val ra = points.mapNotNull { p -> p.value["ra"].double?.let { (p.asOf.toEpochMilli() - start) / 1000.0 to it } }
    val dec = points.mapNotNull { p -> p.value["dec"].double?.let { (p.asOf.toEpochMilli() - start) / 1000.0 to it } }
    if (ra.isEmpty() && dec.isEmpty()) {
        GuideReadout()
        Note("The trace appears here while guiding.")
    } else {
        // Told apart by dash as well as colour: Night has only the one hue.
        Box(contentAlignment = Alignment.BottomCenter) {
            Chart(
                listOf(ChartSeries(ra, theme.accent), ChartSeries(dec, theme.good, dashed = true)),
                "Guide error trace", yRange = -2.0..2.0, height = 220, tag = "guide.trace",
                modifier = Modifier.then(if (app.snapshot.isConnected) Modifier else Modifier.background(theme.surface)),
            )
            GuideReadout(Modifier.padding(bottom = 28.dp))
        }
        Note("Solid: RA. Dashed: Dec. Arc-seconds.")
    }
}

/**
 * The guide star as the Master has it: PHD2's close-up of the star, which is all PHD2 sends a network client. Small, and
 * not tappable: the star is chosen in PHD2 (or by the Master's Find star).
 */
@Composable
private fun GuidePicture() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    val state = app.value("guiding.state")?.value?.displayText() ?: ""
    val request = "${app.workingPier}/$state/${app.value("guiding.rms_ra")?.value?.displayText()}"

    suspend fun load() {
        val star = app.read("guiding.star")
        val width = star["width"].double?.toInt()
        val height = star["height"].double?.toInt()
        val encoded = star["pixels"].string
        val rendered = if (star["available"].bool != false && width != null && height != null && encoded != null) {
            renderCutout(Base64.decode(encoded, Base64.DEFAULT), width, height)
        } else null
        if (rendered != null) {
            image = rendered.asImageBitmap()
            problem = null
        } else if (image == null) {
            problem = "No guide star yet."
        }
    }

    LaunchedEffect(request) { load() }
    LaunchedEffect(Unit) {
        // The Master only keeps fetching the picture while someone is looking.
        while (true) {
            delay(2000)
            load()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(150.dp).background(theme.background), contentAlignment = Alignment.Center) {
            val shown = image
            if (shown != null) {
                Canvas(
                    Modifier.fillMaxHeight().aspectRatio(shown.width.toFloat() / shown.height)
                        .testTag("guide.picture")
                        .semantics { contentDescription = "Guide star" },
                ) {
                    drawImage(
                        shown, dstSize = IntSize(size.width.toInt(), size.height.toInt()), dstOffset = IntOffset.Zero,
                        filterQuality = FilterQuality.None,
                        colorFilter = ColorFilter.tint(theme.imageTint, BlendMode.Modulate),
                    )
                }
            } else if (problem != null) {
                Note(problem!!, Modifier.then(Modifier))
            } else {
                Spinner()
            }
        }
    }
}

/**
 * The cut-out's 16-bit little-endian pixels as a grey picture, stretched from the background to just under the
 * brightest, with a square root so a faint star isn't lost beside a bright one (the desktop does the same).
 */
fun renderCutout(pixels: ByteArray, width: Int, height: Int): Bitmap? {
    val count = width * height
    if (width <= 0 || height <= 0 || pixels.size != count * 2) return null
    val values = DoubleArray(count) { i -> ((pixels[i * 2].toInt() and 0xFF) or ((pixels[i * 2 + 1].toInt() and 0xFF) shl 8)).toDouble() }
    val sorted = values.sortedArray()
    val low = sorted[count / 2]
    var high = sorted[min(count - 1, (count * 0.998).toInt())]
    if (high <= low) high = low + 1
    val argb = IntArray(count) { i ->
        val grey = (255 * ((values[i] - low) / (high - low)).coerceIn(0.0, 1.0).pow(0.5)).toInt()
        (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
    }
    return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
}

