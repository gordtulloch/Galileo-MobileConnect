package com.galileo.mobileconnect.core.mock

import com.galileo.mobileconnect.core.api.Role
import com.galileo.mobileconnect.core.link.ClientIdentity
import com.galileo.mobileconnect.core.link.removeSavedTarget
import com.galileo.mobileconnect.core.link.savedTargets
import com.galileo.mobileconnect.core.session.ObservatorySession
import com.galileo.mobileconnect.core.sky.SavedTarget
import com.galileo.mobileconnect.core.vectors.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The saved targets list (`GET`/`DELETE /mobileconnect/v1/targets`) against the scripted Master. */
class TargetsTest {
    private val identity = ClientIdentity("Vector phone", "0.1.0", "android", "phone")
    private val scope = CoroutineScope(Dispatchers.Default)
    private val masters = mutableListOf<MockMaster>()

    @AfterTest
    fun tearDown() {
        masters.forEach { it.shutDown() }
        scope.cancel()
    }

    private fun master(scenario: String) = MockMaster(scenario, scope).also { masters += it }

    @Test
    fun `the saved targets are listed with what the tile shows, and unknowns are left out`() = runBlocking<Unit> {
        val api = master("normal-night").connection(identity, MockMaster.TOKEN).api
        val listing = assertNotNull(api.savedTargets())
        assertEquals(listOf("M42", "M31"), listing.targets.map { it.name }) // the scenario's own, newest first
        assertEquals("Nebula · mag 4.0 · 65.0′", listing.targets.first().summary)
        assertEquals("Galaxy · mag 3.4 · 178.0′", listing.targets.last().summary)
        assertTrue(listing.version.isNotEmpty())
    }

    @Test
    fun `a camel-case type is shown as words`() {
        val cluster = SavedTarget("M13", emptyList(), 250.4, 36.5, "GlobularCluster", magnitude = 5.8, sizeArcmin = 20.0, description = "")
        assertEquals("Globular Cluster · mag 5.8 · 20.0′", cluster.summary)
    }

    @Test
    fun `a wire magnitude of 99 and a size of 0 mean unknown`() {
        val unknown = SavedTarget("X", emptyList(), 1.0, 2.0, "Other", magnitude = null, sizeArcmin = null, description = "")
        assertEquals("Other", unknown.summary)
    }

    @Test
    fun `removing an entry takes it off the list and changes the version`() = runBlocking<Unit> {
        val api = master("normal-night").connection(identity, MockMaster.TOKEN).api
        val before = assertNotNull(api.savedTargets())
        assertNull(api.removeSavedTarget("M42"))
        val after = assertNotNull(api.savedTargets())
        assertEquals(listOf("M31"), after.targets.map { it.name })
        assertNotEquals(before.version, after.version)
    }

    @Test
    fun `a Viewer is refused the removal and the list is unchanged`() = runBlocking<Unit> {
        val api = master("viewer").connection(identity, MockMaster.TOKEN).api
        val before = assertNotNull(api.savedTargets()).targets.map { it.name }
        assertEquals("Only an Operator can remove a saved target.", api.removeSavedTarget(before.first()))
        assertEquals(before, assertNotNull(api.savedTargets()).targets.map { it.name })
    }

    @Test
    fun `the session refuses a Viewer without asking the Master`() = runBlocking<Unit> {
        val session = ObservatorySession(master("viewer").connection(identity, MockMaster.TOKEN), scope)
        session.start()
        assertTrue(eventually { session.snapshot.value.role == Role.Viewer })
        val before = assertNotNull(session.savedTargets()).targets.map { it.name }
        assertEquals("Only an Operator can remove a saved target.", session.removeSavedTarget(before.first()))
        assertEquals(before, assertNotNull(session.savedTargets()).targets.map { it.name })
        session.stop()
    }
}
