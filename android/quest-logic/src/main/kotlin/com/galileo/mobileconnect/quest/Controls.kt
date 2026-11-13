package com.galileo.mobileconnect.quest

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/** The left thumbstick moves the sky through time, never the viewer (MAPP-QST-130, -190). */
object TimeStick {
    const val DEAD_ZONE = 0.12f
    const val FULL_DEFLECTION_SECONDS_PER_SECOND = 3600.0 // one hour of sky per second at full deflection

    /** Seconds of sky time to add this frame: proportional to the deflection beyond the dead zone, so a small push is slow. */
    fun delta(deflection: Float, frameSeconds: Double): Double {
        val magnitude = abs(deflection)
        if (magnitude <= DEAD_ZONE) return 0.0
        val scaled = ((magnitude - DEAD_ZONE) / (1f - DEAD_ZONE)).coerceIn(0f, 1f)
        // A curve, so fine control near the centre and a fast sweep at the end.
        return sign(deflection).toDouble() * scaled.toDouble().pow(2.0) * FULL_DEFLECTION_SECONDS_PER_SECOND * frameSeconds
    }
}

/** The other stick zooms the field (the tablet's pinch), within the range the star field supports. */
object ZoomStick {
    const val MIN = 1f
    const val MAX = 40f
    private const val PER_SECOND = 2.5f // at full deflection the zoom doubles-and-a-half each second

    fun next(zoom: Float, deflection: Float, frameSeconds: Double): Float {
        if (abs(deflection) <= TimeStick.DEAD_ZONE) return zoom
        val factor = PER_SECOND.toDouble().pow(deflection.toDouble() * frameSeconds).toFloat()
        return max(MIN, min(MAX, zoom * factor))
    }
}

/**
 * Held operations (MAPP-QST-060, -090): a hold needs a controller button held, and ends the moment the button is released,
 * tracking is lost, or the headset comes off. This decides only whether the hold may be going; starting and stopping it
 * on the Master is the session's.
 */
class HoldControl {
    sealed interface Action {
        data object Start : Action
        data class Stop(val why: Why) : Action
    }

    enum class Why { Released, TrackingLost, HeadsetRemoved, LeaseLost }

    private var buttonDown = false
    private var tracking = true
    private var worn = true
    private var holding = false

    val isHolding get() = holding

    private fun update(): Action? {
        val allowed = buttonDown && tracking && worn
        return when {
            allowed && !holding -> {
                holding = true
                Action.Start
            }
            !allowed && holding -> {
                holding = false
                // Whatever ended it, the move must not start again by itself when tracking or the headset returns:
                // it needs a fresh press (MAPP-QST-060).
                val released = !buttonDown
                buttonDown = false
                Action.Stop(
                    when {
                        released -> Why.Released
                        !worn -> Why.HeadsetRemoved
                        !tracking -> Why.TrackingLost
                        else -> Why.Released
                    },
                )
            }
            else -> null
        }
    }

    fun button(down: Boolean): Action? {
        buttonDown = down
        return update()
    }

    fun tracking(ok: Boolean): Action? {
        tracking = ok
        return update()
    }

    fun worn(on: Boolean): Action? {
        worn = on
        return update()
    }

    /** The Master took the lease away: the hold is over whatever the button says, and the button must be pressed again. */
    fun leaseLost(): Action? {
        buttonDown = false
        return if (holding) {
            holding = false
            Action.Stop(Why.LeaseLost)
        } else null
    }
}

/**
 * The magnification the renderer applies about a centre (quest.cpp zoomFwd): angular distance from the centre is multiplied
 * by k. A pointer sees the displayed sky, so a pick undoes this first and then compares true positions.
 */
object Zoom {
    fun inverse(displayed: com.galileo.mobileconnect.core.sky.Vec3, centre: com.galileo.mobileconnect.core.sky.Vec3, k: Float): com.galileo.mobileconnect.core.sky.Vec3 {
        if (k <= 1.001f) return displayed
        val c = centre.normalized()
        val d = displayed.normalized()
        val cosine = d.dot(c).coerceIn(-1f, 1f)
        val p = d - c * cosine
        val pl = p.length()
        if (pl < 1e-6f) return d
        val t = kotlin.math.acos(cosine) / k
        return c * kotlin.math.cos(t) + p * (kotlin.math.sin(t) / pl)
    }

    fun forward(true_: com.galileo.mobileconnect.core.sky.Vec3, centre: com.galileo.mobileconnect.core.sky.Vec3, k: Float): com.galileo.mobileconnect.core.sky.Vec3 {
        if (k <= 1.001f) return true_
        val c = centre.normalized()
        val d = true_.normalized()
        val cosine = d.dot(c).coerceIn(-1f, 1f)
        val p = d - c * cosine
        val pl = p.length()
        if (pl < 1e-6f) return d
        val t = kotlin.math.acos(cosine) * k
        return c * kotlin.math.cos(t) + p * (kotlin.math.sin(t) / pl)
    }
}
