package com.galileo.mobileconnect.quest

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.galileo.mobileconnect.AppViewModel
import com.galileo.mobileconnect.core.link.MasterApi
import com.galileo.mobileconnect.core.link.checkPosition
import com.galileo.mobileconnect.core.link.constellations
import com.galileo.mobileconnect.core.link.ephemeris
import com.galileo.mobileconnect.core.link.identify
import com.galileo.mobileconnect.core.link.skyCatalogs
import com.galileo.mobileconnect.core.link.skySite
import com.galileo.mobileconnect.core.session.ObservatorySession
import com.galileo.mobileconnect.core.sky.EphemerisInterpolator
import com.galileo.mobileconnect.core.sky.EphemerisSample
import com.galileo.mobileconnect.core.sky.Mat3
import com.galileo.mobileconnect.core.sky.PositionCheck
import com.galileo.mobileconnect.core.sky.SiteAnswer
import com.galileo.mobileconnect.core.sky.SkyConstellations
import com.galileo.mobileconnect.core.sky.SkyGeometry
import com.galileo.mobileconnect.core.sky.SkyObject
import com.galileo.mobileconnect.core.sky.SkySite
import com.galileo.mobileconnect.core.sky.SkyStore
import com.galileo.mobileconnect.core.sky.Vec3
import com.galileo.mobileconnect.core.state.displayText
import com.galileo.mobileconnect.core.state.double
import com.galileo.mobileconnect.core.state.get
import com.galileo.mobileconnect.core.state.obj
import com.galileo.mobileconnect.core.state.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** What the pointer chose, with what the Master says about it (the same card the tablet's Star Atlas shows). */
data class Selection(
    val name: String,
    val raDeg: Double,
    val decDeg: Double,
    val details: List<String> = emptyList(),
    val check: PositionCheck? = null,
)

/**
 * The star field's data and its upload (docs/quest/SDD.md Q-DATA, Q-SKY). Everything drawn came from the Master through the
 * same [SkyStore] the tablet uses; nothing is baked into the app. The renderer is given J2000 positions and one rotation for
 * the shown time, so a moving sky costs one 3x3 matrix per frame, not a re-upload.
 */
class QuestSky(
    private val session: ObservatorySession,
    private val pier: String,
    private val app: AppViewModel,
    private val scope: CoroutineScope,
) {
    private val api: MasterApi get() = session.masterApi
    private val store = SkyStore(api, scope)

    var site: SkySite? = null; private set
    var siteProblem: String? = null; private set

    @Volatile var deepSky: List<SkyObject> = emptyList(); private set
    @Volatile var bright: List<SkyObject> = emptyList(); private set
    @Volatile var samples: List<EphemerisSample> = emptyList(); private set
    @Volatile var constellations = SkyConstellations(); private set
    @Volatile var fieldOfView: Pair<Double, Double>? = null; private set
    var selection by mutableStateOf<Selection?>(null); private set
    var loading by mutableStateOf(false); private set

    private var allDeepSky: List<SkyObject> = emptyList()
    private var catalogMembers: Map<String, Set<Int>> = emptyMap()
    private var shownCatalogs = app.pref("sky.catalogs", "Messier").split(',').filter { it.isNotEmpty() }.toSet()
    private var arrivals = 0
    private var sampleRange: ClosedRange<Instant>? = null
    private var fetchingSamples = false

    suspend fun start() {
        store.setRate(app.snapshot.capabilities?.skyStreamRate)
        when (val answer = api.skySite(pier)) {
            is SiteAnswer.Found -> site = answer.site
            SiteAnswer.NoLocation -> siteProblem = "This Pier has no site location, so the sky can't be placed. Set one in Settings."
            SiteAnswer.Failed -> siteProblem = "The Master didn't answer for the site location."
        }
        api.constellations()?.let {
            constellations = it
            uploadConstellations(it)
        }
        catalogMembers = api.skyCatalogs()
        fetchFieldOfView()
    }

    private fun uploadConstellations(c: SkyConstellations) {
        val out = ArrayList<Float>()
        for (line in c.lines) {
            for (i in 0 until line.size - 1) {
                for (p in listOf(line[i], line[i + 1])) {
                    val v = SkyGeometry.unitVector(p.raDeg.toFloat(), p.decDeg.toFloat())
                    out += v.x; out += v.y; out += v.z
                }
            }
        }
        NativeBridge.nativeSetConLines(out.toFloatArray(), out.size / 3)
    }

    /** The view has settled on a new part of the sky: ask the Master for what is there, brightest first. */
    fun look(raDeg: Double, decDeg: Double, radiusDegrees: Double, level: Int) {
        loading = true
        store.look(raDeg, decDeg, radiusDegrees, level) { received(it) }
    }

    private suspend fun received(objects: List<SkyObject>) {
        arrivals += 1
        val arrival = arrivals
        val sorted = withContext(Dispatchers.Default) { sortOut(objects) }
        if (arrival != arrivals) return
        NativeBridge.nativeSetStars(sorted.stars, sorted.starCount)
        allDeepSky = sorted.deepSky
        chooseDeepSky()
        bright = sorted.bright
        loading = false
    }

    private class Sorted(val stars: FloatArray, val starCount: Int, val deepSky: List<SkyObject>, val bright: List<SkyObject>)

    private fun sortOut(objects: List<SkyObject>): Sorted {
        val field = FloatArray(objects.size * 4)
        var count = 0
        val deep = ArrayList<SkyObject>()
        val brightOnes = ArrayList<SkyObject>()
        for (o in objects) {
            if (o.isDeepSky) {
                deep += o
                continue
            }
            val v = SkyGeometry.unitVector(o.raDeg, o.decDeg)
            field[count * 4] = v.x
            field[count * 4 + 1] = v.y
            field[count * 4 + 2] = v.z
            field[count * 4 + 3] = o.magnitude
            count += 1
            if (o.magnitude < 4) brightOnes += o
        }
        return Sorted(field.copyOf(count * 4), count, deep, brightOnes)
    }

    private fun chooseDeepSky() {
        // A Master that can't say which is which has its objects all shown, rather than none (as on the tablet).
        deepSky = if (catalogMembers.isEmpty()) allDeepSky else {
            val wanted = shownCatalogs.flatMap { catalogMembers[it] ?: emptySet() }.toSet()
            allDeepSky.filter { it.index in wanted }
        }
        val markers = FloatArray(deepSky.size * 5)
        deepSky.forEachIndexed { i, o ->
            val v = SkyGeometry.unitVector(o.raDeg, o.decDeg)
            markers[i * 5] = v.x; markers[i * 5 + 1] = v.y; markers[i * 5 + 2] = v.z
            markers[i * 5 + 3] = 1.2f; markers[i * 5 + 4] = 0f
        }
        deepMarkers = markers
        pushMarkers()
    }

    private var deepMarkers = FloatArray(0)
    private var bodyMarkers = FloatArray(0)

    private fun pushMarkers() {
        val all = deepMarkers + bodyMarkers
        NativeBridge.nativeSetMarkers(all, all.size / 5)
    }

    /** The Master's positions for a span around the shown time, so scrubbing needs no round trips (MAPP-STR-020). */
    fun ensureSamples(moment: Instant) {
        if (sampleRange?.contains(moment) == true || fetchingSamples) return
        fetchingSamples = true
        scope.launch {
            try {
                val from = moment.minusSeconds(6 * 3600)
                val to = moment.plusSeconds(18 * 3600)
                api.ephemeris(from, to, 1800.0)?.let {
                    samples = it
                    sampleRange = from..to
                }
            } finally {
                fetchingSamples = false
            }
        }
    }

    /** Where the bodies are at [moment], as pickable things; their markers are refreshed with them. */
    fun bodies(moment: Instant): List<Pickable.Body> {
        val now = EphemerisInterpolator.bodies(moment, samples)
        val markers = FloatArray(now.size * 5)
        now.forEachIndexed { i, b ->
            val v = SkyGeometry.unitVector(b.raDeg.toFloat(), b.decDeg.toFloat())
            markers[i * 5] = v.x; markers[i * 5 + 1] = v.y; markers[i * 5 + 2] = v.z
            markers[i * 5 + 3] = 1f; markers[i * 5 + 4] = 6f
        }
        bodyMarkers = markers
        pushMarkers()
        return now.map { Pickable.Body(it.name, it.raDeg, it.decDeg) }
    }

    private suspend fun fetchFieldOfView() {
        val framing = session.read(pier, "sky.framing", mapOf("ra_deg" to JsonPrimitive(0), "dec_deg" to JsonPrimitive(0))) ?: return
        val size = framing["fov_deg"] as? JsonArray ?: return
        if (size.size != 2) return
        val width = size[0].double ?: return
        val height = size[1].double ?: return
        fieldOfView = width to height
    }

    // -- selection --------------------------------------------------------------------------------------------------

    suspend fun picked(pick: Pickable, moment: Instant) {
        when (pick) {
            is Pickable.Body -> choose(pick.name, pick.raDeg, pick.decDeg, askForDetails = false)
            is Pickable.DeepSky -> {
                val identified = api.identify(pick.object_.index)
                choose(identified?.name ?: "Deep-sky object", pick.raDeg, pick.decDeg, askForDetails = true)
            }
            is Pickable.Star -> {
                val identified = api.identify(pick.object_.index)
                choose(identified?.name ?: "Star", pick.raDeg, pick.decDeg, askForDetails = false)
            }
        }
    }

    private suspend fun choose(name: String, raDeg: Double, decDeg: Double, askForDetails: Boolean) {
        var chosen = Selection(name, raDeg, decDeg)
        selection = chosen
        val details = scope.async {
            if (askForDetails) session.read(pier, "sky.object", mapOf("name" to JsonPrimitive(name))) else null
        }
        val check = scope.async { api.checkPosition(pier, raDeg, decDeg) }
        val found = details.await()
        chosen = chosen.copy(check = check.await())
        if (found != null) {
            val lines = ArrayList<String>()
            found["type"].string?.let { lines += it }
            found["magnitude"].double?.takeIf { it < 90 }?.let { lines += "Magnitude %.1f".format(it) }
            found["size_arcmin"].double?.let { lines += "Size %.1f′".format(it) }
            found["rise_transit_set"].obj?.let { times ->
                for (key in listOf("rise", "transit", "set")) {
                    val value = times[key]
                    if (value != null && value !is JsonNull) lines += "${key.replaceFirstChar { it.uppercase() }}: ${value.displayText()}"
                }
            }
            chosen = chosen.copy(details = lines)
        }
        if (selection?.name == name) selection = chosen
    }

    fun clearSelection() {
        selection = null
    }

    fun stop() {
        store.stop()
    }

    // -- what is drawn in the world frame ---------------------------------------------------------------------------

    /** J2000 to the headset's world for the shown time: precession, the sidereal turn, then the horizon laid on the world. */
    fun rotation(moment: Instant): Mat3? {
        val site = site ?: return null
        val m = SkyGeometry.equatorialToHorizon(site, moment)
        return Mat3(World.horizonToWorld(m.c0), World.horizonToWorld(m.c1), World.horizonToWorld(m.c2))
    }

    fun toWorld(rotation: Mat3, raDeg: Double, decDeg: Double): Vec3 = rotation * SkyGeometry.unitVector(raDeg.toFloat(), decDeg.toFloat())

    /** The horizon profile, altitude rings, the pointing cross, the field frame and the selection ring, for the shown time. */
    fun overlay(rotation: Mat3, night: Boolean, mount: Pair<Double, Double>?): Pair<FloatArray, FloatArray> {
        val site = site ?: return FloatArray(0) to FloatArray(0)
        val lines = ArrayList<Float>()
        fun colour(r: Float, g: Float, b: Float, a: Float) = if (night) floatArrayOf(r.coerceAtLeast(g).coerceAtLeast(b), 0f, 0f, a) else floatArrayOf(r, g, b, a)
        fun seg(a: Vec3, b: Vec3, c: FloatArray) {
            lines += listOf(a.x, a.y, a.z, c[0], c[1], c[2], c[3])
            lines += listOf(b.x, b.y, b.z, c[0], c[1], c[2], c[3])
        }
        fun horizonPoint(alt: Double, az: Double) = World.horizonToWorld(horizonDirection(alt, az))

        // the horizon as the site has it
        val horizonColour = colour(0.9f, 0.8f, 0.5f, 0.9f)
        var az = 0.0
        while (az < 360) {
            seg(horizonPoint(site.skylineAltitude(az), az), horizonPoint(site.skylineAltitude(az + 5), az + 5), horizonColour)
            az += 5.0
        }
        // rings at 30 and 60 degrees, faint
        val ringColour = colour(0.4f, 0.55f, 0.8f, 0.35f)
        for (alt in listOf(30.0, 60.0)) {
            az = 0.0
            while (az < 360) {
                seg(horizonPoint(alt, az), horizonPoint(alt, az + 6), ringColour)
                az += 6.0
            }
        }
        // north, east, south, west ticks at the horizon
        for (a in listOf(0.0, 90.0, 180.0, 270.0)) seg(horizonPoint(0.0, a), horizonPoint(4.0, a), horizonColour)

        // where the telescope points: a cross, and the camera's field around it
        val pointColour = colour(0.3f, 1f, 0.5f, 1f)
        mount?.let { (ra, dec) ->
            val centre = toWorld(rotation, ra, dec).normalized()
            val (east, north) = tangent(centre)
            val arm = 0.012f
            seg(centre + east * -arm, centre + east * arm, pointColour)
            seg(centre + north * -arm, centre + north * arm, pointColour)
            fieldOfView?.let { (w, h) ->
                val hw = tan(Math.toRadians(w / 2)).toFloat()
                val hh = tan(Math.toRadians(h / 2)).toFloat()
                val c = listOf(east * -hw + north * -hh, east * hw + north * -hh, east * hw + north * hh, east * -hw + north * hh)
                for (i in 0 until 4) seg((centre + c[i]).normalized(), (centre + c[(i + 1) % 4]).normalized(), pointColour)
            }
        }
        // the selection: a ring
        selection?.let { s ->
            val centre = toWorld(rotation, s.raDeg, s.decDeg).normalized()
            val (east, north) = tangent(centre)
            val ringRadius = tan(Math.toRadians(1.6)).toFloat()
            val ring = colour(1f, 0.9f, 0.3f, 1f)
            val n = 28
            for (i in 0 until n) {
                val a0 = 2 * Math.PI * i / n
                val a1 = 2 * Math.PI * (i + 1) / n
                seg(
                    (centre + east * (ringRadius * cos(a0).toFloat()) + north * (ringRadius * sin(a0).toFloat())).normalized(),
                    (centre + east * (ringRadius * cos(a1).toFloat()) + north * (ringRadius * sin(a1).toFloat())).normalized(),
                    ring,
                )
            }
        }
        return lines.toFloatArray() to FloatArray(0)
    }

    private fun tangent(centre: Vec3): Pair<Vec3, Vec3> {
        val reference = if (kotlin.math.abs(centre.y) > 0.95f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)
        val east = reference.cross(centre).normalized()
        val north = centre.cross(east).normalized()
        return east to north
    }

    fun limitingMagnitude(zoom: Float): Float {
        val level = com.galileo.mobileconnect.core.sky.SkyCamera(fieldOfView = 100.0 / zoom).levelOfDetail
        return floatArrayOf(4f, 7f, 10f, 13f, 15f)[max(0, min(4, level))].coerceAtMost(9f)
    }
}
