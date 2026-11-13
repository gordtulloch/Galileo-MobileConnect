package com.galileo.mobileconnect.core.state

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant

data class Stamped(val value: JsonElement, val asOf: Instant)

data class SeriesPoint(val value: JsonElement, val asOf: Instant)

/** One span of a Dashboard activity lane: [state] from [start] until the next segment. */
data class LaneSegment(val state: String, val start: Instant)

data class PierState(
    /**
     * Leaves by dotted key, for example `mount.tracking`. The Master publishes such fragments
     * (`galileo.control_surface.state`), so a key is the natural unit of state and of staleness.
     */
    val values: Map<String, Stamped> = emptyMap(),
    val series: Map<String, List<SeriesPoint>> = emptyMap(),
    val lanes: Map<String, List<LaneSegment>> = emptyMap(),
    val leaseHolder: String? = null,
    /** Who holds the lease, for a person to read. Null from a Master that gives only [leaseHolder]. */
    val leaseHolderName: String? = null,
    val access: String? = null,
    val reachable: Boolean? = null,
    val reason: String? = null,
)

enum class LivePhase {
    /** Connected, or connecting, with no snapshot yet. */
    Loading,
    Live,

    /** The Master could not replay the gap; state was discarded and a new snapshot is on its way. */
    Resyncing,
}

/**
 * Everything the Master has said, and nothing else (ADR-APP-002). Changed only by [reduce], a pure function of its
 * inputs with no clock or I/O: what makes the shared vectors possible (S-STATE).
 */
