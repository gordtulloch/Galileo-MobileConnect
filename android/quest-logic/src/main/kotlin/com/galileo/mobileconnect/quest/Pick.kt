package com.galileo.mobileconnect.quest

import com.galileo.mobileconnect.core.sky.SkyObject
import com.galileo.mobileconnect.core.sky.Vec3
import com.galileo.mobileconnect.core.sky.SkySite
import com.galileo.mobileconnect.core.sky.SkyGeometry
import java.time.Instant

/** What a pick can land on, in the order they win: deep-sky objects are what people aim at, then bright stars, then bodies. */
sealed interface Pickable {
    val raDeg: Double
    val decDeg: Double

    data class DeepSky(val object_: SkyObject) : Pickable {
        override val raDeg get() = object_.raDeg.toDouble()
        override val decDeg get() = object_.decDeg.toDouble()
    }

    data class Star(val object_: SkyObject) : Pickable {
        override val raDeg get() = object_.raDeg.toDouble()
        override val decDeg get() = object_.decDeg.toDouble()
    }

    data class Body(val name: String, override val raDeg: Double, override val decDeg: Double) : Pickable
}

/**
 * Which object a pointer is on (MAPP-QST-320). The ray is turned into a horizon direction, and each candidate's position
 * (J2000 from the Master, put on the sky for the shown time exactly as the tablet does) is compared by angle. Reach is
 * in degrees on the sky and shrinks as the view is zoomed in, so a crowded field can still be picked one star at a time.
 * Objects the ground hides cannot be picked.
 */
object Picker {
    const val DEEP_SKY_REACH_DEGREES = 2.5
    const val STAR_REACH_DEGREES = 2.0
    const val BODY_REACH_DEGREES = 3.0

    fun pick(
        ray: Ray,
        zoom: Float,
        site: SkySite,
        at: Instant,
        deepSky: List<SkyObject>,
        bright: List<SkyObject>,
        bodies: List<Pickable.Body>,
        groundHides: Boolean = true,
    ): Pickable? {
        val look = World.worldToHorizon(ray.unit)
        val z = maxOf(1f, zoom)

        fun nearest(candidates: List<Pickable>, reachDegrees: Double): Pickable? {
            var best: Pickable? = null
            var bestAngle = Double.MAX_VALUE
            for (candidate in candidates) {
                val place = SkyGeometry.horizontal(candidate.raDeg, candidate.decDeg, site, at)
                if (groundHides && place.altitude < 0) continue
                val angle = angleBetweenDegrees(look, horizonDirection(place.altitude, place.azimuth))
                if (angle < reachDegrees / z && angle < bestAngle) {
                    best = candidate
                    bestAngle = angle
                }
            }
            return best
        }
        return nearest(deepSky.map { Pickable.DeepSky(it) }, DEEP_SKY_REACH_DEGREES)
            ?: nearest(bodies, BODY_REACH_DEGREES)
            ?: nearest(bright.map { Pickable.Star(it) }, STAR_REACH_DEGREES)
    }
}
