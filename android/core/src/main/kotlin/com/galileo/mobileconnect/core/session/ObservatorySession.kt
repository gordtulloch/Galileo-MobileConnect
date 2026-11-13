package com.galileo.mobileconnect.core.session

import com.galileo.mobileconnect.core.api.Capabilities
import com.galileo.mobileconnect.core.api.HandshakeOutcome
import com.galileo.mobileconnect.core.api.LeaseRefusal
import com.galileo.mobileconnect.core.api.OpResult
import com.galileo.mobileconnect.core.api.OperationClass
import com.galileo.mobileconnect.core.api.OperationInfo
import com.galileo.mobileconnect.core.api.PierInfo
import com.galileo.mobileconnect.core.api.Role
import com.galileo.mobileconnect.core.control.ConfirmationRequest
import com.galileo.mobileconnect.core.control.ControlPolicy
import com.galileo.mobileconnect.core.control.HoldSession
import com.galileo.mobileconnect.core.control.HoldState
import com.galileo.mobileconnect.core.control.HoldStop
import com.galileo.mobileconnect.core.control.OperationLedger
import com.galileo.mobileconnect.core.control.RequestAnswer
import com.galileo.mobileconnect.core.link.Backoff
import com.galileo.mobileconnect.core.link.ConnectionState
import com.galileo.mobileconnect.core.link.LeaseOutcome
import com.galileo.mobileconnect.core.link.LinkClock
import com.galileo.mobileconnect.core.link.LinkError
import com.galileo.mobileconnect.core.link.LinkEvent
import com.galileo.mobileconnect.core.link.LinkProtocol
import com.galileo.mobileconnect.core.link.MasterApi
import com.galileo.mobileconnect.core.link.StartStop
import com.galileo.mobileconnect.core.link.StreamLink
import com.galileo.mobileconnect.core.link.StreamTransport
import com.galileo.mobileconnect.core.link.SystemClock
import com.galileo.mobileconnect.core.link.PreviewSource
import com.galileo.mobileconnect.core.link.preview
import com.galileo.mobileconnect.core.link.tile
import com.galileo.mobileconnect.core.link.checkPosition
import com.galileo.mobileconnect.core.link.read
import com.galileo.mobileconnect.core.state.AlertEvent
import com.galileo.mobileconnect.core.state.LivePhase
import com.galileo.mobileconnect.core.state.LiveState
import com.galileo.mobileconnect.core.state.PierState
import com.galileo.mobileconnect.core.state.StreamEvent
import com.galileo.mobileconnect.core.state.StreamMessage
import com.galileo.mobileconnect.core.state.ValueView
import com.galileo.mobileconnect.core.state.view
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.UUID

/** Why a session ended and can't simply be retried. */
sealed interface SessionStop {
    data class Unpaired(val message: String) : SessionStop
    data object IdentityChanged : SessionStop

    /** A refused handshake. [updateApp] says which side is behind, when that is known (MAPP-PLT-090). */
    data class Refused(val message: String, val updateApp: Boolean?) : SessionStop

    /** The Master granted a permission this app must never hold (MAPP-CFG-030). */
    data object ConfigurationGrant : SessionStop
}

/** Everything a screen may show, as one immutable value. */
data class SessionSnapshot(
    val connection: ConnectionState = ConnectionState.Connecting,
    val stop: SessionStop? = null,
    val role: Role? = null,
    val peerId: String? = null,
    val galileoVersion: String? = null,
    val capabilities: Capabilities? = null,
    val piers: List<PierInfo> = emptyList(),
    val catalogues: Map<String, List<OperationInfo>> = emptyMap(),
    val live: LiveState = LiveState(),
    val ledger: OperationLedger = OperationLedger(),
    val hold: HoldState = HoldState.Idle,
    /** A problem worth a line on screen, for example a failed start-up step. */
    val notice: String? = null,
) {
    val isConnected: Boolean get() = connection == ConnectionState.Connected

    fun operation(name: String, pier: String): OperationInfo? = catalogues[pier]?.firstOrNull { it.name == name }

    sealed interface Lease {
        data object Free : Lease
        data object Mine : Lease
        data class Other(val holder: String) : Lease
    }

    fun lease(pier: String): Lease {
        val holder = live.piers[pier]?.leaseHolder
        if (holder.isNullOrEmpty()) return Lease.Free
        return if (holder == peerId) Lease.Mine else Lease.Other(live.piers[pier]?.leaseHolderName ?: holder)
    }

    /** Access as last stated by the stream, falling back on the Pier list. */
    fun access(pier: String): PierInfo.Access {
        live.piers[pier]?.access?.let { text -> PierInfo.Access.of(text)?.let { return it } }
        return piers.firstOrNull { it.id == pier }?.access ?: PierInfo.Access.None
    }

    fun unavailableReason(pier: String): String? {
        val live = live.piers[pier]
        if (live?.reachable == false) return live.reason ?: "This Pier can't be reached."
        piers.firstOrNull { it.id == pier }?.let { if (!it.reachable) return it.reason ?: "This Pier can't be reached." }
        return null
    }

    fun value(key: String, pier: String, now: Instant): ValueView? = live.view(pier, key, now, isConnected)
}

