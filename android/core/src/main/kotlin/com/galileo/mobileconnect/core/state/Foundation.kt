package com.galileo.mobileconnect.core.state

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.OffsetDateTime

// Small, null-safe readers over kotlinx's JSON tree, the Kotlin counterpart of the iOS `JSONValue` accessors.

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

val JsonElement?.string: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

val JsonElement?.int: Int?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

val JsonElement?.double: Double?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

val JsonElement?.bool: Boolean?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

val JsonElement?.obj: JsonObject? get() = this as? JsonObject

/** A value as the UI shows it, in the same words as the iOS `JSONValue.displayText`. */
fun JsonElement.displayText(): String = when (this) {
    is JsonNull -> "—"
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> if (booleanOrNull == true) "Yes" else "No"
        else -> {
            val d = doubleOrNull
            when {
                d == null -> content
                d == Math.rint(d) && Math.abs(d) < 1e15 -> d.toLong().toString()
                else -> BigDecimal(d).round(MathContext(3)).stripTrailingZeros().toPlainString()
            }
        }
    }
    is JsonArray -> joinToString(", ") { it.displayText() }
    is JsonObject -> keys.sorted().joinToString(", ") { "$it: ${this[it]!!.displayText()}" }
}

/** The Master's timestamps, with or without fractional seconds, `Z` or an offset. */
object WireTime {
    private val fractional = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)

    /** As the Master writes it (and iOS does): UTC, with milliseconds. */
    fun string(at: Instant): String = fractional.format(at)

    fun parse(text: String): Instant? =
        runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
}
