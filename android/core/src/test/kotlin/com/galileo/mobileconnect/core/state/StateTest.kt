package com.galileo.mobileconnect.core.state

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Kotlin side of iOS `StateTests.swift`: same cases, so the two reducers agree (S-STATE, MAPP-PLT-040). */
class StateTest {
    private val t0 = Instant.ofEpochSecond(1_800_000_000)

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
    private fun str(v: String) = JsonPrimitive(v)
    private fun num(v: Number) = JsonPrimitive(v)
    private fun bool(v: Boolean) = JsonPrimitive(v)

    private fun reduce(messages: List<StreamMessage>, from: LiveState = LiveState()) =
        messages.fold(from, LiveState::reduce)

    private fun frame(seq: Int, event: StreamEvent) = StreamMessage.Frame(seq, event)
    private fun delta(pier: String, key: String, value: JsonElement, at: Instant = t0) =
        StreamEvent.Delta(pier, key, value, at)

    private val snapshotted = listOf(
        StreamMessage.Hello("e", 0),
        frame(1, StreamEvent.Snapshot(t0, mapOf("p" to obj("mount" to obj("tracking" to bool(true)), "weather.wind" to num(3))))),
    )

    @Test
    fun `MAPP-OVR-050 snapshot then deltas, with dotted keys either nested or flat`() {
        val state = reduce(snapshotted + frame(2, delta("p", "mount.tracking", bool(false))))
        assertEquals(LivePhase.Live, state.phase)
        assertEquals(bool(false), state.piers["p"]?.values?.get("mount.tracking")?.value)
        assertEquals(num(3), state.piers["p"]?.values?.get("weather.wind")?.value)
    }

    @Test
    fun `MAPP-OVR-080 the node health report goes stale when the Master stops sending it`() {
        val report = obj("cpu_percent" to num(23), "status" to str("ok"))
        val state = reduce(snapshotted + frame(2, delta("p", "node.health", report)))
        val fresh = state.view("p", "node.health.cpu_percent", t0.plusSeconds(10), connected = true)
        val silent = state.view("p", "node.health.cpu_percent", t0.plusSeconds(120), connected = true)
        assertEquals(false, fresh?.stale)
        assertEquals(23.0, fresh?.value.double)
        assertEquals(true, silent?.stale)
        // unchanged state does not age
        assertEquals(false, state.view("p", "mount.tracking", t0.plusSeconds(120), connected = true)?.stale)
    }

    @Test
    fun `a delta replaces the subtree at its key`() {
        val state = reduce(
            listOf(
                StreamMessage.Hello("e", 0),
                frame(1, StreamEvent.Snapshot(t0, mapOf("p" to obj("safety.inputs" to obj("Rain" to str("Dry"), "Wind" to str("8")))))),
                frame(2, delta("p", "safety.inputs", obj("Rain" to str("Wet")))),
            ),
        )
        assertEquals(str("Wet"), state.piers["p"]?.values?.get("safety.inputs.Rain")?.value)
        assertNull(state.piers["p"]?.values?.get("safety.inputs.Wind"))
    }

    @Test
    fun `a buffered delta older than the snapshot must not overwrite it`() {
        val state = reduce(
            listOf(
                StreamMessage.Hello("e", 0),
                frame(1, delta("p", "a", str("old"), t0)),
                frame(2, StreamEvent.Snapshot(t0.plusSeconds(1), mapOf("p" to obj("a" to str("new"))))),
            ),
        )
        assertEquals(str("new"), state.piers["p"]?.values?.get("a")?.value)
    }

    @Test
    fun `a buffered delta newer than the snapshot is applied after it`() {
        val state = reduce(
            listOf(
                StreamMessage.Hello("e", 0),
                frame(1, delta("p", "a", str("later"), t0.plusSeconds(2))),
                frame(2, StreamEvent.Snapshot(t0.plusSeconds(1), mapOf("p" to obj("a" to str("snap"))))),
            ),
        )
        assertEquals(str("later"), state.piers["p"]?.values?.get("a")?.value)
    }

