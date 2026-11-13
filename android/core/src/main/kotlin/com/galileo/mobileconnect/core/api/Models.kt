package com.galileo.mobileconnect.core.api

import com.galileo.mobileconnect.core.state.bool
import com.galileo.mobileconnect.core.state.double
import com.galileo.mobileconnect.core.state.string
import kotlinx.serialization.json.JsonElement

/** The Master's own number format: whole numbers without a decimal point, and no digit grouping. */
internal fun plainNumber(value: Double): String =
    if (value == Math.rint(value) && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()

data class ArgumentInfo(
    val name: String,
    val kind: Kind,
    val required: Boolean = true,
    val unit: String? = null,
    val description: String? = null,
    val defaultValue: JsonElement? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val choices: List<String> = emptyList(),
) {
    enum class Kind(val wire: String) {
        Int("int"), Float("float"), Str("str"), Bool("bool"), Enum("enum");

        companion object {
            fun of(wire: String) = entries.firstOrNull { it.wire == wire }
        }
    }

    /** Why [value] is not acceptable, in the words `galileo.control_surface.schema` uses, or null. */
    fun problem(value: JsonElement): String? {
        val unitSuffix = unit?.let { " $it" } ?: ""
        return when (kind) {
            Kind.Bool -> if (value.bool == null) "must be true or false" else null
            Kind.Int, Kind.Float -> {
                val number = value.double ?: return "must be a number$unitSuffix"
                when {
                    kind == Kind.Int && number != Math.rint(number) -> "must be a whole number"
                    !number.isFinite() -> "must be finite"
                    minimum != null && number < minimum -> "must be at least ${plainNumber(minimum)}$unitSuffix"
                    maximum != null && number > maximum -> "must be at most ${plainNumber(maximum)}$unitSuffix"
                    else -> null
                }
            }
            Kind.Str -> if (value.string == null) "must be text" else null
            Kind.Enum -> {
                val text = value.string ?: return "must be text"
                if (text in choices) null else "must be one of ${choices.joinToString(", ")}"
            }
        }
    }
}

enum class OperationClass(val wire: String) {
    Read("read"), Operate("operate"), Confirm("confirm"), Emergency("emergency");

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire }
    }
}

data class ArgumentFault(val path: String, val reason: String)

data class OperationInfo(
    val name: String,
    val area: String,
    val operationClass: OperationClass,
    val needsLease: Boolean,
    val arguments: List<ArgumentInfo> = emptyList(),
    val description: String? = null,
    val available: Boolean = true,
    val unavailableBecause: String? = null,
    val longRunning: Boolean = false,
) {
    /** The first fault in [args], naming the argument, exactly as the Master would (`validate_args`). */
    fun validate(args: Map<String, JsonElement>): ArgumentFault? {
        val known = arguments.map { it.name }.toSet()
        args.keys.filter { it !in known }.minOrNull()?.let { return ArgumentFault(it, "is not an argument of this operation") }
        for (argument in arguments) {
            val value = args[argument.name]
            if (value != null) {
                argument.problem(value)?.let { return ArgumentFault(argument.name, it) }
            } else if (argument.required && argument.defaultValue == null) {
                return ArgumentFault(argument.name, "is required")
            }
        }
        return null
    }
}

/**
 * The catalogue as the app may see it: configuration cannot be represented, and a focuser can only be moved through the
 * focus module (MAPP-CFG-010, MAPP-FOC-030).
 */
object CatalogueFilter {
    private val allowedAreas = setOf(
        "camera", "device", "dome", "filter", "flatpanel", "flip", "focus", "guiding", "history", "imaging", "mount",
        "pier", "rotator", "safety", "scheduler", "session", "sky", "solve", "system", "weather",
    )

    /** Null when the entry must not reach the app: an unknown class (`config` included) or a name outside the areas. */
    fun accept(name: String, className: String): OperationClass? {
        val area = name.substringBefore('.')
        if (area !in allowedAreas) return null
        return OperationClass.of(className)
    }
}

data class PierInfo(
    val id: String,
    val name: String,
    val node: String,
    val observatory: String? = null,
    val access: Access = Access.Control,
    val reachable: Boolean = true,
    val reason: String? = null,
) {
    enum class Access(val wire: String) {
        Control("control"), View("view"), None("none");

        companion object {
            fun of(wire: String) = entries.firstOrNull { it.wire == wire }
        }
    }
}

data class Capabilities(
    val areas: List<String> = emptyList(),
    val skyStreamAvailable: Boolean? = null,
    val skyStreamReason: String? = null,
    val skyStreamRate: Double? = null,
)

data class HandshakeOutcome(
    val verdict: Verdict,
    val message: String,
    val allowed: Set<String>,
    val masterProtocol: Int,
    val galileoVersion: String,
) {
    enum class Verdict { Ok, Restricted, Refused }
}

data class Identity(val peerId: String, val name: String, val permissions: Set<String>)

enum class Role(val wire: String) {
    Viewer("Viewer"), Operator("Operator");

    companion object {
        /**
         * The peer's real grant comes from `whoami`. The handshake's `allowed` only says what the exchange permits and
         * always contains `config.*` from a current Master, so it cannot be the test for a configuration grant.
         */
        fun from(permissions: Set<String>, allowed: Set<String>): Role? {
            val effective = permissions intersect allowed
            return when {
                "control" in effective -> Operator
                "view" in effective -> Viewer
                else -> null
            }
        }

        /** MAPP-CFG-030: the app refuses to run on a grant it must never hold. */
        fun hasConfigurationGrant(permissions: Set<String>) = permissions.any { it.startsWith("config.") }
    }
}

/** The Master's typed answer to an operation. */
sealed interface OpResult {
    data class Ok(val value: JsonElement?, val correlationId: String?) : OpResult
    data class Refused(val reason: String, val kind: String?, val holder: String?, val canTake: Boolean) : OpResult
    data class Invalid(val path: String?, val reason: String) : OpResult
    data class Unavailable(val reason: String) : OpResult
    data class Busy(val reason: String) : OpResult
    data class Unreachable(val reason: String) : OpResult
    data class ConfirmationRequired(val token: String, val reason: String?) : OpResult

    /** The request may or may not have reached the Master. Never treated as done, never retried on its own. */
    data object ConnectionLost : OpResult
}

data class LeaseAnswer(val heldByMe: Boolean, val holder: String?)

data class LeaseRefusal(val reason: String, val holder: String? = null, val takeOverToken: String? = null)