/**
 * One connection to one Master: S-LINK, S-STATE and S-CTL behind a single surface for the UI. There is no function here
 * that disables the safety monitor, clears an unsafe state, resumes after a safety stop, overrides a limit or changes
 * configuration (MAPP-SAF-040, MAPP-CFG-010).
 */
class ObservatorySession(
    private val api: MasterApi,
    private val transport: StreamTransport,
    private val scope: CoroutineScope,
    private val clock: LinkClock = SystemClock,
    private val backoff: Backoff = Backoff(),
    private val makeId: () -> String = { UUID.randomUUID().toString() },
    private val onUnpaired: () -> Unit = {},
) {
    constructor(
        connection: com.galileo.mobileconnect.core.link.MasterConnection,
        scope: CoroutineScope,
        clock: LinkClock = SystemClock,
        backoff: Backoff = Backoff(),
        makeId: () -> String = { UUID.randomUUID().toString() },
        onUnpaired: () -> Unit = {},
    ) : this(connection.api, connection.stream, scope, clock, backoff, makeId, onUnpaired)

    private val state = MutableStateFlow(SessionSnapshot())
    private val lock = Mutex()

    @Volatile private var link: StreamLink? = null

    @Volatile private var hold: HoldSession? = null

    @Volatile private var retrying: Job? = null

    @Volatile private var longRunning: Set<String> = emptySet()

    val snapshot: StateFlow<SessionSnapshot> = state.asStateFlow()

    // -- lifecycle ------------------------------------------------------------------------------------------------

    /** Opens the stream, loading what the Master offers on every (re)connect, and keeps it open (S-LINK). */
    fun start() = startLink(prepare = true)

    internal fun startLink(prepare: Boolean = false) {
        if (link != null) return
        val new = StreamLink(
            transport = transport,
            scope = scope,
            clock = clock,
            backoff = backoff,
            prepare = { if (prepare) prepareSession() },
            onStartStop = { handleStartStop(it) },
            onEvent = { handle(it) },
        )
        link = new
        new.start()
    }

    suspend fun stop() {
        retrying?.cancel()
        hold?.stop(HoldStop.Released)
        link?.stop()
        link = null
    }

    /** The scene left the foreground: stop held motion at once (MAPP-DEV-030). */
    suspend fun enteredBackground() {
        hold?.stop(HoldStop.Background)
    }

    /** The app returned to the foreground, or the network came back: reconnect now rather than wait out the back-off. */
    fun enteredForeground() {
        link?.reconnectNow()
    }

    /** Version handshake, identity, and what the Master offers, on every (re)connect (S-LINK). */
    private suspend fun prepareSession() {
        try {
            loadFromMaster()
        } catch (e: LinkError.Unexpected) {
            // For example a Master without the MobileConnect plugin. Retried, but not in silence.
            state.update { it.copy(notice = e.message) }
            throw e
        }
    }

    private suspend fun loadFromMaster() {
        val outcome = api.handshake()
        if (outcome.verdict == HandshakeOutcome.Verdict.Refused) {
            val updateApp = if (outcome.masterProtocol == LinkProtocol.VERSION) null else outcome.masterProtocol > LinkProtocol.VERSION
            throw StartStop(SessionStop.Refused(outcome.message, updateApp))
        }
        val identity = api.whoAmI()
        if (Role.hasConfigurationGrant(identity.permissions)) throw StartStop(SessionStop.ConfigurationGrant)
        val role = Role.from(identity.permissions, outcome.allowed)
        val (piers, piersReason) = api.piers()
        val catalogues = state.value.catalogues.toMutableMap()
        for (pier in piers) if (pier.id !in catalogues) catalogues[pier.id] = api.catalogue(pier.id)
        longRunning = catalogues.values.flatten().filter { it.longRunning }.map { it.name }.toSet()
        val capabilities = api.capabilities()
        val alerts = api.alerts()

        state.update {
            it.copy(
                role = role, peerId = identity.peerId, galileoVersion = outcome.galileoVersion, piers = piers,
                catalogues = catalogues, capabilities = capabilities, notice = piersReason,
                live = if (it.live.alerts.isEmpty()) it.live.copy(alerts = alerts) else it.live,
            )
        }
        if (piersReason != null) retryUntilPiersAppear()
    }

    /**
     * The Master was up but Galileo's control surface was not (Galileo is still starting, or was restarted while the phone
     * stayed connected). The stream does not drop for that, so nothing would reconnect and ask again: the Piers and the
     * red notice would stay as they were. Ask again until there are Piers, then ask for the live state.
     */
    private fun retryUntilPiersAppear() {
        if (retrying != null) return
        retrying = scope.launch {
            while (isActive) {
                clock.sleep(RETRY_SECONDS)
                if (retryPiersOnce()) return@launch
            }
        }
    }

    /** True when there is nothing more to retry: the Piers are there, or the session is over. */
    private suspend fun retryPiersOnce(): Boolean {
        if (link == null) {
            retrying = null
            return true
        }
        try {
            loadFromMaster()
            if (state.value.notice == null) {
                runCatching { api.requestSnapshot() }
                retrying = null
                return true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is LinkError.Unpaired || e is StartStop) {
                retrying = null
                return true
            }
        }
        return false
    }

    private suspend fun handleStartStop(refusal: StartStop) {
        (refusal.reason as? SessionStop)?.let { lock.withLock { finish(it) } }
    }

    private suspend fun handle(event: LinkEvent) = lock.withLock {
        when (event) {
            is LinkEvent.Connection -> state.update { it.copy(connection = event.state) }
            is LinkEvent.Message -> {
                val before = state.value
                val mine = before.live.piers.keys.filter { before.lease(it) == SessionSnapshot.Lease.Mine }
                val message = event.message
                state.update { current ->
                    var next = current.copy(live = LiveState.reduce(current.live, message))
                    val operation = ((message as? StreamMessage.Frame)?.event as? StreamEvent.Operation)?.event
                    if (operation != null) next = next.copy(ledger = next.ledger.apply(operation))
                    next
                }
                askForSnapshotIfNeeded(message)
                val now = state.value
                if (mine.any { now.lease(it) != SessionSnapshot.Lease.Mine }) hold?.stop(HoldStop.LeaseLost)
            }
            LinkEvent.Dropped -> {
                state.update { it.copy(ledger = it.ledger.connectionLost()) }
                hold?.stop(HoldStop.ConnectionLost)
            }
            is LinkEvent.Stopped -> finish(
                if (event.error is LinkError.IdentityChanged) SessionStop.IdentityChanged
                else SessionStop.Unpaired(event.error.message),
            )
        }
    }

    /** `hello` with no state yet, or `resync`: the snapshot comes on the stream, but only when asked for. */
    private fun askForSnapshotIfNeeded(message: StreamMessage) {
        when (message) {
            is StreamMessage.Hello -> if (state.value.live.phase == LivePhase.Live) return
            is StreamMessage.Resync -> Unit
            is StreamMessage.Frame -> return
        }
        scope.launch {
            try {
                api.requestSnapshot()
            } catch (e: LinkError.Unpaired) {
                lock.withLock { finish(SessionStop.Unpaired(e.message)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The stream going with it is what happens next, and reconnecting asks again.
            }
        }
    }

    private fun finish(stop: SessionStop) {
        state.update { it.copy(stop = stop, ledger = it.ledger.connectionLost()) }
        if (stop is SessionStop.Unpaired) onUnpaired()
        link?.stop()
    }

    // -- operations (S-CTL) ---------------------------------------------------------------------------------------

    /**
     * Asks for an operation. Answers with a question for the user, or with the record of what was sent. Declining a
     * confirmation is simply not calling [confirm]: nothing is sent.
     */
    suspend fun request(pier: String, operation: String, args: Map<String, JsonElement> = emptyMap()): RequestAnswer {
        val info = state.value.operation(operation, pier)
        refusal(info)?.let { return RequestAnswer.NotAllowed(it) }
        info?.validate(args)?.let { return RequestAnswer.Invalid(it.path, it.reason) }
        // A `confirm` operation gets its wording and token from the Master, so that goes first.
        if (operation in ControlPolicy.alwaysConfirm && info?.operationClass != OperationClass.Confirm) {
            return RequestAnswer.Confirm(makeConfirmation(pier, operation, args, info, consequence = null, token = null))
        }
        return send(pier, operation, args, token = null, info = info)
    }

    /** The user accepted a confirmation. */
    suspend fun confirm(request: ConfirmationRequest): RequestAnswer {
        val info = state.value.operation(request.operation, request.pier)
        refusal(info)?.let { return RequestAnswer.NotAllowed(it) }
        return send(request.pier, request.operation, request.args, request.token, info)
    }

    /**
     * The emergency actions: no lease, no second round trip, confirmed by the deliberate gesture (MAPP-EMG-010,
     * MAPP-EMG-020). Viewers are refused here and by the Master.
     */
    suspend fun emergency(pier: String, operation: String): RequestAnswer {
        if (state.value.role != Role.Operator) return RequestAnswer.NotAllowed("Emergency actions need an Operator.")
        return send(pier, operation, emptyMap(), null, state.value.operation(operation, pier), emergency = true)
    }

    private fun refusal(info: OperationInfo?): String? {
        if (state.value.role != Role.Operator && info?.operationClass != OperationClass.Read) {
            return "This device is a Viewer: it can watch but not operate."
        }
        if (info != null && !info.available) return info.unavailableBecause ?: "The Master says this isn't available now."
        return null
    }

    private fun makeConfirmation(
        pier: String,
        name: String,
        args: Map<String, JsonElement>,
        info: OperationInfo?,
        consequence: String?,
        token: String?,
    ) = ConfirmationRequest(
        pier = pier, operation = name, args = args, title = ControlPolicy.title(info, name),
        details = ControlPolicy.details(args, info), consequence = consequence, token = token,
    )

    private suspend fun send(
        pier: String,
        name: String,
        args: Map<String, JsonElement>,
        token: String?,
        info: OperationInfo?,
        emergency: Boolean = false,
    ): RequestAnswer {
        val id = makeId()
        state.update { it.copy(ledger = it.ledger.sent(id, pier, name, clock.now())) }
        val result = api.invoke(pier, name, args, idempotencyKey = id, confirmToken = token, emergencyConfirmed = emergency)
        if (result is OpResult.Refused && result.kind == "unpaired") {
            lock.withLock { finish(SessionStop.Unpaired(LinkError.Unpaired.message)) }
        }
        if (result is OpResult.ConfirmationRequired) {
            state.update { it.copy(ledger = it.ledger.forget(id)) }
            return RequestAnswer.Confirm(makeConfirmation(pier, name, args, info, result.reason, result.token))
        }
        state.update { it.copy(ledger = it.ledger.answered(id, result, name in longRunning)) }
        return state.value.ledger.record(id)?.let { RequestAnswer.Sent(it) }
            ?: RequestAnswer.NotAllowed("The operation's record was lost.")
    }

    // -- lease (S-CTL) --------------------------------------------------------------------------------------------

    /** Null when the lease is held now; otherwise why not, and the token to take it over when the Master offers one. */
    suspend fun takeLease(pier: String, takeOverToken: String? = null): LeaseRefusal? =
        when (val outcome = api.acquireLease(pier, takeOverToken)) {
            is LeaseOutcome.Granted -> {
                val peer = state.value.peerId
                if (outcome.answer.heldByMe && peer != null) {
                    // The Master's own answer: the stream's `lease` event says the same thing.
                    setLeaseHolder(pier, peer)
                }
                null
            }
            is LeaseOutcome.Refused -> outcome.refusal
        }

    suspend fun releaseLease(pier: String) {
        hold?.stop(HoldStop.LeaseLost)
        if (api.releaseLease(pier)) setLeaseHolder(pier, null)
    }

    private fun setLeaseHolder(pier: String, holder: String?) = state.update {
        val existing = it.live.piers[pier] ?: if (holder == null) return@update it else PierState()
        it.copy(live = it.live.copy(piers = it.live.piers + (pier to existing.copy(leaseHolder = holder))))
    }

    // -- held motion ----------------------------------------------------------------------------------------------

    /** Press: begins a hold if the catalogue offers one for [family] (`focus`, `mount`). */
    suspend fun startHold(pier: String, family: String, args: Map<String, JsonElement>) {
        val current = state.value
        if (current.role != Role.Operator || current.operation("$family.hold_start", pier) == null) return
        hold?.stop(HoldStop.Released)
        val session = HoldSession(
            family = family, scope = scope, clock = clock,
            invoke = { operation, arguments -> api.invoke(pier, operation, arguments, idempotencyKey = makeId()) },
            onChange = { next -> state.update { it.copy(hold = next) } },
        )
        hold = session
        session.start(args)
    }

    /** Release. */
    suspend fun stopHold() {
        hold?.stop(HoldStop.Released)
    }

    // -- sky and reads --------------------------------------------------------------------------------------------

    /** The sky's own reads go straight to the API: they are read-only and shouldn't sit in the operations ledger. */
    val masterApi: MasterApi get() = api

    /** One read-class operation, for something a screen wants to know. Not in the ledger: nothing is being done. */
    suspend fun read(pier: String, operation: String, args: Map<String, JsonElement> = emptyMap()): JsonElement? =
        api.read(pier, operation, args, makeId())

    /**
     * The go-to question (MAPP-SKY-040): the target, its altitude now and the Master's horizon check. Nothing is sent
     * unless the user accepts, and the Master still enforces its limits when it is.
     */
    suspend fun goToQuestion(pier: String, name: String, raDeg: Double, decDeg: Double): RequestAnswer {
        val operation = "mount.slew"
        val info = state.value.operation(operation, pier)
        refusal(info)?.let { return RequestAnswer.NotAllowed(it) }
        val args = mapOf("ra_deg" to JsonPrimitive(raDeg), "dec_deg" to JsonPrimitive(decDeg))
        info?.validate(args)?.let { return RequestAnswer.Invalid(it.path, it.reason) }
        val check = api.checkPosition(pier, raDeg, decDeg)
        val details = mutableListOf("RA %.3f°, Dec %.3f°".format(raDeg, decDeg))
        var consequence: String
        if (check != null) {
            details += "Altitude now: %.1f°".format(check.altitude)
            consequence = if (check.aboveHorizon) "Above this Observatory's horizon." else check.reason ?: "Below this Observatory's horizon."
        } else {
            consequence = "The Master couldn't check this position against the horizon. It still enforces its limits."
        }
        consequence += " The mount will slew now."
        return RequestAnswer.Confirm(
            ConfirmationRequest(
                pier = pier, operation = operation, args = args, title = "Slew to $name", details = details,
                consequence = consequence, token = null,
            ),
        )
    }

    // -- images (S-IMG) -------------------------------------------------------------------------------------------

    suspend fun preview(pier: String, width: Int, stretch: Int, quality: Int = 80, source: PreviewSource = PreviewSource.Current) =
        api.preview(pier, width, quality, stretch, source)

    suspend fun tile(pier: String, level: Int, x: Int, y: Int, stretch: Int, source: PreviewSource = PreviewSource.Current) =
        api.tile(pier, level, x, y, stretch, source)

    // -- test seam ------------------------------------------------------------------------------------------------

    internal fun installForTesting(role: Role, peerId: String, catalogues: Map<String, List<OperationInfo>>) {
        state.update { it.copy(role = role, peerId = peerId, catalogues = catalogues) }
        longRunning = catalogues.values.flatten().filter { it.longRunning }.map { it.name }.toSet()
    }

    companion object {
        private const val RETRY_SECONDS = 5.0
    }
}
