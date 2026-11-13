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
import androidx.compose.foundation.layout.fillMaxWidth
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
                Card("Guide star") { GuidePicture() }
                Card("Guide error") {
                    LiveRow("State", "guiding.state")
                    LiveRow("RMS RA", "guiding.rms_ra", "″", 2)
                    LiveRow("RMS Dec", "guiding.rms_dec", "″", 2)
                    LiveRow("Star SNR", "guiding.star_snr")
                    GuideTrace()
                }
            },
            right = {
                Card("Control") {
                    ButtonRow(
                        { OperationButton("Connect", "guiding.connect") },
                        { OperationButton("Disconnect", "guiding.disconnect") },
                    )
                    ButtonRow(
                        { OperationButton("Start guiding", "guiding.start", kind = ActionKind.Prominent) },
                        { OperationButton("Stop guiding", "guiding.stop") },
                    )
                    ButtonRow(
                        { OperationButton("Dither", "guiding.dither") },
                        { OperationButton("Recalibrate", "guiding.recalibrate") },
                    )
                    OperationButton("Find star", "guiding.find_star")
                }
            },
        )
    }
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
        Note("The trace appears here while guiding.")
    } else {
        // Told apart by dash as well as colour: Night has only the one hue.
        Chart(
            listOf(ChartSeries(ra, theme.accent), ChartSeries(dec, theme.good, dashed = true)),
            "Guide error trace", yRange = -2.0..2.0, height = 220, tag = "guide.trace",
            modifier = Modifier.then(if (app.snapshot.isConnected) Modifier else Modifier.background(theme.surface)),
        )
        Note("Solid: RA. Dashed: Dec. Arc-seconds.")
    }
}

/**
 * The guide star as the Master has it: PHD2's close-up of the star, or the whole guide frame. A tap on either asks PHD2
 * to choose the star there (MAPP-GUI-030); the Master looks for the best star in a small box around the tap, so it need
 * not be exact. Taps are sent as where on the picture they landed, in that picture's own pixels, and the Master works
 * out where that is on the guide frame.
 */
@Composable
private fun GuidePicture() {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val scope = rememberCoroutineScope()
    var fullFrame by remember { mutableStateOf(false) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val state = app.value("guiding.state")?.value?.displayText() ?: ""
    // PHD2 only takes a new star while looping; the Master says no at any other time, and so is only asked then.
    val canChoose = state in setOf("Looping", "Selected", "LostLock")
    val request = "${app.workingPier}/$fullFrame/$state/${app.value("guiding.rms_ra")?.value?.displayText()}"

    suspend fun load() {
        if (fullFrame) {
            val shot = app.preview(1200, 50, PreviewSource.Guide)
            val decoded = shot?.let { BitmapFactory.decodeByteArray(it.data, 0, it.data.size) }
            if (decoded != null && shot.frameWidth != null && shot.frameHeight != null) {
                image = decoded.asImageBitmap()
                problem = null
            } else if (image == null) {
                problem = "No guide frame yet. The Master can only show it when PHD2 runs on the Master's computer."
            }
        } else {
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
    }

    LaunchedEffect(request) { load() }
    LaunchedEffect(fullFrame) {
        // The Master only keeps fetching the picture (for the frame, a file write each) while someone is looking.
        while (true) {
            delay(2000)
            load()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ButtonRow(
            { ActionButton("Star", kind = if (!fullFrame) ActionKind.Prominent else ActionKind.Normal, tag = "guide.show.Star") { fullFrame = false; image = null } },
            { ActionButton("Full frame", kind = if (fullFrame) ActionKind.Prominent else ActionKind.Normal, tag = "guide.show.Full frame") { fullFrame = true; image = null } },
        )
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(theme.background), contentAlignment = Alignment.Center) {
            val shown = image
            if (shown != null) {
                val density = LocalDensity.current
                Canvas(
                    Modifier.fillMaxWidth().aspectRatio(shown.width.toFloat() / shown.height)
                        .testTag("guide.picture")
                        .semantics { contentDescription = if (fullFrame) "Guide frame" else "Guide star" }
                        .pointerInput(canChoose, fullFrame, shown) {
                            detectTapGestures { tap ->
                                if (!canChoose) return@detectTapGestures
                                val x = tap.x / size.width * shown.width
                                val y = tap.y / size.height * shown.height
                                scope.launch {
                                    message = app.perform(
                                        "guiding.select_star",
                                        mapOf(
                                            "x" to JsonPrimitive(x.toDouble()), "y" to JsonPrimitive(y.toDouble()),
                                            "space" to JsonPrimitive(if (fullFrame) "frame" else "cutout"),
                                        ),
                                    )
                                }
                            }
                        },
                ) {
                    drawImage(
                        shown, dstSize = IntSize(size.width.toInt(), size.height.toInt()), dstOffset = IntOffset.Zero,
                        filterQuality = if (fullFrame) FilterQuality.Medium else FilterQuality.None,
                        colorFilter = ColorFilter.tint(theme.imageTint, BlendMode.Modulate),
                    )
                }
            } else if (problem != null) {
                Note(problem!!, Modifier.then(Modifier))
            } else {
                Spinner()
            }
        }
        message?.let { Note(it) }
        Note(
            if (canChoose) "Tap a star to guide on it."
            else "Loop exposures to choose a star; the star can't be changed while guiding.",
        )
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

