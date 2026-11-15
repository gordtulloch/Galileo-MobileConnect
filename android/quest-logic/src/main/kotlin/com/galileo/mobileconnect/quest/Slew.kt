package com.galileo.mobileconnect.quest

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** Angular distance between two equatorial positions, degrees. */
fun separationDegrees(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
    val a1 = Math.toRadians(dec1)
    val a2 = Math.toRadians(dec2)
    val c = sin(a1) * sin(a2) + cos(a1) * cos(a2) * cos(Math.toRadians(ra1 - ra2))
    return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
}

/**
 * Follows one slew the wearer started from the object menu, so the sky can show where the telescope is on its way
 * (the telescope marker, the path to the target and a progress bar). The Master reports the mount's position and whether it
 * is slewing; the destination is the one thing it does not report as coordinates, so it is remembered from the press.
 *
 * It is only a display aid: it never decides whether anything moves.
 */
class SlewTracker {
    data class Status(
        val name: String,
        val fromRaDeg: Double, val fromDecDeg: Double,
        val toRaDeg: Double, val toDecDeg: Double,
        val remainingDegrees: Double,
        /** 0 at the start of the path, 1 on the target. */
        val progress: Float,
        /** The mount has stopped slewing: this is the last thing shown before the tracker clears itself. */
        val arrived: Boolean,
    )

    private var name = ""
    private var fromRa = 0.0
    private var fromDec = 0.0
    private var toRa = 0.0
    private var toDec = 0.0
    private var startedAt = 0L
    private var sawSlewing = false
    private var arrivedAt = 0L
    private var active = false

    val isActive get() = active

    /** The wearer asked for a slew to [name]. [from] is where the mount is now, when known. */
    fun begin(name: String, raDeg: Double, decDeg: Double, from: Pair<Double, Double>?, nowMillis: Long) {
        this.name = name
        toRa = raDeg
        toDec = decDeg
        fromRa = from?.first ?: raDeg
        fromDec = from?.second ?: decDeg
        startedAt = nowMillis
        sawSlewing = false
        arrivedAt = 0L
        active = true
    }

    fun cancel() {
        active = false
    }

    /** The request is waiting on the user (the Master's confirmation): the time to start does not run meanwhile. */
    fun waiting(nowMillis: Long) {
        if (active && !sawSlewing) startedAt = nowMillis
    }

    /**
     * Call as often as you like with what the Master last said. [slewing] is null when the Master has not said.
     * Returns what to show, or null when there is no slew to show.
     */
    fun update(mount: Pair<Double, Double>?, slewing: Boolean?, nowMillis: Long): Status? {
        if (!active) return null
        if (slewing == true) sawSlewing = true
        val remaining = mount?.let { separationDegrees(it.first, it.second, toRa, toDec) }
        if (arrivedAt == 0L) {
            when {
                sawSlewing && slewing == false -> arrivedAt = nowMillis
                // a slew that never began (refused, or the mount was already there) must not leave the indicator up
                !sawSlewing && nowMillis - startedAt > NEVER_STARTED_MILLIS -> { active = false; return null }
                !sawSlewing && remaining != null && remaining < ARRIVED_DEGREES && nowMillis - startedAt > 2_000 -> arrivedAt = nowMillis
            }
        } else if (nowMillis - arrivedAt > LINGER_MILLIS) {
            active = false
            return null
        }
        val total = separationDegrees(fromRa, fromDec, toRa, toDec)
        val left = if (arrivedAt != 0L) 0.0 else remaining ?: total
        val progress = if (arrivedAt != 0L) 1f else if (total < 0.01) 0f else (1.0 - left / total).coerceIn(0.0, 1.0).toFloat()
        return Status(name, fromRa, fromDec, toRa, toDec, left, progress, arrivedAt != 0L)
    }

    companion object {
        const val NEVER_STARTED_MILLIS = 15_000L
        const val LINGER_MILLIS = 4_000L
        const val ARRIVED_DEGREES = 0.1
    }
}
