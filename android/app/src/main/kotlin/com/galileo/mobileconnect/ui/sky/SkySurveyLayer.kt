package com.galileo.mobileconnect.ui.sky

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.galileo.mobileconnect.core.link.MasterApi
import com.galileo.mobileconnect.core.link.surveyTile
import com.galileo.mobileconnect.core.sky.SkySurvey
import com.galileo.mobileconnect.core.sky.SkyTile
import com.galileo.mobileconnect.core.sky.SkyTileImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The survey tiles held for drawing: bitmaps and their sky-shaped meshes, fetched from the Master as views need them
 * (MAPP-SKY-070). In memory only, because the pictures aren't ours to keep.
 */
class SkySurveyLayer(private val api: MasterApi, private val scope: CoroutineScope) {
    /** A tile ready to draw: its picture and a grid of real sky directions (x, y, z per vertex, row by row). */
    class Drawable(val tile: SkyTile, val bitmap: Bitmap, val directions: FloatArray)

    /** Whose pictures these are, once there are some. */
    var credit by mutableStateOf<String?>(null)
        private set

    /** Moves when a tile arrives, so that the view draws again. */
    var version by mutableIntStateOf(0)
        private set

    private val held = HashMap<SkyTile, Drawable>()
    private val lastUsed = HashMap<SkyTile, Int>()
    private var clock = 0
    private val fetching = HashMap<SkyTile, Job>()
    private val retryAfter = HashMap<SkyTile, Long>()

    @Synchronized
    fun stop() {
        fetching.values.forEach { it.cancel() }
        fetching.clear()
    }

    /**
     * What to draw for a view, coarsest first, so that a finer tile covers its parent. A tile not here yet is drawn from
     * the nearest ancestor that is, and asked for.
     */
    @Synchronized
    fun drawables(wanted: List<SkyTile>, fetch: Boolean = true): List<Drawable> {
        clock += 1
        val chosen = LinkedHashSet<SkyTile>()
        for (tile in wanted) {
            var candidate: SkyTile? = tile
            while (candidate != null && held[candidate] == null) candidate = candidate.parent
            if (candidate != null) {
                chosen += candidate
                lastUsed[candidate] = clock
            }
        }
        if (fetch) request(wanted)
        return chosen.sortedBy { it.order }.mapNotNull { held[it] }
    }

    private fun request(wanted: List<SkyTile>) {
        // Tiles the view has moved away from aren't worth finishing.
        val current = wanted.toSet()
        for (tile in fetching.keys.filter { it !in current }) fetching.remove(tile)?.cancel()
        val now = System.currentTimeMillis()
        for (tile in wanted) {
            if (fetching.size >= SIMULTANEOUS) break
            if (held[tile] != null || fetching[tile] != null || (retryAfter[tile] ?: 0L) > now) continue
            fetching[tile] = scope.launch {
                val image = api.surveyTile(tile)
                arrived(tile, image)
            }
        }
    }

    private suspend fun arrived(tile: SkyTile, image: SkyTileImage?) {
        if (image == null) return finished(tile, failed = true)
        credit = image.credit
        // Decoding is done away from the main thread.
        val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(image.jpeg, 0, image.jpeg.size) }
        if (bitmap == null) return finished(tile, failed = true)
        synchronized(this) {
            held[tile] = Drawable(tile, bitmap, mesh(tile))
            lastUsed[tile] = clock
            trim()
        }
        finished(tile, failed = false)
    }

    @Synchronized
    private fun finished(tile: SkyTile, failed: Boolean) {
        fetching.remove(tile)
        if (failed) retryAfter[tile] = System.currentTimeMillis() + RETRY_MILLIS
        version += 1 // which also asks for the next ones
    }

    private fun trim() {
        if (held.size <= HELD_LIMIT) return
        for (tile in held.keys.sortedBy { lastUsed[it] ?: 0 }.take(held.size - HELD_LIMIT)) {
            held.remove(tile)?.bitmap?.recycle()
            lastUsed.remove(tile)
        }
    }

    /**
     * A grid of real sky directions: each vertex then goes through the same projection as the stars, so a tile can't
     * disagree with them, and the cell's curved edges come out curved. In the image the column is `fy` and the row `fx`:
     * measured against catalog stars on the real tiles.
     */
    private fun mesh(tile: SkyTile): FloatArray {
        val out = FloatArray((CELLS + 1) * (CELLS + 1) * 3)
        var n = 0
        for (i in 0..CELLS) for (j in 0..CELLS) {
            val d = SkySurvey.direction(tile, i.toDouble() / CELLS, j.toDouble() / CELLS)
            out[n++] = d.x.toFloat()
            out[n++] = d.y.toFloat()
            out[n++] = d.z.toFloat()
        }
        return out
    }

    companion object {
        const val CELLS = 8
        private const val SIMULTANEOUS = 4
        private const val HELD_LIMIT = 96 // a megabyte each
        private const val RETRY_MILLIS = 60_000L
    }
}
