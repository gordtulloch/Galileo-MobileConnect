package com.galileo.mobileconnect.core.control

import com.galileo.mobileconnect.core.api.ArgumentInfo
import com.galileo.mobileconnect.core.api.CatalogueFilter
import com.galileo.mobileconnect.core.api.OpResult
import com.galileo.mobileconnect.core.api.OperationClass
import com.galileo.mobileconnect.core.api.OperationInfo
import com.galileo.mobileconnect.core.api.Role
import com.galileo.mobileconnect.core.link.ClientIdentity
import com.galileo.mobileconnect.core.link.DropNullFields
import com.galileo.mobileconnect.core.link.MasterApi
import com.galileo.mobileconnect.core.state.OperationEvent
import com.galileo.mobileconnect.core.vectors.TestClock
import com.galileo.mobileconnect.core.vectors.eventually
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun num(v: Number) = JsonPrimitive(v)
private fun str(v: String) = JsonPrimitive(v)
private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

/** The Kotlin side of iOS `CatalogueTests`: same cases, so the two clients agree (S-API, MAPP-PLT-040). */
class CatalogueTest {
    private val slew = OperationInfo(
        name = "mount.slew", area = "mount", operationClass = OperationClass.Operate, needsLease = true,
        arguments = listOf(
            ArgumentInfo("ra_deg", ArgumentInfo.Kind.Float, minimum = 0.0, maximum = 360.0),
            ArgumentInfo("count", ArgumentInfo.Kind.Int, required = false, minimum = 1.0, maximum = 5.0),
            ArgumentInfo("frame", ArgumentInfo.Kind.Enum, required = false, choices = listOf("Light", "Dark")),
            ArgumentInfo("on", ArgumentInfo.Kind.Bool, required = false),
        ),
    )

    @Test
    fun `validation matches the Master's rules`() {
        assertNull(slew.validate(mapOf("ra_deg" to num(10))))
        assertEquals("ra_deg", slew.validate(emptyMap())?.path)
        assertEquals("must be at most 360", slew.validate(mapOf("ra_deg" to num(400)))?.reason)
        assertEquals("must be a number", slew.validate(mapOf("ra_deg" to str("ten")))?.reason)
        assertEquals("must be a whole number", slew.validate(mapOf("ra_deg" to num(1), "count" to num(2.5)))?.reason)
        assertEquals("frame", slew.validate(mapOf("ra_deg" to num(1), "frame" to str("Bias")))?.path)
        assertEquals("must be true or false", slew.validate(mapOf("ra_deg" to num(1), "on" to num(1)))?.reason)
        assertEquals("is not an argument of this operation", slew.validate(mapOf("ra_deg" to num(1), "other" to num(1)))?.reason)
    }

    @Test
    fun `MAPP-CFG-010, MAPP-FOC-030 configuration and a generic focuser move can't reach the app`() {
        assertEquals(OperationClass.Confirm, CatalogueFilter.accept("mount.park", "confirm"))
        assertNull(CatalogueFilter.accept("profile.save", "config"))
        assertNull(CatalogueFilter.accept("profile.save", "operate"))
        assertNull(CatalogueFilter.accept("mount.configure", "config"))
        assertNull(CatalogueFilter.accept("focuser.move", "operate"))
        assertEquals(OperationClass.Operate, CatalogueFilter.accept("focus.move_to", "operate"))
        assertNull(CatalogueFilter.accept("mount.future", "a_new_class"))
    }

    @Test
    fun `MAPP-CTL-010, MAPP-CFG-030 role comes from the grant, never from what the handshake permits`() {
        val everythingAllowed = setOf("config.read", "config.write", "data.push", "data.pull", "control", "view")
        assertEquals(Role.Operator, Role.from(setOf("control", "view"), everythingAllowed))
        assertEquals(Role.Viewer, Role.from(setOf("view"), everythingAllowed))
        assertNull(Role.from(setOf("data.pull"), everythingAllowed))
        assertTrue(Role.hasConfigurationGrant(setOf("control", "config.read")))
        assertFalse(Role.hasConfigurationGrant(setOf("control", "view")))
    }
}

/** The Kotlin side of iOS `LedgerTests` (MAPP-CTL-050, MAPP-CTL-070). */
class LedgerTest {
    private val now = Instant.EPOCH

    private fun event(phase: String, correlation: String, request: String? = null, progress: Double? = null, outcome: String? = null, reason: String? = null) =
        OperationEvent(phase, correlation, request, null, null, progress, outcome, reason)

    @Test
    fun `nothing is done until the Master says so`() {
        var ledger = OperationLedger().sent("k", "p", "o", now)
        assertEquals(OperationState.Pending, ledger.record("k")?.state)
        ledger = ledger.answered("k", OpResult.Ok(num(1), null), longRunning = false)
        assertEquals(OperationState.Done(num(1)), ledger.record("k")?.state)
    }

