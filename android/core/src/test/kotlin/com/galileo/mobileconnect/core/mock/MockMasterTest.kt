package com.galileo.mobileconnect.core.mock

import com.galileo.mobileconnect.core.api.PierInfo
import com.galileo.mobileconnect.core.api.Role
import com.galileo.mobileconnect.core.control.HoldState
import com.galileo.mobileconnect.core.control.OperationState
import com.galileo.mobileconnect.core.control.RequestAnswer
import com.galileo.mobileconnect.core.link.Backoff
import com.galileo.mobileconnect.core.link.ClientIdentity
import com.galileo.mobileconnect.core.link.InMemorySecureStore
import com.galileo.mobileconnect.core.link.MasterAddress
import com.galileo.mobileconnect.core.link.PairingFlow
import com.galileo.mobileconnect.core.link.PairingStatus
import com.galileo.mobileconnect.core.session.ObservatorySession
import com.galileo.mobileconnect.core.session.SessionSnapshot
import com.galileo.mobileconnect.core.session.SessionStop
import com.galileo.mobileconnect.core.state.LivePhase
import com.galileo.mobileconnect.core.state.string
import com.galileo.mobileconnect.core.vectors.VectorFiles
import com.galileo.mobileconnect.core.vectors.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What `:app` does with `android.graphics`, done with the JDK so that the stretch and the tiles can be checked here. */
object AwtRenderer : ImageRenderer {
    private fun encode(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()

    override fun stretched(jpeg: ByteArray, stretch: Double): ByteArray {
        val source = ImageIO.read(ByteArrayInputStream(jpeg))
        val power = 2.0.pow((50 - stretch) / 25)
        val out = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val rgb = source.getRGB(x, y)
            fun channel(shift: Int) = (255 * (((rgb shr shift) and 0xFF) / 255.0).pow(power)).toInt().coerceIn(0, 255)
            out.setRGB(x, y, (channel(16) shl 16) or (channel(8) shl 8) or channel(0))
        }
        return encode(out)
    }

    override fun tile(jpeg: ByteArray, level: Int, x: Int, y: Int): ByteArray? {
        val source = ImageIO.read(ByteArrayInputStream(jpeg))
        val scale = 1.0 / (1 shl maxOf(0, level))
        val width = (source.width * scale).toInt()
        val height = (source.height * scale).toInt()
        val left = x * 256
        val top = y * 256
        if (left >= width || top >= height) return null
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply { drawImage(source, 0, 0, width, height, null); dispose() }
        return encode(scaled.getSubimage(left, top, min(256, width - left), min(256, height - top)))
    }
}

/** The real client against the scripted Master: what demonstration mode runs (S-MOCK, MAPP-CON-110). */
class MockMasterTest {
    private val identity = ClientIdentity("Vector iPad", "0.1.0", "android", "tablet")
    private val scope = CoroutineScope(Dispatchers.Default)
    private val masters = mutableListOf<MockMaster>()

    @AfterTest
    fun tearDown() {
        masters.forEach { it.shutDown() }
        scope.cancel()
    }

    private fun connect(scenario: String, timeScale: Double = 1.0, onUnpaired: () -> Unit = {}): Pair<ObservatorySession, MockMaster> {
        val master = MockMaster(scenario, scope, timeScale = timeScale, renderer = AwtRenderer).also { masters += it }
        val session = ObservatorySession(
            master.connection(identity, MockMaster.TOKEN), scope, backoff = Backoff(initial = 0.02, jitter = { 1.0 }), onUnpaired = onUnpaired,
        )
        return session to master
    }

    private val ObservatorySession.current: SessionSnapshot get() = snapshot.value

    private suspend fun live(session: ObservatorySession) = eventually(5000) {
        session.current.let { it.live.phase == LivePhase.Live && it.isConnected }
    }

    private fun value(session: ObservatorySession, key: String, pier: String = "p1") = session.current.value(key, pier, Instant.now())?.value

    private val scenarioNames = listOf(
        "normal-night", "safety-event", "meridian-flip", "lease-contention", "dropped-stream", "revoked-pairing",
        "first-pairing-refused", "viewer", "pi-class-master", "older-master", "newer-master", "certificate-change",
        "focus-run", "polar-alignment", "session-run",
    )

    @Test
    fun `every scenario loads`() {
        scenarioNames.forEach { masters += MockMaster(it, scope) }
    }

    @Test
    fun `every scenario file is listed above`() {
        val files = java.io.File(VectorFiles.directory.parentFile, "scenarios").listFiles { f -> f.extension == "json" && !f.name.startsWith("catalogue") }!!
        assertEquals(scenarioNames.toSet(), files.map { it.nameWithoutExtension }.toSet())
    }

