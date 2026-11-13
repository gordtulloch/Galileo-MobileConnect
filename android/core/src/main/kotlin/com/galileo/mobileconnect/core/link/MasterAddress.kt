package com.galileo.mobileconnect.core.link

/**
 * Where a Master listens. Manual entry is validated here before any request is made (MAPP-CON-020).
 * Mirrors the iOS `MasterAddress`; both are held to the same cases.
 */
data class MasterAddress(val host: String, val port: Int = DEFAULT_PORT) {

    /** `https://host:port`, bracketing an IPv6 host. */
    val baseUrl: String
        get() = "https://${if (':' in host) "[$host]" else host}:$port"

    sealed interface Problem {
        val message: String

        data object Empty : Problem {
            override val message = "Enter the address of the computer running Galileo."
        }

        data object NotEncrypted : Problem {
            override val message = "Galileo only connects over an encrypted link. Use https, or leave the prefix off."
        }

        data class UnsupportedScheme(val scheme: String) : Problem {
            override val message = "“$scheme” is not a kind of address Galileo can use."
        }

        data object BadHost : Problem {
            override val message = "That doesn't look like a computer name or IP address."
        }

        data object BadPort : Problem {
            override val message = "The port must be a number from 1 to 65535."
        }
    }

    class ProblemException(val problem: Problem) : Exception(problem.message)

    companion object {
        const val DEFAULT_PORT = 8765

        /** The address, or a failure carrying a [ProblemException]. */
        fun parse(text: String): Result<MasterAddress> {
            val (address, problem) = check(text)
            return if (address != null) Result.success(address) else Result.failure(ProblemException(problem!!))
        }

        /** The same parse, with the problem as a value rather than an exception: exactly one side is non-null. */
        fun check(text: String): Pair<MasterAddress?, Problem?> {
            var rest = text.trim()
            if (rest.isEmpty()) return null to Problem.Empty
            val schemeEnd = rest.indexOf("://")
            if (schemeEnd >= 0) {
                val scheme = rest.substring(0, schemeEnd).lowercase()
                if (scheme == "http") return null to Problem.NotEncrypted
                if (scheme != "https") return null to Problem.UnsupportedScheme(scheme)
                rest = rest.substring(schemeEnd + 3)
            }
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                if (!rest.substring(slash).all { it == '/' }) return null to Problem.BadHost
                rest = rest.substring(0, slash)
            }

            var host = rest
            var portText: String? = null
            if (rest.startsWith("[")) { // [IPv6] or [IPv6]:port
                val close = rest.indexOf(']')
                if (close < 0) return null to Problem.BadHost
                host = rest.substring(1, close)
                val after = rest.substring(close + 1)
                if (after.isNotEmpty()) {
                    if (!after.startsWith(":")) return null to Problem.BadHost
                    portText = after.substring(1)
                }
                if (':' !in host || !host.all(::isIpv6Char)) return null to Problem.BadHost
            } else if (rest.count { it == ':' } == 1) {
                val colon = rest.indexOf(':')
                host = rest.substring(0, colon)
                portText = rest.substring(colon + 1)
                if (!isHostName(host)) return null to Problem.BadHost
            } else if (':' in rest) { // a bare IPv6 address has no room for a port
                if (!rest.all(::isIpv6Char)) return null to Problem.BadHost
            } else if (!isHostName(host)) {
                return null to Problem.BadHost
            }

            var port = DEFAULT_PORT
            if (portText != null) {
                val value = portText.takeIf { s -> s.isNotEmpty() && s.all { it in '0'..'9' } }?.toIntOrNull()
                if (value == null || value !in 1..65535) return null to Problem.BadPort
                port = value
            }
            return MasterAddress(host, port) to null
        }

        private fun isIpv6Char(c: Char) = c == ':' || c == '.' || c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        private fun isHostName(text: String): Boolean {
            if (text.isEmpty() || text.length > 253) return false
            return text.split('.').all { label ->
                label.isNotEmpty() && label.length <= 63 && !label.startsWith("-") && !label.endsWith("-") &&
                    label.all { it.code < 128 && (it.isLetterOrDigit() || it == '-') }
            }
        }
    }
}