    @Test
    fun `a replayed frame is ignored`() {
        val state = reduce(
            snapshotted + listOf(
                frame(2, delta("p", "a", num(2))),
                StreamMessage.Hello("e", 2),
                frame(2, delta("p", "a", num(99))),
            ),
        )
        assertEquals(num(2), state.piers["p"]?.values?.get("a")?.value)
        assertEquals(2, state.lastSeq)
    }

    @Test
    fun `MAPP-OVR-040 resync discards values and keeps stated access`() {
        val state = reduce(
            snapshotted + listOf(
                frame(2, StreamEvent.Access("p", "view", false, "offline")),
                StreamMessage.Resync("e2", 50),
            ),
        )
        assertEquals(LivePhase.Resyncing, state.phase)
        assertEquals(true, state.piers["p"]?.values?.isEmpty())
        assertEquals("offline", state.piers["p"]?.reason)
        assertEquals("e2", state.epoch)
    }

    @Test
    fun `MAPP-OVR-040 anything is stale while the link is down and only moving values age while it is up`() {
        val state = reduce(snapshotted)
        val later = t0.plusSeconds(3600)
        assertFalse(assertNotNull(state.view("p", "mount.tracking", later, connected = true)).stale)
        assertTrue(assertNotNull(state.view("p", "mount.tracking", t0, connected = false)).stale)
        assertTrue(assertNotNull(state.view("p", "weather.wind", later, connected = true)).stale)
        assertEquals(Duration.ofSeconds(5), assertNotNull(state.view("p", "weather.wind", t0.plusSeconds(5), connected = true)).age)
        // A Master clock ahead of the device must not produce a negative age.
        assertEquals(Duration.ZERO, assertNotNull(state.view("p", "weather.wind", t0.plusSeconds(-9), connected = true)).age)
    }

    @Test
    fun `MAPP-OVR-020 activity deltas build lanes and repeats do not add segments`() {
        val state = reduce(
            snapshotted + listOf(
                frame(2, delta("p", "activity.mount", str("Slewing"), t0)),
                frame(3, delta("p", "activity.mount", str("Slewing"), t0.plusSeconds(1))),
                frame(4, delta("p", "activity.mount", str("Tracking"), t0.plusSeconds(2))),
            ),
        )
        assertEquals(listOf("Slewing", "Tracking"), state.piers["p"]?.lanes?.get("mount")?.map { it.state })
    }

    @Test
    fun `MAPP-GUI-020 the guide trace keeps a bounded history`() {
        val frames = (2..(LiveState.SERIES_LIMIT + 20)).map { frame(it, delta("p", "guiding.error", obj("ra" to num(0.1)))) }
        assertEquals(LiveState.SERIES_LIMIT, reduce(snapshotted + frames).piers["p"]?.series?.get("guiding.error")?.size)
    }

    @Test
    fun `MAPP-OVR-060 a new alert goes first and a repeated id replaces`() {
        val a = AlertEvent("1", "info", t0, message = "first")
        val b = AlertEvent("2", "critical", t0, message = "second")
        val state = reduce(listOf(frame(1, StreamEvent.Alert(a)), frame(2, StreamEvent.Alert(b)), frame(3, StreamEvent.Alert(a))))
        assertEquals(listOf("1", "2"), state.alerts.map { it.id })
    }

    @Test
    fun `messages decode and an unknown type is not an error`() {
        val unknown = assertNotNull(StreamMessage.parse("""{"seq":3,"data":{"type":"brand_new"}}"""))
        assertEquals(frame(3, StreamEvent.Unknown), unknown)
        assertNull(StreamMessage.parse("not json"))
        val python = assertNotNull(
            StreamMessage.parse(
                """{"seq":1,"data":{"type":"delta","pier":"p","key":"k","value":1,"as_of":"2026-11-06T02:00:00.123456+00:00"}}""",
            ),
        )
        assertIs<StreamEvent.Delta>(assertIs<StreamMessage.Frame>(python).event) // a Python isoformat() timestamp must decode
    }

    @Test
    fun `display text matches the iOS wording`() {
        assertEquals("—", JsonNull.displayText())
        assertEquals("Yes", bool(true).displayText())
        assertEquals("No", bool(false).displayText())
        assertEquals("42", num(42.0).displayText())
        assertEquals("0.5", num(0.5).displayText())
        assertEquals("a: 1, b: x", obj("b" to str("x"), "a" to num(1)).displayText())
    }
}