    @Test
    fun `effects only name operations the catalogue has`() {
        val scenario = MockMaster.loadScenario("normal-night")
        val dir = java.io.File(VectorFiles.directory.parentFile, "scenarios")
        val names = listOf("catalogue.json", "catalogue.planned.json").flatMap { file ->
            ((Json.parseToJsonElement(java.io.File(dir, file).readText()) as JsonObject)["operations"] as JsonArray).mapNotNull { (it as JsonObject)["name"].string }
        }.toSet()
        for (name in (scenario["effects"] as JsonObject).keys) assertTrue(name in names, name)
    }

    @Test
    fun `MAPP-CON-030, MAPP-CON-110 pairing against the mock gives a usable token, once`() = runBlocking<Unit> {
        val master = MockMaster("normal-night", scope, timeScale = 100.0).also { masters += it }
        val store = InMemorySecureStore()
        val flow = PairingFlow(master.connection(identity).api, store, MasterAddress("demo"), "Demo", pollIntervalSeconds = 0.01)
        val waiting = assertIs<PairingStatus.Waiting>(flow.start())
        assertEquals(6, waiting.code.length)
        assertEquals(MockMaster.FINGERPRINT, waiting.fingerprint)
        assertIs<PairingStatus.Paired>(flow.waitForAnswer())
        assertEquals(MockMaster.TOKEN, store.all().first().token)
    }

