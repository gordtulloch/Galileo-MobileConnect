package com.galileo.mobileconnect.quest

import com.galileo.mobileconnect.core.sky.SkyGeometry
import com.galileo.mobileconnect.core.sky.SkyObject
import com.galileo.mobileconnect.core.sky.SkySite
import com.galileo.mobileconnect.core.sky.Vec3
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun near(a: Float, b: Float, tolerance: Float = 1e-3f) = abs(a - b) <= tolerance
private fun assertNear(expected: Float, actual: Float, tolerance: Float = 1e-3f) =
    assertTrue(near(expected, actual, tolerance), "expected $expected, was $actual")

class GeometryTest {
    private val face = Quad(Vec3(0f, 0f, -1f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), 2f, 1f, 2000, 1000)

    @Test
    fun `a ray through the middle lands in the middle of the surface`() {
        val hit = assertNotNull(face.intersect(Ray(Vec3(0f, 0f, 0f), Vec3(0f, 0f, -1f))))
        assertNear(1f, hit.distance)
        assertNear(1000f, hit.xPixels, 0.5f)
        assertNear(500f, hit.yPixels, 0.5f)
    }

    @Test
    fun `right is more x and up is less y, as pixels count from the top left`() {
        val hit = assertNotNull(face.intersect(Ray(Vec3(0f, 0f, 0f), Vec3(0.5f, 0.25f, -1f))))
        assertTrue(hit.xPixels > 1000f && hit.yPixels < 500f)
    }

    @Test
    fun `off the edge, behind, parallel and from the back are all misses`() {
        assertNull(face.intersect(Ray(Vec3(0f, 0f, 0f), Vec3(2f, 0f, -1f))))
        assertNull(face.intersect(Ray(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 1f))))
        assertNull(face.intersect(Ray(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f))))
        assertNull(face.intersect(Ray(Vec3(0f, 0f, -2f), Vec3(0f, 0f, 1f))), "the back of a pane is not touchable")
    }

    @Test
    fun `horizon and world agree that north is in front and up is up`() {
        val north = World.horizonToWorld(horizonDirection(0.0, 0.0))
        assertNear(-1f, north.z)
        assertNear(0f, north.y)
        val east = World.horizonToWorld(horizonDirection(0.0, 90.0))
        assertNear(1f, east.x)
        val zenith = World.horizonToWorld(horizonDirection(90.0, 0.0))
        assertNear(1f, zenith.y)
        val back = World.worldToHorizon(World.horizonToWorld(Vec3(0.3f, 0.5f, 0.8f)))
        assertNear(0.3f, back.x)
        assertNear(0.5f, back.y)
        assertNear(0.8f, back.z)
    }
}

class DockAndPanesTest {
    @Test
    fun `the dock appears in front of and below the gaze, level, facing the user`() {
        for ((fx, fz) in listOf(0f to -1f, 1f to 0f, 0.6f to -0.8f, -1f to 0f)) {
            val head = Vec3(0f, 1.6f, 0f)
            val placement = Dock.placeInFrontOf(head, Vec3(fx, 0.3f, fz)) // looking a little up
            val quad = Dock.quad(placement)
            assertNear(head.y - Dock.BELOW_GAZE, placement.center.y)
            // The face's normal points back at the user, and the face stays vertical.
            val toUser = (head - placement.center).let { Vec3(it.x, 0f, it.z) }.normalized()
            assertNear(1f, quad.normal.dot(toUser), 1e-3f)
            assertNear(0f, quad.normal.y)
            assertNear(0f, quad.right.y)
        }
    }

    @Test
    fun `looking straight up still places the dock somewhere sensible`() {
        val placement = Dock.placeInFrontOf(Vec3(0f, 1.6f, 0f), Vec3(0f, 1f, 0f))
        assertNear(Dock.DISTANCE, Vec3(placement.center.x, 0f, placement.center.z).length(), 1e-3f)
    }

    @Test
    fun `the dock has a button for each operating area and none for the sky`() {
        assertEquals(10, DockArea.entries.size)
        assertTrue(DockArea.entries.none { it.label == "Sky" })
        assertEquals(DockArea.Overview, Dock.areaAt(Dock.STATUS_PIXELS + 10f, 100f))
        assertEquals(DockArea.Equipment, Dock.areaAt(Dock.STATUS_PIXELS + Dock.CELL_PIXELS + 10f, 100f))
        assertEquals(DockArea.Settings, Dock.areaAt(Dock.STATUS_PIXELS + 9 * Dock.CELL_PIXELS + 10f, 100f))
        assertNull(Dock.areaAt(10f, 100f), "the status cell is not an area")
        assertNull(Dock.areaAt(Dock.STATUS_PIXELS + 10 * Dock.CELL_PIXELS + 10f, 100f))
    }

