package com.galileo.mobileconnect.ui.sky

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.galileo.mobileconnect.core.sky.EphemerisSample
import com.galileo.mobileconnect.core.sky.HorizontalPosition
import com.galileo.mobileconnect.core.sky.PositionCheck
import com.galileo.mobileconnect.core.sky.SiteAnswer
import com.galileo.mobileconnect.core.sky.SkyCamera
import com.galileo.mobileconnect.core.sky.SkyConstellations
import com.galileo.mobileconnect.core.sky.SkyGeometry
import com.galileo.mobileconnect.core.sky.SkyObject
import com.galileo.mobileconnect.core.sky.SkySite
import com.galileo.mobileconnect.core.sky.SkyStore
import com.galileo.mobileconnect.core.state.double
import com.galileo.mobileconnect.core.state.displayText
import com.galileo.mobileconnect.core.state.get
import com.galileo.mobileconnect.core.state.obj
import com.galileo.mobileconnect.core.state.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

data class Selection(
    val name: String,
    val raDeg: Double,
    val decDeg: Double,
    val details: List<String> = emptyList(),
    val check: PositionCheck? = null,
)

/** The stars as the renderer wants them: a unit vector and a magnitude each, flat (x, y, z, magnitude). */
class StarField(val data: FloatArray) {
    val count: Int get() = data.size / 4
}

/**
 * What the sky view is showing. Everything drawn came from the Master; this only remembers it and asks for more as the
 * user looks around (S-SKY).
 */
