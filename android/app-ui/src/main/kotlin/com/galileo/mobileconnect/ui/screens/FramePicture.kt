package com.galileo.mobileconnect.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galileo.mobileconnect.core.link.PreviewSource
import com.galileo.mobileconnect.core.state.displayText
import com.galileo.mobileconnect.ui.LocalApp
import com.galileo.mobileconnect.ui.Note
import com.galileo.mobileconnect.ui.Spinner
import com.galileo.mobileconnect.ui.theme.LocalTheme
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Where polar alignment's circle (the point the mount's axis aims at) and cross (the celestial pole) lie in the frame
 * whose `refresh` this is, in that frame's pixels from its top left.
 */
data class PolarMarks(
    val refresh: String,
    val frameWidth: Double,
    val frameHeight: Double,
    val axis: Offset,
    val pole: Offset,
    val arcminPerPixel: Double,
)

private const val MAX_ZOOM = 16f
private const val TILE_PIXELS = 256
private const val MAXIMUM_TILES = 24

/** The current frame, as large as the space it's given, with pinch zoom and finer tiles from the Master as it zooms. */
@Composable
fun FramePicture(
    stretch: Double,
    stack: Boolean,
    stackFrames: Int,
    refresh: String = "",
    solve: Boolean = false,
    marks: PolarMarks? = null,
) {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val density = LocalDensity.current.density
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var frameSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    /** The `refresh` the picture on screen was fetched for, so that marks for another frame are not drawn on it. */
    var loadedRefresh by remember { mutableStateOf("") }

    val source = if (solve) PreviewSource.Solve else if (stack) PreviewSource.Stack else PreviewSource.Current
    // A new frame, Pier or stretch means a new picture from the Master.
    val request = "${app.workingPier}/${app.value("imaging.frame")?.value?.displayText()}/${app.record("imaging.capture")?.id}/" +
        "${app.record("imaging.capture")?.state?.isOpen}/${stretch.toInt()}/$stack/$stackFrames/$refresh/$solve"

    LaunchedEffect(request) {
        // Give a dragged slider a moment to settle; the current image stays until the new one arrives.
        delay(if (solve) 0 else 180)
        val wanted = refresh
        val shot = app.preview((1600 * minOf(density, 2f) / 2).toInt(), stretch.toInt(), source)
        val decoded = shot?.let { BitmapFactory.decodeByteArray(it.data, 0, it.data.size) }
        if (decoded != null) {
            image = decoded.asImageBitmap()
            loadedRefresh = wanted
            val w = shot.frameWidth
            val h = shot.frameHeight
            if (w != null && h != null) frameSize = w to h
            problem = null
        } else if (image == null) {
            problem = "No frame yet."
        }
    }

    Box(Modifier.fillMaxSize().background(theme.background), contentAlignment = Alignment.Center) {
        val shown = image
        when {
            shown != null -> ZoomableImage(shown, frameSize, stretch.toInt(), request, source, marks?.takeIf { it.refresh == loadedRefresh })
            problem != null -> Note(problem!!, Modifier.padding(16.dp))
            else -> Spinner()
        }
    }
}

/** A tile held for the frame being looked at: where it goes, in frame pixels, and its picture. */
private class HeldTile(val x: Float, val y: Float, val width: Float, val height: Float, val image: ImageBitmap)

