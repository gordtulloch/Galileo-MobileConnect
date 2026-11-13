package com.galileo.mobileconnect.core.vectors

import com.galileo.mobileconnect.api.infrastructure.Serializer
import com.galileo.mobileconnect.api.model.Catalogue
import com.galileo.mobileconnect.core.api.OperationInfo
import com.galileo.mobileconnect.core.api.Role
import com.galileo.mobileconnect.core.control.HoldState
import com.galileo.mobileconnect.core.control.HoldStop
import com.galileo.mobileconnect.core.control.OperationState
import com.galileo.mobileconnect.core.control.RequestAnswer
import com.galileo.mobileconnect.core.link.MasterCredentials
import com.galileo.mobileconnect.core.link.operationInfo
import com.galileo.mobileconnect.core.session.SessionStop
import com.galileo.mobileconnect.core.link.ClientIdentity
import com.galileo.mobileconnect.core.link.InMemorySecureStore
import com.galileo.mobileconnect.core.link.MasterAddress
import com.galileo.mobileconnect.core.link.MasterApi
import com.galileo.mobileconnect.core.link.DropNullFields
import com.galileo.mobileconnect.core.link.IdentityInterceptor
import com.galileo.mobileconnect.core.link.PairingFlow
import com.galileo.mobileconnect.core.link.PairingStatus
import com.galileo.mobileconnect.core.link.TokenHolder
import com.galileo.mobileconnect.core.link.Backoff
import com.galileo.mobileconnect.core.link.ConnectionState
import com.galileo.mobileconnect.core.session.ObservatorySession
import com.galileo.mobileconnect.core.state.LivePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import java.time.Instant

/**
 * Replays one file from `api/vectors/` through the real client code (S-TEST, MAPP-PLT-040): the same generated client
 * and the same [MasterApi] and [PairingFlow] the app uses, against a Master whose every answer comes from the vector.
 *
 * What a vector may use is listed in [SUPPORTED_ACTIONS] and [SUPPORTED_STATE_KEYS]; the rest of the shared suite
 * still waits for the matching Kotlin components (see `VectorTest.pending`).
 */
class VectorRunner(private val vector: JsonObject) {
    private val master = VectorMaster()
    private val clock = TestClock(
        (vector["clock_start"] as? JsonPrimitive)?.contentOrNull?.let { Instant.parse(it) } ?: Instant.ofEpochSecond(1_800_000_000),
    )
    private val store = InMemorySecureStore()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val jobs = mutableListOf<Job>()

    // Setup defaults are fixed by api/vectors/README.md: device name "Vector iPad", platform "ipados".
    private val identity = ClientIdentity("Vector iPad", "0.1.0", "ipados", "tablet")
    private val address = MasterAddress("master.local")
    private val holder = TokenHolder()
    private val api: MasterApi
    private val pairing: PairingFlow
    private val stream = VectorStream()
    private val session: ObservatorySession

    @Volatile
    private var pairingStatus: PairingStatus = PairingStatus.Idle

    /** The latest answer to a request, which `confirm` and the `ConfirmationRequired`/`Invalid`/`NotAllowed` outcomes read. */
    @Volatile
    private var lastAnswer: RequestAnswer? = null

    init {
        val client = OkHttpClient.Builder()
            .addInterceptor(IdentityInterceptor(identity, holder))
            .addInterceptor(DropNullFields)
            .addInterceptor(master) // last: answers the request, never reaching a network
            .build()
        api = MasterApi("https://master.local:8765", client, identity, holder)
        pairing = PairingFlow(api, store, address, "Master", clock)

        val setup = vector["setup"] as? JsonObject
        val credentials = MasterCredentials("Master", address, "tok", "ff")
        if ((setup?.get("token_stored") as? JsonPrimitive)?.booleanOrNull == true) {
            store.save(credentials)
            holder.current = "tok"
        }
        val counter = java.util.concurrent.atomic.AtomicInteger()
        session = ObservatorySession(
            api, stream, scope, clock, Backoff(jitter = { 1.0 }), // the vectors fix the jitter so their delays are exact
            makeId = { "k${counter.incrementAndGet()}" },
            onUnpaired = { store.delete(credentials.id) },
        )
        val role = if ((setup?.get("role") as? JsonPrimitive)?.contentOrNull == "Viewer") Role.Viewer else Role.Operator
        session.installForTesting(role, "me", mapOf("p1" to realCatalogue()))
    }