    private fun stack(): PaneStack {
        val dock = Dock.placeInFrontOf(Vec3(0f, 1.6f, 0f), Vec3(0f, 0f, -1f))
        return PaneStack { dock }
    }

    @Test
    fun `pressing an area opens its pane, and pressing it again only brings it to the front`() {
        val panes = stack()
        panes.press(DockArea.Imaging)
        panes.press(DockArea.Focus)
        panes.press(DockArea.Imaging)
        assertEquals(listOf(DockArea.Focus, DockArea.Imaging), panes.open.map { it.area })
        assertTrue(panes.isOpen(DockArea.Focus))
        panes.close(DockArea.Focus)
        assertEquals(listOf(DockArea.Imaging), panes.open.map { it.area })
    }

    @Test
    fun `a pane is resized only within limits`() {
        val panes = stack()
        panes.press(DockArea.Overview)
        panes.resize(DockArea.Overview, 99f, 0.01f)
        val pane = panes.open.single()
        assertTrue(pane.widthMetres <= 2.4f && pane.heightMetres >= 0.35f)
    }

    @Test
    fun `no pane covers the dock, and the front pane is the one that is touched`() {
        val panes = stack()
        val dock = Dock.placeInFrontOf(Vec3(0f, 1.6f, 0f), Vec3(0f, 0f, -1f))
        panes.press(DockArea.Imaging)
        panes.press(DockArea.Focus)
        val head = Vec3(0f, 1.6f, 0f)
        // A ray at the dock itself hits the dock.
        assertIs<PaneStack.Target.OnDock>(panes.pick(Ray(head, dock.center - head)))
        // A ray at a pane's centre hits that pane, not the dock.
        val front = panes.open.last()
        val target = assertIs<PaneStack.Target.OnPane>(panes.pick(Ray(head, front.placement.center - head)))
        assertEquals(front.area, target.pane.area)
        // Straight up, nothing.
        assertNull(panes.pick(Ray(head, Vec3(0f, 1f, 0f))))
    }
}

class PickerTest {
    private val site = SkySite(latitude = 49.9, longitude = -97.1)
    private val at = Instant.ofEpochSecond(1_793_930_400)

    /** An object exactly at a place in the sky, as the Master would send it (J2000). */
    private fun objectAt(altitude: Double, azimuth: Double, index: Int, kind: Int = 1, magnitude: Float = 3f): SkyObject {
        val (ra, dec) = SkyGeometry.equatorial(altitude, azimuth, site, at)
        return SkyObject(index, ra.toFloat(), dec.toFloat(), magnitude, kind)
    }

    private fun rayAt(altitude: Double, azimuth: Double) =
        Ray(Vec3(0f, 1.6f, 0f), World.horizonToWorld(horizonDirection(altitude, azimuth)))

    @Test
    fun `pointing at an object picks it, and a little off picks nothing`() {
        val m42 = objectAt(40.0, 200.0, 7)
        val pick = assertIs<Pickable.DeepSky>(Picker.pick(rayAt(40.0, 200.5), 1f, site, at, listOf(m42), emptyList(), emptyList()))
        assertEquals(7, pick.object_.index)
        assertNull(Picker.pick(rayAt(40.0, 215.0), 1f, site, at, listOf(m42), emptyList(), emptyList()))
    }

    @Test
    fun `zooming in narrows the reach so a crowded field can be picked one at a time`() {
        val a = objectAt(40.0, 200.0, 1)
        val pointedJustOff = rayAt(40.0, 201.8)
        assertNotNull(Picker.pick(pointedJustOff, 1f, site, at, listOf(a), emptyList(), emptyList()))
        assertNull(Picker.pick(pointedJustOff, 10f, site, at, listOf(a), emptyList(), emptyList()))
    }

    @Test
    fun `a deep-sky object wins over a star, and a star is picked when nothing else is near`() {
        val dso = objectAt(40.0, 200.0, 1, kind = 1)
        val star = objectAt(40.0, 200.2, 2, kind = 0, magnitude = 1f)
        assertIs<Pickable.DeepSky>(Picker.pick(rayAt(40.0, 200.1), 1f, site, at, listOf(dso), listOf(star), emptyList()))
        assertIs<Pickable.Star>(Picker.pick(rayAt(40.0, 200.1), 1f, site, at, emptyList(), listOf(star), emptyList()))
    }

    @Test
    fun `a body can be picked`() {
        val (ra, dec) = SkyGeometry.equatorial(30.0, 120.0, site, at)
        val moon = Pickable.Body("Moon", ra, dec)
        val pick = assertIs<Pickable.Body>(Picker.pick(rayAt(30.0, 120.0), 1f, site, at, emptyList(), emptyList(), listOf(moon)))
        assertEquals("Moon", pick.name)
    }

