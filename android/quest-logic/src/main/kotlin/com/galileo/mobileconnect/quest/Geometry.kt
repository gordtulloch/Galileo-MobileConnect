package com.galileo.mobileconnect.quest

import com.galileo.mobileconnect.core.sky.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

operator fun Vec3.minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
operator fun Vec3.unaryMinus() = Vec3(-x, -y, -z)
fun Vec3.length(): Float = sqrt(dot(this))

/**
 * The headset's world: metres, y up, the user starting to face -z (OpenXR's local/stage space). The sky is laid on it by
 * [horizonToWorld]: the horizon frame of the core (x east, y north, z up) with north in front of the user at the start.
 */
object World {
    fun horizonToWorld(v: Vec3) = Vec3(v.x, v.z, -v.y)
    fun worldToHorizon(v: Vec3) = Vec3(v.x, -v.z, v.y)
}

/** A pointer: from a controller or a pinching hand. [direction] need not be unit length. */
data class Ray(val origin: Vec3, val direction: Vec3) {
    val unit: Vec3 get() = direction.normalized()
    fun at(t: Float) = origin + unit * t
}

/**
 * A flat rectangle in the world: a pane or the dock. [normal] points at the user; [right] and [up] span its face. Sizes are
 * metres; [pixelWidth] x [pixelHeight] is the surface the face shows, so a hit can be handed to the pane as a touch.
 */
data class Quad(
    val center: Vec3,
    val right: Vec3,
    val up: Vec3,
    val widthMetres: Float,
    val heightMetres: Float,
    val pixelWidth: Int,
    val pixelHeight: Int,
) {
    val normal: Vec3 get() = right.cross(up).normalized()

    /** Where a ray meets the face: distance along the ray and the point in the pane's own pixels (origin top left). */
    data class Hit(val distance: Float, val xPixels: Float, val yPixels: Float)

    fun intersect(ray: Ray): Hit? {
        val n = normal
        val d = ray.unit
        val denominator = n.dot(d)
        if (abs(denominator) < 1e-6f) return null // parallel to the face
        val t = n.dot(center - ray.origin) / denominator
        if (t <= 0f) return null // behind the pointer
        val local = ray.at(t) - center
        val u = local.dot(right.normalized()) / widthMetres + 0.5f
        val v = 0.5f - local.dot(up.normalized()) / heightMetres
        if (u < 0f || u > 1f || v < 0f || v > 1f) return null
        // A face seen from behind is not touchable.
        if (denominator > 0f) return null
        return Hit(t, u * pixelWidth, v * pixelHeight)
    }
}

/** Degrees between two directions. */
fun angleBetweenDegrees(a: Vec3, b: Vec3): Double {
    val c = a.normalized().dot(b.normalized()).toDouble().coerceIn(-1.0, 1.0)
    return acos(c) * 180 / PI
}

/** A direction at [azimuthDeg] (clockwise from north) and [altitudeDeg], in the horizon frame (x east, y north, z up). */
fun horizonDirection(altitudeDeg: Double, azimuthDeg: Double): Vec3 {
    val a = altitudeDeg * PI / 180
    val z = azimuthDeg * PI / 180
    return Vec3((cos(a) * sin(z)).toFloat(), (cos(a) * cos(z)).toFloat(), sin(a).toFloat())
}

fun azimuthOf(v: Vec3): Double {
    var az = atan2(v.x.toDouble(), v.y.toDouble()) * 180 / PI
    if (az < 0) az += 360
    return az
}

fun altitudeOf(v: Vec3): Double = kotlin.math.asin(v.normalized().z.toDouble().coerceIn(-1.0, 1.0)) * 180 / PI

internal fun clamp(v: Float, low: Float, high: Float) = max(low, min(high, v))
