package com.galileo.mobileconnect.core.control

import com.galileo.mobileconnect.core.api.OpResult
import com.galileo.mobileconnect.core.api.OperationInfo
import com.galileo.mobileconnect.core.state.OperationEvent
import com.galileo.mobileconnect.core.state.displayText
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/** What became of one operation. Only the Master's answer moves it past [Pending] (MAPP-CTL-050). */
sealed interface OperationState {
    /** Open: the stream or the HTTP answer can still close it. */
    val isOpen: Boolean get() = false

    data object Pending : OperationState {
        override val isOpen get() = true
    }

    /** Accepted, and still running on the Master. */
    data class Running(val progress: Double? = null) : OperationState {
        override val isOpen get() = true
    }

    data class Done(val value: JsonElement? = null) : OperationState
    data class Refused(val reason: String) : OperationState
    data class Invalid(val path: String?, val reason: String) : OperationState
    data class Unavailable(val reason: String) : OperationState
    data class Busy(val reason: String) : OperationState
    data class Unreachable(val reason: String) : OperationState

    /** The link went while this was in flight (MAPP-CTL-070). Check the state; it is never re-sent. */
    data object OutcomeUnknown : OperationState
}

/** One operation as the user sees it. */
data class OperationRecord(
    /** The `Idempotency-Key` of the request. */
    val id: String,
    val pier: String,
    val operation: String,
    val state: OperationState,
    val correlationId: String? = null,
    val startedAt: Instant,
)

/** What has been asked of the Master and what became of it. A pure value, so its rules are simply testable. */
data class OperationLedger(
    val records: List<OperationRecord> = emptyList(),
    /** How many requests went out with each key: the proof that nothing was re-sent. */
    val sentCounts: Map<String, Int> = emptyMap(),
    /** A finish that arrived before the HTTP answer that names its correlation id. */
    private val earlyFinishes: Map<String, OperationEvent> = emptyMap(),
) {
    fun record(id: String): OperationRecord? = records.firstOrNull { it.id == id }

    fun latest(pier: String, operation: String): OperationRecord? =
        records.lastOrNull { it.pier == pier && it.operation == operation }

    fun sent(id: String, pier: String, operation: String, at: Instant): OperationLedger = copy(
        records = (records + OperationRecord(id, pier, operation, OperationState.Pending, startedAt = at)).takeLast(LIMIT),
        sentCounts = sentCounts + (id to (sentCounts[id] ?: 0) + 1),
    )

    /** Drops a record that only produced a request for confirmation: nothing happened yet. */
    fun forget(id: String): OperationLedger = copy(records = records.filterNot { it.id == id })

    private fun update(id: String, change: (OperationRecord) -> OperationRecord): OperationLedger =
        copy(records = records.map { if (it.id == id) change(it) else it })

    /** The Master's HTTP answer. It is authoritative even if it arrives after the stream dropped. */
    fun answered(id: String, result: OpResult, longRunning: Boolean): OperationLedger {
        val correlation = (result as? OpResult.Ok)?.correlationId
        val early = correlation?.let { earlyFinishes[it] }
        val remaining = if (correlation != null && early != null) earlyFinishes - correlation else earlyFinishes
        return copy(earlyFinishes = remaining).update(id) { record ->
            when (result) {
                is OpResult.Ok -> when {
                    early != null -> record.copy(correlationId = result.correlationId, state = finalState(early))
                    // Only keep a record open when the stream can close it.
                    longRunning && result.correlationId != null ->
                        // op.finished overtook the HTTP answer
                        if (record.state is OperationState.Done) record.copy(correlationId = result.correlationId)
                        else record.copy(correlationId = result.correlationId, state = OperationState.Running())
                    else -> record.copy(correlationId = result.correlationId, state = OperationState.Done(result.value))
                }
                is OpResult.Refused -> record.copy(state = OperationState.Refused(result.reason))
                is OpResult.Invalid -> record.copy(state = OperationState.Invalid(result.path, result.reason))
                is OpResult.Unavailable -> record.copy(state = OperationState.Unavailable(result.reason))
                is OpResult.Busy -> record.copy(state = OperationState.Busy(result.reason))
                is OpResult.Unreachable -> record.copy(state = OperationState.Unreachable(result.reason))
                is OpResult.ConfirmationRequired -> record // handled before this
                OpResult.ConnectionLost -> record.copy(state = OperationState.OutcomeUnknown)
            }
        }
    }

    /** The stream dropped: everything in flight is now unknown. */
    fun connectionLost(): OperationLedger =
        copy(records = records.map { if (it.state.isOpen) it.copy(state = OperationState.OutcomeUnknown) else it })

    fun apply(event: OperationEvent): OperationLedger {
        val index = records.indexOfFirst {
            (it.correlationId != null && it.correlationId == event.correlationId) ||
                (event.requestId != null && it.id == event.requestId)
        }
        if (index < 0) {
            if (event.phase != "op.finished") return this
            val kept = if (earlyFinishes.size >= LIMIT) emptyMap() else earlyFinishes
            return copy(earlyFinishes = kept + (event.correlationId to event))
        }
        val record = records[index].copy(correlationId = event.correlationId)
        val next = when (event.phase) {
            "op.started" -> if (record.state.isOpen) record.copy(state = OperationState.Running()) else record
            "op.progress" -> record.copy(state = OperationState.Running(event.progress))
            "op.finished" -> record.copy(state = finalState(event))
            else -> record
        }
        return copy(records = records.toMutableList().also { it[index] = next })
    }

    private fun finalState(event: OperationEvent): OperationState {
        val reason = event.reason ?: "The Master gave no reason."
        return when (event.outcome) {
            "refused" -> OperationState.Refused(reason)
            "invalid" -> OperationState.Invalid(null, reason)
            "unavailable" -> OperationState.Unavailable(reason)
            "busy" -> OperationState.Busy(reason)
            "unreachable" -> OperationState.Unreachable(reason)
            else -> OperationState.Done()
        }
    }

    companion object {
        const val LIMIT = 100
    }
}