    @Test
    fun `what the ground hides cannot be picked`() {
        val below = objectAt(-10.0, 90.0, 3)
        assertNull(Picker.pick(rayAt(-10.0, 90.0), 1f, site, at, listOf(below), emptyList(), emptyList()))
        assertNotNull(Picker.pick(rayAt(-10.0, 90.0), 1f, site, at, listOf(below), emptyList(), emptyList(), groundHides = false))
    }
}

class ControlsTest {
    @Test
    fun `the time stick does nothing in its dead zone, is proportional beyond it, and is symmetric`() {
        assertEquals(0.0, TimeStick.delta(0.05f, 1.0))
        val slow = TimeStick.delta(0.4f, 1.0)
        val fast = TimeStick.delta(0.9f, 1.0)
        assertTrue(slow > 0 && fast > slow)
        assertEquals(3600.0, TimeStick.delta(1f, 1.0), 1e-6)
        assertEquals(-fast, TimeStick.delta(-0.9f, 1.0), 1e-9)
        assertEquals(TimeStick.delta(0.7f, 1.0) / 2, TimeStick.delta(0.7f, 0.5), 1e-9) // scales with the frame
    }

    @Test
    fun `zoom stays between its limits and holds still in the dead zone`() {
        assertEquals(5f, ZoomStick.next(5f, 0.05f, 1.0))
        assertEquals(ZoomStick.MAX, ZoomStick.next(39f, 1f, 5.0))
        assertEquals(ZoomStick.MIN, ZoomStick.next(1.2f, -1f, 5.0))
        assertTrue(ZoomStick.next(2f, 0.8f, 0.1) > 2f)
    }
}

class HoldControlTest {
    @Test
    fun `a hold starts on the button and ends on release`() {
        val hold = HoldControl()
        assertEquals(HoldControl.Action.Start, hold.button(true))
        assertNull(hold.button(true))
        assertEquals(HoldControl.Action.Stop(HoldControl.Why.Released), hold.button(false))
        assertFalse(hold.isHolding)
    }

    @Test
    fun `losing tracking or taking the headset off stops it, and it never restarts by itself`() {
        val hold = HoldControl()
        hold.button(true)
        assertEquals(HoldControl.Action.Stop(HoldControl.Why.TrackingLost), hold.tracking(false))
        assertNull(hold.tracking(true), "tracking came back with the button still down: no motion without a new press")
        hold.button(false)
        assertEquals(HoldControl.Action.Start, hold.button(true))

        val other = HoldControl()
        other.button(true)
        assertEquals(HoldControl.Action.Stop(HoldControl.Why.HeadsetRemoved), other.worn(false))
        assertNull(other.worn(true))
        assertFalse(other.isHolding)
    }

    @Test
    fun `no hold begins while tracking is lost or the headset is off`() {
        val hold = HoldControl()
        hold.tracking(false)
        assertNull(hold.button(true))
        hold.button(false)
        hold.tracking(true)
        hold.worn(false)
        assertNull(hold.button(true))
    }

    @Test
    fun `losing the lease ends a hold`() {
        val hold = HoldControl()
        hold.button(true)
        assertEquals(HoldControl.Action.Stop(HoldControl.Why.LeaseLost), hold.leaseLost())
        assertNull(hold.leaseLost())
        assertFalse(hold.isHolding)
    }
}

class ZoomTest {
    @Test
    fun `inverse undoes forward at any distance from the centre`() {
        val centre = Vec3(0f, 0f, -1f)
        for (angle in listOf(0.01f, 0.1f, 0.3f)) {
            val truth = Vec3(kotlin.math.sin(angle), 0f, -kotlin.math.cos(angle))
            val shown = Zoom.forward(truth, centre, 8f)
            val back = Zoom.inverse(shown, centre, 8f)
            assertNear(truth.x, back.x, 1e-4f)
            assertNear(truth.z, back.z, 1e-4f)
        }
    }

    @Test
    fun `no magnification changes nothing and the centre stays put`() {
        val d = Vec3(0.3f, 0.5f, -0.8f).normalized()
        assertEquals(d, Zoom.inverse(d, Vec3(0f, 0f, -1f), 1f))
        val c = Vec3(0f, 0f, -1f)
        assertNear(1f, Zoom.inverse(c, c, 10f).dot(c))
    }

    @Test
    fun `magnified, a point looks further from the centre than it is`() {
        val centre = Vec3(0f, 0f, -1f)
        val truth = Vec3(kotlin.math.sin(0.05f), 0f, -kotlin.math.cos(0.05f))
        val shown = Zoom.forward(truth, centre, 10f)
        assertTrue(shown.x > truth.x * 5f)
    }
}

