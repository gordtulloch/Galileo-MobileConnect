package com.galileo.mobileconnect

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The first screen, before any Master: what a person sees on a first run (MAPP-CON-020, MAPP-PLT-070). */
@OptIn(ExperimentalTestApi::class)
class WelcomeUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun launch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("galileo", Context.MODE_PRIVATE).edit().clear().commit()
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).putExtra("galileo.theme", "night").putExtra("galileo.noautoconnect", true))
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("address")).fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun close() {
        scenario?.close()
    }

    @Test
    fun theWelcomeExplainsTheLocalNetworkBeforeAskingForIt() {
        compose.onNodeWithText("Find my observatory").assertExists()
        compose.onNodeWithText("On this network").assertExists()
    }

    @Test
    fun anAddressWithoutEncryptionIsRefusedInPlainWords() {
        compose.onNodeWithTag("address").performTextInput("http://observatory.local")
        compose.onNodeWithTag("connect").performClick()
        compose.onNodeWithText("Galileo only connects over an encrypted link. Use https, or leave the prefix off.").assertExists()
    }

    @Test
    fun anEmptyAddressSaysWhatToEnter() {
        compose.onNodeWithTag("connect").performClick()
        compose.onNodeWithText("Enter the address of the computer running Galileo.").assertExists()
    }

    @Test
    fun theDemoLeadsToAConnectedObservatoryAndBackOut() {
        compose.onNodeWithTag("demo").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("demo.banner")).fetchSemanticsNodes().isNotEmpty() }
    }
}
