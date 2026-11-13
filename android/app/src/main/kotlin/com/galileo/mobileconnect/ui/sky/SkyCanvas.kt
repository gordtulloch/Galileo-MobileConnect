package com.galileo.mobileconnect.ui.sky

import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galileo.mobileconnect.core.sky.EphemerisInterpolator
import com.galileo.mobileconnect.core.sky.Mat3
import com.galileo.mobileconnect.core.sky.SkyCamera
import com.galileo.mobileconnect.core.sky.SkyConstellations
import com.galileo.mobileconnect.core.sky.SkyGeometry
import com.galileo.mobileconnect.core.sky.SkyObject
import com.galileo.mobileconnect.core.sky.SkySite
import com.galileo.mobileconnect.core.sky.SkySurvey
import com.galileo.mobileconnect.core.sky.Vec3
import com.galileo.mobileconnect.core.state.double
import com.galileo.mobileconnect.ui.Banner
import com.galileo.mobileconnect.ui.LocalApp
import com.galileo.mobileconnect.ui.Spinner
import com.galileo.mobileconnect.ui.theme.LocalTheme
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.time.Instant
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max

/**
 * The picture: the survey, the ground and the star field drawn with the canvas, and over them everything that needs a
 * label or a shape. (docs/android/SDD.md 3.7 proposed OpenGL ES for the stars; the canvas draws a still sky well enough
 * that the GL path is left until a real star field shows it is needed.)
 */
@Composable
fun SkyCanvas(sky: SkyModel, onTap: (Offset?) -> Unit, onMoved: () -> Unit) {
    val app = LocalApp.current
    val theme = LocalTheme.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val now = app.now
    val moment = sky.time(now)
    val site = sky.site

    Box(
        Modifier.fillMaxSize()
            .semantics {
                contentDescription = "Sky view"
                stateDescription = "Looking at altitude ${sky.camera.altitude.toInt()}, azimuth ${sky.camera.azimuth.toInt()}. Field ${sky.camera.fieldOfView.toInt()} degrees."
            }
            .testTag("sky.view"),
    ) {
        Canvas(
            Modifier.fillMaxSize()
                .clipToBounds()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        onMoved()
                        if (zoom != 1f) sky.zoomFrom(sky.camera.fieldOfView, zoom.toDouble())
                        if (pan != Offset.Zero) sky.pan(pan.x.toDouble(), pan.y.toDouble(), size.height.toDouble())
                        sky.cameraMoved(Instant.now())
                    }
                }
                .pointerInput(sky.shownDeepSky, sky.bright, sky.camera, moment) {
                    detectTapGestures { at ->
                        val place = site ?: return@detectTapGestures
                        val reach = density.density
                        val hit = nearest(sky.shownDeepSky, 32 * reach, at, sky, place, size.width.toFloat(), size.height.toFloat(), moment)
                            ?: nearest(sky.bright, 28 * reach, at, sky, place, size.width.toFloat(), size.height.toFloat(), moment)
                        if (hit != null) {
                            onTap(at)
                            scope.launch { sky.tapped(hit, Instant.now()) }
                        } else {
                            sky.clearSelection()
                            onTap(null)
                        }
                    }
                },
        ) {
            if (site == null) return@Canvas
            val w = size.width
            val h = size.height
            val camera = sky.camera
            val toHorizon = SkyGeometry.equatorialToHorizon(site, moment)
            val toView = camera.horizonToView * toHorizon
            val scale = camera.scale(h.toDouble()).toFloat()

            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                // A night sky on the Light theme's pale page would be a black hole in it: there the stars stay as they are.
                if (sky.showSurvey && !theme.isLightPage) drawSurvey(native, sky, site, moment, toView, camera, w, h, scale, theme.imageTint.toArgb(), density.density)
                drawStars(native, sky, toView, toHorizon, w, h, scale, theme.text.toArgb(), density.density)
            }
            if (sky.showGround) drawGround(site, camera, theme.landNear, theme.landFar, theme.horizonFill, w, h)
            drawOverlay(sky, site, moment, camera, theme.text, theme.textSecondary, w, h, measurer, live = app.snapshot.isConnected && sky.timeOffset == 0.0, density.density, app)
        }
        if (!app.snapshot.isConnected) {
            Banner("✕", "Not live: showing the last sky received", Modifier.padding(8.dp).padding(end = 60.dp).testTag("sky.notlive"))
        } else if (sky.loading) {
            Box(Modifier.padding(8.dp).align(Alignment.TopStart)) { Spinner() }
        }
    }
}

