package com.galileo.mobileconnect.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ChartTest {
    @Test
    fun `ticks land on round numbers`() {
        assertEquals(listOf(-2.0, -1.0, 0.0, 1.0, 2.0), niceTicks(-2.0, 2.0))
        assertEquals(listOf(0.0, 5.0, 10.0), niceTicks(0.0, 10.0, 2))
        assertEquals(listOf(5.0), niceTicks(5.0, 5.0))
    }

    @Test
    fun `labels use only the digits the step needs`() {
        assertEquals("2", tickLabel(2.0, 1.0))
        assertEquals("0.5", tickLabel(0.5, 0.5))
    }
}
