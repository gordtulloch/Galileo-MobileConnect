package com.galileo.mobileconnect.core.mock

import com.galileo.mobileconnect.api.infrastructure.Serializer
import com.galileo.mobileconnect.api.model.Operation
import com.galileo.mobileconnect.core.api.OperationClass
import com.galileo.mobileconnect.core.link.ClientIdentity
import com.galileo.mobileconnect.core.link.DropNullFields
import com.galileo.mobileconnect.core.link.IdentityInterceptor
import com.galileo.mobileconnect.core.link.LinkClock
import com.galileo.mobileconnect.core.link.MasterApi
import com.galileo.mobileconnect.core.link.MasterConnection
import com.galileo.mobileconnect.core.link.TokenHolder
import okhttp3.OkHttpClient
import com.galileo.mobileconnect.core.link.LinkError
import com.galileo.mobileconnect.core.link.StreamTransport
import com.galileo.mobileconnect.core.link.SystemClock
import com.galileo.mobileconnect.core.link.operationInfo
import com.galileo.mobileconnect.core.sky.SkyFrameDecoder
import com.galileo.mobileconnect.core.sky.SkyGeometry
import com.galileo.mobileconnect.core.sky.SkyObject
import com.galileo.mobileconnect.core.sky.SkySite
import com.galileo.mobileconnect.core.state.WireTime
import com.galileo.mobileconnect.core.state.bool
import com.galileo.mobileconnect.core.state.double
import com.galileo.mobileconnect.core.state.get
import com.galileo.mobileconnect.core.state.int
import com.galileo.mobileconnect.core.state.obj
import com.galileo.mobileconnect.core.state.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Turns a picture into what the Master would render. The mock's stand-in for `galileo.control_surface.frames`: platform
 * code (`:app`) supplies one that really applies the stretch and cuts tiles; this default serves the picture as it is.
 */
interface ImageRenderer {
    fun stretched(jpeg: ByteArray, stretch: Double): ByteArray = jpeg
    fun frameSize(jpeg: ByteArray): Pair<Int, Int>? = JpegSize.of(jpeg)
    fun tile(jpeg: ByteArray, level: Int, x: Int, y: Int): ByteArray? = null

    companion object {
        val PassThrough = object : ImageRenderer {}
    }
}

/** The dimensions of a JPEG from its start-of-frame marker: enough to say how big a frame is without decoding it. */
object JpegSize {
    fun of(bytes: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 9 < bytes.size) {
            if (bytes[i].toInt() and 0xFF != 0xFF) {
                i += 1
                continue
            }
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker in 0xC0..0xC3) {
                val height = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                val width = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                return width to height
            }
            val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            i += 2 + length
        }
        return null
    }
}

internal fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map(::toJson))
    is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toJson(it.value) })
    else -> JsonPrimitive(v.toString())
}

internal fun j(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { it.first to toJson(it.second) })

private val JsonElement?.array: JsonArray? get() = this as? JsonArray

/**
 * The Master's side of Connect Link and the Control Surface, played from a scenario in `api/scenarios/` (S-MOCK,
 * ADR-APP-006). It stands behind the same transports as a real Master (an OkHttp interceptor and a [StreamTransport]),
 * so all the client code runs against it, in tests, in UI tests and in demonstration mode (MAPP-CON-110).
 *
 * Install [intercept] as the last interceptor of the client, after the identity one, so the bearer token is on the request.
 */