    private fun realCatalogue(): List<OperationInfo> {
        val text = java.io.File(VectorFiles.directory.parentFile, "scenarios/catalogue.json").readText()
        return Serializer.kotlinxSerializationJson.decodeFromString<Catalogue>(text).operations.mapNotNull(::operationInfo)
    }

    suspend fun run() {
        val name = (vector["name"] as? JsonPrimitive)?.content ?: "?"
        val steps = vector["steps"] as? JsonArray ?: JsonArray(emptyList())
        try {
            steps.forEachIndexed { index, step ->
                val fields = step as JsonObject
                try {
                    perform(fields)
                } catch (e: Throwable) {
                    throw VectorFailure("$name step $index ${(fields["do"] as? JsonPrimitive)?.content}: ${e.message ?: e}")
                }
            }
        } finally {
            session.stop()
            master.failAll()
            stream.drop()
            jobs.forEach { it.cancelAndJoin() }
        }
    }

    private suspend fun perform(step: JsonObject) {
        when (val kind = (step["do"] as? JsonPrimitive)?.content) {
            "act" -> act(step)
            "send" -> expectSend(step)
            "receive" -> master.answer((step["status"] as? JsonPrimitive)?.intOrNull ?: 200, step["body"] ?: JsonObject(emptyMap()))
            "clock" -> {
                // Let the client reach its next sleep first, so the advance is deterministic.
                delay(40)
                clock.advance((step["advance_s"] as? JsonPrimitive)?.doubleOrNull ?: 0.0)
            }
            "stream" -> {
                if (!eventually { stream.open }) throw VectorFailure("no connection to deliver to")
                stream.deliver(step["frame"] ?: JsonNull)
            }
            "drop" -> stream.drop()
            "expect_connect" -> {
                val done = eventually { stream.connects.size >= 2 && stream.open }
                val latest = stream.connects.lastOrNull()
                if (!done || latest == null) throw VectorFailure("no reconnect")
                val epoch = (step["epoch"] as? JsonPrimitive)?.contentOrNull
                val last = (step["last"] as? JsonPrimitive)?.intOrNull
                if (latest.first != epoch || latest.second != last) {
                    throw VectorFailure("reconnected from $latest, expected epoch $epoch last $last")
                }
            }
            "expect" -> expect(step)
            else -> throw VectorFailure("step kind '$kind' is not supported by this runner yet")
        }
    }

    private fun act(step: JsonObject) {
        when (val action = (step["action"] as? JsonPrimitive)?.content) {
            "pair_start" -> spawn { pairingStatus = pairing.start() }
            "pair_poll" -> spawn { pairingStatus = pairing.poll() }
            "handshake" -> spawn { runCatching { api.handshake() } }
            "stream_start" -> session.startLink()
            "invoke" -> {
                val (pier, operation, args) = target(step)
                spawn { lastAnswer = session.request(pier, operation, args) }
            }
            "emergency" -> {
                val (pier, operation, _) = target(step)
                spawn { lastAnswer = session.emergency(pier, operation) }
            }
            "confirm" -> (lastAnswer as? RequestAnswer.Confirm)?.let { question ->
                spawn { lastAnswer = session.confirm(question.request) }
            }
            "decline" -> lastAnswer = null
            "hold_press" -> {
                val (pier, _, args) = target(step)
                val family = (step["family"] as? JsonPrimitive)?.contentOrNull ?: ""
                spawn { session.startHold(pier, family, args) }
            }
            "hold_release" -> spawn { session.stopHold() }
            "background" -> spawn { session.enteredBackground() }
            else -> throw VectorFailure("action '$action' is not supported by this runner yet")
        }
    }

    private data class Target(val pier: String, val operation: String, val args: Map<String, JsonElement>)

    private fun target(step: JsonObject) = Target(
        (step["pier"] as? JsonPrimitive)?.contentOrNull ?: "p1",
        (step["operation"] as? JsonPrimitive)?.contentOrNull ?: "",
        (step["args"] as? JsonObject) ?: emptyMap(),
    )

    private fun spawn(body: suspend () -> Unit) {
        jobs += scope.launch { body() }
    }

