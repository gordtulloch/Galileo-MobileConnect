package com.galileo.mobileconnect.quest

import android.app.Activity
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.galileo.mobileconnect.AppViewModel
import com.galileo.mobileconnect.Route
import com.galileo.mobileconnect.core.sky.SkyCamera
import com.galileo.mobileconnect.core.sky.SkyGeometry
import com.galileo.mobileconnect.core.sky.Vec3
import com.galileo.mobileconnect.SessionTarget
import com.galileo.mobileconnect.core.state.bool
import com.galileo.mobileconnect.core.state.double
import com.galileo.mobileconnect.ui.theme.ThemeChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val TAG = "GalileoQuestHost"

/**
 * The Kotlin side of the Quest app (docs/quest/SDD.md Q-HOST): once per frame it reads the controllers, decides what they
 * did, and tells the renderer what to draw. Every decision is made with the tested logic in :quest-logic; this class only
 * wires it to the headset and to the app. It runs on the main thread and calls nothing that blocks.
 *
 * Nothing here has been run on a headset. The parts that only a headset can prove are listed in docs/quest/SDD.md.
 */
class QuestHost(private val activity: Activity, private val app: AppViewModel) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = DockState()
    private val raw = FloatArray(NativeBridge.INPUT_SIZE)
    private val input = InputSnapshot()

    // -- surfaces ---------------------------------------------------------------------------------------------------

    private class Surf(val id: Int, val pixelWidth: Int, val pixelHeight: Int, val name: String, val content: @Composable () -> Unit) {
        var window: PaneWindow? = null
        var failed = false
    }

    private val surfaces = HashMap<Int, Surf>()
    private var dockPlacement: Placement? = null
    private val stack = PaneStack { dockPlacement ?: Placement(Vec3(0f, 1.0f, -0.9f), 0f) }
    private var alertPlacement = Placement(Vec3(0f, 1.2f, -0.7f), 0f)
    private var menuPlacement = Placement(Vec3(0f, 1.5f, -0.8f), 0f)
    private var alertShown = false
    private var menuShown = false

    // the description card on the right controller, the head-locked slew status, and the slew being followed
    private var cardQuad: Quad? = null
    private var statusQuad: Quad? = null
    private var statusShown = false
    private val statusState = StatusState()
    private val slew = SlewTracker()
    private var currentSlew: SlewTracker.Status? = null
    private var messageUntil = 0L

    // -- the sky ----------------------------------------------------------------------------------------------------

    private var skyState by mutableStateOf<QuestSky?>(null)
    private var skyKey: Any? = null
    private var timeOffset = 0.0
    private var zoom = 1f
    private var zoomCentre = Vec3(0f, 0f, -1f)
    private var showConstellations = true
    private var brightnessIndex = 1
    private var bodiesNow: List<Pickable.Body> = emptyList()
    private var lastSlow = 0L
    private var lastLookAt = 0L
    private var lastLookDir: Vec3? = null
    private var lastLookLevel = -1
    private var atlas: LabelAtlas? = null
    private var atlasKey = ""
    private var passthrough = false

    // -- pointers ---------------------------------------------------------------------------------------------------

    private class Located(val id: Int, val hit: Quad.Hit)
    private class Grab(val id: Int, val distance: Float)

    private inner class Pointer(val hand: () -> HandInput, val isRight: Boolean) {
        val hold = HoldControl()
        var touching: Int = -1
        var lastX = 0f
        var lastY = 0f
        var grab: Grab? = null
        var triggerDown = false
        var squeezeDown = false
        /** Where the pointer was before the trigger started to move: squeezing it deflects the aim, so a pick uses this. */
        var restRay: Ray? = null
    }

    private var narrationReady = false
    private val right = Pointer({ input.right }, true)
    private val left = Pointer({ input.left }, false)
    private var lastFrame = System.nanoTime()
    private var previousButtons = 0
    private var previousClicks = Pair(false, false)

    private var loop: kotlinx.coroutines.Job? = null

    fun start() {
        state.noticePending = app.pref("quest.notice", "") != "seen"
        ensure(DOCK, Dock.PIXEL_WIDTH, Dock.PIXEL_HEIGHT, "dock") { QuestRoot(app) { DockContent(state, ::onArea, ::togglePassthrough, ::recentreDock) } }
        loop = scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    Log.e(TAG, "tick failed", e)
                }
                delay(11)
            }
        }
    }

    fun stop() {
        Narration.stop()
        loop?.cancel()
        releaseEverything()
        surfaces.values.toList().forEach { remove(it.id) }
        skyState?.stop()
        scope.cancel()
    }

    /** The app left the front or the headset came off: nothing may stay held (MAPP-QST-060, MAPP-DEV-030). */
    fun releaseEverything() {
        for (p in listOf(right, left)) {
            p.hold.worn(false)
            p.hold.leaseLost()
            cancelTouch(p)
        }
        NativeBridge.nativeSetLaser(-1f, -1f)
    }

    // -- the frame --------------------------------------------------------------------------------------------------

    private fun tick() {
        val nowNanos = System.nanoTime()
        val dt = ((nowNanos - lastFrame) / 1e9).coerceIn(0.0, 0.25)
        lastFrame = nowNanos
        if (!narrationReady) { Narration.init(activity); narrationReady = true }
        if (!NativeBridge.nativeGetInput(raw)) return
        input.read(raw)

        // The pose is only real a moment after the session is focused: until then the head reads as the origin facing -Z,
        // which put the dock and the first-run notice where the wearer was not.
        poseFrames = if (input.focused && input.head.length() > 1e-3f) poseFrames + 1 else 0
        if (dockPlacement == null && poseSettled()) dockPlacement = dockStart()
        syncApp()
        val modal = alertWanted()

        // Taking the headset off ends every hold at once, whatever the buttons say.
        for (p in listOf(right, left)) {
            if (!input.focused) {
                p.hold.worn(false)
                cancelTouch(p)
            } else {
                p.hold.worn(true)
            }
            // Losing control ends a held move once, when it is lost. Doing it every frame while the app cannot operate (on the
            // pairing screen, as a Viewer) restarted any held trigger each frame, so no tap on a button ever completed.
            if (couldOperate && !app.canOperate) {
                p.hold.leaseLost()
            }
        }
        couldOperate = app.canOperate
        pointer(right, modal)
        pointer(left, modal)
        NativeBridge.nativeSetLaser(rightLaser, leftLaser)
        if (input.focused) scrollPane(dt)
        if (input.focused && !modal) controls(dt)
        updateSlew()
        updateCard()
        updateStatus()
        pushSky()
        pushQuads()
        pump()
    }

    // -- the slew, the description card and the status panel ----------------------------------------------------------

    /** Follows the slew started from the menu, using what the Master says about the mount. */
    private fun updateSlew() {
        val now = System.currentTimeMillis()
        // While the Master's confirmation is up the slew has not been allowed yet, so the wait for it does not count.
        if (app.question != null) slew.waiting(now)
        currentSlew = slew.update(mountPosition(), app.value("mount.slewing")?.value.bool, now)
        statusState.slew = currentSlew
        if (currentSlew == null) statusState.hint = null
        if (statusState.message != null && now > messageUntil) statusState.message = null
    }

    /**
     * The card sits on the right controller as in the spike: its bottom edge runs along the pointer axis, it faces the head, and the
     * text reads left to right for the wearer, so turning the palm towards you reads it like a wrist display.
     */
    private fun updateCard() {
        val h = input.right
        if (!menuShown || !h.tracked || !input.focused) {
            cardQuad = null
            return
        }
        val a = h.direction.normalized()
        val world = Vec3(0f, 1f, 0f)
        val toHead = input.head - h.origin
        var perp = toHead - a * toHead.dot(a)
        perp = if (perp.length() > 1e-3f) perp.normalized() else a.cross(world).normalized()
        val headRight = input.forward.cross(world)
        val hr = if (headRight.length() > 1e-3f) headRight.normalized() else Vec3(1f, 0f, 0f)
        val r = if (a.dot(hr) >= 0f) a else a * -1f
        val u = perp.cross(r).normalized()
        val bottom = h.origin + a * 0.02f + perp * 0.03f
        cardQuad = Quad(bottom + u * (CARD_H / 2f), r, u, CARD_W, CARD_H, CARD_PW, CARD_PH)
    }

    /** The slew status rides above the gaze, a little way out, and turns with the head. */
    private fun updateStatus() {
        if (!statusShown || !input.focused) {
            statusQuad = null
            return
        }
        val f = input.forward.normalized()
        val r = f.cross(input.up).normalized()
        val u = r.cross(f).normalized()
        statusQuad = Quad(input.head + f * STATUS_DISTANCE + u * STATUS_ABOVE, r, u, STATUS_W, STATUS_H, STATUS_PW, STATUS_PH)
    }

    private fun say(text: String) {
        statusState.message = text
        messageUntil = System.currentTimeMillis() + MESSAGE_MILLIS
    }

    /**
     * A button on the object menu. The menu closes at once; the work (and the Master's confirmation, if it asks) goes on here, not in
     * the menu, which no longer exists. A slew is then followed by the status panel and the telescope marker on the sky.
     */
    private fun menuAction(action: MenuAction, sel: Selection) {
        skyState?.clearSelection()
        scope.launch {
            val args: Map<String, JsonElement> = mapOf("ra_deg" to JsonPrimitive(sel.raDeg), "dec_deg" to JsonPrimitive(sel.decDeg))
            val problem = when (action) {
                MenuAction.Goto -> {
                    beginSlew(sel)
                    app.goTo(sel.name, sel.raDeg, sel.decDeg)
                }
                MenuAction.Centre -> {
                    beginSlew(sel)
                    app.perform("solve.centre", args + ("name" to JsonPrimitive(sel.name)))
                }
                MenuAction.Sync -> app.perform("mount.sync", args)
                MenuAction.AddToSession -> {
                    app.sessionTarget = SessionTarget(sel.name, sel.raDeg, sel.decDeg)
                    app.requestedArea = "Sessions"
                    null
                }
            }
            if (problem != null) {
                slew.cancel()
                say(problem)
            }
        }
    }

    private fun beginSlew(sel: Selection) = slew.begin(sel.name, sel.raDeg, sel.decDeg, mountPosition(), System.currentTimeMillis())

    private fun alertWanted() = app.route != Route.Connected || app.snapshot.stop != null || app.question != null ||
        app.emergencyOpen || app.pierPickerOpen || app.leaseOffer != null || state.noticePending

    private fun syncApp() {
        // Leaving the observatory (Exit the demo, a dropped link, un-pairing) ends the panels that belonged to it.
        if (app.route != Route.Connected) {
            for (pane in stack.open) closeArea(pane.area)
        }
        // the alert surface
        val wanted = alertWanted()
        if (wanted && !alertShown) {
            // Placed from the wearer's real head pose, like the dock: before the headset is worn and tracking, the
            // pose is a default at the origin facing -Z, which left the first-run notice tilted and seen by one eye.
            if (poseSettled()) {
                alertPlacement = inFront(ALERT_DISTANCE, ALERT_HEIGHT)
                ensure(ALERT, ALERT_PW, ALERT_PH, "alert") { QuestRoot(app) { AlertContent(state) { noticeSeen() } } }
                alertShown = true
            }
        } else if (!wanted && alertShown) {
            remove(ALERT)
            alertShown = false
            alertOffSince = 0L
        } else if (alertShown) {
            recentreAlert()
        }

        // the sky follows the connection
        val context = app.skyContext()
        val key = context?.let { it.first to it.second }
        if (key != skyKey) {
            skyState?.stop()
            skyState = null
            skyKey = key
            lastLookDir = null
            if (context != null) {
                val sky = QuestSky(context.first, context.second, app, scope)
                skyState = sky
                scope.launch { sky.start() }
            }
        }

        // an object menu while something is selected
        val sky = skyState
        val selected = sky?.selection != null
        if (selected && !menuShown) {
            menuPlacement = inFront(0.8f, 0.10f)
            ensure(MENU, MENU_PW, MENU_PH, "object") {
                QuestRoot(app) { skyState?.let { ObjectMenuContent(it, { it.clearSelection() }, ::menuAction) } }
            }
            ensure(CARD, CARD_PW, CARD_PH, "card") { QuestRoot(app) { skyState?.let { ObjectCardContent(it) } } }
        } else if (!selected && menuShown) {
            Narration.stop()
            remove(MENU)
            remove(CARD)
            cardQuad = null
        }
        menuShown = selected

        // the slew status, while a slew is followed or a message is up
        val wantStatus = (currentSlew != null || statusState.message != null) && !alertWanted()
        if (wantStatus && !statusShown) {
            ensure(STATUS, STATUS_PW, STATUS_PH, "status") { QuestRoot(app) { StatusContent(statusState) } }
        } else if (!wantStatus && statusShown) {
            remove(STATUS)
            statusQuad = null
        }
        statusShown = wantStatus

        // "Add to Session" and the like ask for an area by name
        app.requestedArea?.let { label ->
            DockArea.entries.firstOrNull { it.label == label }?.let { openArea(it) }
            app.requestedArea = null
        }
        state.openAreas = stack.open.map { it.area }.toSet()
    }

    // -- the pointer ------------------------------------------------------------------------------------------------

    private fun pointer(p: Pointer, modal: Boolean) {
        val h = p.hand()
        val ray = Ray(h.origin, h.direction)
        val located = if (h.tracked) locate(ray, modal) else null
        if (p.isRight) rightOver = located else leftOver = located
        for ((id, surf) in surfaces) {
            surf.window?.night = app.theme == ThemeChoice.Night
            if (located != null && located.id == id) surf.window?.hover(p.isRight, located.hit.xPixels, located.hit.yPixels)
            else surf.window?.hover(p.isRight, null, null)
        }

        if (h.tracked && h.trigger < 0.08f) p.restRay = ray
        val trigger = if (p.triggerDown) h.trigger > 0.3f else h.trigger > 0.6f
        p.triggerDown = trigger
        val squeeze = if (p.squeezeDown) h.squeeze > 0.3f else h.squeeze > 0.7f
        val gripping = squeeze

        // tracking lost: any press in progress ends
        val trackingAction = p.hold.tracking(h.tracked)
        if (trackingAction is HoldControl.Action.Stop) cancelTouch(p)

        // The right grip (second trigger) reads the open object's description aloud, or stops it, as in the atlas spike. It does so
        // wherever the pointer is, except on the dock or a pane, where it still grabs them.
        val readAloud = p.isRight && menuShown && !modal && (located == null || located.id == MENU)
        // grab: the grip on a surface moves it, at the distance it was grabbed
        if (gripping && !p.squeezeDown && located != null && located.id != ALERT && !readAloud) p.grab = Grab(located.id, located.hit.distance.coerceIn(0.5f, 3f))
        if (!gripping) {
            if (p.grab?.id == DOCK) saveDockPlacement()
            p.grab = null
        }
        if (readAloud && gripping && !p.squeezeDown) skyState?.selection?.let { Narration.toggle(it.name) }
        p.squeezeDown = gripping
        p.grab?.let { moveSurface(it, ray) }

        val laser = when {
            !h.tracked -> -1f
            located != null -> located.hit.distance
            else -> 4f
        }
        if (p.isRight) rightLaser = laser else leftLaser = laser

        if (p.grab != null) return // gripping: the trigger does nothing

        val holdAction = p.hold.button(trigger)
        when (val action = holdAction) {
            HoldControl.Action.Start -> {
                if (located != null) {
                    surfaces[located.id]?.window?.let {
                        it.touchDown(located.hit.xPixels, located.hit.yPixels)
                        p.touching = located.id
                        p.lastX = located.hit.xPixels
                        p.lastY = located.hit.yPixels
                    }
                } else if (!modal) {
                    pickSky(p.restRay ?: ray)
                }
            }
            is HoldControl.Action.Stop -> {
                if (action.why == HoldControl.Why.Released) {
                    surfaces[p.touching]?.window?.touchUp(p.lastX, p.lastY)
                    p.touching = -1
                } else {
                    cancelTouch(p)
                }
            }
            null -> Unit
        }
        if (p.touching >= 0 && p.hold.isHolding) {
            if (located != null && located.id == p.touching) {
                p.lastX = located.hit.xPixels
                p.lastY = located.hit.yPixels
                surfaces[p.touching]?.window?.touchMove(p.lastX, p.lastY)
            } else {
                // the pointer left the surface mid-press: the press ends, a held move with it
                cancelTouch(p)
            }
        }
    }

    private var rightOver: Located? = null
    private var couldOperate = false
    private var leftOver: Located? = null
    private var alertOffSince = 0L
    private var poseFrames = 0
    private fun poseSettled() = poseFrames >= 30
    private var leftLaser = -1f
    private var rightLaser = -1f

    /** Right stick up/down scrolls the panel under the right pointer, like a mouse wheel; harder push, faster scroll. */
    private fun scrollPane(dt: Double) {
        // the description card on the right controller scrolls with the left stick's Y (its X is time, and the right stick zooms)
        if (cardQuad != null) {
            val stick = input.left.stickY
            if (abs(stick) >= SCROLL_DEADZONE) {
                val eased = (abs(stick) - SCROLL_DEADZONE) / (1f - SCROLL_DEADZONE)
                val rate = SCROLL_NOTCHES_PER_SECOND * eased * eased * (if (stick > 0) 1f else -1f)
                surfaces[CARD]?.window?.scroll(CARD_PW / 2f, CARD_PH / 2f, (rate * dt).toFloat())
            }
        }
        val over = rightOver ?: return
        val stick = input.right.stickY
        if (abs(stick) < SCROLL_DEADZONE) return
        val eased = (abs(stick) - SCROLL_DEADZONE) / (1f - SCROLL_DEADZONE)
        val rate = SCROLL_NOTCHES_PER_SECOND * eased * eased * (if (stick > 0) 1f else -1f)
        surfaces[over.id]?.window?.scroll(over.hit.xPixels, over.hit.yPixels, (rate * dt).toFloat())
    }

    private fun cancelTouch(p: Pointer) {
        if (p.touching >= 0) surfaces[p.touching]?.window?.touchCancel()
        p.touching = -1
    }

    private fun locate(ray: Ray, modal: Boolean): Located? {
        if (modal) return quadOf(ALERT)?.intersect(ray)?.let { Located(ALERT, it) }
        val target = stack.pick(ray)
        val menu = if (menuShown) quadOf(MENU)?.intersect(ray) else null
        return when (target) {
            is PaneStack.Target.OnDock -> Located(DOCK, target.hit)
            is PaneStack.Target.OnPane -> {
                if (menu != null && menu.distance < target.hit.distance) Located(MENU, menu) else Located(paneId(target.pane.area), target.hit)
            }
            null -> menu?.let { Located(MENU, it) }
        }
    }

    private fun quadOf(id: Int): Quad? = when (id) {
        DOCK -> dockPlacement?.let { Dock.quad(it) }
        ALERT -> Quad(alertPlacement.center, alertPlacement.right, alertPlacement.up, ALERT_W, ALERT_H, ALERT_PW, ALERT_PH)
        MENU -> Quad(menuPlacement.center, menuPlacement.right, menuPlacement.up, MENU_W, MENU_H, MENU_PW, MENU_PH)
        CARD -> cardQuad
        STATUS -> statusQuad
        else -> stack.open.firstOrNull { paneId(it.area) == id }?.quad()
    }

    private fun yawToward(target: Vec3): Float {
        val fx = target.x - input.head.x
        val fz = target.z - input.head.z
        val l = kotlin.math.sqrt(fx * fx + fz * fz)
        return if (l < 1e-3f) 0f else atan2(-fx / l, -fz / l)
    }

    private fun moveSurface(grab: Grab, ray: Ray) {
        val target = ray.at(grab.distance)
        val yaw = yawToward(target)
        when (grab.id) {
            DOCK -> dockPlacement = Placement(target, yaw)
            MENU -> menuPlacement = Placement(target, yaw)
            ALERT -> Unit
            else -> {
                val pane = stack.open.firstOrNull { paneId(it.area) == grab.id } ?: return
                val away = Vec3(-sin(yaw), 0f, -cos(yaw))
                stack.move(pane.area, Placement(target - away * pane.depth, yaw))
            }
        }
    }

    // -- the sky ----------------------------------------------------------------------------------------------------

    private fun shownTime(): Instant = Instant.now().plusMillis((timeOffset * 1000).toLong())

    private fun pickSky(ray: Ray) {
        val sky = skyState ?: return
        val site = sky.site ?: return
        val moment = shownTime()
        val pickRay = Ray(ray.origin, Zoom.inverse(ray.unit, zoomCentre, zoom))
        val picked = Picker.pick(pickRay, zoom, site, moment, sky.deepSky, sky.bright, bodiesNow)
        if (picked != null) scope.launch { sky.picked(picked, moment) } else sky.clearSelection()
    }

    private fun controls(dt: Double) {
        // left stick: through time. Right stick up/down: zoom. Clicks: live time, recentre the zoom.
        val dtSky = TimeStick.delta(input.left.stickX, dt)
        if (dtSky != 0.0) timeOffset += dtSky
        val before = zoom
        // The right stick scrolls a panel while the right pointer is on one (scrollPane), and zooms the sky otherwise.
        zoom = ZoomStick.next(zoom, if (rightOver != null) 0f else input.right.stickY, dt)
        if (before <= 1.001f && zoom > 1.001f) zoomCentre = input.forward.normalized()
        val clicks = Pair(input.left.stickClick, input.right.stickClick)
        if (clicks.first && !previousClicks.first) timeOffset = 0.0
        if (clicks.second && !previousClicks.second) {
            if (zoom > 1.001f) zoomCentre = Zoom.inverse(input.forward.normalized(), zoomCentre, zoom) else zoom = 1f
        }
        previousClicks = clicks

        val pressed = input.buttons and previousButtons.inv()
        previousButtons = input.buttons
        if (pressed and NativeBridge.BUTTON_A != 0) togglePassthrough()
        if (pressed and NativeBridge.BUTTON_B != 0) recentreDock()
        if (pressed and NativeBridge.BUTTON_X != 0) showConstellations = !showConstellations
        if (pressed and NativeBridge.BUTTON_Y != 0) brightnessIndex = (brightnessIndex + 1) % BRIGHTNESS.size
        state.timeLabel = timeLabel()
        state.zoomLabel = if (zoom > 1.01f) "x%.1f".format(zoom) else ""
        state.constellations = showConstellations
    }

    private fun timeLabel(): String {
        if (abs(timeOffset) < 1.0) return "Live"
        val total = abs(timeOffset).toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        return (if (timeOffset < 0) "-" else "+") + (if (h > 0) "${h}h " else "") + "${m}m"
    }

    private fun pushSky() {
        val sky = skyState ?: return
        val moment = shownTime()
        val rotation = sky.rotation(moment) ?: return
        val night = app.theme == ThemeChoice.Night
        val flags = (if (night) 1 else 0) or 2 or (if (showConstellations) 4 else 0)
        val ground = if (night) floatArrayOf(0.03f, 0f, 0f, 1f) else floatArrayOf(0.04f, 0.05f, 0.04f, 1f)
        NativeBridge.nativeSetSky(
            rotation.toColumnMajor(), floatArrayOf(zoomCentre.x, zoomCentre.y, zoomCentre.z), zoom, sky.limitingMagnitude(zoom),
            BRIGHTNESS[brightnessIndex], flags, ground,
        )
        sky.ensureSamples(moment)
        val nowMs = System.currentTimeMillis()
        if (currentSlew != null) statusState.hint = mountPosition()?.let { scopeHint(sky.toWorld(rotation, it.first, it.second).normalized()) }

        // ask the Master for what we are looking at once the view has settled
        val site = sky.site
        if (site != null && nowMs - lastLookAt > 1000) {
            val level = SkyCamera(fieldOfView = 100.0 / zoom).levelOfDetail
            val trueGaze = Zoom.inverse(input.forward.normalized(), zoomCentre, zoom)
            val radius = min(110.0, max(8.0, 100.0 / zoom))
            val moved = lastLookDir?.let { angleBetweenDegrees(it, trueGaze) > radius / 4 } ?: true
            if (moved || level != lastLookLevel) {
                val h = World.worldToHorizon(trueGaze)
                val (ra, dec) = SkyGeometry.equatorial(altitudeOf(h), azimuthOf(h), site, moment)
                sky.look(ra, dec, radius, level)
                lastLookDir = trueGaze
                lastLookLevel = level
                lastLookAt = nowMs
            }
        }

        // the overlay and the names follow the shown time a few times a second, not every frame
        if (nowMs - lastSlow > 250) {
            lastSlow = nowMs
            val mount = mountPosition()
            val (lines, tris) = sky.overlay(rotation, night, mount, currentSlew)
            NativeBridge.nativeSetOverlay(lines, lines.size / 7, tris, tris.size / 7)
            bodiesNow = sky.bodies(moment)
            pushLabels(sky, rotation, night)
        }
    }

    /** Where the telescope is relative to where the wearer faces, in words and an arrow, so a slew off to the side can be found. */
    private fun scopeHint(scope: Vec3): String {
        val shown = Zoom.forward(scope, zoomCentre, zoom).normalized()
        val gaze = input.forward.normalized()
        val away = angleBetweenDegrees(gaze, shown)
        if (away < 20.0) return "Telescope is in view"
        val right = gaze.cross(input.up).normalized()
        val up = right.cross(gaze).normalized()
        val angle = Math.toDegrees(atan2(shown.dot(up).toDouble(), shown.dot(right).toDouble()))
        val arrows = arrayOf("→", "↗", "↑", "↖", "←", "↙", "↓", "↘")
        val arrow = arrows[(((angle + 360 + 22.5) % 360) / 45).toInt() % 8]
        return "Telescope is %.0f° from where you look  %s".format(away, arrow)
    }

    private fun mountPosition(): Pair<Double, Double>? {
        val ra = app.value("mount.position.ra_deg")?.value.double ?: return null
        val dec = app.value("mount.position.dec_deg")?.value.double ?: return null
        return ra to dec
    }

    private fun pushLabels(sky: QuestSky, rotation: com.galileo.mobileconnect.core.sky.Mat3, night: Boolean) {
        class L(val text: String, val dir: Vec3, val scale: Float = 1f)
        val labels = ArrayList<L>()
        fun up(v: Vec3) = Vec3(v.x, v.y + 0.035f, v.z).normalized() // a little above the thing it names
        if (showConstellations) {
            for (n in sky.constellations.names) labels += L(n.name, sky.toWorld(rotation, n.raDeg, n.decDeg).normalized())
        }
        for (b in bodiesNow) labels += L(b.name, up(sky.toWorld(rotation, b.raDeg, b.decDeg).normalized()))
        sky.selection?.let { labels += L(it.name, up(sky.toWorld(rotation, it.raDeg, it.decDeg).normalized())) }
        // The compass: large letters standing on the horizon, so north is found at a glance; the in-between points are half the size.
        for ((text, az) in listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)) {
            labels += L(text, World.horizonToWorld(horizonDirection(CARDINAL_ALTITUDE, az)), CARDINAL_SCALE)
        }
        for ((text, az) in listOf("NE" to 45.0, "SE" to 135.0, "SW" to 225.0, "NW" to 315.0)) {
            labels += L(text, World.horizonToWorld(horizonDirection(INTERCARDINAL_ALTITUDE, az)), INTERCARDINAL_SCALE)
        }

        val key = labels.map { it.text }.distinct().sorted().joinToString("|")
        var bitmap: android.graphics.Bitmap? = null
        if (key != atlasKey || atlas == null) {
            atlas = LabelAtlas.build(labels.map { it.text })
            atlasKey = key
            bitmap = atlas!!.bitmap
        }
        val a = atlas ?: return
        val entries = ArrayList<Float>()
        var count = 0
        for (l in labels) {
            val place = a.place(l.text) ?: continue
            entries += listOf(l.dir.x, l.dir.y, l.dir.z, place.u0, place.v0, place.u1, place.v1, place.widthDegrees * l.scale, place.heightDegrees * l.scale)
            count += 1
        }
        NativeBridge.nativeSetLabels(bitmap, entries.toFloatArray(), count)
    }

    // -- dock actions -----------------------------------------------------------------------------------------------

    private fun onArea(area: DockArea) {
        if (stack.isOpen(area) && stack.open.last().area == area) closeArea(area) else openArea(area)
    }

    private fun openArea(area: DockArea) {
        val pane = stack.press(area)
        ensure(paneId(area), pane.pixelWidth, pane.pixelHeight, area.label) { QuestRoot(app) { PaneContent(area, skyState) { closeArea(area) } } }
        state.openAreas = stack.open.map { it.area }.toSet()
    }

    private fun closeArea(area: DockArea) {
        stack.close(area)
        remove(paneId(area))
        state.openAreas = stack.open.map { it.area }.toSet()
    }

    private fun togglePassthrough() {
        passthrough = !passthrough
        state.passthrough = passthrough
        NativeBridge.nativeSetPassthrough(passthrough)
    }

    /**
     * Where the dock starts, and where it returns to on recentre: where the wearer last put it, relative to their head (forward,
     * sideways and up from where they face), else in front of and below the gaze (Dock.placeInFrontOf).
     */
    private fun dockStart(): Placement {
        val saved = app.pref("quest.dock", "").split(",").mapNotNull { it.toFloatOrNull() }
        if (saved.size != 3) return Dock.placeInFrontOf(input.head, input.forward)
        val flat = Vec3(input.forward.x, 0f, input.forward.z)
        val f = if (flat.length() < 1e-3f) Vec3(0f, 0f, -1f) else flat.normalized()
        val right = Vec3(-f.z, 0f, f.x)  // f x up
        val centre = Vec3(
            input.head.x + f.x * saved[0] + right.x * saved[1],
            input.head.y + saved[2],
            input.head.z + f.z * saved[0] + right.z * saved[1],
        )
        return Placement(centre, yawToward(centre))
    }

    /** The dock was let go of: remember where it sits relative to the head, so it starts there next time. */
    private fun saveDockPlacement() {
        val dock = dockPlacement ?: return
        val flat = Vec3(input.forward.x, 0f, input.forward.z)
        if (flat.length() < 1e-3f) return
        val f = flat.normalized()
        val right = Vec3(-f.z, 0f, f.x)
        val rel = Vec3(dock.center.x - input.head.x, dock.center.y - input.head.y, dock.center.z - input.head.z)
        val forward = rel.x * f.x + rel.z * f.z
        val side = rel.x * right.x + rel.z * right.z
        app.setPref("quest.dock", "$forward,$side,${rel.y}")
        Log.i("QuestDock", "saved dock placement: forward=$forward side=$side up=${rel.y}")
    }

    private fun recentreDock() {
        dockPlacement = dockStart()
    }

    private fun noticeSeen() {
        app.setPref("quest.notice", "seen")
        state.noticePending = false
    }

    /**
     * The alert is placed where the wearer faces when it appears, which is often not where they end up looking (they were
     * pointing at the button that caused it). If it has been well off their gaze for a moment, and neither pointer is on it,
     * it moves to where they now face; while they aim at it, it stays put.
     */
    private fun recentreAlert() {
        val aimed = rightOver?.id == ALERT || leftOver?.id == ALERT
        val toward = Vec3(alertPlacement.center.x - input.head.x, 0f, alertPlacement.center.z - input.head.z)
        val gaze = Vec3(input.forward.x, 0f, input.forward.z)
        if (aimed || toward.length() < 1e-3f || gaze.length() < 1e-3f ||
            angleBetweenDegrees(toward, gaze) < ALERT_RECENTRE_DEGREES) {
            alertOffSince = 0L
            return
        }
        val now = System.nanoTime()
        if (alertOffSince == 0L) alertOffSince = now
        else if (now - alertOffSince > ALERT_RECENTRE_AFTER_NANOS) {
            alertPlacement = inFront(ALERT_DISTANCE, ALERT_HEIGHT)
            alertOffSince = 0L
        }
    }

    private fun inFront(distance: Float, dy: Float): Placement {
        val flat = Vec3(input.forward.x, 0f, input.forward.z)
        val f = if (flat.length() < 1e-3f) Vec3(0f, 0f, -1f) else flat.normalized()
        val centre = Vec3(input.head.x + f.x * distance, input.head.y + dy, input.head.z + f.z * distance)
        return Placement(centre, atan2(-f.x, -f.z))
    }

    // -- surfaces ---------------------------------------------------------------------------------------------------

    private fun ensure(id: Int, pw: Int, ph: Int, name: String, content: @Composable () -> Unit) {
        if (surfaces.containsKey(id)) return
        surfaces[id] = Surf(id, pw, ph, name, content)
        NativeBridge.nativeCreateQuad(id, pw, ph)
    }

    private fun remove(id: Int) {
        val s = surfaces.remove(id) ?: return
        s.window?.close()
        NativeBridge.nativeSetQuad(id, false, FloatArray(9), 1f, 1f)
        NativeBridge.nativeDestroyQuad(id)
    }

    /** A surface exists once the render thread has made its swapchain: only then can the screen be put on it. */
    private fun pump() {
        for (s in surfaces.values) {
            if (s.window != null || s.failed) continue
            val surface = NativeBridge.nativeTakeSurface(s.id) ?: continue
            try {
                s.window = PaneWindow(activity, surface, s.pixelWidth, s.pixelHeight, DENSITY_DPI, s.name, s.content)
            } catch (e: Exception) {
                s.failed = true
                Log.e(TAG, "surface ${s.name} could not be shown", e)
            }
        }
    }

    private fun pushQuads() {
        for (s in surfaces.values) {
            val q = quadOf(s.id)
            if (q == null || s.window == null) {
                NativeBridge.nativeSetQuad(s.id, false, FloatArray(9), 1f, 1f)
                continue
            }
            val r = q.right.normalized()
            val u = q.up.normalized()
            NativeBridge.nativeSetQuad(
                s.id, true, floatArrayOf(q.center.x, q.center.y, q.center.z, r.x, r.y, r.z, u.x, u.y, u.z), q.widthMetres, q.heightMetres,
            )
        }
    }

    private fun paneId(area: DockArea) = PANE_BASE + area.ordinal

    companion object {
        // The compositor draws quad layers in id order, later ones over earlier: the dock, then the panes, then the object
        // menu, and the alert (connecting, pairing, a confirmation) over everything. kMaxQuads in quest.cpp is 16.
        private const val DOCK = 0
        private const val PANE_BASE = 1       // one id per DockArea
        private const val MENU = 12
        private const val ALERT = 13
        private const val CARD = 14           // the object's description on the right controller
        private const val STATUS = 15         // slew progress; the last quad (kMaxQuads is 16)
        private const val CARD_PW = 440
        private const val CARD_PH = 560
        private const val CARD_W = 0.12f
        private const val CARD_H = 0.153f
        private const val STATUS_PW = 640
        private const val STATUS_PH = 150
        private const val STATUS_W = 0.40f
        private const val STATUS_H = 0.094f
        private const val STATUS_DISTANCE = 0.8f
        private const val STATUS_ABOVE = 0.20f
        private const val MESSAGE_MILLIS = 8_000L
        private const val CARDINAL_SCALE = 4f
        private const val CARDINAL_ALTITUDE = 8.0
        private const val INTERCARDINAL_SCALE = 2f
        private const val INTERCARDINAL_ALTITUDE = 5.0
        private const val DENSITY_DPI = 240
        private const val ALERT_PW = 1000
        private const val ALERT_PH = 700
        private const val ALERT_W = 0.9f
        private const val ALERT_H = 0.63f
        private const val ALERT_DISTANCE = 1.3f
        private const val ALERT_RECENTRE_DEGREES = 20.0
        private const val ALERT_RECENTRE_AFTER_NANOS = 700_000_000L
        private const val ALERT_HEIGHT = 0.10f   // above eye level: the 0.63 m panel's bottom edge then clears the dock
        private const val SCROLL_DEADZONE = 0.2f
        private const val SCROLL_NOTCHES_PER_SECOND = 14f
        private const val MENU_PW = 440
        private const val MENU_PH = 500
        private const val MENU_W = 0.40f
        private const val MENU_H = 0.46f
        private val BRIGHTNESS = floatArrayOf(0.35f, 0.55f, 0.8f, 1.0f)
    }
}