private val com.galileo.mobileconnect.ui.theme.Theme.isLightPage: Boolean get() = background.red > 0.5f && !isNight

private fun place(camera: SkyCamera, alt: Double, az: Double, w: Float, h: Float): Offset? =
    camera.project(alt, az, h.toDouble())?.let { Offset(w / 2 + it.x.toFloat(), h / 2 - it.y.toFloat()) }

private fun nearest(
    objects: List<SkyObject>, reach: Float, at: Offset, sky: SkyModel, site: SkySite, w: Float, h: Float, moment: Instant,
): SkyObject? {
    var best: SkyObject? = null
    var bestDistance = Float.MAX_VALUE
    for (o in objects) {
        val horizontal = SkyGeometry.horizontal(o.raDeg.toDouble(), o.decDeg.toDouble(), site, moment)
        if (sky.showGround && horizontal.altitude < 0) continue // what the ground hides can't be touched
        val p = place(sky.camera, horizontal.altitude, horizontal.azimuth, w, h) ?: continue
        val d = hypot(p.x - at.x, p.y - at.y)
        if (d < reach && d < bestDistance) {
            best = o
            bestDistance = d
        }
    }
    return best
}

private fun drawSurvey(
    native: android.graphics.Canvas, sky: SkyModel, site: SkySite, moment: Instant, toView: Mat3, camera: SkyCamera,
    w: Float, h: Float, scale: Float, tint: Int, density: Float,
) {
    val wanted = SkySurvey.plan(camera, site, moment, w.toDouble() / density, h.toDouble() / density, density.toDouble())
    // A view narrower than this fetches no new tiles: it draws what it holds, so panning deep in never waits on the network.
    val fetch = camera.fieldOfView > SkySurvey.FETCH_ABOVE_FIELD_DEGREES
    val paint = Paint().apply {
        isFilterBitmap = true
        colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY)
    }
    val cells = SkySurveyLayer.CELLS
    val verts = FloatArray((cells + 1) * (cells + 1) * 2)
    for (tile in sky.survey.drawables(wanted, fetch)) {
        var visible = true
        var n = 0
        val d = tile.directions
        var i = 0
        while (i < d.size) {
            val v = toView * Vec3(d[i], d[i + 1], d[i + 2])
            if (v.z <= SkyCamera.FAR_LIMIT) {
                visible = false
                break
            }
            val k = 2f / (1f + v.z) * scale
            verts[n++] = w / 2 + v.x * k
            verts[n++] = h / 2 - v.y * k
            i += 3
        }
        if (visible) native.drawBitmapMesh(tile.bitmap, cells, cells, verts, 0, null, 0, paint)
    }
}

