package com.galileo.mobileconnect.core.sky

import com.galileo.mobileconnect.core.link.LinkClock
import com.galileo.mobileconnect.core.link.MasterApi
import com.galileo.mobileconnect.core.link.SystemClock
import com.galileo.mobileconnect.core.link.skyPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/** A short-lived, bounded memory of streamed pages (MAPP-STR-050, Shared SDD D3). Nothing is written to disk. */
class SkyPageCache(val maximumObjects: Int = 150_000, val lifetimeSeconds: Long = 15 * 60) {
    data class Key(val ra: Int, val dec: Int, val radius: Int, val level: Int)

    private class Entry(val objects: List<SkyObject>, val complete: Boolean, val stored: Instant, var used: Instant)

    private val entries = LinkedHashMap<Key, Entry>()
    var objectCount = 0
        private set

    @Synchronized
    fun get(key: Key, now: Instant): Pair<List<SkyObject>, Boolean>? {
        val entry = entries[key] ?: return null
        if (now.epochSecond - entry.stored.epochSecond > lifetimeSeconds) {
            remove(key)
            return null
        }
        entry.used = now
        return entry.objects to entry.complete
    }

    @Synchronized
    fun put(key: Key, objects: List<SkyObject>, complete: Boolean, now: Instant) {
        remove(key)
        entries[key] = Entry(objects, complete, now, now)
        objectCount += objects.size
        while (objectCount > maximumObjects && entries.size > 1) {
            val oldest = entries.filter { it.key != key }.minByOrNull { it.value.used }?.key ?: break
            remove(oldest)
        }
    }

    private fun remove(key: Key) {
        entries.remove(key)?.let { objectCount -= it.objects.size }
    }
}

/**
 * S-SKY: asks the Master for what the view is looking at, brightest first, and hands over each page as it arrives
 * (MAPP-STR-010, 040). Requests stay under the Master's rate; being told to slow down just slows it.
 */
class SkyStore(
    private val api: MasterApi,
    private val scope: CoroutineScope,
    private val clock: LinkClock = SystemClock,
) {
    private val cache = SkyPageCache()
    private val objects = HashMap<Int, SkyObject>()
    private var loading: Job? = null
    private val keepObjects = 80_000
    private val maximumPages = 40
    private val maximumBackOff = 16.0

    /** Seconds between requests, from the Master's stated rate. */
    @Volatile
    var minimumInterval = 0.15
        private set

    val cachedObjectCount: Int get() = cache.objectCount

    fun setRate(perSecond: Double?) {
        if (perSecond != null && perSecond > 0) minimumInterval = 1 / perSecond * 1.2
    }

    /** The view has settled here. Replaces whatever was still loading. [update] gets the whole current set each time. */
    @Synchronized
    fun look(raDeg: Double, decDeg: Double, radius: Double, level: Int, update: suspend (List<SkyObject>) -> Unit) {
        loading?.cancel()
        // Quantised, so that small moves ask for nothing new.
        val step = max(1.0, radius / 2)
        val ra = (Math.rint(raDeg / step) * step) % 360
        val dec = max(-90.0, min(90.0, Math.rint(decDeg / step) * step))
        val region = min(180.0, radius * 1.3)
        val key = SkyPageCache.Key((ra * 10).toInt(), (dec * 10).toInt(), (region * 10).toInt(), level)
        loading = scope.launch { load(key, if (ra < 0) ra + 360 else ra, dec, region, level, update) }
    }

    @Synchronized
    fun stop() {
        loading?.cancel()
        loading = null
    }

    val currentObjects: List<SkyObject> get() = synchronized(objects) { objects.values.toList() }

    private fun merge(page: List<SkyObject>) = synchronized(objects) {
        for (o in page) objects[o.index] = o
        if (objects.size > keepObjects) {
            // Over the bound: the faintest go first, since they matter least and return with the next look.
            val keep = objects.values.sortedBy { it.magnitude }.take(keepObjects)
            objects.clear()
            for (o in keep) objects[o.index] = o
        }
    }

    private suspend fun load(
        key: SkyPageCache.Key, ra: Double, dec: Double, radius: Double, level: Int,
        update: suspend (List<SkyObject>) -> Unit,
    ) {
        val collected = ArrayList<SkyObject>()
        cache.get(key, clock.now())?.let { (cached, complete) ->
            merge(cached)
            update(currentObjects)
            if (complete) return
        }
        var cursor: String? = null
        var backOff = 1.0
        var pages = 0
        while (scope.isActive && pages < maximumPages) {
            when (val answer = api.skyPage(ra, dec, radius, level, cursor)) {
                is SkyPage.Page -> {
                    pages += 1
                    backOff = 1.0
                    collected += answer.objects
                    merge(answer.objects)
                    update(currentObjects)
                    cache.put(key, collected.toList(), answer.next.isEmpty(), clock.now())
                    if (answer.next.isEmpty()) return
                    cursor = answer.next
                    clock.sleep(minimumInterval)
                }
                SkyPage.BackOff -> {
                    clock.sleep(backOff)
                    backOff = min(maximumBackOff, backOff * 2)
                    if (backOff >= maximumBackOff) return // the link is down, or the Master is busy: the next look asks again
                }
                is SkyPage.Refused -> return
            }
        }
    }
}
