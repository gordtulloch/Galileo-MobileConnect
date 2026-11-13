package com.galileo.mobileconnect.core.sky

import com.galileo.mobileconnect.core.control.OperationState
import com.galileo.mobileconnect.core.control.RequestAnswer
import com.galileo.mobileconnect.core.link.ClientIdentity
import com.galileo.mobileconnect.core.link.checkPosition
import com.galileo.mobileconnect.core.link.constellations
import com.galileo.mobileconnect.core.link.ephemeris
import com.galileo.mobileconnect.core.link.identify
import com.galileo.mobileconnect.core.link.skyCatalogs
import com.galileo.mobileconnect.core.link.skySite
import com.galileo.mobileconnect.core.link.surveyTile
import com.galileo.mobileconnect.core.mock.MockMaster
import com.galileo.mobileconnect.core.session.ObservatorySession
import com.galileo.mobileconnect.core.state.LivePhase
import com.galileo.mobileconnect.core.state.double
import com.galileo.mobileconnect.core.state.get
import com.galileo.mobileconnect.core.state.int
import com.galileo.mobileconnect.core.state.string
import com.galileo.mobileconnect.core.vectors.VectorFiles
import com.galileo.mobileconnect.core.vectors.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Kotlin side of iOS `SkyTests.swift`: the same cases and the same shared vectors (S-SKY, MAPP-PLT-040). */
class SkyTest {
    private val site = SkySite(latitude = 49.9, longitude = -97.1)
    private val night = Instant.ofEpochSecond(1_793_930_400) // 2026-11-06T02:00:00Z
    private val identity = ClientIdentity("Vector iPad", "0.1.0", "android", "tablet")
    private val scope = CoroutineScope(Dispatchers.Default)
    private val masters = mutableListOf<MockMaster>()

    @AfterTest
    fun tearDown() {
        masters.forEach { it.shutDown() }
        scope.cancel()
    }

    private fun master() = MockMaster("normal-night", scope).also { masters += it }
    private fun json(name: String) = Json.parseToJsonElement(File(VectorFiles.directory, name).readText()) as JsonObject
    private fun bytes(name: String) = File(VectorFiles.directory, name).readBytes()

    @Test
    fun `MAPP-STR-070 core's own encoder's bytes decode exactly`() {
        val objects = SkyFrameDecoder.decode(bytes("sky/frame-v1.bin"))
        val wanted = json("sky/frame-v1.expected.json")["objects"] as JsonArray
        assertEquals(wanted.size, objects.size)
        for ((o, w) in objects.zip(wanted)) {
            assertEquals(w["index"].int, o.index)
            assertTrue(abs(o.raDeg - w["ra"].double!!) < 0.001)
            assertTrue(abs(o.decDeg - w["dec"].double!!) < 0.001)
            assertTrue(abs(o.magnitude - w["mag"].double!!) < 0.01)
            assertEquals(w["kind"].int, o.kind)
        }
    }

    @Test
    fun `our encoder matches core's byte for byte, so the mock is honest`() {
        val data = bytes("sky/frame-v1.bin")
        assertContentEquals(data, SkyFrameDecoder.encode(SkyFrameDecoder.decode(data)))
    }

    @Test
    fun `a frame that isn't one is refused rather than crashing`() {
        assertFailsWith<SkyFrameError.NotASkyFrame> { SkyFrameDecoder.decode("nope".toByteArray()) }
        assertEquals(
            SkyFrameError.UnsupportedEncoding(9),
            assertFailsWith<SkyFrameError.UnsupportedEncoding> { SkyFrameDecoder.decode(byteArrayOf(0x47, 0x53, 0x4B, 9, 1, 0, 0, 0, 0)) },
        )
        assertFailsWith<SkyFrameError.Truncated> { SkyFrameDecoder.decode(byteArrayOf(0x47, 0x53, 0x4B, 1, 1, 5, 0, 0, 0, 0x02)) }
    }

    @Test
    fun `sidereal time against a known value, J2000 at Greenwich is 280 46 degrees`() {
        val j2000 = Instant.ofEpochSecond(946_728_000)
        assertTrue(abs(SkyGeometry.siderealDegrees(j2000, 0.0) - 280.46) < 0.01)
        assertTrue(abs(SkyGeometry.siderealDegrees(j2000, -90.0) - 190.46) < 0.01)
    }