class MockMaster(
    scenarioName: String,
    private val scope: CoroutineScope,
    private val clock: LinkClock = SystemClock,
    /** Above 1 runs the script faster; tests use it. */
    private val timeScale: Double = 1.0,
    private val renderer: ImageRenderer = ImageRenderer.PassThrough,
) : Interceptor, StreamTransport {
    private val lock = Any()
    private val scenario: JsonObject = loadScenario(scenarioName)
    private val catalogue: Map<String, Operation>
    private val catalogueBodies: List<JsonObject>
    private val preview: ByteArray? = resource("preview.jpg")

    private val values = HashMap<String, MutableMap<String, JsonElement>>()
    private class PairRequest(val created: Instant, var collected: Boolean = false)
    private val pairingRequests = HashMap<String, PairRequest>()
    private var revoked = false
    private val leases = HashMap<String, String?>()
    private val alerts = ArrayList<JsonObject>()
    private val idempotent = HashMap<String, Pair<Int, JsonElement>>()
    private class Confirm(val operation: String, val args: JsonElement)
    private val confirmTokens = HashMap<String, Confirm>()
    private class Hold(val pier: String, val family: String, var lastBeat: Instant)
    private val holds = HashMap<String, Hold>()
    private var counter = 0
    private var cachedSkyObjects: List<SkyObject>? = null

    private var epoch = "mock-epoch-1"
    private var seq = 0
    private val ring = ArrayList<Pair<Int, JsonElement>>()
    private val ringLimit = 256
    private var stream: Channel<String>? = null
    private var started: Instant? = null
    private val jobs = ArrayList<Job>()

    /** Requests seen, for tests: `METHOD /path`. */
    val requests: List<String> get() = synchronized(lock) { requestLog.toList() }
    private val requestLog = ArrayList<String>()

    init {
        val bodies = ArrayList<JsonObject>()
        for (file in listOf("catalogue.json", "catalogue.planned.json")) {
            val json = Json.parseToJsonElement(String(resource(file) ?: error("missing scenarios/$file")))
            json["operations"].array?.forEach { bodies += it as JsonObject }
        }
        catalogueBodies = bodies
        catalogue = bodies.associate { body ->
            val operation = Serializer.kotlinxSerializationJson.decodeFromJsonElement(Operation.serializer(), body)
            operation.name to operation
        }
        for ((pier, content) in scenario["state"].obj ?: emptyMap()) values[pier] = (content.obj ?: emptyMap()).toMutableMap()
        for ((pier, holder) in scenario["lease_holders"].obj ?: emptyMap()) leases[pier] = holder.string
        scenario["alerts"].array?.forEach { alerts += it as JsonObject }
    }

    /** The connection demonstration mode uses: the real client code, in front of the mock. */
    fun connection(identity: ClientIdentity, token: String? = null): MasterConnection {
        val holder = TokenHolder(token)
        val client = OkHttpClient.Builder()
            .addInterceptor(IdentityInterceptor(identity, holder))
            .addInterceptor(DropNullFields)
            .addInterceptor(this) // last: answers the request, never reaching a network
            .build()
        return MasterConnection(MasterApi("https://demo.invalid:8765", client, identity, holder), this)
    }

    /** Ends the script's timers; demonstration mode calls this when it is left. */
    fun shutDown() {
        synchronized(lock) {
            jobs.forEach { it.cancel() }
            jobs.clear()
            stream?.close()
            stream = null
        }
    }

    // -- HTTP -----------------------------------------------------------------------------------------------------

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val text = request.body?.let { Buffer().also { b -> it.writeTo(b) }.readUtf8() } ?: ""
        val body = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: JsonObject(emptyMap())
        return synchronized(lock) { handle(request, body) }
    }

    private fun reply(request: Request, status: Int, value: JsonElement, headers: Map<String, String> = emptyMap()) =
        build(request, status, Json.encodeToString(JsonElement.serializer(), value).toByteArray(), "application/json", headers)

    private fun build(request: Request, status: Int, bytes: ByteArray, type: String, headers: Map<String, String> = emptyMap()): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("mock").apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.body(bytes.toResponseBody(type.toMediaType())).build()

    private fun handle(request: Request, json: JsonElement): Response {
        val path = request.url.encodedPath
        val method = request.method
        requestLog += "$method $path"
        val segments = path.split('/').filter { it.isNotEmpty() }
        fun query(name: String) = request.url.queryParameter(name)

        // Unauthenticated: pairing.
        if (method == "POST" && path == "/connect/v1/pair") {
            val name = json["name"].string
            if (json["plugin"].string == null || name.isNullOrEmpty()) return reply(request, 400, j("error" to "bad request"))
            counter += 1
            val id = "req-$counter"
            pairingRequests[id] = PairRequest(clock.now())
            val code = "%06d".format((counter * 271_828L) % 1_000_000)
            return reply(
                request, 202,
                j(
                    "request_id" to id, "code" to code, "expires_in" to 300, "fingerprint" to FINGERPRINT,
                    "protocol" to (scenario["handshake"]["node"]["protocol"] ?: JsonPrimitive(1)),
                ),
            )
        }
        if (method == "GET" && segments.size == 4 && segments[2] == "pair") return reply(request, 200, pollPairing(segments[3]))

        // Everything else needs the token.
        if (revoked || request.header("Authorization") != "Bearer $TOKEN") return reply(request, 401, j("error" to "unauthorised"))

        when (method to path) {
            "POST" to "/connect/v1/handshake" -> {
                val result = (scenario["handshake"].obj ?: emptyMap()).toMutableMap()
                if (result["allowed"] == null) {
                    result["allowed"] = if (result["verdict"].string == "refused") JsonArray(emptyList())
                    else toJson(listOf("config.read", "config.write", "data.push", "data.pull", "control", "view"))
                }
                return reply(request, 200, JsonObject(result))
            }
            "GET" to "/connect/v1/whoami" ->
                return reply(request, 200, j("peer_id" to PEER_ID, "name" to "Mock device", "plugin" to "mobileconnect",
                    "permissions" to (scenario["permissions"] ?: toJson(listOf("control", "view")))))
            "GET" to "/mobileconnect/v1/capabilities" ->
                return reply(request, 200, scenario["capabilities"] ?: j("plugin_api" to "1.0", "areas" to emptyList<String>()))
            "GET" to "/mobileconnect/v1/piers" -> return reply(request, 200, j("piers" to (scenario["piers"] ?: JsonArray(emptyList()))))
            "GET" to "/mobileconnect/v1/catalogue" -> return reply(request, 200, j("operations" to catalogueBody(query("pier"))))
            "GET" to "/mobileconnect/v1/alerts" -> return reply(request, 200, j("alerts" to alerts.map(::stampedAlert).reversed()))
        }

        val pier = query("pier") ?: ""
        return when (method to path) {
            "POST" to "/mobileconnect/v1/snapshot" -> {
                val open = stream != null
                if (open) emitSnapshot()
                reply(request, 200, j("ok" to open))
            }
            "POST" to "/mobileconnect/v1/lease" -> leaseAcquire(pier, request.header("X-Confirm-Token")).let { reply(request, it.first, it.second) }
            "DELETE" to "/mobileconnect/v1/lease" -> leaseRelease(pier).let { reply(request, it.first, it.second) }
            "POST" to "/mobileconnect/v1/ops" ->
                invoke(pier, query("name") ?: "", json, request.header("Idempotency-Key"), request.header("X-Confirm-Token"))
                    .let { reply(request, it.first, it.second) }
            "GET" to "/mobileconnect/v1/preview" -> {
                val image = rendered(query("stretch")?.toDoubleOrNull() ?: 50.0) ?: return reply(request, 404, j("error" to "no frame yet"))
                val size = preview?.let { renderer.frameSize(it) }
                val headers = HashMap<String, String>()
                if (size != null) {
                    headers["X-Frame-Width"] = size.first.toString()
                    headers["X-Frame-Height"] = size.second.toString()
                }
                build(request, 200, image, "image/jpeg", headers)
            }
            "GET" to "/mobileconnect/v1/tile" -> {
                val image = preview?.let { renderer.tile(it, query("z")?.toIntOrNull() ?: 0, query("x")?.toIntOrNull() ?: 0, query("y")?.toIntOrNull() ?: 0) }
                    ?: return reply(request, 404, j("error" to "no such tile"))
                build(request, 200, image, "image/jpeg")
            }
            "GET" to "/mobileconnect/v1/sky/catalog" -> skyCatalog(request)
            "GET" to "/mobileconnect/v1/sky/ephemeris" -> reply(request, 200, skyEphemeris(request))
            "GET" to "/mobileconnect/v1/sky/site" ->
                scenario["sky"]["site"]?.let { reply(request, 200, it) } ?: reply(request, 404, j("error" to "no location set"))
            "GET" to "/mobileconnect/v1/sky/constellations" -> reply(request, 200, skyConstellations())
            "GET" to "/mobileconnect/v1/sky/catalogs" -> reply(request, 200, skyCatalogs())
            // The demo ships no sky pictures, as they aren't ours to. Tests can point it at a folder of real tiles.
            "GET" to "/mobileconnect/v1/sky/survey/tile" -> reply(request, 404, j("error" to "no sky survey in the demo"))
            "GET" to "/mobileconnect/v1/sky/check" ->
                skyCheck(query("ra")?.toDoubleOrNull(), query("dec")?.toDoubleOrNull())?.let { reply(request, 200, it) }
                    ?: reply(request, 404, j("error" to "no location set"))
            "GET" to "/mobileconnect/v1/sky/identify" ->
                skyIdentify(query("index")?.toIntOrNull())?.let { reply(request, 200, it) } ?: reply(request, 404, j("error" to "no such object"))
            else -> reply(request, 404, j("error" to "no such route"))
        }
    }

    private fun pollPairing(id: String): JsonElement {
        val request = pairingRequests[id] ?: return j("state" to "expired")
        val waited = (clock.now().toEpochMilli() - request.created.toEpochMilli()) / 1000.0 * timeScale
        if (waited < (scenario["pair_approval_s"].double ?: 0.0)) return j("state" to "pending")
        return when (scenario["pairing"].string) {
            "reject" -> j("state" to "rejected")
            "never" -> j("state" to "pending")
            else -> {
                if (request.collected) return j("state" to "collected")
                request.collected = true
                j("state" to "approved", "token" to TOKEN)
            }
        }
    }

    private fun catalogueBody(pier: String?): List<JsonObject> {
        val unavailable = pier?.let { scenario["unavailable"][it].obj } ?: emptyMap()
        return catalogueBodies.map { body ->
            val reason = body["name"].string?.let { unavailable[it] } ?: return@map body
            JsonObject(body + mapOf("available" to JsonPrimitive(false), "unavailable_because" to reason))
        }
    }

    private fun stampedAlert(alert: JsonObject): JsonObject =
        if (alert["time"] == null) JsonObject(alert + ("time" to JsonPrimitive(WireTime.string(clock.now())))) else alert

    // -- lease ----------------------------------------------------------------------------------------------------

    private fun leaseAcquire(pier: String, token: String?): Pair<Int, JsonElement> {
        if (!hasControl(pier)) return 403 to j("error" to "refused", "reason" to "This device can only view this Pier.")
        val holder = leases[pier]
        if (holder != null && holder != PEER_ID) {
            val taking = token?.let { confirmTokens.remove(it)?.operation } == "lease"
            if (!taking) {
                return 409 to j(
                    "error" to "refused", "reason" to "$holder has control of this Pier.",
                    "detail" to j("kind" to "lease_held", "holder" to holder, "can_take" to true),
                    "confirm_token" to makeConfirmToken("lease", null), "expires_in" to 60,
                )
            }
        }
        leases[pier] = PEER_ID
        emit(j("type" to "lease", "pier" to pier, "holder" to PEER_ID, "holder_name" to "This device"))
        return 200 to j("pier" to pier, "held_by_me" to true, "holder" to PEER_ID)
    }

    private fun leaseRelease(pier: String): Pair<Int, JsonElement> {
        if (leases[pier] == PEER_ID) {
            leases[pier] = null
            emit(j("type" to "lease", "pier" to pier, "holder" to null, "holder_name" to null))
        }
        return 200 to j("pier" to pier, "held_by_me" to false)
    }

    private fun makeConfirmToken(operation: String, args: JsonElement?): String {
        counter += 1
        val token = "confirm-$counter"
        confirmTokens[token] = Confirm(operation, args ?: JsonNull)
        return token
    }

    private fun hasControl(pier: String): Boolean {
        val info = scenario["piers"].array?.firstOrNull { it["id"].string == pier }
        val permitted = scenario["permissions"].array?.any { it.string == "control" } ?: true
        return permitted && info["access"].string == "control" && info["reachable"].bool != false
    }

    // -- operations -----------------------------------------------------------------------------------------------

    private fun invoke(pier: String, name: String, body: JsonElement, key: String?, confirmToken: String?): Pair<Int, JsonElement> {
        val storeKey = key?.let { "$pier/$it" }
        storeKey?.let { idempotent[it] }?.let { return it }
        val answer = run(pier, name, body, confirmToken, key)
        storeKey?.let { idempotent[it] = answer }
        return answer
    }

    private fun refuse(kind: String, reason: String, status: Int = 409, detail: JsonElement? = null): Pair<Int, JsonElement> {
        val fields = mutableMapOf<String, JsonElement>("error" to JsonPrimitive(kind), "reason" to JsonPrimitive(reason))
        if (detail != null) fields["detail"] = detail
        return status to JsonObject(fields)
    }

    private fun run(pier: String, name: String, body: JsonElement, confirmToken: String?, key: String?): Pair<Int, JsonElement> {
        val entry = catalogue[name]
        val info = entry?.let { operationInfo(it) } ?: return refuse("unavailable", "This Master does not offer $name.", 404)
        val args = body["args"].obj ?: emptyMap()
        info.validate(args)?.let { fault ->
            return refuse("invalid", "${fault.path} ${fault.reason}", 400, j("path" to fault.path))
        }
        scenario["unavailable"][pier][name].string?.let { return refuse("unavailable", it) }
        if (info.operationClass != OperationClass.Read && !hasControl(pier)) {
            return refuse("refused", "This device can only view this Pier.", 403)
        }
        if (info.operationClass == OperationClass.Emergency && body["confirmed"].bool != true) {
            return refuse("refused", "An emergency action needs a deliberate confirmation.", 403)
        }
        if (info.needsLease && leases[pier] != PEER_ID) {
            val holder = leases[pier]
            if (holder != null) {
                return refuse("refused", "$holder has control of this Pier.", 409, j("kind" to "lease_held", "holder" to holder, "can_take" to true))
            }
            return refuse("refused", "Take control of this Pier first.", 409, j("kind" to "no_lease"))
        }
        if (info.operationClass == OperationClass.Confirm) {
            val stored = confirmToken?.let { confirmTokens.remove(it) }
            if (stored == null || stored.operation != name || stored.args != JsonObject(args)) {
                val token = makeConfirmToken(name, JsonObject(args))
                val reason = scenario["consequences"][name].string ?: info.description ?: name
                return 409 to j("error" to "confirmation_required", "reason" to reason, "confirm_token" to token, "expires_in" to 60)
            }
        }

        var result: JsonElement? = scenario["results"][name]
        if (name.startsWith("sky.")) {
            val answer = skyOperation(name, args) ?: return refuse("refused", "There is no catalogue object called that.")
            return 200 to j("ok" to true, "result" to answer)
        }
        when {
            name.endsWith(".hold_start") -> {
                counter += 1
                val id = "hold-$counter"
                holds[id] = Hold(pier, name.removeSuffix(".hold_start"), clock.now())
                schedule(0.0, scaled = false) { deadmanLoop(id) }
                result = j("hold_id" to id)
            }
            name.endsWith(".hold_beat") -> {
                val id = args["hold_id"].string ?: ""
                val alive = holds[id] != null
                holds[id]?.lastBeat = clock.now()
                return 200 to j("ok" to true, "result" to alive)
            }
            name.endsWith(".hold_stop") -> result = JsonPrimitive(holds.remove(args["hold_id"].string ?: "") != null)
            name.endsWith(".status") || name.endsWith(".names") -> result = statusBody(pier, info.area)
        }

        runEffects(name, pier, args)

        counter += 1
        val correlation = "op-$counter"
        if (info.longRunning) {
            val requestId: JsonElement = key?.let { JsonPrimitive(it) } ?: JsonNull
            emit(j("type" to "op.started", "correlation_id" to correlation, "request_id" to requestId, "pier" to pier, "operation" to name))
            schedule(scenario["durations"][name].double ?: 3.0) {
                emit(j("type" to "op.finished", "correlation_id" to correlation, "request_id" to requestId, "pier" to pier,
                    "operation" to name, "outcome" to "ok"))
            }
        }
        val fields = mutableMapOf<String, JsonElement>("ok" to JsonPrimitive(true), "correlation_id" to JsonPrimitive(correlation))
        if (result != null) fields["result"] = result
        return 200 to JsonObject(fields)
    }

    private fun statusBody(pier: String, area: String): JsonElement {
        val result = LinkedHashMap<String, JsonElement>()
        for ((key, value) in values[pier] ?: emptyMap<String, JsonElement>()) {
            if (key.startsWith("$area.")) result[key.substring(area.length + 1)] = value
        }
        return JsonObject(result)
    }

    /** As the real Master: a hold whose keep-alives stop ends by itself (`HoldManager.check`). */
    private suspend fun deadmanLoop(id: String) {
        while (true) {
            if (synchronized(lock) { holds[id] } == null) return
            clock.sleep(0.1)
            val done = synchronized(lock) {
                val current = holds[id] ?: return
                if ((clock.now().toEpochMilli() - current.lastBeat.toEpochMilli()) / 1000.0 >= 0.6) {
                    holds.remove(id)
                    runEffects("${current.family}.hold_stop", current.pier, emptyMap())
                    true
                } else false
            }
            if (done) return
        }
    }

    private fun runEffects(name: String, pier: String, args: Map<String, JsonElement>) {
        for (step in scenario["effects"][name].array ?: emptyList()) {
            val delay = step["after_s"].double ?: 0.0
            val changes = step["set"].obj ?: emptyMap()
            if (delay == 0.0) apply(changes, pier, args) else schedule(delay) { apply(changes, pier, args) }
        }
    }

    private fun apply(changes: Map<String, JsonElement>, pier: String, args: Map<String, JsonElement>) = synchronized(lock) {
        for (key in changes.keys.sorted()) {
            var value = changes.getValue(key)
            val argName = value["\$arg"].string
            val add = value["\$add"].array
            if (argName != null) {
                value = args[argName] ?: JsonNull
            } else if (add != null && add.size == 2 && add[0].string != null && add[1].string != null) {
                val step = if (add[1].string == "_one") 1.0 else args[add[1].string]?.double ?: 0.0
                value = JsonPrimitive((values[pier]?.get(add[0].string!!).double ?: 0.0) + step)
            }
            set(key, value, pier)
        }
    }

    private fun set(key: String, value: JsonElement, pier: String) {
        values.getOrPut(pier) { HashMap() }[key] = value
        emit(j("type" to "delta", "pier" to pier, "key" to key, "value" to value, "as_of" to WireTime.string(clock.now())))
    }

    /** The mock's stand-in for the Master rendering a preview: it applies the requested stretch so the control visibly works. */
    private fun rendered(stretch: Double): ByteArray? {
        val image = preview ?: return null
        return if (stretch == 50.0) image else renderer.stretched(image, stretch)
    }

    // -- sky ------------------------------------------------------------------------------------------------------

    /** Deterministic made-up stars: this is a demonstration, and the app ships no catalog (MAPP-STR-010). */
    private val skyObjects: List<SkyObject>
        get() = cachedSkyObjects ?: buildSkyObjects().also { cachedSkyObjects = it }

    private fun buildSkyObjects(): List<SkyObject> {
        var state = (scenario["sky"]["seed"].int ?: 1).toULong()
        fun next(): Double {
            state = state * 6_364_136_223_846_793_005UL + 1_442_695_040_888_963_407UL
            return (state shr 11).toDouble() / (1L shl 53).toDouble()
        }
        val list = ArrayList<SkyObject>()
        val stars = scenario["sky"]["stars"].int ?: 0
        for (i in 0 until stars) {
            val ra = next() * 360
            val dec = asin(next() * 2 - 1) * 180 / Math.PI
            val mag = 13 - 13.5 * next().pow(0.33) // many faint, few bright
            list += SkyObject(i, ra.toFloat(), dec.toFloat(), mag.toFloat(), 0)
        }
        scenario["sky"]["objects"].array?.forEachIndexed { i, o ->
            list += SkyObject(
                stars + i, (o["ra_deg"].double ?: 0.0).toFloat(), (o["dec_deg"].double ?: 0.0).toFloat(),
                (o["magnitude"].double ?: 99.0).toFloat(), 1,
            )
        }
        // The stars of the demo's constellations are real, so that their figures have something to join.
        for (star in figureStars) list += SkyObject(list.size, star.ra.toFloat(), star.dec.toFloat(), star.magnitude.toFloat(), 0)
        return list
    }

    private class FigureStar(val name: String, val ra: Double, val dec: Double, val magnitude: Double)

    private val figureStars: List<FigureStar>
        get() = (scenario["sky"]["constellations"].array ?: emptyList()).flatMap { figure ->
            (figure["stars"].array ?: emptyList()).mapNotNull { star ->
                val row = star.array ?: return@mapNotNull null
                if (row.size != 4) return@mapNotNull null
                FigureStar(row[0].string ?: return@mapNotNull null, row[1].double ?: return@mapNotNull null,
                    row[2].double ?: return@mapNotNull null, row[3].double ?: return@mapNotNull null)
            }
        }

    private fun skyConstellations(): JsonElement {
        val lines = ArrayList<JsonElement>()
        val names = ArrayList<JsonElement>()
        for (figure in scenario["sky"]["constellations"].array ?: emptyList()) {
            val stars = figure["stars"].array ?: emptyList()
            for (line in figure["lines"].array ?: emptyList()) {
                lines.add(JsonArray((line.array ?: emptyList()).mapNotNull { it.int }.map { index ->
                    val star = stars[index].array!!
                    JsonArray(listOf(star[1], star[2]))
                }))
            }
            names.add(j("name" to (figure["name"] ?: ""), "code" to (figure["code"] ?: ""),
                "ra_deg" to (figure["ra_deg"] ?: 0), "dec_deg" to (figure["dec_deg"] ?: 0)))
        }
        return j("lines" to JsonArray(lines), "names" to JsonArray(names))
    }

    private fun skyCatalog(request: Request): Response {
        fun q(n: String) = request.url.queryParameter(n)
        val ra = q("ra")?.toDoubleOrNull() ?: 0.0
        val dec = q("dec")?.toDoubleOrNull() ?: 0.0
        val radius = q("radius")?.toDoubleOrNull() ?: 180.0
        val level = q("lod")?.toIntOrNull() ?: 4
        val limit = listOf(4f, 7f, 10f, 13f, 99f)[max(0, min(4, level))]
        val center = SkyGeometry.unitVector(ra.toFloat(), dec.toFloat())
        val cosRadius = cos(min(180.0, radius) * Math.PI / 180).toFloat()
        val matched = skyObjects
            .filter { it.magnitude <= limit && SkyGeometry.unitVector(it.raDeg, it.decDeg).dot(center) >= cosRadius }
            .sortedBy { it.magnitude }
        val offset = q("cursor")?.toIntOrNull() ?: 0
        val page = matched.drop(offset).take(500)
        val next = offset + page.size
        return build(
            request, 200, SkyFrameDecoder.encode(page), "application/octet-stream",
            mapOf("X-Sky-Next" to if (next < matched.size) next.toString() else "", "X-Sky-Total" to matched.size.toString()),
        )
    }

    private fun skyEphemeris(request: Request): JsonElement {
        fun time(n: String) = request.url.queryParameter(n)?.let(WireTime::parse)
        val start = time("from") ?: time("t") ?: clock.now()
        val end = time("to") ?: start
        val step = max(60.0, request.url.queryParameter("step")?.toDoubleOrNull() ?: 600.0)
        val samples = ArrayList<JsonElement>()
        var at = start
        while (at <= end && samples.size < 1440) {
            val hours = at.toEpochMilli() / 1000.0 / 3600
            val bodies = (scenario["sky"]["bodies"].array ?: emptyList()).map { body ->
                val moved = ((body["ra_deg"].double ?: 0.0) + (body["rate_deg_per_h"].double ?: 0.0) * (hours - 493_000)) % 360
                j("name" to (body["name"] ?: JsonPrimitive("?")), "ra_deg" to if (moved < 0) moved + 360 else moved,
                    "dec_deg" to (body["dec_deg"] ?: JsonPrimitive(0)))
            }
            samples.add(j("t" to WireTime.string(at), "bodies" to bodies))
            at = at.plusMillis((step * 1000).toLong())
        }
        return j("as_of" to WireTime.string(clock.now()), "samples" to JsonArray(samples))
    }

    /** By name, as `catalogs_of` in core has it. */
    private fun skyCatalogs(): JsonElement {
        val starCount = scenario["sky"]["stars"].double?.toInt() ?: 0
        val objects = scenario["sky"]["objects"].array ?: emptyList()
        fun catalog(name: String, prefix: String): JsonElement = j(
            "name" to name,
            "indexes" to objects.indices.filter {
                val n = objects[it]["name"].string ?: ""
                n.startsWith(prefix) && n.drop(prefix.length).firstOrNull()?.isDigit() == true
            }.map { starCount + it },
        )
        return j("catalogs" to listOf(catalog("Messier", "M "), catalog("Caldwell", "C "), catalog("NGC", "NGC ")))
    }

    private fun mockSite(): SkySite? {
        val site = scenario["sky"]["site"] ?: return null
        return SkySite(
            site["name"].string, site["latitude_deg"].double ?: return null, site["longitude_deg"].double ?: return null,
            (site["horizon"].array ?: emptyList()).mapNotNull {
                SkySite.HorizonPoint(it["az_deg"].double ?: return@mapNotNull null, it["alt_deg"].double ?: return@mapNotNull null)
            },
        )
    }

    private fun skyCheck(ra: Double?, dec: Double?): JsonElement? {
        val site = mockSite() ?: return null
        if (ra == null || dec == null) return null
        val place = SkyGeometry.horizontal(ra, dec, site, clock.now())
        val skyline = site.skylineAltitude(place.azimuth)
        val answer = mutableMapOf<String, JsonElement>(
            "altitude_deg" to JsonPrimitive(place.altitude), "azimuth_deg" to JsonPrimitive(place.azimuth),
            "above_horizon" to JsonPrimitive(place.altitude > skyline),
        )
        if (place.altitude <= skyline) {
            answer["reason"] = JsonPrimitive("Below the horizon: altitude %.1f° where the skyline is %.1f°.".format(place.altitude, skyline))
        }
        return JsonObject(answer)
    }

    private fun skyIdentify(index: Int?): JsonElement? {
        val found = skyObjects.firstOrNull { it.index == index } ?: return null
        val answer = mutableMapOf<String, JsonElement>(
            "index" to JsonPrimitive(found.index), "kind" to JsonPrimitive(if (found.isDeepSky) "deep_sky" else "star"),
            "ra_deg" to JsonPrimitive(found.raDeg.toDouble()), "dec_deg" to JsonPrimitive(found.decDeg.toDouble()),
            "magnitude" to JsonPrimitive(found.magnitude.toDouble()),
        )
        val stars = scenario["sky"]["stars"].int ?: 0
        if (found.isDeepSky) {
            scenario["sky"]["objects"].array?.getOrNull(found.index - stars)?.let { o ->
                o["name"]?.let { answer["name"] = it }
                o["type"]?.let { answer["type"] = it }
            }
        } else if (found.index >= stars) {
            val offset = found.index - stars - (scenario["sky"]["objects"].array?.size ?: 0)
            figureStars.getOrNull(offset)?.let { answer["name"] = JsonPrimitive(it.name) }
        }
        return JsonObject(answer)
    }

    private fun skyOperation(name: String, args: Map<String, JsonElement>): JsonElement? {
        val objects = scenario["sky"]["objects"].array?.map { it as JsonObject } ?: emptyList()
        fun names(o: JsonObject) = listOf(o["name"].string ?: "") + (o["designations"].array?.mapNotNull { it.string } ?: emptyList())
        return when (name) {
            "sky.search" -> {
                val query = args["query"].string?.lowercase() ?: ""
                val found = objects.filter { o -> names(o).any { it.lowercase().contains(query) } }
                j("query" to query, "objects" to found, "total" to found.size)
            }
            "sky.object" -> {
                val wanted = args["name"].string?.lowercase() ?: ""
                val o = objects.firstOrNull { o -> names(o).any { it.lowercase() == wanted } } ?: return null
                JsonObject(o + mapOf(
                    "site" to (scenario["sky"]["site"]["name"] ?: JsonNull),
                    "rise_transit_set" to j("rise" to "19:12", "transit" to "02:40", "set" to "10:08"),
                ))
            }
            "sky.framing" -> j("ra_deg" to (args["ra_deg"] ?: 0), "dec_deg" to (args["dec_deg"] ?: 0),
                "fov_deg" to (scenario["sky"]["framing"]["fov_deg"] ?: toJson(listOf(1, 1))))
            else -> null
        }
    }

    // -- stream ---------------------------------------------------------------------------------------------------

    override fun connect(epoch: String?, last: Int?): Flow<String> = flow {
        val clientEpoch = epoch
        val channel = Channel<String>(Channel.UNLIMITED)
        val currentEpoch = synchronized(lock) { this@MockMaster.epoch }
        synchronized(lock) {
            if (revoked) throw LinkError.Unpaired
            stream?.close()
            stream = channel
            if (last != null && clientEpoch != null) {
                val oldest = ring.firstOrNull()?.first ?: (seq + 1)
                if (clientEpoch == currentEpoch && last >= oldest - 1) {
                    yieldFrame(j("type" to "hello", "epoch" to currentEpoch, "seq" to seq))
                    for (entry in ring) if (entry.first > last) yieldFrame(j("seq" to entry.first, "data" to entry.second))
                } else {
                    yieldFrame(j("type" to "resync", "epoch" to currentEpoch, "seq" to seq))
                }
            } else {
                yieldFrame(j("type" to "hello", "epoch" to currentEpoch, "seq" to seq))
            }
            startScriptOnce()
        }
        try {
            for (frame in channel) emit(frame)
        } finally {
            synchronized(lock) { if (stream === channel) stream = null }
        }
    }

    private fun emitSnapshot() {
        val piers = LinkedHashMap<String, JsonElement>()
        for ((pier, content) in values) {
            val merged = content.toMutableMap()
            leases[pier]?.let { holder ->
                merged["lease.holder"] = JsonPrimitive(holder)
                merged["lease.holder_name"] = JsonPrimitive(if (holder == PEER_ID) "This device" else holder)
            }
            piers[pier] = JsonObject(merged)
        }
        emit(j("type" to "snapshot", "as_of" to WireTime.string(clock.now()), "piers" to JsonObject(piers)))
    }

    private fun emit(data: JsonElement) {
        seq += 1
        ring += seq to data
        if (ring.size > ringLimit) ring.subList(0, ring.size - ringLimit).clear()
        yieldFrame(j("seq" to seq, "data" to data))
    }

    private fun yieldFrame(frame: JsonElement) {
        stream?.trySend(Json.encodeToString(JsonElement.serializer(), frame))
    }

    /** Ends the connection without warning. */
    fun dropStream() = synchronized(lock) {
        stream?.close(IOException("connection lost"))
        stream = null
    }

    /** The Master restarts its stream, so nothing can be replayed. */
    fun forceResync() = synchronized(lock) {
        counter += 1
        epoch = "mock-epoch-${counter + 1}"
        ring.clear()
        dropStream()
    }

    fun revoke() = synchronized(lock) {
        revoked = true
        dropStream()
    }

    // -- script ---------------------------------------------------------------------------------------------------

    private fun schedule(afterSeconds: Double, scaled: Boolean = true, body: suspend () -> Unit) {
        val scale = if (scaled) timeScale else 1.0
        synchronized(lock) {
            jobs.removeAll { it.isCancelled || it.isCompleted }
            jobs += scope.launch {
                if (afterSeconds > 0) {
                    try {
                        clock.sleep(afterSeconds / scale)
                    } catch (e: CancellationException) {
                        throw e
                    }
                }
                body()
            }
        }
    }

    private fun startScriptOnce() {
        if (started != null) return
        started = clock.now()
        runTimeline()
        for (wave in scenario["waves"].array ?: emptyList()) startWave(wave)
    }

    private fun runTimeline() {
        val events = scenario["timeline"].array ?: emptyList()
        for (event in events) schedule(event["at_s"].double ?: 0.0) { synchronized(lock) { play(event) } }
        val loop = scenario["loop_s"].double
        if (loop != null && loop > 0 && events.isNotEmpty()) schedule(loop) { synchronized(lock) { runTimeline() } }
    }

    private fun play(event: JsonElement) {
        val changes = event["set"].obj
        val pier = event["pier"].string
        if (changes != null && pier != null) apply(changes, pier, emptyMap())
        event["alert"].obj?.let { source ->
            counter += 1
            val alert = JsonObject(source + mapOf("id" to JsonPrimitive("a$counter"), "time" to JsonPrimitive(WireTime.string(clock.now())), "type" to JsonPrimitive("alert")))
            alerts += alert
            if (alerts.size > 100) alerts.removeAt(0)
            emit(alert)
        }
        when (event["do"].string) {
            "drop" -> dropStream()
            "resync" -> forceResync()
            "revoke" -> revoke()
        }
    }

    private fun startWave(wave: JsonElement) {
        val every = wave["every_s"].double ?: return
        schedule(every) { waveTick(wave, every) }
    }

    private fun waveTick(wave: JsonElement, every: Double) {
        val pier = wave["pier"].string
        val key = wave["key"].string
        if (pier == null || key == null) return
        synchronized(lock) {
            val elapsed = (clock.now().toEpochMilli() - (started ?: clock.now()).toEpochMilli()) / 1000.0 * timeScale
            fun sine(spec: JsonElement): Double {
                val amplitude = spec["amp"].double ?: 0.0
                val period = max(0.001, spec["period_s"].double ?: 1.0)
                return (spec["base"].double ?: 0.0) + amplitude * sin(2 * Math.PI * elapsed / period)
            }
            val fields = wave["fields"].obj
            val ramp = wave["ramp_s"].double
            val value: JsonElement = when {
                fields != null -> JsonObject(fields.mapValues { JsonPrimitive(sine(it.value)) })
                ramp != null -> JsonPrimitive((elapsed % ramp) / ramp)
                else -> JsonPrimitive(sine(wave))
            }
            set(key, value, pier)
        }
        schedule(every) { waveTick(wave, every) }
    }

    companion object {
        const val TOKEN = "mock-token"
        const val PEER_ID = "mock-peer"
        val FINGERPRINT = "ab".repeat(32)

        private fun resource(name: String): ByteArray? = MockMaster::class.java.getResourceAsStream("/scenarios/$name")?.use { it.readBytes() }

        /** A scenario with `extends` resolved. */
        fun loadScenario(name: String): JsonObject {
            val own = Json.parseToJsonElement(String(resource("$name.json") ?: error("missing scenario $name"))) as JsonObject
            val parent = own["extends"].string ?: return own
            return JsonObject(loadScenario(parent) + own)
        }
    }
}