    private suspend fun expectSend(step: JsonObject) {
        var seen: SeenRequest? = null
        eventually {
            seen = master.claim()
            seen != null
        }
        val request = seen ?: throw VectorFailure("expected a request: $step")
        (step["method"] as? JsonPrimitive)?.content?.let { if (it != request.method) throw VectorFailure("method ${request.method}, expected $it") }
        (step["path"] as? JsonPrimitive)?.content?.let { if (it != request.path) throw VectorFailure("path ${request.path}, expected $it") }
        (step["auth"] as? JsonPrimitive)?.booleanOrNull?.let { wanted ->
            val has = request.headers.keys.any { it.equals("authorization", ignoreCase = true) }
            if (wanted != has) throw VectorFailure("authorization header present: $has, expected $wanted")
        }
        val agent = request.headers.entries.firstOrNull { it.key.equals("user-agent", ignoreCase = true) }?.value
        if (agent?.startsWith("GalileoMobileConnect/") != true) throw VectorFailure("User-Agent is $agent (MAPP-CTL-080)")
        (step["headers"] as? JsonObject)?.forEach { (name, value) ->
            val got = request.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            if (got != (value as? JsonPrimitive)?.content) throw VectorFailure("header $name = $got, expected $value")
        }
        step["body"]?.let { wanted ->
            if (!contains(request.body ?: JsonNull, wanted)) throw VectorFailure("body ${request.body}, expected to contain $wanted")
        }
    }

    private suspend fun expect(step: JsonObject) {
        var last = ""
        val met = eventually {
            last = mismatch(step) ?: ""
            last.isEmpty()
        }
        if (!met) throw VectorFailure(last)
    }

    private fun mismatch(step: JsonObject): String? {
        (step["outcome"] as? JsonPrimitive)?.content?.let { wanted ->
            if (!holds(wanted)) {
                val now = session.snapshot.value
                return "outcome is not $wanted; answer $lastAnswer pairing $pairingStatus phase ${now.live.phase} stop ${now.stop}"
            }
        }
        (step["state"] as? JsonObject)?.forEach { (key, wanted) ->
            val actual = observe(key)
            if (!contains(actual, wanted)) return "$key is $actual, expected $wanted"
        }
        return null
    }

    private fun holds(outcome: String): Boolean = when (outcome) {
        "Waiting" -> pairingStatus is PairingStatus.Waiting
        "Paired" -> pairingStatus is PairingStatus.Paired
        "Refused" -> pairingStatus == PairingStatus.Refused
        "TimedOut" -> pairingStatus == PairingStatus.TimedOut
        "Failed" -> pairingStatus is PairingStatus.Failed
        "Loading" -> session.snapshot.value.live.phase == LivePhase.Loading
        "Live" -> session.snapshot.value.let { it.live.phase == LivePhase.Live && it.connection == ConnectionState.Connected }
        "Resyncing" -> session.snapshot.value.live.phase == LivePhase.Resyncing
        "Pending" -> session.snapshot.value.ledger.records.lastOrNull()?.state == OperationState.Pending
        "OutcomeUnknown" -> session.snapshot.value.ledger.records.lastOrNull()?.state == OperationState.OutcomeUnknown
        "Done" -> session.snapshot.value.ledger.records.lastOrNull()?.state is OperationState.Done
        "ConfirmationRequired" -> lastAnswer is RequestAnswer.Confirm
        "Invalid" -> lastAnswer is RequestAnswer.Invalid
        "NotAllowed" -> lastAnswer is RequestAnswer.NotAllowed
        "Unpaired" -> session.snapshot.value.stop is SessionStop.Unpaired
        "IdentityChanged" -> session.snapshot.value.stop == SessionStop.IdentityChanged
        else -> throw VectorFailure("outcome '$outcome' is not supported by this runner yet")
    }

    private fun observe(key: String): JsonElement = when (key) {
        "code" -> (pairingStatus as? PairingStatus.Waiting)?.let { JsonPrimitive(it.code) } ?: JsonNull
        "token_stored" -> JsonPrimitive(store.all().isNotEmpty())
        "operations_offered" -> JsonPrimitive(pairingStatus is PairingStatus.Paired)
        "pinned_fingerprint" -> store.all().firstOrNull()?.let { JsonPrimitive(it.fingerprint) } ?: JsonNull
        "requests_sent" -> JsonPrimitive(master.total)
        "requests_sent_with_key" -> JsonObject(master.keys.mapValues { JsonPrimitive(it.value) })
        "connection" -> JsonPrimitive(session.snapshot.value.connection.name)
        "confirmation.consequence" ->
            (lastAnswer as? RequestAnswer.Confirm)?.request?.consequence?.let { JsonPrimitive(it) } ?: JsonNull
        "confirmation.token" -> (lastAnswer as? RequestAnswer.Confirm)?.request?.token?.let { JsonPrimitive(it) } ?: JsonNull
        "invalid.path" -> (lastAnswer as? RequestAnswer.Invalid)?.let { JsonPrimitive(it.path) } ?: JsonNull
        "hold" -> JsonPrimitive(describe(session.snapshot.value.hold))
        else -> if (key.startsWith("operation.")) operationState(key.removePrefix("operation.")) else pierValue(key)
    }