    @Test
    fun `Polaris stands at about the site's latitude, whenever you look`() {
        for (hours in 0 until 24 step 6) {
            val place = SkyGeometry.horizontal(37.95, 89.264, site, night.plusSeconds(hours * 3600L))
            assertTrue(abs(place.altitude - 49.9) < 1)
        }
    }

    @Test
    fun `horizontal and equatorial are inverses`() {
        val place = SkyGeometry.horizontal(83.82, -5.39, site, night)
        val (ra, dec) = SkyGeometry.equatorial(place.altitude, place.azimuth, site, night)
        assertTrue(abs(ra - 83.82) < 0.01 && abs(dec + 5.39) < 0.01)
    }

    @Test
    fun `the GPU's matrix puts a star where the formula does`() {
        val m = SkyGeometry.equatorialToHorizon(site, night)
        for ((ra, dec) in listOf(83.82 to -5.39, 250.4 to 36.46, 10.7 to 41.27)) {
            val v = m * SkyGeometry.unitVector(ra.toFloat(), dec.toFloat())
            val place = SkyGeometry.horizontal(ra, dec, site, night)
            assertTrue(abs(asin(v.z.toDouble()) * 180 / Math.PI - place.altitude) < 0.01)
            var az = atan2(v.x.toDouble(), v.y.toDouble()) * 180 / Math.PI
            if (az < 0) az += 360
            assertTrue(abs(az - place.azimuth) < 0.01)
        }
    }

    @Test
    fun `the camera centres what's looked at, up is up, east is left when facing south, and it inverts`() {
        val camera = SkyCamera(40.0, 180.0, 60.0)
        val centre = assertNotNull(camera.project(40.0, 180.0, 800.0))
        assertTrue(abs(centre.x) < 0.01 && abs(centre.y) < 0.01)
        val above = assertNotNull(camera.project(50.0, 180.0, 800.0))
        assertTrue(above.y > 0 && abs(above.x) < 0.01)
        val eastward = assertNotNull(camera.project(40.0, 170.0, 800.0)) // facing south, east is on the left
        assertTrue(eastward.x < 0)
        val place = camera.unproject(eastward.x, eastward.y, 800.0)
        assertTrue(abs(place.altitude - 40) < 0.01 && abs(place.azimuth - 170) < 0.01)
        val edge = assertNotNull(camera.project(70.0, 180.0, 800.0)) // half the field is at the edge of the shorter side
        assertTrue(abs(edge.y - 400) < 1)
    }

    @Test
    fun `zoom and pan stay in range`() {
        var camera = SkyCamera(45.0, 10.0, 90.0).zoomed(10_000.0)
        assertEquals(SkyCamera.FIELD_MIN, camera.fieldOfView)
        camera = camera.zoomed(0.000_1)
        assertEquals(SkyCamera.FIELD_MAX, camera.fieldOfView)
        camera = camera.copy(fieldOfView = 90.0).panned(0.0, 100_000.0, 800.0)
        assertEquals(90.0, camera.altitude)
        camera = camera.copy(altitude = 45.0).panned(400.0, 0.0, 800.0)
        assertTrue(camera.azimuth >= 0 && camera.azimuth < 360)
    }

    @Test
    fun `MAPP-STR-010 a narrower view asks for fainter stars`() {
        assertTrue(SkyCamera(fieldOfView = 120.0).levelOfDetail < SkyCamera(fieldOfView = 30.0).levelOfDetail)
        assertTrue(SkyCamera(fieldOfView = 30.0).levelOfDetail < SkyCamera(fieldOfView = 5.0).levelOfDetail)
    }

    @Test
    fun `MAPP-STR-050 the cache is bounded, short-lived and evicts the least used`() {
        val cache = SkyPageCache(maximumObjects = 10, lifetimeSeconds = 60)
        val t = Instant.ofEpochSecond(0)
        val five = List(5) { SkyObject(0, 0f, 0f, 0f, 0) }
        val a = SkyPageCache.Key(1, 0, 1, 1)
        val b = SkyPageCache.Key(2, 0, 1, 1)
        val c = SkyPageCache.Key(3, 0, 1, 1)
        cache.put(a, five, true, t)
        cache.put(b, five, true, t.plusSeconds(1))
        cache.get(a, t.plusSeconds(2))
        cache.put(c, five, true, t.plusSeconds(3))
        assertEquals(10, cache.objectCount)
        assertNull(cache.get(b, t.plusSeconds(4))) // least recently used
        assertNotNull(cache.get(a, t.plusSeconds(4)))
        assertNull(cache.get(a, t.plusSeconds(100))) // outlived
    }