/** A question to put to the user before anything is sent (ADR-APP-005). */
data class ConfirmationRequest(
    val pier: String,
    val operation: String,
    val args: Map<String, JsonElement>,
    val title: String,
    /** The arguments in words. */
    val details: List<String>,
    /** What the Master says this will do. */
    val consequence: String?,
    /** The Master's single-use token, when it asked; null when only the client's own list asks. */
    val token: String?,
) {
    val id: String get() = "$pier/$operation/${token ?: "local"}"
}

sealed interface RequestAnswer {
    data class Confirm(val request: ConfirmationRequest) : RequestAnswer
    data class Sent(val record: OperationRecord) : RequestAnswer
    data class NotAllowed(val reason: String) : RequestAnswer
    data class Invalid(val path: String, val reason: String) : RequestAnswer
}

object ControlPolicy {
    /**
     * Operations that always get a dialog here, whatever the Master's class says (MAPP-CTL-040). A minimum: whatever the
     * Master marks `confirm` also prompts.
     */
    val alwaysConfirm = setOf("camera.abort", "imaging.stop", "session.stop", "mount.park", "mount.unpark", "flip.now")

    fun title(operation: OperationInfo?, name: String): String {
        val description = operation?.description
        if (!description.isNullOrEmpty()) {
            // "Park the mount (asks for a second confirmation)": the note is for developers, not for this dialog.
            return description.substringBefore(" (")
        }
        return name.replace('.', ' ').replace('_', ' ').split(' ').joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    fun details(args: Map<String, JsonElement>, operation: OperationInfo?): List<String> =
        args.keys.sorted().map { key ->
            val unit = operation?.arguments?.firstOrNull { it.name == key }?.unit
            "${key.replace('_', ' ')}: ${args.getValue(key).displayText()}${unit?.let { " $it" } ?: ""}"
        }
}