@Composable
private fun ZoomableImage(
    image: ImageBitmap, frameSize: Pair<Int, Int>?, stretch: Int, generation: String, source: PreviewSource, marks: PolarMarks?,
) {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer()
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(Size.Zero) }
    val tiles: SnapshotStateMap<String, HeldTile> = remember(generation) { mutableStateMapOf() }
    val pending = remember(generation) { HashSet<String>() }

    /** Where the frame sits inside the view, which fits it with bars at the sides or ends. */
    fun fitted(frame: Pair<Int, Int>): androidx.compose.ui.geometry.Rect {
        val scale = min(size.width / frame.first, size.height / frame.second)
        val w = frame.first * scale
        val h = frame.second * scale
        return androidx.compose.ui.geometry.Rect((size.width - w) / 2, (size.height - h) / 2, (size.width + w) / 2, (size.height + h) / 2)
    }

    // Tiles for what is on screen, once zoomed in: the coarsest level that is still sharp (ADR-AND-005).
    LaunchedEffect(zoom, offset, generation, frameSize, size) {
        val frame = frameSize ?: return@LaunchedEffect
        if (zoom <= 1.2f || size.width <= 0f) return@LaunchedEffect
        delay(120)
        val fit = fitted(frame)
        val pixelsOnScreen = fit.width * zoom
        val level = max(0, min(12, floor(ln(frame.first / max(1f, pixelsOnScreen)) / ln(2f)).toInt()))
        val span = TILE_PIXELS * (1 shl level).toFloat() // one tile, in frame pixels
        val perPixel = fit.width / frame.first // view points per frame pixel
        // The visible part of the view, undone from the zoom: screen = (p - c) * zoom + c + offset.
        val c = Offset(size.width / 2, size.height / 2)
        fun back(p: Offset) = Offset((p.x - c.x - offset.x) / zoom + c.x, (p.y - c.y - offset.y) / zoom + c.y)
        val topLeft = back(Offset.Zero)
        val bottomRight = back(Offset(size.width, size.height))
        val left = max(topLeft.x, fit.left)
        val top = max(topLeft.y, fit.top)
        val right = min(bottomRight.x, fit.right)
        val bottom = min(bottomRight.y, fit.bottom)
        if (right <= left || bottom <= top) return@LaunchedEffect
        val columns = floor((left - fit.left) / perPixel / span).toInt()..floor((right - fit.left - 0.01f) / perPixel / span).toInt()
        val rows = floor((top - fit.top) / perPixel / span).toInt()..floor((bottom - fit.top - 0.01f) / perPixel / span).toInt()
        if (columns.count() * rows.count() > MAXIMUM_TILES) return@LaunchedEffect
        tiles.keys.filterNot { it.startsWith("$level/") }.forEach { tiles.remove(it) }
        for (y in rows) for (x in columns) {
            val key = "$level/$x/$y"
            if (tiles[key] != null || !pending.add(key)) continue
            val data = app.tile(level, x, y, stretch, source)
            pending.remove(key)
            val bitmap = data?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: continue
            tiles[key] = HeldTile(
                x * span, y * span, bitmap.width * (1 shl level).toFloat(), bitmap.height * (1 shl level).toFloat(), bitmap.asImageBitmap(),
            )
        }
    }

    Canvas(
        Modifier.fillMaxSize()
            .testTag("preview.image")
            .semantics { contentDescription = "Current frame. Pinch to zoom." }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, gesture, _ ->
                    val c = Offset(this.size.width / 2f, this.size.height / 2f)
                    val newZoom = (zoom * gesture).coerceIn(1f, MAX_ZOOM)
                    // Keep the point under the fingers where it is, then follow them.
                    var o = offset
                    o = (centroid - c) - ((centroid - c) - o) * (newZoom / zoom) + pan
                    val limitX = (newZoom - 1f) * this.size.width / 2f
                    val limitY = (newZoom - 1f) * this.size.height / 2f
                    zoom = newZoom
                    offset = Offset(o.x.coerceIn(-limitX, limitX), o.y.coerceIn(-limitY, limitY))
                }
            },
    ) {
        size = this.size
        val c = Offset(this.size.width / 2, this.size.height / 2)
        clipRect {
            withTransform({
                translate(offset.x, offset.y)
                scale(zoom, zoom, pivot = c)
            }) {
                val frame = frameSize
                val fit = if (frame != null) fitted(frame) else androidx.compose.ui.geometry.Rect(0f, 0f, this.size.width, this.size.height)
                // The picture is fitted by its own shape when the Master hasn't said how big the frame is.
                val box = if (frame != null) fit else fitImage(image, this.size)
                drawImage(
                    image, dstOffset = IntOffset(box.left.toInt(), box.top.toInt()), dstSize = IntSize(box.width.toInt(), box.height.toInt()),
                    colorFilter = ColorFilter.tint(theme.imageTint, BlendMode.Modulate),
                )
                if (frame != null) {
                    val perPixel = fit.width / frame.first
                    for (tile in tiles.values) {
                        drawImage(
                            tile.image, dstOffset = IntOffset((fit.left + tile.x * perPixel).toInt(), (fit.top + tile.y * perPixel).toInt()),
                            dstSize = IntSize((tile.width * perPixel).toInt() + 1, (tile.height * perPixel).toInt() + 1),
                            colorFilter = ColorFilter.tint(theme.imageTint, BlendMode.Modulate),
                        )
                    }
                    if (marks != null) drawMarks(marks, fit, zoom, theme.accent, theme.danger, theme.warning, measurer, density)
                }
            }
        }
    }
}