    @Test
    fun `MAPP-STR-020 interpolation between the Master's samples, across RA's wrap`() {
        val t0 = Instant.ofEpochSecond(0)
        val t1 = t0.plusSeconds(3600)
        val samples = listOf(
            EphemerisSample(t0, listOf(EphemerisBody("Moon", 359.0, 10.0))),
            EphemerisSample(t1, listOf(EphemerisBody("Moon", 1.0, 20.0))),
        )
        val middle = EphemerisInterpolator.bodies(t0.plusSeconds(1800), samples)
        assertTrue(abs(middle[0].raDeg) < 0.001 || abs(middle[0].raDeg - 360) < 0.001)
        assertEquals(15.0, middle[0].decDeg)
        assertEquals(359.0, EphemerisInterpolator.bodies(t0.plusSeconds(-10), samples)[0].raDeg)
        assertEquals(1.0, EphemerisInterpolator.bodies(t1.plusSeconds(10), samples)[0].raDeg)
        assertTrue(EphemerisInterpolator.bodies(t0, emptyList()).isEmpty())
    }

    @Test
    fun `MAPP-STR-040 pages arrive progressively, and each only ever adds`() = runBlocking<Unit> {
        val api = master().connection(identity, MockMaster.TOKEN).api
        val store = SkyStore(api, scope)
        store.setRate(100.0)
        val updates = CopyOnWriteArrayList<List<SkyObject>>()
        store.look(83.0, 0.0, 180.0, 4) { updates += it }
        assertTrue(eventually(8000) { updates.size >= 4 })
        val seen = updates.toList()
        assertTrue(seen.zipWithNext().all { (a, b) -> a.size <= b.size })
        assertTrue((seen.first().maxOf { it.magnitude }) < seen.last().maxOf { it.magnitude }, "brightest first")
        store.stop()
    }

    @Test
    fun `MAPP-STR-010 a second look at the same place comes from the cache and asks for nothing`() = runBlocking<Unit> {
        val mock = master()
        val store = SkyStore(mock.connection(identity, MockMaster.TOKEN).api, scope)
        store.setRate(1000.0)
        val updates = CopyOnWriteArrayList<List<SkyObject>>()
        store.look(83.0, 0.0, 20.0, 1) { updates += it }
        assertTrue(eventually(5000) { updates.size >= 1 && store.cachedObjectCount > 0 })
        delay(100)
        val before = mock.requests.size
        store.look(83.0, 0.0, 20.0, 1) { updates += it }
        delay(100)
        assertEquals(before, mock.requests.size)
    }

    @Test
    fun `MAPP-SKY-030, MAPP-SKY-040 search, details, the Master's check, and a go-to that asks first`() = runBlocking<Unit> {
        val mock = master()
        val session = ObservatorySession(mock.connection(identity, MockMaster.TOKEN), scope)
        session.start()
        assertTrue(eventually(5000) { session.snapshot.value.live.phase == LivePhase.Live })
        val found = session.read("p1", "sky.search", mapOf("query" to JsonPrimitive("orion"), "limit" to JsonPrimitive(10)))
        assertEquals("M 42", ((found["objects"] as JsonArray).first())["name"].string)
        val details = session.read("p1", "sky.object", mapOf("name" to JsonPrimitive("M 42")))
        assertNotNull(details["rise_transit_set"]["transit"])

        val requests = mock.requests.size
        val question = assertIs<RequestAnswer.Confirm>(session.goToQuestion("p1", "M 42", 83.822, -5.391)).request
        assertEquals("Slew to M 42", question.title)
        assertTrue(question.details.any { it.startsWith("Altitude now:") })
        assertTrue(question.consequence!!.contains("horizon"))
        assertTrue(mock.requests.drop(requests).none { it.contains("/ops") }, "nothing is sent until accepted")

        session.takeLease("p1")
        val sent = assertIs<RequestAnswer.Sent>(session.confirm(question))
        assertTrue(sent.record.state != OperationState.Pending)
        session.stop()
    }