private fun drawStars(
    native: android.graphics.Canvas, sky: SkyModel, toView: Mat3, toHorizon: Mat3, w: Float, h: Float, scale: Float, color: Int, density: Float,
) {
    val field = sky.stars
    if (field.count == 0) return
    val limit = SkyModel.limitingMagnitude(sky.camera.levelOfDetail)
    val buckets = 8
    val points = Array(buckets) { FloatArray(field.count * 2) }
    val used = IntArray(buckets)
    val hide = sky.showGround
    val data = field.data
    val up = toHorizon.c0.z to Pair(toHorizon.c1.z, toHorizon.c2.z)
    for (i in 0 until field.count) {
        val x = data[i * 4]
        val y = data[i * 4 + 1]
        val z = data[i * 4 + 2]
        if (hide && up.first * x + up.second.first * y + up.second.second * z < 0f) continue
        val v = toView * Vec3(x, y, z)
        if (v.z <= SkyCamera.FAR_LIMIT) continue
        val k = 2f / (1f + v.z) * scale
        val px = w / 2 + v.x * k
        val py = h / 2 - v.y * k
        if (px < -4 || px > w + 4 || py < -4 || py > h + 4) continue
        val brightness = (limit - data[i * 4 + 3]) / (limit + 1.5f)
        val bucket = (brightness * buckets).toInt().coerceIn(0, buckets - 1)
        points[bucket][used[bucket]++] = px
        points[bucket][used[bucket]++] = py
    }
    val paint = Paint().apply {
        this.color = color
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    for (b in 0 until buckets) {
        if (used[b] == 0) continue
        paint.strokeWidth = (1.2f + b * 0.7f) * density
        native.drawPoints(points[b], 0, used[b], paint)
    }
}

/** The land below the horizon: lighter far off, darker near; the horizon profile's shadow over the true horizon. */
private fun DrawScope.drawGround(site: SkySite, camera: SkyCamera, near: Color, far: Color, fill: Color, w: Float, h: Float) {
    val step = 3
    val bands = doubleArrayOf(0.0, -4.0, -15.0, -40.0, -89.0)
    for (az in 0 until 360 step step) {
        val a0 = az.toDouble()
        val a1 = (az + step).toDouble()
        for (b in 0 until bands.size - 1) {
            val p = listOf(place(camera, bands[b], a0, w, h), place(camera, bands[b], a1, w, h), place(camera, bands[b + 1], a1, w, h), place(camera, bands[b + 1], a0, w, h))
            if (p.any { it == null }) continue
            val t = b / (bands.size - 2).toFloat()
            val path = Path().apply {
                moveTo(p[0]!!.x, p[0]!!.y)
                lineTo(p[1]!!.x, p[1]!!.y)
                lineTo(p[2]!!.x, p[2]!!.y)
                lineTo(p[3]!!.x, p[3]!!.y)
                close()
            }
            drawPath(path, androidx.compose.ui.graphics.lerp(far, near, t))
        }
        // What the profile blocks, between the true horizon and the skyline.
        val s0 = site.skylineAltitude(a0)
        val s1 = site.skylineAltitude(a1)
        if (s0 > 0 || s1 > 0) {
            val q = listOf(place(camera, 0.0, a0, w, h), place(camera, 0.0, a1, w, h), place(camera, s1, a1, w, h), place(camera, s0, a0, w, h))
            if (q.all { it != null }) {
                val path = Path().apply {
                    moveTo(q[0]!!.x, q[0]!!.y)
                    lineTo(q[1]!!.x, q[1]!!.y)
                    lineTo(q[2]!!.x, q[2]!!.y)
                    lineTo(q[3]!!.x, q[3]!!.y)
                    close()
                }
                drawPath(path, fill.copy(alpha = 0.28f))
            }
        }
    }
}

private fun DrawScope.drawOverlay(
    sky: SkyModel, site: SkySite, moment: Instant, camera: SkyCamera, color: Color, dim: Color, w: Float, h: Float,
    measurer: TextMeasurer, live: Boolean, density: Float, app: com.galileo.mobileconnect.AppViewModel,
) {
    fun plot(alt: Double, az: Double) = place(camera, alt, az, w, h)
    fun at(ra: Double, dec: Double): Offset? {
        val p = SkyGeometry.horizontal(ra, dec, site, moment)
        return plot(p.altitude, p.azimuth)
    }
    // The sky's own things stop at the ground. What the user is aiming at is marked wherever it is.
    fun inSky(ra: Double, dec: Double): Offset? {
        val p = SkyGeometry.horizontal(ra, dec, site, moment)
        return if (sky.showGround && p.altitude < 0) null else plot(p.altitude, p.azimuth)
    }
    fun label(text: String, center: Offset, style: TextStyle) {
        val layout = measurer.measure(text, style)
        drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
    }
    val small = TextStyle(color = dim.copy(alpha = 0.8f), fontSize = 11.sp)
    val bold = TextStyle(color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)

    // Under everything else: they're for finding your way, not for looking at.
    if (sky.showConstellations) {
        val figures: SkyConstellations = sky.constellations
        val outlines = Path()
        for (line in figures.lines) {
            var previous: Offset? = null
            for (point in line) {
                val p = inSky(point.raDeg, point.decDeg)
                if (p == null || p.x < -w || p.x > w * 2 || p.y < -h || p.y > h * 2) {
                    previous = null
                    continue
                }
                if (previous != null) outlines.lineTo(p.x, p.y) else outlines.moveTo(p.x, p.y)
                previous = p
            }
        }
        drawPath(outlines, dim.copy(alpha = 0.45f), style = Stroke(1.dp.toPx()))
        for (name in figures.names) {
            val p = inSky(name.raDeg, name.decDeg) ?: continue
            if (p.x in 0f..w && p.y in 0f..h) label(name.name, p, small)
        }
    }

    // The horizon profile, and the true horizon under it.
    val skyline = Path()
    var started = false
    var az = 0.0
    while (az <= 360.0) {
        val p = plot(site.skylineAltitude(az), az)
        if (p == null) started = false else {
            if (started) skyline.lineTo(p.x, p.y) else skyline.moveTo(p.x, p.y)
            started = true
        }
        az += 3.0
    }
    drawPath(skyline, color, style = Stroke(1.5.dp.toPx()))
    for ((name, azimuth) in listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)) {
        plot(0.0, azimuth)?.let { label(name, Offset(it.x, it.y + 12 * density), bold) }
    }

    for (o in sky.shownDeepSky) {
        val p = inSky(o.raDeg.toDouble(), o.decDeg.toDouble()) ?: continue
        drawCircle(dim, radius = 5.dp.toPx(), center = p, style = Stroke(1.dp.toPx()))
    }

    for (body in EphemerisInterpolator.bodies(moment, sky.samples)) {
        val p = inSky(body.raDeg, body.decDeg) ?: continue
        drawCircle(color, radius = 6.dp.toPx(), center = p)
        label(body.name, Offset(p.x, p.y + 14 * density), TextStyle(color = color, fontSize = 11.sp))
    }

    sky.selection?.let { selection ->
        at(selection.raDeg, selection.decDeg)?.let { p ->
            drawCircle(
                color, radius = 14.dp.toPx(), center = p,
                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
            label(selection.name, Offset(p.x, p.y - 24 * density), bold)
        }
    }

    // Hidden, never frozen, when not live (MAPP-STR-060).
    val ra = app.value("mount.position.ra_deg")?.value.double
    val dec = app.value("mount.position.dec_deg")?.value.double
    if (live && ra != null && dec != null) {
        at(ra, dec)?.let { p ->
            val arm = 12.dp.toPx()
            drawLine(color, Offset(p.x - arm, p.y), Offset(p.x + arm, p.y), strokeWidth = 2.dp.toPx())
            drawLine(color, Offset(p.x, p.y - arm), Offset(p.x, p.y + arm), strokeWidth = 2.dp.toPx())
            sky.fieldOfView?.let { (fw, fh) ->
                val perDegree = (camera.scale(h.toDouble()) * PI / 180).toFloat()
                val width = max(8f, fw.toFloat() * perDegree)
                val height = max(8f, fh.toFloat() * perDegree)
                drawRect(color, topLeft = Offset(p.x - width / 2, p.y - height / 2), size = androidx.compose.ui.geometry.Size(width, height), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}
