package com.galileo.mobileconnect.quest

import com.galileo.mobileconnect.core.sky.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Something placed in the world that faces the user: the dock or a pane. */
data class Placement(val center: Vec3, val yawRadians: Float) {
    /** Right and up for a face that looks back at the origin of yaw: level, never tilted (MAPP-QST-190). */
    val right: Vec3 get() = Vec3(cos(yawRadians), 0f, -sin(yawRadians))
    val up: Vec3 get() = Vec3(0f, 1f, 0f)
}

/** The areas the dock offers, in order (MAPP-QST-330). Sky is not among them: the star field is the sky (MAPP-QST-300). */
enum class DockArea(val label: String) {
    Overview("Overview"), Equipment("Equipment"), Imaging("Imaging"), Focus("Focus"), Solve("Solve"), Guiding("Guiding"),
    Flip("Flip"), Sessions("Sessions"), Safety("Safety"), Settings("Settings"),
}

object Dock {
    const val WIDTH_METRES = 1.5f
    const val HEIGHT_METRES = 0.22f
    const val PIXEL_WIDTH = 1500
    const val PIXEL_HEIGHT = 220
    const val DISTANCE = 0.9f
    const val BELOW_GAZE = 0.40f

    /**
     * Where the dock goes when the app opens or the user calls it back: in front of and below where they are looking, level,
     * turned to face them. It is placed once and then stays: it does not follow the head (MAPP-QST-360).
     */
    fun placeInFrontOf(head: Vec3, gaze: Vec3): Placement {
        val flat = Vec3(gaze.x, 0f, gaze.z)
        val forward = if (flat.length() < 1e-3f) Vec3(0f, 0f, -1f) else flat.normalized() // looking straight up or down: keep facing the way we were
        val center = Vec3(head.x + forward.x * DISTANCE, head.y - BELOW_GAZE, head.z + forward.z * DISTANCE)
        // The face looks back at the user: its normal is -forward.
        return Placement(center, atan2(-forward.x, -forward.z))
    }

    fun quad(placement: Placement) = Quad(
        placement.center, placement.right, placement.up, WIDTH_METRES, HEIGHT_METRES, PIXEL_WIDTH, PIXEL_HEIGHT,
    )

    /** Which area button a point on the dock is over, when the dock is laid out in a row of equal cells after the status cell. */
    fun areaAt(xPixels: Float, yPixels: Float): DockArea? {
        if (yPixels < 0 || yPixels > PIXEL_HEIGHT) return null
        val index = ((xPixels - STATUS_PIXELS) / CELL_PIXELS).toInt()
        return if (xPixels >= STATUS_PIXELS && index in DockArea.entries.indices) DockArea.entries[index] else null
    }

    const val STATUS_PIXELS = 150f
    const val CELL_PIXELS = 100f
}

/** An open pane: a tablet screen on a floating quad. */
data class Pane(
    val area: DockArea,
    val placement: Placement,
    val widthMetres: Float = 1.2f,
    val heightMetres: Float = 0.8f,
    val pixelWidth: Int = 1200,
    val pixelHeight: Int = 800,
    /** Metres behind the pane's anchor, away from the user: set by the stack so the front pane is the nearest, and never nearer than the dock. */
    val depth: Float = 0.2f,
) {
    fun quad(): Quad {
        val away = Vec3(-sin(placement.yawRadians), 0f, -cos(placement.yawRadians))
        return Quad(placement.center + away * depth, placement.right, placement.up, widthMetres, heightMetres, pixelWidth, pixelHeight)
    }
}

/**
 * The open panes (MAPP-QST-340). Several can be open; each area has at most one pane; the dock is always nearest the user,
 * so no pane covers it (MAPP-QST-350); pressing an area that is open brings its pane to the front of the others.
 */
class PaneStack(private val dockPlacement: () -> Placement) {
    private val panes = ArrayList<Pane>() // back to front: the last is in front

    val open: List<Pane> get() = panes.toList()

    fun isOpen(area: DockArea) = panes.any { it.area == area }

    /** Opens the area's pane, or brings it to the front when it is already open. Returns the pane. */
    fun press(area: DockArea): Pane {
        val existing = panes.indexOfFirst { it.area == area }
        if (existing >= 0) {
            panes += panes.removeAt(existing)
        } else {
            val dock = dockPlacement()
            // New panes appear above the dock, a little to one side for each pane already open, so they do not hide one another.
            val n = panes.size
            val center = dock.center + dock.right * ((n % 3 - 1) * 0.25f) + Vec3(0f, PANE_LIFT, 0f)
            panes += Pane(area, Placement(center, dock.yawRadians))
        }
        relayer()
        return panes.last()
    }

    /** Back to front: the last pane is nearest the user, the first farthest, and all of them behind the dock. */
    private fun relayer() {
        val n = panes.size
        for (i in panes.indices) panes[i] = panes[i].copy(depth = BASE_DEPTH + (n - 1 - i) * STEP_DEPTH)
    }

    fun close(area: DockArea) {
        panes.removeAll { it.area == area }
        relayer()
    }

    fun move(area: DockArea, placement: Placement) {
        val i = panes.indexOfFirst { it.area == area }
        if (i >= 0) panes[i] = panes[i].copy(placement = placement)
    }

    fun resize(area: DockArea, widthMetres: Float, heightMetres: Float) {
        val i = panes.indexOfFirst { it.area == area }
        if (i >= 0) {
            val p = panes[i]
            val w = clamp(widthMetres, MIN_WIDTH, MAX_WIDTH)
            val h = clamp(heightMetres, MIN_HEIGHT, MAX_HEIGHT)
            panes[i] = p.copy(widthMetres = w, heightMetres = h)
        }
    }

    /** What a ray lands on: the dock if it is hit, else the front-most pane hit, with the point in that surface's pixels. */
    sealed interface Target {
        data class OnDock(val hit: Quad.Hit) : Target
        data class OnPane(val pane: Pane, val hit: Quad.Hit) : Target
    }

    fun pick(ray: Ray): Target? {
        val dock = Dock.quad(dockPlacement()).intersect(ray)
        val candidates = panes.mapNotNull { pane -> pane.quad().intersect(ray)?.let { pane to it } }
        val nearestPane = candidates.minByOrNull { it.second.distance }
        // Nothing may be in front of the dock (MAPP-QST-350): the dock wins whenever it is hit.
        if (dock != null && (nearestPane == null || dock.distance <= nearestPane.second.distance + DOCK_PRIORITY_SLACK)) return Target.OnDock(dock)
        return nearestPane?.let { Target.OnPane(it.first, it.second) }
    }

    private companion object {
        const val BASE_DEPTH = 0.15f
        const val STEP_DEPTH = 0.08f
        const val PANE_LIFT = 0.62f
        const val MIN_WIDTH = 0.5f
        const val MAX_WIDTH = 2.4f
        const val MIN_HEIGHT = 0.35f
        const val MAX_HEIGHT = 1.8f
        const val DOCK_PRIORITY_SLACK = 0.3f
    }
}

internal fun Vec3.horizontalLength(): Float = sqrt(x * x + z * z)