    @Test
    fun `MAPP-CON-030 a refusal stores nothing`() = runBlocking<Unit> {
        val master = MockMaster("first-pairing-refused", scope, timeScale = 100.0).also { masters += it }
        val store = InMemorySecureStore()
        val flow = PairingFlow(master.connection(identity).api, store, MasterAddress("demo"), "Demo", pollIntervalSeconds = 0.01)
        flow.start()
        assertEquals(PairingStatus.Refused, flow.waitForAnswer())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `MAPP-OVR-010, MAPP-OVR-070 connecting shows the observatory promptly`() = runBlocking<Unit> {
        val (session, _) = connect("normal-night")
        session.start()
        val started = System.nanoTime()
        assertTrue(live(session))
        assertTrue((System.nanoTime() - started) / 1e9 < 5)
        val snapshot = session.current
        assertEquals(Role.Operator, snapshot.role)
        assertEquals(listOf("p1", "p2"), snapshot.piers.map { it.id }.sorted())
        assertTrue(snapshot.catalogues["p1"]!!.isNotEmpty())
        assertEquals(JsonPrimitive(true), value(session, "mount.tracking"))
        assertEquals(true, snapshot.capabilities?.skyStreamAvailable)
        assertEquals(false, snapshot.operation("dome.open", "p2")?.available)
        assertTrue(snapshot.live.alerts.isNotEmpty())
        session.stop()
    }

    @Test
    fun `MAPP-CTL-020 lease, operation, effect, release`() = runBlocking<Unit> {
        val (session, _) = connect("normal-night", timeScale = 100.0)
        session.start()
        assertTrue(live(session))

        // Without the lease: the Master's reason, and no retry.
        val refused = assertIs<RequestAnswer.Sent>(session.request("p1", "filter.select", mapOf("name" to JsonPrimitive("Ha"))))
        assertEquals(OperationState.Refused("Take control of this Pier first."), refused.record.state)

        assertNull(session.takeLease("p1"))
        assertEquals(SessionSnapshot.Lease.Mine, session.current.lease("p1"))
        val done = assertIs<RequestAnswer.Sent>(session.request("p1", "filter.select", mapOf("name" to JsonPrimitive("Ha"))))
        assertIs<OperationState.Done>(done.record.state)
        assertTrue(eventually { value(session, "filter.current") == JsonPrimitive("Ha") })
        session.releaseLease("p1")
        assertEquals(SessionSnapshot.Lease.Free, session.current.lease("p1"))
        session.stop()
    }

    @Test
    fun `ADR-APP-005 the Master's confirmation, with a token bound to the operation`() = runBlocking<Unit> {
        val (session, _) = connect("normal-night", timeScale = 100.0)
        session.start()
        assertTrue(live(session))
        session.takeLease("p1")
        val question = assertIs<RequestAnswer.Confirm>(session.request("p1", "mount.park")).request
        assertTrue(question.consequence!!.contains("park position"))
        assertNotNull(question.token)
        assertEquals(JsonPrimitive(false), value(session, "mount.parked"))
        val sent = assertIs<RequestAnswer.Sent>(session.confirm(question))
        assertIs<OperationState.Done>(sent.record.state)
        assertTrue(eventually { value(session, "mount.parked") == JsonPrimitive(true) })
        session.stop()
    }

    @Test
    fun `MAPP-CTL-020 another client holds the lease, and taking it over needs the offered token`() = runBlocking<Unit> {
        val (session, _) = connect("lease-contention")
        session.start()
        assertTrue(live(session))
        assertEquals(SessionSnapshot.Lease.Other("Desktop"), session.current.lease("p1"))
        val refusal = assertNotNull(session.takeLease("p1"))
        assertEquals("Desktop", refusal.holder)
        val token = assertNotNull(refusal.takeOverToken)
        assertEquals(SessionSnapshot.Lease.Other("Desktop"), session.current.lease("p1"))
        assertNull(session.takeLease("p1", token))
        assertEquals(SessionSnapshot.Lease.Mine, session.current.lease("p1"))
        session.stop()
    }

    @Test
    fun `MAPP-EMG-020 emergency needs no lease and lists its parts`() = runBlocking<Unit> {
        val (session, _) = connect("normal-night")
        session.start()
        assertTrue(live(session))
        val sent = assertIs<RequestAnswer.Sent>(session.emergency("p1", "system.stop_everything"))
        val done = assertIs<OperationState.Done>(sent.record.state)
        assertTrue(((done.value as JsonObject)["parts"] as JsonArray).isNotEmpty())
        session.stop()
    }

    @Test
    fun `MAPP-CON-070 the scripted drop replays, and the scripted restart resynchronises`() = runBlocking<Unit> {
        val (session, master) = connect("normal-night")
        session.start()
        assertTrue(live(session))
        val key = "camera.state"
        master.dropStream()
        assertTrue(live(session))
        assertNotNull(value(session, key))
        val epoch = session.current.live.epoch
        master.forceResync()
        assertTrue(eventually { session.current.live.epoch != epoch })
        assertTrue(live(session))
        assertNotNull(value(session, key))
        session.stop()
    }

    @Test
    fun `MAPP-CON-060 revocation ends the session and tells the owner of the token`() = runBlocking<Unit> {
        val told = java.util.concurrent.atomic.AtomicBoolean(false)
        val (session, master) = connect("normal-night", onUnpaired = { told.set(true) })
        session.start()
        assertTrue(live(session))
        master.revoke()
        assertTrue(eventually { session.current.stop is SessionStop.Unpaired })
        assertTrue(told.get())
    }

    @Test
    fun `MAPP-CTL-010 a Viewer's grant reaches the role, and an unreachable Pier carries its reason`() = runBlocking<Unit> {
        val (session, _) = connect("viewer")
        session.start()
        assertTrue(live(session))
        val snapshot = session.current
        assertEquals(Role.Viewer, snapshot.role)
        assertEquals("Spoke offline since 21:14", snapshot.unavailableReason("p2"))
        assertEquals(PierInfo.Access.View, snapshot.access("p1"))
        assertIs<RequestAnswer.NotAllowed>(session.request("p1", "mount.track", mapOf("enabled" to JsonPrimitive(true))))
        session.stop()
    }

    @Test
    fun `MAPP-CON-080, MAPP-PLT-090 a refused handshake says which side to update and stops`() = runBlocking<Unit> {
        for ((scenario, updateApp) in listOf("older-master" to false, "newer-master" to true)) {
            val (session, _) = connect(scenario)
            session.start()
            assertTrue(eventually { session.current.stop is SessionStop.Refused }, scenario)
            val stop = session.current.stop as SessionStop.Refused
            assertEquals(updateApp, stop.updateApp, scenario)
            assertTrue(stop.message.isNotEmpty())
            assertTrue(session.current.catalogues.isEmpty())
            session.stop()
        }
    }

    @Test
    fun `MAPP-CON-090 a Pi-class Master's missing feature carries its reason`() = runBlocking<Unit> {
        val (session, _) = connect("pi-class-master")
        session.start()
        assertTrue(live(session))
        val capabilities = session.current.capabilities
        assertEquals(false, capabilities?.skyStreamAvailable)
        assertEquals("master_hardware", capabilities?.skyStreamReason)
        session.stop()
    }

    @Test
    fun `MAPP-DEV-030 a hold moves, and stops when released`() = runBlocking<Unit> {
        val (session, _) = connect("normal-night")
        session.start()
        assertTrue(live(session))
        session.takeLease("p1")
        session.startHold("p1", "focus", mapOf("direction" to JsonPrimitive(1)))
        assertEquals(HoldState.Holding, session.current.hold)
        assertTrue(eventually { value(session, "focus.moving") == JsonPrimitive(true) })
        session.stopHold()
        assertTrue(eventually { value(session, "focus.moving") == JsonPrimitive(false) })
        session.stop()
    }

    @Test
    fun `MAPP-IMG-030, MAPP-IMG-040 a preview comes from the Master, and the stretch reaches it`() = runBlocking<Unit> {
        val (session, _) = connect("normal-night")
        session.start()
        assertTrue(live(session))
        val plain = assertNotNull(session.preview("p1", 800, 50))
        val stretched = assertNotNull(session.preview("p1", 800, 80))
        assertTrue(plain.data.size > 1000)
        assertFalse(plain.data.contentEquals(stretched.data))
        assertEquals(1200 to 800, plain.frameWidth to plain.frameHeight) // tile addressing needs the frame's size
        assertTrue((session.tile("p1", 0, 1, 1, 50)?.size ?: 0) > 100)
        assertNull(session.tile("p1", 0, 99, 99, 50))
        session.stop()
    }
}
