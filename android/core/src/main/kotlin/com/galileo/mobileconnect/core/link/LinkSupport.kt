package com.galileo.mobileconnect.core.link

import java.time.Instant

/** Anything the core can say went wrong with the link. [message] is written for a person. */
sealed class LinkError(override val message: String) : Exception(message) {
    /** 401: the pairing was revoked or has expired. */
    object Unpaired : LinkError("This device is no longer paired with the Master. Pair again to reconnect.")

    /** The Master presented a different certificate from the pinned one. */
    object IdentityChanged : LinkError(
        "The Master's identity has changed: it presented a different certificate from the one this device " +
            "trusted. If you did not replace it, do not continue.",
    )

    class Unreachable(why: String) : LinkError("The Master can't be reached. $why")

    class Unexpected(why: String) : LinkError(why)
}

/** Who is asking: sent in the User-Agent and the handshake so the Master can record who did what (MAPP-CTL-080). */
data class ClientIdentity(
    val deviceName: String,
    val appVersion: String,
    /** `android` (or `quest`). */
    val platform: String,
    /** `tablet`, `phone` or `quest`. */
    val clientClass: String,
) {
    val userAgent: String get() = "GalileoMobileConnect/$appVersion ($platform)"
}

/** The Connect Link protocol this client speaks (`PROTOCOL_VERSION` in `galileo/connect_link/server.py`). */
object LinkProtocol {
    const val VERSION = 1
}

/**
 * The core's only source of time, so tests can move it (docs/SDD.md: "clients have no other source of time in the
 * core"). [sleep] must stop promptly when the calling coroutine is cancelled.
 */
interface LinkClock {
    fun now(): Instant
    suspend fun sleep(seconds: Double)
}

object SystemClock : LinkClock {
    override fun now(): Instant = Instant.now()
    override suspend fun sleep(seconds: Double) = kotlinx.coroutines.delay((seconds * 1000).toLong())
}