class SkyModel(
    private val session: ObservatorySession,
    private val pier: String,
    private val app: AppViewModel,
    private val scope: CoroutineScope,
) {
    private val api: MasterApi get() = session.masterApi

    var camera by mutableStateOf(SkyCamera())

    /** Seconds from now that the view is showing. Zero is live (MAPP-SKY-020). */
    var timeOffset by mutableStateOf(0.0)
    var query by mutableStateOf("")
    var searching by mutableStateOf(false); private set
    var results by mutableStateOf<List<JsonElement>>(emptyList()); private set
    var selection by mutableStateOf<Selection?>(null); private set
    var site by mutableStateOf<SkySite?>(null); private set
    var siteMissing by mutableStateOf(false); private set
    var siteFailed by mutableStateOf(false); private set
    var stars by mutableStateOf(StarField(FloatArray(0))); private set

    /** Moves when [stars] changes, so that the view needn't compare tens of thousands of them to find out. */
    var starsVersion by mutableIntStateOf(0); private set
    var deepSky by mutableStateOf<List<SkyObject>>(emptyList()); private set
    var bright by mutableStateOf<List<SkyObject>>(emptyList()); private set
    var samples by mutableStateOf<List<EphemerisSample>>(emptyList()); private set
    var fieldOfView by mutableStateOf<Pair<Double, Double>?>(null); private set
    var loading by mutableStateOf(false); private set
    var constellations by mutableStateOf(SkyConstellations()); private set

    /** The deep-sky objects of the catalogs chosen, as the Star Atlas's own choice works: Messier to start with. */
    var shownDeepSky by mutableStateOf<List<SkyObject>>(emptyList()); private set

    var showConstellations by mutableStateOf(app.pref("sky.constellations", "true") == "true")
        private set
    var showGround by mutableStateOf(app.pref("sky.ground", "true") == "true")
        private set
    var showSurvey by mutableStateOf(app.pref("sky.survey", "true") == "true")
        private set
    var shownCatalogs by mutableStateOf(app.pref("sky.catalogs", "Messier").split(',').filter { it.isNotEmpty() }.toSet())
        private set

    private var catalogMembers: Map<String, Set<Int>> = emptyMap()
    private val store = SkyStore(api, scope)

    /** Survey tiles behind the stars. */
    val survey = SkySurveyLayer(api, scope)

    private var settling: Job? = null
    private var sampleRange: ClosedRange<Instant>? = null
    private var fetchingSamples = false
    private var arrivals = 0

    fun time(now: Instant): Instant = now.plusMillis((timeOffset * 1000).toLong())

    fun chooseConstellations(on: Boolean) {
        showConstellations = on
        app.setPref("sky.constellations", on.toString())
    }

    fun chooseGround(on: Boolean) {
        showGround = on
        app.setPref("sky.ground", on.toString())
    }

    fun chooseSurvey(on: Boolean) {
        showSurvey = on
        app.setPref("sky.survey", on.toString())
    }

    fun toggleCatalog(name: String) {
        shownCatalogs = if (name in shownCatalogs) shownCatalogs - name else shownCatalogs + name
        app.setPref("sky.catalogs", shownCatalogs.joinToString(","))
        chooseDeepSky()
    }

    // -- loading ----------------------------------------------------------------------------------------------------

    suspend fun start(now: Instant, rate: Double?) {
        store.setRate(rate)
        siteMissing = false
        siteFailed = false
        when (val answer = api.skySite(pier)) {
            is SiteAnswer.Found -> site = answer.site
            SiteAnswer.NoLocation -> siteMissing = true
            SiteAnswer.Failed -> siteFailed = true
        }
        if (site == null) return
        pointingNow()?.let { lookAt(it.altitude, it.azimuth) }
        cameraMoved(now)
        fetchSamples(time(now))
        fetchFieldOfView()
        api.constellations()?.let { constellations = it }
        catalogMembers = api.skyCatalogs()
        chooseDeepSky()
    }

    private fun pointingNow(): HorizontalPosition? {
        val site = site ?: return null
        val snapshot = session.snapshot.value
        val ra = snapshot.value("mount.position.ra_deg", pier, Instant.now())?.value.double ?: return null
        val dec = snapshot.value("mount.position.dec_deg", pier, Instant.now())?.value.double ?: return null
        return SkyGeometry.horizontal(ra, dec, site, Instant.now())
    }

    fun lookAt(altitude: Double, azimuth: Double) {
        camera = camera.copy(altitude = min(90.0, max(-10.0, altitude)), azimuth = azimuth)
    }

    fun pan(dx: Double, dy: Double, viewHeight: Double) {
        camera = camera.panned(dx, dy, viewHeight)
    }

    fun zoomFrom(start: Double, factor: Double) {
        camera = camera.copy(fieldOfView = start).zoomed(factor)
    }

    /** The view moved. Asks once it has settled, not on every frame of a drag. */
    fun cameraMoved(now: Instant) {
        settling?.cancel()
        val camera = this.camera
        val moment = time(now)
        settling = scope.launch {
            delay(300)
            val site = site ?: return@launch
            val (ra, dec) = SkyGeometry.equatorial(camera.altitude, camera.azimuth, site, moment)
            loading = true
            store.look(ra, dec, min(180.0, camera.fieldOfView), camera.levelOfDetail) { received(it) }
        }
    }

    /** A real catalog is tens of thousands of objects a page apart: sort them out away from the main thread. */
    private suspend fun received(objects: List<SkyObject>) {
        arrivals += 1
        val arrival = arrivals
        val sorted = withContext(Dispatchers.Default) { sortOut(objects) }
        if (arrival != arrivals) return // a newer page got here first
        stars = sorted.stars
        starsVersion += 1
        deepSky = sorted.deepSky
        chooseDeepSky()
        bright = sorted.bright
        loading = false
    }

    private fun chooseDeepSky() {
        // A Master that can't say which is which has its objects all shown, rather than none.
        if (catalogMembers.isEmpty()) {
            shownDeepSky = deepSky
            return
        }
        val wanted = shownCatalogs.flatMap { catalogMembers[it] ?: emptySet() }.toSet()
        shownDeepSky = deepSky.filter { it.index in wanted }
    }

    private class Sorted(val stars: StarField, val deepSky: List<SkyObject>, val bright: List<SkyObject>)

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
        return Sorted(StarField(field.copyOf(count * 4)), deep, brightOnes)
    }

    /** The Master's positions for a span around the shown time, so scrubbing needs no round trips (MAPP-STR-020). */
    suspend fun fetchSamples(moment: Instant) {
        if (sampleRange?.contains(moment) == true) return
        if (fetchingSamples) return
        fetchingSamples = true
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

    private suspend fun fetchFieldOfView() {
        // The train's field of view doesn't depend on where it's pointing; any position will do.
        val framing = session.read(pier, "sky.framing", mapOf("ra_deg" to JsonPrimitive(0), "dec_deg" to JsonPrimitive(0))) ?: return
        val size = framing["fov_deg"] as? JsonArray ?: return
        if (size.size != 2) return
        val width = size[0].double ?: return
        val height = size[1].double ?: return
        fieldOfView = width to height
    }

    // -- search and selection ---------------------------------------------------------------------------------------

    suspend fun search() {
        val text = query.trim()
        if (text.isEmpty()) {
            results = emptyList()
            return
        }
        searching = true
        try {
            val found = session.read(pier, "sky.search", mapOf("query" to JsonPrimitive(text), "limit" to JsonPrimitive(20)))
            results = (found["objects"] as? JsonArray)?.toList() ?: emptyList()
        } finally {
            searching = false
        }
    }

    /** A search result, with the Master's details and check. */
    suspend fun select(obj: JsonElement, now: Instant) {
        val name = obj["name"].string ?: return
        val ra = obj["ra_deg"].double ?: return
        val dec = obj["dec_deg"].double ?: return
        results = emptyList()
        selectPosition(name, ra, dec, now, centre = true, askForDetails = true)
    }

    /** Something tapped in the view: the index is the Master's, and only it knows what that is. */
    suspend fun tapped(obj: SkyObject, now: Instant) {
        val identified = api.identify(obj.index)
        val name = identified?.name ?: if (obj.isDeepSky) "Deep-sky object" else "Star"
        selectPosition(name, obj.raDeg.toDouble(), obj.decDeg.toDouble(), now, centre = false, askForDetails = obj.isDeepSky)
    }

    /** [askForDetails]: `sky.object` knows deep-sky objects only, and a star would just be a refusal in the audit log. */
    private suspend fun selectPosition(name: String, raDeg: Double, decDeg: Double, now: Instant, centre: Boolean, askForDetails: Boolean) {
        var chosen = Selection(name, raDeg, decDeg)
        val site = site
        if (centre && site != null) {
            val place = SkyGeometry.horizontal(raDeg, decDeg, site, time(now))
            lookAt(place.altitude, place.azimuth)
            cameraMoved(now)
        }
        selection = chosen
        val details = scope.async {
            if (askForDetails) session.read(pier, "sky.object", mapOf("name" to JsonPrimitive(name))) else null
        }
        val check = scope.async { api.checkPosition(pier, raDeg, decDeg) }
        val found = details.await()
        chosen = chosen.copy(check = check.await())
        if (found != null) {
            val lines = chosen.details.toMutableList()
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
        settling?.cancel()
        survey.stop()
        store.stop()
    }

    companion object {
        val catalogs = listOf("Messier", "Caldwell", "NGC")

        /** `LOD_MAGNITUDE` in `galileo.skystream`, with the last level's "everything" cut off for drawing. */
        fun limitingMagnitude(level: Int): Float = floatArrayOf(4f, 7f, 10f, 13f, 15f)[max(0, min(4, level))]
    }
}
