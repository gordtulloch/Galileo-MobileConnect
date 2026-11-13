package com.galileo.mobileconnect.core.sky

import java.time.Instant
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** One tile of the all-sky survey the Master serves: a HEALPix cell at a HiPS order, in J2000 (MAPP-SKY-070). */
data class SkyTile(val order: Int, val npix: Int) {
    val parent: SkyTile? get() = if (order > 0) SkyTile(order - 1, npix / 4) else null
}

/** A tile's picture and whose picture it is. Not ours to keep: memory only, and its credit goes where it goes. */
class SkyTileImage(val jpeg: ByteArray, val credit: String)

/** Where in a tile a position is, each fraction 0-1. In the tile's image, the column is [fy] and the row [fx]. */
data class TilePlace(val tile: SkyTile, val fx: Double, val fy: Double)

/** HEALPix, nested: the same maths as `galileo.planning.sky_survey`, against the same vectors (`api/vectors/sky/healpix.json`). */
object SkySurvey {
    /** Mellinger's mosaic has nothing finer. */
    const val MAX_ORDER = 4

    /** Enough for a screen at its order; a view needing more is drawn one order coarser. */
    const val MAX_VIEW_TILES = 48
    private const val ORDER0_DEGREES_PER_PIXEL = 58.63 / 512

    /** A view narrower than this fetches no new tiles: it draws what it holds, so panning deep in never waits on the network. */
    const val FETCH_ABOVE_FIELD_DEGREES = 3.0

    fun tile(raDeg: Double, decDeg: Double, order: Int): TilePlace {
        val (face, x, y) = faceXY(raDeg, decDeg)
        val side = 1 shl order
        val tx = min((x * side).toInt(), side - 1)
        val ty = min((y * side).toInt(), side - 1)
        var npix = 0
        for (bit in 0 until order) {
            npix = npix or (((tx shr bit) and 1) shl (2 * bit))
            npix = npix or (((ty shr bit) and 1) shl (2 * bit + 1))
        }
        return TilePlace(SkyTile(order, (face shl (2 * order)) or npix), x * side - tx, y * side - ty)
    }

    private data class Face(val face: Int, val x: Double, val y: Double)

    private fun faceXY(raDeg: Double, decDeg: Double): Face {
        val z = sin(decDeg * PI / 180)
        var tt = (raDeg * PI / 180 / (PI / 2)) % 4
        if (tt < 0) tt += 4
        if (abs(z) <= 2.0 / 3) {
            val jp = 0.5 + tt - 0.75 * z
            val jm = 0.5 + tt + 0.75 * z
            val ifp = floor(jp)
            val ifm = floor(jm)
            val face = when {
                ifp == ifm -> ifp.toInt() % 4 + 4
                ifp < ifm -> ifp.toInt() % 4
                else -> ifm.toInt() % 4 + 8
            }
            return Face(face, jm - ifm, 1 - (jp - ifp))
        }
        val ntt = min(floor(tt), 3.0)
        val tp = tt - ntt
        val tmp = sqrt(3 * (1 - abs(z)))
        val a = min(tp * tmp, 1.0)
        val b = min((1 - tp) * tmp, 1.0)
        return if (z > 0) Face(ntt.toInt(), 1 - b, 1 - a) else Face(ntt.toInt() + 8, a, b)
    }

    private val jrBase = doubleArrayOf(2.0, 2.0, 2.0, 2.0, 3.0, 3.0, 3.0, 3.0, 4.0, 4.0, 4.0, 4.0)
    private val tBase = doubleArrayOf(1.0, 3.0, 5.0, 7.0, 0.0, 2.0, 4.0, 6.0, 1.0, 3.0, 5.0, 7.0)

    /** The J2000 unit vector at a place in a tile. */
    fun direction(tile: SkyTile, fx: Double, fy: Double): Vec3d {
        val cells = 1 shl (2 * tile.order)
        val face = tile.npix / cells
        var inFace = tile.npix % cells
        var tx = 0
        var ty = 0
        var bit = 0
        while (inFace > 0) {
            tx = tx or ((inFace and 1) shl bit)
            ty = ty or (((inFace shr 1) and 1) shl bit)
            inFace = inFace shr 2
            bit += 1
        }
        val side = (1 shl tile.order).toDouble()
        val x = (tx + fx) / side
        val y = (ty + fy) / side
        val jr = jrBase[face] - x - y
        val z: Double
        val nr: Double
        when {
            jr < 1 -> {
                nr = jr
                z = 1 - nr * nr / 3
            }
            jr > 3 -> {
                nr = 4 - jr
                z = nr * nr / 3 - 1
            }
            else -> {
                nr = 1.0
                z = (2 - jr) * 2 / 3
            }
        }
        val t = tBase[face] * nr + x - y
        val phi = if (nr > 1e-12) PI / 4 * t / nr else 0.0
        val s = sqrt(max(0.0, 1 - z * z))
        return Vec3d(s * cos(phi), s * sin(phi), z)
    }

    /** One finer than just matching the screen, because a tile's JPEG blocks show otherwise. */
    fun order(degreesPerPixel: Double): Int =
        max(0, min(MAX_ORDER, 1 + ceil(ln(ORDER0_DEGREES_PER_PIXEL / max(degreesPerPixel, 1e-6)) / ln(2.0)).toInt()))

    /** The tiles a cone of the sky touches, by descending from the 12 base cells. May include a few just outside it. */
    fun tiles(centre: Vec3d, radiusDegrees: Double, order: Int): List<SkyTile> {
        val found = ArrayList<SkyTile>()
        fun visit(tile: SkyTile) {
            val middle = direction(tile, 0.5, 0.5)
            val distance = acos(min(1.0, max(-1.0, middle.dot(centre)))) * 180 / PI
            // A cell's corners are at most this far from its middle: the base cells' ~48 degrees, halving each order.
            if (distance > radiusDegrees + 55 / (1 shl tile.order).toDouble()) return
            if (tile.order == order) found += tile else for (child in 0 until 4) visit(SkyTile(tile.order + 1, tile.npix * 4 + child))
        }
        for (face in 0 until 12) visit(SkyTile(0, face))
        // Nearest first: what the user is looking at comes before the corners.
        return found.sortedByDescending { direction(it, 0.5, 0.5).dot(centre) }
    }

    /** What a view needs: the finest order that doesn't take too many tiles. */
    fun plan(camera: SkyCamera, site: SkySite, moment: Instant, width: Double, height: Double, pixelsPerPoint: Double): List<SkyTile> {
        val scale = camera.scale(height)
        if (scale <= 0) return emptyList()
        val toHorizon = SkyGeometry.equatorialToHorizon(site, moment).transpose()
        val toView = camera.horizonToView.transpose()
        val forward = toHorizon * (toView * Vec3(0f, 0f, 1f))
        val centre = Vec3d(forward.x.toDouble(), forward.y.toDouble(), forward.z.toDouble()).normalized()
        val radius = 2 * atan(hypot(width, height) / 2 / scale / 2) * 180 / PI
        var level = order(180 / PI / scale / pixelsPerPoint)
        var found = tiles(centre, radius, level)
        while (found.size > MAX_VIEW_TILES && level > 0) {
            level -= 1
            found = tiles(centre, radius, level)
        }
        return found
    }
}