    @Test
    fun `MAPP-STR-030 the site and tapped objects come from the Master`() = runBlocking<Unit> {
        val api = master().connection(identity, MockMaster.TOKEN).api
        val site = assertIs<SiteAnswer.Found>(api.skySite("p1")).site
        assertTrue(site.latitude == 49.9 && site.horizon.size == 5)
        val named = assertNotNull(api.identify(2500))
        assertTrue(named.isDeepSky && named.name == "M 42")
        val star = assertNotNull(api.identify(0))
        assertTrue(!star.isDeepSky && star.name == null)
        assertNull(api.identify(999_999))
        assertNull(api.surveyTile(SkyTile(0, 0)), "the demo ships no sky pictures")
        val figures = assertNotNull(api.constellations())
        assertTrue(figures.names.any { it.name == "Orion" })
        assertEquals(6, figures.lines.first().size)
        // The figures' stars are real and named, after the objects.
        val vega = assertNotNull(api.identify(2500 + 6 + 7 + 7 + 5 + 5))
        assertTrue(vega.name == "Vega" && !vega.isDeepSky)
        val now = Instant.now()
        val samples = assertNotNull(api.ephemeris(now, now.plusSeconds(3600), 600.0))
        assertTrue(samples.size == 7 && samples[0].bodies.any { it.name == "Moon" })
    }

    /** The horizon fill undoes the stars' turn with a transpose, and the survey's plan both: only right for pure rotations. */
    @Test
    fun `the sky turns are rotations so they can be undone`() {
        val camera = SkyCamera(altitude = 37.0, azimuth = 251.0)
        for (m in listOf(camera.horizonToView, SkyGeometry.equatorialToHorizon(site, Instant.ofEpochSecond(1_800_000_000)))) {
            val product = m * m.transpose()
            val columns = listOf(product.c0, product.c1, product.c2)
            for (c in 0 until 3) for (r in 0 until 3) {
                val v = listOf(columns[c].x, columns[c].y, columns[c].z)[r]
                assertTrue(abs(v - (if (c == r) 1 else 0)) < 1e-5)
            }
        }
    }

    /** MAPP-SKY-070: the same answers as astropy_healpix, which `galileo.planning.sky_survey` is held to as well. */
    @Test
    fun `survey tiles are the same cells as core`() {
        val cases = json("sky/healpix.json")["cases"] as JsonArray
        assertTrue(cases.size > 300)
        for (item in cases) {
            val ra = item["ra_deg"].double!!
            val dec = item["dec_deg"].double!!
            val order = item["order"].double!!.toInt()
            val npix = item["npix"].double!!.toInt()
            assertEquals(SkyTile(order, npix), SkySurvey.tile(ra, dec, order).tile, "$ra $dec $order")
        }
    }

    /** The mesh is built with this inverse, so a tile lies where its name says. */
    @Test
    fun `a place in a tile goes back to the same direction`() {
        for ((ra, dec) in listOf(83.8 to -5.4, 10.68 to 41.27, 200.0 to 80.0, 300.0 to -75.0, 0.5 to 0.2, 359.0 to 44.0, 135.0 to 89.5)) {
            for (order in listOf(0, 3, 7)) {
                val found = SkySurvey.tile(ra, dec, order)
                val back = SkySurvey.direction(found.tile, found.fx, found.fy)
                val want = SkyGeometry.unitVector(ra.toFloat(), dec.toFloat())
                val distance = sqrt((back.x - want.x) * (back.x - want.x) + (back.y - want.y) * (back.y - want.y) + (back.z - want.z) * (back.z - want.z))
                assertTrue(distance < 1e-4, "$ra $dec $order")
            }
        }
    }

