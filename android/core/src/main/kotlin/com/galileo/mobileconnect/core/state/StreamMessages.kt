package com.galileo.mobileconnect.core.state

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant

data class AlertEvent(
    val id: String,
    val severity: String,
    val time: Instant,
    val pier: String? = null,
    val message: String,
    val important: Boolean = false,
)

data class OperationEvent(
    /** `op.started`, `op.progress` or `op.finished`. */
    val phase: String,
    val correlationId: String,
    val requestId: String? = null,
    val pier: String? = null,
    val operation: String? = null,
    val progress: Double? = null,
    val outcome: String? = null,
    val reason: String? = null,
)

sealed interface StreamEvent {
    data class Snapshot(val asOf: Instant, val piers: Map<String, JsonElement>) : StreamEvent
    data class Delta(val pier: String, val key: String, val value: JsonElement, val asOf: Instant) : StreamEvent
    data class Operation(val event: OperationEvent) : StreamEvent
    data class Alert(val alert: AlertEvent) : StreamEvent
    data class Lease(val pier: String, val holder: String?, val holderName: String?) : StreamEvent
    data class Access(val pier: String, val access: String, val reachable: Boolean?, val reason: String?) : StreamEvent
    data class Closing(val reason: String) : StreamEvent

    /** A newer Master's message. Ignored, never an error (`events.schema.json`). */
    data object Unknown : StreamEvent
}

/** One frame of `GET /connect/v1/stream`. */
sealed interface StreamMessage {
    data class Hello(val epoch: String, val seq: Int) : StreamMessage
    data class Resync(val epoch: String, val seq: Int) : StreamMessage
    data class Frame(val seq: Int, val event: StreamEvent) : StreamMessage

    companion object {
        fun parse(text: String): StreamMessage? =
            runCatching { Json.parseToJsonElement(text) }.getOrNull()?.let(::from)

        fun from(json: JsonElement): StreamMessage? {
            val seq = json["seq"].int ?: return null
            return when (json["type"].string) {
                "hello" -> Hello(json["epoch"].string ?: return null, seq)
                "resync" -> Resync(json["epoch"].string ?: return null, seq)
                else -> Frame(seq, event(json["data"] ?: return null))
            }
        }

        private fun event(json: JsonElement): StreamEvent {
            fun time(key: String) = json[key].string?.let(WireTime::parse)
            val type = json["type"].string
            return when {
                type == "snapshot" -> {
                    val asOf = time("as_of")
                    val piers = json["piers"].obj
                    if (asOf == null || piers == null) StreamEvent.Unknown else StreamEvent.Snapshot(asOf, piers)
                }
                type == "delta" -> {
                    val pier = json["pier"].string
                    val key = json["key"].string
                    val value = json["value"]
                    val asOf = time("as_of")
                    if (pier == null || key == null || value == null || asOf == null) StreamEvent.Unknown
                    else StreamEvent.Delta(pier, key, value, asOf)
                }
                type != null && type.startsWith("op.") -> {
                    val correlation = json["correlation_id"].string ?: return StreamEvent.Unknown
                    StreamEvent.Operation(
                        OperationEvent(
                            phase = type, correlationId = correlation, requestId = json["request_id"].string,
                            pier = json["pier"].string, operation = json["operation"].string,
                            progress = json["progress"].double, outcome = json["outcome"].string,
                            reason = json["reason"].string,
                        ),
                    )
                }
                type == "alert" -> {
                    val id = json["id"].string
                    val severity = json["severity"].string
                    val at = time("time")
                    val message = json["message"].string
                    if (id == null || severity == null || at == null || message == null) StreamEvent.Unknown
                    else StreamEvent.Alert(
                        AlertEvent(id, severity, at, json["pier"].string, message, json["important"].bool ?: false),
                    )
                }
                type == "lease" -> {
                    val pier = json["pier"].string ?: return StreamEvent.Unknown
                    StreamEvent.Lease(pier, json["holder"].string, json["holder_name"].string)
                }
                type == "access" -> {
                    val pier = json["pier"].string
                    val access = json["access"].string
                    if (pier == null || access == null) StreamEvent.Unknown
                    else StreamEvent.Access(pier, access, json["reachable"].bool, json["reason"].string)
                }
                type == "closing" -> StreamEvent.Closing(json["reason"].string ?: "")
                else -> StreamEvent.Unknown
            }
        }
    }
}
