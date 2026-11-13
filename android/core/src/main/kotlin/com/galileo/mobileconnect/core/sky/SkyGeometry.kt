package com.galileo.mobileconnect.core.sky

import java.time.Instant
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A three-component float vector: just what the sky maths needs, in place of `simd` (S-SKY). */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun normalized(): Vec3 {
        val n = sqrt(dot(this))
        return if (n > 0f) Vec3(x / n, y / n, z / n) else this
    }
}

/** A 3x3 float matrix held by columns, as `simd_float3x3` is. */
class Mat3(val c0: Vec3, val c1: Vec3, val c2: Vec3) {
    operator fun times(v: Vec3): Vec3 = c0 * v.x + c1 * v.y + c2 * v.z
    operator fun times(m: Mat3) = Mat3(this * m.c0, this * m.c1, this * m.c2)
    fun transpose() = Mat3(Vec3(c0.x, c1.x, c2.x), Vec3(c0.y, c1.y, c2.y), Vec3(c0.z, c1.z, c2.z))

    /** Row-major, for handing to a GPU. */
    fun toColumnMajor() = floatArrayOf(c0.x, c0.y, c0.z, c1.x, c1.y, c1.z, c2.x, c2.y, c2.z)
}

/** A double-precision direction, for the survey's HEALPix maths. */
data class Vec3d(val x: Double, val y: Double, val z: Double) {
    fun dot(o: Vec3d) = x * o.x + y * o.y + z * o.z
    fun normalized(): Vec3d {
        val n = sqrt(dot(this))
        return if (n > 0) Vec3d(x / n, y / n, z / n) else this
    }
}

data class Pt(val x: Double, val y: Double)

/** Where the Observatory is. From the Master (MAPP-STR-030). */
data class SkySite(
    val name: String? = null,
    val latitude: Double,
    val longitude: Double,
    val horizon: List<HorizonPoint> = emptyList(),
) {
    data class HorizonPoint(val azimuth: Double, val altitude: Double)

    /** The local horizon's altitude at an azimuth, interpolated round the circle: the true horizon where none is set. */
    fun skylineAltitude(azimuth: Double): Double {
        if (horizon.isEmpty()) return 0.0
        val points = horizon.sortedBy { it.azimuth }
        val last = points.last()
        val first = points.first()
        val before = points.lastOrNull { it.azimuth <= azimuth } ?: HorizonPoint(last.azimuth - 360, last.altitude)
        val after = points.firstOrNull { it.azimuth > azimuth } ?: HorizonPoint(first.azimuth + 360, first.altitude)
        val span = after.azimuth - before.azimuth
        return if (span > 0) before.altitude + (after.altitude - before.altitude) * (azimuth - before.azimuth) / span else before.altitude
    }
}

data class HorizontalPosition(val altitude: Double, val azimuth: Double)

internal fun Instant.epochSecondsDouble(): Double = epochSecond + nano * 1e-9

/**
 * Turning the Master's positions into the view: a change of coordinates for drawing, not an ephemeris. Decisions about
 * what is above the horizon or within limits are the Master's (`/sky/check`).
 */
object SkyGeometry {
    private const val RADIANS = PI / 180

    /** Local sidereal time in degrees: `local_sidereal_deg` in `galileo.planning.star_atlas`. */
    fun siderealDegrees(at: Instant, longitude: Double): Double {
        val days = at.epochSecondsDouble() / 86400 + 2440587.5 - 2451545.0
        val t = days / 36525
        val gmst = 280.46061837 + 360.98564736629 * days + 0.000387933 * t * t
        val lst = (gmst + longitude) % 360
        return if (lst < 0) lst + 360 else lst
    }

