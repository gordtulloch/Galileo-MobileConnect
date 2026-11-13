package com.galileo.mobileconnect.core.control

import com.galileo.mobileconnect.core.api.OpResult
import com.galileo.mobileconnect.core.link.LinkClock
import com.galileo.mobileconnect.core.link.SystemClock
import com.galileo.mobileconnect.core.state.bool
import com.galileo.mobileconnect.core.state.get
import com.galileo.mobileconnect.core.state.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Why a hold ended. The UI says so when the Master is what will stop the motion. */
sealed interface HoldStop {
    data object Released : HoldStop
    data object Background : HoldStop

    /** Nothing can reach the Master, so its deadman stops the motion (about 0.6 s). */
    data object ConnectionLost : HoldStop
    data object LeaseLost : HoldStop
    data class Refused(val reason: String) : HoldStop
    data object BeatFailed : HoldStop
}

sealed interface HoldState {
    data object Idle : HoldState
    data object Starting : HoldState
    data object Holding : HoldState
    data class Stopped(val reason: HoldStop) : HoldState
}

/**
 * Held motion (S-CTL, MAPP-DEV-030, MAPP-CTL-060): moves only while keep-alives flow. This owns the timing and the stop
 * conditions; the platform only forwards the press, the release and lifecycle changes.
 *
 * Nothing is locked across a suspension: a release that arrives while the start request is in flight must be able to
 * run, and the start then stops the hold it has just been given.
 */
class HoldSession(
    /** The operation prefix, for example `focus` for `focus.hold_start`. */
    private val family: String,
    private val scope: CoroutineScope,
    private val clock: LinkClock = SystemClock,
    private val invoke: suspend (operation: String, args: Map<String, JsonElement>) -> OpResult,
    private val onChange: suspend (HoldState) -> Unit = {},
) {
    private val lock = Any()
    private var holdId: String? = null
    private var beats: Job? = null
    private var finished = false

    @Volatile
    var state: HoldState = HoldState.Idle
        private set

    private suspend fun set(new: HoldState) {
        state = new
        onChange(new)
    }

    suspend fun start(args: Map<String, JsonElement>) {
        if (state != HoldState.Idle) return
        set(HoldState.Starting)
        val result = invoke("$family.hold_start", args)
        val value = (result as? OpResult.Ok)?.value
        val id = value?.string ?: value["hold_id"].string
        if (id == null) {
            when (result) {
                is OpResult.Refused -> finish(HoldStop.Refused(result.reason))
                is OpResult.Unavailable -> finish(HoldStop.Refused(result.reason))
                else -> finish(HoldStop.BeatFailed)
            }
            return
        }
        val releasedMeanwhile = synchronized(lock) {
            holdId = id
            finished
        }
        if (releasedMeanwhile) {
            stopRequest(id)
            return
        }
        set(HoldState.Holding)
        val loop = scope.launch { beatLoop(id) }
        synchronized(lock) { if (finished) loop.cancel() else beats = loop }
    }

    private suspend fun beatLoop(id: String) {
        while (true) {
            clock.sleep(BEAT_INTERVAL)
            scopeCheck()
            val result = invoke("$family.hold_beat", mapOf("hold_id" to JsonPrimitive(id)))
            scopeCheck()
            when {
                result is OpResult.Ok && result.value.bool != false -> continue
                result is OpResult.ConnectionLost -> {
                    stop(HoldStop.ConnectionLost)
                    return
                }
                else -> {
                    // The Master no longer knows the hold, or refused the beat: this hold is over either way.
                    stop(HoldStop.BeatFailed)
                    return
                }
            }
        }
    }

    private suspend fun scopeCheck() = currentCoroutineContext().ensureActive()

    /** Called on release, leaving the foreground, connection loss, lease loss, or any beat error. */
    suspend fun stop(reason: HoldStop) {
        val id = synchronized(lock) {
            if (finished) return
            finished = true
            beats?.cancel()
            beats = null
            holdId
        }
        // The beat loop ends its own hold, so what follows must survive the cancellation just issued.
        withContext(NonCancellable) {
            set(HoldState.Stopped(reason))
            if (id != null && reason != HoldStop.ConnectionLost) stopRequest(id)
        }
    }

    private suspend fun finish(reason: HoldStop) {
        synchronized(lock) { finished = true }
        set(HoldState.Stopped(reason))
    }

    /** Sent even when the coroutine that asks has been cancelled: it is what stops the motion. */
    private suspend fun stopRequest(id: String) {
        withContext(NonCancellable) { invoke("$family.hold_stop", mapOf("hold_id" to JsonPrimitive(id))) }
    }

    companion object {
        /** `HoldManager.DEFAULT_BEAT_S` on the Master. */
        const val BEAT_INTERVAL = 0.25
    }
}
