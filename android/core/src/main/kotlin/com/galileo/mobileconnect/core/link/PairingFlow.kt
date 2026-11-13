package com.galileo.mobileconnect.core.link

import kotlinx.coroutines.CancellationException

/** Pairing as the user sees it (MAPP-CON-030). Nothing else in the app is reachable before [Paired]. */
sealed interface PairingStatus {
    val isFinished: Boolean get() = this !is Idle && this !is Waiting

    data object Idle : PairingStatus
    data class Waiting(val code: String, val expiresIn: Double, val fingerprint: String) : PairingStatus
    data class Paired(val credentials: MasterCredentials) : PairingStatus
    data object Refused : PairingStatus
    data object TimedOut : PairingStatus
    data class Failed(val message: String) : PairingStatus
}

/**
 * S-LINK pairing: request, show the code, poll, take the token once, store it. The app displays the code; it never
 * asks for one. Mirrors the iOS `PairingFlow`; the shared vectors hold the two to the same behaviour.
 *
 * [presentedFingerprint] reports the certificate seen on this connection; during pairing it must equal the one the
 * Master says it has, or pairing ends and nothing is stored.
 */
class PairingFlow(
    private val api: MasterApi,
    private val store: SecureStore,
    private val address: MasterAddress,
    private val masterName: String,
    private val clock: LinkClock = SystemClock,
    private val pollIntervalSeconds: Double = 2.0,
    private val presentedFingerprint: () -> String? = { null },
) {
    private var requestId: String? = null
    private var fingerprint: String? = null

    @Volatile
    var status: PairingStatus = PairingStatus.Idle
        private set

    suspend fun start(): PairingStatus {
        status = try {
            val started = api.requestPairing()
            val presented = presentedFingerprint()
            if (presented != null && !presented.equals(started.fingerprint, ignoreCase = true)) {
                PairingStatus.Failed(LinkError.IdentityChanged.message)
            } else {
                requestId = started.requestId
                fingerprint = started.fingerprint
                PairingStatus.Waiting(started.code, started.expiresIn, started.fingerprint)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkError) {
            PairingStatus.Failed(e.message)
        }
        return status
    }

    suspend fun poll(): PairingStatus {
        val id = requestId
        val pin = fingerprint
        if (id == null || pin == null || status !is PairingStatus.Waiting) return status
        try {
            val (state, token) = api.pollPairing(id)
            when (state) {
                "approved" -> status = if (token == null) {
                    PairingStatus.Failed("The Master approved the request but sent no token. Try again.")
                } else {
                    val credentials = MasterCredentials(masterName, address, token, pin)
                    try {
                        store.save(credentials)
                        api.holder.current = token
                        PairingStatus.Paired(credentials)
                    } catch (e: Exception) {
                        PairingStatus.Failed("This device could not store the pairing securely.")
                    }
                }
                "rejected" -> status = PairingStatus.Refused
                "expired" -> status = PairingStatus.TimedOut
                "collected" -> status =
                    PairingStatus.Failed("This request was already answered and its token is gone. Try again.")
                else -> Unit // pending
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkError.Unreachable) {
            // A dropped poll is not a refusal: keep waiting, and let the request's own expiry end it.
        } catch (e: LinkError) {
            status = PairingStatus.Failed(e.message)
        }
        return status
    }

    /** Polls until the request is answered or lapses. Cancel the coroutine to stop waiting. */
    suspend fun waitForAnswer(): PairingStatus {
        while (status is PairingStatus.Waiting) {
            clock.sleep(pollIntervalSeconds)
            poll()
        }
        return status
    }
}