    /** J2000 to the mean equator of the date (Meeus, IAU 1976): `precess_from_j2000` in `galileo.planning.star_atlas`. */
    fun precessed(raDeg: Double, decDeg: Double, at: Instant): Pair<Double, Double> {
        val t = (at.epochSecondsDouble() / 86400 + 2440587.5 - 2451545.0) / 36525
        val zeta = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) / 3600 * RADIANS
        val z = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) / 3600 * RADIANS
        val theta = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) / 3600 * RADIANS
        val ra = raDeg * RADIANS
        val dec = decDeg * RADIANS
        val a = cos(dec) * sin(ra + zeta)
        val b = cos(theta) * cos(dec) * cos(ra + zeta) - sin(theta) * sin(dec)
        val c = sin(theta) * cos(dec) * cos(ra + zeta) + cos(theta) * sin(dec)
        var out = (atan2(a, b) + z) / RADIANS
        out %= 360
        return (if (out < 0) out + 360 else out) to asin(max(-1.0, min(1.0, c))) / RADIANS
    }

    /** Takes J2000 to a mean-of-date unit vector, as a matrix built from the same formula. */
    private fun precession(at: Instant): Mat3 {
        fun column(ra: Double, dec: Double): Vec3 {
            val (pra, pdec) = precessed(ra, dec, at)
            return Vec3(
                (cos(pdec * RADIANS) * cos(pra * RADIANS)).toFloat(),
                (cos(pdec * RADIANS) * sin(pra * RADIANS)).toFloat(),
                sin(pdec * RADIANS).toFloat(),
            )
        }
        return Mat3(column(0.0, 0.0), column(90.0, 0.0), column(0.0, 90.0))
    }

    /** Where a J2000 position is in the sky, as `equatorial_to_horizontal` after precession has it in Galileo. */
    fun horizontal(raDeg: Double, decDeg: Double, site: SkySite, at: Instant): HorizontalPosition {
        val (ofDateRa, ofDateDec) = precessed(raDeg, decDeg, at)
        val hourAngle = (siderealDegrees(at, site.longitude) - ofDateRa) * RADIANS
        val dec = ofDateDec * RADIANS
        val lat = site.latitude * RADIANS
        val altitude = asin(sin(dec) * sin(lat) + cos(dec) * cos(lat) * cos(hourAngle))
        val azimuth = atan2(-sin(hourAngle) * cos(dec), sin(dec) * cos(lat) - cos(dec) * cos(hourAngle) * sin(lat))
        val degrees = azimuth / RADIANS
        return HorizontalPosition(altitude / RADIANS, if (degrees < 0) degrees + 360 else degrees)
    }

    /** The J2000 position at a place in the sky: the inverse of [horizontal]. */
    fun equatorial(altitude: Double, azimuth: Double, site: SkySite, at: Instant): Pair<Double, Double> {
        val alt = altitude * RADIANS
        val az = azimuth * RADIANS
        val lat = site.latitude * RADIANS
        val dec = asin(sin(alt) * sin(lat) + cos(alt) * cos(lat) * cos(az))
        val hourAngle = atan2(-sin(az) * cos(alt), sin(alt) * cos(lat) - cos(alt) * cos(az) * sin(lat))
        val ra = (siderealDegrees(at, site.longitude) - hourAngle / RADIANS) % 360
        // That's of the date: back to J2000, as `horizontal` takes.
        val ofDate = Vec3((cos(dec) * cos(ra * RADIANS)).toFloat(), (cos(dec) * sin(ra * RADIANS)).toFloat(), sin(dec).toFloat())
        val j2000 = precession(at).transpose() * ofDate
        val back = atan2(j2000.y.toDouble(), j2000.x.toDouble()) / RADIANS
        return (if (back < 0) back + 360 else back) to asin(max(-1f, min(1f, j2000.z)).toDouble()) / RADIANS
    }

    /** Unit vector of an equatorial position: what the GPU is given, once, for each star. */
    fun unitVector(raDeg: Float, decDeg: Float): Vec3 {
        val ra = raDeg * RADIANS.toFloat()
        val dec = decDeg * RADIANS.toFloat()
        return Vec3(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec))
    }

    /** Rotates J2000 unit vectors into horizon axes: x east, y north, z up. */
    fun equatorialToHorizon(site: SkySite, at: Instant): Mat3 {
        val lst = (siderealDegrees(at, site.longitude) * RADIANS).toFloat()
        val lat = (site.latitude * RADIANS).toFloat()
        val east = Vec3(-sin(lst), cos(lst), 0f)
        val up = Vec3(cos(lat) * cos(lst), cos(lat) * sin(lst), sin(lat))
        val north = Vec3(-sin(lat) * cos(lst), -sin(lat) * sin(lst), cos(lat))
        // Rows are the new axes; the matrix takes columns.
        return Mat3(Vec3(east.x, north.x, up.x), Vec3(east.y, north.y, up.y), Vec3(east.z, north.z, up.z)) * precession(at)
    }
}

