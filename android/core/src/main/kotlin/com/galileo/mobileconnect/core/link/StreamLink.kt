package com.galileo.mobileconnect.core.link

import com.galileo.mobileconnect.core.state.StreamMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URLEncoder
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Reconnect timing for the event stream (S-LINK, MAPP-CON-070): exponential back-off with jitter, capped. */
class Backoff(
    private val initial: Double = 1.0,
    private val maximum: Double = 30.0,
    /** Failed attempts before `Reconnecting` is reported as `Unreachable`. */
    val unreachableAfter: Int = 4,
    private val jitter: () -> Double = { Random.nextDouble(0.8, 1.2) },
) {
    fun delaySeconds(attempt: Int): Double = min(maximum, initial * 2.0.pow(maxOf(0, attempt))) * jitter()
}

object StreamUrl {
    /**
     * `wss://host:port/connect/v1/stream`, with the core's resume parameters `?epoch=...&last=...` when both are
     * known (docs/SDD.md 4.2 items 1 and 2).
     */
    fun of(address: MasterAddress, epoch: String?, last: Int?): String {
        val host = if (':' in address.host) "[${address.host}]" else address.host
        val base = "wss://$host:${address.port}/connect/v1/stream"
        if (epoch == null || last == null) return base
        return "$base?epoch=${URLEncoder.encode(epoch, "UTF-8")}&last=$last"
    }
}

/** Opens the event stream. [WebSocketStreamTransport] is the real one; the mock Master is the other. */
interface StreamTransport {
    /**
     * Frames as they arrive, as text. The flow completes, with or without an error, when the connection does. It
     * throws [LinkError.Unpaired] for a 401 and [LinkError.IdentityChanged] for a refused certificate.
     */
    fun connect(epoch: String?, last: Int?): Flow<String>
}

enum class ConnectionState {
    Connecting,
    Connected,

    /** Lost, and trying again. */
    Reconnecting,

    /** Still trying, but it has been failing for a while: say so. */
    Unreachable,
}

sealed interface LinkEvent {
    data class Connection(val state: ConnectionState) : LinkEvent
    data class Message(val message: StreamMessage) : LinkEvent

    /** The stream dropped; whatever was in flight is now of unknown outcome. */
    data object Dropped : LinkEvent

    /** Terminal: the loop has stopped. */
    data class Stopped(val error: LinkError) : LinkEvent
}

/**
 * Thrown by a `prepare` step to end the loop without retrying: a refused handshake or a forbidden grant, which no
 * reconnect can fix. The session decides what to tell the user.
 */
class StartStop(val reason: Any) : Exception()

/**
 * The reconnect loop (S-LINK, MAPP-CON-070): exponential back-off with jitter, an immediate attempt when the app
 * returns to the foreground, and the last `(epoch, seq)` passed on every attempt. The client does not poll.
 */
class StreamLink(
    private val transport: StreamTransport,
    private val scope: CoroutineScope,
    private val clock: LinkClock = SystemClock,
    private val backoff: Backoff = Backoff(),
    private val prepare: suspend () -> Unit = {},
    private val onStartStop: suspend (StartStop) -> Unit = {},
    private val onEvent: suspend (LinkEvent) -> Unit,
) {
    private var epoch: String? = null
    private var last: Int? = null

    @Volatile private var loop: Job? = null

    @Volatile private var wake: Job? = null

    fun start() {
        if (loop != null) return
        loop = scope.launch { run() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        wake?.cancel()
    }

    /** The app returned to the foreground, or the network came back: don't wait out the back-off. */
    fun reconnectNow() {
        wake?.cancel()
    }

    private suspend fun run() {
        var failures = 0
        var everConnected = false
        while (scope.isActive) {
            val unreachable = failures >= backoff.unreachableAfter
            onEvent(LinkEvent.Connection(if (!everConnected) ConnectionState.Connecting else if (unreachable) ConnectionState.Unreachable else ConnectionState.Reconnecting))
            try {
                prepare()
                var announced = false
                transport.connect(epoch, last).collect { text ->
                    val message = StreamMessage.parse(text) ?: return@collect
                    if (!announced) {
                        announced = true
                        everConnected = true
                        failures = 0
                        onEvent(LinkEvent.Connection(ConnectionState.Connected))
                    }
                    track(message)
                    onEvent(LinkEvent.Message(message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkError.Unpaired) {
                onEvent(LinkEvent.Stopped(e))
                return
            } catch (e: LinkError.IdentityChanged) {
                onEvent(LinkEvent.Stopped(e))
                return
            } catch (e: StartStop) {
                onStartStop(e)
                return
            } catch (e: Exception) {
                // Any other failure is an ordinary drop, slow-client disconnects included.
            }
            onEvent(LinkEvent.Dropped)
            everConnected = true
            onEvent(LinkEvent.Connection(if (failures >= backoff.unreachableAfter) ConnectionState.Unreachable else ConnectionState.Reconnecting))
            val delay = backoff.delaySeconds(failures)
            failures += 1
            val sleeper = scope.launch { clock.sleep(delay) }
            wake = sleeper
            sleeper.join()
        }
    }

    private fun track(message: StreamMessage) {
        when (message) {
            is StreamMessage.Hello -> if (epoch != message.epoch || last == null) {
                epoch = message.epoch
                last = message.seq
            }
            is StreamMessage.Resync -> {
                epoch = message.epoch
                last = message.seq
            }
            is StreamMessage.Frame -> last = maxOf(last ?: 0, message.seq)
        }
    }
}