    @Test
    fun `a drop makes open records unknown and nothing else`() {
        val ledger = OperationLedger().sent("a", "p", "o", now).sent("b", "p", "o", now)
            .answered("b", OpResult.Ok(null, null), longRunning = false).connectionLost()
        assertEquals(OperationState.OutcomeUnknown, ledger.record("a")?.state)
        assertEquals(OperationState.Done(null), ledger.record("b")?.state)
        assertEquals(1, ledger.sentCounts["a"])
    }

    @Test
    fun `the Master's finish resolves an unknown outcome`() {
        val ledger = OperationLedger().sent("a", "p", "o", now).connectionLost()
            .apply(event("op.finished", "c", request = "a", outcome = "ok"))
        assertEquals(OperationState.Done(null), ledger.record("a")?.state)
    }

    @Test
    fun `long running stays open until finished`() {
        var ledger = OperationLedger().sent("a", "p", "o", now).answered("a", OpResult.Ok(null, "c"), longRunning = true)
        assertEquals(OperationState.Running(null), ledger.record("a")?.state)
        ledger = ledger.apply(event("op.progress", "c", progress = 0.5))
        assertEquals(OperationState.Running(0.5), ledger.record("a")?.state)
        ledger = ledger.apply(event("op.finished", "c", outcome = "refused", reason = "Clouds"))
        assertEquals(OperationState.Refused("Clouds"), ledger.record("a")?.state)
    }

    @Test
    fun `op finished can overtake the HTTP answer`() {
        val ledger = OperationLedger().sent("a", "p", "o", now)
            .apply(event("op.finished", "c", request = "a", outcome = "ok"))
            .answered("a", OpResult.Ok(null, "c"), longRunning = true)
        assertEquals(OperationState.Done(null), ledger.record("a")?.state)
    }

    @Test
    fun `a finish seen before the answer that names it is applied by that answer`() {
        val ledger = OperationLedger().sent("a", "p", "o", now)
            .apply(event("op.finished", "c", outcome = "busy", reason = "Later"))
            .answered("a", OpResult.Ok(null, "c"), longRunning = true)
        assertEquals(OperationState.Busy("Later"), ledger.record("a")?.state)
    }

    @Test
    fun `a long running answer with no correlation id can not stay open forever`() {
        val ledger = OperationLedger().sent("a", "p", "o", now).answered("a", OpResult.Ok(null, null), longRunning = true)
        assertEquals(OperationState.Done(null), ledger.record("a")?.state)
    }

    @Test
    fun `the ledger is bounded`() {
        var ledger = OperationLedger()
        repeat(OperationLedger.LIMIT + 20) { ledger = ledger.sent("k$it", "p", "o", now) }
        assertEquals(OperationLedger.LIMIT, ledger.records.size)
        assertNull(ledger.record("k0"))
        assertNotNull(ledger.record("k${OperationLedger.LIMIT + 19}"))
    }
}

/** The Kotlin side of iOS `HoldTests` (MAPP-DEV-030, MAPP-CTL-060). */
class HoldTest {
    private val clock = TestClock(Instant.ofEpochSecond(1_800_000_000))
    private val calls = CopyOnWriteArrayList<String>()
    private val scope = CoroutineScope(Dispatchers.Default)

    private fun make(beat: OpResult = OpResult.Ok(JsonPrimitive(true), null)) = HoldSession(
        family = "focus", scope = scope, clock = clock,
        invoke = { name, _ ->
            calls += name
            when {
                name.endsWith("start") -> OpResult.Ok(obj("hold_id" to str("h")), null)
                name.endsWith("beat") -> beat
                else -> OpResult.Ok(JsonPrimitive(true), null)
            }
        },
    )

    private suspend fun beat(until: Int) {
        eventually {
            clock.advance(HoldSession.BEAT_INTERVAL)
            calls.count { it.endsWith("beat") } >= until
        }
    }

    @Test
    fun `beats flow until release`() = runBlocking<Unit> {
        val session = make()
        session.start(mapOf("direction" to num(1)))
        assertEquals(HoldState.Holding, session.state)
        beat(2)
        session.stop(HoldStop.Released)
        assertEquals("focus.hold_stop", calls.last())
        assertEquals(HoldState.Stopped(HoldStop.Released), session.state)
        scope.cancel()
    }

    @Test
    fun `a beat the Master no longer recognises ends the hold`() = runBlocking<Unit> {
        val session = make(beat = OpResult.Ok(JsonPrimitive(false), null))
        session.start(emptyMap())
        beat(1)
        assertTrue(eventually { session.state == HoldState.Stopped(HoldStop.BeatFailed) })
        scope.cancel()
    }