    /** MAPP-SKY-070: everything on screen is asked for, finer as the view zooms, and never more than a screen can hold. */
    @Test
    fun `a view asks for its tiles, and finer as it zooms`() {
        val moment = Instant.ofEpochSecond(1_800_000_000)
        val (width, height) = 1000.0 to 700.0
        var lastOrder = -1
        for (field in listOf(100.0, 20.0, 2.0)) {
            val camera = SkyCamera(altitude = 40.0, azimuth = 200.0, fieldOfView = field)
            val wanted = SkySurvey.plan(camera, site, moment, width, height, 2.0)
            assertTrue(wanted.isNotEmpty() && wanted.size <= SkySurvey.MAX_VIEW_TILES)
            val order = wanted[0].order
            assertTrue(order > lastOrder)
            lastOrder = order
            val scale = camera.scale(min(width, height))
            val toSky = SkyGeometry.equatorialToHorizon(site, moment).transpose() * camera.horizonToView.transpose()
            for ((x, y) in listOf(0.0 to 0.0, 500.0 to 350.0, -500.0 to -350.0, 500.0 to -350.0, -500.0 to 350.0)) {
                val px = x / scale
                val py = y / scale
                val r = (px * px + py * py) / 4
                val view = Vec3((px / (1 + r)).toFloat(), (py / (1 + r)).toFloat(), ((1 - r) / (1 + r)).toFloat())
                val sky = toSky * view
                val ra = atan2(sky.y.toDouble(), sky.x.toDouble()) * 180 / Math.PI
                val dec = asin(min(1.0, max(-1.0, sky.z.toDouble()))) * 180 / Math.PI
                assertTrue(SkySurvey.tile(if (ra < 0) ra + 360 else ra, dec, order).tile in wanted, "$field $x $y")
            }
        }
    }

    @Test
    fun `the skyline interpolates round the circle`() {
        var s = SkySite(latitude = 0.0, longitude = 0.0)
        assertEquals(0.0, s.skylineAltitude(123.0), "no profile: the true horizon")
        s = s.copy(horizon = listOf(SkySite.HorizonPoint(0.0, 10.0), SkySite.HorizonPoint(180.0, 30.0)))
        assertTrue(abs(s.skylineAltitude(90.0) - 20) < 1e-9)
        assertTrue(abs(s.skylineAltitude(270.0) - 20) < 1e-9)
    }

    /** MAPP-SKY-090: what the app draws is where Galileo's Star Atlas draws it, from values its own code wrote. */
    @Test
    fun `the projection is the Star Atlas's`() {
        val cases = json("sky/projection.json")["cases"] as JsonArray
        assertEquals(120, cases.size)
        for (item in cases) {
            fun n(key: String) = item[key].double!!
            val s = SkySite(latitude = n("latitude"), longitude = n("longitude"))
            val unix = n("unix")
            val moment = Instant.ofEpochSecond(Math.floor(unix).toLong(), ((unix - Math.floor(unix)) * 1e9).toLong())
            val ra = n("ra_deg")
            val dec = n("dec_deg")
            val camera = SkyCamera(n("view_altitude"), n("view_azimuth"), n("field"))
            val width = n("width")
            val height = n("height")

            val place = SkyGeometry.horizontal(ra, dec, s, moment)
            assertTrue(abs(place.altitude - n("altitude")) < 1e-6)
            val azimuthError = abs(place.azimuth - n("azimuth"))
            assertTrue(min(azimuthError, 360 - azimuthError) < 1e-5)

            // Viewport's own limit is -0.85 too, so only a hair's breadth of float difference can disagree.
            val point = camera.project(place.altitude, place.azimuth, height) ?: continue
            val x = n("x")
            val y = n("y")
            if (abs(x - width / 2) >= 4 * width || abs(y - height / 2) >= 4 * height) continue
            assertTrue(abs(width / 2 + point.x - x) < 0.5, "$item")
            assertTrue(abs(height / 2 - point.y - y) < 0.5, "$item")

            val view = camera.horizonToView * (SkyGeometry.equatorialToHorizon(s, moment) * SkyGeometry.unitVector(ra.toFloat(), dec.toFloat()))
            val k = (2 / (1 + view.z)).toDouble() * camera.scale(height)
            assertTrue(abs(width / 2 + view.x * k - x) < 1.5, "the stars' path: $item")
            assertTrue(abs(height / 2 - view.y * k - y) < 1.5, "the stars' path: $item")
        }
    }

    /** MAPP-SKY-110: what's in which catalog is the Master's to say. */
    @Test
    fun `the Master says what is in which catalog`() = runBlocking<Unit> {
        val catalogs = master().connection(identity, MockMaster.TOKEN).api.skyCatalogs()
        assertTrue(2500 in catalogs["Messier"]!!, "M 42")
        assertTrue(catalogs["NGC"] != null && catalogs["Caldwell"] != null)
    }

    @Test
    fun `position check comes from the Master`() = runBlocking<Unit> {
        val api = master().connection(identity, MockMaster.TOKEN).api
        val check = assertNotNull(api.checkPosition("p1", 83.822, -5.391))
        assertTrue(check.altitude > -90 && check.altitude < 90)
    }
}