    private fun operationState(id: String): JsonElement =
        session.snapshot.value.ledger.record(id)?.let { JsonPrimitive(describe(it.state)) } ?: JsonNull

    private fun describe(state: HoldState): String = when (state) {
        HoldState.Idle -> "Idle"
        HoldState.Starting -> "Starting"
        HoldState.Holding -> "Holding"
        is HoldState.Stopped -> when (state.reason) {
            HoldStop.Released -> "Stopped: released"
            HoldStop.Background -> "Stopped: background"
            HoldStop.ConnectionLost -> "Stopped: connection lost"
            HoldStop.LeaseLost -> "Stopped: lease lost"
            HoldStop.BeatFailed -> "Stopped: beat failed"
            is HoldStop.Refused -> "Stopped: refused"
        }
    }

    private fun describe(state: OperationState): String = when (state) {
        OperationState.Pending -> "Pending"
        is OperationState.Running -> "Running"
        is OperationState.Done -> "Done"
        is OperationState.Refused -> "Refused: ${state.reason}"
        is OperationState.Invalid -> "Invalid: ${state.reason}"
        is OperationState.Unavailable -> "Unavailable: ${state.reason}"
        is OperationState.Busy -> "Busy: ${state.reason}"
        is OperationState.Unreachable -> "Unreachable: ${state.reason}"
        OperationState.OutcomeUnknown -> "OutcomeUnknown"
    }

    /** `<pier>.<state key>`: `{value, stale}` as the UI would be told, or null when the Master hasn't said. */
    private fun pierValue(key: String): JsonElement {
        val parts = key.split('.', limit = 2)
        if (parts.size != 2) throw VectorFailure("state key '$key' is not supported by this runner yet")
        val current = session.snapshot.value
        val view = current.value(parts[1], parts[0], clock.now()) ?: return JsonNull
        return JsonObject(mapOf("value" to view.value, "stale" to JsonPrimitive(view.stale)))
    }

    companion object {
        val SUPPORTED_ACTIONS = setOf(
            "pair_start", "pair_poll", "handshake", "stream_start", "invoke", "confirm", "decline", "emergency",
            "hold_press", "hold_release", "background",
        )
        val SUPPORTED_STEPS = setOf("act", "send", "receive", "clock", "expect", "stream", "drop", "expect_connect")
        val SUPPORTED_OUTCOMES = setOf(
            "Waiting", "Paired", "Refused", "TimedOut", "Failed", "Loading", "Live", "Resyncing", "Pending", "Done",
            "OutcomeUnknown", "ConfirmationRequired", "Invalid", "NotAllowed", "Unpaired", "IdentityChanged",
        )
        val SUPPORTED_STATE_KEYS = setOf(
            "code", "token_stored", "operations_offered", "pinned_fingerprint", "requests_sent", "requests_sent_with_key",
            "connection", "confirmation.consequence", "confirmation.token", "invalid.path", "hold",
        )

        // `p1.camera.state`: a Pier id, then a dotted state key. Names reserved for S-CTL's ledger don't have this shape.
        private fun isPierKey(key: String) = key.contains('.') && key !in SUPPORTED_STATE_KEYS && !key.startsWith("operation.")

        /** True when every step, action, outcome and state key a vector uses is one this runner implements. */
        fun supports(vector: JsonObject): Boolean {
            val steps = vector["steps"] as? JsonArray ?: return false
            return steps.all { raw ->
                val step = raw as JsonObject
                val kind = (step["do"] as? JsonPrimitive)?.content
                kind in SUPPORTED_STEPS &&
                    (kind != "act" || (step["action"] as? JsonPrimitive)?.content in SUPPORTED_ACTIONS) &&
                    ((step["outcome"] as? JsonPrimitive)?.content?.let { it in SUPPORTED_OUTCOMES } ?: true) &&
                    ((step["state"] as? JsonObject)?.keys?.all { it in SUPPORTED_STATE_KEYS || it.startsWith("operation.") || isPierKey(it) } ?: true)
            }
        }
    }
}