    @Test
    fun `a lost beat stops beating and sends no stop it can't deliver`() = runBlocking<Unit> {
        val session = make(beat = OpResult.ConnectionLost)
        session.start(emptyMap())
        beat(1)
        assertTrue(eventually { session.state == HoldState.Stopped(HoldStop.ConnectionLost) })
        assertFalse("focus.hold_stop" in calls)
        scope.cancel()
    }

    @Test
    fun `stopping twice sends one stop`() = runBlocking<Unit> {
        val session = make()
        session.start(emptyMap())
        session.stop(HoldStop.Background)
        session.stop(HoldStop.Released)
        assertEquals(1, calls.count { it == "focus.hold_stop" })
        assertEquals(HoldState.Stopped(HoldStop.Background), session.state)
        scope.cancel()
    }

    @Test
    fun `released while the start is still in flight the motion is still stopped`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val session = HoldSession(
            family = "focus", scope = scope, clock = clock,
            invoke = { name, _ ->
                calls += name
                if (name.endsWith("start")) gate.await()
                OpResult.Ok(if (name.endsWith("start")) obj("hold_id" to str("h")) else JsonPrimitive(true), null)
            },
        )
        val starting = scope.async { session.start(emptyMap()) }
        assertTrue(eventually { calls.toList() == listOf("focus.hold_start") })
        session.stop(HoldStop.Released)
        gate.complete(Unit)
        starting.await()
        assertEquals("focus.hold_stop", calls.last())
        assertEquals(HoldState.Stopped(HoldStop.Released), session.state)
        scope.cancel()
    }

    @Test
    fun `refused at the start`() = runBlocking<Unit> {
        val session = HoldSession(
            family = "focus", scope = scope, clock = clock,
            invoke = { _, _ -> OpResult.Refused("Take the lease first", null, null, false) },
        )
        session.start(emptyMap())
        assertEquals(HoldState.Stopped(HoldStop.Refused("Take the lease first")), session.state)
        scope.cancel()
    }
}

/** What actually goes on the wire for an operation, which the shared vectors only check as a subset. */
class InvokeWireTest {
    private val bodies = CopyOnWriteArrayList<String>()
    private val paths = CopyOnWriteArrayList<String>()

    private fun api(status: Int = 200, answer: String = """{"ok":true}"""): MasterApi {
        val client = OkHttpClient.Builder().addInterceptor(DropNullFields).addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                bodies += Buffer().also { request.body?.writeTo(it) }.readUtf8()
                paths += request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("t")
                    .body(answer.toResponseBody("application/json".toMediaType())).build()
            },
        ).build()
        return MasterApi("https://master.local:8765", client, ClientIdentity("T", "0.1.0", "android", "phone"))
    }

    @Test
    fun `an ordinary request carries no confirmed flag, not even a null one`() = runBlocking<Unit> {
        api().invoke("p1", "mount.track", mapOf("enabled" to JsonPrimitive(true)), "k1")
        val sent = Json.parseToJsonElement(bodies.single()) as JsonObject
        assertEquals(setOf("args"), sent.keys)
    }

    @Test
    fun `an emergency request says it was confirmed`() = runBlocking<Unit> {
        api().invoke("p1", "system.stop_everything", emptyMap(), "k1", emergencyConfirmed = true)
        assertEquals(JsonPrimitive(true), (Json.parseToJsonElement(bodies.single()) as JsonObject)["confirmed"])
    }

    @Test
    fun `an answer that is not the Master's is never shown as success`() = runBlocking<Unit> {
        assertIs<OpResult.Unavailable>(api(status = 502, answer = "<html>Bad gateway</html>").invoke("p1", "mount.track", emptyMap(), "k1"))
        assertIs<OpResult.Unavailable>(api(status = 200, answer = "not json").invoke("p1", "mount.track", emptyMap(), "k1"))
    }

    @Test
    fun `a typed refusal keeps the Master's words and the lease holder`() = runBlocking<Unit> {
        val body = """{"error":"refused","reason":"Desktop holds the lease.","detail":{"kind":"lease_held","holder":"Desktop","can_take":true}}"""
        val result = api(status = 409, answer = body).invoke("p1", "mount.sync", emptyMap(), "k1")
        assertEquals(OpResult.Refused("Desktop holds the lease.", "lease_held", "Desktop", true), result)
    }

    @Test
    fun `a confirmation with no token is not a confirmation`() = runBlocking<Unit> {
        val result = api(status = 409, answer = """{"error":"confirmation_required","reason":"Sure?"}""").invoke("p1", "mount.park", emptyMap(), "k1")
        assertIs<OpResult.Unavailable>(result)
    }

    @Test
    fun `the answer to a lost connection is that the outcome is unknown`() = runBlocking<Unit> {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { throw java.io.IOException("reset") }).build()
        val api = MasterApi("https://master.local:8765", client, ClientIdentity("T", "0.1.0", "android", "phone"))
        assertEquals(OpResult.ConnectionLost, api.invoke("p1", "mount.slew", emptyMap(), "k1"))
    }
}
