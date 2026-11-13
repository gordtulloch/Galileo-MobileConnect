package com.galileo.mobileconnect.core.vectors

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared vectors (MAPP-PLT-040). Every file in `api/vectors/` is either replayed through the real client code or
 * listed in [pending]: a vector the Kotlin runner can't yet express is never silently skipped, and the pending list
 * must be kept exact, so finishing a component forces the matching vector to be switched on.
 */
class VectorTest {
    private fun load(file: java.io.File) = Json.parseToJsonElement(file.readText()) as JsonObject

    private val pending = emptySet<String>()

    @Test
    fun `there are vectors to replay`() {
        assertTrue(VectorFiles.all().size >= 10)
    }

    @Test
    fun `the pending list is exactly the vectors this runner cannot express yet`() {
        val unsupported = VectorFiles.all().filterNot { VectorRunner.supports(load(it)) }.map { it.nameWithoutExtension }.toSet()
        assertEquals(pending, unsupported)
    }

    @TestFactory
    fun `MAPP-PLT-040 supported vectors replay`(): List<DynamicTest> =
        VectorFiles.all().map { it to load(it) }.filter { VectorRunner.supports(it.second) }.map { (file, vector) ->
            DynamicTest.dynamicTest(file.nameWithoutExtension) { runBlocking { VectorRunner(vector).run() } }
        }
}