/**
 * What the sky view is looking at: `Viewport` in `galileo.planning.star_atlas`, held to it by
 * `api/vectors/sky/projection.json`. A stereographic projection about (altitude, azimuth), with east to the left of north
 * as on the sky seen from below.
 */
data class SkyCamera(
    val altitude: Double = 40.0,
    val azimuth: Double = 180.0,
    /** Degrees up the height of the view. */
    val fieldOfView: Double = 100.0,
) {
    fun panned(deltaX: Double, deltaY: Double, viewHeight: Double): SkyCamera {
        val perPoint = fieldOfView / max(1.0, viewHeight)
        var az = (azimuth - deltaX * perPoint / max(0.25, cos(altitude * PI / 180))) % 360
        if (az < 0) az += 360
        return copy(azimuth = az, altitude = min(90.0, max(-10.0, altitude + deltaY * perPoint)))
    }

    fun zoomed(factor: Double): SkyCamera =
        copy(fieldOfView = min(FIELD_MAX, max(FIELD_MIN, fieldOfView / max(1e-6, factor))))

    /** Rotates horizon axes (x east, y north, z up) into view axes (x right, y up, z towards what's looked at). */
    val horizonToView: Mat3
        get() {
            val alt = (altitude * PI / 180).toFloat()
            val az = (azimuth * PI / 180).toFloat()
            val forward = Vec3(cos(alt) * sin(az), cos(alt) * cos(az), sin(alt))
            val up = Vec3(-sin(alt) * sin(az), -sin(alt) * cos(az), cos(alt)).normalized()
            // Looking up from below, east is to the left of north: right = forward x up.
            val right = forward.cross(up)
            return Mat3(Vec3(right.x, up.x, forward.x), Vec3(right.y, up.y, forward.y), Vec3(right.z, up.z, forward.z))
        }

    /** Points per unit of the stereographic plane: `Viewport.scale`. */
    fun scale(viewHeight: Double): Double = viewHeight / 2 / (2 * tan(fieldOfView * PI / 180 / 4))

    /** A horizon-frame direction on screen, from the centre of the view, y up; null when behind the viewer. */
    fun project(altitude: Double, azimuth: Double, viewHeight: Double): Pt? {
        val a = altitude * PI / 180
        val z = azimuth * PI / 180
        val direction = Vec3((cos(a) * sin(z)).toFloat(), (cos(a) * cos(z)).toFloat(), sin(a).toFloat())
        val v = horizonToView * direction
        if (v.z <= FAR_LIMIT) return null
        val k = (2 / (1 + v.z)).toDouble() * scale(viewHeight)
        return Pt(v.x * k, v.y * k)
    }

    /** The inverse of [project]: which direction a point on screen is. */
    fun unproject(x: Double, y: Double, viewHeight: Double): HorizontalPosition {
        val s = scale(viewHeight)
        val px = x / s
        val py = y / s
        val d = (px * px + py * py) / 4
        val w = (1 - d) / (1 + d)
        val k = (1 + w) / 2
        val view = Vec3((px * k).toFloat(), (py * k).toFloat(), w.toFloat())
        val direction = horizonToView.transpose() * view
        val alt = asin(max(-1f, min(1f, direction.z)).toDouble()) * 180 / PI
        var az = atan2(direction.x.toDouble(), direction.y.toDouble()) * 180 / PI
        if (az < 0) az += 360
        return HorizontalPosition(alt, az)
    }

    /**
     * How deep to ask for, from how much of the sky is in view (MAPP-STR-010). Levels are `LOD_MAGNITUDE` in
     * `galileo.skystream`: magnitude 4, 7, 10, 13, everything.
     */
    val levelOfDetail: Int
        get() = when {
            fieldOfView >= 60 -> 1
            fieldOfView >= 15 -> 2
            fieldOfView >= 3 -> 3
            else -> 4
        }

    companion object {
        const val FIELD_MIN = 0.5
        const val FIELD_MAX = 150.0

        /** The far side of the projection, which runs off to infinity, isn't drawn: `cos_c > -0.85` in `Viewport.project`. */
        const val FAR_LIMIT = -0.85f
    }
}
