package com.galileo.mobileconnect

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The app driven end to end against the demonstration Master (docs/android/SDD.md 5). Night is the theme under test:
 * it is red only (MAPP-LAY-030), every control is labelled for TalkBack, and the emergency hold works.
 */
@OptIn(ExperimentalTestApi::class)
class DemoUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val areas = listOf("Overview", "Equipment", "Sky", "Imaging", "Focus", "Solve", "Guiding", "Flip", "Sessions", "Safety", "Settings")

    @Before
    fun launch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // A clean start: no remembered theme or pairing from an earlier run.
        context.getSharedPreferences("galileo", Context.MODE_PRIVATE).edit().clear().commit()
        val intent = Intent(context, MainActivity::class.java).putExtra("galileo.demo", "normal-night").putExtra("galileo.theme", "night")
        scenario = ActivityScenario.launch(intent)
        compose.waitUntil(20_000) { exists("connection") }
        compose.waitUntil(20_000) { exists("safety.indicator") }
    }

    @After
    fun close() {
        scenario?.close()
    }

    private fun exists(tag: String) = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun goTo(area: String) {
        // A tablet keeps the areas in a menu; a phone has them in a strip.
        if (!exists("area.$area") && exists("menu")) compose.onNodeWithTag("menu").performClick()
        compose.waitUntil(5_000) { exists("area.$area") }
        val node: SemanticsNodeInteraction = compose.onNodeWithTag("area.$area")
        runCatching { node.performScrollTo() }
        node.performClick()
        compose.waitForIdle()
        Thread.sleep(1500) // the Master's answers arrive over the stream
    }

    @Test
    fun everyAreaOpensWithoutFailing() {
        for (area in areas) {
            goTo(area)
            compose.waitForIdle()
        }
        // Back to the first: the shell kept working through all eleven.
        goTo("Overview")
        compose.onNodeWithText("Piers").assertExists()
    }

    @Test
    fun nightIsRedOnEveryScreen() {
        val faults = mutableListOf<String>()
        for (area in areas) {
            goTo(area)
            val image = compose.onNode(androidx.compose.ui.test.isRoot()).captureToImage()
            val pixels = IntArray(image.width * image.height)
            image.readPixels(pixels)
            var bad = 0
            var first = ""
            for ((i, p) in pixels.withIndex()) {
                val green = (p shr 8) and 0xFF
                val blue = p and 0xFF
                if (green > 2 || blue > 2) {
                    bad += 1
                    if (first.isEmpty()) first = "(${i % image.width},${i / image.width}) = ${Integer.toHexString(p)}"
                }
            }
            if (bad > 0) faults += "$area: $bad pixels are not red-only, first at $first"
        }
        if (faults.isNotEmpty()) fail(faults.joinToString("\n"))
    }

    @Test
    fun everyControlHasALabel() {
        val unlabelled = mutableListOf<String>()
        for (area in areas) {
            goTo(area)
            for (node in compose.onAllNodes(hasClickAction(), useUnmergedTree = false).fetchSemanticsNodes()) {
                val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                if (text.isNullOrBlank() && description.isNullOrBlank()) unlabelled += "$area: node ${node.id} has no text or description"
            }
        }
        if (unlabelled.isNotEmpty()) fail(unlabelled.joinToString("\n"))
    }

    @Test
    fun theMastersConfirmationComesBeforeParking() {
        compose.onNodeWithTag("lease.take").performClick()
        compose.waitUntil(10_000) { exists("lease.release") }
        goTo("Equipment")
        compose.onNodeWithTag("mount.park").performScrollTo().performClick()
        compose.waitUntil(10_000) { exists("confirm.accept") }
        compose.onNodeWithText("Park the mount").assertExists()
        compose.onNodeWithTag("confirm.cancel").performClick()
        compose.waitUntil(5_000) { !exists("confirm.accept") }
    }

    @Test
    fun theEmergencyNeedsAHoldAndListsEachPart() {
        compose.onNodeWithTag("emergency").performClick()
        compose.waitUntil(5_000) { exists("emergency.system.stop_everything") }
        // A tap does nothing.
        compose.onNodeWithTag("emergency.system.stop_everything").performClick()
        Thread.sleep(300)
        assertTrue(!exists("emergency.result"))
        // A hold of more than a second does it.
        compose.onNodeWithTag("emergency.system.stop_everything").performTouchInput {
            down(center)
            advanceEventTime(1_300)
            up()
        }
        compose.waitUntil(10_000) { exists("emergency.result") }
    }

    private fun model(): AppViewModel {
        var vm: AppViewModel? = null
        scenario!!.onActivity { vm = androidx.lifecycle.ViewModelProvider(it)[AppViewModel::class.java] }
        return vm!!
    }

    private fun takeControl() {
        compose.onNodeWithTag("lease.take").performClick()
        compose.waitUntil(10_000) { exists("lease.release") }
    }

    @Test
    fun flipShowsItsStateAndTheFlipNowControl() {
        goTo("Flip")
        compose.onNodeWithText("Meridian flip").assertExists()
        compose.onNodeWithText("Minutes to the flip").assertExists()
        compose.waitUntil(5_000) { exists("flip.now") }
    }

    @Test
    fun aHeldFocusButtonMovesOnlyWhileHeldAndStopsOnRelease() {
        takeControl()
        goTo("Focus")
        val button = compose.onNodeWithTag("hold.focus.Hold out").performScrollTo()
        button.performTouchInput { down(center) }
        compose.waitUntil(10_000) { model().snapshot.hold == com.galileo.mobileconnect.core.control.HoldState.Holding }
        val whileHeld = model().snapshot.hold
        // Lifting the finger. If the injector will not lift a touch an earlier call pressed, the button's own release is used.
        val lifted = runCatching { button.performTouchInput { up() } }.isSuccess
        if (!lifted) runCatching { button.performTouchInput { cancel() } }
        if (model().snapshot.hold == com.galileo.mobileconnect.core.control.HoldState.Holding && !lifted) {
            Thread.sleep(500)
        }
        assertTrue(whileHeld == com.galileo.mobileconnect.core.control.HoldState.Holding, "held, it was $whileHeld")
        compose.waitUntil(10_000) {
            model().snapshot.hold == com.galileo.mobileconnect.core.control.HoldState.Stopped(com.galileo.mobileconnect.core.control.HoldStop.Released)
        }
    }

    @Test
    fun leavingTheAppStopsAHeldMove() {
        takeControl()
        goTo("Focus")
        compose.onNodeWithTag("hold.focus.Hold in").performScrollTo().performTouchInput { down(center) }
        compose.waitUntil(10_000) { model().snapshot.hold == com.galileo.mobileconnect.core.control.HoldState.Holding }
        // The app leaves the foreground with the finger still down: the move must stop at once (MAPP-DEV-030).
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.waitUntil(10_000) {
            val state = model().snapshot.hold
            state is com.galileo.mobileconnect.core.control.HoldState.Stopped
        }
        val stopped = model().snapshot.hold as com.galileo.mobileconnect.core.control.HoldState.Stopped
        assertTrue(stopped.reason == com.galileo.mobileconnect.core.control.HoldStop.Background, "stopped because ${stopped.reason}")
    }

    @Test
    fun aViewerSeesNoControlsAndNoEmergencyButton() {
        // The viewer scenario: a Viewer's grant reaches the role, so nothing here can operate anything (MAPP-CTL-010).
        scenario!!.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).putExtra("galileo.demo", "viewer").putExtra("galileo.theme", "night"))
        compose.waitUntil(20_000) { exists("safety.indicator") }
        goTo("Equipment")
        assertTrue(!exists("emergency"), "a Viewer must not be offered the emergency button")
        assertTrue(!exists("mount.park"), "a Viewer must not be offered Park")
        assertTrue(!exists("lease.take"), "a Viewer must not be offered control")
    }
}
