package com.galileo.mobileconnect.core.sky

import java.time.Instant

/** A planet's place at one moment. */
data class EphemerisBody(val name: String, val raDeg: Double, val decDeg: Double)

data class EphemerisSample(val time: Instant, val bodies: List<EphemerisBody>)

data class IdentifiedObject(
    val index: Int,
    val isDeepSky: Boolean,
    val name: String?,
    val type: String?,
    val raDeg: Double,
    val decDeg: Double,
    val magnitude: Double?,
)

/** Constellation figures, from the Master: the app ships none. */
data class SkyConstellations(
    val lines: List<List<Point>> = emptyList(),
    val names: List<Name> = emptyList(),
) {
    data class Name(val name: String, val raDeg: Double, val decDeg: Double)
    data class Point(val raDeg: Double, val decDeg: Double)
}

data class PositionCheck(val altitude: Double, val azimuth: Double, val aboveHorizon: Boolean, val reason: String?)

/** The Master's answer to a request for a page: objects, or why not now. */
sealed interface SkyPage {
    data class Page(val objects: List<SkyObject>, val next: String) : SkyPage

    /** Over the rate or the client limit. Back off; this isn't an error (MAPP-STR-070). */
    data object BackOff : SkyPage
    data class Refused(val reason: String) : SkyPage
}

/** A Master saying it has no location is one thing, and not getting an answer is another. */
sealed interface SiteAnswer {
    data class Found(val site: SkySite) : SiteAnswer
    data object NoLocation : SiteAnswer
    data object Failed : SiteAnswer
}

/** A preview picture, and the full frame's size, which tile addressing needs (null from a Master that doesn't say). */
class PreviewImage(val data: ByteArray, val frameWidth: Int?, val frameHeight: Int?)

/** Interpolates a time series, the only computing done on positions over time: the Master computed each sample. */
object EphemerisInterpolator {
    fun bodies(at: Instant, samples: List<EphemerisSample>): List<EphemerisBody> {
        val first = samples.firstOrNull() ?: return emptyList()
        val after = samples.indexOfFirst { it.time >= at }
        if (after < 0) return samples.last().bodies
        if (after == 0) return first.bodies
        val a = samples[after - 1]
        val b = samples[after]
        val span = (b.time.toEpochMilli() - a.time.toEpochMilli()) / 1000.0
        val f = if (span > 0) (at.toEpochMilli() - a.time.toEpochMilli()) / 1000.0 / span else 0.0
        return a.bodies.map { body ->
            val other = b.bodies.firstOrNull { it.name == body.name } ?: return@map body
            var delta = other.raDeg - body.raDeg
            if (delta > 180) delta -= 360
            if (delta < -180) delta += 360
            var ra = (body.raDeg + delta * f) % 360
            if (ra < 0) ra += 360
            EphemerisBody(body.name, ra, body.decDeg + (other.decDeg - body.decDeg) * f)
        }
    }
}