data class LiveState(
    val phase: LivePhase = LivePhase.Loading,
    val epoch: String? = null,
    val lastSeq: Int? = null,
    val piers: Map<String, PierState> = emptyMap(),
    val alerts: List<AlertEvent> = emptyList(),
    val closingReason: String? = null,
    internal val buffered: List<StreamEvent.Delta> = emptyList(),
) {
    companion object {
        private val seriesKeys = setOf("guiding.error", "focus.vcurve")
        internal const val SERIES_LIMIT = 300
        private const val LANE_LIMIT = 200
        private const val ALERT_LIMIT = 200
        private const val LANE_PREFIX = "activity."

        fun reduce(state: LiveState, message: StreamMessage): LiveState = when (message) {
            is StreamMessage.Hello ->
                // A resuming hello reports the head of the stream and replay follows, so the position only moves on
                // the frames themselves.
                if (state.epoch != message.epoch || state.lastSeq == null) {
                    state.copy(epoch = message.epoch, lastSeq = message.seq)
                } else {
                    state
                }

            is StreamMessage.Resync -> state.copy(
                epoch = message.epoch,
                lastSeq = message.seq,
                phase = LivePhase.Resyncing,
                piers = state.piers.mapValues { (_, old) ->
                    PierState(access = old.access, reachable = old.reachable, reason = old.reason)
                },
                buffered = emptyList(),
            )

            is StreamMessage.Frame -> {
                val last = state.lastSeq
                if (last != null && message.seq <= last) state // a replayed duplicate
                else apply(state.copy(lastSeq = message.seq), message.event)
            }
        }

        private fun apply(state: LiveState, event: StreamEvent): LiveState = when (event) {
            is StreamEvent.Snapshot -> applySnapshot(state, event)

            is StreamEvent.Delta ->
                if (state.phase == LivePhase.Live) {
                    state.copy(piers = state.piers + (event.pier to applyDelta(state.piers[event.pier], event)))
                } else {
                    state.copy(buffered = state.buffered + event)
                }

            is StreamEvent.Alert -> {
                val alerts = (listOf(event.alert) + state.alerts.filterNot { it.id == event.alert.id }).take(ALERT_LIMIT)
                state.copy(alerts = alerts)
            }

            is StreamEvent.Lease -> state.withPier(event.pier) {
                it.copy(leaseHolder = event.holder, leaseHolderName = event.holderName)
            }

            is StreamEvent.Access -> state.withPier(event.pier) {
                it.copy(access = event.access, reachable = event.reachable, reason = event.reason)
            }

            is StreamEvent.Closing -> state.copy(closingReason = event.reason)

            // Operation events are the ledger's (S-CTL).
            is StreamEvent.Operation, StreamEvent.Unknown -> state
        }

        private fun LiveState.withPier(id: String, change: (PierState) -> PierState) =
            copy(piers = piers + (id to change(piers[id] ?: PierState())))

        private fun applySnapshot(state: LiveState, event: StreamEvent.Snapshot): LiveState {
            val piers = LinkedHashMap<String, PierState>()
            for ((id, content) in event.piers) {
                val old = state.piers[id]
                val values = LinkedHashMap<String, Stamped>()
                flatten(content, "", values, event.asOf)
                var pier = (old ?: PierState()).copy(
                    values = values,
                    series = old?.series ?: emptyMap(),
                    lanes = old?.lanes ?: emptyMap(),
                    leaseHolder = values["lease.holder"]?.value.string,
                    leaseHolderName = values["lease.holder_name"]?.value.string,
                )
                for (key in values.keys.filter { it.startsWith(LANE_PREFIX) }) {
                    pier = addLaneSegment(pier, key, values.getValue(key).value, event.asOf)
                }
                piers[id] = pier
            }
            var next = state.copy(piers = piers, closingReason = null, phase = LivePhase.Live, buffered = emptyList())
            for (waiting in state.buffered.filter { it.asOf >= event.asOf }) {
                next = next.copy(piers = next.piers + (waiting.pier to applyDelta(next.piers[waiting.pier], waiting)))
            }
            return next
        }

        private fun applyDelta(existing: PierState?, delta: StreamEvent.Delta): PierState {
            var pier = existing ?: PierState()
            val values = LinkedHashMap(pier.values)
            // A delta replaces the whole subtree at its key.
            values.keys.removeAll { it == delta.key || it.startsWith(delta.key + ".") }
            flatten(delta.value, delta.key, values, delta.asOf)
            pier = pier.copy(values = values)
            if (delta.key in seriesKeys) {
                val points = ((pier.series[delta.key] ?: emptyList()) + SeriesPoint(delta.value, delta.asOf)).takeLast(SERIES_LIMIT)
                pier = pier.copy(series = pier.series + (delta.key to points))
            }
            if (delta.key.startsWith(LANE_PREFIX)) pier = addLaneSegment(pier, delta.key, delta.value, delta.asOf)
            return pier
        }

        private fun addLaneSegment(pier: PierState, key: String, value: JsonElement, at: Instant): PierState {
            val lane = key.removePrefix(LANE_PREFIX)
            val state = value.displayText()
            val existing = pier.lanes[lane] ?: emptyList()
            if (existing.lastOrNull()?.state == state) return pier
            return pier.copy(lanes = pier.lanes + (lane to (existing + LaneSegment(state, at)).takeLast(LANE_LIMIT)))
        }

        private fun flatten(value: JsonElement, prefix: String, into: MutableMap<String, Stamped>, asOf: Instant) {
            if (value is JsonObject && value.isNotEmpty()) {
                for ((key, child) in value) flatten(child, if (prefix.isEmpty()) key else "$prefix.$key", into, asOf)
            } else if (prefix.isNotEmpty()) {
                into[prefix] = Stamped(value, asOf)
            }
        }
    }
}

/** A value as the UI may show it: never stale as if live (MAPP-OVR-040). */
data class ValueView(val value: JsonElement, val age: Duration, val stale: Boolean)

/**
 * The Master publishes a value only when it changes (`ObservableState.publish`), so most keys must not age out while
 * the stream is up: silence means "unchanged". Only values that move continuously have a limit.
 */
object FreshnessLimits {
    private val prefixes = listOf("mount.position" to 10L, "guiding.error" to 15L, "weather." to 300L, "node.health" to 60L)

    fun limitSeconds(key: String): Long? = prefixes.firstOrNull { key.startsWith(it.first) }?.second
}

fun LiveState.view(pier: String, key: String, now: Instant, connected: Boolean): ValueView? {
    val stamped = piers[pier]?.values?.get(key) ?: return null
    val age = Duration.between(stamped.asOf, now).let { if (it.isNegative) Duration.ZERO else it }
    val overLimit = FreshnessLimits.limitSeconds(key)?.let { age.seconds > it } ?: false
    return ValueView(stamped.value, age, !connected || overLimit)
}