private fun fitImage(image: ImageBitmap, view: Size): androidx.compose.ui.geometry.Rect {
    val scale = min(view.width / image.width, view.height / image.height)
    val w = image.width * scale
    val h = image.height * scale
    return androidx.compose.ui.geometry.Rect((view.width - w) / 2, (view.height - h) / 2, (view.width + w) / 2, (view.height + h) / 2)
}

/**
 * Draws the marks over the picture: a circle where the axis aims, a cross at the pole, and the vector between them with
 * its length, which is how far and which way to move the axis. It lives inside the zoomed view so it stays on the stars,
 * and draws at a size that does not grow as the picture is zoomed.
 */
private fun DrawScope.drawMarks(
    marks: PolarMarks, fit: androidx.compose.ui.geometry.Rect, zoom: Float, circleColour: Color, crossColour: Color, vectorColour: Color,
    measurer: androidx.compose.ui.text.TextMeasurer, density: Float,
) {
    val fitScale = fit.width / marks.frameWidth.toFloat()
    fun place(p: Offset) = Offset(fit.left + p.x * fitScale, fit.top + p.y * fitScale)
    val k = 1f / max(zoom, 1f) * density
    val axis = place(marks.axis)
    val pole = place(marks.pole)
    val ring = 13f * k
    val length = hypot(pole.x - axis.x, pole.y - axis.y)

    // The vector first, so the circle and cross sit on top of its ends: from the axis point to the pole.
    if (length > ring) {
        val ux = (pole.x - axis.x) / length
        val uy = (pole.y - axis.y) / length
        val start = Offset(axis.x + ux * ring, axis.y + uy * ring)
        drawLine(vectorColour, start, pole, strokeWidth = 2.5f * k)
        val head = 12f * k
        val back = Offset(pole.x - ux * head, pole.y - uy * head)
        drawLine(vectorColour, pole, Offset(back.x - uy * head * 0.5f, back.y + ux * head * 0.5f), strokeWidth = 2.5f * k)
        drawLine(vectorColour, pole, Offset(back.x + uy * head * 0.5f, back.y - ux * head * 0.5f), strokeWidth = 2.5f * k)

        val arcsec = hypot(marks.pole.x - marks.axis.x, marks.pole.y - marks.axis.y) * marks.arcminPerPixel * 60
        val layout = measurer.measure(
            polarAngle(arcsec),
            TextStyle(color = vectorColour, fontSize = (15f * k / density).sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
        )
        // Beside the middle of the vector, on the side away from the cross-hairs so it doesn't sit on the line.
        val middle = Offset((axis.x + pole.x) / 2, (axis.y + pole.y) / 2)
        drawText(
            layout,
            topLeft = Offset(
                middle.x - uy * (layout.size.width / 2f + 8 * k) - layout.size.width / 2f,
                middle.y + ux * (layout.size.height / 2f + 8 * k) - layout.size.height / 2f,
            ),
        )
    }
    drawCircle(circleColour, radius = ring, center = axis, style = Stroke(3f * k))
    val arm = 10f * k
    drawLine(crossColour, Offset(pole.x - arm, pole.y - arm), Offset(pole.x + arm, pole.y + arm), strokeWidth = 3.5f * k)
    drawLine(crossColour, Offset(pole.x - arm, pole.y + arm), Offset(pole.x + arm, pole.y - arm), strokeWidth = 3.5f * k)
}

/** An angle as a polar-alignment screen reads it: 45″, 3′12″ or 1°02′05″. */
fun polarAngle(arcsec: Double): String {
    val total = Math.round(Math.abs(arcsec)).toInt()
    val degrees = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return when {
        degrees > 0 -> "%d°%02d′%02d″".format(degrees, minutes, seconds)
        minutes > 0 -> "%d′%02d″".format(minutes, seconds)
        else -> "$seconds″"
    }
}
